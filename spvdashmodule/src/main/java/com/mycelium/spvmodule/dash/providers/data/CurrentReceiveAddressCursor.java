package com.mycelium.spvmodule.dash.providers.data;

import android.database.MatrixCursor;

import com.mycelium.spvmodule.providers.TransactionContract;

public class CurrentReceiveAddressCursor extends MatrixCursor {

    private static String[] columnNames = {
            TransactionContract.CurrentReceiveAddress._ID,
            TransactionContract.CurrentReceiveAddress.ADDRESS,
    };

    public CurrentReceiveAddressCursor() {
        super(columnNames, 1);
    }
}
