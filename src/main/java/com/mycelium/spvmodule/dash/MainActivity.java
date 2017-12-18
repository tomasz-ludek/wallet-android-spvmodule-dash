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

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class MainActivity extends ToolbarActivity implements View.OnClickListener, View.OnLongClickListener {

    private static final String TAG = MainActivity.class.getSimpleName();

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
        }
    }

    private void showPeers() {
        Intent intent = new Intent(this, PeersActivity.class);
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
        Wallet wallet = WalletManager.getInstance().getWallet();
        Log.i(TAG, "description: " + wallet.getDescription());
        Log.i(TAG, "balance: " + wallet.getBalance());
        Log.i(TAG, "fresh receive address: " + wallet.freshReceiveAddress());
        Log.i(TAG, "watched addresses: " + wallet.getWatchedAddresses().size());
        Log.i(TAG, "issued receive addresses: " + wallet.getIssuedReceiveAddresses().size());
        Log.i(TAG, wallet.toString(true, true, true, null));
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
