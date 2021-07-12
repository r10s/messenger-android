/*
 * Copyright (C) 2011 Whisper Systems
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
package org.thoughtcrime.securesms;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.provider.Browser;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.View.OnKeyListener;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.WindowCompat;

import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;
import com.b44t.messenger.DcMsg;

import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.attachments.UriAttachment;
import org.thoughtcrime.securesms.audio.AudioRecorder;
import org.thoughtcrime.securesms.audio.AudioSlidePlayer;
import org.thoughtcrime.securesms.components.AnimatingToggle;
import org.thoughtcrime.securesms.components.AttachmentTypeSelector;
import org.thoughtcrime.securesms.components.ComposeText;
import org.thoughtcrime.securesms.components.HidingLinearLayout;
import org.thoughtcrime.securesms.components.InputAwareLayout;
import org.thoughtcrime.securesms.components.InputPanel;
import org.thoughtcrime.securesms.components.KeyboardAwareLinearLayout.OnKeyboardShownListener;
import org.thoughtcrime.securesms.components.ScaleStableImageView;
import org.thoughtcrime.securesms.components.SendButton;
import org.thoughtcrime.securesms.components.camera.QuickAttachmentDrawer;
import org.thoughtcrime.securesms.components.camera.QuickAttachmentDrawer.AttachmentDrawerListener;
import org.thoughtcrime.securesms.components.camera.QuickAttachmentDrawer.DrawerState;
import org.thoughtcrime.securesms.components.emoji.EmojiKeyboardProvider;
import org.thoughtcrime.securesms.components.emoji.MediaKeyboard;
import org.thoughtcrime.securesms.connect.ApplicationDcContext;
import org.thoughtcrime.securesms.connect.DcEventCenter;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.map.MapActivity;
import org.thoughtcrime.securesms.messagerequests.MessageRequestsBottomView;
import org.thoughtcrime.securesms.mms.AttachmentManager;
import org.thoughtcrime.securesms.mms.AttachmentManager.MediaType;
import org.thoughtcrime.securesms.mms.AudioSlide;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.mms.GlideRequests;
import org.thoughtcrime.securesms.mms.PartAuthority;
import org.thoughtcrime.securesms.mms.QuoteModel;
import org.thoughtcrime.securesms.mms.SlideDeck;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.providers.PersistentBlobProvider;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.scribbles.ScribbleActivity;
import org.thoughtcrime.securesms.util.DynamicLanguage;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.Prefs;
import org.thoughtcrime.securesms.util.RelayUtil;
import org.thoughtcrime.securesms.util.SendRelayedMessageUtil;
import org.thoughtcrime.securesms.util.ServiceUtil;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.thoughtcrime.securesms.util.concurrent.AssertedSuccessListener;
import org.thoughtcrime.securesms.util.concurrent.ListenableFuture;
import org.thoughtcrime.securesms.util.concurrent.SettableFuture;
import org.thoughtcrime.securesms.util.guava.Optional;
import org.thoughtcrime.securesms.util.views.Stub;
import org.thoughtcrime.securesms.video.recode.VideoRecoder;
import org.thoughtcrime.securesms.videochat.VideochatUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.thoughtcrime.securesms.NewConversationActivity.MAILTO;
import static org.thoughtcrime.securesms.TransportOption.Type;
import static org.thoughtcrime.securesms.util.RelayUtil.getSharedText;
import static org.thoughtcrime.securesms.util.RelayUtil.isForwarding;
import static org.thoughtcrime.securesms.util.RelayUtil.isRelayingMessageContent;
import static org.thoughtcrime.securesms.util.RelayUtil.isSharing;

/**
 * Activity for displaying a message thread, as well as
 * composing/sending a new message into that thread.
 *
 * @author Moxie Marlinspike
 *
 */
@SuppressLint("StaticFieldLeak")
public class ConversationActivity extends PassphraseRequiredActionBarActivity
    implements ConversationFragment.ConversationFragmentListener,
               AttachmentManager.AttachmentListener,
               SearchView.OnQueryTextListener,
               DcEventCenter.DcEventDelegate,
               OnKeyboardShownListener,
               AttachmentDrawerListener,
               InputPanel.Listener,
               InputPanel.MediaListener
{
  private static final String TAG = ConversationActivity.class.getSimpleName();

  public static final String CHAT_ID_EXTRA           = "chat_id";
  public static final String TEXT_EXTRA              = "draft_text";
  public static final String STARTING_POSITION_EXTRA = "starting_position";

  private static final int PICK_GALLERY        = 1;
  private static final int PICK_DOCUMENT       = 2;
  private static final int PICK_AUDIO          = 3;
  private static final int PICK_CONTACT        = 4;
  private static final int GROUP_EDIT          = 6;
  private static final int TAKE_PHOTO          = 7;
  private static final int RECORD_VIDEO        = 8;
  private static final int PICK_LOCATION       = 9;  // TODO: i think, this can be deleted
  private static final int SMS_DEFAULT         = 11; // TODO: i think, this can be deleted

  /**
   * If the user opens a chat, goes to another app, and shares some content to the same chat, we will have two ConversationActivity's
   * with the same chat (because sharing uses startActivityForResult() and all activities started this way will be created a second time,
   * without affecting the existing activity). So, when the sharing ConversationActivity closes, it sets this variable to `true` so that
   * the still-running-currently-in-background ConversationActivity re-initializes the draft.
   * There is always only one still-running-currently-in-background ConversationActivity, which is the one started normally (i.e. with
   * `startActivity()`). Therefore, after reinitializing the draft in one activity, we can safely set this variable to `false`.
   * See https://github.com/deltachat/deltachat-android/pull/1770
   */
  private static boolean doReinitializeDraft;

  private   GlideRequests               glideRequests;
  protected ComposeText                 composeText;
  private   AnimatingToggle             buttonToggle;
  private   SendButton                  sendButton;
  private   ImageButton                 attachButton;
  protected ConversationTitleView       titleView;
  private   ConversationFragment        fragment;
  private   InputAwareLayout            container;
  private   View                        composePanel;
  private   ScaleStableImageView        backgroundView;
  private   MessageRequestsBottomView   messageRequestBottomView;

  private   AttachmentTypeSelector attachmentTypeSelector;
  private   AttachmentManager      attachmentManager;
  private   AudioRecorder          audioRecorder;
  private   Stub<MediaKeyboard>    emojiDrawerStub;
  protected HidingLinearLayout     quickAttachmentToggle;
  private   QuickAttachmentDrawer  quickAttachmentDrawer;
  private   InputPanel             inputPanel;

  private Recipient  recipient;
  private ApplicationDcContext dcContext;
  private DcChat     dcChat                = new DcChat(0);
  private int        chatId;
  private final boolean isSecureText          = true;
  private boolean    isDefaultSms             = true;
  private boolean    isSecurityInitialized    = false;
  private boolean    isShareDraftInitialized  = false;

  private boolean successfulForwardingAttempt = false;


  private final DynamicTheme       dynamicTheme    = new DynamicTheme();
  private final DynamicLanguage    dynamicLanguage = new DynamicLanguage();

  @Override
  protected void onPreCreate() {
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
  }

  @Override
  protected void onCreate(Bundle state, boolean ready) {
    Log.w(TAG, "onCreate()");

    final Context context = getApplicationContext();
    this.dcContext = DcHelper.getContext(context);

    supportRequestWindowFeature(WindowCompat.FEATURE_ACTION_BAR_OVERLAY);
    setContentView(R.layout.conversation_activity);

    TypedArray typedArray = obtainStyledAttributes(new int[] {R.attr.conversation_background});
    int color = typedArray.getColor(0, Color.WHITE);
    typedArray.recycle();

    getWindow().getDecorView().setBackgroundColor(color);

    fragment = initFragment(R.id.fragment_content, new ConversationFragment(), dynamicLanguage.getCurrentLocale());

    initializeActionBar();
    initializeViews();
    initializeResources();
    initializeSecurity(false, isDefaultSms).addListener(new AssertedSuccessListener<Boolean>() {
      @Override
      public void onSuccess(Boolean result) {
        initializeDraft().addListener(new AssertedSuccessListener<Boolean>() {
          @Override
          public void onSuccess(Boolean result) {
            if (result != null && result) {
              Util.runOnMain(() -> {
                if (fragment != null && fragment.isResumed()) {
                  fragment.moveToLastSeen();
                } else {
                  Log.w(TAG, "Wanted to move to the last seen position, but the fragment was in an invalid state");
                }
              });
            }
          }
        });
      }
    });

    dcContext.eventCenter.addObserver(DcContext.DC_EVENT_CHAT_MODIFIED, this);
    dcContext.eventCenter.addObserver(DcContext.DC_EVENT_CHAT_EPHEMERAL_TIMER_MODIFIED, this);
    dcContext.eventCenter.addObserver(DcContext.DC_EVENT_CONTACTS_CHANGED, this);

    if (isForwarding(this)) {
      handleForwarding();
    } else if (isSharing(this)) {
      handleSharing();
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    Log.w(TAG, "onNewIntent()");
    
    if (isFinishing()) {
      Log.w(TAG, "Activity is finishing...");
      return;
    }

    if (!Util.isEmpty(composeText) || attachmentManager.isAttachmentPresent()) {
      processComposeControls(ACTION_SAVE_DRAFT);
      attachmentManager.clear(glideRequests, false);
      composeText.setText("");
    }

    setIntent(intent);
    initializeResources();
    initializeSecurity(false, isDefaultSms).addListener(new AssertedSuccessListener<Boolean>() {
      @Override
      public void onSuccess(Boolean result) {
        initializeDraft();
      }
    });

    if (fragment != null) {
      fragment.onNewIntent();
    }
  }

  @Override
  protected void onStart() {
    super.onStart();
  }

  @Override
  protected void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);
    quickAttachmentDrawer.onResume();

    initializeEnabledCheck();
    composeText.setTransport(sendButton.getSelectedTransport());

    titleView.setTitle(glideRequests, dcChat);

    dcContext.notificationCenter.updateVisibleChat(chatId);

    if (doReinitializeDraft) {
      initializeDraft();
      doReinitializeDraft = false;
    }
  }

  @Override
  protected void onPause() {
    super.onPause();

    processComposeControls(ACTION_SAVE_DRAFT);
    if (getCallingActivity() != null) {
      // `getCallingActivity() != null` finds out whether the activity was started using `startActivityForResult()`.
      doReinitializeDraft = true;
    }

    dcContext.notificationCenter.updateVisibleChat(0);
    if (isFinishing()) overridePendingTransition(R.anim.fade_scale_in, R.anim.slide_to_right);
    quickAttachmentDrawer.onPause();
    inputPanel.onPause();
    AudioSlidePlayer.stopAll();
  }

  @Override
  protected void onStop() {
    super.onStop();
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    Log.w(TAG, "onConfigurationChanged(" + newConfig.orientation + ")");
    super.onConfigurationChanged(newConfig);
    composeText.setTransport(sendButton.getSelectedTransport());
    quickAttachmentDrawer.onConfigurationChanged();

    if (emojiDrawerStub.resolved() && container.getCurrentInput() == emojiDrawerStub.get()) {
      container.hideAttachedInput(true);
    }

    initializeBackground();
  }

  @Override
  protected void onDestroy() {
    dcContext.eventCenter.removeObservers(this);
    super.onDestroy();
  }

  @Override
  public void onActivityResult(final int reqCode, int resultCode, Intent data) {
    Log.w(TAG, "onActivityResult called: " + reqCode + ", " + resultCode + " , " + data);
    super.onActivityResult(reqCode, resultCode, data);

    if ((data == null && reqCode != TAKE_PHOTO && reqCode != RECORD_VIDEO && reqCode != SMS_DEFAULT) ||
        (resultCode != RESULT_OK && reqCode != SMS_DEFAULT))
    {
      return;
    }

    switch (reqCode) {
    case PICK_GALLERY:
      MediaType mediaType;

      String mimeType = MediaUtil.getMimeType(this, data.getData());

      if      (MediaUtil.isGif(mimeType))   mediaType = MediaType.GIF;
      else if (MediaUtil.isVideo(mimeType)) mediaType = MediaType.VIDEO;
      else                                  mediaType = MediaType.IMAGE;

      setMedia(data.getData(), mediaType);

      break;
    case PICK_DOCUMENT:
      setMedia(data.getData(), MediaType.DOCUMENT);
      break;
    case PICK_AUDIO:
      setMedia(data.getData(), MediaType.AUDIO);
      break;
    case PICK_CONTACT:
      addAttachmentContactInfo(data);
      break;
    case GROUP_EDIT:
      dcChat = dcContext.getChat(chatId);
      titleView.setTitle(glideRequests, dcChat);
      break;
    case TAKE_PHOTO:
      if (attachmentManager.getImageCaptureUri() != null) {
        setMedia(attachmentManager.getImageCaptureUri(), MediaType.IMAGE);
      }
      break;
    case RECORD_VIDEO:
      Uri uri = null;
      if (data!=null) { uri = data.getData(); }
      if (uri==null) { uri = attachmentManager.getVideoCaptureUri(); }
      if (uri!=null) {
        setMedia(uri, MediaType.VIDEO);
      }
      else {
        Toast.makeText(this, "No video returned from system", Toast.LENGTH_LONG).show();
      }
      break;
    case PICK_LOCATION:
      break;
    case ScribbleActivity.SCRIBBLE_REQUEST_CODE:
      setMedia(data.getData(), MediaType.IMAGE);
      break;
    case SMS_DEFAULT:
      initializeSecurity(isSecureText, isDefaultSms);
      break;
    }
  }

  @Override
  public void startActivity(Intent intent) {
    if (intent.getStringExtra(Browser.EXTRA_APPLICATION_ID) != null) {
      intent.removeExtra(Browser.EXTRA_APPLICATION_ID);
    }

    try {
      super.startActivity(intent);
    } catch (ActivityNotFoundException e) {
      Log.w(TAG, e);
      Toast.makeText(this, R.string.no_app_to_handle_data, Toast.LENGTH_LONG).show();
    }
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuInflater inflater = this.getMenuInflater();
    menu.clear();

    if(chatId == DcChat.DC_CHAT_ID_DEADDROP) {
      return true;
    }

    inflater.inflate(R.menu.conversation, menu);

    if (dcChat.isSelfTalk()) {
      menu.findItem(R.id.menu_mute_notifications).setVisible(false);
    } else if(dcChat.isMuted()) {
      menu.findItem(R.id.menu_mute_notifications).setTitle(R.string.menu_unmute);
    }

    if (!Prefs.isLocationStreamingEnabled(this)) {
      menu.findItem(R.id.menu_show_map).setVisible(false);
    }

    if (!dcContext.isWebrtcConfigOk() || !dcChat.canVideochat()) {
      menu.findItem(R.id.menu_videochat_invite).setVisible(false);
    }

    if (!dcChat.canSend()) {
      menu.findItem(R.id.menu_ephemeral_messages).setVisible(false);
    }

    if (isGroupConversation()) {
      if (!dcChat.isMailingList()) { // Leaving mailing lists is currently not supported
        inflater.inflate(R.menu.conversation_push_group_options, menu);
      }
    }

    inflater.inflate(R.menu.conversation_archive, menu);
    if (isArchived()) {
      menu.findItem(R.id.menu_archive_chat).setTitle(R.string.menu_unarchive_chat);
    }

    inflater.inflate(R.menu.conversation_delete, menu);

    try {
      MenuItem searchItem = menu.findItem(R.id.menu_search_chat);
      searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
        @Override
        public boolean onMenuItemActionExpand(final MenuItem item) {
          searchExpand(menu, item);
          return true;
        }

        @Override
        public boolean onMenuItemActionCollapse(final MenuItem item) {
          searchCollapse(menu, item);
          return true;
        }
      });
      SearchView searchView = (SearchView) searchItem.getActionView();
      searchView.setOnQueryTextListener(this);
      searchView.setQueryHint(getString(R.string.search));
      searchView.setIconifiedByDefault(true);

      // hide the [X] beside the search field - this is too much noise, search can be aborted eg. by "back"
      ImageView closeBtn = searchView.findViewById(R.id.search_close_btn);
      if (closeBtn!=null) {
        closeBtn.setEnabled(false);
        closeBtn.setImageDrawable(null);
      }
    } catch (Exception e) {
      Log.e(TAG, "cannot set up in-chat-search: ", e);
    }

    if (!dcChat.canSend()) {
      MenuItem attachItem =  menu.findItem(R.id.menu_add_attachment);
      if (attachItem!=null) {
        attachItem.setVisible(false);
      }
    }

    super.onPrepareOptionsMenu(menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);
    switch (item.getItemId()) {
      case R.id.menu_add_attachment:        handleAddAttachment();             return true;
      case R.id.menu_leave:                 handleLeaveGroup();                return true;
      case R.id.menu_archive_chat:          handleArchiveChat();               return true;
      case R.id.menu_delete_chat:           handleDeleteChat();                return true;
      case R.id.menu_mute_notifications:    handleMuteNotifications();         return true;
      case R.id.menu_profile:               handleProfile();                   return true;
      case R.id.menu_show_map:              handleShowMap();                   return true;
      case R.id.menu_search_up:             handleMenuSearchNext(false);       return true;
      case R.id.menu_search_down:           handleMenuSearchNext(true);        return true;
      case android.R.id.home:               handleReturnToConversationList();  return true;
      case R.id.menu_ephemeral_messages:    handleEphemeralMessages();         return true;
      case R.id.menu_videochat_invite:      handleVideochatInvite();           return true;
    }

    return false;
  }

  @Override
  public void onBackPressed() {
    Log.w(TAG, "onBackPressed()");
    if (container.isInputOpen()){
      container.hideCurrentInput(composeText);
    } else {
      handleReturnToConversationList();
    }
  }

  @Override
  public void onKeyboardShown() {
    inputPanel.onKeyboardShown();
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  public void setDraftText(String txt) {
    composeText.setText(txt);
    composeText.setSelection(composeText.getText().length());
  }

  public void hideSoftKeyboard() {
    container.hideCurrentInput(composeText);
  }

  //////// Event Handlers

  private void handleEphemeralMessages() {
      int preselected = dcContext.getChatEphemeralTimer(chatId);
      EphemeralMessagesDialog.show(this, preselected, duration -> {
        dcContext.setChatEphemeralTimer(chatId, (int) duration);
      });
  }

  private void handleVideochatInvite() {
    new VideochatUtil().invite(this, chatId);
  }

  private void handleShowMap() {
    Intent intent = new Intent(this, MapActivity.class);
    intent.putExtra(MapActivity.CHAT_ID, chatId);
    startActivity(intent);
  }

  private void handleReturnToConversationList() {

    if (isRelayingMessageContent(this) || successfulForwardingAttempt) {
      if (isSharing(this)) {
        // we're allowing only 1 try to share, going back to the conversation list will
        // close the conversation list in activtyForResult() as well, so that the user
        // comes back to the extenal app's share menu
        setResult(RESULT_OK);
      }
      finish();
      return;
    }

    Intent intent = new Intent(this, (isArchived() ? ConversationListArchiveActivity.class : ConversationListActivity.class));
    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    startActivity(intent);
    finish();
  }

  private void handleMuteNotifications() {
    if(!dcChat.isMuted()) {
      MuteDialog.show(this, duration -> {
        dcContext.setChatMuteDuration(chatId, duration);
        titleView.setTitle(glideRequests, dcChat);
      });
    } else {
      // unmute
      dcContext.setChatMuteDuration(chatId, 0);
      titleView.setTitle(glideRequests, dcChat);
    }
  }

  private void handleProfile() {
    if(chatId != DcChat.DC_CHAT_ID_DEADDROP) {
      Intent intent = new Intent(this, ProfileActivity.class);
      intent.putExtra(ProfileActivity.CHAT_ID_EXTRA, chatId);
      intent.putExtra(ProfileActivity.FROM_CHAT, true);
      startActivity(intent);
      overridePendingTransition(0, 0);
    }
  }

  private void handleLeaveGroup() {
    new AlertDialog.Builder(this)
      .setMessage(getString(R.string.ask_leave_group))
      .setPositiveButton(R.string.yes, (dialog, which) -> {
        dcContext.removeContactFromChat(chatId, DcContact.DC_CONTACT_ID_SELF);
        Toast.makeText(this, getString(R.string.done), Toast.LENGTH_SHORT).show();
      })
      .setNegativeButton(R.string.no, null)
      .show();
  }

  private void handleArchiveChat() {
    int newVisibility = isArchived() ?
            DcChat.DC_CHAT_VISIBILITY_NORMAL : DcChat.DC_CHAT_VISIBILITY_ARCHIVED;
    dcContext.setChatVisibility(chatId, newVisibility);
    Toast.makeText(this, getString(R.string.done), Toast.LENGTH_SHORT).show();
    if (newVisibility==DcChat.DC_CHAT_VISIBILITY_ARCHIVED) {
      finish();
      return;
    }
    dcChat = dcContext.getChat(chatId);
  }

  private void handleDeleteChat() {
    new AlertDialog.Builder(this)
        .setMessage(getResources().getQuantityString(R.plurals.ask_delete_chat, 1, 1))
        .setPositiveButton(R.string.delete, (dialog, which) -> {
          dcContext.deleteChat(chatId);
          Toast.makeText(this, getString(R.string.done), Toast.LENGTH_SHORT).show();
          finish();
        })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  private void handleAddAttachment() {
    if (attachmentTypeSelector == null) {
      attachmentTypeSelector = new AttachmentTypeSelector(this, getSupportLoaderManager(), new AttachmentTypeListener(), chatId);
    }
    attachmentTypeSelector.show(this, attachButton);
  }

  private void handleSecurityChange(boolean isSecureText, boolean isDefaultSms) {
    Log.w(TAG, "handleSecurityChange(" + isSecureText + ", " + isDefaultSms + ")");
    if (isSecurityInitialized && isSecureText == this.isSecureText && isDefaultSms == this.isDefaultSms) {
      return;
    }

    this.isDefaultSms          = isDefaultSms;
    this.isSecurityInitialized = true;

    sendButton.resetAvailableTransports();
    sendButton.setDefaultTransport(Type.NORMAL_MAIL);
  }

  private void handleForwarding() {
    DcChat dcChat = dcContext.getChat(chatId);
    if (dcChat.isSelfTalk()) {
      SendRelayedMessageUtil.immediatelyRelay(this, chatId);
    } else {
      String name = dcChat.getName();
      if (!dcChat.isGroup()) {
        int[] contactIds = dcContext.getChatContacts(chatId);
        if (contactIds.length == 1 || contactIds.length == 2) {
          name = dcContext.getContact(contactIds[0]).getNameNAddr();
        }
      }
      new AlertDialog.Builder(this)
              .setMessage(getString(R.string.ask_forward, name))
              .setPositiveButton(R.string.ok, (dialogInterface, i) -> {
                SendRelayedMessageUtil.immediatelyRelay(this, chatId);
                successfulForwardingAttempt = true;
              })
              .setNegativeButton(R.string.cancel, (dialogInterface, i) -> finish())
              .show();
    }
  }

  private void handleSharing() {
    ArrayList<Uri> uriList =  RelayUtil.getSharedUris(this);
    if (uriList.size() > 1) {
      String message = String.format(getString(R.string.share_multiple_attachments), uriList.size());
      new AlertDialog.Builder(this)
              .setMessage(message)
              .setCancelable(false)
              .setNegativeButton(android.R.string.cancel, ((dialog, which) -> finish()))
              .setPositiveButton(R.string.menu_send, (dialog, which) -> SendRelayedMessageUtil.immediatelyRelay(this, chatId))
              .show();
    } else {
      if (uriList.isEmpty()) {
        dcContext.setDraft(chatId, SendRelayedMessageUtil.createMessage(this, null, getSharedText(this)));
      } else {
        dcContext.setDraft(chatId, SendRelayedMessageUtil.createMessage(this, uriList.get(0), getSharedText(this)));
      }
      initializeDraft().addListener(new AssertedSuccessListener<Boolean>() {
        @Override
        public void onSuccess(Boolean result) {
          isShareDraftInitialized = true;
        }
      });
    }
  }

  ///// Initializers

  /**
   * Drafts can be initialized by click on a mailto: link or from the database
   * @return
   */
  private ListenableFuture<Boolean> initializeDraft() {
    if (isMailToIntent()) {
      return initializeDraftFromIntent();
    } else {
      return initializeDraftFromDatabase();
    }
  }

  boolean isMailToIntent() {
    return getIntent() != null && getIntent().getData() != null && MAILTO.equals(getIntent().getData().getScheme());
  }

  private ListenableFuture<Boolean> initializeDraftFromIntent() {
    SettableFuture<Boolean> result = new SettableFuture<>();
    final String draftText = RelayUtil.getSharedText(this);

    if (draftText != null) {
      composeText.setText(draftText);
    }

    result.set(draftText != null);
    updateToggleButtonState();
    return result;
  }

  private void initializeEnabledCheck() {
    boolean enabled = true;
    inputPanel.setEnabled(enabled);
    sendButton.setEnabled(enabled);
    attachButton.setEnabled(enabled);
  }

  private ListenableFuture<Boolean> initializeDraftFromDatabase() {
    final SettableFuture<Boolean> future = new SettableFuture<>();
    DcMsg draft = dcContext.getDraft(chatId);

    if (draft == null) {
      future.set(false);
      updateToggleButtonState();
      return future;
    }

    final String text = draft.getText();
    if(!text.isEmpty()) {
      composeText.setText(text);
      composeText.setSelection(composeText.getText().length());
    }

    DcMsg quote = draft.getQuotedMsg();
    if (quote != null) {
      handleReplyMessage(quote);
    }

    String filename = draft.getFile();
    if (filename.isEmpty() || !new File(filename).exists()) {
      future.set(!text.isEmpty());
      updateToggleButtonState();
      return future;
    }

    ListenableFuture.Listener<Boolean> listener = new ListenableFuture.Listener<Boolean>() {
      @Override
      public void onSuccess(Boolean result) {
        future.set(result || !text.isEmpty());
        updateToggleButtonState();
      }

      @Override
      public void onFailure(ExecutionException e) {
        future.set(!text.isEmpty());
        updateToggleButtonState();
      }
    };

    File file = new File(filename);
    Uri uri = Uri.fromFile(file);
    switch (draft.getType()) {
      case DcMsg.DC_MSG_IMAGE:
        setMedia(uri, MediaType.IMAGE).addListener(listener);
        break;
      case DcMsg.DC_MSG_GIF:
        setMedia(uri, MediaType.GIF).addListener(listener);
        break;
      case DcMsg.DC_MSG_AUDIO:
        setMedia(uri, MediaType.AUDIO).addListener(listener);
        break;
      case DcMsg.DC_MSG_VIDEO:
        setMedia(uri, MediaType.VIDEO).addListener(listener);
        break;
      default:
        setMedia(uri, MediaType.DOCUMENT).addListener(listener);
        break;
    }

    return future;
  }

  private ListenableFuture<Boolean> initializeSecurity(final boolean currentSecureText,
                                                       final boolean currentIsDefaultSms)
  {
    final SettableFuture<Boolean> future = new SettableFuture<>();

    handleSecurityChange(currentSecureText || isPushGroupConversation(), currentIsDefaultSms);

    future.set(true);
    return future;
  }

  private void initializeViews() {
    ActionBar supportActionBar = getSupportActionBar();
    if (supportActionBar == null) throw new AssertionError();

    titleView             = (ConversationTitleView) supportActionBar.getCustomView();
    buttonToggle          = ViewUtil.findById(this, R.id.button_toggle);
    sendButton            = ViewUtil.findById(this, R.id.send_button);
    attachButton          = ViewUtil.findById(this, R.id.attach_button);
    composeText           = ViewUtil.findById(this, R.id.embedded_text_editor);
    emojiDrawerStub       = ViewUtil.findStubById(this, R.id.emoji_drawer_stub);
    composePanel          = ViewUtil.findById(this, R.id.bottom_panel);
    container             = ViewUtil.findById(this, R.id.layout_container);
    quickAttachmentDrawer = ViewUtil.findById(this, R.id.quick_attachment_drawer);
    quickAttachmentToggle = ViewUtil.findById(this, R.id.quick_attachment_toggle);
    inputPanel            = ViewUtil.findById(this, R.id.bottom_panel);
    backgroundView        = ViewUtil.findById(this, R.id.conversation_background);
    messageRequestBottomView = ViewUtil.findById(this, R.id.conversation_activity_message_request_bottom_bar);

    ImageButton quickCameraToggle = ViewUtil.findById(this, R.id.quick_camera_toggle);

    container.addOnKeyboardShownListener(this);
    container.addOnKeyboardHiddenListener(backgroundView);
    container.addOnKeyboardShownListener(backgroundView);
    inputPanel.setListener(this);
    inputPanel.setMediaListener(this);

    attachmentTypeSelector = null;
    attachmentManager      = new AttachmentManager(this, this);
    audioRecorder          = new AudioRecorder(this);

    SendButtonListener        sendButtonListener        = new SendButtonListener();
    ComposeKeyPressedListener composeKeyPressedListener = new ComposeKeyPressedListener();

    composeText.setOnEditorActionListener(sendButtonListener);
    attachButton.setOnClickListener(new AttachButtonListener());
    attachButton.setOnLongClickListener(new AttachButtonLongClickListener());
    sendButton.setOnClickListener(sendButtonListener);
    sendButton.setEnabled(true);
    sendButton.addOnTransportChangedListener((newTransport, manuallySelected) -> {
      composeText.setTransport(newTransport);
      buttonToggle.getBackground().invalidateSelf();
    });

    titleView.setOnClickListener(v -> handleProfile());
    titleView.setOnBackClickedListener(view -> handleReturnToConversationList());

    composeText.setOnKeyListener(composeKeyPressedListener);
    composeText.addTextChangedListener(composeKeyPressedListener);
    composeText.setOnEditorActionListener(sendButtonListener);
    composeText.setOnClickListener(composeKeyPressedListener);
    composeText.setOnFocusChangeListener(composeKeyPressedListener);

    if (QuickAttachmentDrawer.isDeviceSupported(this)) {
      quickAttachmentDrawer.setListener(this);
      quickCameraToggle.setOnClickListener(new QuickCameraToggleListener());
    } else {
      quickCameraToggle.setVisibility(View.GONE);
      quickCameraToggle.setEnabled(false);
    }

    initializeBackground();
  }

  private void initializeBackground() {
    String backgroundImagePath = Prefs.getBackgroundImagePath(this);
    Drawable background;
    if(!backgroundImagePath.isEmpty()) {
      background = Drawable.createFromPath(backgroundImagePath);
    }
    else if(DynamicTheme.isDarkTheme(this)) {
      background = getResources().getDrawable(R.drawable.background_hd_dark);
    }
    else {
      background = getResources().getDrawable(R.drawable.background_hd);
    }
    backgroundView.setImageDrawable(background);
  }

  protected void initializeActionBar() {
    ActionBar supportActionBar = getSupportActionBar();
    if (supportActionBar == null) throw new AssertionError();

    supportActionBar.setDisplayHomeAsUpEnabled(false);
    supportActionBar.setCustomView(R.layout.conversation_title_view);
    supportActionBar.setDisplayShowCustomEnabled(true);
    supportActionBar.setDisplayShowTitleEnabled(false);

    Toolbar parent = (Toolbar) supportActionBar.getCustomView().getParent();
    parent.setPadding(0,0,0,0);
    parent.setContentInsetsAbsolute(0,0);
  }

  private void initializeResources() {
    chatId = getIntent().getIntExtra(CHAT_ID_EXTRA, -1);
    if(chatId == DcChat.DC_CHAT_NO_CHAT)
      throw new IllegalStateException("can't display a conversation for no chat.");
    dcChat           = dcContext.getChat(chatId);
    recipient        = dcContext.getRecipient(dcChat);
    glideRequests    = GlideApp.with(this);


    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
      LinearLayout conversationContainer = ViewUtil.findById(this, R.id.conversation_container);
      conversationContainer.setClipChildren(true);
      conversationContainer.setClipToPadding(true);
    }

    if (!dcChat.canSend()) {
      composePanel.setVisibility(View.GONE);
    }

    if (chatId == DcChat.DC_CHAT_ID_DEADDROP) {
      titleView.hideAvatar();
    }
  }

  //////// Helper Methods

  private void addAttachment(int type) {
    Log.w("ComposeMessageActivity", "Selected: " + type);
    switch (type) {
    case AttachmentTypeSelector.ADD_GALLERY:
      AttachmentManager.selectGallery(this, PICK_GALLERY); break;
    case AttachmentTypeSelector.ADD_DOCUMENT:
      AttachmentManager.selectDocument(this, PICK_DOCUMENT); break;
    case AttachmentTypeSelector.ADD_SOUND:
      AttachmentManager.selectAudio(this, PICK_AUDIO); break;
    case AttachmentTypeSelector.ADD_CONTACT_INFO:
      startContactChooserActivity(); break;
    case AttachmentTypeSelector.ADD_LOCATION:
      AttachmentManager.selectLocation(this, chatId); break;
    case AttachmentTypeSelector.TAKE_PHOTO:
      attachmentManager.capturePhoto(this, TAKE_PHOTO); break;
    case AttachmentTypeSelector.RECORD_VIDEO:
      if(VideoRecoder.canRecode()) {
        attachmentManager.captureVideo(this, RECORD_VIDEO);
      }
      else {
        Toast.makeText(this, "This device does not support video-compression (requires Android 4.4 KitKat)", Toast.LENGTH_LONG).show();
      }
      break;
    }
  }

  private void startContactChooserActivity() {
    Intent intent = new Intent(ConversationActivity.this, BlockedAndShareContactsActivity.class);
    startActivityForResult(intent, PICK_CONTACT);
  }

  private ListenableFuture<Boolean> setMedia(@Nullable Uri uri, @NonNull MediaType mediaType) {
    if (uri == null) {
      return new SettableFuture<>(false);
    }

    return attachmentManager.setMedia(glideRequests, uri, mediaType, 0, 0);
  }

  private void addAttachmentContactInfo(Intent data) {
    String name = data.getStringExtra(BlockedAndShareContactsActivity.SHARE_CONTACT_NAME_EXTRA);
    String mail = data.getStringExtra(BlockedAndShareContactsActivity.SHARE_CONTACT_MAIL_EXTRA);
    composeText.append(name + "\n" + mail);
  }

  private boolean isGroupConversation() {
    return dcChat.isGroup();
  }

  private boolean isPushGroupConversation() {
    return isGroupConversation(); // push groups are non-sms groups, so in delta, these are all groups
  }

  private boolean isArchived() {
    return dcChat.getVisibility() == DcChat.DC_CHAT_VISIBILITY_ARCHIVED;
  }

  private String getRealPathFromAttachment(Attachment attachment) {
    try {
      // get file in the blobdir as `<blobdir>/<name>[-<uniqueNumber>].<ext>`
      String filename = attachment.getFileName();
      String ext = "";
      if(filename==null) {
        filename = new SimpleDateFormat("yyyy-MM-dd-HH-mm").format(new Date());
        ext = "." + MediaUtil.getExtensionFromMimeType(attachment.getContentType());
      }
      else {
        int i = filename.lastIndexOf(".");
        if(i>=0) {
          ext = filename.substring(i);
          filename = filename.substring(0, i);
        }
      }
      String path = dcContext.getBlobdirFile(filename, ext);

      // copy content to this file
      if(path!=null) {
        InputStream inputStream = PartAuthority.getAttachmentStream(this, attachment.getDataUri());
        OutputStream outputStream = new FileOutputStream(path);
        Util.copy(inputStream, outputStream);
      }

      return path;
    }
    catch(Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  //////// send message or save draft

  protected static final int ACTION_SEND_OUT = 1;
  protected static final int ACTION_SAVE_DRAFT = 2;

  protected ListenableFuture<Integer> processComposeControls(int action) {
    return processComposeControls(action, composeText.getTextTrimmed(),
      attachmentManager.isAttachmentPresent() ?
        attachmentManager.buildSlideDeck() : null);
  }

  protected ListenableFuture<Integer> processComposeControls(final int action, String body, SlideDeck slideDeck) {

    final SettableFuture<Integer> future  = new SettableFuture<>();

    DcMsg msg = null;
    Optional<QuoteModel> quote = inputPanel.getQuote();
    Integer recompress = 0;

    // for a quick ui feedback, we clear the related controls immediately on sending messages.
    // for drafts, however, we do not change the controls, the activity may be resumed.
    if (action==ACTION_SEND_OUT) {
      composeText.setText("");
      inputPanel.clearQuote();
    }

    if(slideDeck!=null) {

      if (action==ACTION_SEND_OUT) {
        attachmentManager.clear(glideRequests, false);
      }

      try {
        List<Attachment> attachments = slideDeck.asAttachments();
        for (Attachment attachment : attachments) {
          String contentType = attachment.getContentType();
          if (MediaUtil.isImageType(contentType) && slideDeck.getDocumentSlide()==null) {
            msg = new DcMsg(dcContext,
                    MediaUtil.isGif(contentType) ? DcMsg.DC_MSG_GIF : DcMsg.DC_MSG_IMAGE);
            msg.setDimension(attachment.getWidth(), attachment.getHeight());
          }
          else if (MediaUtil.isAudioType(contentType)) {
            msg = new DcMsg(dcContext,
                attachment.isVoiceNote()? DcMsg.DC_MSG_VOICE : DcMsg.DC_MSG_AUDIO);
          }
          else if (MediaUtil.isVideoType(contentType) && slideDeck.getDocumentSlide()==null) {
            msg = new DcMsg(dcContext, DcMsg.DC_MSG_VIDEO);
            recompress = DcMsg.DC_MSG_VIDEO;
          }
          else {
            msg = new DcMsg(dcContext, DcMsg.DC_MSG_FILE);
          }
          String path = getRealPathFromAttachment(attachment);
          msg.setFile(path, null);
          msg.setText(body);
        }
      }
      catch(Exception e) {
        e.printStackTrace();
      }
    }
    else if (!body.isEmpty()){
      msg = new DcMsg(dcContext, DcMsg.DC_MSG_TEXT);
      msg.setText(body);
    }

    if (quote.isPresent()) {
      if (msg == null) msg = new DcMsg(dcContext, DcMsg.DC_MSG_TEXT);
      msg.setQuote(quote.get().getQuotedMsg());
    }

    // msg may still be null to clear drafts
    new AsyncTask<Object, Void, Void>() {
      @Override
      protected Void doInBackground(Object... param) {
        DcMsg msg = (DcMsg)param[0];
        Integer recompress = (Integer)param[1];
        if (action==ACTION_SEND_OUT) {
          dcContext.setDraft(dcChat.getId(), null);

          if(msg!=null)
          {
            boolean doSend = true;
            if (recompress==DcMsg.DC_MSG_VIDEO) {
              doSend = VideoRecoder.prepareVideo(ConversationActivity.this, dcChat.getId(), msg);
            }

            if (doSend) {
              dcContext.sendMsg(dcChat.getId(), msg);
            }

            Util.runOnMain(()-> sendComplete(dcChat.getId()));
          }
        }
        else {
          dcContext.setDraft(dcChat.getId(), msg);
        }
        return null;
      }

      @Override
      protected void onPostExecute(Void result) {
        future.set(chatId);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, msg, recompress);

    return future;
  }


  protected void sendComplete(int chatId) {
    boolean refreshFragment = (chatId != this.chatId);
    this.chatId = chatId;

    if (fragment == null || !fragment.isVisible() || isFinishing()) {
      return;
    }

    fragment.setLastSeen(-1);

    if (refreshFragment) {
      fragment.reload(recipient, chatId);
      dcContext.notificationCenter.updateVisibleChat(chatId);
    }

    fragment.scrollToBottom();
    attachmentManager.cleanup();

    if (isShareDraftInitialized) {
      isShareDraftInitialized = false;
      setResult(RESULT_OK);
    }
  }


  // handle attachment drawer, camera, recorder

  private void updateToggleButtonState() {
    if (inputPanel.isRecordingInLockedMode()) {
      buttonToggle.display(sendButton);
      quickAttachmentToggle.hide();
      return;
    }

    if (composeText.getText().length() == 0 && !attachmentManager.isAttachmentPresent()) {
      buttonToggle.display(attachButton);
      quickAttachmentToggle.show();
    } else {
      buttonToggle.display(sendButton);
      quickAttachmentToggle.hide();
    }
  }

  @Override
  public void onAttachmentDrawerStateChanged(DrawerState drawerState) {
    ActionBar supportActionBar = getSupportActionBar();
    if (supportActionBar == null) throw new AssertionError();

    if (drawerState == DrawerState.FULL_EXPANDED) {
      supportActionBar.hide();
    } else {
      supportActionBar.show();
    }

    if (drawerState == DrawerState.COLLAPSED) {
      container.hideAttachedInput(true);
    }
  }

  @Override
  public void onImageCapture(@NonNull final byte[] imageBytes) {
    setMedia(PersistentBlobProvider.getInstance()
                                   .create(this, imageBytes, MediaUtil.IMAGE_JPEG, null),
             MediaType.IMAGE);
    quickAttachmentDrawer.hide(false);
  }

  @Override
  public void onCameraFail() {
    Toast.makeText(this, R.string.chat_camera_unavailable, Toast.LENGTH_SHORT).show();
    quickAttachmentDrawer.hide(false);
    quickAttachmentToggle.disable();
  }

  @Override
  public void onCameraStart() {}

  @Override
  public void onCameraStop() {}

  @Override
  public void onRecorderPermissionRequired() {
    Permissions.with(this)
               .request(Manifest.permission.RECORD_AUDIO)
               .ifNecessary()
               .withPermanentDenialDialog(getString(R.string.perm_explain_access_to_mic_denied))
               .execute();
  }

  @Override
  public void onRecorderStarted() {
    Vibrator vibrator = ServiceUtil.getVibrator(this);
    vibrator.vibrate(20);

    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    audioRecorder.startRecording();
  }

  @Override
  public void onRecorderLocked() {
    updateToggleButtonState();
    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
  }

  @Override
  public void onRecorderFinished() {
    updateToggleButtonState();
    Vibrator vibrator = ServiceUtil.getVibrator(this);
    vibrator.vibrate(20);

    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    ListenableFuture<Pair<Uri, Long>> future = audioRecorder.stopRecording();
    future.addListener(new ListenableFuture.Listener<Pair<Uri, Long>>() {
      @Override
      public void onSuccess(final @NonNull Pair<Uri, Long> result) {
        AudioSlide audioSlide     = new AudioSlide(ConversationActivity.this, result.first, result.second, MediaUtil.AUDIO_AAC, true);
        SlideDeck  slideDeck      = new SlideDeck();
        slideDeck.addSlide(audioSlide);

        processComposeControls(ACTION_SEND_OUT, "", slideDeck).addListener(new AssertedSuccessListener<Integer>() {
          @Override
          public void onSuccess(Integer chatId) {
            new AsyncTask<Void, Void, Void>() {
              @Override
              protected Void doInBackground(Void... params) {
                PersistentBlobProvider.getInstance().delete(ConversationActivity.this, result.first);
                return null;
              }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
          }
        });
      }

      @Override
      public void onFailure(ExecutionException e) {
        Toast.makeText(ConversationActivity.this, R.string.chat_unable_to_record_audio, Toast.LENGTH_LONG).show();
      }
    });
  }

  @Override
  public void onRecorderCanceled() {
    updateToggleButtonState();
    Vibrator vibrator = ServiceUtil.getVibrator(this);
    vibrator.vibrate(50);

    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    ListenableFuture<Pair<Uri, Long>> future = audioRecorder.stopRecording();
    future.addListener(new ListenableFuture.Listener<Pair<Uri, Long>>() {
      @Override
      public void onSuccess(final Pair<Uri, Long> result) {
        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {
            PersistentBlobProvider.getInstance().delete(ConversationActivity.this, result.first);
            return null;
          }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
      }

      @Override
      public void onFailure(ExecutionException e) {}
    });
  }

  @Override
  public void onEmojiToggle() {
    if (!emojiDrawerStub.resolved()) {
      initializeMediaKeyboardProviders(emojiDrawerStub.get(), false);
      inputPanel.setMediaKeyboard(emojiDrawerStub.get());
    }

    if (container.getCurrentInput() == emojiDrawerStub.get()) {
      container.showSoftkey(composeText);
    } else {
      container.show(composeText, emojiDrawerStub.get());
    }
  }

  // media selected by the system keyboard
  @Override
  public void onMediaSelected(@NonNull Uri uri, String contentType) {
    if (MediaUtil.isImageType(contentType)) {
      sendSticker(uri, contentType);
    } else if (MediaUtil.isVideoType(contentType)) {
      setMedia(uri, MediaType.VIDEO);
    } else if (MediaUtil.isAudioType(contentType)) {
      setMedia(uri, MediaType.AUDIO);
    }
  }

  private void sendSticker(@NonNull Uri uri, String contentType) {
    Attachment attachment = new UriAttachment(uri, null, contentType,
      AttachmentDatabase.TRANSFER_PROGRESS_STARTED, 0, 0, 0, null, null, false);
    String path = getRealPathFromAttachment(attachment);

    Optional<QuoteModel> quote = inputPanel.getQuote();
    inputPanel.clearQuote();

    DcMsg msg = new DcMsg(dcContext, DcMsg.DC_MSG_STICKER);
    if (quote.isPresent()) {
      msg.setQuote(quote.get().getQuotedMsg());
    }
    msg.setFile(path, null);
    dcContext.sendMsg(chatId, msg);
  }

  private void initializeMediaKeyboardProviders(@NonNull MediaKeyboard mediaKeyboard, boolean stickersAvailable) {
    boolean isSystemEmojiPreferred   = Prefs.isSystemEmojiPreferred(this);
    if (!isSystemEmojiPreferred) {
      mediaKeyboard.setProviders(0, new EmojiKeyboardProvider(this, inputPanel));
    }
  }

  // Listeners

  private class AttachmentTypeListener implements AttachmentTypeSelector.AttachmentClickedListener {
    @Override
    public void onClick(int type) {
      addAttachment(type);
    }

    @Override
    public void onQuickAttachment(Uri uri) {
      Intent intent = new Intent();
      intent.setData(uri);

      onActivityResult(PICK_GALLERY, RESULT_OK, intent);
    }
  }

  private class QuickCameraToggleListener implements OnClickListener {
    @Override
    public void onClick(View v) {
      if (!quickAttachmentDrawer.isShowing()) {
        Permissions.with(ConversationActivity.this)
                   .request(Manifest.permission.CAMERA)
                   .ifNecessary()
                   .withPermanentDenialDialog(getString(R.string.perm_explain_access_to_camera_denied))
                   .onAllGranted(() -> {
                     composeText.clearFocus();
                     container.show(composeText, quickAttachmentDrawer);
                   })
                   .execute();
      } else {
        container.hideAttachedInput(false);
      }
    }
  }

  private class SendButtonListener implements OnClickListener, TextView.OnEditorActionListener {
    @Override
    public void onClick(View v) {
      if (inputPanel.isRecordingInLockedMode()) {
        inputPanel.releaseRecordingLock();
        return;
      }

      String rawText = composeText.getTextTrimmed();
      if (rawText.length() < 1 && !attachmentManager.isAttachmentPresent()) {
        Toast.makeText(ConversationActivity.this, R.string.chat_please_enter_message,
            Toast.LENGTH_SHORT).show();
      }
      else {
        processComposeControls(ACTION_SEND_OUT);
        dcContext.notificationCenter.maybePlaySendSound(dcChat);
      }
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
      if (actionId == EditorInfo.IME_ACTION_SEND) {
        sendButton.performClick();
        return true;
      }
      return false;
    }
  }

  private class AttachButtonListener implements OnClickListener {
    @Override
    public void onClick(View v) {
      handleAddAttachment();
    }
  }

  private class AttachButtonLongClickListener implements View.OnLongClickListener {
    @Override
    public boolean onLongClick(View v) {
      return sendButton.performLongClick();
    }
  }

  private class ComposeKeyPressedListener implements OnKeyListener, OnClickListener, TextWatcher, OnFocusChangeListener {

    int beforeLength;

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
      if (event.getAction() == KeyEvent.ACTION_DOWN) {
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
          if (Prefs.isEnterSendsEnabled(ConversationActivity.this)) {
            sendButton.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            sendButton.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            return true;
          }
        }
      }
      return false;
    }

    @Override
    public void onClick(View v) {
      container.showSoftkey(composeText);
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count,int after) {
      beforeLength = composeText.getTextTrimmed().length();
    }

    @Override
    public void afterTextChanged(Editable s) {
      if (composeText.getTextTrimmed().length() == 0 || beforeLength == 0) {
        composeText.postDelayed(ConversationActivity.this::updateToggleButtonState, 50);
      }
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before,int count) {}

    @Override
    public void onFocusChange(View v, boolean hasFocus) {}
  }

  @Override
  public void handleReplyMessage(DcMsg msg) {
    // If you modify these lines you may also want to modify ConversationItem.setQuote():
    Recipient author = dcContext.getRecipient(dcContext.getContact(msg.getFromId()));

    SlideDeck slideDeck = new SlideDeck();
    if (msg.getType() != DcMsg.DC_MSG_TEXT) {
      slideDeck.addSlide(MediaUtil.getSlideForMsg(this, msg));
    }

    String text = msg.getSummarytext(500);

    inputPanel.setQuote(GlideApp.with(this),
            msg,
            msg.getTimestamp(),
            author,
            text,
            slideDeck);

    inputPanel.clickOnComposeInput();
  }

  @Override
  public void onAttachmentChanged() {
    handleSecurityChange(isSecureText, isDefaultSms);
    updateToggleButtonState();
  }

  @Override
  public void handleEvent(DcEvent event) {
    int eventId = event.getId();
    if ((eventId == DcContext.DC_EVENT_CHAT_MODIFIED && event.getData1Int() == chatId)
     || (eventId == DcContext.DC_EVENT_CHAT_EPHEMERAL_TIMER_MODIFIED && event.getData1Int() == chatId)
     || eventId == DcContext.DC_EVENT_CONTACTS_CHANGED) {
      dcChat = dcContext.getChat(chatId);
      titleView.setTitle(glideRequests, dcChat);
      initializeSecurity(isSecureText, isDefaultSms);
    }
  }


  // in-chat search

  private int beforeSearchComposeVisibility = View.VISIBLE;
  private int beforeSearchAttachVisibility = View.GONE;

  private Menu  searchMenu = null;
  private int[] searchResult = {};
  private int   searchResultPosition = -1;

  private Toast lastToast = null;

  private void updateResultCounter(int curr, int total) {
    if (searchMenu!=null) {
      MenuItem item = searchMenu.findItem(R.id.menu_search_counter);
      if (curr!=-1) {
        item.setTitle(String.format("%d/%d", total==0? 0 : curr+1, total));
        item.setVisible(true);
      } else {
        item.setVisible(false);
      }
    }
  }

  private void searchExpand(final Menu menu, final MenuItem searchItem) {
    searchMenu = menu;

    beforeSearchComposeVisibility = composePanel.getVisibility();
    composePanel.setVisibility(View.GONE);

    beforeSearchAttachVisibility = attachmentManager.getVisibility();
    attachmentManager.setVisibility(View.GONE);

    ConversationActivity.this.makeSearchMenuVisible(menu, searchItem, true);
  }

  private void searchCollapse(final Menu menu, final MenuItem searchItem) {
    searchMenu = null;
    composePanel.setVisibility(beforeSearchComposeVisibility);
    attachmentManager.setVisibility(beforeSearchAttachVisibility);

    ConversationActivity.this.makeSearchMenuVisible(menu, searchItem, false);
  }

  private void handleMenuSearchNext(boolean searchNext) {
    if(searchResult.length>0) {
      searchResultPosition += searchNext? 1 : -1;
      if(searchResultPosition<0) searchResultPosition = searchResult.length-1;
      if(searchResultPosition>=searchResult.length) searchResultPosition = 0;
      fragment.scrollToMsgId(searchResult[searchResultPosition]);
      updateResultCounter(searchResultPosition, searchResult.length);
    } else {
      // no search, scroll to first/last message
      if(searchNext) {
        fragment.scrollToBottom();
      } else {
        fragment.scrollToTop();
      }
    }
  }

  @Override
  public boolean onQueryTextSubmit(String query) {
    return true; // action handled by listener
  }

  @Override
  public boolean onQueryTextChange(String query) {
    if (lastToast!=null) {
      lastToast.cancel();
      lastToast = null;
    }

    String normQuery = query.trim();
    searchResult = dcContext.searchMsgs(chatId, normQuery);

    if(searchResult.length>0) {
      searchResultPosition = searchResult.length - 1;
      fragment.scrollToMsgId(searchResult[searchResultPosition]);
      updateResultCounter(searchResultPosition, searchResult.length);
    } else {
      searchResultPosition = -1;
      if (normQuery.isEmpty()) {
        updateResultCounter(-1, 0); // hide
      } else {
        String msg = getString(R.string.search_no_result_for_x, normQuery);
        if (lastToast != null) {
          lastToast.cancel();
        }
        lastToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        lastToast.show();
        updateResultCounter(0, 0); // show as "0/0"
      }
    }
    return true; // action handled by listener
  }

  private boolean isInMessageRequest() {
    return messageRequestBottomView.getVisibility() == View.VISIBLE;
  }

  public void onMessageRequest(@NonNull MessageRequestViewModel viewModel) {
    messageRequestBottomView.setAcceptOnClickListener(v -> viewModel.onAccept());
    messageRequestBottomView.setDeleteOnClickListener(v -> onMessageRequestDeleteClicked(viewModel));
    messageRequestBottomView.setBlockOnClickListener(v -> onMessageRequestBlockClicked(viewModel));
   );
  }

}
