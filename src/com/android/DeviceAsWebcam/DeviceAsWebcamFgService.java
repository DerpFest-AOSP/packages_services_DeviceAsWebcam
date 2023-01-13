/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.DeviceAsWebcam;

import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.HardwareBuffer;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.util.Size;

import androidx.core.app.NotificationCompat;

import com.android.DeviceAsWebcam.annotations.UsedByNative;

import java.lang.ref.WeakReference;
import java.util.Objects;

public class DeviceAsWebcamFgService extends Service {
    private static final String TAG = "DeviceAsWebcamFgService";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    static {
        System.loadLibrary("jni_deviceAsWebcam");
    }

    private final IBinder mBinder = new LocalBinder();
    private Context mContext;
    private CameraController mCameraController;
    private Runnable mDestroyActivityCallback = null;
    private boolean mDestroyCallbackCalled = false;

    private static native int setupServicesAndStartListeningNative(Object selfRef);

    @UsedByNative("SdkFrameProvider.cpp")
    private static void setStreamConfig(Object selfRef, boolean mjpeg, int width, int height,
            int fps) {
        DeviceAsWebcamFgService fgService = getStrongFgService(selfRef);
        if (fgService == null) {
            Log.e(TAG, "FG service is dead, returning");
            return;
        }
        fgService.setStreamConfig(mjpeg, width, height, fps);
    }

    @UsedByNative("SdkFrameProvider.cpp")
    private static void startStreaming(Object selfRef) {
        DeviceAsWebcamFgService fgService = getStrongFgService(selfRef);
        if (fgService == null) {
            Log.e(TAG, "FG service is dead, returning");
            return;
        }
        fgService.startWebcamStreaming();
    }

    @UsedByNative("SdkFrameProvider.cpp")
    private static void stopService(Object selfRef) {
        DeviceAsWebcamFgService fgService = getStrongFgService(selfRef);
        if (fgService == null) {
            Log.e(TAG, "FG service is dead, returning");
            return;
        }
        fgService.stopServiceSelf();
    }

    // Be careful while using this, assumes selfRef is actually a weak ref of type
    // DeviceAsWebcamFgService
    private static DeviceAsWebcamFgService getStrongFgService(Object selfRef) {
        WeakReference<DeviceAsWebcamFgService> weakR =
                (WeakReference<DeviceAsWebcamFgService>) selfRef;
        return weakR.get();
    }

    @UsedByNative("SdkFrameProvider.cpp")
    private static void stopStreaming(Object selfRef) {
        DeviceAsWebcamFgService fgService = getStrongFgService(selfRef);
        if (fgService == null) {
            Log.e(TAG, "FG service is dead, returning");
            return;
        }
        fgService.stopWebcamStreaming();
    }

    @UsedByNative("SdkFrameProvider.cpp")
    private static void returnImage(Object selfRef, long timestamp) {
        DeviceAsWebcamFgService fgService = getStrongFgService(selfRef);
        if (fgService == null) {
            Log.e(TAG, "FG service is dead, returning");
            return;
        }
        fgService.mCameraController.returnImage(timestamp);
    }

    // TODO(b/267794640): Make this non-static, and clean up the JNI surface.
    public static native int nativeEncodeImage(HardwareBuffer buffer, long ts);

    public native void stopServiceNative();

    private int setupServicesAndStartListening() {
        return setupServicesAndStartListeningNative(new WeakReference<>(this));
    }

    /**
     * Method to setOnDestroyedCallback. This callback will be called when immediately before the
     * foreground service is destroyed. Intended to give and bound context a change to clean up
     * before the Service is destroyed. {@code setOnDestroyedCallback(null)} must be called to unset
     * the callback when a bound context finishes to prevent Context leak.
     *
     * @param callback callback to be called when the service is destroyed. {@code null} unsets
     *                 the callback
     */
    public void setOnDestroyedCallback(@Nullable Runnable callback) {
        mDestroyActivityCallback = callback;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        if (mDestroyCallbackCalled && mDestroyActivityCallback != null) {
            mDestroyActivityCallback.run();
            mDestroyCallbackCalled = true;
        }
        stopServiceNative();
        if (VERBOSE) {
            Log.v(TAG, "Destroyed fg service");
        }
        super.onDestroy();
    }

    private void startForegroundWithNotification() {
        Intent notificationIntent = new Intent(mContext, DeviceAsWebcamPreview.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, notificationIntent,
                PendingIntent.FLAG_MUTABLE);
        String channelId = createNotificationChannel();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId);
        Notification n = builder.setOngoing(true).setPriority(
                Notification.PRIORITY_DEFAULT).setCategory(
                Notification.CATEGORY_SERVICE).setContentIntent(pendingIntent).setSmallIcon(
                R.drawable.ic_root_webcam).build();
        startForeground(/*in*/1, n);
    }

    private String createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel("WebcamService",
                "DeviceAsWebcamServiceFg", NotificationManager.IMPORTANCE_LOW);
        NotificationManager notMan = getSystemService(NotificationManager.class);
        Objects.requireNonNull(notMan).createNotificationChannel(channel);
        return "WebcamService";
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        mContext = getApplicationContext();
        if (mContext == null) {
            Log.e(TAG, "Application context is null!, something is going to go wrong");
        }
        mCameraController = new CameraController(mContext, new WeakReference<>(this));
        int res = setupServicesAndStartListening();
        startForegroundWithNotification();
        // If `setupServiceAndStartListening` fails, we don't want to start the foreground
        // service. However, Android expects a call to `startForegroundWithNotification` in
        // `onStartCommand` and throws an exception if it isn't called. So, if the foreground
        // service should not be running, we call `startForegroundWithNotification` which starts
        // the service, and immediately call `stopSelf` which causes the service to be
        // torn down once `onStartCommand` returns.
        if (res != 0) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void setStreamConfig(boolean mjpeg, int width, int height, int fps) {
        mCameraController.setWebcamStreamConfig(mjpeg, width, height, fps);
    }

    private void startWebcamStreaming() {
        mCameraController.startWebcamStreaming();
    }

    private void stopServiceSelf() {
        if (mDestroyActivityCallback != null) {
            mDestroyActivityCallback.run();
            mDestroyCallbackCalled = true;
        }
        stopSelf();
    }

    private void stopWebcamStreaming() {
        mCameraController.stopWebcamStreaming();
    }


    /**
     * Returns a suitable preview size <= the maxPreviewSize so there is no FoV change between
     * webcam and preview streams
     * TODO(b/267794640): Make this dynamic
     *
     * @param maxPreviewSize The upper limit of preview size
     */
    public Size getSuitablePreviewSize(Size maxPreviewSize) {
        return new Size(1920, 1080);
    }

    /**
     * Method to set a preview surface texture that camera will stream to. Should be of the size
     * returned by {@link #getSuitablePreviewSize}.
     *
     * @param surfaceTexture surfaceTexture to stream preview frames to
     */
    public void setPreviewSurfaceTexture(SurfaceTexture surfaceTexture) {
        mCameraController.startPreviewStreaming(surfaceTexture);
    }

    /**
     * Method to remove any preview SurfaceTexture set by {@link #setPreviewSurfaceTexture}.
     */
    public void removePreviewSurfaceTexture() {
        mCameraController.stopPreviewStreaming();
    }

    /**
     * Simple class to hold a reference to {@link DeviceAsWebcamFgService} instance and have it be
     * accessible from {@link android.content.ServiceConnection#onServiceConnected} callback.
     */
    public class LocalBinder extends Binder {
        DeviceAsWebcamFgService getService() {
            return DeviceAsWebcamFgService.this;
        }
    }
}
