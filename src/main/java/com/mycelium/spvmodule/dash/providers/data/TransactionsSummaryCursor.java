package com.mycelium.spvmodule.dash.providers.data;

import android.database.MatrixCursor;

import com.mycelium.spvmodule.providers.TransactionContract;

public class TransactionsSummaryCursor extends MatrixCursor {

    private static String[] columnNames = {
            TransactionContract.TransactionSummary._ID, TransactionContract.TransactionSummary.VALUE,
            TransactionContract.TransactionSummary.SYMBOL, TransactionContract.TransactionSummary.IS_INCOMING,
            TransactionContract.TransactionSummary.TIME, TransactionContract.TransactionSummary.HEIGHT,
            TransactionContract.TransactionSummary.CONFIRMATIONS, TransactionContract.TransactionSummary.IS_QUEUED_OUTGOING,
            TransactionContract.TransactionSummary.CONFIRMATION_RISK_PROFILE_LENGTH, TransactionContract.TransactionSummary.CONFIRMATION_RISK_PROFILE_RBF_RISK,
            TransactionContract.TransactionSummary.CONFIRMATION_RISK_PROFILE_DOUBLE_SPEND, TransactionContract.TransactionSummary.DESTINATION_ADDRESS,
            TransactionContract.TransactionSummary.TO_ADDRESSES
    };

    public TransactionsSummaryCursor(int initialCapacity) {
        super(columnNames, initialCapacity);
    }
}
