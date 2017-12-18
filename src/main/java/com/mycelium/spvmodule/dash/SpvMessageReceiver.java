package com.mycelium.spvmodule.dash;

import android.content.Context;
import android.content.Intent;
import android.support.annotation.NonNull;

import com.mycelium.spvmodule.IntentContract;

import org.bitcoinj.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;

/**
 * This BroadcastReceiver bridges between other apps and SpvModule. It forwards requests to the
 * SpvService.
 */
public class SpvMessageReceiver {

    private static final Logger log = LoggerFactory.getLogger(SpvDashModuleApplication.class);

    private Context context;
    private WalletManager walletManager;

    public SpvMessageReceiver(Context context) {
        this.context = context;
        this.walletManager = WalletManager.getInstance();
    }

    public void onMessage(@NonNull String callingPackageName, @NonNull Intent intent) {

        long tStart = System.currentTimeMillis();
        Intent clone = (Intent) intent.clone();
        clone.setClass(context, SpvService.class);

        String action = intent.getAction();
        if (action == null) {
            log.info("Action shouldn't be empty");
            return;
        }

        int accountIndex = intent.getIntExtra(IntentContract.ACCOUNT_INDEX_EXTRA, -1);
        switch (action) {

            case IntentContract.RequestWalletSeed.ACTION: {
                if (accountIndex == -1) {
                    log.error("No account specified. Skipping " + action);
                } else {
                    handleRequestWalletSeed(intent);
                }
                break;
            }

            case IntentContract.ReceiveTransactions.ACTION: {
                if (walletManager.isWalletReady()) {
                    Wallet wallet = WalletManager.getInstance().getWallet();
                    log.info(wallet.toString(true, true, true, null));
                } else {
                    requestPrivateKey(accountIndex);
                }
                break;
            }

            case IntentContract.SendFunds.ACTION: {
                clone.setAction(BlockchainService.ACTION_SEND_FUNDS);
                context.startService(clone);
            }

            default: {
                log.error("Unhandled action " + action);
            }
        }
    }

    private void requestPrivateKey(int accountIndex) {
        if (walletManager.isWalletReady()) {
            throw new IllegalStateException("Wallet already created");
        }
        Intent intent = new Intent(IntentContract.RequestWalletSeed.ACTION);
        intent.putExtra(IntentContract.ACCOUNT_INDEX_EXTRA, accountIndex);
        SpvDashModuleApplication.sendMbw(context, intent);
    }

    private void handleRequestWalletSeed(Intent intent) {
        final ArrayList<String> bip39Passphrase = intent.getStringArrayListExtra(IntentContract.RequestWalletSeed.BIP39_PASS_PHRASE_EXTRA);
        long creationTimeSeconds = intent.getLongExtra(IntentContract.RequestWalletSeed.CREATION_TIME_SECONDS_EXTRA, 0);
        log.info("handleRequestWalletSeed");
        try {
            walletManager.restoreWalletFromSeed(context, bip39Passphrase, Constants.NETWORK_PARAMETERS);
        } catch (IOException ex) {
            log.error("Unable to restore wallet from seed!", ex);
        }
    }
}
