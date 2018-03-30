/*
 * Copyright 2011-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mycelium.spvmodule.dash;

import android.text.format.DateUtils;

import com.mycelium.spvmodule.TransactionFee;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.CoinDefinition;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;

/**
 * @author Andreas Schildbach
 */
public final class Constants {

    public static final boolean TEST = BuildConfig.APPLICATION_ID.contains("testnet");

    /**
     * Network this wallet is on (e.g. testnet or mainnet).
     */
    public static final NetworkParameters NETWORK_PARAMETERS = TEST ? TestNet3Params.get() : MainNetParams.get();

    /**
     * Bitcoinj global context.
     */
    public static final Context CONTEXT = new Context(NETWORK_PARAMETERS);

    public final static class Files {

        private static final String FILENAME_NETWORK_SUFFIX = NETWORK_PARAMETERS.getId()
                .equals(NetworkParameters.ID_MAINNET) ? "" : "-testnet";

        /**
         * Filename of the wallet.
         */
        public static final String WALLET_FILENAME_PROTOBUF = "wallet-protobuf" + FILENAME_NETWORK_SUFFIX;

        /**
         * How often the wallet is autosaved.
         */
        public static final long WALLET_AUTOSAVE_DELAY_MS = 5 * DateUtils.SECOND_IN_MILLIS;

        /**
         * Filename of the automatic key backup (old format, can only be read).
         */
        public static final String WALLET_KEY_BACKUP_BASE58 = "key-backup-base58" + FILENAME_NETWORK_SUFFIX;

        /**
         * Filename of the automatic wallet backup.
         */
        public static final String WALLET_KEY_BACKUP_PROTOBUF = "key-backup-protobuf" + FILENAME_NETWORK_SUFFIX;

        /**
         * Filename of the block store for storing the chain.
         */
        public static final String BLOCKCHAIN_FILENAME = "blockchain" + FILENAME_NETWORK_SUFFIX;

        /**
         * Filename of the block checkpoints file.
         */
        public static final String CHECKPOINTS_FILENAME = "checkpoints" + FILENAME_NETWORK_SUFFIX + ".txt";
    }

    /**
     * Maximum size of backups. Files larger will be rejected.
     */
    public static final long BACKUP_MAX_CHARS = 10000000;

    /**
     * User-agent to use for network access.
     */
    public static final String USER_AGENT = CoinDefinition.coinName + " Wallet";

    public static final char CHAR_THIN_SPACE = '\u2009';

    public static final int PEER_DISCOVERY_TIMEOUT_MS = 10 * (int) DateUtils.SECOND_IN_MILLIS;
    public static final int PEER_TIMEOUT_MS = 15 * (int) DateUtils.SECOND_IN_MILLIS;

    public static final long LAST_USAGE_THRESHOLD_JUST_MS = DateUtils.HOUR_IN_MILLIS;
    public static final long LAST_USAGE_THRESHOLD_RECENTLY_MS = 2 * DateUtils.DAY_IN_MILLIS;

    public static final int MEMORY_CLASS_LOWEND = 64;

    //Dash Specific
    public static long EARLIEST_HD_SEED_CREATION_TIME = 1427610960L;

    public static final String BIP39_WORDLIST_FILENAME = "bip39-wordlist.txt";

    public static Coin minerFeeValue(TransactionFee minerFee) {
        long minerFeeValue;
        switch (minerFee) {
            case LOW_PRIORITY: {
                minerFeeValue = 0L;
                break;
            }
            case ECONOMIC: {
                minerFeeValue = 500L;
                break;
            }
            case NORMAL: {
                minerFeeValue = 1000L;
                break;
            }
            case PRIORITY: {
                minerFeeValue = 2000L;
                break;
            }
            default: {
                throw new IllegalArgumentException("Unsupported fee " + minerFee);
            }
        }
        return Coin.valueOf(minerFeeValue);
    }

    public static final String QR_ADDRESS_PREFIX = "dash:";

    public static final String COIN_SYMBOL = "DASH";
}
