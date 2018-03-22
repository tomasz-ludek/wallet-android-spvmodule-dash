package com.mycelium.spvmodule.dash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.v4.content.LocalBroadcastManager;
import android.text.Html;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.mycelium.spvmodule.dash.util.WalletUtils;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Locale;

public class MainActivity extends ToolbarActivity implements View.OnClickListener, View.OnLongClickListener {

    private static final Logger log = LoggerFactory.getLogger(MainActivity.class);

    private TextView labelView;
    private TextView blockchainStateView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        init();
    }

    private void init() {
        findViewById(R.id.btn1).setOnClickListener(this);
        findViewById(R.id.btn2).setOnClickListener(this);
        findViewById(R.id.btn3).setOnClickListener(this);
        findViewById(R.id.btn3).setOnLongClickListener(this);

        findViewById(R.id.btn4).setOnClickListener(this);
        boolean showBtn4 = BuildConfig.DEBUG && !WalletManager.getInstance().isWalletReady();
        findViewById(R.id.btn4).setVisibility(showBtn4 ? View.VISIBLE : View.GONE);

        labelView = (TextView) findViewById(R.id.walletLabel);
        blockchainStateView = (TextView) findViewById(R.id.blockchainState);

        LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(this);
        broadcastManager.registerReceiver(blockchainStateReceiver,
                new IntentFilter(BlockchainService.ACTION_BLOCKCHAIN_STATE));
    }

    private final BroadcastReceiver blockchainStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent broadcast) {
            final BlockchainState blockchainState = BlockchainState.fromIntent(broadcast);
            showWalletInfo(blockchainState);
        }
    };

    public void showWalletInfo(BlockchainState blockchainState) {
        WalletManager walletManager = WalletManager.getInstance();
        if (walletManager.isWalletReady()) {
            Wallet wallet = walletManager.getWallet();
            Coin balance = wallet.getBalance();
            labelView.setText(String.format(Locale.US, "Balance: %s", balance.toFriendlyString()));
            DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            String bestChainDate = dateFormat.format(blockchainState.bestChainDate);
            String label = String.format(Locale.US, "SPV-synced to <b>%s</b>", bestChainDate);
            blockchainStateView.setText(Html.fromHtml(label));
        } else {
            labelView.setText(R.string.wallet_not_initialized);
        }
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        switch (id) {
            case R.id.btn1:
                showInfo();
                break;
            case R.id.btn2:
                showPeers();
                break;
            case R.id.btn3:

                break;
            case R.id.btn4:
                restoreDummyWallet();
                break;
        }
    }

    private void restoreDummyWallet() {
        WalletManager walletManager = WalletManager.getInstance();
        String[] dummySeed = new String[]{"erode", "bridge", "organ", "you", "often", "teach", "desert", "thrive", "spike", "pottery", "sight", "sport"};
        try {
            walletManager.restoreWalletFromSeed(this, Arrays.asList(dummySeed), Constants.NETWORK_PARAMETERS);
            findViewById(R.id.btn4).setEnabled(false);
        } catch (IOException ex) {
            log.error("Unable to restore wallet from seed!", ex);
        }
    }

    private void showPeers() {
        Intent intent = new Intent(this, NetworkMonitorActivity.class);
        startActivity(intent);
    }

    @Override
    public boolean onLongClick(View view) {
        int id = view.getId();
        switch (id) {
            case R.id.btn2:
                sendSomeCoins();
                return true;
        }
        return false;
    }

    private void showInfo() {
        WalletManager walletManager = WalletManager.getInstance();
        if (!walletManager.isWalletReady()) {
            return;
        }
        Wallet wallet = walletManager.getWallet();
        log.info("description: " + wallet.getDescription());
        log.info("balance: " + wallet.getBalance());
        log.info("fresh receive address: " + wallet.freshReceiveAddress());
        log.info("watched addresses: " + wallet.getWatchedAddresses().size());
        log.info("issued receive addresses: " + wallet.getIssuedReceiveAddresses().size());
        log.info(wallet.toString(true, true, true, null));
    }

    private void sendSomeCoins() {
//        Wallet wallet = SpvDashModuleApplication.getWallet();
        Address address = WalletUtils.fromBase58(Constants.NETWORK_PARAMETERS, "Xs7Vpu7qQsTsWaJSPzvxhit48GueyBQ2xB");
        Coin value = Coin.valueOf(100000);
        /*
        Wallet.SendRequest sendRequest = Wallet.SendRequest.to(address, value);
        Log.i(TAG, "sending " + value);
        try {
            wallet.sendCoins(sendRequest);
        } catch (InsufficientMoneyException e) {
            Log.w(TAG, e.getMessage());
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
        */
    }
}
