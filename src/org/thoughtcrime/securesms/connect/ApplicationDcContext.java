package org.thoughtcrime.securesms.connect;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;
import com.b44t.messenger.DcEventEmitter;
import com.b44t.messenger.DcLot;
import com.b44t.messenger.DcMsg;

import org.thoughtcrime.securesms.BuildConfig;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.database.model.ThreadRecord;
import org.thoughtcrime.securesms.notifications.NotificationCenter;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.Prefs;
import org.thoughtcrime.securesms.util.Util;

import java.io.File;
import java.util.Date;
import java.util.HashMap;

public class ApplicationDcContext extends DcContext {

  public static final String TAG = "DeltaChat";

  @IntDef({RECIPIENT_TYPE_CHAT, RECIPIENT_TYPE_CONTACT})
  public @interface RecipientType {
  }

  public static final int RECIPIENT_TYPE_CHAT = 0;
  public static final int RECIPIENT_TYPE_CONTACT = 1;

  public Context context;
  public NotificationCenter notificationCenter;

  public ApplicationDcContext(Context context) {
    super("Android "+BuildConfig.VERSION_NAME, AccountManager.getInstance().getSelectedAccount(context).getAbsolutePath());
    this.context = context;

    // if ui-based migrations are needed, this is a good place
    // (see eg. https://github.com/deltachat/deltachat-android/pull/1618 for an example)

    // screen-lock is deprecated, inform users still using it
    try {
      if (!Prefs.getBooleanPreference(context, "pref_android_screen_lock_checked", false)) {
        Prefs.setBooleanPreference(context, "pref_android_screen_lock_checked", true);
        if (Prefs.isScreenLockEnabled(context)) {
          Prefs.setBooleanPreference(context, "pref_android_screen_lock_keep_for_now", true);
          DcMsg msg = new DcMsg(this, DcMsg.DC_MSG_TEXT);
          msg.setText("⚠️ You are using the function \"Screen lock\" " +
            "that will be removed in one of the next versions for the following reasons:\n" +
            "\n" +
            "• It does not add much protection as one just has to repeat the system secret.\n" +
            "\n" +
            "• It is hard to maintain across different Android versions and is not even doable on some." +
            " We like to put the resources to other things.\n" +
            "\n" +
            "• It is not planned/possible on iOS or Desktop this way" +
            " and stands in the way of moving towards unified solutions.\n" +
            "\n" +
            "• Same or even better functionality is available by other apps or maybe by the device itself.\n" +
            "\n" +
            "\uD83D\uDC49 For the future, we suggest to keep your phone locked " +
            "or use an appropriate app or check the device settings.");
          addDeviceMsg("android-screen-lock-deprecated14", msg);
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }

    new Thread(() -> {
      DcEventEmitter emitter = getEventEmitter();
      while (true) {
        DcEvent event = emitter.getNextEvent();
        if (event==null) {
          break;
        }
        handleEvent(event);
      }
      Log.i(TAG, "shutting down event handler");
    }, "eventThread").start();

    notificationCenter = new NotificationCenter(this);
    maybeStartIo();
  }

  public void maybeStartIo() {
    Log.i("DeltaChat", "++++++++++++++++++ ApplicationDcContext.maybeStartIo() ++++++++++++++++++");
    if (isConfigured()!=0) {
      startIo();
    }
  }

  public void setStockTranslations() {
    // the integers are defined in the core and used only here, an enum or sth. like that won't have a big benefit
    setStockTranslation(1, context.getString(R.string.chat_no_messages));
    setStockTranslation(2, context.getString(R.string.self));
    setStockTranslation(3, context.getString(R.string.draft));
    setStockTranslation(7, context.getString(R.string.voice_message));
    setStockTranslation(8, context.getString(R.string.chat_contact_request));
    setStockTranslation(9, context.getString(R.string.image));
    setStockTranslation(10, context.getString(R.string.video));
    setStockTranslation(11, context.getString(R.string.audio));
    setStockTranslation(12, context.getString(R.string.file));
    setStockTranslation(13, context.getString(R.string.pref_default_status_text));
    setStockTranslation(14, context.getString(R.string.group_hello_draft));
    setStockTranslation(15, context.getString(R.string.systemmsg_group_name_changed));
    setStockTranslation(16, context.getString(R.string.systemmsg_group_image_changed));
    setStockTranslation(17, context.getString(R.string.systemmsg_member_added));
    setStockTranslation(18, context.getString(R.string.systemmsg_member_removed));
    setStockTranslation(19, context.getString(R.string.systemmsg_group_left));
    setStockTranslation(23, context.getString(R.string.gif));
    setStockTranslation(24, context.getString(R.string.encrypted_message));
    setStockTranslation(29, context.getString(R.string.systemmsg_cannot_decrypt));
    setStockTranslation(31, context.getString(R.string.systemmsg_read_receipt_subject));
    setStockTranslation(32, context.getString(R.string.systemmsg_read_receipt_body));
    setStockTranslation(33, context.getString(R.string.systemmsg_group_image_deleted));
    setStockTranslation(35, context.getString(R.string.contact_verified));
    setStockTranslation(36, context.getString(R.string.contact_not_verified));
    setStockTranslation(37, context.getString(R.string.contact_setup_changed));
    setStockTranslation(40, context.getString(R.string.chat_archived_chats_title));
    setStockTranslation(42, context.getString(R.string.autocrypt_asm_subject));
    setStockTranslation(43, context.getString(R.string.autocrypt_asm_general_body));
    setStockTranslation(60, context.getString(R.string.login_error_cannot_login));
    setStockTranslation(61, context.getString(R.string.login_error_server_response));
    setStockTranslation(62, context.getString(R.string.systemmsg_action_by_user));
    setStockTranslation(63, context.getString(R.string.systemmsg_action_by_me));
    setStockTranslation(68, context.getString(R.string.device_talk));
    setStockTranslation(69, context.getString(R.string.saved_messages));
    setStockTranslation(70, context.getString(R.string.device_talk_explain));
    setStockTranslation(71, context.getString(R.string.device_talk_welcome_message));
    setStockTranslation(72, context.getString(R.string.systemmsg_unknown_sender_for_chat));
    setStockTranslation(73, context.getString(R.string.systemmsg_subject_for_new_contact));
    setStockTranslation(74, context.getString(R.string.systemmsg_failed_sending_to));
    setStockTranslation(75, context.getString(R.string.systemmsg_ephemeral_timer_disabled));
    setStockTranslation(76, context.getString(R.string.systemmsg_ephemeral_timer_enabled));
    setStockTranslation(77, context.getString(R.string.systemmsg_ephemeral_timer_minute));
    setStockTranslation(78, context.getString(R.string.systemmsg_ephemeral_timer_hour));
    setStockTranslation(79, context.getString(R.string.systemmsg_ephemeral_timer_day));
    setStockTranslation(80, context.getString(R.string.systemmsg_ephemeral_timer_week));
    setStockTranslation(82, context.getString(R.string.videochat_invitation));
    setStockTranslation(83, context.getString(R.string.videochat_invitation_body));
    setStockTranslation(84, context.getString(R.string.configuration_failed_with_error));
    setStockTranslation(88, context.getString(R.string.systemmsg_chat_protection_enabled));
    setStockTranslation(89, context.getString(R.string.systemmsg_chat_protection_disabled));
    setStockTranslation(90, context.getString(R.string.reply_noun));
    setStockTranslation(93, context.getString(R.string.systemmsg_ephemeral_timer_minutes));
    setStockTranslation(94, context.getString(R.string.systemmsg_ephemeral_timer_hours));
    setStockTranslation(95, context.getString(R.string.systemmsg_ephemeral_timer_days));
    setStockTranslation(96, context.getString(R.string.systemmsg_ephemeral_timer_weeks));
    setStockTranslation(97, context.getString(R.string.forwarded));
  }

  public File getImexDir() {
    // DIRECTORY_DOCUMENTS is only available since KitKat;
    // as we also support Ice Cream Sandwich and Jellybean (2017: 11% in total), this is no option.
    // Moreover, DIRECTORY_DOWNLOADS seems to be easier accessible by the user,
    // eg. "Download Managers" are nearly always installed.
    // CAVE: do not use DownloadManager to add the file as it is deleted on uninstall then ...
    return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
  }

  public static HashMap<String, Integer> sharedFiles = new HashMap<>();

  public void openForViewOrShare(Context activity,
                                 int msg_id, String cmd) {

    if(!(activity instanceof Activity)) {
      // would be nicer to accepting only Activity objects,
      // however, typically in Android just Context objects are passed around (as this normally does not make a difference).
      // Accepting only Activity here would force callers to cast, which would easily result in even more ugliness.
      Toast.makeText(context, "openForViewOrShare() expects an Activity object", Toast.LENGTH_LONG).show();
      return;
    }

    DcMsg msg = getMsg(msg_id);
    String path = msg.getFile();
    String mimeType = msg.getFilemime();
    try {
      File file = new File(path);
      if (!file.exists()) {
        Toast.makeText(context, context.getString(R.string.file_not_found, path), Toast.LENGTH_LONG).show();
        return;
      }

      Uri uri;
      if (path.startsWith(getBlobdir())) {
        uri = Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".attachments/" + file.getName());
        sharedFiles.put("/" + file.getName(), 1); // as different Android version handle uris in putExtra differently, we also check them on our own
      } else {
        if (Build.VERSION.SDK_INT >= 24) {
          uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".fileprovider", file);
        } else {
          uri = Uri.fromFile(file);
        }
      }

      if (cmd.equals(Intent.ACTION_VIEW)) {
        mimeType = checkMime(path, mimeType);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mimeType);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity((Activity) activity, intent);
      } else {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.putExtra(Intent.EXTRA_TEXT, msg.getText());
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(intent, context.getString(R.string.chat_share_with_title)));
      }
    } catch (RuntimeException e) {
      Toast.makeText(context, String.format("%s (%s)", context.getString(R.string.no_app_to_handle_data), mimeType), Toast.LENGTH_LONG).show();
      Log.i(TAG, "opening of external activity failed.", e);
    }
  }

  private void startActivity(Activity activity, Intent intent) {
    // request for permission to install apks on API 26+ if intent mimetype is an apk
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
              "application/vnd.android.package-archive".equals(intent.getType()) &&
              !activity.getPackageManager().canRequestPackageInstalls()) {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(Uri.parse(String.format("package:%s", activity.getPackageName()))));
            return;
      }
      activity.startActivity(intent);
  }

  private String checkMime(String path, String mimeType) {
    if(mimeType == null || mimeType.equals("application/octet-stream")) {
      path = path.replaceAll(" ", "");
      String extension = MimeTypeMap.getFileExtensionFromUrl(path);
      String newType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
      if(newType != null) return newType;
    }
    return mimeType;
  }

  public String getBlobdirFile(String filename, String ext) {
    String outPath = null;
    for (int i = 0; i < 1000; i++) {
      String test = getBlobdir() + "/" + filename + (i == 0 ? "" : i < 100 ? "-" + i : "-" + (new Date().getTime() + i)) + ext;
      if (!new File(test).exists()) {
        outPath = test;
        break;
      }
    }
    if(outPath==null) {
      // should not happen
      outPath = getBlobdir() + "/" + Math.random();
    }
    return outPath;
  }

  public String getBlobdirFile(String path) {
    String filename = path.substring(path.lastIndexOf('/')+1); // is the whole path if '/' is not found (lastIndexOf() returns -1 then)
    String ext = "";
    int point = filename.indexOf('.');
    if(point!=-1) {
      ext = filename.substring(point);
      filename = filename.substring(0, point);
    }
    return getBlobdirFile(filename, ext);

  }

  public boolean isWebrtcConfigOk() {
    String instance = getConfig(DcHelper.CONFIG_WEBRTC_INSTANCE);
    return (instance != null && !instance.isEmpty());
  }

  /***********************************************************************************************
   * create objects compatible to the database model of Signal
   **********************************************************************************************/

  @NonNull
  public Recipient getRecipient(@RecipientType int recipientType, int id) {
    switch (recipientType) {
      case RECIPIENT_TYPE_CHAT:
        return getRecipient(getChat(id));
      case RECIPIENT_TYPE_CONTACT:
        return getRecipient(getContact(id));
      default:
        throw new IllegalArgumentException("Wrong RecipientType");
    }
  }

  @NonNull
  public Recipient getRecipient(DcChat chat) {
    return new Recipient(context, chat, null);
  }

  @NonNull

  public Recipient getRecipient(DcContact contact) {
    return new Recipient(context, null, contact);
  }

  @NonNull
  public ThreadRecord getThreadRecord(DcLot summary, DcChat chat) { // adapted from ThreadDatabase.getCurrent()
    int chatId = chat.getId();

    String body = summary.getText1();
    if (!body.isEmpty()) {
      body += ": ";
    }
    body += summary.getText2();

    Recipient recipient = getRecipient(chat);
    long date = summary.getTimestamp();
    int unreadCount = getFreshMsgCount(chatId);

    return new ThreadRecord(body, recipient, date,
        unreadCount, chatId,
        chat.getVisibility(), chat.isProtected(), chat.isSendingLocations(), chat.isMuted(), summary);
  }

  /***********************************************************************************************
   * Tools
   **********************************************************************************************/

  public boolean isNetworkConnected() {
    try {
      ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      NetworkInfo netInfo = cm.getActiveNetworkInfo();
      if (netInfo != null && netInfo.isConnected()) {
        return true;
      }

    } catch (Exception e) {
    }
    return false;
  }

  /***********************************************************************************************
   * Event Handling
   **********************************************************************************************/

  public DcEventCenter eventCenter = new DcEventCenter();

  private final Object lastErrorLock = new Object();
  private String lastErrorString = "";
  private boolean showNextErrorAsToast = true;
  public boolean showNetworkErrors = true; // set to false if one network error was reported while having no internet

  public void captureNextError() {
    synchronized (lastErrorLock) {
      showNextErrorAsToast = false;
      lastErrorString = "";
    }
  }

  public boolean hasCapturedError() {
    synchronized (lastErrorLock) {
      return !lastErrorString.isEmpty();
    }
  }

  public String getCapturedError() {
    synchronized (lastErrorLock) {
      return lastErrorString;
    }
  }

  public void endCaptureNextError() {
    synchronized (lastErrorLock) {
      showNextErrorAsToast = true;
    }
  }

  private void handleError(int event, String string) {
    // log error
    boolean showAsToast;
    Log.e(TAG, string);
    synchronized (lastErrorLock) {
      lastErrorString = string;
      showAsToast = showNextErrorAsToast;
      showNextErrorAsToast = true;
    }

    // show error to user
    Util.runOnMain(() -> {
      if (showAsToast) {
        String toastString = null;

        if (event == DC_EVENT_ERROR_NETWORK) {
          if (isNetworkConnected()) {
            toastString = string;
            showNetworkErrors = true;
          } else if (showNetworkErrors) {
            toastString = context.getString(R.string.error_no_network);
            showNetworkErrors = false;
          }
        }
        else if (event == DC_EVENT_ERROR_SELF_NOT_IN_GROUP) {
          toastString = context.getString(R.string.group_self_not_in_group);
        }

        ForegroundDetector foregroundDetector = ForegroundDetector.getInstance();
        if (toastString != null && (foregroundDetector == null || foregroundDetector.isForeground())) {
          Toast.makeText(context, toastString, Toast.LENGTH_LONG).show();
        }
      }
    });
  }

  public long handleEvent(DcEvent event) {
    int id = event.getId();
    switch (id) {
      case DC_EVENT_INFO:
        Log.i(TAG, event.getData2Str());
        break;

      case DC_EVENT_WARNING:
        Log.w(TAG, event.getData2Str());
        break;

      case DC_EVENT_ERROR:
        handleError(id, event.getData2Str());
        break;

      case DC_EVENT_ERROR_NETWORK:
        handleError(id, event.getData2Str());
        break;

      case DC_EVENT_ERROR_SELF_NOT_IN_GROUP:
        handleError(id, event.getData2Str());
        break;

      case DC_EVENT_INCOMING_MSG:
        notificationCenter.addNotification(event.getData1Int(), event.getData2Int());
        if (eventCenter != null) {
          eventCenter.sendToObservers(event);
        }
        break;

      case DC_EVENT_MSGS_NOTICED:
        notificationCenter.removeNotifications(event.getData1Int());
        if (eventCenter != null) {
          eventCenter.sendToObservers(event);
        }
        break;

      default: {
        if (eventCenter != null) {
          eventCenter.sendToObservers(event);
        }
      }
      break;
    }

    if (id == DC_EVENT_CHAT_MODIFIED) {
      // Possibly a chat was deleted or the avatar was changed, directly refresh DirectShare so that
      // a new chat can move up / the chat avatar change is populated
      DirectShareUtil.triggerRefreshDirectShare(context);
    }

    return 0;
  }

}
