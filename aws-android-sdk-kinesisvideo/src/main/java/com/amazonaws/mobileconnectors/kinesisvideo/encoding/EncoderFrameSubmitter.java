/**
 * Copyright 2017-2018 Amazon.com,
 * Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Amazon Software License (the "License").
 * You may not use this file except in compliance with the
 * License. A copy of the License is located at
 *
 *     http://aws.amazon.com/asl/
 *
 * or in the "license" file accompanying this file. This file is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, express or implied. See the License
 * for the specific language governing permissions and
 * limitations under the License.
 */

package com.amazonaws.mobileconnectors.kinesisvideo.encoding;

import android.media.Image;
import android.media.MediaCodec;

import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import android.graphics.Rect;
import android.graphics.ImageFormat;


/**
 * Utility class to submit frames to the encoder.
 *
 * Submits the camera image image into the encoder plane-by-plane.
 *
 * It's up to you to make sure the camera image has the same color format as the encoder input
 * image. It works on multiple devices when camera image is in ImageFormat.YUV_420_888 and
 * encoder is MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible. 'Flexible' here means
 * that encoder can choose it's own way of packaging the YUV420 frame, so it might not work on
 * some devices. Probably there needs to be more complex logic to mediate/convert between these.
 *
 * Danger. Serious magic happens here, there's zero documentation for this in sdk or interwebs.
 * Modify at your own risk.
 *
 * Currently works like this:
 *
 *   - first, get the temporary input buffer from the encoder:
 *        - encoders expect frame data to be aligned correctly, and they have a lot of
 *          assumptions about it, so it's hard to figure out the way to correctly put data
 *          into it.
 *
 *          So we're not going to use this buffer directly to put the frame data into. We need
 *          it just to know its index and calculate its size.
 *
 *          We still need buffer index and buffer size to do anything with the encoder,
 *          so there's no way to get rid of this step;
 *
 *   - get the input image from the encoder using the buffer index we got in the previous
 *     step:
 *        - encoder gives us writable image in correct format. This way we don't need to worry
 *          about alignments or anything, we just need to populate buffers for each of the image's
 *          plane.
 *
 *          When we get the image, the original buffer from the previous step becomes unusable;
 *
 *   - copy the camera image into the encoder input image plane-by-plane:
 *        - this assumes they're in the same format. Which may not be the case. There should be
 *          more complicated logic to support this;
 *
 *   - submit the image into the encoder:
 *        - we need to use the input buffer index and also provide the size of
 *          the data we're submitting.
 *
 *          Because we used the same input buffer index for the first two steps, we can use the same
 *          index for submitting the buffer into the encoder.
 *
 *          Problem now is that we also need to know the input data size when we're submitting it.
 *          But we used the input image, we did not put data into input buffer directly.
 *          So we don't know its size. That's because when we got the input image from the encoder,
 *          it managed the buffer allocation and alignment and all other magic internally
 *          behind the scenes, so we cannot just add up all image planes sizes
 *          to figure out the buffer size.
 *
 *          So the best guess now is to use the original temporary input buffer size we got from
 *          first step. Some internal alien magic happens here either. Actual image size is larger
 *          than the buffer size from the first step, so encoder seems to use something else
 *          in addition to this information. But if you submit incorrect buffer size it'll crash.
 *
 *    - mic drop
 */
public class EncoderFrameSubmitter {
    private static final long NS_IN_US = 1000;
    private static final long NS_IN_MS = 1000000;
    private static final int FROM_START = 0;
    private static final int NO_FLAGS = 0;
    private static final int DEQUEUE_NOW = -1;

    private final MediaCodec mEncoder;
    private long mFirstFrameTimestamp = -1;

    private int uvPixelStride;

    public EncoderFrameSubmitter(final MediaCodec encoder) {
        mEncoder = encoder;
    }

    public void submitFrameToEncoder(final Image frameImageYUV420,
                                     final boolean endOfStream) {

        // encoders are super sensitive to the timestamps, careful here
        final long timestamp = nanosSinceFirstFrame();
        queueIntoInputImage(frameImageYUV420, timestamp / NS_IN_US, endOfStream);
    }

    /**
     * TLDR: read the above javadoc for the class
     */
    private void queueIntoInputImage(final Image frameImageYUV420,
                                     final long timestampInUS,
                                     final boolean endOfStream) {

        final int flags = endOfStream ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : NO_FLAGS;

        // step one. get the info about the encoder input buffer
        final int inputBufferIndex = mEncoder.dequeueInputBuffer(DEQUEUE_NOW);
        final ByteBuffer tmpBuffer = mEncoder.getInputBuffer(inputBufferIndex);
        final int tmpBufferSize = tmpBuffer.capacity();

        // step two. copy the frame into the encoder input image
        copyCameraFrameIntoInputImage(inputBufferIndex, frameImageYUV420);

        // step three. submit the buffer into the encoder
        mEncoder.queueInputBuffer(
                inputBufferIndex,
                FROM_START,
                tmpBufferSize,
                timestampInUS,
                flags);
    }

    /**
     * this assumes that camera frame and encoder input image have the same format
     */
    private void copyCameraFrameIntoInputImage(final int inputBufferIndex,
                                               final Image cameraFrame) {
        final Image codecInputImage = mEncoder.getInputImage(inputBufferIndex);

        System.out.println("cameraFrame.getPlanes().length is " + cameraFrame.getPlanes().length);

        // ByteBuffer[] planes = convertByteArrayToPlanes(rotateYUV420Degree90(convertYUV420ToByteArray(cameraFrame), 320, 240, 90), 240, 320);
        ByteBuffer[] planes = convertByteArrayToPlanes(convertYUV420ToByteArray(cameraFrame), 320, 240);
        for (int i = 0; i < planes.length; i++) {
            final ByteBuffer sourceImagePlane = planes[i];
        // for (int i = 0; i < cameraFrame.getPlanes().length; i++) {
        //     final ByteBuffer sourceImagePlane = cameraFrame.getPlanes()[i].getBuffer();
            final ByteBuffer destinationImagePlane = codecInputImage.getPlanes()[i].getBuffer();
            copyBuffer(sourceImagePlane, destinationImagePlane);
        }
    }

    private int copyBuffer(final ByteBuffer sourceBuffer,
                           final ByteBuffer destinationBuffer) {

        final int bytesToCopy = Math.min(destinationBuffer.capacity(), sourceBuffer.remaining());
        destinationBuffer.limit(bytesToCopy);
        sourceBuffer.limit(bytesToCopy);
        destinationBuffer.put(sourceBuffer);
        destinationBuffer.rewind();

        return bytesToCopy;
    }

    private long nanosSinceFirstFrame() {
        final long currentTime = System.currentTimeMillis();
        if (mFirstFrameTimestamp < 0) {
            mFirstFrameTimestamp = currentTime;
        }
        return (currentTime - mFirstFrameTimestamp) * NS_IN_MS;
    }

    private ByteBuffer[] convertByteArrayToPlanes(byte[] data, int width, int height) {
        ByteBuffer[] buffers = new ByteBuffer[3];

        // Calculate the size of YUV components
        int ySize = width * height;
        int uvSize = ySize / 4; // U and V planes have half the width and height of Y plane

        // Allocate buffers for Y, U, and V planes
        ByteBuffer yBuffer = ByteBuffer.allocateDirect(ySize);
        ByteBuffer uBuffer;
        ByteBuffer vBuffer;

        // Copy Y components from byte array to Y buffer
        yBuffer.put(data, 0, ySize).rewind(); // Y plane is the first ySize bytes

        if (this.uvPixelStride == 1) {
            uBuffer = ByteBuffer.allocateDirect(uvSize);
            vBuffer = ByteBuffer.allocateDirect(uvSize);

            // Planar format: Copy U and V components from byte array to their respective buffers
            uBuffer.put(data, ySize, uvSize).rewind(); // U plane follows Y plane
            vBuffer.put(data, ySize + uvSize, uvSize).rewind(); // V plane follows U plane
        } else {
            uBuffer = ByteBuffer.allocateDirect(uvSize * 2 - 1);
            vBuffer = ByteBuffer.allocateDirect(uvSize * 2 - 1);
            
            // Semi-planar format: Interleave U and V components
            for (int i = 0; i < uvSize - 1; i++) {
                uBuffer.put(data[ySize + 2 * i]);
                uBuffer.put(data[ySize + 2 * i + 1]);
                vBuffer.put(data[ySize + 2 * i + 1]);
                vBuffer.put(data[ySize + 2 * i + 2]);
            }
            uBuffer.rewind();
            vBuffer.rewind();
        }

        buffers[0] = yBuffer;
        buffers[1] = uBuffer;
        buffers[2] = vBuffer;

        return buffers;
    }

    // public static byte[] rotateYUV420Degree90(byte[] data, int imageWidth, int imageHeight) {
    //     byte[] yuv = new byte[data.length];
    
    //     int yOffset = 0;
    //     int uOffset = imageWidth * imageHeight;
    //     int vOffset = imageWidth * imageHeight + (imageWidth * imageHeight / 4);
    
    //     // Rotate the Y luma
    //     int i = 0;
    //     for (int x = 0; x < imageWidth; x++) {
    //         for (int y = imageHeight - 1; y >= 0; y--) {
    //             yuv[i++] = data[y * imageWidth + x];
    //         }
    //     }
    
    //     // Rotate the U and V color components
    //     int iUv = 0;
    //     for (int x = imageWidth - 1; x > 0; x -= 2) {
    //         for (int y = imageHeight / 2 - 1; y >= 0; y--) {
    //             yuv[uOffset + iUv] = data[(imageWidth * imageHeight) + y * imageWidth + x];
    //             yuv[vOffset + iUv] = data[(imageWidth * imageHeight) + y * imageWidth + (x - 1)];
    //             iUv++;
    //         }
    //     }
    
    //     return yuv;
    // }

    public static byte[] rotateYUV420Degree90(final byte[] yuv,
                                final int width,
                                final int height,
                                final int rotation)
    {
    if (rotation == 0) return yuv;
    if (rotation % 90 != 0 || rotation < 0 || rotation > 270) {
        throw new IllegalArgumentException("0 <= rotation < 360, rotation % 90 == 0");
    }

    final byte[]  output    = new byte[yuv.length];
    final int     frameSize = width * height;
    final boolean swap      = rotation % 180 != 0;
    final boolean xflip     = rotation % 270 != 0;
    final boolean yflip     = rotation >= 180;

    for (int j = 0; j < height; j++) {
        for (int i = 0; i < width; i++) {
        final int yIn = j * width + i;
        final int uIn = frameSize + (j >> 1) * width + (i & ~1);
        final int vIn = uIn       + 1;

        final int wOut     = swap  ? height              : width;
        final int hOut     = swap  ? width               : height;
        final int iSwapped = swap  ? j                   : i;
        final int jSwapped = swap  ? i                   : j;
        final int iOut     = xflip ? wOut - iSwapped - 1 : iSwapped;
        final int jOut     = yflip ? hOut - jSwapped - 1 : jSwapped;

        final int yOut = jOut * wOut + iOut;
        final int uOut = frameSize + (jOut >> 1) * wOut + (iOut & ~1);
        final int vOut = uOut + 1;

        output[yOut] = (byte)(0xff & yuv[yIn]);
        output[uOut] = (byte)(0xff & yuv[uIn]);
        output[vOut] = (byte)(0xff & yuv[vIn]);
        }
    }
    return output;
    }
    
    private byte[] convertYUV420ToByteArray(Image image) {
        // Extract the planes from the image
        Image.Plane[] planes = image.getPlanes();
    
        // Calculate the size of YUV components
        int width = image.getWidth();
        int height = image.getHeight();
        int ySize = width * height;
        int uvSize = ySize / 4; // U and V planes have half the width and height of Y plane
    
        // Allocate a byte array to hold the YUV data
        byte[] yuvData = new byte[ySize + 2 * uvSize];
    
        // Copy Y plane data to the byte array
        ByteBuffer yBuffer = planes[0].getBuffer();
        yBuffer.get(yuvData, 0, ySize);
    
        // Copy U and V plane data to the byte array
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        int uvRowStride = planes[1].getRowStride();
        this.uvPixelStride = planes[1].getPixelStride();

        int uvHeight = height / 2;
        int uvWidth = width / 2;

        System.out.println("[TESTING][PLANES] Pixel stride is: " + this.uvPixelStride);

        if (this.uvPixelStride == 1) {
            // Planar format
            uBuffer.get(yuvData, ySize, uvSize);
            vBuffer.get(yuvData, ySize + uvSize, uvSize);
        } else {
            // Semi-planar format
            for (int row = 0; row < uvHeight; row++) {
                int rowOffset = row * uvRowStride;
                for (int col = 0; col < uvWidth; col++) {
                    yuvData[ySize + row * uvWidth + col] = uBuffer.get(rowOffset + col * this.uvPixelStride);
                    yuvData[ySize + uvSize + row * uvWidth + col] = vBuffer.get(rowOffset + col * this.uvPixelStride);
                }
            }
        }

        return yuvData;
    }

    
    public static byte[] imageToMat(Image image) {

        Image.Plane[] planes = image.getPlanes();
    
        ByteBuffer buffer0 = planes[0].getBuffer();
        ByteBuffer buffer1 = planes[1].getBuffer();
        ByteBuffer buffer2 = planes[2].getBuffer();
    
        int offset = 0;
    
        int width = image.getWidth();
        int height = image.getHeight();
    
        byte[] data = new byte[image.getWidth() * image.getHeight() * ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8];
        byte[] rowData1 = new byte[planes[1].getRowStride()];
        byte[] rowData2 = new byte[planes[2].getRowStride()];
    
        int bytesPerPixel = ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8;
    
        // loop via rows of u/v channels
    
        int offsetY = 0;
    
        int sizeY =  width * height * bytesPerPixel;
        int sizeUV = (width * height * bytesPerPixel) / 4;
    
        for (int row = 0; row < height ; row++) {
    
            // fill data for Y channel, two row
            {
                int length = bytesPerPixel * width;
                buffer0.get(data, offsetY, length);
    
                if ( height - row != 1)
                    buffer0.position(buffer0.position()  +  planes[0].getRowStride() - length);
    
                offsetY += length;
            }
    
            if (row >= height/2)
                continue;
    
            {
                int uvlength = planes[1].getRowStride();
    
                if ( (height / 2 - row) == 1 ) {
                    uvlength = width / 2 - planes[1].getPixelStride() + 1;
                }
    
                buffer1.get(rowData1, 0, uvlength);
                buffer2.get(rowData2, 0, uvlength);
    
                // fill data for u/v channels
                for (int col = 0; col < width / 2; ++col) {
                    // u channel
                    data[sizeY + (row * width)/2 + col] = rowData1[col * planes[1].getPixelStride()];
    
                    // v channel
                    data[sizeY + sizeUV + (row * width)/2 + col] = rowData2[col * planes[2].getPixelStride()];
                }
            }
    
        }
    
        return data;
    }



}
