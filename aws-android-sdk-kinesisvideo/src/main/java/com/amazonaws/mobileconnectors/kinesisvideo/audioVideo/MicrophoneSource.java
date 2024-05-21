package com.amazonaws.mobileconnectors.kinesisvideo.audiovideo;


import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.MediaFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import com.amazonaws.kinesisvideo.common.exception.KinesisVideoException;
import com.amazonaws.mobileconnectors.kinesisvideo.encoding.EncoderWrapper.FrameAvailableListener;
import com.amazonaws.kinesisvideo.producer.KinesisVideoFrame;
import com.amazonaws.mobileconnectors.kinesisvideo.util.FrameUtility;
import com.amazonaws.kinesisvideo.producer.Tag;
import com.amazonaws.kinesisvideo.internal.client.mediasource.MediaSourceSink;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.AUDIO_TRACK_ID;



public class MicrophoneSource {
    private static final String TAG = MicrophoneSource.class.getSimpleName();

    // TODO: Make the below configured through mAudioVideoMediaSourceConfiguration
    private static final int AUDIO_SAMPLE_RATE = 8000; // mMediaSourceConfiguration.getSampleRate(), Hardcoded sample rate for now (emulator requires 8000);
    private static final int AUDIO_CHANNEL_TYPE = AudioFormat.CHANNEL_IN_MONO; // Android Docs: "CHANNEL_IN_MONO is guaranteed to work on all devices."
    private static final int AUDIO_ENCODING_TYPE = AudioFormat.ENCODING_PCM_16BIT; // Android Docs: "ENCODING_PCM_16BIT is Guaranteed to be supported by devices."
    private static final int AUDIO_BIT_RATE = 6000;

    private final int bufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_TYPE, AUDIO_ENCODING_TYPE); 

    private final OutputStream outputStream = new ByteArrayOutputStream();


    private AudioRecord audioRecord = null;
    MediaCodec audioEncoder = null;
    private Thread audioCaptureThread = null;
    private boolean isCapturing = false;
    private long mLastRecordedFrameTimestamp = 0;
    private int mFrameIndex = 0;
    private long mFragmentStart = 0;
    MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    private MediaSourceSink mMediaSourceSink;

    public MicrophoneSource(MediaSourceSink mediaSourceSink) {
        mMediaSourceSink = mediaSourceSink;
    }

    private FrameAvailableListener mFrameAvailableListener = new FrameAvailableListener() {
        @Override
        public void onFrameAvailable(final KinesisVideoFrame frame) {
            try {
                Log.i(TAG, "updating sink with frame");
                mMediaSourceSink.onFrame(frame);
            } catch (final KinesisVideoException e) {
                Log.e(TAG, "error updating sink with frame", e);
            }
        }
    };



    public void startAudioCapture () {
        System.out.println("[TESTING] startAudioCapture called.");

        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.DEFAULT,
                        AUDIO_SAMPLE_RATE,
                        AUDIO_CHANNEL_TYPE,
                        AUDIO_ENCODING_TYPE,
                        bufferSize);
            
            audioEncoder = createAudioEncoder();
            audioEncoder.start();
            audioRecord.startRecording();
            isCapturing = true;

            audioCaptureThread = new Thread(new Runnable() {
                public void run() {
                    // MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                    ByteBuffer[] codecInputBuffers = audioEncoder.getInputBuffers();
                    ByteBuffer[] codecOutputBuffers = audioEncoder.getOutputBuffers();


                    try {
                        while (isCapturing) {
                            // System.out.println("[TESTING] Handling audio sample...");
                            boolean success = handleCodecInput(codecInputBuffers, Thread.currentThread().isAlive());
                            if (success) {handleCodecOutput(codecOutputBuffers, mBufferInfo, outputStream);}
                        }
                    } catch (IOException e) {
                        System.out.println("Failed in audioCaptureThread: " + e);
                    } finally {
                        audioEncoder.stop();
                        audioRecord.stop();
            
                        audioEncoder.release();
                        audioRecord.release();
            
                        try {
                            outputStream.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }


                    // submitAudioSampleToKVS();
                }
            }, "AudioCapture Thread");
            audioCaptureThread.start();

        } catch (final SecurityException e) {
            System.out.println("Security exception: " + e);
        } catch (IOException e) {
            System.out.println("Failed in audioCaptureThread: " + e);
        }
    }

    public void stopAudioCapture() {
        Log.i(TAG, "stopping audio capturing");
        isCapturing = false;
    }

    private MediaCodec createAudioEncoder() throws IOException {

        MediaCodec mediaCodec = null;
        try{
            mediaCodec = MediaCodec.createEncoderByType("audio/mp4a-latm");

        } catch (Exception e) {
            System.out.println("[TESTING] Failed to createEncoderByType");
            throw new IOException(e);
        }
        MediaFormat mediaFormat = new MediaFormat();

        mediaFormat.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC);
        mediaFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, AUDIO_SAMPLE_RATE);
        mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, AUDIO_CHANNEL_TYPE);
        mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE);
        mediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);

        try {
            mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Exception e) {
            System.out.println("[TESTING] Failed to configure mediaCodec");
            mediaCodec.release();
            throw new IOException(e);
        }

        return mediaCodec;
    }

    private boolean handleCodecInput(ByteBuffer[] codecInputBuffers, boolean running) throws IOException {
        byte[] audioRecordData = new byte[bufferSize];
        int length = audioRecord.read(audioRecordData, 0, audioRecordData.length);

        System.out.println("[TESTING] handleCodecInput checking for invalid data length.");

        if (length == AudioRecord.ERROR_BAD_VALUE ||
                length == AudioRecord.ERROR_INVALID_OPERATION ||
                length != bufferSize) {
            if (length != bufferSize) {
                return false;
            }
        }

        System.out.println("[TESTING] handleCodecInput calling dequeueInputBuffer.");

        int codecInputBufferIndex = audioEncoder.dequeueInputBuffer(-1); // (-1 == no timeout)

        if (codecInputBufferIndex >= 0) {
            System.out.println("[TESTING] handleCodecInput codecInputBufferIndex is >= 0.");
            ByteBuffer codecBuffer = codecInputBuffers[codecInputBufferIndex];
            codecBuffer.clear();
            codecBuffer.put(audioRecordData);
            audioEncoder.queueInputBuffer(codecInputBufferIndex, 0, length, 0, running ? 0 : audioEncoder.BUFFER_FLAG_END_OF_STREAM);
        }

        return true;
    }


    private void handleCodecOutput(ByteBuffer[] codecOutputBuffers,
                                   MediaCodec.BufferInfo bufferInfo,
                                   OutputStream outputStream)
            throws IOException {
        int codecOutputBufferIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, 0);

        if (mBufferInfo.size == 0) {
            Log.w(TAG, "empty buffer " + codecOutputBufferIndex);
            //audioEncoder.releaseOutputBuffer(codecOutputBufferIndex, false);
            return;
        }

        System.out.println("[TESTING] handleCodecOutput starting while loop.");


        while (codecOutputBufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
            if (codecOutputBufferIndex >= 0) {
                System.out.println("[TESTING] handleCodecOutput codecOutputBufferIndex is >= 0.");

                ByteBuffer encoderOutputBuffer = codecOutputBuffers[codecOutputBufferIndex];

                if (encoderOutputBuffer == null) {
                    System.out.println("[TESTING] encodedData is null.");
                    return;
                }

                encoderOutputBuffer.position(bufferInfo.offset);
                encoderOutputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                    // byte[] header = createAdtsHeader(bufferInfo.size - bufferInfo.offset);
                    // outputStream.write(header);
            
                    if (encoderOutputBuffer == null) {
                        System.out.println("[TESTING] encoderOutputBuffer is null.");
                        return;
                    }
                    
                    System.out.println("[TESTING] handleCodecOutput calling sendEncodedFrameToProducerSDK.");
                    sendEncodedFrameToProducerSDK(encoderOutputBuffer);
                    // outputStream.write(data);
                } else {
                    System.out.println("[TESTING] Audio encoder outputted Codec Config!");
                    notifyCodecPrivateDataAvailable(encoderOutputBuffer);
                }

                encoderOutputBuffer.clear();

                audioEncoder.releaseOutputBuffer(codecOutputBufferIndex, false);
            } else if (codecOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                System.out.println("[TESTING] handleCodecOutput codecOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED.");
                codecOutputBuffers = audioEncoder.getOutputBuffers();
            }

            codecOutputBufferIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, 0);
        }
    }

    private void notifyCodecPrivateDataAvailable(final ByteBuffer codecPrivateData) {
        Log.d(TAG, "[TESTING] got codec private data");

        final byte[] codecPrivateDataArray = new byte[codecPrivateData.remaining()];
        codecPrivateData.get(codecPrivateDataArray);

        System.out.println("[TESTING] Calling onCodecPrivateData");

        try {
            mMediaSourceSink.onCodecPrivateData(codecPrivateDataArray, AUDIO_TRACK_ID);
        } catch (KinesisVideoException e) {
            Log.e(TAG, "error updating sink with codec private data", e);
            throw new RuntimeException("error updating sink with codec private data", e);
        }

        // if (mListener == null) {
        //     mCodecPrivateDataListener.onCodecPrivateDataAvailable(codecPrivateDataArray);
        // } else {
        //     try {
        //         mListener.onCodecPrivateData(codecPrivateDataArray, mTrackId);
        //     } catch (KinesisVideoException e) {
        //         Log.e(TAG, "error updating sink with codec private data", e);
        //         throw new RuntimeException("error updating sink with codec private data", e);
        //     }
        // }
    }


    private void sendEncodedFrameToProducerSDK(final ByteBuffer encodedData) {
        final long currentTime = System.currentTimeMillis();
        Log.d(TAG, "[TESTING] Microphone's sendEncodedFrameToProducerSDK time between frames: " + (currentTime - mLastRecordedFrameTimestamp) + "ms");

        mLastRecordedFrameTimestamp = currentTime;

        if (mFragmentStart == 0) {
            mFragmentStart = currentTime;
        }

        final ByteBuffer frameData = encodedData;

        mFrameAvailableListener.onFrameAvailable(
                FrameUtility.createFrameWithTrackID(
                        mBufferInfo,
                        1 + currentTime - mFragmentStart,
                        mFrameIndex++,
                        frameData,
                        AUDIO_TRACK_ID));
    }
}