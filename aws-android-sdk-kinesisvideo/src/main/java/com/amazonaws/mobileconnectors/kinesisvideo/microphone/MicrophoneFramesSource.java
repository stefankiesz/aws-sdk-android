package com.amazonaws.mobileconnectors.kinesisvideo.microphone;


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


public class MicrophoneFramesSource {
    private static final int AUDIO_SAMPLE_RATE = 8000; // mMediaSourceConfiguration.getSampleRate(), Hardcoded sample rate for now (emulator requires 8000);
    private static final int AUDIO_CHANNEL_TYPE = AudioFormat.CHANNEL_IN_MONO; // Android Docs: "CHANNEL_IN_MONO is guaranteed to work on all devices."
    private static final int AUDIO_ENCODING_TYPE = AudioFormat.ENCODING_PCM_16BIT; // Android Docs: "ENCODING_PCM_16BIT is Guaranteed to be supported by devices."
    private static final int AUDIO_BIT_RATE = 6000;

    private final int bufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_TYPE, AUDIO_ENCODING_TYPE); 

    private final OutputStream outputStream = new ByteArrayOutputStream();


    private AudioRecord audioRecord = null;
    MediaCodec audioEncoder = null;
    private Thread audioCaptureThread = null;
    private boolean isRecording = false;
    private long mLastRecordedFrameTimestamp = 0;



    public void startAudioCapture () {
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.DEFAULT,
                        AUDIO_SAMPLE_RATE,
                        AUDIO_CHANNEL_TYPE,
                        AUDIO_ENCODING_TYPE,
                        bufferSize);
            
            audioEncoder = createAudioEncoder();
            audioEncoder.start();
            audioRecord.startRecording();
            isRecording = true;

            audioCaptureThread = new Thread(new Runnable() {
                public void run() {
                    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                    ByteBuffer[] codecInputBuffers = audioEncoder.getInputBuffers();
                    ByteBuffer[] codecOutputBuffers = audioEncoder.getOutputBuffers();


                    try {
                        while (true) {
                            // System.out.println("[TESTING] Handling audio sample...");
                            boolean success = handleCodecInput(codecInputBuffers, Thread.currentThread().isAlive());
                            if (success) {handleCodecOutput(codecOutputBuffers, bufferInfo, outputStream);}
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

    // private void encodeCapturedAudio(MediaCodec audioEncoder) {
    //     int codecInputBufferIndex = audioEncoder.dequeueInputBuffer(10 * 1000);

    //     if (codecInputBufferIndex >= 0) {
    //         ByteBuffer codecBuffer = codecInputBuffers[codecInputBufferIndex];
    //         codecBuffer.clear();
    //         codecBuffer.put(audioRecordData);
    //         audioEncoder.queueInputBuffer(codecInputBufferIndex, 0, length, 0, running ? 0 : audioEncoder.BUFFER_FLAG_END_OF_STREAM);
    //     }
    // }

    private boolean handleCodecInput(ByteBuffer[] codecInputBuffers, boolean running) throws IOException {
        byte[] audioRecordData = new byte[bufferSize];
        int length = audioRecord.read(audioRecordData, 0, audioRecordData.length);

        if (length == AudioRecord.ERROR_BAD_VALUE ||
                length == AudioRecord.ERROR_INVALID_OPERATION ||
                length != bufferSize) {
            if (length != bufferSize) {
                return false;
            }
        }

        int codecInputBufferIndex = audioEncoder.dequeueInputBuffer(-1); // (-1 == no timeout)

        if (codecInputBufferIndex >= 0) {
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

        while (codecOutputBufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
            if (codecOutputBufferIndex >= 0) {
                ByteBuffer encoderOutputBuffer = codecOutputBuffers[codecOutputBufferIndex];

                encoderOutputBuffer.position(bufferInfo.offset);
                encoderOutputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                    // byte[] header = createAdtsHeader(bufferInfo.size - bufferInfo.offset);
                    // outputStream.write(header);

                    byte[] data = new byte[encoderOutputBuffer.remaining()];
                    encoderOutputBuffer.get(data);
                    outputStream.write(data);
                }

                encoderOutputBuffer.clear();

                audioEncoder.releaseOutputBuffer(codecOutputBufferIndex, false);
            } else if (codecOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                codecOutputBuffers = audioEncoder.getOutputBuffers();
            }

            codecOutputBufferIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, 0);
        }
    }


    // private void submitAudioSampleToKVS() {
    //     // Write the output audio in byte

    //     String filePath = "/sdcard/voice8K16bitmono.pcm";
    //     short sData[] = new short[BufferElements2Rec];

    //     FileOutputStream os = null;
    //     try {
    //         os = new FileOutputStream(filePath);
    //     } catch (FileNotFoundException e) {
    //         e.printStackTrace();
    //     }

    //     while (isRecording) {
    //         // gets the voice output from microphone to byte format

    //         recorder.read(sData, 0, BufferElements2Rec);
    //         System.out.println("Short writing to file" + sData.toString());
    //         try {
    //             // // writes the data to file from buffer
    //             // // stores the voice buffer
    //             byte bData[] = short2byte(sData);
    //             os.write(bData, 0, BufferElements2Rec * BytesPerElement);
    //         } catch (IOException e) {
    //             e.printStackTrace();
    //         }
    //     }
    //     try {
    //         os.close();
    //     } catch (IOException e) {
    //         e.printStackTrace();
    //     }
    // }

    // private void sendEncodedFrameToProducerSDK(final ByteBuffer encodedData) {
    //     final long currentTime = System.currentTimeMillis();
    //     Log.d(TAG, "time between frames: " + (currentTime - mLastRecordedFrameTimestamp) + "ms");
    //     mLastRecordedFrameTimestamp = currentTime;

    //     if (mFragmentStart == 0) {
    //         mFragmentStart = currentTime;
    //     }

    //     final ByteBuffer frameData = encodedData;

    //     mFrameAvailableListener.onFrameAvailable(
    //             FrameUtility.createFrame(
    //                     mBufferInfo,
    //                     1 + currentTime - mFragmentStart,
    //                     mFrameIndex++,
    //                     frameData));
    // }



}








// public class Audio_Record extends Activity {
//     private static final int RECORDER_SAMPLERATE = 8000;
//     private static final int RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_MONO;
//     private static final int RECORDER_AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
//     private AudioRecord recorder = null;
//     private Thread recordingThread = null;
//     private boolean isRecording = false;

//     @Override
//     public void onCreate(Bundle savedInstanceState) {
//         super.onCreate(savedInstanceState);
//         setContentView(R.layout.main);

//         setButtonHandlers();
//         enableButtons(false);

//         int bufferSize = AudioRecord.getMinBufferSize(RECORDER_SAMPLERATE,
//                 RECORDER_CHANNELS, RECORDER_AUDIO_ENCODING); 
//     }

//     private void setButtonHandlers() {
//         ((Button) findViewById(R.id.btnStart)).setOnClickListener(btnClick);
//         ((Button) findViewById(R.id.btnStop)).setOnClickListener(btnClick);
//     }

//     private void enableButton(int id, boolean isEnable) {
//         ((Button) findViewById(id)).setEnabled(isEnable);
//     }

//     private void enableButtons(boolean isRecording) {
//         enableButton(R.id.btnStart, !isRecording);
//         enableButton(R.id.btnStop, isRecording);
//     }

//     int BufferElements2Rec = 1024; // want to play 2048 (2K) since 2 bytes we use only 1024
//     int BytesPerElement = 2; // 2 bytes in 16bit format

//     private void startRecording() {

//         recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
//                 RECORDER_SAMPLERATE, RECORDER_CHANNELS,
//                 RECORDER_AUDIO_ENCODING, BufferElements2Rec * BytesPerElement);

//         recorder.startRecording();
//         isRecording = true;
//         recordingThread = new Thread(new Runnable() {
//             public void run() {
//                 writeAudioDataToFile();
//             }
//         }, "AudioRecorder Thread");
//         recordingThread.start();
//     }

//         //convert short to byte
//     private byte[] short2byte(short[] sData) {
//         int shortArrsize = sData.length;
//         byte[] bytes = new byte[shortArrsize * 2];
//         for (int i = 0; i < shortArrsize; i++) {
//             bytes[i * 2] = (byte) (sData[i] & 0x00FF);
//             bytes[(i * 2) + 1] = (byte) (sData[i] >> 8);
//             sData[i] = 0;
//         }
//         return bytes;

//     }

//     private void writeAudioDataToFile() {
//         // Write the output audio in byte

//         String filePath = "/sdcard/voice8K16bitmono.pcm";
//         short sData[] = new short[BufferElements2Rec];

//         FileOutputStream os = null;
//         try {
//             os = new FileOutputStream(filePath);
//         } catch (FileNotFoundException e) {
//             e.printStackTrace();
//         }

//         while (isRecording) {
//             // gets the voice output from microphone to byte format

//             recorder.read(sData, 0, BufferElements2Rec);
//             System.out.println("Short writing to file" + sData.toString());
//             try {
//                 // // writes the data to file from buffer
//                 // // stores the voice buffer
//                 byte bData[] = short2byte(sData);
//                 os.write(bData, 0, BufferElements2Rec * BytesPerElement);
//             } catch (IOException e) {
//                 e.printStackTrace();
//             }
//         }
//         try {
//             os.close();
//         } catch (IOException e) {
//             e.printStackTrace();
//         }
//     }

//     private void stopRecording() {
//         // stops the recording activity
//         if (null != recorder) {
//             isRecording = false;
//             recorder.stop();
//             recorder.release();
//             recorder = null;
//             recordingThread = null;
//         }
//     }

//     private View.OnClickListener btnClick = new View.OnClickListener() {
//         public void onClick(View v) {
//             switch (v.getId()) {
//             case R.id.btnStart: {
//                 enableButtons(true);
//                 startRecording();
//                 break;
//             }
//             case R.id.btnStop: {
//                 enableButtons(false);
//                 stopRecording();
//                 break;
//             }
//             }
//         }
//     };

//     @Override
//     public boolean onKeyDown(int keyCode, KeyEvent event) {
//         if (keyCode == KeyEvent.KEYCODE_BACK) {
//             finish();
//         }
//         return super.onKeyDown(keyCode, event);
//     }
// }
