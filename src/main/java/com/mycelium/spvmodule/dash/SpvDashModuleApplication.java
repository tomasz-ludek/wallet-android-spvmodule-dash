package com.mycelium.spvmodule.dash;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.multidex.MultiDexApplication;
import android.text.format.DateUtils;
import android.util.Base64;

import com.crashlytics.android.Crashlytics;
import com.mycelium.modularizationtools.CommunicationManager;
import com.mycelium.modularizationtools.ModuleMessageReceiver;
import com.mycelium.spvmodule.dash.util.WalletUtils;

import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.crypto.LinuxSecureRandom;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import io.fabric.sdk.android.Fabric;

public class SpvDashModuleApplication extends MultiDexApplication implements ModuleMessageReceiver {

    private Configuration config;

    private SpvMessageReceiver spvMessageReceiver;

    private Intent blockchainServiceIntent;
    private Intent blockchainServiceCancelCoinsReceivedIntent;
    private Intent blockchainServiceResetBlockchainIntent;

    private PackageInfo packageInfo;

    private static final Logger log = LoggerFactory.getLogger(SpvDashModuleApplication.class);

    @Override
    public void onCreate() {

        new LinuxSecureRandom(); // init proper random number generator
        WalletUtils.initLogging(this);

        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectAll().permitDiskReads()
                .permitDiskWrites().penaltyLog().build());

        Threading.throwOnLockCycles();
        org.bitcoinj.core.Context.enableStrictMode();
        org.bitcoinj.core.Context.propagate(Constants.CONTEXT);

        log.info("=== starting app using configuration: {}, {}", Constants.TEST ? "test" : "prod", Constants.NETWORK_PARAMETERS.getId());

        super.onCreate();
        Fabric.with(this, new Crashlytics());

        packageInfo = packageInfoFromContext(this);

        initMnemonicCode();
        config = new Configuration(PreferenceManager.getDefaultSharedPreferences(this), getResources());

        blockchainServiceIntent = new Intent(this, SpvService.class);
        blockchainServiceCancelCoinsReceivedIntent = new Intent(SpvService.ACTION_CANCEL_COINS_RECEIVED, null, this, SpvService.class);
        blockchainServiceResetBlockchainIntent = new Intent(SpvService.ACTION_RESET_BLOCKCHAIN, null, this, SpvService.class);

        WalletManager.initialize(this);
        spvMessageReceiver = new SpvMessageReceiver(this);
    }

    private void initMnemonicCode() {
        try {
            final long start = System.currentTimeMillis();
            MnemonicCode.INSTANCE = new MnemonicCode(getAssets().open(Constants.BIP39_WORDLIST_FILENAME), null);
            log.info("BIP39 wordlist loaded from: '" + Constants.BIP39_WORDLIST_FILENAME + "', took " + (System.currentTimeMillis() - start) + "ms");
        } catch (final IOException x) {
            throw new Error(x);
        }
    }

    @Override
    public void onMessage(@NonNull final String callingPackageName, @NonNull final Intent intent) {
        log.info(String.format(Locale.US, "onMessage(%s, %s)", callingPackageName, intent.getAction()));
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                spvMessageReceiver.onMessage(callingPackageName, intent);
            }
        });
    }

    public Configuration getConfiguration() {
        return config;
    }

    public void startBlockchainService(final boolean cancelCoinsReceived) {
        if (cancelCoinsReceived) {
            startService(blockchainServiceCancelCoinsReceivedIntent);
        } else {
            startService(blockchainServiceIntent);
        }
    }

    public void stopBlockchainService() {
        stopService(blockchainServiceIntent);
    }

    public void resetBlockchain() {
        // implicitly stops blockchain service
        startService(blockchainServiceResetBlockchainIntent);
    }

    public void broadcastTransaction(SendRequest sendRequest) {
        Wallet wallet = WalletManager.getInstance().getWallet();
        try {
            log.info("sending: {}", sendRequest);
            final Transaction transaction = wallet.sendCoinsOffline(sendRequest); // can take long
            log.info("send successful, transaction committed: {}", transaction.getHashAsString());
//            wallet.completeTx(sendRequest);
            broadcastTransaction(sendRequest.tx);
        } catch (InsufficientMoneyException e) {
            log.warn("No enough funds to complete transaction", e);
        }
    }

    public void broadcastTransaction(final Transaction tx) {
        final Intent intent = new Intent(SpvService.ACTION_BROADCAST_TRANSACTION, null, this, SpvService.class);
        intent.putExtra(SpvService.ACTION_BROADCAST_TRANSACTION_HASH, tx.getHash().getBytes());
        startService(intent);
    }

    public static PackageInfo packageInfoFromContext(final Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (final PackageManager.NameNotFoundException x) {
            throw new RuntimeException(x);
        }
    }

    public PackageInfo packageInfo() {
        return packageInfo;
    }

    public boolean isLowRamDevice() {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            return activityManager.isLowRamDevice();
        } else {
            return activityManager.getMemoryClass() <= Constants.MEMORY_CLASS_LOWEND;
        }
    }

    public int maxConnectedPeers() {
        return isLowRamDevice() ? 4 : 6;
    }

    public static void scheduleStartBlockchainService(final Context context) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        final Configuration config = new Configuration(sharedPreferences, context.getResources());
        final long lastUsedAgo = config.getLastUsedAgo();

        // apply some backoff
        final long alarmInterval;
        if (lastUsedAgo < Constants.LAST_USAGE_THRESHOLD_JUST_MS) {
            alarmInterval = AlarmManager.INTERVAL_FIFTEEN_MINUTES;
        } else if (lastUsedAgo < Constants.LAST_USAGE_THRESHOLD_RECENTLY_MS) {
            alarmInterval = AlarmManager.INTERVAL_HALF_DAY;
        } else {
            alarmInterval = AlarmManager.INTERVAL_DAY;
        }

        log.info("Last used {} minutes ago, rescheduling blockchain sync in roughly {} minutes",
                lastUsedAgo / DateUtils.MINUTE_IN_MILLIS, alarmInterval / DateUtils.MINUTE_IN_MILLIS);

        final AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            final PendingIntent alarmIntent = PendingIntent.getService(context, 0,
                    new Intent(context, SpvService.class), 0);
            alarmManager.cancel(alarmIntent);
            // workaround for no inexact set() before KitKat
            final long now = System.currentTimeMillis();
            alarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP, now + alarmInterval, AlarmManager.INTERVAL_DAY, alarmIntent);
        }
    }

    public static void sendMbw(Context context, Intent intent) {
        CommunicationManager.getInstance(context.getApplicationContext()).send(getMbwModuleName(), intent);
    }

    private static String getMbwModuleName() {
        switch (BuildConfig.APPLICATION_ID) {
            case "org.dash.mycelium.spvdashmodule.debug":
            case "org.dash.mycelium.spvdashmodule": {
                throw new RuntimeException("Not yet implemented");  //FIXME return package name of Mycelium mainnet wallet
            }
            case "org.dash.mycelium.spvdashmodule.testnet": {
                return "com.mycelium.testnetwallet";
            }
            case "org.dash.mycelium.spvdashmodule.testnet.debug":{
//                return "com.mycelium.devwallet_spore";
                return "com.mycelium.testnetdigitalassets";
            }
            default: {
                throw new RuntimeException("No mbw module defined for BuildConfig " + BuildConfig.APPLICATION_ID);
            }
        }
    }
}
