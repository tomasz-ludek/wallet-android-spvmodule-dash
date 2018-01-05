/*
 * Copyright 2011-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mycelium.spvmodule.dash;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.support.v4.content.LocalBroadcastManager;
import android.text.format.DateUtils;
import android.util.Log;

import com.google.common.base.Stopwatch;
import com.mycelium.spvmodule.TransactionFee;
import com.mycelium.spvmodule.dash.providers.TransactionContentProvider;
import com.mycelium.spvmodule.IntentContract;
import com.mycelium.spvmodule.dash.util.ThrottlingWalletChangeListener;
import com.mycelium.spvmodule.dash.util.WalletUtils;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.CheckpointManager;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.FilteredBlock;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence.ConfidenceType;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.core.listeners.PeerConnectedEventListener;
import org.bitcoinj.core.listeners.PeerDataEventListener;
import org.bitcoinj.core.listeners.PeerDisconnectedEventListener;
import org.bitcoinj.net.discovery.MultiplexingDiscovery;
import org.bitcoinj.net.discovery.PeerDiscovery;
import org.bitcoinj.net.discovery.PeerDiscoveryException;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

public class SpvService extends android.app.Service implements BlockchainService {

    private NotificationsHelper notificationsHelper;

    private SpvDashModuleApplication application;
    private Configuration config;

    public static WalletManager walletManager;

    private BlockStore blockStore;
    private File blockChainFile;
    private BlockChain blockChain;
    @Nullable
    private PeerGroup peerGroup;

    private final Handler handler = new Handler();
    private final Handler delayHandler = new Handler();
    private WakeLock wakeLock;

    private PeerConnectivityListener peerConnectivityListener;
    private ConnectivityManager connectivityManager;
    private final Set<BlockchainState.Impediment> impediments = EnumSet.noneOf(BlockchainState.Impediment.class);

    private AtomicInteger transactionsReceived = new AtomicInteger();
    private long serviceCreatedAt;
    private boolean resetBlockchainOnShutdown = false;

    private static final int MIN_COLLECT_HISTORY = 2;
    private static final int IDLE_BLOCK_TIMEOUT_MIN = 2;
    private static final int IDLE_TRANSACTION_TIMEOUT_MIN = 9;
    private static final int MAX_HISTORY_SIZE = Math.max(IDLE_TRANSACTION_TIMEOUT_MIN, IDLE_BLOCK_TIMEOUT_MIN);
    private static final long APPWIDGET_THROTTLE_MS = DateUtils.SECOND_IN_MILLIS;
    private static final long BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS = DateUtils.SECOND_IN_MILLIS;

    private static final Logger log = LoggerFactory.getLogger(SpvService.class);

    private static final String LOG_TAG = SpvService.class.getSimpleName();

    private final ThrottlingWalletChangeListener walletEventListener = new ThrottlingWalletChangeListener(APPWIDGET_THROTTLE_MS) {

        @Override
        public void onThrottledWalletChanged() {
//            WalletBalanceWidgetProvider.updateWidgets(BlockchainServiceImpl.this, application.getWallet());
            Log.d(LOG_TAG, "onThrottledWalletChanged()");
        }

        @Override
        public void onCoinsReceived(final Wallet wallet, final Transaction tx, final Coin prevBalance,
                                    final Coin newBalance) {

            Log.d(LOG_TAG, "onCoinsReceived(...)");

            transactionsReceived.incrementAndGet();

            final int bestChainHeight = blockChain.getBestChainHeight();

            final Address address = WalletUtils.getWalletAddressOfReceived(tx, wallet);
            final Coin amount = tx.getValue(wallet);
            final ConfidenceType confidenceType = tx.getConfidence().getConfidenceType();

            handler.post(new Runnable() {
                @Override
                public void run() {
                    final boolean isReceived = amount.signum() > 0;
                    final boolean replaying = bestChainHeight < config.getBestChainHeightEver();
                    final boolean isReplayedTx = confidenceType == ConfidenceType.BUILDING && replaying;

                    if (isReceived && !isReplayedTx) {
                        notificationsHelper.notifyCoinsReceived(address, amount);
                    }
                    TransactionContentProvider.notifyCurrentReceiveAddress(getApplicationContext());
                }
            });
        }

        @Override
        public void onCoinsSent(final Wallet wallet, final Transaction tx, final Coin prevBalance,
                                final Coin newBalance) {
            transactionsReceived.incrementAndGet();
            Log.d(LOG_TAG, "onCoinsSent(...)");
        }
    };

    private final class PeerConnectivityListener
            implements PeerConnectedEventListener, PeerDisconnectedEventListener, OnSharedPreferenceChangeListener {
        private int peerCount;
        private AtomicBoolean stopped = new AtomicBoolean(false);

        public PeerConnectivityListener() {
            config.registerOnSharedPreferenceChangeListener(this);
        }

        public void stop() {
            stopped.set(true);
            config.unregisterOnSharedPreferenceChangeListener(this);
            notificationsHelper.cancelConnected();
        }

        @Override
        public void onPeerConnected(final Peer peer, final int peerCount) {
            this.peerCount = peerCount;
            changed(peerCount);
        }

        @Override
        public void onPeerDisconnected(final Peer peer, final int peerCount) {
            this.peerCount = peerCount;
            changed(peerCount);
        }

        @Override
        public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
            if (Configuration.PREFS_KEY_CONNECTIVITY_NOTIFICATION.equals(key)) {
                changed(peerCount);
            }
            if (Configuration.PREFS_KEY_INSTANTX_ENABLED.equals(key)) {
                //InstantXSystem.get(blockChain).setEnabled(sharedPreferences.getBoolean(Configuration.PREFS_KEY_INSTANTX_ENABLED, false));
            }
        }

        private void changed(final int numPeers) {
            if (stopped.get()) {
                return;
            }

            handler.post(new Runnable() {
                @Override
                public void run() {
                    final boolean connectivityNotificationEnabled = config.getConnectivityNotificationEnabled();
                    if (!connectivityNotificationEnabled || numPeers == 0) {
                        notificationsHelper.cancelConnected();
                    } else {
                        notificationsHelper.notifyConnected(numPeers);
                    }
                    // send broadcast
                    broadcastPeerState(numPeers);
                }
            });
        }
    }

    private final PeerDataEventListener blockchainDownloadListener = new DownloadProgressTracker() {
        private final AtomicLong lastMessageTime = new AtomicLong(0);

        @Override
        public void onBlocksDownloaded(final Peer peer, final Block block, final FilteredBlock filteredBlock, final int blocksLeft) {
            super.onBlocksDownloaded(peer, block, filteredBlock, blocksLeft);

            delayHandler.removeCallbacksAndMessages(null);

            final long now = System.currentTimeMillis();
            if (now - lastMessageTime.get() > BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS) {
                delayHandler.post(runnable);
            } else {
                delayHandler.postDelayed(runnable, BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS);
            }
        }

        private final Runnable runnable = new Runnable() {
            @Override
            public void run() {
                lastMessageTime.set(System.currentTimeMillis());
                config.maybeIncrementBestChainHeightEver(blockChain.getChainHead().getHeight());
                broadcastBlockchainState();
            }
        };
    };

    private final BroadcastReceiver connectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                final NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
                final boolean hasConnectivity = networkInfo != null && networkInfo.isConnected();

                if (log.isInfoEnabled()) {
                    final StringBuilder s = new StringBuilder("Active network is ")
                            .append(hasConnectivity ? "up" : "down");
                    if (networkInfo != null) {
                        s.append(", type: ").append(networkInfo.getTypeName());
                        s.append(", state: ").append(networkInfo.getState()).append('/')
                                .append(networkInfo.getDetailedState());
                        final String extraInfo = networkInfo.getExtraInfo();
                        if (extraInfo != null)
                            s.append(", extraInfo: ").append(extraInfo);
                        final String reason = networkInfo.getReason();
                        if (reason != null)
                            s.append(", reason: ").append(reason);
                    }
                    log.info(s.toString());
                }

                if (hasConnectivity) {
                    impediments.remove(BlockchainState.Impediment.NETWORK);
                } else {
                    impediments.add(BlockchainState.Impediment.NETWORK);
                }
                check();
            } else if (Intent.ACTION_DEVICE_STORAGE_LOW.equals(action)) {
                log.info("Device storage low");
                impediments.add(BlockchainState.Impediment.STORAGE);
                check();
            } else if (Intent.ACTION_DEVICE_STORAGE_OK.equals(action)) {
                log.info("Device storage ok");
                impediments.remove(BlockchainState.Impediment.STORAGE);
                check();
            }
        }

        @SuppressLint("Wakelock")
        private void check() {

            Wallet wallet = walletManager.getWallet();

            if (impediments.isEmpty() && peerGroup == null) {
                log.debug("acquiring wakelock");
                wakeLock.acquire(TimeUnit.MINUTES.toMillis(10));

                // consistency check
                final int walletLastBlockSeenHeight = wallet.getLastBlockSeenHeight();
                final int bestChainHeight = blockChain.getBestChainHeight();
                if (walletLastBlockSeenHeight != -1 && walletLastBlockSeenHeight != bestChainHeight) {
                    log.error("Wallet/blockchain out of sync: {} / {}", walletLastBlockSeenHeight, bestChainHeight);
                }

                log.info("Starting peergroup");
                peerGroup = new PeerGroup(Constants.NETWORK_PARAMETERS, blockChain);
                peerGroup.setDownloadTxDependencies(0); // recursive implementation causes StackOverflowError
                peerGroup.addWallet(wallet);
                peerGroup.setUserAgent(Constants.USER_AGENT, application.packageInfo().versionName);
                peerGroup.addConnectedEventListener(peerConnectivityListener);
                peerGroup.addDisconnectedEventListener(peerConnectivityListener);

                final int maxConnectedPeers = application.maxConnectedPeers();

                final String trustedPeerHost = config.getTrustedPeerHost();
                final boolean hasTrustedPeer = trustedPeerHost != null;

                final boolean connectTrustedPeerOnly = hasTrustedPeer && config.getTrustedPeerOnly();
                peerGroup.setMaxConnections(connectTrustedPeerOnly ? 1 : maxConnectedPeers);
                peerGroup.setConnectTimeoutMillis(Constants.PEER_TIMEOUT_MS);
                peerGroup.setPeerDiscoveryTimeoutMillis(Constants.PEER_DISCOVERY_TIMEOUT_MS);

                peerGroup.addPeerDiscovery(new PeerDiscovery() {
                    private final PeerDiscovery normalPeerDiscovery = MultiplexingDiscovery.forServices(Constants.NETWORK_PARAMETERS, 0);

                    @Override
                    public InetSocketAddress[] getPeers(final long services, final long timeoutValue,
                                                        final TimeUnit timeoutUnit) throws PeerDiscoveryException {
                        final List<InetSocketAddress> peers = new LinkedList<InetSocketAddress>();

                        boolean needsTrimPeersWorkaround = false;

                        if (hasTrustedPeer) {
                            log.info("Trusted peer '{}'{}", trustedPeerHost, (connectTrustedPeerOnly ? " only" : ""));

                            final InetSocketAddress addr = new InetSocketAddress(trustedPeerHost, Constants.NETWORK_PARAMETERS.getPort());
                            if (addr.getAddress() != null) {
                                peers.add(addr);
                                needsTrimPeersWorkaround = true;
                            }
                        }

                        if (!connectTrustedPeerOnly) {
                            peers.addAll(
                                    Arrays.asList(normalPeerDiscovery.getPeers(services, timeoutValue, timeoutUnit)));
                        }

                        // workaround because PeerGroup will shuffle peers
                        if (needsTrimPeersWorkaround) {
                            while (peers.size() >= maxConnectedPeers) {
                                peers.remove(peers.size() - 1);
                            }
                        }

                        return peers.toArray(new InetSocketAddress[0]);
                    }

                    @Override
                    public void shutdown() {
                        normalPeerDiscovery.shutdown();
                    }
                });

                // start peergroup
                peerGroup.startAsync();
                peerGroup.startBlockChainDownload(new DownloadProgressTracker());
                peerGroup.startBlockChainDownload(blockchainDownloadListener);
            } else if (!impediments.isEmpty() && peerGroup != null) {
                log.info("Stopping peergroup");
                peerGroup.removeDisconnectedEventListener(peerConnectivityListener);
                peerGroup.removeConnectedEventListener(peerConnectivityListener);
                peerGroup.removeWallet(wallet);
                peerGroup.stopAsync();
                peerGroup = null;

                log.debug("Releasing wakelock");
                wakeLock.release();
            }

            broadcastBlockchainState();
        }
    };

    private final static class ActivityHistoryEntry {
        public final int numTransactionsReceived;
        public final int numBlocksDownloaded;

        public ActivityHistoryEntry(final int numTransactionsReceived, final int numBlocksDownloaded) {
            this.numTransactionsReceived = numTransactionsReceived;
            this.numBlocksDownloaded = numBlocksDownloaded;
        }

        @Override
        public String toString() {
            return numTransactionsReceived + "/" + numBlocksDownloaded;
        }
    }

    private final BroadcastReceiver tickReceiver = new BroadcastReceiver() {
        private int lastChainHeight = 0;
        private final List<ActivityHistoryEntry> activityHistory = new LinkedList<ActivityHistoryEntry>();

        @Override
        public void onReceive(final Context context, final Intent intent) {

            final int chainHeight = blockChain.getBestChainHeight();

            if (lastChainHeight > 0) {
                final int numBlocksDownloaded = chainHeight - lastChainHeight;
                final int numTransactionsReceived = transactionsReceived.getAndSet(0);

                // push history
                activityHistory.add(0, new ActivityHistoryEntry(numTransactionsReceived, numBlocksDownloaded));

                // trim
                while (activityHistory.size() > MAX_HISTORY_SIZE)
                    activityHistory.remove(activityHistory.size() - 1);

                // print
                final StringBuilder builder = new StringBuilder();
                for (final ActivityHistoryEntry entry : activityHistory) {
                    if (builder.length() > 0) {
                        builder.append(", ");
                    }
                    builder.append(entry);
                }
                log.info("History of transactions/blocks: " + builder);

                // determine if block and transaction activity is idling
                boolean isIdle = false;
                if (activityHistory.size() >= MIN_COLLECT_HISTORY) {
                    isIdle = true;
                    for (int i = 0; i < activityHistory.size(); i++) {
                        final ActivityHistoryEntry entry = activityHistory.get(i);
                        final boolean blocksActive = entry.numBlocksDownloaded > 0 && i <= IDLE_BLOCK_TIMEOUT_MIN;
                        final boolean transactionsActive = entry.numTransactionsReceived > 0
                                && i <= IDLE_TRANSACTION_TIMEOUT_MIN;

                        if (blocksActive || transactionsActive) {
                            isIdle = false;
                            break;
                        }
                    }
                }

                // if idling, shutdown service
                if (isIdle) {
                    log.info("Idling detected, stopping service");
                    stopSelf();
                }
            }

            lastChainHeight = chainHeight;
        }
    };

    public class LocalBinder extends Binder {
        public BlockchainService getService() {
            return SpvService.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();

    @Override
    public IBinder onBind(final Intent intent) {
        log.debug(".onBind()");

        return mBinder;
    }

    @Override
    public boolean onUnbind(final Intent intent) {
        log.debug(".onUnbind()");

        return super.onUnbind(intent);
    }

    @Override
    public void onCreate() {
        serviceCreatedAt = System.currentTimeMillis();
        log.debug(".onCreate()");

        super.onCreate();

        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        application = (SpvDashModuleApplication) getApplication();
        config = application.getConfiguration();
        notificationsHelper = new NotificationsHelper(this, config);
        walletManager = WalletManager.getInstance();

        if (!walletManager.isWalletReady()) {
            log.info("Wallet not yet initialized");
            return;
        }

        final PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            final String lockName = getPackageName() + " blockchain sync";
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, lockName);
        }

        peerConnectivityListener = new PeerConnectivityListener();

        broadcastPeerState(0);

        blockChainFile = new File(getDir("blockstore", Context.MODE_PRIVATE), Constants.Files.BLOCKCHAIN_FILENAME);
        final boolean blockChainFileExists = blockChainFile.exists();

        if (!blockChainFileExists) {
            log.info("Blockchain does not exist, resetting wallet");
            walletManager.getWallet().reset();
        }

        try {
            blockStore = new SPVBlockStore(Constants.NETWORK_PARAMETERS, blockChainFile);
            blockStore.getChainHead(); // detect corruptions as early as possible

            final long earliestKeyCreationTime = walletManager.getWallet().getEarliestKeyCreationTime();

            if (!blockChainFileExists && earliestKeyCreationTime > 0 && !Constants.TEST) {
                try {
                    final Stopwatch watch = Stopwatch.createStarted();
                    final InputStream checkpointsInputStream = getAssets().open(Constants.Files.CHECKPOINTS_FILENAME);
                    CheckpointManager.checkpoint(Constants.NETWORK_PARAMETERS, checkpointsInputStream, blockStore,
                            earliestKeyCreationTime);
                    watch.stop();
                    log.info("Checkpoints loaded from '{}', took {}", Constants.Files.CHECKPOINTS_FILENAME, watch);
                } catch (final IOException x) {
                    log.error("Problem reading checkpoints, continuing without", x);
                }
            }
        } catch (final BlockStoreException x) {
            //noinspection ResultOfMethodCallIgnored
            blockChainFile.delete();
            final String msg = "Blockstore cannot be created";
            log.error(msg, x);
            throw new RuntimeException(msg, x);
        }

        try {
            blockChain = new BlockChain(Constants.NETWORK_PARAMETERS, walletManager.getWallet(), blockStore);
        } catch (final BlockStoreException x) {
            throw new Error("Blockchain cannot be created", x);
        }

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_LOW);
        intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);
        registerReceiver(connectivityReceiver, intentFilter); // implicitly start PeerGroup

        walletManager.getWallet().addCoinsReceivedEventListener(Threading.SAME_THREAD, walletEventListener);
        walletManager.getWallet().addCoinsSentEventListener(Threading.SAME_THREAD, walletEventListener);
        walletManager.getWallet().addChangeEventListener(Threading.SAME_THREAD, walletEventListener);

        registerReceiver(tickReceiver, new IntentFilter(Intent.ACTION_TIME_TICK));

        walletManager.getWallet().getContext().initDashSync(getDir("masternode", MODE_PRIVATE).getAbsolutePath());
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {

        log.debug(".onStartCommand(...)");

        if (!walletManager.isWalletReady()) {
            log.info("Wallet not yet initialized");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null) {
            log.info("Service start command: " + intent + (intent.hasExtra(Intent.EXTRA_ALARM_COUNT)
                    ? " (alarm count: " + intent.getIntExtra(Intent.EXTRA_ALARM_COUNT, 0) + ")" : ""));

            final String action = intent.getAction();
            if (BlockchainService.ACTION_CANCEL_COINS_RECEIVED.equals(action)) {

                notificationsHelper.reset();

            } else if (BlockchainService.ACTION_RESET_BLOCKCHAIN.equals(action)) {

                log.info("Will remove blockchain on service shutdown");
                resetBlockchainOnShutdown = true;
                stopSelf();

            } else if (BlockchainService.ACTION_BROADCAST_TRANSACTION.equals(action)) {

                final Sha256Hash hash = Sha256Hash
                        .wrap(intent.getByteArrayExtra(BlockchainService.ACTION_BROADCAST_TRANSACTION_HASH));
                final Transaction tx = walletManager.getWallet().getTransaction(hash);
                if (tx != null) {
                    if (peerGroup != null) {
                        log.info("Broadcasting transaction " + tx.getHashAsString());
                        peerGroup.broadcastTransaction(tx);
                    } else {
                        log.info("Peergroup not available, not broadcasting transaction " + tx.getHashAsString());
                    }
                }
            } else if (BlockchainService.ACTION_SEND_FUNDS.equals(action)) {
                String rawAddress = intent.getStringExtra(IntentContract.SendFunds.ADDRESS_EXTRA);
                long rawAmount = intent.getLongExtra(IntentContract.SendFunds.AMOUNT_EXTRA, -1);
                String txFeeStr = intent.getStringExtra(IntentContract.SendFunds.FEE_EXTRA);
                if (rawAddress.isEmpty() || rawAmount < 0 || txFeeStr == null) {
                    log.error("Could not send funds with parameters rawAddress {}, rawAmount {} and feePerKb {}.", rawAddress, rawAmount, txFeeStr);
                    return START_NOT_STICKY;
                }
                Address address = Address.fromBase58(Constants.NETWORK_PARAMETERS, rawAddress);
                Coin amount = Coin.valueOf(rawAmount);
                SendRequest sendRequest = SendRequest.to(address, amount);
                sendRequest.feePerKb = Constants.minerFeeValue(TransactionFee.valueOf(txFeeStr));

                application.broadcastTransaction(sendRequest);
            }
        } else {
            log.warn("Service restart, although it was started as non-sticky");
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        log.debug(".onDestroy()");

        SpvDashModuleApplication.scheduleStartBlockchainService(this);  //disconnect feature

        unregisterReceiver(tickReceiver);

        if (!walletManager.isWalletReady()) {
            super.onDestroy();
            return;
        }

        walletManager.getWallet().removeChangeEventListener(walletEventListener);
        walletManager.getWallet().removeCoinsSentEventListener(walletEventListener);
        walletManager.getWallet().removeCoinsReceivedEventListener(walletEventListener);

        unregisterReceiver(connectivityReceiver);

        if (peerGroup != null) {
            peerGroup.removeDisconnectedEventListener(peerConnectivityListener);
            peerGroup.removeConnectedEventListener(peerConnectivityListener);
            peerGroup.removeWallet(walletManager.getWallet());
            peerGroup.stop();

            log.info("Peergroup stopped");
        }

        peerConnectivityListener.stop();

        delayHandler.removeCallbacksAndMessages(null);

        try {
            blockStore.close();
        } catch (final BlockStoreException x) {
            throw new RuntimeException(x);
        }

        walletManager.saveWallet();

        if (wakeLock.isHeld()) {
            log.debug("Wakelock still held, releasing");
            wakeLock.release();
        }

        if (resetBlockchainOnShutdown) {
            log.info("Removing blockchain");
            //noinspection ResultOfMethodCallIgnored
            blockChainFile.delete();
        }

        super.onDestroy();
        log.info("Service was up for " + ((System.currentTimeMillis() - serviceCreatedAt) / 1000 / 60) + " minutes");
    }

    @Override
    public void onTrimMemory(final int level) {
        log.info("SpvService.onTrimMemory({}) called", level);

        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            log.warn("Low memory detected, stopping service");
            stopSelf();
        }
    }

    @Override
    public BlockchainState getBlockchainState() {
        final StoredBlock chainHead = blockChain.getChainHead();
        final Date bestChainDate = chainHead.getHeader().getTime();
        final int bestChainHeight = chainHead.getHeight();
        final boolean replaying = chainHead.getHeight() < config.getBestChainHeightEver();

        return new BlockchainState(bestChainDate, bestChainHeight, replaying, impediments);
    }

    @Override
    public List<Peer> getConnectedPeers() {
        if (peerGroup != null)
            return peerGroup.getConnectedPeers();
        else
            return null;
    }

    @Override
    public List<StoredBlock> getRecentBlocks(final int maxBlocks) {
        final List<StoredBlock> blocks = new ArrayList<StoredBlock>(maxBlocks);

        try {
            StoredBlock block = blockChain.getChainHead();

            while (block != null) {
                blocks.add(block);

                if (blocks.size() >= maxBlocks)
                    break;

                block = block.getPrev(blockStore);
            }
        } catch (final BlockStoreException x) {
            // swallow
        }

        return blocks;
    }

    private void broadcastPeerState(final int numPeers) {
        final Intent broadcast = new Intent(ACTION_PEER_STATE);
        broadcast.setPackage(getPackageName());
        broadcast.putExtra(ACTION_PEER_STATE_NUM_PEERS, numPeers);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    private void broadcastBlockchainState() {
        final Intent broadcast = new Intent(ACTION_BLOCKCHAIN_STATE);
        broadcast.setPackage(getPackageName());
        getBlockchainState().putExtras(broadcast);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);

        Intent intent = new Intent("com.mycelium.wallet.blockchainState");
        getBlockchainState().putExtras(intent);
        intent.removeExtra("impediment");   //FIXME this extra can't be deserialized by mbw since it doesn't have access to internal class BlockchainState.Impediment
        SpvDashModuleApplication.sendMbw(this, intent);
    }
}
