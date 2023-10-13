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

import android.annotation.IntRange;
import android.content.Context;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.OrientationEventListener;
import android.view.Surface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provider for receiving rotation updates from the {@link SensorManager} when the rotation of
 * the device has changed.
 *
 * <p> This class monitors motion sensor and notifies the listener about physical orientation
 * changes in the rotation degrees value which can be used to rotate the stream images to the
 * upright orientation.
 *
 * <pre><code>
 * // Create a provider.
 * RotationProvider mRotationProvider = new RotationProvider(getApplicationContext());
 *
 * // Add listener to receive updates.
 * mRotationProvider.addListener(rotation -> {
 *     // Apply the rotation values to the related targets
 * });
 *
 * // Remove when no longer needed.
 * mRotationProvider.clearListener();
 * </code></pre>
 */
public final class RotationProvider {

    private final Object mLock = new Object();
    private final OrientationEventListener mOrientationListener;
    private final Map<Listener, ListenerWrapper> mListeners = new HashMap<>();
    private int mRotation;
    private int mSensorOrientation;

    /**
     * Creates a new RotationProvider.
     *
     * @param applicationContext the application context used to register
     * {@link OrientationEventListener} or get display rotation.
     * @param sensorOrientation the camera sensor orientation value
     */
    public RotationProvider(Context applicationContext, int sensorOrientation) {
        int displayRotation = applicationContext.getSystemService(DisplayManager.class).getDisplay(
                Display.DEFAULT_DISPLAY).getRotation();
        mRotation = displayRotation == Surface.ROTATION_270 ? 180 : 0;
        mSensorOrientation = sensorOrientation;
        mOrientationListener = new OrientationEventListener(applicationContext) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
                    // Short-circuit if orientation is unknown. Unknown rotation
                    // can't be handled so it shouldn't be sent.
                    return;
                }

                int newRotation = sensorOrientationToRotationDegrees(orientation);
                int originalRotation;
                List<ListenerWrapper> listeners = new ArrayList<>();
                // Take a snapshot for thread safety.
                synchronized (mLock) {
                    originalRotation = mRotation;
                    if (mRotation != newRotation) {
                        mRotation = newRotation;
                        listeners.addAll(mListeners.values());
                    }
                }

                if (originalRotation != newRotation) {
                    if (!listeners.isEmpty()) {
                        for (ListenerWrapper listenerWrapper : listeners) {
                            listenerWrapper.onRotationChanged(newRotation);
                        }
                    }
                }
            }
        };
    }

    public int getRotation() {
        synchronized (mLock) {
            return mRotation;
        }
    }

    /**
     * Sets a {@link Listener} that listens for rotation changes.
     *
     * @param executor The executor in which the {@link Listener#onRotationChanged(int)} will be
     *                 run.
     * @return false if the device cannot detection rotation changes. In that case, the listener
     * will not be set.
     */
    public boolean addListener(Executor executor, Listener listener) {
        synchronized (mLock) {
            if (!mOrientationListener.canDetectOrientation()) {
                return false;
            }
            mListeners.put(listener, new ListenerWrapper(listener, executor));
            mOrientationListener.enable();
        }
        return true;
    }

    /**
     * Removes the given {@link Listener} from this object.
     *
     * <p> The removed listener will no longer receive rotation updates.
     */
    public void removeListener(Listener listener) {
        synchronized (mLock) {
            ListenerWrapper listenerWrapper = mListeners.get(listener);
            if (listenerWrapper != null) {
                listenerWrapper.disable();
                mListeners.remove(listener);
            }
            if (mListeners.isEmpty()) {
                mOrientationListener.disable();
            }
        }
    }

    /**
     * Converts sensor orientation degrees to the image rotation degrees.
     *
     * <p>Currently, the returned value can only be 0 or 180 because DeviceAsWebcam only support
     * in the landscape mode. The webcam stream images will be rotated to upright orientation when
     * the device is in the landscape orientation.
     */
    private int sensorOrientationToRotationDegrees(@IntRange(from = 0, to = 359) int orientation) {
        if ((mSensorOrientation % 180 == 90 && orientation >= 45 && orientation < 135) || (
                mSensorOrientation % 180 == 0 && orientation >= 135 && orientation < 225)) {
            return 180;
        } else {
            return 0;
        }
    }

    /**
     * Wrapper of {@link Listener} with the executor and a tombstone flag.
     */
    private static class ListenerWrapper {
        private final Listener mListener;
        private final Executor mExecutor;
        private final AtomicBoolean mEnabled;

        ListenerWrapper(Listener listener, Executor executor) {
            mListener = listener;
            mExecutor = executor;
            mEnabled = new AtomicBoolean(true);
        }

        void onRotationChanged(int rotation) {
            mExecutor.execute(() -> {
                if (mEnabled.get()) {
                    mListener.onRotationChanged(rotation);
                }
            });
        }

        /**
         * Once disabled, the app will not receive callback even if it has already been posted on
         * the callback thread.
         */
        void disable() {
            mEnabled.set(false);
        }
    }

    /**
     * Callback interface to receive rotation updates.
     */
    public interface Listener {

        /**
         * Called when the physical rotation of the device changes to cause the corresponding
         * rotation value is changed.
         *
         * <p>Currently, the returned value can only be 0 or 180 because DeviceAsWebcam only
         * support in the landscape mode. The webcam stream images will be rotated to upright
         * orientation when the device is in the landscape orientation.
         */
        void onRotationChanged(int rotation);
    }
}

