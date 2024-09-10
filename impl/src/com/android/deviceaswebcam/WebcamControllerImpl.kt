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

package com.android.deviceaswebcam

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Size
import com.android.deviceaswebcam.CameraController.RotationUpdateListener
import java.util.function.Consumer

class WebcamControllerImpl(context: Context) : WebcamController() {
    private val mLock = Object()

    private val mCameraController = CameraController(context, /* webcamController= */ this)
    private var mDestroyActivityCallback: Runnable? = null

    override fun setStreamConfig(size: Size, frameRate: Int) {
        synchronized(mLock) {
            mCameraController.setWebcamStreamConfig(size.width, size.height, frameRate)
        }
    }

    override fun startStream() {
        synchronized(mLock) { mCameraController.startWebcamStreaming() }
    }

    override fun stopStream() {
        synchronized(mLock) { mCameraController.stopWebcamStreaming() }
    }

    override fun onImageReturned(token: Long) {
        synchronized(mLock) { mCameraController.returnImage(token) }
    }

    override fun onDestroy() {
        synchronized(mLock) { mDestroyActivityCallback?.run() }
    }

    /**
     * Method to set a preview surface texture that camera will stream to. Should be of the size
     * returned by [.getSuitablePreviewSize].
     *
     * @param surfaceTexture surfaceTexture to stream preview frames to
     * @param previewSize the preview size
     * @param previewSizeChangeListener a listener to monitor the preview size change events.
     */
    fun setPreviewSurfaceTexture(
        surfaceTexture: SurfaceTexture,
        previewSize: Size,
        previewSizeChangeListener: Consumer<Size>?
    ) {
        synchronized(mLock) {
            mCameraController.startPreviewStreaming(
                surfaceTexture,
                previewSize,
                previewSizeChangeListener
            )
        }
    }

    /** Returns the available [CameraId] list. */
    fun getAvailableCameraIds(): List<CameraId> {
        synchronized(mLock) {
            return mCameraController.availableCameraIds
        }
    }

    /** Returns current rotation degrees value. */
    fun getCurrentRotation(): Int {
        synchronized(mLock) {
            return mCameraController.currentRotation
        }
    }

    /** Sets a [CameraController.RotationUpdateListener] to monitor the device rotation changes. */
    fun setRotationUpdateListener(listener: RotationUpdateListener?) {
        synchronized(mLock) { mCameraController.setRotationUpdateListener(listener) }
    }

    /** Method to remove any preview SurfaceTexture set by [.setPreviewSurfaceTexture]. */
    fun removePreviewSurfaceTexture() {
        synchronized(mLock) { mCameraController.stopPreviewStreaming() }
    }

    /** Sets the new zoom ratio setting to the working camera. */
    fun setZoomRatio(zoomRatio: Float) {
        synchronized(mLock) { mCameraController.zoomRatio = zoomRatio }
    }

    /** Returns the [CameraInfo] of the working camera. */
    fun getCameraInfo(): CameraInfo? {
        synchronized(mLock) {
            return mCameraController.cameraInfo
        }
    }

    /**
     * Retrieves current tap-to-focus points.
     *
     * @return the normalized points or `null` if it is auto-focus mode currently.
     */
    fun getTapToFocusPoints(): FloatArray? {
        synchronized(mLock) {
            return mCameraController.tapToFocusPoints
        }
    }

    /** Returns true if high quality mode is enabled, false otherwise */
    fun isHighQualityModeEnabled(): Boolean {
        synchronized(mLock) {
            return mCameraController.isHighQualityModeEnabled
        }
    }

    /**
     * Enables/Disables high quality mode. See [CameraController.setHighQualityModeEnabled] for more
     * info.
     */
    fun setHighQualityModeEnabled(enabled: Boolean, callback: Runnable) {
        synchronized(mLock) { mCameraController.setHighQualityModeEnabled(enabled, callback) }
    }

    /**
     * Returns the best suitable output size for preview.
     *
     * If the webcam stream doesn't exist, find the largest 16:9 supported output size which is not
     * larger than 1080p. If the webcam stream exists, find the largest supported output size which
     * matches the aspect ratio of the webcam stream size and is not larger than the webcam stream
     * size.
     */
    fun getSuitablePreviewSize(): Size? {
        synchronized(mLock) {
            return mCameraController.suitablePreviewSize
        }
    }

    /** Returns current zoom ratio setting. */
    fun getZoomRatio(): Float {
        synchronized(mLock) {
            return mCameraController.zoomRatio
        }
    }

    /**
     * Method to setOnDestroyedCallback. This callback will be called when immediately before the
     * foreground service is destroyed. Intended to give and bound context a change to clean up
     * before the Service is destroyed. `setOnDestroyedCallback(null)` must be called to unset the
     * callback when a bound context finishes to prevent Context leak.
     *
     * This callback must not call `setOnDestroyedCallback` from within the callback.
     *
     * @param callback callback to be called when the service is destroyed. `null` unsets the
     *   callback
     */
    fun setOnDestroyedCallback(callback: Runnable?) {
        synchronized(mLock) { mDestroyActivityCallback = callback }
    }

    /** Returns the [CameraInfo] for the specified camera id. */
    fun getOrCreateCameraInfo(cameraId: CameraId): CameraInfo? {
        synchronized(mLock) {
            return mCameraController.getOrCreateCameraInfo(cameraId)
        }
    }

    /** Toggles camera between the back and front cameras. */
    fun toggleCamera() {
        synchronized(mLock) { mCameraController.toggleCamera() }
    }

    /** Switches current working camera to specific one. */
    fun switchCamera(cameraId: CameraId) {
        synchronized(mLock) { mCameraController.switchCamera(cameraId) }
    }

    /** Resets to the auto-focus mode. */
    fun resetToAutoFocus() {
        synchronized(mLock) { mCameraController.resetToAutoFocus() }
    }

    /**
     * Trigger tap-to-focus operation for the specified normalized points mapping to the FOV.
     *
     * The specified normalized points will be used to calculate the corresponding metering
     * rectangles that will be applied for AF, AE and AWB.
     */
    fun tapToFocus(normalizedPoint: FloatArray?) {
        synchronized(mLock) { mCameraController.tapToFocus(normalizedPoint) }
    }

    companion object {
        private const val TAG = "WebcamControllerImpl"
    }
}
