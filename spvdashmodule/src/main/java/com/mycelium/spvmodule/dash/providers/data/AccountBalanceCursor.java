package com.mycelium.spvmodule.dash.providers.data;

import android.database.MatrixCursor;

import com.mycelium.spvmodule.providers.TransactionContract;

public class AccountBalanceCursor extends MatrixCursor {

    private static String[] columnNames = {
            TransactionContract.AccountBalance._ID,
            TransactionContract.AccountBalance.CONFIRMED,
            TransactionContract.AccountBalance.SENDING,
            TransactionContract.AccountBalance.RECEIVING
    };

    public AccountBalanceCursor() {
        super(columnNames, 1);
    }
}
