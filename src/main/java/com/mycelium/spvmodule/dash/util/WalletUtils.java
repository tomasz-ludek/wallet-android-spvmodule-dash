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

package com.mycelium.spvmodule.dash.util;

import android.app.Application;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.format.DateUtils;
import android.text.style.TypefaceSpan;

import com.google.common.base.Charsets;
import com.mycelium.spvmodule.dash.Constants;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.CoinDefinition;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.DumpedPrivateKey;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.ScriptException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.WrongNetworkException;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptException;
import org.bitcoinj.wallet.KeyChainGroup;
import org.bitcoinj.wallet.UnreadableWalletException;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletProtobufSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import javax.annotation.Nullable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.android.LogcatAppender;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;

import static android.content.Context.MODE_PRIVATE;

/**
 * @author Andreas Schildbach
 */
public class WalletUtils {
    public static Editable formatAddress(final Address address, final int groupSize, final int lineSize) {
        return formatHash(address.toBase58(), groupSize, lineSize);
    }

    public static Editable formatAddress(@Nullable final String prefix, final Address address, final int groupSize, final int lineSize) {
        return formatHash(prefix, address.toString(), groupSize, lineSize, Constants.CHAR_THIN_SPACE);
    }

    public static Editable formatHash(final String address, final int groupSize, final int lineSize) {
        return formatHash(null, address, groupSize, lineSize, Constants.CHAR_THIN_SPACE);
    }

    public static long longHash(final Sha256Hash hash) {
        final byte[] bytes = hash.getBytes();

        return (bytes[31] & 0xFFl) | ((bytes[30] & 0xFFl) << 8) | ((bytes[29] & 0xFFl) << 16)
                | ((bytes[28] & 0xFFl) << 24) | ((bytes[27] & 0xFFl) << 32) | ((bytes[26] & 0xFFl) << 40)
                | ((bytes[25] & 0xFFl) << 48) | ((bytes[23] & 0xFFl) << 56);
    }

    public static Editable formatHash(@Nullable final String prefix, final String address, final int groupSize,
            final int lineSize, final char groupSeparator) {
        final SpannableStringBuilder builder = prefix != null ? new SpannableStringBuilder(prefix)
                : new SpannableStringBuilder();

        final int len = address.length();
        for (int i = 0; i < len; i += groupSize) {
            final int end = i + groupSize;
            final String part = address.substring(i, end < len ? end : len);

            builder.append(part);
            builder.setSpan(new TypefaceSpan("monospace"), builder.length() - part.length(), builder.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (end < len) {
                final boolean endOfLine = lineSize > 0 && end % lineSize == 0;
                builder.append(endOfLine ? '\n' : groupSeparator);
            }
        }

        return builder;
    }

    @Nullable
    public static Address getToAddressOfSent(final Transaction tx, final Wallet wallet) {
        for (final TransactionOutput output : tx.getOutputs()) {
            try {
                if (!output.isMine(wallet)) {
                    final Script script = output.getScriptPubKey();
                    return script.getToAddress(Constants.NETWORK_PARAMETERS, true);
                }
            } catch (final ScriptException x) {
                // swallow
            }
        }

        return null;
    }

    @Nullable
    public static Address getWalletAddressOfReceived(final Transaction tx, final Wallet wallet) {
        for (final TransactionOutput output : tx.getOutputs()) {
            try {
                if (output.isMine(wallet)) {
                    final Script script = output.getScriptPubKey();
                    return script.getToAddress(Constants.NETWORK_PARAMETERS, true);
                }
            } catch (final ScriptException x) {
                // swallow
            }
        }

        return null;
    }

    public static boolean isEntirelySelf(final Transaction tx, final Wallet wallet) {
        for (final TransactionInput input : tx.getInputs()) {
            final TransactionOutput connectedOutput = input.getConnectedOutput();
            if (connectedOutput == null || !connectedOutput.isMine(wallet))
                return false;
        }

        for (final TransactionOutput output : tx.getOutputs()) {
            if (!output.isMine(wallet))
                return false;
        }

        return true;
    }

    public static Wallet restoreWalletFromProtobufOrBase58(final InputStream is,
            final NetworkParameters expectedNetworkParameters) throws IOException {
        is.mark((int) Constants.BACKUP_MAX_CHARS);

        try {
            return restoreWalletFromProtobuf(is, expectedNetworkParameters);
        } catch (final IOException x) {
            try {
                is.reset();
                return restorePrivateKeysFromBase58(is);
            } catch (final IOException x2) {
                throw new IOException(
                        "cannot read protobuf (" + x.getMessage() + ") or base58 (" + x2.getMessage() + ")", x);
            }
        }
    }

    public static Wallet restoreWalletFromProtobuf(final InputStream is,
            final NetworkParameters expectedNetworkParameters) throws IOException {
        try {
            final Wallet wallet = new WalletProtobufSerializer().readWallet(is, true, null);

            if (!wallet.getParams().equals(expectedNetworkParameters))
                throw new IOException("bad wallet backup network parameters: " + wallet.getParams().getId());
            if (!wallet.isConsistent())
                throw new IOException("inconsistent wallet backup");

            return wallet;
        } catch (final UnreadableWalletException x) {
            throw new IOException("unreadable wallet", x);
        }
    }

    public static Wallet restorePrivateKeysFromBase58(final InputStream is) throws IOException {
        final BufferedReader keyReader = new BufferedReader(new InputStreamReader(is, Charsets.UTF_8));

        // create non-HD wallet
        final KeyChainGroup group = new KeyChainGroup(Constants.NETWORK_PARAMETERS);
        group.importKeys(WalletUtils.readKeys(keyReader, Constants.NETWORK_PARAMETERS));
        return new Wallet(Constants.NETWORK_PARAMETERS, group);
    }

    public static void writeKeys(final Writer out, final List<ECKey> keys) throws IOException {
        final DateFormat format = Iso8601Format.newDateTimeFormatT();

        out.write("# KEEP YOUR PRIVATE KEYS SAFE! Anyone who can read this can spend your "+ CoinDefinition.coinName+"s.\n");

        for (final ECKey key : keys) {
            out.write(key.getPrivateKeyEncoded(Constants.NETWORK_PARAMETERS).toBase58());
            if (key.getCreationTimeSeconds() != 0) {
                out.write(' ');
                out.write(format.format(new Date(key.getCreationTimeSeconds() * DateUtils.SECOND_IN_MILLIS)));
            }
            out.write('\n');
        }
    }

    public static List<ECKey> readKeys(final BufferedReader in, final NetworkParameters expectedNetworkParameters)
            throws IOException {
        try {
            final DateFormat format = Iso8601Format.newDateTimeFormatT();

            final List<ECKey> keys = new LinkedList<ECKey>();

            long charCount = 0;
            while (true) {
                final String line = in.readLine();
                if (line == null)
                    break; // eof
                charCount += line.length();
                if (charCount > Constants.BACKUP_MAX_CHARS)
                    throw new IOException("read more than the limit of " + Constants.BACKUP_MAX_CHARS + " characters");
                if (line.trim().isEmpty() || line.charAt(0) == '#')
                    continue; // skip comment

                final String[] parts = line.split(" ");

                final ECKey key = DumpedPrivateKey.fromBase58(expectedNetworkParameters, parts[0]).getKey();
                key.setCreationTimeSeconds(
                        parts.length >= 2 ? format.parse(parts[1]).getTime() / DateUtils.SECOND_IN_MILLIS : 0);

                keys.add(key);
            }

            return keys;
        } catch (final AddressFormatException x) {
            throw new IOException("cannot read keys", x);
        } catch (final ParseException x) {
            throw new IOException("cannot read keys", x);
        }
    }

    public static final FileFilter KEYS_FILE_FILTER = new FileFilter() {
        @Override
        public boolean accept(final File file) {
            BufferedReader reader = null;

            try {
                reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), Charsets.UTF_8));
                WalletUtils.readKeys(reader, Constants.NETWORK_PARAMETERS);

                return true;
            } catch (final IOException x) {
                return false;
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (final IOException x) {
                        // swallow
                    }
                }
            }
        }
    };

    public static final FileFilter BACKUP_FILE_FILTER = new FileFilter() {
        @Override
        public boolean accept(final File file) {
            InputStream is = null;

            try {
                is = new FileInputStream(file);
                return WalletProtobufSerializer.isWallet(is);
            } catch (final IOException x) {
                return false;
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (final IOException x) {
                        // swallow
                    }
                }
            }
        }
    };

    /**
     * Construct an address from its Base58 representation.
     *
     * @param params The expected NetworkParameters or null if you don't want validation.
     * @param base58 The textual form of the address, such as "17kzeh4N8g49GFvdDzSf8PjaPfyoD1MndL".
     * @throws AddressFormatException if the given base58 doesn't parse or the checksum is invalid
     * @throws WrongNetworkException  if the given address is valid but for a different chain (eg testnet vs mainnet)
     */
    public static Address fromBase58(@Nullable NetworkParameters params, String base58) throws IllegalStateException {
        try {
            return new Address(params, base58);
        } catch (WrongNetworkException e) {
            throw new IllegalStateException("Given address is valid but for a different chain (eg testnet vs mainnet)", e);
        } catch (AddressFormatException e) {
            throw new IllegalStateException("Given base58 doesn't parse or the checksum is invalid", e);
        }
    }

    public static void initLogging(Application application) {
        final File logDir = application.getDir("log", MODE_PRIVATE);
        final File logFile = new File(logDir, "wallet.log");

        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        final PatternLayoutEncoder filePattern = new PatternLayoutEncoder();
        filePattern.setContext(context);
        filePattern.setPattern("%d{HH:mm:ss,UTC} [%thread] %logger{0} - %msg%n");
        filePattern.start();

        final RollingFileAppender<ILoggingEvent> fileAppender = new RollingFileAppender<ILoggingEvent>();
        fileAppender.setContext(context);
        fileAppender.setFile(logFile.getAbsolutePath());

        final TimeBasedRollingPolicy<ILoggingEvent> rollingPolicy = new TimeBasedRollingPolicy<ILoggingEvent>();
        rollingPolicy.setContext(context);
        rollingPolicy.setParent(fileAppender);
        rollingPolicy.setFileNamePattern(logDir.getAbsolutePath() + "/wallet.%d{yyyy-MM-dd,UTC}.log.gz");
        rollingPolicy.setMaxHistory(7);
        rollingPolicy.start();

        fileAppender.setEncoder(filePattern);
        fileAppender.setRollingPolicy(rollingPolicy);
        fileAppender.start();

        final PatternLayoutEncoder logcatTagPattern = new PatternLayoutEncoder();
        logcatTagPattern.setContext(context);
        logcatTagPattern.setPattern("%logger{0}");
        logcatTagPattern.start();

        final PatternLayoutEncoder logcatPattern = new PatternLayoutEncoder();
        logcatPattern.setContext(context);
        logcatPattern.setPattern("[%thread] %msg%n");
        logcatPattern.start();

        final LogcatAppender logcatAppender = new LogcatAppender();
        logcatAppender.setContext(context);
        logcatAppender.setTagEncoder(logcatTagPattern);
        logcatAppender.setEncoder(logcatPattern);
        logcatAppender.start();

        final ch.qos.logback.classic.Logger log = context.getLogger(Logger.ROOT_LOGGER_NAME);
        log.addAppender(fileAppender);
        log.addAppender(logcatAppender);
        log.setLevel(Level.INFO);
    }
}
