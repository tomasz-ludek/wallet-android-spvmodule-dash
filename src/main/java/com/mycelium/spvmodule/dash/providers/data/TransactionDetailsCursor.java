package com.mycelium.spvmodule.dash.providers.data;

import android.database.MatrixCursor;

import com.mycelium.spvmodule.providers.TransactionContract;

public class TransactionDetailsCursor extends MatrixCursor {

    private static String[] columnNames = {
            TransactionContract.TransactionDetails._ID, TransactionContract.TransactionDetails.HEIGHT,
            TransactionContract.TransactionDetails.TIME, TransactionContract.TransactionDetails.RAW_SIZE,
            TransactionContract.TransactionDetails.INPUTS, TransactionContract.TransactionDetails.OUTPUTS
    };

    public TransactionDetailsCursor() {
        super(columnNames, 1);
    }
}
