package com.mycelium.spvmodule.dash.providers.data.model;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Sha256Hash;

import java.io.Serializable;

public class TransactionDetails {

    public final int height;
    public final int time;
    public final int rawSize;
    public final Sha256Hash hash;
    public final Item[] inputs;
    public final Item[] outputs;

    public TransactionDetails(Sha256Hash hash, int height, int time, Item[] inputs, Item[] outputs, int rawSize) {
        this.hash = hash;
        this.height = height;
        this.time = time;
        this.inputs = inputs;
        this.outputs = outputs;
        this.rawSize = rawSize;
    }

    public static class Item implements Serializable {
        private static final long serialVersionUID = 1L;
        public final Address address;
        public final long value;
        public final boolean isCoinbase;

        public Item(Address address, long value, boolean isCoinbase) {
            this.address = address;
            this.value = value;
            this.isCoinbase = isCoinbase;
        }
    }
}
