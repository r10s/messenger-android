package org.thoughtcrime.securesms.video.recode;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import com.b44t.messenger.DcMsg;
import com.coremedia.iso.IsoFile;
import com.coremedia.iso.boxes.Box;
import com.coremedia.iso.boxes.MediaBox;
import com.coremedia.iso.boxes.MediaHeaderBox;
import com.coremedia.iso.boxes.SampleSizeBox;
import com.coremedia.iso.boxes.TrackBox;
import com.coremedia.iso.boxes.TrackHeaderBox;
import com.googlecode.mp4parser.util.Matrix;
import com.googlecode.mp4parser.util.Path;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.Prefs;
import org.thoughtcrime.securesms.util.Util;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import androidx.annotation.RequiresApi;

public class VideoRecoder {

  private static final String TAG = VideoRecoder.class.getSimpleName();

  private boolean videoConvertFirstWrite = true;
  private final static String MIME_TYPE = "video/avc";
  private final static int PROCESSOR_TYPE_OTHER = 0;
  private final static int PROCESSOR_TYPE_QCOM = 1;
  private final static int PROCESSOR_TYPE_INTEL = 2;
  private final static int PROCESSOR_TYPE_MTK = 3;
  private final static int PROCESSOR_TYPE_SEC = 4;
  private final static int PROCESSOR_TYPE_TI = 5;
  private boolean cancelCurrentVideoConversion = false;
  private final Object videoConvertSync = new Object();

  private void checkConversionCanceled() {
    boolean cancelConversion;
    synchronized (videoConvertSync) {
      cancelConversion = cancelCurrentVideoConversion;
    }
    if (cancelConversion) {
      throw new RuntimeException("canceled conversion");
    }
  }

  @TargetApi(16)
  private int selectTrack(MediaExtractor extractor, boolean audio) {
    int numTracks = extractor.getTrackCount();
    for (int i = 0; i < numTracks; i++) {
      MediaFormat format = extractor.getTrackFormat(i);
      String mime = format.getString(MediaFormat.KEY_MIME);
      if (audio) {
        if (mime.startsWith("audio/")) {
          return i;
        }
      } else {
        if (mime.startsWith("video/")) {
          return i;
        }
      }
    }
    return -5;
  }

  @SuppressLint("NewApi")
  private static MediaCodecInfo selectCodec(String mimeType) {
    int numCodecs = MediaCodecList.getCodecCount();
    MediaCodecInfo lastCodecInfo = null;
    for (int i = 0; i < numCodecs; i++) {
      MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
      if (!codecInfo.isEncoder()) {
        continue;
      }
      String[] types = codecInfo.getSupportedTypes();
      for (String type : types) {
        if (type.equalsIgnoreCase(mimeType)) {
          lastCodecInfo = codecInfo;
          if (!lastCodecInfo.getName().equals("OMX.SEC.avc.enc")) {
            return lastCodecInfo;
          } else if (lastCodecInfo.getName().equals("OMX.SEC.AVC.Encoder")) {
            return lastCodecInfo;
          }
        }
      }
    }
    return lastCodecInfo;
  }

  private static boolean isRecognizedFormat(int colorFormat) {
    switch (colorFormat) {
      case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
      case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
      case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
      case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
      case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
        return true;
      default:
        return false;
    }
  }

  @SuppressLint("NewApi")
  private static int selectColorFormat(MediaCodecInfo codecInfo, String mimeType) {
    MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
    int lastColorFormat = 0;
    for (int i = 0; i < capabilities.colorFormats.length; i++) {
      int colorFormat = capabilities.colorFormats[i];
      if (isRecognizedFormat(colorFormat)) {
        lastColorFormat = colorFormat;
        if (!(codecInfo.getName().equals("OMX.SEC.AVC.Encoder") && colorFormat == 19)) {
          return colorFormat;
        }
      }
    }
    return lastColorFormat;
  }

  @TargetApi(16)
  private long readAndWriteTrack(MediaExtractor extractor, MP4Builder mediaMuxer,
                                 MediaCodec.BufferInfo info, long start, long end,
                                 File file, boolean isAudio) throws IOException {
    int trackIndex = selectTrack(extractor, isAudio);
    if (trackIndex >= 0) {
      extractor.selectTrack(trackIndex);
      MediaFormat trackFormat = extractor.getTrackFormat(trackIndex);
      int muxerTrackIndex = mediaMuxer.addTrack(trackFormat, isAudio);
      int maxBufferSize = trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
      boolean inputDone = false;
      if (start > 0) {
        extractor.seekTo(start, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
      } else {
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
      }
      ByteBuffer buffer = ByteBuffer.allocateDirect(maxBufferSize);
      long startTime = -1;

      checkConversionCanceled();
      long lastTimestamp = -100;

      while (!inputDone) {
        checkConversionCanceled();

        boolean eof = false;
        int index = extractor.getSampleTrackIndex();
        if (index == trackIndex) {
          info.size = extractor.readSampleData(buffer, 0);
          if (info.size >= 0) {
            info.presentationTimeUs = extractor.getSampleTime();
          } else {
            info.size = 0;
            eof = true;
          }

          if (info.size > 0 && !eof) {
            if (start > 0 && startTime == -1) {
              startTime = info.presentationTimeUs;
            }
            if (end < 0 || info.presentationTimeUs < end) {
              if (info.presentationTimeUs > lastTimestamp) {
                info.offset = 0;
                info.flags = extractor.getSampleFlags();
                if (mediaMuxer.writeSampleData(muxerTrackIndex, buffer, info, isAudio)) {
                  //didWriteData(messageObject, file, false, false);
                }
              }
              lastTimestamp = info.presentationTimeUs;
            } else {
              eof = true;
            }
          }
          if (!eof) {
            extractor.advance();
          }
        } else if (index == -1) {
          eof = true;
        } else {
          extractor.advance();
        }
        if (eof) {
          inputDone = true;
        }
      }

      extractor.unselectTrack(trackIndex);
      return startTime;
    }
    return -1;
  }

  @TargetApi(16)
  private boolean convertVideo(final VideoEditedInfo videoEditedInfo, String destPath) {

    long startTime = videoEditedInfo.startTime;
    long endTime = videoEditedInfo.endTime;
    int resultWidth = videoEditedInfo.resultWidth;
    int resultHeight = videoEditedInfo.resultHeight;
    int rotationValue = videoEditedInfo.rotationValue;
    int originalWidth = videoEditedInfo.originalWidth;
    int originalHeight = videoEditedInfo.originalHeight;
    int originalVideoBitrate = videoEditedInfo.originalVideoBitrate;
    int resultVideoBitrate = videoEditedInfo.resultVideoBitrate;
    int rotateRender = 0;
    File cacheFile = new File(destPath);

    if (Build.VERSION.SDK_INT < 18 && resultHeight > resultWidth && resultWidth != originalWidth && resultHeight != originalHeight) {
      int temp = resultHeight;
      resultHeight = resultWidth;
      resultWidth = temp;
      rotationValue = 90;
      rotateRender = 270;
    } else if (Build.VERSION.SDK_INT > 20) {
      if (rotationValue == 90) {
        int temp = resultHeight;
        resultHeight = resultWidth;
        resultWidth = temp;
        rotationValue = 0;
        rotateRender = 270;
      } else if (rotationValue == 180) {
        rotateRender = 180;
        rotationValue = 0;
      } else if (rotationValue == 270) {
        int temp = resultHeight;
        resultHeight = resultWidth;
        resultWidth = temp;
        rotationValue = 0;
        rotateRender = 90;
      }
    }

    File inputFile = new File(videoEditedInfo.originalPath);
    if (!inputFile.canRead()) {
      Log.w(TAG, "Could not read video file to be recoded");
      //didWriteData(messageObject, cacheFile, true, true);
      return false;
    }

    videoConvertFirstWrite = true;
    boolean error = false;
    long videoStartTime = startTime;

    long time = System.currentTimeMillis();

    if (resultWidth != 0 && resultHeight != 0) {
      MP4Builder mediaMuxer = null;
      MediaExtractor extractor = null;

      try {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        Mp4Movie movie = new Mp4Movie();
        movie.setCacheFile(cacheFile);
        movie.setRotation(rotationValue);
        movie.setSize(resultWidth, resultHeight);
        mediaMuxer = new MP4Builder().createMovie(movie);
        extractor = new MediaExtractor();
        extractor.setDataSource(inputFile.toString());

        checkConversionCanceled();

        if (resultVideoBitrate<originalVideoBitrate || resultWidth != originalWidth || resultHeight != originalHeight || rotateRender != 0) {
          int videoIndex;
          videoIndex = selectTrack(extractor, false);
          if (videoIndex >= 0) {
            MediaCodec decoder = null;
            MediaCodec encoder = null;
            InputSurface inputSurface = null;
            OutputSurface outputSurface = null;

            try {
              long videoTime = -1;
              boolean outputDone = false;
              boolean inputDone = false;
              boolean decoderDone = false;
              int swapUV = 0;
              int videoTrackIndex = -5;

              int colorFormat;
              int processorType = PROCESSOR_TYPE_OTHER;
              String manufacturer = Build.MANUFACTURER.toLowerCase();
              if (Build.VERSION.SDK_INT < 18) {
                MediaCodecInfo codecInfo = selectCodec(MIME_TYPE);
                colorFormat = selectColorFormat(codecInfo, MIME_TYPE);
                if (colorFormat == 0) {
                  throw new RuntimeException("no supported color format");
                }
                String codecName = codecInfo.getName();
                if (codecName.contains("OMX.qcom.")) {
                  processorType = PROCESSOR_TYPE_QCOM;
                  if (Build.VERSION.SDK_INT == 16) {
                    if (manufacturer.equals("lge") || manufacturer.equals("nokia")) {
                      swapUV = 1;
                    }
                  }
                } else if (codecName.contains("OMX.Intel.")) {
                  processorType = PROCESSOR_TYPE_INTEL;
                } else if (codecName.equals("OMX.MTK.VIDEO.ENCODER.AVC")) {
                  processorType = PROCESSOR_TYPE_MTK;
                } else if (codecName.equals("OMX.SEC.AVC.Encoder")) {
                  processorType = PROCESSOR_TYPE_SEC;
                  swapUV = 1;
                } else if (codecName.equals("OMX.TI.DUCATI1.VIDEO.H264E")) {
                  processorType = PROCESSOR_TYPE_TI;
                }
                //Log.i("DeltaChat", "codec = " + codecInfo.getName() + " manufacturer = " + manufacturer + "device = " + Build.MODEL);
              } else {
                colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
              }
              //Log.i("DeltaChat", "colorFormat = " + colorFormat);

              int resultHeightAligned = resultHeight;
              int padding = 0;
              int bufferSize = resultWidth * resultHeight * 3 / 2;
              if (processorType == PROCESSOR_TYPE_OTHER) {
                if (resultHeight % 16 != 0) {
                  resultHeightAligned += (16 - (resultHeight % 16));
                  padding = resultWidth * (resultHeightAligned - resultHeight);
                  bufferSize += padding * 5 / 4;
                }
              } else if (processorType == PROCESSOR_TYPE_QCOM) {
                if (!manufacturer.toLowerCase().equals("lge")) {
                  int uvoffset = (resultWidth * resultHeight + 2047) & ~2047;
                  padding = uvoffset - (resultWidth * resultHeight);
                  bufferSize += padding;
                }
              } else if (processorType == PROCESSOR_TYPE_TI) {
                //resultHeightAligned = 368;
                //bufferSize = resultWidth * resultHeightAligned * 3 / 2;
                //resultHeightAligned += (16 - (resultHeight % 16));
                //padding = resultWidth * (resultHeightAligned - resultHeight);
                //bufferSize += padding * 5 / 4;
              } else if (processorType == PROCESSOR_TYPE_MTK) {
                if (manufacturer.equals("baidu")) {
                  resultHeightAligned += (16 - (resultHeight % 16));
                  padding = resultWidth * (resultHeightAligned - resultHeight);
                  bufferSize += padding * 5 / 4;
                }
              }

              extractor.selectTrack(videoIndex);
              if (startTime > 0) {
                extractor.seekTo(startTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
              } else {
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
              }
              MediaFormat inputFormat = extractor.getTrackFormat(videoIndex);

              MediaFormat outputFormat = MediaFormat.createVideoFormat(MIME_TYPE, resultWidth, resultHeight);
              outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
              outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, resultVideoBitrate != 0 ? resultVideoBitrate : 921600);
              outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
              outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);
              if (Build.VERSION.SDK_INT < 18) {
                outputFormat.setInteger("stride", resultWidth + 32);
                outputFormat.setInteger("slice-height", resultHeight);
              }

              encoder = MediaCodec.createEncoderByType(MIME_TYPE);
              encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
              if (Build.VERSION.SDK_INT >= 18) {
                inputSurface = new InputSurface(encoder.createInputSurface());
                inputSurface.makeCurrent();
              }
              encoder.start();

              decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
              if (Build.VERSION.SDK_INT >= 18) {
                outputSurface = new OutputSurface();
              } else {
                outputSurface = new OutputSurface(resultWidth, resultHeight, rotateRender);
              }
              decoder.configure(inputFormat, outputSurface.getSurface(), null, 0);
              decoder.start();

              final int TIMEOUT_USEC = 2500;
              ByteBuffer[] decoderInputBuffers = null;
              ByteBuffer[] encoderOutputBuffers = null;
              ByteBuffer[] encoderInputBuffers = null;
              if (Build.VERSION.SDK_INT < 21) {
                decoderInputBuffers = decoder.getInputBuffers();
                encoderOutputBuffers = encoder.getOutputBuffers();
                if (Build.VERSION.SDK_INT < 18) {
                  encoderInputBuffers = encoder.getInputBuffers();
                }
              }

              checkConversionCanceled();

              while (!outputDone) {
                checkConversionCanceled();
                if (!inputDone) {
                  boolean eof = false;
                  int index = extractor.getSampleTrackIndex();
                  if (index == videoIndex) {
                    int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                    if (inputBufIndex >= 0) {
                      ByteBuffer inputBuf;
                      if (Build.VERSION.SDK_INT < 21) {
                        inputBuf = decoderInputBuffers[inputBufIndex];
                      } else {
                        inputBuf = decoder.getInputBuffer(inputBufIndex);
                      }
                      int chunkSize = extractor.readSampleData(inputBuf, 0);
                      if (chunkSize < 0) {
                        decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                      } else {
                        decoder.queueInputBuffer(inputBufIndex, 0, chunkSize, extractor.getSampleTime(), 0);
                        extractor.advance();
                      }
                    }
                  } else if (index == -1) {
                    eof = true;
                  }
                  if (eof) {
                    int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                    if (inputBufIndex >= 0) {
                      decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                      inputDone = true;
                    }
                  }
                }

                boolean decoderOutputAvailable = !decoderDone;
                boolean encoderOutputAvailable = true;
                while (decoderOutputAvailable || encoderOutputAvailable) {
                  checkConversionCanceled();
                  int encoderStatus = encoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
                  if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    encoderOutputAvailable = false;
                  } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    if (Build.VERSION.SDK_INT < 21) {
                      encoderOutputBuffers = encoder.getOutputBuffers();
                    }
                  } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = encoder.getOutputFormat();
                    if (videoTrackIndex == -5) {
                      videoTrackIndex = mediaMuxer.addTrack(newFormat, false);
                    }
                  } else if (encoderStatus < 0) {
                    throw new RuntimeException("unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
                  } else {
                    ByteBuffer encodedData;
                    if (Build.VERSION.SDK_INT < 21) {
                      encodedData = encoderOutputBuffers[encoderStatus];
                    } else {
                      encodedData = encoder.getOutputBuffer(encoderStatus);
                    }
                    if (encodedData == null) {
                      throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                    }
                    if (info.size > 1) {
                      if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (mediaMuxer.writeSampleData(videoTrackIndex, encodedData, info, false)) {
                          //didWriteData(messageObject, cacheFile, false, false);
                        }
                      } else if (videoTrackIndex == -5) {
                        byte[] csd = new byte[info.size];
                        encodedData.limit(info.offset + info.size);
                        encodedData.position(info.offset);
                        encodedData.get(csd);
                        ByteBuffer sps = null;
                        ByteBuffer pps = null;
                        for (int a = info.size - 1; a >= 0; a--) {
                          if (a > 3) {
                            if (csd[a] == 1 && csd[a - 1] == 0 && csd[a - 2] == 0 && csd[a - 3] == 0) {
                              sps = ByteBuffer.allocate(a - 3);
                              pps = ByteBuffer.allocate(info.size - (a - 3));
                              sps.put(csd, 0, a - 3).position(0);
                              pps.put(csd, a - 3, info.size - (a - 3)).position(0);
                              break;
                            }
                          } else {
                            break;
                          }
                        }

                        MediaFormat newFormat = MediaFormat.createVideoFormat(MIME_TYPE, resultWidth, resultHeight);
                        if (sps != null && pps != null) {
                          newFormat.setByteBuffer("csd-0", sps);
                          newFormat.setByteBuffer("csd-1", pps);
                        }
                        videoTrackIndex = mediaMuxer.addTrack(newFormat, false);
                      }
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(encoderStatus, false);
                  }
                  if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                  }

                  if (!decoderDone) {
                    int decoderStatus = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
                    if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                      decoderOutputAvailable = false;
                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {

                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                      MediaFormat newFormat = decoder.getOutputFormat();
                      //Log.i("DeltaChat", "newFormat = " + newFormat);
                    } else if (decoderStatus < 0) {
                      throw new RuntimeException("unexpected result from decoder.dequeueOutputBuffer: " + decoderStatus);
                    } else {
                      boolean doRender;
                      if (Build.VERSION.SDK_INT >= 18) {
                        doRender = info.size != 0;
                      } else {
                        doRender = info.size != 0 || info.presentationTimeUs != 0;
                      }
                      if (endTime > 0 && info.presentationTimeUs >= endTime) {
                        inputDone = true;
                        decoderDone = true;
                        doRender = false;
                        info.flags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                      }
                      if (startTime > 0 && videoTime == -1) {
                        if (info.presentationTimeUs < startTime) {
                          doRender = false;
                          //Log.i("DeltaChat", "drop frame startTime = " + startTime + " present time = " + info.presentationTimeUs);
                        } else {
                          videoTime = info.presentationTimeUs;
                        }
                      }
                      decoder.releaseOutputBuffer(decoderStatus, doRender);
                      if (doRender) {
                        boolean errorWait = false;
                        try {
                          outputSurface.awaitNewImage();
                        } catch (Exception e) {
                          errorWait = true;
                          Log.d(TAG, "error while waiting for recording output surface", e);
                        }
                        if (!errorWait) {
                          if (Build.VERSION.SDK_INT >= 18) {
                            outputSurface.drawImage(false);
                            inputSurface.setPresentationTime(info.presentationTimeUs * 1000);
                            inputSurface.swapBuffers();
                          } else {
                            Log.i(TAG, "Cannot proceed with the current SDK version");
                            return false; // TODO: this should be caught much earlier
                            /*
                            int inputBufIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC);
                            if (inputBufIndex >= 0) {
                              outputSurface.drawImage(true);
                              ByteBuffer rgbBuf = outputSurface.getFrame();
                              ByteBuffer yuvBuf = encoderInputBuffers[inputBufIndex];
                              yuvBuf.clear();
                              Utilities.convertVideoFrame(rgbBuf, yuvBuf, colorFormat, resultWidth, resultHeight, padding, swapUV);
                              encoder.queueInputBuffer(inputBufIndex, 0, bufferSize, info.presentationTimeUs, 0);
                            } else {
                              //Log.i("DeltaChat", "input buffer not available");
                            }
                            */
                          }
                        }
                      }
                      if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        decoderOutputAvailable = false;
                        //Log.i("DeltaChat", "decoder stream end");
                        if (Build.VERSION.SDK_INT >= 18) {
                          encoder.signalEndOfInputStream();
                        } else {
                          int inputBufIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC);
                          if (inputBufIndex >= 0) {
                            encoder.queueInputBuffer(inputBufIndex, 0, 1, info.presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                          }
                        }
                      }
                    }
                  }
                }
              }
              if (videoTime != -1) {
                videoStartTime = videoTime;
              }
            } catch (Exception e) {
              Log.i(TAG,"Recoding video failed unexpectedly", e);
              error = true;
            }

            extractor.unselectTrack(videoIndex);

            if (outputSurface != null) {
              outputSurface.release();
            }
            if (inputSurface != null) {
              inputSurface.release();
            }
            if (decoder != null) {
              decoder.stop();
              decoder.release();
            }
            if (encoder != null) {
              encoder.stop();
              encoder.release();
            }

            checkConversionCanceled();
          }
        } else {
          long videoTime = readAndWriteTrack(extractor, mediaMuxer, info, startTime, endTime, cacheFile, false);
          if (videoTime != -1) {
            videoStartTime = videoTime;
          }
        }
        if (!error) {
          readAndWriteTrack(extractor, mediaMuxer, info, videoStartTime, endTime, cacheFile, true);
        }
      } catch (Exception e) {
        error = true;
        Log.i(TAG,"Recoding video failed unexpectedly", e);
        return false;
      } finally {
        if (extractor != null) {
          extractor.release();
        }
        if (mediaMuxer != null) {
          try {
            mediaMuxer.finishMovie(error);
          } catch (Exception e) {
            Log.i(TAG,"Flushing video failed unexpectedly", e);
          }
        }
        //Log.i("DeltaChat", "time = " + (System.currentTimeMillis() - time));
      }
    } else {
      //didWriteData(messageObject, cacheFile, true, true);
      Log.i(TAG,"Video width or height are 0, refusing recode.");
      return false;
    }
    //didWriteData(messageObject, cacheFile, true, error);
    return true;
  }

  private static class VideoEditedInfo {
    String originalPath;
    float  originalDurationMs;
    long   originalAudioBytes;
    int    originalRotationValue;
    int    originalWidth;
    int    originalHeight;
    int    originalVideoBitrate;

    long   startTime;
    long   endTime;
    int    rotationValue;

    int    resultWidth;
    int    resultHeight;
    int    resultVideoBitrate;

    int    estimatedBytes;
  }

  public static boolean canRecode()
  {
    boolean canRecode = true;
    if (Build.VERSION.SDK_INT < 16 /*= Jelly Bean 4.1 (before that codecInfo.getName() was not there) */) {
      canRecode = false;
    }
    else if (Build.VERSION.SDK_INT < 18 /*= Jelly Bean 4.3*/) {
      try {
        MediaCodecInfo codecInfo = VideoRecoder.selectCodec(VideoRecoder.MIME_TYPE);
        if (codecInfo == null) {
          canRecode = false;
        } else {
          String name = codecInfo.getName();
          if (name.equals("OMX.google.h264.encoder") ||
              name.equals("OMX.ST.VFM.H264Enc") ||
              name.equals("OMX.Exynos.avc.enc") ||
              name.equals("OMX.MARVELL.VIDEO.HW.CODA7542ENCODER") ||
              name.equals("OMX.MARVELL.VIDEO.H264ENCODER") ||
              name.equals("OMX.k3.video.encoder.avc") ||
              name.equals("OMX.TI.DUCATI1.VIDEO.H264E")) {
            canRecode = false;
          } else {
            if (VideoRecoder.selectColorFormat(codecInfo, VideoRecoder.MIME_TYPE) == 0) {
              canRecode = false;
            }
          }
        }
      } catch (Exception e) {
        Log.i(TAG, "Determinating recoding capabilities failed unexpectedly", e);
        canRecode = false;
      }
    }
    return canRecode;
  }

  private static VideoEditedInfo getVideoEditInfoFromFile(String videoPath) {
    // load information for the given video
    VideoEditedInfo vei = new VideoEditedInfo();
    vei.originalPath = videoPath;

    try {
      IsoFile isoFile = new IsoFile(videoPath);
      List<Box> boxes = Path.getPaths(isoFile, "/moov/trak/");
      TrackHeaderBox trackHeaderBox = null;

      // if we find a video-track, we're just optimistic that the following recoding also works -
      // if it fails, we know this then.
      // older versions check before for paths as "/moov/trak/mdia/minf/stbl/stsd/mp4a/"
      // (using Path.getPath()), however this is not sufficient, other paths as "moov/mvhd"
      // are also valid and it is hard to maintain a list here.

      for (Box box : boxes) {
        TrackBox trackBox = (TrackBox) box;
        long sampleSizes = 0;
        long trackBitrate = 0;
        try {
          MediaBox mediaBox = trackBox.getMediaBox();
          MediaHeaderBox mediaHeaderBox = mediaBox.getMediaHeaderBox();
          SampleSizeBox sampleSizeBox = mediaBox.getMediaInformationBox().getSampleTableBox().getSampleSizeBox();
          for (long size : sampleSizeBox.getSampleSizes()) {
            sampleSizes += size;
          }
          float originalVideoSeconds = (float) mediaHeaderBox.getDuration() / (float) mediaHeaderBox.getTimescale();
          trackBitrate = (int) (sampleSizes * 8 / originalVideoSeconds);
          vei.originalDurationMs = originalVideoSeconds * 1000;
        } catch (Exception e) {
          Log.i(TAG, "Calculating sample sizes failed unexpectedly", e);
        }
        TrackHeaderBox headerBox = trackBox.getTrackHeaderBox();
        if (headerBox.getWidth() != 0 && headerBox.getHeight() != 0) {
          trackHeaderBox = headerBox;
          vei.originalVideoBitrate = (int) (trackBitrate / 100000 * 100000);
        } else {
          vei.originalAudioBytes += sampleSizes;
        }
      }
      if (trackHeaderBox == null) {
        return null;
      }

      Matrix matrix = trackHeaderBox.getMatrix();
      if (matrix.equals(Matrix.ROTATE_90)) {
        vei.originalRotationValue = 90;
      } else if (matrix.equals(Matrix.ROTATE_180)) {
        vei.originalRotationValue = 180;
      } else if (matrix.equals(Matrix.ROTATE_270)) {
        vei.originalRotationValue = 270;
      }
      vei.originalWidth = (int) trackHeaderBox.getWidth();
      vei.originalHeight = (int) trackHeaderBox.getHeight();

    } catch (Exception e) {
      Log.i(TAG, "Reading message info failed unexpectedly", e);
      return null;
    }

    return vei;
  }

  private static int calculateEstimatedSize(float timeDelta, int resultBitrate, float originalDurationMs, long originalAudioBytes) {
    long videoFramesSize = (long) (resultBitrate / 8 * (originalDurationMs /1000));
    int size = (int) ((originalAudioBytes + videoFramesSize) * timeDelta);
    return size;
  }

  private static void toastRecodingFailed(Context context) {
    Util.runOnMain(() -> Toast.makeText(context, R.string.recoding_video_failed,
        Toast.LENGTH_LONG).show());
  }

  // prepareVideo() assumes the msg object is set up properly to being sent;
  // the function fills out missing information and also recodes the video as needed;
  // to get a responsive ui, DcChat.prepareMsg() may be called.
  // see canRecode() for version requirement
  // return: true=video might be prepared, can be sent, false=error
  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
  public static boolean prepareVideo(Context context, int chatId, DcMsg msg) {

    try {
      String inPath = msg.getFile();

      // try to get information from video file
      VideoEditedInfo vei = getVideoEditInfoFromFile(inPath);
      if (vei == null) {
        Log.w(TAG, String.format("recoding for %s failed: cannot get info", inPath));
        toastRecodingFailed(context);
        return false;
      }
      vei.rotationValue = vei.originalRotationValue;
      vei.startTime = 0;
      vei.endTime = -1;

      // set these information to the message object (not yet in database);
      // if we can recdode, this will be overwritten below
      if (vei.originalRotationValue == 90 || vei.originalRotationValue == 270) {
        msg.setDimension(vei.originalHeight, vei.originalWidth);
      } else {
        msg.setDimension(vei.originalWidth, vei.originalHeight);
      }
      msg.setDuration((int)vei.originalDurationMs);

      if (!canRecode()) {
        Log.w(TAG, String.format("recoding for %s failed: this system cannot recode videos", inPath));
        toastRecodingFailed(context);
        return false;
      }

      // check if video bitrate is already reasonable
      final int  MAX_KBPS = 1500000;
      final long MAX_BYTES = DcHelper.getInt(context, "sys.msgsize_max_recommended");
      long inBytes = new File(inPath).length();
      if (inBytes > 0 && inBytes <= MAX_BYTES && vei.originalVideoBitrate <= MAX_KBPS*2 /*be tolerant as long the file size matches*/) {
        Log.i(TAG, String.format("recoding for %s is not needed, %d bytes and %d kbps are ok", inPath, inBytes, vei.originalVideoBitrate));
        return true;
      }

      // calculate new video bitrate, sth. between 200 kbps and 1500 kbps
      long resultDurationMs = (long) vei.originalDurationMs;
      long maxVideoBytes = MAX_BYTES - vei.originalAudioBytes - resultDurationMs /*10 kbps codec overhead*/;
      vei.resultVideoBitrate = (int) (maxVideoBytes / Math.max(1, resultDurationMs / 1000) * 8);

      if (vei.resultVideoBitrate < 200000) {
        vei.resultVideoBitrate = 200000;
      } else if (vei.resultVideoBitrate > 500000) {
        boolean hardCompression = Prefs.isHardCompressionEnabled(context);
        if (resultDurationMs < 30 * 1000 && !hardCompression) {
          vei.resultVideoBitrate = MAX_KBPS; // ~ 12 MB/minute, plus Audio
        } else if (resultDurationMs < 60 * 1000 && !hardCompression) {
          vei.resultVideoBitrate = 1000000; // ~ 8 MB/minute, plus Audio
        } else {
          vei.resultVideoBitrate = 500000; // ~ 3.7 MB/minute, plus Audio
        }
      }

      // calculate video dimensions
      int maxSide = vei.resultVideoBitrate > 400000 ? 640 : 480;
      vei.resultWidth = vei.originalWidth;
      vei.resultHeight = vei.originalHeight;
      if (vei.resultWidth > maxSide || vei.resultHeight > maxSide) {
        float scale = vei.resultWidth > vei.resultHeight ? (float) maxSide / vei.resultWidth : (float) maxSide / vei.resultHeight;
        vei.resultWidth *= scale;
        vei.resultHeight *= scale;
      }

      // we know the most important things now, prepare the message to get a resposive ui
      if (vei.originalRotationValue == 90 || vei.originalRotationValue == 270) {
        msg.setDimension(vei.resultHeight, vei.resultWidth);
      } else {
        msg.setDimension(vei.resultWidth, vei.resultHeight);
      }
      msg.setDuration((int) resultDurationMs);
      DcHelper.getContext(context).prepareMsg(chatId, msg);

      // calulate bytes
//      vei.estimatedBytes = VideoRecoder.calculateEstimatedSize((float) resultDurationMs / vei.originalDurationMs,
//          vei.resultVideoBitrate, vei.originalDurationMs, vei.originalAudioBytes);

//      if (vei.estimatedBytes > MAX_BYTES+MAX_BYTES/4) {
//        logNtoast(context, String.format("recoding for %s failed: resulting file probably too large", inPath));
//        return false;
//      }

      // recode
      String tempPath = DcHelper.getContext(context).getBlobdirFile(inPath);
      VideoRecoder videoRecoder = new VideoRecoder();
      if (!videoRecoder.convertVideo(vei, tempPath)) {
        Log.w(TAG, String.format("recoding for %s failed: cannot convert to temporary file %s", inPath, tempPath));
        toastRecodingFailed(context);
        return false;
      }

      if (!Util.moveFile(tempPath, inPath)) {
        Log.w(TAG, String.format("recoding for %s failed: cannot move temporary file %s", inPath, tempPath));
        toastRecodingFailed(context);
        return false;
      }

      Log.i(TAG, String.format("recoding for %s done", inPath));
    }
    catch(Exception e) {
      Log.w(TAG, "Video recoding failed unexpectedly.", e);
      toastRecodingFailed(context);
      return false;
    }

    return true;
  }
}
