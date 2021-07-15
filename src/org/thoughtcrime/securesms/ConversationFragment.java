/*
 * Copyright (C) 2015 Open Whisper Systems
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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.OnScrollListener;

import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;
import com.b44t.messenger.DcMsg;

import org.thoughtcrime.securesms.ConversationAdapter.ItemClickListener;
import org.thoughtcrime.securesms.components.reminder.DozeReminder;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.AccessibilityUtil;
import org.thoughtcrime.securesms.util.Debouncer;
import org.thoughtcrime.securesms.util.StickyHeaderDecoration;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.thoughtcrime.securesms.util.views.ConversationAdaptiveActionsToolbar;
import org.thoughtcrime.securesms.videochat.VideochatUtil;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import static com.b44t.messenger.DcContact.DC_CONTACT_ID_SELF;
import static org.thoughtcrime.securesms.util.RelayUtil.REQUEST_RELAY;
import static org.thoughtcrime.securesms.util.RelayUtil.setForwardingMessageIds;

@SuppressLint("StaticFieldLeak")
public class ConversationFragment extends MessageSelectorFragment
{
    private static final String TAG       = ConversationFragment.class.getSimpleName();
    private static final String KEY_LIMIT = "limit";

    private static final int SCROLL_ANIMATION_THRESHOLD = 50;
    private static final int CODE_ADD_EDIT_CONTACT      = 77;

    private final ActionModeCallback actionModeCallback     = new ActionModeCallback();
    private final ItemClickListener  selectionClickListener = new ConversationFragmentItemClickListener();

    private ConversationFragmentListener listener;

    private Recipient                   recipient;
    private long                        chatId;
    private int                         startingPosition;
    private boolean                     firstLoad;
    private Locale                      locale;
    private RecyclerView                list;
    private RecyclerView.ItemDecoration lastSeenDecoration;
    private StickyHeaderDecoration      dateDecoration;
    private View                        scrollToBottomButton;
    private View                        floatingLocationButton;
    private TextView                    noMessageTextView;
    private Timer                       reloadTimer;

    public boolean isPaused;
    private Debouncer markseenDebouncer;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        this.locale = (Locale) getArguments().getSerializable(PassphraseRequiredActionBarActivity.LOCALE_EXTRA);
        this.dcContext = DcHelper.getContext(getContext());

        dcContext.eventCenter.addObserver(DcContext.DC_EVENT_INCOMING_MSG, this);
        dcContext.eventCenter.addObserver(DcContext.DC_EVENT_MSGS_CHANGED, this);
        dcContext.eventCenter.addObserver(DcContext.DC_EVENT_MSG_DELIVERED, this);
        dcContext.eventCenter.addObserver(DcContext.DC_EVENT_MSG_FAILED, this);
        dcContext.eventCenter.addObserver(DcContext.DC_EVENT_MSG_READ, this);
        dcContext.eventCenter.addObserver(DcContext.DC_EVENT_CHAT_MODIFIED, this);

        markseenDebouncer = new Debouncer(800);
        reloadTimer = new Timer("reloadTimer", false);
        reloadTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                Util.runOnMain(ConversationFragment.this::reloadList);
            }
        }, 60 * 1000, 60 * 1000);
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle bundle) {
        final View view = inflater.inflate(R.layout.conversation_fragment, container, false);
        list                   = ViewUtil.findById(view, android.R.id.list);
        scrollToBottomButton   = ViewUtil.findById(view, R.id.scroll_to_bottom_button);
        floatingLocationButton = ViewUtil.findById(view, R.id.floating_location_button);
        noMessageTextView      = ViewUtil.findById(view, R.id.no_messages_text_view);

        scrollToBottomButton.setOnClickListener(v -> scrollToBottom());

        final SetStartingPositionLinearLayoutManager layoutManager = new SetStartingPositionLinearLayoutManager(getActivity(), LinearLayoutManager.VERTICAL, true);

        list.setHasFixedSize(false);
        list.setLayoutManager(layoutManager);
        list.setItemAnimator(null);

        new ConversationItemSwipeCallback(
                msg -> actionMode == null,
                this::handleReplyMessage
        ).attachToRecyclerView(list);

        // setLayerType() is needed to allow larger items (long texts in our case)
        // with hardware layers, drawing may result in errors as "OpenGLRenderer: Path too large to be rendered into a texture"
        list.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        return view;
    }

    @Override
    public void onActivityCreated(Bundle bundle) {
        super.onActivityCreated(bundle);

        initializeResources();
        initializeListAdapter();
    }

    private void setNoMessageText() {
        DcChat dcChat = getListAdapter().getChat();
        if(chatId == DcChat.DC_CHAT_ID_DEADDROP) {
            if(DcHelper.getInt(getActivity(), "show_emails")!= DcContext.DC_SHOW_EMAILS_ALL) {
                noMessageTextView.setText(R.string.chat_no_contact_requests);
            }
            else {
                noMessageTextView.setText(R.string.chat_no_messages);
            }
        }
        else if(dcChat.isGroup()){
            if(dcContext.getChat((int) chatId).isUnpromoted()) {
                noMessageTextView.setText(R.string.chat_new_group_hint);
            }
            else {
                noMessageTextView.setText(R.string.chat_no_messages);
            }
        }
        else if(dcChat.isSelfTalk()) {
            noMessageTextView.setText(R.string.saved_messages_explain);
        }
        else if(dcChat.isDeviceTalk()) {
            noMessageTextView.setText(R.string.device_talk_explain);
        }
        else {
            String message = getString(R.string.chat_no_messages_hint, dcChat.getName(), dcChat.getName());
            noMessageTextView.setText(message);
        }
    }

    @Override
    public void onDestroy() {
        dcContext.eventCenter.removeObservers(this);
        reloadTimer.cancel();
        super.onDestroy();
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        this.listener = (ConversationFragmentListener)activity;
    }

    @Override
    public void onResume() {
        super.onResume();

        dcContext.marknoticedChat((int) chatId);
        if (list.getAdapter() != null) {
            list.getAdapter().notifyDataSetChanged();
        }

        if (isPaused) {
            isPaused = false;
            markseenDebouncer.publish(() -> manageMessageSeenState());
        }
    }


    @Override
    public void onPause() {
        super.onPause();
        setLastSeen(System.currentTimeMillis());
        isPaused = true;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        dateDecoration.onConfigurationChanged(newConfig);
    }

    public void onNewIntent() {
        if (actionMode != null) {
            actionMode.finish();
        }

        initializeResources();
        initializeListAdapter();

        if (chatId == -1) {
            reloadList();
            updateLocationButton();
        }
    }

    public void moveToLastSeen() {
        if (list == null || getListAdapter() == null) {
            Log.w(TAG, "Tried to move to last seen position, but we hadn't initialized the view yet.");
            return;
        }

        if (getListAdapter().getLastSeenPosition() < 0) {
            return;
        }
        final int lastSeenPosition = getListAdapter().getLastSeenPosition() + 1;
        if (lastSeenPosition > 0) {
            list.post(() -> ((LinearLayoutManager)list.getLayoutManager()).scrollToPositionWithOffset(lastSeenPosition, list.getHeight()));
        }
    }

    private void initializeResources() {
        this.chatId            = this.getActivity().getIntent().getIntExtra(ConversationActivity.CHAT_ID_EXTRA, -1);
        this.recipient         = Recipient.from(getActivity(), Address.fromChat((int)this.chatId));
        this.startingPosition  = this.getActivity().getIntent().getIntExtra(ConversationActivity.STARTING_POSITION_EXTRA, -1);
        this.firstLoad         = true;

        OnScrollListener scrollListener = new ConversationScrollListener(getActivity());
        list.addOnScrollListener(scrollListener);
    }

    private void initializeListAdapter() {
        if (this.recipient != null && this.chatId != -1) {
            ConversationAdapter adapter = new ConversationAdapter(getActivity(), this.recipient.getChat(), GlideApp.with(this), locale, selectionClickListener, this.recipient);
            list.setAdapter(adapter);

            dateDecoration = new StickyHeaderDecoration(adapter, false, false);
            list.addItemDecoration(dateDecoration);

            int freshMsgs = dcContext.getFreshMsgCount((int) chatId);
            SetStartingPositionLinearLayoutManager layoutManager = (SetStartingPositionLinearLayoutManager) list.getLayoutManager();
            if (startingPosition > -1) {
                layoutManager.setStartingPosition(startingPosition);
            } else if (freshMsgs > 0) {
                layoutManager.setStartingPosition(freshMsgs - 1);
            }

            reloadList();
            updateLocationButton();

            if (freshMsgs > 0) {
                getListAdapter().setLastSeenPosition(freshMsgs - 1);
                if (lastSeenDecoration != null) {
                    list.removeItemDecoration(lastSeenDecoration);
                }
                lastSeenDecoration = new ConversationAdapter.LastSeenHeader(getListAdapter());
                list.addItemDecoration(lastSeenDecoration);
            }
        }
    }

    @Override
    protected void setCorrectMenuVisibility(Menu menu) {
        Set<DcMsg>         messageRecords = getListAdapter().getSelectedItems();

        if (actionMode != null && messageRecords.size() == 0) {
            actionMode.finish();
            return;
        }

        if (messageRecords.size() > 1) {
            menu.findItem(R.id.menu_context_details).setVisible(false);
            menu.findItem(R.id.menu_context_share).setVisible(false);
            menu.findItem(R.id.menu_context_save_attachment).setVisible(false);
            menu.findItem(R.id.menu_context_reply).setVisible(false);
            menu.findItem(R.id.menu_context_reply_privately).setVisible(false);
        } else {
            DcMsg messageRecord = messageRecords.iterator().next();
            DcChat chat = getListAdapter().getChat();
            menu.findItem(R.id.menu_context_details).setVisible(true);
            menu.findItem(R.id.menu_context_share).setVisible(messageRecord.hasFile());
            menu.findItem(R.id.menu_context_save_attachment).setVisible(messageRecord.hasFile());
            boolean canReply = canReplyToMsg(messageRecord);
            menu.findItem(R.id.menu_context_reply).setVisible(chat.canSend() && canReply);
            boolean showReplyPrivately = chat.isGroup() && !messageRecord.isOutgoing() && canReply && chat.getId() != DcChat.DC_CHAT_ID_DEADDROP;
            menu.findItem(R.id.menu_context_reply_privately).setVisible(showReplyPrivately);
        }

        // if one of the selected item cannot be forwarded, disable forwarding.
        boolean canForward = true;
        for (DcMsg messageRecord : messageRecords) {
            if (messageRecord.isInfo()) {
                canForward = false;
                break;
            }
        }
        menu.findItem(R.id.menu_context_forward).setVisible(canForward);
    }

    static boolean canReplyToMsg(DcMsg dcMsg) {
        return !dcMsg.isInfo() && dcMsg.getType() != DcMsg.DC_MSG_VIDEOCHAT_INVITATION;
    }

    private ConversationAdapter getListAdapter() {
        return (ConversationAdapter) list.getAdapter();
    }

    public void reload(Recipient recipient, long chatId) {
        this.recipient = recipient;

        if (this.chatId != chatId) {
            this.chatId = chatId;
            initializeListAdapter();
        }
    }

    public void scrollToTop() {
        ConversationAdapter adapter = (ConversationAdapter)list.getAdapter();
        if (adapter.getItemCount()>0) {
            final int pos = adapter.getItemCount()-1;
            list.post(() -> {
                list.getLayoutManager().scrollToPosition(pos);
            });
        }
    }

    public void scrollToBottom() {
        if (((LinearLayoutManager) list.getLayoutManager()).findFirstVisibleItemPosition() < SCROLL_ANIMATION_THRESHOLD
                && !AccessibilityUtil.areAnimationsDisabled(getContext())) {
            list.smoothScrollToPosition(0);
        } else {
            list.scrollToPosition(0);
        }
    }

    void setLastSeen(long lastSeen) {
        getListAdapter().setLastSeen(lastSeen);
        if (lastSeenDecoration != null) {
            list.removeItemDecoration(lastSeenDecoration);
        }
        if (lastSeen > 0) {
            lastSeenDecoration = new ConversationAdapter.LastSeenHeader(getListAdapter());
            list.addItemDecoration(lastSeenDecoration);
        }
    }

    private void handleCopyMessage(final Set<DcMsg> dcMsgsSet) {
        List<DcMsg> dcMsgsList = new LinkedList<>(dcMsgsSet);
        Collections.sort(dcMsgsList, (lhs, rhs) -> Long.compare(lhs.getDateReceived(), rhs.getDateReceived()));
        boolean singleMsg = dcMsgsList.size() == 1;

        StringBuilder result = new StringBuilder();

        DcMsg prevMsg = new DcMsg(dcContext, DcMsg.DC_MSG_TEXT);
        for (DcMsg msg : dcMsgsList) {
            if (result.length() > 0) {
                result.append("\n\n");
            }

            if (msg.getFromId() != prevMsg.getFromId() && !singleMsg) {
                DcContact contact = dcContext.getContact(msg.getFromId());
                result.append(msg.getSenderName(contact, false)).append(":\n");
            }
            if (msg.getType() == DcMsg.DC_MSG_TEXT || (singleMsg && !msg.getText().isEmpty())) {
                result.append(msg.getText());
            } else {
                result.append(msg.getSummarytext(10000000));
            }

            prevMsg = msg;
        }

        if (result.length() > 0) {
            Util.writeTextToClipboard(getActivity(), result.toString());
            Toast.makeText(getActivity(), getActivity().getResources().getString(R.string.copied_to_clipboard), Toast.LENGTH_LONG).show();
        }
    }

    private void handleForwardMessage(final Set<DcMsg> messageRecords) {
        Intent composeIntent = new Intent(getActivity(), ConversationListActivity.class);
        int[] msgIds = DcMsg.msgSetToIds(messageRecords);
        setForwardingMessageIds(composeIntent, msgIds);
        startActivityForResult(composeIntent, REQUEST_RELAY);
        getActivity().overridePendingTransition(R.anim.slide_from_right, R.anim.fade_scale_out);
    }

    @SuppressLint("RestrictedApi")
    private void handleReplyMessage(final DcMsg message) {
        if (getActivity() != null) {
            //noinspection ConstantConditions
            ((AppCompatActivity) getActivity()).getSupportActionBar().collapseActionView();
        }

        listener.handleReplyMessage(message);
    }

    private void handleReplyMessagePrivately(final DcMsg msg) {

        if (getActivity() != null) {
            int privateChatId = dcContext.createChatByContactId(msg.getFromId());
            DcMsg replyMsg = new DcMsg(dcContext, DcMsg.DC_MSG_TEXT);
            replyMsg.setQuote(msg);
            dcContext.setDraft(privateChatId, replyMsg);

            Intent intent = new Intent(getActivity(), ConversationActivity.class);
            intent.putExtra(ConversationActivity.CHAT_ID_EXTRA, privateChatId);
            getActivity().startActivity(intent);
            getActivity().finish();
        } else {
            Log.e(TAG, "Activity was null");
        }
    }

    private void reloadList() {
        ConversationAdapter adapter = getListAdapter();
        if (adapter == null) {
            return;
        }

        int oldCount = 0;
        int oldIndex = 0;
        int pixelOffset = 0;
        if (!firstLoad) {
            oldCount = adapter.getItemCount();
            oldIndex = ((LinearLayoutManager) list.getLayoutManager()).findFirstCompletelyVisibleItemPosition();
            View firstView = list.getLayoutManager().findViewByPosition(oldIndex);
            pixelOffset = (firstView == null) ? 0 : list.getBottom() - firstView.getBottom() - list.getPaddingBottom();
        }

        if (getContext() == null) {
            Log.e(TAG, "reloadList: getContext() was null");
            return;
        }

        DcContext dcContext = DcHelper.getContext(getContext());

        long startMs = System.currentTimeMillis();
        int[] msgs = dcContext.getChatMsgs((int) chatId, 0, 0);
        Log.i(TAG, "⏰ getChatMsgs(" + chatId + "): " + (System.currentTimeMillis() - startMs) + "ms");

        adapter.changeData(msgs);

        if (firstLoad) {
            if (startingPosition >= 0) {
                getListAdapter().pulseHighlightItem(startingPosition);
            }
            firstLoad = false;
        } else if(oldIndex  > 0) {
            int newIndex = oldIndex + msgs.length - oldCount;

            if (newIndex < 0)                 { newIndex = 0; pixelOffset = 0; }
            else if (newIndex >= msgs.length) { newIndex = msgs.length - 1; pixelOffset = 0; }

            ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(newIndex, pixelOffset);
        }

        if(!adapter.isActive()){
            setNoMessageText();
            noMessageTextView.setVisibility(View.VISIBLE);
        }
        else{
            noMessageTextView.setVisibility(View.GONE);
        }

        if (!isPaused) {
            markseenDebouncer.publish(() -> manageMessageSeenState());
        }
    }

    private void updateLocationButton() {
        floatingLocationButton.setVisibility(dcContext.isSendingLocationsToChat((int) chatId)? View.VISIBLE : View.GONE);
    }

    private void scrollAndHighlight(final int pos, boolean smooth) {
        list.post(() -> {
            if (smooth && !AccessibilityUtil.areAnimationsDisabled(getContext())) {
                list.smoothScrollToPosition(pos);
            } else {
                list.scrollToPosition(pos);
            }
            getListAdapter().pulseHighlightItem(pos);
        });
    }

    public void scrollToMsgId(final int msgId) {
        ConversationAdapter adapter = (ConversationAdapter)list.getAdapter();
        int position = adapter.msgIdToPosition(msgId);
        if (position!=-1) {
            scrollAndHighlight(position, false);
        } else {
            Log.e(TAG, "msgId {} not found for scrolling");
        }
    }

    public interface ConversationFragmentListener {
        void handleReplyMessage(DcMsg messageRecord);
    }

    private class ConversationScrollListener extends OnScrollListener {

        private final Animation              scrollButtonInAnimation;
        private final Animation              scrollButtonOutAnimation;

        private boolean wasAtBottom           = true;
        private boolean wasAtZoomScrollHeight = false;
        //private long    lastPositionId        = -1;

        ConversationScrollListener(@NonNull Context context) {
            this.scrollButtonInAnimation  = AnimationUtils.loadAnimation(context, R.anim.fade_scale_in);
            this.scrollButtonOutAnimation = AnimationUtils.loadAnimation(context, R.anim.fade_scale_out);

            this.scrollButtonInAnimation.setDuration(100);
            this.scrollButtonOutAnimation.setDuration(50);
        }

        @Override
        public void onScrolled(final RecyclerView rv, final int dx, final int dy) {
            boolean currentlyAtBottom           = isAtBottom();
            boolean currentlyAtZoomScrollHeight = isAtZoomScrollHeight();
//            int     positionId                  = getHeaderPositionId();

            if (currentlyAtZoomScrollHeight && !wasAtZoomScrollHeight) {
                ViewUtil.animateIn(scrollToBottomButton, scrollButtonInAnimation);
            } else if (currentlyAtBottom && !wasAtBottom) {
                ViewUtil.animateOut(scrollToBottomButton, scrollButtonOutAnimation, View.INVISIBLE);
            }
            
//      if (positionId != lastPositionId) {
//        bindScrollHeader(conversationDateHeader, positionId);
//      }

            wasAtBottom           = currentlyAtBottom;
            wasAtZoomScrollHeight = currentlyAtZoomScrollHeight;
//            lastPositionId        = positionId;

            markseenDebouncer.publish(() -> manageMessageSeenState());
        }

        @Override
        public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
//      if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
//        conversationDateHeader.show();
//      } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
//        conversationDateHeader.hide();
//      }
        }

        private boolean isAtBottom() {
            if (list.getChildCount() == 0) return true;

            View    bottomView       = list.getChildAt(0);
            int     firstVisibleItem = ((LinearLayoutManager) list.getLayoutManager()).findFirstVisibleItemPosition();
            boolean isAtBottom       = (firstVisibleItem == 0);

            return isAtBottom && bottomView.getBottom() <= list.getHeight();
        }

        private boolean isAtZoomScrollHeight() {
            return ((LinearLayoutManager) list.getLayoutManager()).findFirstCompletelyVisibleItemPosition() > 4;
        }

 //       private int getHeaderPositionId() {
 //           return ((LinearLayoutManager)list.getLayoutManager()).findLastVisibleItemPosition();
 //       }

 //       private void bindScrollHeader(HeaderViewHolder headerViewHolder, int positionId) {
 //           if (((ConversationAdapter)list.getAdapter()).getHeaderId(positionId) != -1) {
 //               ((ConversationAdapter) list.getAdapter()).onBindHeaderViewHolder(headerViewHolder, positionId);
 //           }
 //       }
    }

    private void manageMessageSeenState() {

        LinearLayoutManager layoutManager = (LinearLayoutManager)list.getLayoutManager();

        int firstPos = layoutManager.findFirstVisibleItemPosition();
        int lastPos = layoutManager.findLastVisibleItemPosition();
        if(firstPos == RecyclerView.NO_POSITION || lastPos == RecyclerView.NO_POSITION) {
            return;
        }

        int[] ids = new int[lastPos - firstPos + 1];
        int index = 0;
        for(int pos = firstPos; pos <= lastPos; pos++) {
            DcMsg message = ((ConversationAdapter) list.getAdapter()).getMsg(pos);
            if (message.getFromId() != DC_CONTACT_ID_SELF && !message.isSeen()) {
                ids[index] = message.getId();
                index++;
            }
        }
        dcContext.markseenMsgs(ids);
    }


    void querySetupCode(final DcMsg dcMsg, String[] preload)
    {
        if( !dcMsg.isSetupMessage()) {
            return;
        }

        View gl = View.inflate(getActivity(), R.layout.setup_code_grid, null);
        final EditText[] editTexts = {
                gl.findViewById(R.id.setupCode0),  gl.findViewById(R.id.setupCode1),  gl.findViewById(R.id.setupCode2),
                gl.findViewById(R.id.setupCode3),  gl.findViewById(R.id.setupCode4),  gl.findViewById(R.id.setupCode5),
                gl.findViewById(R.id.setupCode6),  gl.findViewById(R.id.setupCode7),  gl.findViewById(R.id.setupCode8)
        };
        AlertDialog.Builder builder1 = new AlertDialog.Builder(getActivity());
        builder1.setView(gl);
        editTexts[0].setText(dcMsg.getSetupCodeBegin());
        editTexts[0].setSelection(editTexts[0].getText().length());

        for( int i = 0; i < 9; i++ ) {
            if( preload != null && i < preload.length ) {
                editTexts[i].setText(preload[i]);
                editTexts[i].setSelection(editTexts[i].getText().length());
            }
            editTexts[i].addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if( s.length()==4 ) {
                        for ( int i = 0; i < 8; i++ ) {
                            if( editTexts[i].hasFocus() && editTexts[i+1].getText().length()<4 ) {
                                editTexts[i+1].requestFocus();
                                break;
                            }
                        }
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
        }

        builder1.setTitle(getActivity().getString(R.string.autocrypt_continue_transfer_title));
        builder1.setMessage(getActivity().getString(R.string.autocrypt_continue_transfer_please_enter_code));
        builder1.setNegativeButton(android.R.string.cancel, null);
        builder1.setCancelable(false); // prevent the dialog from being dismissed accidentally (when the dialog is closed, the setup code is gone forever and the user has to create a new setup message)
        builder1.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            String setup_code = "";
            final String[] preload1 = new String[9];
            for ( int i = 0; i < 9; i++ ) {
                preload1[i] = editTexts[i].getText().toString();
                setup_code += preload1[i];
            }
            boolean success = dcContext.continueKeyTransfer(dcMsg.getId(), setup_code);

            AlertDialog.Builder builder2 = new AlertDialog.Builder(getActivity());
            builder2.setTitle(getActivity().getString(R.string.autocrypt_continue_transfer_title));
            builder2.setMessage(getActivity().getString(success? R.string.autocrypt_continue_transfer_succeeded : R.string.autocrypt_bad_setup_code));
            if( success ) {
                builder2.setPositiveButton(android.R.string.ok, null);
            }
            else {
                builder2.setNegativeButton(android.R.string.cancel, null);
                builder2.setPositiveButton(R.string.autocrypt_continue_transfer_retry, (dialog1, which1) -> querySetupCode(dcMsg, preload1));
            }
            builder2.show();
        });
        builder1.show();
    }

    private class ConversationFragmentItemClickListener implements ItemClickListener {

        @Override
        public void onItemClick(DcMsg messageRecord) {
            if (actionMode != null) {
                ((ConversationAdapter) list.getAdapter()).toggleSelection(messageRecord);
                list.getAdapter().notifyDataSetChanged();

                if (getListAdapter().getSelectedItems().size() == 0) {
                    actionMode.finish();
                } else {
                    Menu menu = actionMode.getMenu();
                    setCorrectMenuVisibility(menu);
                    ConversationAdaptiveActionsToolbar.adjustMenuActions(menu, 10, requireActivity().getWindow().getDecorView().getMeasuredWidth());
                    actionMode.setTitle(String.valueOf(getListAdapter().getSelectedItems().size()));
                    actionMode.setTitleOptionalHint(false); // the title represents important information, also indicating implicitly, more items can be selected
                }
            }
            else if(messageRecord.isSetupMessage()) {
                querySetupCode(messageRecord,null);
            }
            else if (messageRecord.getType()==DcMsg.DC_MSG_VIDEOCHAT_INVITATION) {
                new VideochatUtil().join(getActivity(), messageRecord.getId());
            }
            else if(DozeReminder.isDozeReminderMsg(getContext(), messageRecord)) {
                DozeReminder.dozeReminderTapped(getContext());
            }
            else {
                String self_mail = dcContext.getConfig("configured_mail_user");
                if (self_mail != null && !self_mail.isEmpty()
                        && messageRecord.getText().contains(self_mail)
                        && getListAdapter().getChat().isDeviceTalk()) {
                    // This is a device message informing the user that the password is wrong
                    startActivity(new Intent(getActivity(), RegistrationActivity.class));
                }
            }
        }

        @Override
        public void onItemLongClick(DcMsg messageRecord) {
            if (actionMode == null) {
                ((ConversationAdapter) list.getAdapter()).toggleSelection(messageRecord);
                list.getAdapter().notifyDataSetChanged();

                actionMode = ((AppCompatActivity)getActivity()).startSupportActionMode(actionModeCallback);
            }
        }

        @Override
        public void onQuoteClicked(DcMsg messageRecord) {
            DcMsg quoted = messageRecord.getQuotedMsg();
            if (quoted == null) {
                Log.i(TAG, "Clicked on a quote whose original message we never had.");
                Toast.makeText(getContext(), R.string.ConversationFragment_quoted_message_not_found, Toast.LENGTH_SHORT).show();
                return;
            }

            int foreignChatId = quoted.getChatId();
            if (foreignChatId != 0 && foreignChatId != chatId) {
                Intent intent = new Intent(getActivity(), ConversationActivity.class);
                intent.putExtra(ConversationActivity.CHAT_ID_EXTRA, foreignChatId);
                int start = DcMsg.getMessagePosition(quoted, dcContext);
                intent.putExtra(ConversationActivity.STARTING_POSITION_EXTRA, start);
                ((ConversationActivity) getActivity()).hideSoftKeyboard();
                if (getActivity() != null) {
                    getActivity().startActivity(intent);
                    getActivity().finish();
                } else {
                    Log.e(TAG, "Activity was null");
                }
            } else {
                LinearLayoutManager layout = ((LinearLayoutManager) list.getLayoutManager());
                boolean smooth = false;
                ConversationAdapter adapter = (ConversationAdapter) list.getAdapter();
                if (adapter == null) return;
                int position = adapter.msgIdToPosition(quoted.getId());
                if (layout != null) {
                    int distance1 = Math.abs(position - layout.findFirstVisibleItemPosition());
                    int distance2 = Math.abs(position - layout.findLastVisibleItemPosition());
                    int distance = Math.min(distance1, distance2);
                    smooth = distance < 15;
                    Log.i(TAG, "Scrolling to quote, smoth: " + smooth + ", distance: " + distance);
                }

                if (position != -1) {
                    scrollAndHighlight(position, smooth);
                } else {
                    Log.e(TAG, "msgId not found for scrolling: " + quoted.getId());
                }
            }
        }

      @Override
      public void onShowFullClicked(DcMsg messageRecord) {
        Intent intent = new Intent(getActivity(), FullMsgActivity.class);
        intent.putExtra(FullMsgActivity.MSG_ID_EXTRA, messageRecord.getId());
        startActivity(intent);
        getActivity().overridePendingTransition(R.anim.slide_from_right, R.anim.fade_scale_out);
      }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == CODE_ADD_EDIT_CONTACT && getContext() != null) {
//      ApplicationContext.getInstance(getContext().getApplicationContext())
//                        .getJobManager()
//                        .add(new DirectoryRefreshJob(getContext().getApplicationContext(), false));
        }
    }

    private class ActionModeCallback implements ActionMode.Callback {

        private int statusBarColor;

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.conversation_context, menu);

            mode.setTitle("1");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Window window = getActivity().getWindow();
                statusBarColor = window.getStatusBarColor();
                window.setStatusBarColor(getResources().getColor(R.color.action_mode_status_bar));
            }

            setCorrectMenuVisibility(menu);
            ConversationAdaptiveActionsToolbar.adjustMenuActions(menu, 10, requireActivity().getWindow().getDecorView().getMeasuredWidth());
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            ((ConversationAdapter)list.getAdapter()).clearSelection();
            list.getAdapter().notifyDataSetChanged();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                getActivity().getWindow().setStatusBarColor(statusBarColor);
            }

            actionMode = null;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            switch(item.getItemId()) {
                case R.id.menu_context_copy:
                    handleCopyMessage(getListAdapter().getSelectedItems());
                    actionMode.finish();
                    return true;
                case R.id.menu_context_delete_message:
                    handleDeleteMessages(getListAdapter().getSelectedItems());
                    return true;
                case R.id.menu_context_share:
                    dcContext.openForViewOrShare(getContext(), getSelectedMessageRecord(getListAdapter().getSelectedItems()).getId(), Intent.ACTION_SEND);
                    return true;
                case R.id.menu_context_details:
                    handleDisplayDetails(getSelectedMessageRecord(getListAdapter().getSelectedItems()));
                    actionMode.finish();
                    return true;
                case R.id.menu_context_forward:
                    handleForwardMessage(getListAdapter().getSelectedItems());
                    actionMode.finish();
                    return true;
                case R.id.menu_context_save_attachment:
                    handleSaveAttachment(getSelectedMessageRecord(getListAdapter().getSelectedItems()));
                    return true;
                case R.id.menu_context_reply:
                    handleReplyMessage(getSelectedMessageRecord(getListAdapter().getSelectedItems()));
                    actionMode.finish();
                    return true;
                case R.id.menu_context_reply_privately:
                    handleReplyMessagePrivately(getSelectedMessageRecord(getListAdapter().getSelectedItems()));
                    return true;
            }

            return false;
        }
    }

    @Override
    public void handleEvent(DcEvent event) {
        switch (event.getId()) {
            case DcContext.DC_EVENT_MSGS_CHANGED:
                if (event.getData1Int() == 0 // deleted messages or batch insert
                 || event.getData1Int() == chatId) {
                    reloadList();
                }
                break;

            case DcContext.DC_EVENT_INCOMING_MSG:
            case DcContext.DC_EVENT_MSG_DELIVERED:
            case DcContext.DC_EVENT_MSG_FAILED:
            case DcContext.DC_EVENT_MSG_READ:
                if (event.getData1Int() == chatId) {
                    reloadList();
                }
                break;

            case DcContext.DC_EVENT_CHAT_MODIFIED:
                if (event.getData1Int() == chatId) {
                  updateLocationButton();
                  reloadList();
                }
                break;
        }

        // removing the "new message" marker on incoming messages may be a bit unexpected,
        // esp. when a series of message is coming in and after the first, the screen is turned on,
        // the "new message" marker will flash for a short moment and disappear.
        /*if (eventId == DcContext.DC_EVENT_INCOMING_MSG && isResumed()) {
            setLastSeen(-1);
        }*/
    }
}
