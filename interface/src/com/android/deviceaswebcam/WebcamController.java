/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.deviceaswebcam;

import android.hardware.HardwareBuffer;
import android.media.Image;
import android.util.Log;
import android.util.Size;

import java.lang.ref.WeakReference;

/** Primary interface for calls between the host and webcam controls. */
public abstract class WebcamController {
    private static final String TAG = WebcamController.class.getSimpleName();

    private WeakReference<DeviceAsWebcamFgService> mServiceRef = null;

    /**
     * Called when the host selects a stream configuration. This is considered inviolable, not
     * honoring the size and frame rate is an error.
     *
     * @param size resolution of the video frame
     * @param frameRate framerate requested by the host. Webcam does not support variable framerate.
     */
    public abstract void setStreamConfig(Size size, int frameRate);

    /** Called when the host wants the webcam to start sending it frames. */
    public abstract void startStream();

    /** Called when the host wants to webcam to stop sending it frames. */
    public abstract void stopStream();

    /**
     * Method to be used to send camera frames from Java implementation to the host. This method
     * (optionally) rotates the frames 180 degrees, encodes the frame to the format expected by the
     * host, and queues the encoded frames to be sent to the host.
     *
     * <p>The passed HardwareBuffer must remain valid until {@link #onImageReturned} is called with
     * the corresponding token. For example, if {@code image} is fetched from {@link
     * Image#getHardwareBuffer()}, {@link Image#close()} must not be called until {@code image} is
     * returned with {@link #onImageReturned}.
     *
     * <p>NOTE: This method _must not_ be overridden.
     *
     * @param image Camera Frame to be sent to the host
     * @param token unique token that can be used to identify the corresponding image
     * @return {@code true} if the image was successfully queued to the host, \ {@code false}
     *     otherwise. {@link #onImageReturned} will only be called for the {@code image} if {@code
     *     true} is returned.
     */
    public final boolean queueImageToHost(
            HardwareBuffer image, long token, boolean rotate180Degrees) {
        DeviceAsWebcamFgService service = mServiceRef.get();
        if (service == null) {
            Log.e(TAG, "queueImageToHost called but service has already been garbage collected?");
            return false;
        }

        return service.nativeEncodeImage(image, token, rotate180Degrees ? 180 : 0) == 0;
    }

    /**
     * Internal method used by the {@link DeviceAsWebcamFgService} allow {@link #queueImageToHost}
     * to call the native implementation.
     */
    final void registerServiceInstance(DeviceAsWebcamFgService service) {
        mServiceRef = new WeakReference<>(service);
    }

    /**
     * Called every time a frame is encoded and queued to the host. The encoded frame's token (as
     * specified {@link #queueImageToHost}) is passed as the parameter.
     *
     * @param token token corresponding to the returned Image.
     */
    public abstract void onImageReturned(long token);

    /**
     * Called when the service is being destroyed. This typically happens when the host has been
     * disconnected, or the Android system is killing the service. This may happen even if {@link
     * #stopStream} was not called.
     *
     * <p>This should close/finish any user facing views or resources such as the PreviewActivity.
     */
    public abstract void onDestroy();
}
