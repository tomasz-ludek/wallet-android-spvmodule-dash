package com.mycelium.spvmodule.dash;

import android.content.Context;

import com.google.common.base.Stopwatch;
import com.mycelium.spvmodule.dash.providers.TransactionContentProvider;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.KeyChainGroup;
import org.bitcoinj.wallet.Protos;
import org.bitcoinj.wallet.UnreadableWalletException;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletProtobufSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class WalletManager {

    private static final Logger log = LoggerFactory.getLogger(SpvDashModuleApplication.class);

    private static WalletManager instance;

    private SpvDashModuleApplication application;

    private File walletFile;
    private Wallet wallet;

    public static void initialize(SpvDashModuleApplication application) {
        if (instance != null) {
            throw new IllegalStateException("WalletManager was already initialized");
        }
        instance = new WalletManager(application);
    }

    public static WalletManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("WalletManager not initialized");
        }
        return instance;
    }

    private WalletManager(SpvDashModuleApplication application) {
        this.application = application;

        walletFile = application.getFileStreamPath(Constants.Files.WALLET_FILENAME_PROTOBUF);
        loadWalletFromProtobuf(application);
    }

    public static boolean isInitialized() {
        return instance != null;
    }

    public boolean isWalletReady() {
        return wallet != null;
    }

    public Wallet getWallet() {
        return wallet;
    }

    private void loadWalletFromProtobuf(Context context) {
        if (walletFile.exists()) {
            FileInputStream walletStream = null;
            try {
                final Stopwatch watch = Stopwatch.createStarted();
                walletStream = new FileInputStream(walletFile);
                wallet = new WalletProtobufSerializer().readWallet(walletStream);
                watch.stop();

                if (!wallet.getParams().equals(Constants.NETWORK_PARAMETERS)) {
                    throw new UnreadableWalletException("Bad wallet network parameters: " + wallet.getParams().getId());
                }

                log.info("Wallet loaded from: '{}', took {}", walletFile, watch);

            } catch (final FileNotFoundException | UnreadableWalletException x) {
                log.error("Problem loading wallet", x);
                wallet = restoreWalletFromBackup(context);
            } finally {
                closeSilently(walletStream);
            }

            if (!wallet.isConsistent()) {
                log.error("Wallet is not consistent, restoring from backup");
                wallet = restoreWalletFromBackup(context);
            }

            if (!wallet.getParams().equals(Constants.NETWORK_PARAMETERS)) {
                throw new RuntimeException("Bad wallet network parameters: " + wallet.getParams().getId());
            }

            afterLoadWallet(context);
            cleanupFiles(context);

        } else {
            log.info("Wallet was not yet initialized by Mycelium module");
        }
    }

    private void afterLoadWallet(Context context) {
        wallet.autosaveToFile(walletFile, Constants.Files.WALLET_AUTOSAVE_DELAY_MS, TimeUnit.MILLISECONDS, null);
        // clean up spam
        try {
            wallet.cleanup();
        } catch (IllegalStateException x) {
            //Catch an inconsistent exception here and reset the blockchain.  This is for loading older wallets that had
            //txes with fees that were too low or dust that were stuck and could not be sent.  In a later version
            //the fees were fixed, then those stuck transactions became inconsistant and the exception is thrown.
            if (x.getMessage().contains("Inconsistent spent tx:")) {
                File blockChainFile = new File(context.getDir("blockstore", Context.MODE_PRIVATE), Constants.Files.BLOCKCHAIN_FILENAME);
                //noinspection ResultOfMethodCallIgnored
                blockChainFile.delete();
            } else {
                throw x;
            }
        }

        // make sure there is at least one recent backup
        if (!context.getFileStreamPath(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF).exists()) {
            backupWallet(context);
        }

        wallet.getContext().initDash(true, true);
        application.startBlockchainService(true);
        TransactionContentProvider.notifyCurrentReceiveAddress(context);
    }

    private void cleanupFiles(Context context) {
        for (final String filename : context.fileList()) {
            if (filename.startsWith(Constants.Files.WALLET_KEY_BACKUP_BASE58)
                    || filename.startsWith(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF + '.')
                    || filename.endsWith(".tmp")) {
                final File file = new File(context.getFilesDir(), filename);
                log.info("Removing obsolete file: '{}'", file);
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    public void backupWallet(Context context) {
        final Stopwatch watch = Stopwatch.createStarted();
        final Protos.Wallet.Builder builder = new WalletProtobufSerializer().walletToProto(wallet).toBuilder();

        // strip redundant
        builder.clearTransaction();
        builder.clearLastSeenBlockHash();
        builder.setLastSeenBlockHeight(-1);
        builder.clearLastSeenBlockTimeSecs();
        final Protos.Wallet walletProto = builder.build();

        OutputStream os = null;
        try {
            os = context.openFileOutput(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF, Context.MODE_PRIVATE);
            walletProto.writeTo(os);
            watch.stop();
            log.info("Wallet backed up to: '{}', took {}", Constants.Files.WALLET_KEY_BACKUP_PROTOBUF, watch);
        } catch (final IOException x) {
            log.error("Problem writing wallet backup", x);
        } finally {
            closeSilently(os);
        }
    }

    public void restoreWalletFromExtendedKeyCoinTypeKey(Context context, String spendingKeyB58, long creationTimeSeconds) throws IOException {

        DeterministicKey coinTypeKey = DeterministicKey.deserializeB58(spendingKeyB58, Constants.NETWORK_PARAMETERS);
        coinTypeKey.setCreationTimeSeconds(creationTimeSeconds);
        wallet = Wallet.fromMasterKey(Constants.NETWORK_PARAMETERS, coinTypeKey, ChildNumber.ZERO);
        wallet.setKeyChainGroupLookaheadSize(20);

        if (!wallet.isConsistent()) {
            throw new IOException("Inconsistent wallet");
        }

        log.info("Wallet successfully restored from extended key coin type");

        walletFile = context.getFileStreamPath(Constants.Files.WALLET_FILENAME_PROTOBUF);
        wallet.saveToFile(walletFile);

        afterLoadWallet(context);
    }

    public void restoreWalletFromSeed(Context context, List<String> words, NetworkParameters expectedNetworkParameters) throws IOException {

        DeterministicSeed deterministicSeed = new DeterministicSeed(words, null, "", Constants.EARLIEST_HD_SEED_CREATION_TIME);
        wallet = new Wallet(Constants.NETWORK_PARAMETERS, new KeyChainGroup(Constants.NETWORK_PARAMETERS, deterministicSeed));

        if (!wallet.getParams().equals(expectedNetworkParameters)) {
            throw new IOException("Bad wallet backup network parameters: " + wallet.getParams().getId());
        }
        if (!wallet.isConsistent()) {
            throw new IOException("Inconsistent wallet");
        }

        log.info("Wallet successfully restored from seed");

        walletFile = context.getFileStreamPath(Constants.Files.WALLET_FILENAME_PROTOBUF);
        wallet.saveToFile(walletFile);

        afterLoadWallet(context);
    }

    private Wallet restoreWalletFromBackup(Context context) {
        InputStream is = null;
        String backupFilePath = Constants.Files.WALLET_KEY_BACKUP_PROTOBUF;
        try {
            is = context.openFileInput(backupFilePath);
            final Wallet wallet = new WalletProtobufSerializer().readWallet(is, true, null);
            if (!wallet.isConsistent()) {
                throw new RuntimeException("Inconsistent backup: " + backupFilePath);
            }

            application.resetBlockchain();

            log.info("Wallet restored from backup " + backupFilePath);

            return wallet;
        } catch (final IOException | UnreadableWalletException x) {
            throw new Error("Cannot read backup " + backupFilePath, x);
        } finally {
            closeSilently(is);
        }
    }

    public void saveWallet() {
        try {
            protobufSerializeWallet(wallet);
        } catch (final IOException x) {
            throw new RuntimeException(x);
        }
    }

    private void protobufSerializeWallet(final Wallet wallet) throws IOException {
        final Stopwatch watch = Stopwatch.createStarted();
        wallet.saveToFile(walletFile);
        watch.stop();

        log.info("Wallet saved to: '{}', took {}", walletFile, watch);
    }

    private void closeSilently(Closeable stream) {
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (final IOException x) {
            // swallow
        }
    }
}
