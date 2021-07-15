package org.thoughtcrime.securesms.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import androidx.annotation.ColorInt;
import androidx.appcompat.app.AlertDialog;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.Toast;

import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;

import org.thoughtcrime.securesms.ConversationActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.Util;

public class LongClickCopySpan extends ClickableSpan {
  private static final String PREFIX_MAILTO = "mailto:";
  private static final String PREFIX_TEL = "tel:";
  private static final String PREFIX_CMD = "cmd:";

  private boolean isHighlighted;
  @ColorInt
  private int highlightColor;
  private final String url;

  public LongClickCopySpan(String url) {
    this.url = url;
  }

  private void openChat(Activity activity, DcContact contact) {
    DcContext dcContext = DcHelper.getContext(activity);
    int chatId = dcContext.createChatByContactId(contact.getId());
    if (chatId != 0) {
      Intent intent = new Intent(activity, ConversationActivity.class);
      intent.putExtra(ConversationActivity.CHAT_ID_EXTRA, chatId);
      activity.startActivity(intent);
    }
  }

  @Override
  public void onClick(View widget) {
    if (url.startsWith(PREFIX_CMD)) {
      try {
        String cmd = url.substring(PREFIX_CMD.length());
        ConversationActivity activity = (ConversationActivity) widget.getContext();
        activity.setDraftText(cmd + " ");
      } catch (Exception e) {
        e.printStackTrace();
      }
    } else if (url.startsWith(PREFIX_MAILTO)) {
      try {
        String addr = prepareUrl(url);
        Activity activity = (Activity) widget.getContext();
        DcContext dcContext = DcHelper.getContext(activity);
        DcContact contact = dcContext.getContact(dcContext.createContact(null, addr));
        if (contact.getId() != 0 && !contact.isBlocked() && dcContext.getChatIdByContactId(contact.getId()) != 0) {
          openChat(activity, contact);
        } else {
          new AlertDialog.Builder(activity)
                  .setMessage(activity.getString(R.string.ask_start_chat_with, contact.getNameNAddr()))
                  .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    openChat(activity, contact);
                  })
                  .setNegativeButton(R.string.cancel, null)
                  .show();
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    } else {
      IntentUtils.showBrowserIntent(widget.getContext(), url);
    }
  }

  void onLongClick(View widget) {
    Context context = widget.getContext();

    if (url.startsWith(PREFIX_CMD)) {
      Util.writeTextToClipboard(context, url.substring(PREFIX_CMD.length()));
      Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show();
    } else {
      String preparedUrl = prepareUrl(url);
      new AlertDialog.Builder(context)
          .setTitle(preparedUrl)
          .setItems(new CharSequence[]{
                  context.getString(R.string.menu_copy_to_clipboard)
              },
              (dialogInterface, i) -> {
                Util.writeTextToClipboard(context, preparedUrl);
                Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show();
              })
          .setNegativeButton(R.string.cancel, null)
          .show();
    }
  }

  @Override
  public void updateDrawState(TextPaint ds) {
    super.updateDrawState(ds);
    ds.bgColor = highlightColor;
    ds.setUnderlineText(!isHighlighted);
  }

  void setHighlighted(boolean highlighted, @ColorInt int highlightColor) {
    this.isHighlighted = highlighted;
    this.highlightColor = highlightColor;
  }

  private String prepareUrl(String url) {
    if (url.startsWith(PREFIX_MAILTO)) {
      return url.substring(PREFIX_MAILTO.length());
    } else if (url.startsWith(PREFIX_TEL)) {
      return url.substring(PREFIX_TEL.length());
    }
    return url;
  }
}
