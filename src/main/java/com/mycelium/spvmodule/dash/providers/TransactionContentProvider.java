package com.mycelium.spvmodule.dash.providers;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.common.base.Optional;
import com.mycelium.modularizationtools.CommunicationManager;
import com.mycelium.spvmodule.TransactionFee;
import com.mycelium.spvmodule.dash.BuildConfig;
import com.mycelium.spvmodule.dash.Constants;
import com.mycelium.spvmodule.dash.WalletManager;
import com.mycelium.spvmodule.dash.providers.data.AccountBalanceCursor;
import com.mycelium.spvmodule.dash.providers.data.CurrentReceiveAddressCursor;
import com.mycelium.spvmodule.dash.providers.data.TransactionDetailsCursor;
import com.mycelium.spvmodule.dash.providers.data.TransactionsSummaryCursor;
import com.mycelium.spvmodule.dash.providers.data.model.TransactionDetails;
import com.mycelium.spvmodule.dash.providers.data.model.TransactionSummary;
import com.mycelium.spvmodule.providers.TransactionContract;
import com.mycelium.spvmodule.providers.data.CalculateMaxSpendableCursor;
import com.mycelium.spvmodule.providers.data.CheckSendAmountCursor;
import com.mycelium.spvmodule.providers.data.ValidateQrCodeCursor;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.bitcoinj.core.Context.propagate;

public class TransactionContentProvider extends ContentProvider {

    private static final Logger log = LoggerFactory.getLogger(TransactionContentProvider.class);

    private CommunicationManager communicationManager;

    private static final UriMatcher URI_MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    private static final int TRANSACTION_SUMMARY_LIST = 1;
    private static final int TRANSACTION_SUMMARY_ID = 2;
    private static final int TRANSACTION_DETAILS_LIST = 3;
    private static final int TRANSACTION_DETAILS_ID = 4;
    private static final int ACCOUNT_BALANCE_LIST = 5;
    private static final int ACCOUNT_BALANCE_ID = 6;
    private static final int CURRENT_RECEIVE_ADDRESS_LIST = 7;
    private static final int CURRENT_RECEIVE_ADDRESS_ID = 8;
    private static final int VALIDATE_QR_CODE_ID = 9;
    private static final int CALCULATE_MAX_SPENDABLE_CODE_ID = 10;
    private static final int CHECK_SEND_AMOUNT_ID = 11;

    static {
        String auth = TransactionContract.AUTHORITY(BuildConfig.APPLICATION_ID);
        URI_MATCHER.addURI(auth, TransactionContract.TransactionSummary.TABLE_NAME, TRANSACTION_SUMMARY_LIST);
        URI_MATCHER.addURI(auth, TransactionContract.TransactionSummary.TABLE_NAME + "/*", TRANSACTION_SUMMARY_ID);
        URI_MATCHER.addURI(auth, TransactionContract.TransactionDetails.TABLE_NAME, TRANSACTION_DETAILS_LIST);
        URI_MATCHER.addURI(auth, TransactionContract.TransactionDetails.TABLE_NAME + "/*", TRANSACTION_DETAILS_ID);
        URI_MATCHER.addURI(auth, TransactionContract.AccountBalance.TABLE_NAME, ACCOUNT_BALANCE_LIST);
        URI_MATCHER.addURI(auth, TransactionContract.AccountBalance.TABLE_NAME + "/*", ACCOUNT_BALANCE_ID);
        URI_MATCHER.addURI(auth, TransactionContract.CurrentReceiveAddress.TABLE_NAME, CURRENT_RECEIVE_ADDRESS_LIST);
        URI_MATCHER.addURI(auth, TransactionContract.CurrentReceiveAddress.TABLE_NAME + "/*", CURRENT_RECEIVE_ADDRESS_ID);
        URI_MATCHER.addURI(auth, TransactionContract.ValidateQrCode.TABLE_NAME, VALIDATE_QR_CODE_ID);
        URI_MATCHER.addURI(auth, TransactionContract.CalculateMaxSpendable.TABLE_NAME, CALCULATE_MAX_SPENDABLE_CODE_ID);
        URI_MATCHER.addURI(auth, TransactionContract.CheckSendAmount.TABLE_NAME, CHECK_SEND_AMOUNT_ID);
    }

    private String getTableFromMatch(int match) {
        switch (match) {
            case TRANSACTION_SUMMARY_LIST:
            case TRANSACTION_SUMMARY_ID: {
                return TransactionContract.TransactionSummary.TABLE_NAME;
            }
            case TRANSACTION_DETAILS_LIST:
            case TRANSACTION_DETAILS_ID: {
                return TransactionContract.TransactionDetails.TABLE_NAME;
            }
            case ACCOUNT_BALANCE_LIST:
            case ACCOUNT_BALANCE_ID: {
                return TransactionContract.AccountBalance.TABLE_NAME;
            }
            case CURRENT_RECEIVE_ADDRESS_LIST:
            case CURRENT_RECEIVE_ADDRESS_ID: {
                return TransactionContract.CurrentReceiveAddress.TABLE_NAME;
            }
            case VALIDATE_QR_CODE_ID: {
                return TransactionContract.ValidateQrCode.TABLE_NAME;
            }
            case CALCULATE_MAX_SPENDABLE_CODE_ID: {
                return TransactionContract.CalculateMaxSpendable.TABLE_NAME;
            }
            case CHECK_SEND_AMOUNT_ID: {
                return TransactionContract.CheckSendAmount.TABLE_NAME;
            }
            default: {
                throw new IllegalArgumentException("Unknown match " + match);
            }
        }
    }

    @Override
    public boolean onCreate() {
        communicationManager = CommunicationManager.getInstance(getContext());
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        checkSignature();
        propagate(Constants.CONTEXT);

        if (!WalletManager.isInitialized()) {
            return null;
        }

        WalletManager walletManager = WalletManager.getInstance();
        if (walletManager.isWalletReady()) {
            if (selection == null || selectionArgs == null || !selection.startsWith(TransactionContract.TransactionSummary.SELECTION_ACCOUNT_INDEX)) {
                throw new IllegalArgumentException("selection has to define accountIndex");     //FIXME does it make sense to define separate SELECTION_ACCOUNT_INDEX for each table?
            }
            int accountIndex = Integer.parseInt(selectionArgs[0]);
            int match = URI_MATCHER.match(uri);
            Wallet wallet = walletManager.getWallet();
            switch (match) {
                case TRANSACTION_SUMMARY_LIST: {
                    return handleTransactionSummaryList(wallet);
                }
                case TRANSACTION_DETAILS_ID: {
                    return handleTransactionDetails(wallet, uri);
                }
                case ACCOUNT_BALANCE_LIST:
                case ACCOUNT_BALANCE_ID: {
                    return handleAccountBalance(wallet, accountIndex);
                }
                case CURRENT_RECEIVE_ADDRESS_ID:
                case CURRENT_RECEIVE_ADDRESS_LIST: {
                    return currentReceiveAddress(wallet, accountIndex);
                }
                case VALIDATE_QR_CODE_ID: {
                    return validateQrCode(selection, selectionArgs);
                }
                case CALCULATE_MAX_SPENDABLE_CODE_ID: {
                    return calculateMaxSpendable(wallet, selection, selectionArgs);
                }
                case CHECK_SEND_AMOUNT_ID: {
                    return checkSendAmount(wallet, selection, selectionArgs);
                }
                default: {
                    // Do nothing.
                }
            }
        }
        return null;
    }

    private CheckSendAmountCursor checkSendAmount(Wallet wallet, String selection, String[] selectionArgs) {
        CheckSendAmountCursor cursor = new CheckSendAmountCursor();
        if (TransactionContract.CheckSendAmount.SELECTION_COMPLETE.equals(selection)) {
            String minerFeeStr = selectionArgs[1];
            String txFeeFactorStr = selectionArgs[2];
            TransactionFee txFee = TransactionFee.valueOf(minerFeeStr);
            String amountToSendStr = selectionArgs[3];
            long amountToSend = Long.parseLong(amountToSendStr);
            String checkSendAmountResult = checkSendAmount(wallet, txFee, amountToSend);

            List<Object> columnValues = new ArrayList<>();
            columnValues.add(txFee);                    //TransactionContract.CheckSendAmount.TX_FEE
            columnValues.add(txFeeFactorStr);           //TransactionContract.CheckSendAmount.TX_FEE_FACTOR
            columnValues.add(amountToSend);             //TransactionContract.CheckSendAmount.AMOUNT_TO_SEND
            columnValues.add(checkSendAmountResult);    //TransactionContract.CheckSendAmount.RESULT
            cursor.addRow(columnValues);
            return cursor;
        }
        return null;
    }

    private String checkSendAmount(Wallet wallet, TransactionFee minerFee, long amountToSend) {
        log.info("checkSendAmount, minerFee = {}, amountToSend = {}", minerFee, amountToSend);
        Address address = getNullAddress(wallet.getNetworkParameters());
        Coin amount = Coin.valueOf(amountToSend);
        SendRequest sendRequest = SendRequest.to(address, amount);
        sendRequest.feePerKb = Constants.minerFeeValue(minerFee);

        try {
            wallet.completeTx(sendRequest);
            return TransactionContract.CheckSendAmount.Result.RESULT_OK.name();
        } catch (InsufficientMoneyException ex) {
            return TransactionContract.CheckSendAmount.Result.RESULT_NOT_ENOUGH_FUNDS.name();
        } catch (Exception ex) {
            return TransactionContract.CheckSendAmount.Result.RESULT_INVALID.name();
        }
    }

    private Address getNullAddress(org.bitcoinj.core.NetworkParameters network) {
        int numAddressBytes = 20;
        byte[] bytes = new byte[numAddressBytes];
        return new Address(network, bytes);
    }

    private CalculateMaxSpendableCursor calculateMaxSpendable(Wallet wallet, String selection, String[] selectionArgs) {
        CalculateMaxSpendableCursor cursor = new CalculateMaxSpendableCursor();
        if (selection.equals(TransactionContract.CalculateMaxSpendable.SELECTION_COMPLETE)) {
            String minerFeeStr = selectionArgs[1];
            String txFeeFactor = selectionArgs[1];
            TransactionFee txFee = TransactionFee.valueOf(minerFeeStr);
            Coin maxSpendableAmount = calculateMaxSpendableAmount(wallet, txFee);

            List<Object> columnValues = new ArrayList<>();
            columnValues.add(txFee);
            columnValues.add(txFeeFactor);
            columnValues.add(maxSpendableAmount);       //TransactionContract.CalculateMaxSpendable.MAX_SPENDABLE
            cursor.addRow(columnValues);
            return cursor;
        }
        return null;
    }

    private Coin calculateMaxSpendableAmount(Wallet wallet, TransactionFee minerFee) {
        log.info("calculateMaxSpendableAmount, minerFee = {}", minerFee);
        Coin txFee = Constants.minerFeeValue(minerFee);
        Coin balance = wallet.getBalance();
        return balance.subtract(txFee);
    }

    private Cursor validateQrCode(String selection, String[] selectionArgs) {
        ValidateQrCodeCursor cursor = new ValidateQrCodeCursor();
        if (selection.endsWith(TransactionContract.ValidateQrCode.SELECTION_QR_CODE)) {
            String qrCode = selectionArgs[1];
            boolean isValid = isValid(qrCode);

            List<Object> columnValues = new ArrayList<>();
            columnValues.add(qrCode);                           //TransactionContract.ValidateQrCode.QR_CODE
            columnValues.add(isValid ? 1 : 0);                  //TransactionContract.ValidateQrCode.IS_VALID
            cursor.addRow(columnValues);
            return cursor;
        }
        return null;
    }

    private boolean isValid(String qrCode) {
        log.info("isValid, qrCode = {}", qrCode);
        try {
            if (qrCode.startsWith("dash:")) {
                String rawAddress = qrCode.replaceFirst("dash:", "");
                org.bitcoinj.core.Address.fromBase58(Constants.NETWORK_PARAMETERS, rawAddress);
                return true;
            }
        } catch (Exception ex) {
            // ignore
        }
        return false;
    }

    private Cursor currentReceiveAddress(Wallet wallet, int accountIndex) {
        CurrentReceiveAddressCursor cursor = new CurrentReceiveAddressCursor();

        List<Object> columnValues = new ArrayList<>();
        columnValues.add(accountIndex);                                 //TransactionContract.CurrentReceiveAddress._ID
        columnValues.add(getAccountCurrentReceiveAddress(wallet));      //TransactionContract.CurrentReceiveAddress.ADDRESS
        cursor.addRow(columnValues);
        return cursor;
    }

    private Cursor handleAccountBalance(Wallet wallet, int accountIndex) {
        AccountBalanceCursor cursor = new AccountBalanceCursor();

        List<Object> columnValues = new ArrayList<>();
        columnValues.add(accountIndex);                   //TransactionContract.AccountBalance._ID
        columnValues.add(getAccountBalance(wallet));      //TransactionContract.AccountBalance.CONFIRMED
        columnValues.add(getAccountSending(wallet));      //TransactionContract.AccountBalance.SENDING
        columnValues.add(getAccountReceiving(wallet));    //TransactionContract.AccountBalance.RECEIVING
        cursor.addRow(columnValues);
        return cursor;
    }

    private long getAccountBalance(Wallet wallet) {
        log.info("getAccountBalance");
        return wallet.getBalance(Wallet.BalanceType.ESTIMATED).getValue();
    }

    private long getAccountSending(Wallet wallet) {
        log.info("getAccountSending");
        long sending = 0L;
        for (Transaction pendingTransaction : wallet.getPendingTransactions()) {
            Coin received = pendingTransaction.getValueSentToMe(wallet);
            Coin netSent = pendingTransaction.getValueSentFromMe(wallet).minus(received);
            sending += netSent.isPositive() ? netSent.value : 0;
        }
        return sending;
    }

    private long getAccountReceiving(Wallet wallet) {
        log.info("getAccountReceiving");
        long receiving = 0L;
        for (Transaction pendingTransaction : wallet.getPendingTransactions()) {
            Coin sent = pendingTransaction.getValueSentFromMe(wallet);
            Coin netReceived = pendingTransaction.getValueSentToMe(wallet).minus(sent);
            receiving += netReceived.isPositive() ? netReceived.value : 0;
        }
        return receiving;
    }

    private Address getAccountCurrentReceiveAddress(Wallet wallet) {
        log.info("getAccountCurrentReceiveAddress");
        Address currentReceiveAddress = wallet.currentReceiveAddress();
        if (currentReceiveAddress != null) {
            return currentReceiveAddress;
        } else {
            return wallet.freshReceiveAddress();
        }
    }

    public static void notifyCurrentReceiveAddress(Context context) {
        String packageName = context.getPackageName();
        Uri contentUri = TransactionContract.CurrentReceiveAddress.CONTENT_URI(packageName);
        context.getContentResolver().notifyChange(contentUri, null);
    }

    private TransactionsSummaryCursor handleTransactionSummaryList(Wallet wallet) {
        log.info("query, TRANSACTION_SUMMARY_LIST");
        List<TransactionSummary> transactionsSummary = getTransactionsSummary(wallet);
        TransactionsSummaryCursor cursor = new TransactionsSummaryCursor(transactionsSummary.size());
        for (TransactionSummary rowItem : transactionsSummary) {
            List<Object> columnValues = new ArrayList<>();
            columnValues.add(rowItem.txid.toString());                              //TransactionContract.TransactionSummary._ID
            columnValues.add(rowItem.value.toPlainString());                        //TransactionContract.TransactionSummary.VALUE
            columnValues.add(rowItem.isIncoming ? 1 : 0);                           //TransactionContract.TransactionSummary.IS_INCOMING
            columnValues.add(rowItem.time);                                         //TransactionContract.TransactionSummary.TIME
            columnValues.add(rowItem.height);                                       //TransactionContract.TransactionSummary.HEIGHT
            columnValues.add(rowItem.confirmations);                                //TransactionContract.TransactionSummary.CONFIRMATIONS
            columnValues.add(rowItem.isQueuedOutgoing ? 1 : 0);                     //TransactionContract.TransactionSummary.IS_QUEUED_OUTGOING

            //FIXME do we need those values? (com.mycelium.wapi.model.TransactionSummary.confirmationRiskProfile [ConfirmationRiskProfileLocal])
            columnValues.add(-1);
            columnValues.add(null);
            columnValues.add(null);

            boolean isDestAddressPresent = rowItem.destinationAddress.isPresent();
            columnValues.add(isDestAddressPresent ? rowItem.destinationAddress.get().toString() : null); //TransactionContract.TransactionSummary.DESTINATION_ADDRESS
            StringBuilder addressesBuilder = new StringBuilder();
            for (Address addr : rowItem.toAddresses) {
                if (addressesBuilder.length() > 0) {
                    addressesBuilder.append(",");
                }
                addressesBuilder.append(addr.toString());
            }
            columnValues.add(addressesBuilder.toString());                          //TransactionContract.TransactionSummary.TO_ADDRESSES
            cursor.addRow(columnValues);
        }
        return cursor;
    }

    private List<TransactionSummary> getTransactionsSummary(Wallet wallet) {
        propagate(Constants.CONTEXT);
        log.info("getTransactionsSummary");
        ArrayList<TransactionSummary> transactionsSummary = new ArrayList<>();

        List<Transaction> transactions = new ArrayList<>(wallet.getTransactions(false));
        Collections.sort(transactions, new Comparator<Transaction>() {
            @Override
            public int compare(Transaction o1, Transaction o2) {
                return o2.getUpdateTime().compareTo(o1.getUpdateTime());
            }
        });
        for (Transaction dashjTransaction : transactions) {

            List<Address> toAddresses = new ArrayList<>();
            Address destAddress = null;

            for (TransactionOutput transactionOutput : dashjTransaction.getOutputs()) {
                Address toAddress = Address.fromBase58(
                        wallet.getNetworkParameters(),
                        transactionOutput
                                .getScriptPubKey()
                                .getToAddress(wallet.getNetworkParameters())
                                .toBase58()
                );
                if (!transactionOutput.isMine(wallet)) {
                    destAddress = toAddress;
                }
                if (toAddress != getNullAddress(wallet.getNetworkParameters())) {
                    toAddresses.add(toAddress);
                }
            }

            int confirmations = dashjTransaction.getConfidence().getDepthInBlocks();
            boolean isQueuedOutgoing = false; //FIXME Change the UI so MBW understand BitcoinJ confidence type.
            Optional<Address> destAddressOptional;
            if (destAddress != null) {
                destAddressOptional = Optional.of(destAddress);
            } else {
                destAddressOptional = Optional.absent();
            }
            Coin dashjValue = dashjTransaction.getValue(wallet);
            boolean isIncoming;
            if (dashjValue.isPositive()) {
                isIncoming = true;
            } else {
                isIncoming = false;
                dashjValue = dashjValue.negate();
            }
            int height = dashjTransaction.getConfidence().getDepthInBlocks();
            TransactionSummary transactionSummary = new TransactionSummary(
                    dashjTransaction.getHash(),
                    dashjValue, isIncoming,
                    dashjTransaction.getUpdateTime().getTime() / 1000,
                    height, confirmations,
                    isQueuedOutgoing, destAddressOptional, toAddresses
            );
            transactionsSummary.add(transactionSummary);
        }
        return transactionsSummary;
    }

    private Cursor handleTransactionDetails(Wallet wallet, Uri uri) {
        log.info("getTransactionDetails, uri = " + uri);

        TransactionDetailsCursor cursor = new TransactionDetailsCursor();
        String hash = uri.getLastPathSegment();
        TransactionDetails transactionDetails = getTransactionDetails(wallet, hash);
        if (transactionDetails == null) {
            return null;
        }

        List<Object> columnValues = new ArrayList<>();
        columnValues.add(transactionDetails.hash.toString());   //TransactionContract.Transaction._ID
        columnValues.add(transactionDetails.height);            //TransactionContract.Transaction.HEIGHT
        columnValues.add(transactionDetails.time);              //TransactionContract.Transaction.TIME
        columnValues.add(transactionDetails.rawSize);           //TransactionContract.Transaction.RAW_SIZE
        StringBuilder inputsBuilder = new StringBuilder();
        for (TransactionDetails.Item input : transactionDetails.inputs) {
            if (inputsBuilder.length() > 0) {
                inputsBuilder.append(",");
            }
            inputsBuilder.append(input.value + " BTC");   //FIXME we should get rid of 'BTC' over here
            inputsBuilder.append(input.address.toString());
        }
        columnValues.add(inputsBuilder.toString());             //TransactionContract.Transaction.INPUTS

        StringBuilder outputsBuilder = new StringBuilder();
        for (TransactionDetails.Item output : transactionDetails.outputs) {
            if (outputsBuilder.length() > 0) {
                outputsBuilder.append(",");
            }
            outputsBuilder.append(output.value + " BTC");   //FIXME we should get rid of 'BTC' over here
            outputsBuilder.append(output.address.toString());
        }
        columnValues.add(outputsBuilder.toString());             //TransactionContract.Transaction.OUTPUTS
        cursor.addRow(columnValues);
        return cursor;
    }

    private TransactionDetails getTransactionDetails(Wallet wallet, String hashStr) {
        Sha256Hash hash = Sha256Hash.wrap(hashStr);
        Transaction dashjTransaction = wallet.getTransaction(hash);
        if (dashjTransaction == null) {
            return null;
        }

        List<TransactionDetails.Item> inputs = new ArrayList<>();
        for (TransactionInput input : dashjTransaction.getInputs()) {
            TransactionOutput connectedOutput = input.getOutpoint().getConnectedOutput();
            Coin value = input.getValue();

            Address address;
            if (connectedOutput == null) {
                address = getNullAddress(wallet.getNetworkParameters());
            } else {
                address = connectedOutput.getScriptPubKey().getToAddress(wallet.getNetworkParameters());
            }
            TransactionDetails.Item item = new TransactionDetails.Item(
                    address,
                    value != null ? value.getValue() : 0L,
                    input.isCoinBase());
            inputs.add(item);
        }

        List<TransactionDetails.Item> outputs = new ArrayList<>();
        for (TransactionOutput output : dashjTransaction.getOutputs()) {
            Address address = output.getScriptPubKey().getToAddress(wallet.getNetworkParameters());
            Coin value = output.getValue();
            TransactionDetails.Item item = new TransactionDetails.Item(
                    address,
                    value != null ? value.getValue() : 0L,
                    false);
            outputs.add(item);
        }

        int height = dashjTransaction.getConfidence().getDepthInBlocks();
        return new TransactionDetails(
                hash,
                height,
                (int) (dashjTransaction.getUpdateTime().getTime() / 1000),
                inputs.toArray(new TransactionDetails.Item[inputs.size()]),
                outputs.toArray(new TransactionDetails.Item[outputs.size()]),
                dashjTransaction.getOptimalEncodingMessageSize());
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        checkSignature();
        switch (URI_MATCHER.match(uri)) {
            case TRANSACTION_SUMMARY_LIST:
            case TRANSACTION_SUMMARY_ID: {
                return TransactionContract.TransactionSummary.CONTENT_TYPE;
            }
            case TRANSACTION_DETAILS_LIST:
            case TRANSACTION_DETAILS_ID: {
                return TransactionContract.TransactionDetails.CONTENT_TYPE;
            }
            case ACCOUNT_BALANCE_LIST:
            case ACCOUNT_BALANCE_ID: {
                return TransactionContract.AccountBalance.CONTENT_TYPE;
            }
            case VALIDATE_QR_CODE_ID: {
                return TransactionContract.ValidateQrCode.CONTENT_TYPE;
            }
            case CALCULATE_MAX_SPENDABLE_CODE_ID: {
                return TransactionContract.CalculateMaxSpendable.CONTENT_TYPE;
            }
            case CHECK_SEND_AMOUNT_ID: {
                return TransactionContract.CheckSendAmount.CONTENT_TYPE;
            }
            default: {
                return null;
            }
        }
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new RuntimeException("Not yet implemented");
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        throw new RuntimeException("Not yet implemented");
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        throw new RuntimeException("Not yet implemented");
    }

    private void checkSignature() {
        String callingPackage = getCallingPackage();
        if (callingPackage == null) {
            throw new IllegalStateException();
        }
        communicationManager.checkSignature(callingPackage);
    }
}
