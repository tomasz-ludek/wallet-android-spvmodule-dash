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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.MonetaryFormat;

import java.util.LinkedList;
import java.util.List;

import javax.annotation.Nullable;

public class NotificationsHelper {

    public static final int NOTIFICATION_ID_CONNECTED = 0;
    public static final int NOTIFICATION_ID_COINS_RECEIVED = 1;

    private Context context;
    private Configuration config;

    private NotificationManager nm;
    private int notificationCount = 0;

    private Coin notificationAccumulatedAmount = Coin.ZERO;
    private final List<Address> notificationAddresses = new LinkedList<Address>();

    public NotificationsHelper(Context context, Configuration config) {
        this.context = context;
        this.nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        this.config = config;
    }

    public void notifyCoinsReceived(@Nullable final Address address, final Coin amount) {

        if (notificationCount == 1) {
            nm.cancel(NOTIFICATION_ID_COINS_RECEIVED);
        }

        notificationCount++;
        notificationAccumulatedAmount = notificationAccumulatedAmount.add(amount);
        if (address != null && !notificationAddresses.contains(address))
            notificationAddresses.add(address);

        final MonetaryFormat btcFormat = config.getFormat();

        final String packageFlavor = applicationPackageFlavor(context);
        final String msgSuffix = packageFlavor != null ? " [" + packageFlavor + "]" : "";

        final String tickerMsg = context.getString(R.string.notification_coins_received_msg, btcFormat.format(amount))
                + msgSuffix;
        final String msg = context.getString(R.string.notification_coins_received_msg,
                btcFormat.format(notificationAccumulatedAmount)) + msgSuffix;

        final StringBuilder text = new StringBuilder();
        for (final Address notificationAddress : notificationAddresses) {
            if (text.length() > 0) {
                text.append(", ");
            }
            final String addressStr = notificationAddress.toBase58();
            text.append(addressStr);
        }

        final Notification.Builder notification = new Notification.Builder(context);
        notification.setSmallIcon(R.drawable.stat_notify_received_24dp);
        notification.setTicker(tickerMsg);
        notification.setContentTitle(msg);
        if (text.length() > 0) {
            notification.setContentText(text);
        }
        notification.setContentIntent(PendingIntent.getActivity(context, 0, new Intent(context, SpvDashModuleApplication.class), 0));
        notification.setNumber(notificationCount == 1 ? 0 : notificationCount);
        notification.setWhen(System.currentTimeMillis());
        notification.setSound(Uri.parse("android.resource://" + context.getPackageName() + "/" + R.raw.coins_received));
        nm.notify(NOTIFICATION_ID_COINS_RECEIVED, notification.getNotification());
    }

    public void reset() {
        notificationCount = 0;
        notificationAccumulatedAmount = Coin.ZERO;
        notificationAddresses.clear();

        nm.cancel(NOTIFICATION_ID_COINS_RECEIVED);
    }

    private String applicationPackageFlavor(Context context) {
        final String packageName = context.getPackageName();
        final int index = packageName.lastIndexOf('_');
        if (index != -1) {
            return packageName.substring(index + 1);
        } else {
            return null;
        }
    }

    public void notifyConnected(int numPeers) {
        final Notification.Builder notification = new Notification.Builder(context);
        notification.setSmallIcon(R.drawable.stat_sys_peers, numPeers > 4 ? 4 : numPeers);
        notification.setContentTitle(context.getString(R.string.app_name));
        notification.setContentText(context.getString(R.string.notification_peers_connected_msg, numPeers));
        notification.setContentIntent(PendingIntent.getActivity(context, 0,
                new Intent(context, PeersActivity.class), 0));
        notification.setWhen(System.currentTimeMillis());
        notification.setOngoing(true);
        nm.notify(NOTIFICATION_ID_CONNECTED, notification.getNotification());
    }

    public void cancelConnected() {
        nm.cancel(NOTIFICATION_ID_CONNECTED);
    }
}
