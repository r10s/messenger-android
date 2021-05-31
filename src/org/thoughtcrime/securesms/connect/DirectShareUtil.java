package org.thoughtcrime.securesms.connect;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcChatlist;
import com.b44t.messenger.DcContext;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import org.thoughtcrime.securesms.ShareActivity;
import org.thoughtcrime.securesms.contacts.avatars.ContactPhoto;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.mms.GlideRequest;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.DrawableUtil;
import org.thoughtcrime.securesms.util.Util;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * The Signal code has a similar class called ConversationUtil.
 *
 * This class uses the Sharing Shortcuts API to publish dynamic launcher shortcuts (the ones that
 * appear when you long-press on an app) and direct-sharing-shortcuts.
 *
 * It replaces the class DirectShareService, because DirectShareService used the
 * ChooserTargetService API, which was replaced by the Sharing Shortcuts API.
 */
public class DirectShareUtil {

  private static final String TAG = DirectShareUtil.class.getSimpleName();
  private static final String SHORTCUT_CATEGORY = "android.shortcut.conversation";

  public static void clearShortcut(@NonNull Context context, int chatId) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      Util.runOnAnyBackgroundThread(() -> ShortcutManagerCompat.removeDynamicShortcuts(context, Collections.singletonList(Integer.toString(chatId))));
    }
  }

  public static void resetAllShortcuts(@NonNull Context context) {
    Util.runOnBackground(() -> {
      ShortcutManagerCompat.removeAllDynamicShortcuts(context);
      triggerRefreshDirectShare(context);
    });
  }

  public static void triggerRefreshDirectShare(Context context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

      Util.runOnBackgroundDelayed(() -> {
        try {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1
                  && context.getSystemService(ShortcutManager.class).isRateLimitingActive()) {
            return;
          }

          int maxShortcuts = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context);
          List<ShortcutInfoCompat> currentShortcuts = ShortcutManagerCompat.getDynamicShortcuts(context);
          List<ShortcutInfoCompat> newShortcuts = getChooserTargets(context);

          if (maxShortcuts > 0
                  && currentShortcuts.size() + newShortcuts.size() > maxShortcuts) {
            ShortcutManagerCompat.removeAllDynamicShortcuts(context);
          }

          boolean success = ShortcutManagerCompat.addDynamicShortcuts(context, newShortcuts);
          Log.i(TAG, "Updated dynamic shortcuts, success: " + success);
        } catch(IllegalArgumentException e) {
          Log.e(TAG, "Updating dynamic shortcuts failed: " + e);
        }

        // Wait  500ms, this is called by onResume(), and we want to make sure that refreshing
        // shortcuts does not delay loading of the chatlist
      }, 500);
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.M)
  private static List<ShortcutInfoCompat> getChooserTargets(Context context) {
    List<ShortcutInfoCompat> results = new LinkedList<>();
    ApplicationDcContext dcContext = DcHelper.getContext(context);

    DcChatlist chatlist = dcContext.getChatlist(
            DcContext.DC_GCL_ADD_ALLDONE_HINT | DcContext.DC_GCL_FOR_FORWARDING | DcContext.DC_GCL_NO_SPECIALS,
            null,
            0
    );
    int max = 4;
    if (chatlist.getCnt() < max) {
      max = chatlist.getCnt();
    }
    for (int i = 0; i <= max; i++) {
      DcChat chat = chatlist.getChat(i);
      if (!chat.canSend()) {
        continue;
      }

      Intent intent = new Intent(context, ShareActivity.class);
      intent.setAction(Intent.ACTION_SEND);
      intent.putExtra(ShareActivity.EXTRA_CHAT_ID, chat.getId());

      Recipient recipient = DcHelper.getContext(context).getRecipient(chat);
      Bitmap avatar = getIconForShortcut(context, recipient);
      results.add(new ShortcutInfoCompat.Builder(context, Integer.toString(chat.getId()))
              .setShortLabel(chat.getName())
              .setLongLived(true)
              .setRank(i+1)
              .setIcon(IconCompat.createWithAdaptiveBitmap(avatar))
              .setCategories(Collections.singleton(SHORTCUT_CATEGORY))
              .setIntent(intent)
              .setActivity(new ComponentName(context, "org.thoughtcrime.securesms.RoutingActivity"))
              .build());
    }

    return results;
  }

  private static Bitmap getIconForShortcut(@NonNull Context context, @NonNull Recipient recipient) {
    try {
      return getShortcutInfoBitmap(context, recipient);
    } catch (ExecutionException | InterruptedException | NullPointerException e) {
      return getFallbackDrawable(context, recipient);
    }
  }

  private static @NonNull Bitmap getShortcutInfoBitmap(@NonNull Context context, @NonNull Recipient recipient) throws ExecutionException, InterruptedException {
    return DrawableUtil.wrapBitmapForShortcutInfo(request(GlideApp.with(context).asBitmap(), context, recipient, false).circleCrop().submit().get());
  }

  private static Bitmap getFallbackDrawable(Context context, @NonNull Recipient recipient) {
    return BitmapUtil.createFromDrawable(recipient.getFallbackAvatarDrawable(context),
            context.getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_width),
            context.getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_height));
  }

  private static <T> GlideRequest<T> request(@NonNull GlideRequest<T> glideRequest, @NonNull Context context, @NonNull Recipient recipient, boolean loadSelf) {
    final ContactPhoto photo;
    photo = recipient.getContactPhoto(context);

    return glideRequest.load(photo)
            .error(getFallbackDrawable(context, recipient))
            .diskCacheStrategy(DiskCacheStrategy.ALL);
  }
}
