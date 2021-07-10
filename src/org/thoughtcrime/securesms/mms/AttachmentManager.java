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
package org.thoughtcrime.securesms.mms;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.MediaPreviewActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.ShareLocationDialog;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.audio.AudioSlidePlayer;
import org.thoughtcrime.securesms.components.AudioView;
import org.thoughtcrime.securesms.components.DocumentView;
import org.thoughtcrime.securesms.components.RemovableEditableMediaView;
import org.thoughtcrime.securesms.components.ThumbnailView;
import org.thoughtcrime.securesms.geolocation.DcLocationManager;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.providers.PersistentBlobProvider;
import org.thoughtcrime.securesms.scribbles.ScribbleActivity;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.ThemeUtil;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.thoughtcrime.securesms.util.concurrent.ListenableFuture;
import org.thoughtcrime.securesms.util.concurrent.ListenableFuture.Listener;
import org.thoughtcrime.securesms.util.concurrent.SettableFuture;
import org.thoughtcrime.securesms.util.guava.Optional;
import org.thoughtcrime.securesms.util.views.Stub;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;


public class AttachmentManager {

  private final static String TAG = AttachmentManager.class.getSimpleName();

  private final @NonNull Context                    context;
  private final @NonNull Stub<View>                 attachmentViewStub;
  private final @NonNull
  AttachmentListener attachmentListener;

  private RemovableEditableMediaView removableMediaView;
  private ThumbnailView              thumbnail;
  private AudioView                  audioView;
  private DocumentView               documentView;
  //private SignalMapView              mapView;

  private @NonNull  List<Uri>       garbage = new LinkedList<>();
  private @NonNull  Optional<Slide> slide   = Optional.absent();
  private @Nullable Uri             imageCaptureUri;
  private @Nullable Uri             videoCaptureUri;

  public AttachmentManager(@NonNull Activity activity, @NonNull AttachmentListener listener) {
    this.context            = activity;
    this.attachmentListener = listener;
    this.attachmentViewStub = ViewUtil.findStubById(activity, R.id.attachment_editor_stub);
  }

  private void inflateStub() {
    if (!attachmentViewStub.resolved()) {
      View root = attachmentViewStub.get();

      this.thumbnail          = ViewUtil.findById(root, R.id.attachment_thumbnail);
      this.audioView          = ViewUtil.findById(root, R.id.attachment_audio);
      this.documentView       = ViewUtil.findById(root, R.id.attachment_document);
      //this.mapView            = ViewUtil.findById(root, R.id.attachment_location);
      this.removableMediaView = ViewUtil.findById(root, R.id.removable_media_view);

      removableMediaView.setRemoveClickListener(new RemoveButtonListener());
      removableMediaView.setEditClickListener(new EditButtonListener());
      thumbnail.setOnClickListener(new ThumbnailClickListener());
      int incomingBubbleColor = ThemeUtil.getThemedColor(context, R.attr.conversation_item_incoming_bubble_color);
      audioView.getBackground().setColorFilter(incomingBubbleColor, PorterDuff.Mode.MULTIPLY);
      documentView.getBackground().setColorFilter(incomingBubbleColor, PorterDuff.Mode.MULTIPLY);
    }

  }

  public void clear(@NonNull GlideRequests glideRequests, boolean animate) {
    if (attachmentViewStub.resolved()) {

      if (animate) {
        ViewUtil.fadeOut(attachmentViewStub.get(), 200).addListener(new Listener<Boolean>() {
          @Override
          public void onSuccess(Boolean result) {
            thumbnail.clear(glideRequests);
            attachmentViewStub.get().setVisibility(View.GONE);
            attachmentListener.onAttachmentChanged();
          }

          @Override
          public void onFailure(ExecutionException e) {
          }
        });
      } else {
        thumbnail.clear(glideRequests);
        attachmentViewStub.get().setVisibility(View.GONE);
        attachmentListener.onAttachmentChanged();
      }

      markGarbage(getSlideUri());
      slide = Optional.absent();

      audioView.cleanup();
    }
  }

  public void cleanup() {
    cleanup(imageCaptureUri);
    cleanup(videoCaptureUri);
    cleanup(getSlideUri());

    imageCaptureUri = null;
    videoCaptureUri = null;
    slide           = Optional.absent();

    Iterator<Uri> iterator = garbage.listIterator();

    while (iterator.hasNext()) {
      cleanup(iterator.next());
      iterator.remove();
    }
  }

  private void cleanup(final @Nullable Uri uri) {
    if (uri != null && PersistentBlobProvider.isAuthority(context, uri)) {
      Log.w(TAG, "cleaning up " + uri);
      PersistentBlobProvider.getInstance().delete(context, uri);
    }
  }

  private void markGarbage(@Nullable Uri uri) {
    if (uri != null && PersistentBlobProvider.isAuthority(context, uri)) {
      Log.w(TAG, "Marking garbage that needs cleaning: " + uri);
      garbage.add(uri);
    }
  }

  private void setSlide(@NonNull Slide slide) {
    if (getSlideUri() != null)                                              cleanup(getSlideUri());
    if (imageCaptureUri != null && !imageCaptureUri.equals(slide.getUri())) cleanup(imageCaptureUri);
    if (videoCaptureUri != null && !videoCaptureUri.equals(slide.getUri())) cleanup(videoCaptureUri);

    this.imageCaptureUri = null;
    this.videoCaptureUri = null;
    this.slide           = Optional.of(slide);
  }

  /*
  public ListenableFuture<Boolean> setLocation(@NonNull final SignalPlace place,
                                               @NonNull final MediaConstraints constraints)
  {
    inflateStub();

    SettableFuture<Boolean>  returnResult = new SettableFuture<>();
    ListenableFuture<Bitmap> future       = mapView.display(place);

    attachmentViewStub.get().setVisibility(View.VISIBLE);
    removableMediaView.display(mapView, false);

    future.addListener(new AssertedSuccessListener<Bitmap>() {
      @Override
      public void onSuccess(@NonNull Bitmap result) {
        byte[]        blob          = BitmapUtil.toByteArray(result);
        Uri           uri           = PersistentBlobProvider.getInstance(context)
                                                            .create(context, blob, MediaUtil.IMAGE_PNG, null);
        LocationSlide locationSlide = new LocationSlide(context, uri, blob.length, place);

        setSlide(locationSlide);
        attachmentListener.onAttachmentChanged();
        returnResult.set(true);
      }
    });

    return returnResult;
  }
  */

  @SuppressLint("StaticFieldLeak")
  public ListenableFuture<Boolean> setMedia(@NonNull final GlideRequests glideRequests,
                                            @NonNull final Uri uri,
                                            @NonNull final MediaType mediaType,
                                                     final int width,
                                                     final int height)
  {
    inflateStub();

    final SettableFuture<Boolean> result = new SettableFuture<>();

    new AsyncTask<Void, Void, Slide>() {
      @Override
      protected void onPreExecute() {
        thumbnail.clear(glideRequests);
        attachmentViewStub.get().setVisibility(View.VISIBLE);
      }

      @Override
      protected @Nullable Slide doInBackground(Void... params) {
        try {
          if (PartAuthority.isLocalUri(uri)) {
            return getManuallyCalculatedSlideInfo(uri, width, height);
          } else {
            Slide result = getContentResolverSlideInfo(uri, width, height);

            if (result == null) return getManuallyCalculatedSlideInfo(uri, width, height);
            else                return result;
          }
        } catch (IOException e) {
          Log.w(TAG, e);
          return null;
        }
      }

      @Override
      protected void onPostExecute(@Nullable final Slide slide) {
         if (slide == null) {
          attachmentViewStub.get().setVisibility(View.GONE);
          result.set(false);
        } else if (slide.getFileSize()>1*1024*1024*1024) {
          // this is only a rough check, videos and images may be recoded
          // and the core checks more carefully later.
          attachmentViewStub.get().setVisibility(View.GONE);
          Log.w(TAG, "File too large.");
          Toast.makeText(slide.context, "File too large.", Toast.LENGTH_LONG).show();
          result.set(false);
        } else {
          setSlide(slide);
          attachmentViewStub.get().setVisibility(View.VISIBLE);

          if (slide.hasAudio()) {
            class SetDurationListener implements AudioSlidePlayer.Listener {
              @Override
              public void onStart() {}

              @Override
              public void onStop() {}

              @Override
              public void onProgress(double progress, long millis) {}

              @Override
              public void onReceivedDuration(int millis) {
                ((AudioView) removableMediaView.getCurrent()).setDuration(millis);
              }
            }
            AudioSlidePlayer audioSlidePlayer = AudioSlidePlayer.createFor(context, (AudioSlide) slide, new SetDurationListener());
            audioSlidePlayer.requestDuration();

            audioView.setAudio((AudioSlide) slide, 0);
            removableMediaView.display(audioView, false);
            result.set(true);
          } else if (slide.hasDocument()) {
            documentView.setDocument((DocumentSlide) slide);
            removableMediaView.display(documentView, false);
            result.set(true);
          } else {
            Attachment attachment = slide.asAttachment();
            result.deferTo(thumbnail.setImageResource(glideRequests, slide, attachment.getWidth(), attachment.getHeight()));
            removableMediaView.display(thumbnail, mediaType == MediaType.IMAGE);
          }

          attachmentListener.onAttachmentChanged();
        }
      }

      private @Nullable Slide getContentResolverSlideInfo(Uri uri, int width, int height) {
        Cursor cursor = null;
        long   start  = System.currentTimeMillis();

        try {
          cursor = context.getContentResolver().query(uri, null, null, null, null);

          if (cursor != null && cursor.moveToFirst()) {
            String fileName = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
            long   fileSize = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE));
            String mimeType = context.getContentResolver().getType(uri);

            if (width == 0 || height == 0) {
              Pair<Integer, Integer> dimens = MediaUtil.getDimensions(context, mimeType, uri);
              width  = dimens.first;
              height = dimens.second;
            }

            Log.w(TAG, "remote slide with size " + fileSize + " took " + (System.currentTimeMillis() - start) + "ms");
            return mediaType.createSlide(context, uri, fileName, mimeType, fileSize, width, height);
          }
        } finally {
          if (cursor != null) cursor.close();
        }

        return null;
      }

      private @NonNull Slide getManuallyCalculatedSlideInfo(Uri uri, int width, int height) throws IOException {
        long start      = System.currentTimeMillis();
        Long mediaSize  = null;
        String fileName = null;
        String mimeType = null;

        if (PartAuthority.isLocalUri(uri)) {
          mediaSize = PartAuthority.getAttachmentSize(context, uri);
          fileName  = PartAuthority.getAttachmentFileName(context, uri);
          mimeType  = PartAuthority.getAttachmentContentType(context, uri);
        }

        if (mediaSize == null) {
          mediaSize = MediaUtil.getMediaSize(context, uri);
        }

        if (mimeType == null) {
          mimeType = MediaUtil.getMimeType(context, uri);
        }

        if (width == 0 || height == 0) {
          Pair<Integer, Integer> dimens = MediaUtil.getDimensions(context, mimeType, uri);
          width  = dimens.first;
          height = dimens.second;
        }

        if (fileName == null) {
          try {
            fileName = new File(uri.getPath()).getName();
          } catch(Exception e) {
            Log.w(TAG, "Could not get file name from uri: " + e.toString());
          }
        }

        Log.w(TAG, "local slide with size " + mediaSize + " took " + (System.currentTimeMillis() - start) + "ms");
        return mediaType.createSlide(context, uri, fileName, mimeType, mediaSize, width, height);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

    return result;
  }

  public boolean isAttachmentPresent() {
    return attachmentViewStub.resolved() && attachmentViewStub.get().getVisibility() == View.VISIBLE;
  }

  public @NonNull SlideDeck buildSlideDeck() {
    SlideDeck deck = new SlideDeck();
    if (slide.isPresent()) deck.addSlide(slide.get());
    return deck;
  }

  public static void selectDocument(Activity activity, int requestCode) {
    Permissions.with(activity)
               .request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
               .ifNecessary()
               .withPermanentDenialDialog(activity.getString(R.string.perm_explain_access_to_storage_denied))
               .onAllGranted(() -> selectMediaType(activity, "*/*", null, requestCode))
               .execute();
  }

  public static void selectGallery(Activity activity, int requestCode) {
    Permissions.with(activity)
               .request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
               .ifNecessary()
               .withPermanentDenialDialog(activity.getString(R.string.perm_explain_access_to_storage_denied))
               .onAllGranted(() -> selectMediaType(activity, "image/*", new String[] {"image/*", "video/*"}, requestCode))
               .execute();
  }

  public static void selectImage(Activity activity, int requestCode) {
    Permissions.with(activity)
            .request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .ifNecessary()
            .withPermanentDenialDialog(activity.getString(R.string.perm_explain_access_to_storage_denied))
            .onAllGranted(() -> selectMediaType(activity, "image/*", null, requestCode))
            .execute();
  }

  public static void selectAudio(Activity activity, int requestCode) {
    Permissions.with(activity)
               .request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
               .ifNecessary()
               .withPermanentDenialDialog(activity.getString(R.string.perm_explain_access_to_storage_denied))
               .onAllGranted(() -> selectMediaType(activity, "audio/*", null, requestCode))
               .execute();
  }

  public static void selectLocation(Activity activity, int chatId) {
    ApplicationContext applicationContext = ApplicationContext.getInstance(activity);
    DcLocationManager dcLocationManager = applicationContext.dcLocationManager;

    if (applicationContext.dcContext.isSendingLocationsToChat(chatId)) {
      dcLocationManager.stopSharingLocation(chatId);
      return;
    }

    // see https://support.google.com/googleplay/android-developer/answer/9799150#zippy=%2Cstep-provide-prominent-in-app-disclosure
    // for rationale dialog requirements
    Permissions.PermissionsBuilder permissionsBuilder = Permissions.with(activity)
            .ifNecessary()
            .withRationaleDialog("To share your live location with chat members, allow Delta Chat to use your location data.\n\nTo make live location work gaplessly, location data is used even when the app is closed or not in use.", R.drawable.ic_location_on_white_24dp)
            .withPermanentDenialDialog(activity.getString(R.string.perm_explain_access_to_location_denied))
            .onAllGranted(() -> {
              ShareLocationDialog.show(activity, durationInSeconds -> {
                switch (durationInSeconds) {
                  case 1: dcLocationManager.shareLastLocation(chatId); break;
                  default:
                    dcLocationManager.shareLocation(durationInSeconds, chatId);
                    break;
                }
              });
            });
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
      permissionsBuilder.request(Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
    } else {
      permissionsBuilder.request(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
    }
    permissionsBuilder.execute();
  }

  private @Nullable Uri getSlideUri() {
    return slide.isPresent() ? slide.get().getUri() : null;
  }

  public @Nullable Uri getImageCaptureUri() {
    return imageCaptureUri;
  }

  public @Nullable Uri getVideoCaptureUri() {
    return videoCaptureUri;
  }

  public void capturePhoto(Activity activity, int requestCode) {
    Permissions.with(activity)
               .request(Manifest.permission.CAMERA)
               .ifNecessary()
               .withPermanentDenialDialog(activity.getString(R.string.perm_explain_access_to_camera_denied))
               .onAllGranted(() -> {
                 try {
                   Intent captureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                   if (captureIntent.resolveActivity(activity.getPackageManager()) != null) {
                     if (imageCaptureUri == null) {
                       imageCaptureUri = PersistentBlobProvider.getInstance().createForExternal(context, MediaUtil.IMAGE_JPEG);
                     }
                     Log.w(TAG, "imageCaptureUri path is " + imageCaptureUri.getPath());
                     captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, imageCaptureUri);
                     activity.startActivityForResult(captureIntent, requestCode);
                   }
                 } catch (Exception e) {
                   Log.w(TAG, e);
                 }
               })
               .execute();
  }

  public void captureVideo(Activity activity, int requestCode) {
    Permissions.with(activity)
        .request(Manifest.permission.CAMERA)
        .ifNecessary()
        .withPermanentDenialDialog(activity.getString(R.string.perm_explain_access_to_camera_denied))
        .onAllGranted(() -> {
          try {
            Intent captureIntent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
            if (captureIntent.resolveActivity(activity.getPackageManager()) != null) {
              if (videoCaptureUri==null) {
                videoCaptureUri = PersistentBlobProvider.getInstance().createForExternal(context, "video/mp4");
              }
              Log.w(TAG, "videoCaptureUri path is " + videoCaptureUri.getPath());
              captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, videoCaptureUri);
              captureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
              captureIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
              activity.startActivityForResult(captureIntent, requestCode);
            } else {
              Util.runOnMain(()->Toast.makeText(context, R.string.no_app_to_capture_video, Toast.LENGTH_LONG).show());
            }
          } catch (Exception e) {
            Log.w(TAG, e);
          }
        })
        .execute();
  }

  private static void selectMediaType(Activity activity, @NonNull String type, @Nullable String[] extraMimeType, int requestCode) {
    final Intent intent = new Intent();
    intent.setType(type);

    if (extraMimeType != null && Build.VERSION.SDK_INT >= 19) {
      intent.putExtra(Intent.EXTRA_MIME_TYPES, extraMimeType);
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      intent.setAction(Intent.ACTION_OPEN_DOCUMENT);
      try {
        activity.startActivityForResult(intent, requestCode);
        return;
      } catch (ActivityNotFoundException anfe) {
        Log.w(TAG, "couldn't complete ACTION_OPEN_DOCUMENT, no activity found. falling back.");
      }
    }

    intent.setAction(Intent.ACTION_GET_CONTENT);

    try {
      activity.startActivityForResult(intent, requestCode);
    } catch (ActivityNotFoundException anfe) {
      Log.w(TAG, "couldn't complete ACTION_GET_CONTENT intent, no activity found. falling back.");
      Toast.makeText(activity, R.string.no_app_to_handle_data, Toast.LENGTH_LONG).show();
    }
  }

  private void previewImageDraft(final @NonNull Slide slide) {
    if (MediaPreviewActivity.isTypeSupported(slide) && slide.getUri() != null) {
      Intent intent = new Intent(context, MediaPreviewActivity.class);
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      intent.putExtra(MediaPreviewActivity.DC_MSG_ID, slide.getDcMsgId());
      intent.putExtra(MediaPreviewActivity.OUTGOING_EXTRA, true);
      intent.setDataAndType(slide.getUri(), slide.getContentType());

      context.startActivity(intent);
    }
  }

  private class ThumbnailClickListener implements View.OnClickListener {
    @Override
    public void onClick(View v) {
      if (slide.isPresent()) previewImageDraft(slide.get());
    }
  }

  private class RemoveButtonListener implements View.OnClickListener {
    @Override
    public void onClick(View v) {
      cleanup();
      clear(GlideApp.with(context.getApplicationContext()), true);
    }
  }

  private class EditButtonListener implements View.OnClickListener {
    @Override
    public void onClick(View v) {
      if (Build.VERSION.SDK_INT >= 19) {
        Intent intent = new Intent(context, ScribbleActivity.class);
        intent.setData(getSlideUri());
        ((Activity) context).startActivityForResult(intent, ScribbleActivity.SCRIBBLE_REQUEST_CODE);
      }
      else {
        Toast.makeText(context, "Image editing requires Android 4.4 KitKat or newer.", Toast.LENGTH_LONG).show();
      }
    }
  }

  public interface AttachmentListener {
    void onAttachmentChanged();
  }

  public enum MediaType {
    IMAGE, GIF, AUDIO, VIDEO, DOCUMENT;

    public @NonNull Slide createSlide(@NonNull  Context context,
                                      @NonNull  Uri     uri,
                                      @Nullable String fileName,
                                      @Nullable String mimeType,
                                                long    dataSize,
                                                int     width,
                                                int     height)
    {
      if (mimeType == null) {
        mimeType = "application/octet-stream";
      }

      switch (this) {
      case IMAGE:    return new ImageSlide(context, uri, dataSize, width, height);
      case GIF:      return new GifSlide(context, uri, dataSize, width, height);
      case AUDIO:    return new AudioSlide(context, uri, dataSize, false, fileName);
      case VIDEO:    return new VideoSlide(context, uri, dataSize);
      case DOCUMENT: return new DocumentSlide(context, uri, mimeType, dataSize, fileName);
      default:       throw  new AssertionError("unrecognized enum");
      }
    }
  }

  public int getVisibility() {
    try {
      return attachmentViewStub.get().getVisibility();
    } catch(Exception e) {
      return View.GONE;
    }
  }

  public void setVisibility(int vis) {
    try {
      attachmentViewStub.get().setVisibility(vis);
    } catch(Exception e) {}
  }
}
