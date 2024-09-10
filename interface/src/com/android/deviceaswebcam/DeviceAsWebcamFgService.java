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

package com.android.deviceaswebcam;

import android.annotation.NonNull;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.HardwareBuffer;
import android.util.Log;
import android.util.Size;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.android.DeviceAsWebcam.R;
import com.android.deviceaswebcam.annotations.UsedByNative;
import com.android.deviceaswebcam.utils.IgnoredV4L2Nodes;

import java.util.Objects;

/**
 * Base abstract class which implements necessary foreground service functionality to talk to the
 * native layer and handles service lifecycle.
 */
public abstract class DeviceAsWebcamFgService extends Service {
    private static final String TAG = "DeviceAsWebcamFgService";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);
    private static final String NOTIF_CHANNEL_ID = "WebcamService";
    private static final int NOTIF_ID = 1;

    static {
        System.loadLibrary("jni_deviceAsWebcam");
    }

    // Guards all methods in the service to ensure a consistent state while executing a method
    private final Object mServiceLock = new Object();
    private Context mContext;
    private WebcamController mWebcamController;
    private boolean mServiceRunning = false;

    private NotificationCompat.Builder mNotificationBuilder;
    private int mNotificationIcon;
    private int mNextNotificationIcon;
    private boolean mNotificationUpdatePending;

    /**
     * Returns the concrete implementation of the WebcamController interface that handles webcam
     * functionality. This method will be called during the service's {@link #onStartCommand} and
     * instance returned by this method will be used until the service is stopped, i.e. when
     * {@link WebcamController#onDestroy} is called.
     *
     * @param context context to access service resources
     * @return The WebcamController that controls the basic webcam functionality.
     */
    protected abstract WebcamController getWebcamController(@NonNull Context context);

    /**
     * The Intent to start the preview activity. This Intent will be called when the user taps the
     * webcam notification.
     *
     * @param context context to access service resources
     * @return {@link Intent} to start the user facing activity.
     */
    protected abstract Intent getPreviewActivityIntent(@NonNull Context context);

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public final int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        synchronized (mServiceLock) {
            mContext = getApplicationContext();
            if (mContext == null) {
                Log.e(TAG, "Application context is null!, something is going to go wrong");
            }

            mWebcamController = getWebcamController(mContext);
            mWebcamController.registerServiceInstance(this);

            int res = setupServicesAndStartListening();
            startForegroundWithNotification();
            // If `setupServicesAndStartListening` fails, we don't want to start the foreground
            // service. However, Android expects a call to `startForegroundWithNotification` in
            // `onStartCommand` and throws an exception if it isn't called. So, if the foreground
            // service should not be running, we call `startForegroundWithNotification` which starts
            // the service, and immediately call `stopSelf` which causes the service to be
            // torn down once `onStartCommand` returns.
            if (res != 0) {
                stopSelf();
            }
            mServiceRunning = true;
            return START_NOT_STICKY;
        }
    }

    private String createNotificationChannel() {
        NotificationChannel channel =
                new NotificationChannel(
                        NOTIF_CHANNEL_ID,
                        getString(R.string.notif_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notMan = getSystemService(NotificationManager.class);
        Objects.requireNonNull(notMan).createNotificationChannel(channel);
        return NOTIF_CHANNEL_ID;
    }

    private void startForegroundWithNotification() {
        Intent notificationIntent = getPreviewActivityIntent(mContext);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        mContext, 0, notificationIntent, PendingIntent.FLAG_MUTABLE);
        String channelId = createNotificationChannel();
        mNextNotificationIcon = mNotificationIcon = R.drawable.ic_notif_line;
        mNotificationBuilder =
                new NotificationCompat.Builder(this, channelId)
                        .setCategory(Notification.CATEGORY_SERVICE)
                        .setContentIntent(pendingIntent)
                        .setContentText(getString(R.string.notif_desc))
                        .setContentTitle(getString(R.string.notif_title))
                        .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                        .setOngoing(true)
                        .setPriority(NotificationManager.IMPORTANCE_DEFAULT)
                        .setShowWhen(false)
                        .setSmallIcon(mNotificationIcon)
                        .setTicker(getString(R.string.notif_ticker))
                        .setVisibility(Notification.VISIBILITY_PUBLIC);
        Notification notif = mNotificationBuilder.build();
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
    }

    private int setupServicesAndStartListening() {
        String[] ignoredNodes = IgnoredV4L2Nodes.getIgnoredNodes(getApplicationContext());
        return setupServicesAndStartListeningNative(ignoredNodes);
    }

    @Override
    public void onDestroy() {
        synchronized (mServiceLock) {
            if (!mServiceRunning) {
                return;
            }
            mServiceRunning = false;
            if (mWebcamController != null) {
                mWebcamController.onDestroy();
            }
            nativeOnDestroy();
            if (VERBOSE) {
                Log.v(TAG, "Destroyed fg service");
            }
            // Ensure that the service notification is removed.
            NotificationManagerCompat.from(mContext).cancelAll();
        }
        super.onDestroy();
    }

    private void updateNotification(boolean isStreaming) {
        int transitionIcon; // animated icon
        int finalIcon; // static icon
        if (isStreaming) {
            transitionIcon = R.drawable.ic_notif_streaming;
            // last frame of ic_notif_streaming
            finalIcon = R.drawable.ic_notif_filled;
        } else {
            transitionIcon = R.drawable.ic_notif_idle;
            // last frame of ic_notif_idle
            finalIcon = R.drawable.ic_notif_line;
        }

        synchronized (mServiceLock) {
            if (finalIcon == mNotificationIcon) {
                // Notification already is desired state.
                return;
            }
            if (transitionIcon == mNotificationIcon) {
                // Notification currently animating to finalIcon.
                // Set next state to desired steady state icon.
                mNextNotificationIcon = finalIcon;
                return;
            }

            if (mNotificationUpdatePending) {
                // Notification animating to some other icon. Set the next icon to the new
                // transition icon and let the update runnable handle the actual updates.
                mNextNotificationIcon = transitionIcon;
                return;
            }

            // Notification is in a steady state. Update notification to the new icon.
            mNextNotificationIcon = transitionIcon;
            updateNotificationToNextIcon();
        }
    }

    private void updateNotificationToNextIcon() {
        synchronized (mServiceLock) {
            if (!mServiceRunning) {
                return;
            }

            mNotificationBuilder.setSmallIcon(mNextNotificationIcon);
            NotificationManagerCompat.from(mContext).notify(NOTIF_ID, mNotificationBuilder.build());
            mNotificationIcon = mNextNotificationIcon;

            boolean notifNeedsUpdate = false;
            if (mNotificationIcon == R.drawable.ic_notif_streaming) {
                // last frame of ic_notif_streaming
                mNextNotificationIcon = R.drawable.ic_notif_filled;
                notifNeedsUpdate = true;
            } else if (mNotificationIcon == R.drawable.ic_notif_idle) {
                // last frame of ic_notif_idle
                mNextNotificationIcon = R.drawable.ic_notif_line;
                notifNeedsUpdate = true;
            }
            mNotificationUpdatePending = notifNeedsUpdate;
            if (notifNeedsUpdate) {
                // Run this method again after 500ms to update the notification to steady
                // state icon
                getMainThreadHandler().postDelayed(this::updateNotificationToNextIcon, 500);
            }
        }
    }

    @UsedByNative("DeviceAsWebcamNative.cpp")
    private void startStreaming() {
        synchronized (mServiceLock) {
            if (!mServiceRunning) {
                Log.e(TAG, "startStreaming was called after Service was destroyed");
                return;
            }
            mWebcamController.startStream();
            updateNotification(/*isStreaming*/ true);
        }
    }

    @UsedByNative("DeviceAsWebcamNative.cpp")
    private void stopService() {
        synchronized (mServiceLock) {
            if (!mServiceRunning) {
                Log.e(TAG, "stopService was called after Service was destroyed");
                return;
            }
            stopSelf();
        }
    }

    @UsedByNative("DeviceAsWebcamNative.cpp")
    private void stopStreaming() {
        synchronized (mServiceLock) {
            if (!mServiceRunning) {
                Log.e(TAG, "stopStreaming was called after Service was destroyed");
                return;
            }
            mWebcamController.stopStream();
            updateNotification(/*isStreaming*/ false);
        }
    }

    @UsedByNative("DeviceAsWebcamNative.cpp")
    private void returnImage(long timestamp) {
        synchronized (mServiceLock) {
            if (!mServiceRunning) {
                Log.e(TAG, "returnImage was called after Service was destroyed");
                return;
            }
            mWebcamController.onImageReturned(timestamp);
        }
    }

    @UsedByNative("DeviceAsWebcamNative.cpp")
    private void setStreamConfig(boolean mjpeg, int width, int height, int fps) {
        synchronized (mServiceLock) {
            if (!mServiceRunning) {
                Log.e(TAG, "setStreamConfig was called after Service was destroyed");
                return;
            }
            mWebcamController.setStreamConfig(new Size(width, height), fps);
        }
    }

    /**
     * Called by {@link DeviceAsWebcamReceiver} to check if the service should be started.
     *
     * @param ignoredNodes V4L2 nodes to ignore
     * @return {@code true} if the foreground service should be started, {@code false} if the
     *     service is already running or should not be started
     */
    public static native boolean shouldStartServiceNative(String[] ignoredNodes);

    /**
     * Called during {@link #onStartCommand} to initialize the native side of the service.
     *
     * @param ignoredNodes V4L2 nodes to ignore
     * @return 0 if native side code was successfully initialized, non-0 otherwise
     */
    private native int setupServicesAndStartListeningNative(String[] ignoredNodes);

    /**
     * Called by {@link CameraController} to queue frames for encoding. The frames are encoded
     * asynchronously. When encoding is done, the native code call {@link #returnImage} with the
     * {@code timestamp} passed here.
     *
     * @param buffer buffer containing the frame to be encoded
     * @param timestamp timestamp associated with the buffer which uniquely identifies the buffer
     * @return 0 if buffer was successfully queued for encoding. non-0 otherwise.
     */
    public native int nativeEncodeImage(HardwareBuffer buffer, long timestamp, int rotation);

    /**
     * Called by {@link #onDestroy} to give the JNI code a chance to clean up before the service
     * goes out of scope.
     */
    private native void nativeOnDestroy();
}
