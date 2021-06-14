package org.thoughtcrime.securesms.notifications;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.RemoteInput;
import androidx.core.app.TaskStackBuilder;

import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcMsg;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import org.thoughtcrime.securesms.ConversationActivity;
import org.thoughtcrime.securesms.ConversationListActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.ApplicationDcContext;
import org.thoughtcrime.securesms.contacts.avatars.ContactPhoto;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.preferences.widgets.NotificationPrivacyPreference;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.Prefs;
import org.thoughtcrime.securesms.util.Util;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class NotificationCenter {
    private static final String TAG = NotificationCenter.class.getSimpleName();
    @NonNull private ApplicationDcContext dcContext;
    @NonNull private Context context;
    private volatile int visibleChatId = 0;
    private volatile long lastAudibleNotification = 0;
    private static final long MIN_AUDIBLE_PERIOD_MILLIS = TimeUnit.SECONDS.toMillis(2);
    private final HashMap<Integer, ArrayList<String>> inboxes = new HashMap<>(); // contains the last lines of each chat

    public NotificationCenter(ApplicationDcContext dcContext) {
        this.dcContext = dcContext;
        this.context = dcContext.context.getApplicationContext();
    }

    private @Nullable Uri effectiveSound(int chatId) { // chatId=0: return app-global setting
        return Prefs.getChatRingtone(context, chatId);
    }

    private boolean effectiveVibrate(int chatId) { // chatId=0: return app-global setting
        Prefs.VibrateState vibrate = Prefs.getChatVibrate(context, chatId);
        if (vibrate == Prefs.VibrateState.ENABLED) {
            return true;
        } else if (vibrate == Prefs.VibrateState.DISABLED) {
            return false;
        }
        return Prefs.isNotificationVibrateEnabled(context);
    }

    private boolean requiresIndependentChannel(int chatId) {
        return Prefs.isChatRingtoneSet(context, chatId)
                || Prefs.getChatVibrate(context, chatId) != Prefs.VibrateState.DEFAULT;
    }

    private int getLedArgb(String ledColor) {
        int argb;
        try {
            argb = Color.parseColor(ledColor);
        }
        catch (Exception e) {
            argb = Color.rgb(0xFF, 0xFF, 0xFF);
        }
        return argb;
    }

    private PendingIntent getOpenChatlistIntent() {
        Intent intent = new Intent(context, ConversationListActivity.class);
        intent.putExtra(ConversationListActivity.CLEAR_NOTIFICATIONS, true);
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent getOpenChatIntent(int chatId) {
        Intent intent = new Intent(context, ConversationActivity.class);
        intent.putExtra(ConversationActivity.CHAT_ID_EXTRA, chatId);
        intent.setData((Uri.parse("custom://"+System.currentTimeMillis())));
        return TaskStackBuilder.create(context)
                .addNextIntentWithParentStack(intent)
                .getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent getRemoteReplyIntent(int chatId) {
        Intent intent = new Intent(RemoteReplyReceiver.REPLY_ACTION);
        intent.setClass(context, RemoteReplyReceiver.class);
        intent.setData((Uri.parse("custom://"+System.currentTimeMillis())));
        intent.putExtra(RemoteReplyReceiver.CHAT_ID_EXTRA, chatId);
        intent.setPackage(context.getPackageName());
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent getMarkAsReadIntent(int chatId, boolean markNoticed) {
        Intent intent = new Intent(markNoticed? MarkReadReceiver.MARK_NOTICED_ACTION : MarkReadReceiver.CANCEL_ACTION);
        intent.setClass(context, MarkReadReceiver.class);
        intent.setData((Uri.parse("custom://"+System.currentTimeMillis())));
        intent.putExtra(MarkReadReceiver.CHAT_ID_EXTRA, chatId);
        intent.setPackage(context.getPackageName());
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }


    // Groups and Notification channel groups
    // --------------------------------------------------------------------------------------------

    // this is just to further organize the appearance of channels in the settings UI
    private static final String CH_GRP_MSG = "chgrp_msg";

    // this is to group together notifications as such, maybe including a summary,
    // see https://developer.android.com/training/notify-user/group.html
    private static final String GRP_MSG = "grp_msg";


    // Notification IDs
    // --------------------------------------------------------------------------------------------

    public static final int ID_PERMANTENT  = 1;
    public static final int ID_MSG_SUMMARY = 2;
    public static final int ID_GENERIC     = 3;
    public static final int ID_MSG_OFFSET  = 0; // msgId is added - as msgId start at 10, there are no conflicts with lower numbers


    // Notification channels
    // --------------------------------------------------------------------------------------------

    // Overview:
    // - since SDK 26 (Oreo), a NotificationChannel is a MUST for notifications
    // - NotificationChannels are defined by a channelId
    //   and its user-editable settings have a higher precedence as the Notification.Builder setting
    // - once created, NotificationChannels cannot be modified programmatically
    // - NotificationChannels can be deleted, however, on re-creation with the same id,
    //   it becomes un-deleted with the old user-defined settings
    //
    // How we use Notification channel:
    // - We include the delta-chat-notifications settings into the name of the channelId
    // - The chatId is included only, if there are separate sound- or vibration-settings for a chat
    // - This way, we have stable and few channelIds and the user
    //   can edit the notifications in Delta Chat as well as in the system

    // channelIds: CH_MSG_* are used here, the other ones from outside (defined here to have some overview)
    public static final String CH_MSG_PREFIX = "ch_msg";
    public static final String CH_MSG_VERSION = "4";
    public static final String CH_PERMANENT = "dc_foreground_notification_ch";
    public static final String CH_GENERIC = "ch_generic";

    private boolean notificationChannelsSupported() {
        return Build.VERSION.SDK_INT >= 26;
    }

    // full name is "ch_msgV_HASH" or "ch_msgV_HASH.CHATID"
    private String computeChannelId(String ledColor, boolean vibrate, @Nullable Uri ringtone, int chatId) {
        String channelId = CH_MSG_PREFIX;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(ledColor.getBytes());
            md.update(vibrate ? (byte) 1 : (byte) 0);
            md.update((ringtone != null ? ringtone.toString() : "").getBytes());
            if (chatId!=0) {
                // for multi-account, force different channelIds for maybe the same chatIds in multiple accounts
                md.update(dcContext.getConfig("addr").getBytes());
            }
            String hash = String.format("%X", new BigInteger(1, md.digest())).substring(0, 16);

            channelId = CH_MSG_PREFIX + CH_MSG_VERSION + "_" + hash;
            if (chatId!=0) {
                channelId += String.format(".%d", chatId);
            }

        } catch(Exception e) {
            Log.e(TAG, e.toString());
        }
        return channelId;
    }

    // return chatId from "ch_msgV_HASH.CHATID" or 0
    private int parseNotificationChannelChatId(String channelId) {
        try {
            int point = channelId.lastIndexOf(".");
            if (point>0) {
                return Integer.parseInt(channelId.substring(point + 1));
            }
        } catch(Exception e) { }
        return 0;
    }

    private String getNotificationChannelGroup(NotificationManagerCompat notificationManager) {
        if (notificationChannelsSupported() && notificationManager.getNotificationChannelGroup(CH_GRP_MSG) == null) {
            NotificationChannelGroup chGrp = new NotificationChannelGroup(CH_GRP_MSG, context.getString(R.string.pref_chats));
            notificationManager.createNotificationChannelGroup(chGrp);
        }
        return CH_GRP_MSG;
    }

    private String getNotificationChannel(NotificationManagerCompat notificationManager, DcChat dcChat) {
        int chatId = dcChat.getId();
        String channelId = CH_MSG_PREFIX;

        if (notificationChannelsSupported()) {
            try {
                // get all values we'll use as settings for the NotificationChannel
                String        ledColor       = Prefs.getNotificationLedColor(context);
                boolean       defaultVibrate = effectiveVibrate(chatId);
                @Nullable Uri ringtone       = effectiveSound(chatId);
                boolean       isIndependent  = requiresIndependentChannel(chatId);

                // get channel id from these settings
                channelId = computeChannelId(ledColor, defaultVibrate, ringtone, isIndependent? chatId : 0);

                // user-visible name of the channel -
                // we just use the name of the chat or "Default"
                // (the name is shown in the context of the group "Chats" - that should be enough context)
                String name = context.getString(R.string.def);
                if (isIndependent) {
                    name = dcChat.getName();
                }

                // check if there is already a channel with the given name
                List<NotificationChannel> channels = notificationManager.getNotificationChannels();
                boolean channelExists = false;
                for (int i = 0; i < channels.size(); i++) {
                    String currChannelId = channels.get(i).getId();
                    if (currChannelId.startsWith(CH_MSG_PREFIX)) {
                        // this is one of the message channels handled here ...
                        if (currChannelId.equals(channelId)) {
                            // ... this is the actually required channel, fine :)
                            // update the name to reflect localize changes and chat renames
                            channelExists = true;
                            channels.get(i).setName(name);
                        } else {
                            // ... another message channel, delete if it is not in use.
                            int currChatId = parseNotificationChannelChatId(currChannelId);
                            if (!currChannelId.equals(computeChannelId(ledColor, effectiveVibrate(currChatId), effectiveSound(currChatId), currChatId))) {
                                notificationManager.deleteNotificationChannel(currChannelId);
                            }
                        }
                    }
                }

                // create a channel with the given settings;
                // we cannot change the settings, however, this is handled by using different values for chId
                if(!channelExists) {
                    NotificationChannel channel = new NotificationChannel(channelId, name, ringtone==null? NotificationManager.IMPORTANCE_LOW : NotificationManager.IMPORTANCE_HIGH);
                    channel.setDescription("Informs about new messages.");
                    channel.setGroup(getNotificationChannelGroup(notificationManager));
                    channel.enableVibration(defaultVibrate);
                    channel.setShowBadge(true);

                    if (!ledColor.equals("none")) {
                        channel.enableLights(true);
                        channel.setLightColor(getLedArgb(ledColor));
                    } else {
                        channel.enableLights(false);
                    }

                    if (ringtone != null) {
                        channel.setSound(ringtone,
                                new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                                        .build());
                    }

                    notificationManager.createNotificationChannel(channel);
                }
            }
            catch(Exception e) {
                e.printStackTrace();
            }
        }

        return channelId;
    }


    // add notifications & co.
    // --------------------------------------------------------------------------------------------

    public void addNotification(int chatId, int msgId) {
        Util.runOnAnyBackgroundThread(() -> {

            DcChat dcChat = dcContext.getChat(chatId);

            if (!Prefs.isNotificationsEnabled(context) || dcChat.isMuted()) {
                return;
            }

            if (chatId == visibleChatId) {
                if (Prefs.isInChatNotifications(context)) {
                    InChatSounds.getInstance(context).playIncomingSound();
                }
                return;
            }

            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);

            // get notification text as a single line
            NotificationPrivacyPreference privacy = Prefs.getNotificationPrivacy(context);

            DcMsg dcMsg = dcContext.getMsg(msgId);
            String line = privacy.isDisplayMessage()? dcMsg.getSummarytext(2000) : context.getString(R.string.notify_new_message);
            if (dcChat.isGroup() && privacy.isDisplayContact()) {
                line = dcMsg.getSenderName(dcContext.getContact(dcMsg.getFromId()), false) + ": " + line;
            }

            // play signal?
            long now = System.currentTimeMillis();
            boolean signal = (now - lastAudibleNotification) > MIN_AUDIBLE_PERIOD_MILLIS;
            if (signal) {
                lastAudibleNotification = now;
            }

            // create a basic notification
            // even without a name or message displayed,
            // it makes sense to use separate notification channels and to open the respective chat directly -
            // the user may eg. have chosen a different sound
            String notificationChannel = getNotificationChannel(notificationManager, dcChat);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, notificationChannel)
                    .setSmallIcon(R.drawable.icon_notification)
                    .setColor(context.getResources().getColor(R.color.delta_primary))
                    .setPriority(Prefs.getNotificationPriority(context))
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setOnlyAlertOnce(!signal)
                    .setContentText(line)
                    .setDeleteIntent(getMarkAsReadIntent(chatId, false))
                    .setContentIntent(getOpenChatIntent(chatId));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setGroup(GRP_MSG);
            }

            if (privacy.isDisplayContact()) {
                builder.setContentTitle(dcChat.getName());
            }

            // if privacy allows, for better accessibility,
            // prepend the sender in the ticker also for one-to-one chats (for group-chats, this is already done)
            String tickerLine = line;
            if (!dcChat.isGroup() && privacy.isDisplayContact()) {
                line = dcMsg.getSenderName(dcContext.getContact(dcMsg.getFromId()), false) + ": " + line;
            }
            builder.setTicker(tickerLine);

            // set sound, vibrate, led for systems that do not have notification channels
            if (!notificationChannelsSupported()) {
                if (signal) {
                    Uri sound = effectiveSound(chatId);
                    if (sound != null) {
                        builder.setSound(sound);
                    }
                    boolean vibrate = effectiveVibrate(chatId);
                    if (vibrate) {
                        builder.setDefaults(Notification.DEFAULT_VIBRATE);
                    }
                }
                String ledColor = Prefs.getNotificationLedColor(context);
                if (!ledColor.equals("none")) {
                    builder.setLights(getLedArgb(ledColor),500, 2000);
                }
            }

            // set avatar
            Recipient recipient = new Recipient(context, dcChat, null);
            if (privacy.isDisplayContact()) {
                try {
                    Drawable drawable;
                    ContactPhoto contactPhoto = recipient.getContactPhoto(context);
                    if (contactPhoto != null) {
                        drawable = GlideApp.with(context.getApplicationContext())
                                .load(contactPhoto)
                                .diskCacheStrategy(DiskCacheStrategy.NONE)
                                .circleCrop()
                                .submit(context.getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_width),
                                        context.getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_height))
                                .get();

                    } else {
                        drawable = recipient.getFallbackContactPhoto().asDrawable(context, recipient.getFallbackAvatarColor());
                    }
                    if (drawable != null) {
                        int wh = context.getResources().getDimensionPixelSize(R.dimen.contact_photo_target_size);
                        Bitmap bitmap = BitmapUtil.createFromDrawable(drawable, wh, wh);
                        if (bitmap != null) {
                            builder.setLargeIcon(bitmap);
                        }
                    }
                } catch (Exception e) { Log.w(TAG, e); }
            }

            // add buttons that allow some actions without opening Delta Chat.
            // if privacy options are enabled, the buttons are not added.
            if (privacy.isDisplayContact() && privacy.isDisplayMessage()
             && !Prefs.isScreenLockEnabled(context)) {
                try {
                    PendingIntent inNotificationReplyIntent = getRemoteReplyIntent(chatId);
                    PendingIntent markReadIntent = getMarkAsReadIntent(chatId, true);

                    NotificationCompat.Action markAsReadAction = new NotificationCompat.Action(R.drawable.check,
                            context.getString(R.string.notify_mark_read),
                            markReadIntent);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(R.drawable.ic_reply_white_36dp,
                                context.getString(R.string.notify_reply_button),
                                inNotificationReplyIntent)
                                .addRemoteInput(new RemoteInput.Builder(RemoteReplyReceiver.EXTRA_REMOTE_REPLY)
                                        .setLabel(context.getString(R.string.notify_reply_button)).build())
                                .build();
                        builder.addAction(replyAction);
                    }

                    NotificationCompat.Action wearableReplyAction = new NotificationCompat.Action.Builder(R.drawable.ic_reply,
                            context.getString(R.string.notify_reply_button),
                            inNotificationReplyIntent)
                            .addRemoteInput(new RemoteInput.Builder(RemoteReplyReceiver.EXTRA_REMOTE_REPLY)
                                    .setLabel(context.getString(R.string.notify_reply_button)).build())
                            .build();
                    builder.addAction(markAsReadAction);
                    builder.extend(new NotificationCompat.WearableExtender().addAction(markAsReadAction).addAction(wearableReplyAction));
                } catch(Exception e) { Log.w(TAG, e); }
            }

            // create a tiny inbox (gets visible if the notification is expanded)
            if (privacy.isDisplayContact() && privacy.isDisplayMessage()) {
                try {
                    NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle();
                    synchronized (inboxes) {
                        ArrayList<String> lines = inboxes.get(chatId);
                        if (lines == null) {
                            lines = new ArrayList<>();
                            inboxes.put(chatId, lines);
                        }
                        lines.add(line);

                        for (int l = lines.size() - 1; l >= 0; l--) {
                            inboxStyle.addLine(lines.get(l));
                        }
                    }
                    builder.setStyle(inboxStyle);
                } catch(Exception e) { Log.w(TAG, e); }
            }

            // messages count, some os make some use of that
            // - do not use setSubText() as this is displayed together with setContentInfo() eg. on Lollipop
            // - setNumber() may overwrite setContentInfo(), should be called last
            // weird stuff.
            int cnt = dcContext.getFreshMsgCount(chatId);
            builder.setContentInfo(String.valueOf(cnt));
            builder.setNumber(cnt);

            // add notification, we use one notification per chat,
            // esp. older android are not that great at grouping
            notificationManager.notify(ID_MSG_OFFSET + chatId, builder.build());

            // group notifications together in a summary, this is possible since SDK 24 (Android 7)
            // https://developer.android.com/training/notify-user/group.html
            // in theory, this won't be needed due to setGroup(), however, in practise, it is needed up to at least Android 10.
            if (Build.VERSION.SDK_INT >= 24) {
                NotificationCompat.Builder summary = new NotificationCompat.Builder(context, notificationChannel)
                        .setGroup(GRP_MSG)
                        .setGroupSummary(true)
                        .setSmallIcon(R.drawable.icon_notification)
                        .setColor(context.getResources().getColor(R.color.delta_primary))
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setContentTitle("Delta Chat") // content title would only be used on SDK <24
                        .setContentText("New messages") // content text would only be used on SDK <24
                        .setContentIntent(getOpenChatlistIntent());
                notificationManager.notify(ID_MSG_SUMMARY, summary.build());
            }
        });
    }

    public void removeNotifications(int chatId) {
        boolean removeSummary;
        synchronized (inboxes) {
            inboxes.remove(chatId);
            removeSummary = inboxes.isEmpty();
        }

        // cancel notification independently of inboxes array,
        // due to restarts, the app may have notification even when inboxes is empty.
        try {
            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
            notificationManager.cancel(ID_MSG_OFFSET + chatId);
            if (removeSummary) {
                notificationManager.cancel(ID_MSG_SUMMARY);
            }
        } catch (Exception e) { Log.w(TAG, e); }
    }

    public void removeAllNotifiations() {
        synchronized (inboxes) {
            inboxes.clear();
        }
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        notificationManager.cancelAll();
    }

    public void updateVisibleChat(int chatId) {
        Util.runOnAnyBackgroundThread(() -> {

            visibleChatId = chatId;
            if (chatId!=0) {
                removeNotifications(chatId);
            }

        });
    }

    public void maybePlaySendSound(DcChat dcChat) {
        if (Prefs.isInChatNotifications(context) && Prefs.isNotificationsEnabled(context) && !dcChat.isMuted()) {
            InChatSounds.getInstance(context).playSendSound();
        }
    }
}
