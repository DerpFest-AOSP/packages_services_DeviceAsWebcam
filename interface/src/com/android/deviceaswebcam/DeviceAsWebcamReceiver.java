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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;

import com.android.deviceaswebcam.utils.IgnoredV4L2Nodes;

/**
 * Base abstract class that receives USB broadcasts from system server and starts the webcam
 * foreground service when needed.
 */
public abstract class DeviceAsWebcamReceiver extends BroadcastReceiver {
    private static final String TAG = DeviceAsWebcamReceiver.class.getSimpleName();
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    static {
        System.loadLibrary("jni_deviceAsWebcam");
    }

    @Override
    public final void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        Bundle extras = intent.getExtras();
        boolean uvcSelected = extras.getBoolean(UsbManager.USB_FUNCTION_UVC);
        if (VERBOSE) {
            Log.v(TAG, "Got broadcast with extras" + extras);
        }
        if (!UsbManager.isUvcSupportEnabled()) {
            Log.e(TAG, "UVC support isn't enabled, why do we have DeviceAsWebcam installed ?");
            return;
        }
        if (UsbManager.ACTION_USB_STATE.equals(action) && uvcSelected) {
            String[] ignoredNodes = IgnoredV4L2Nodes.getIgnoredNodes(context);
            if (!DeviceAsWebcamFgService.shouldStartServiceNative(ignoredNodes)) {
                if (VERBOSE) {
                    Log.v(TAG, "Shouldn't start service so returning");
                }
                return;
            }
            Class<? extends DeviceAsWebcamFgService> klass = getForegroundServiceClass();
            Intent fgIntent = new Intent(context, klass);
            context.startForegroundService(fgIntent);
            if (VERBOSE) {
                Log.v(TAG, "started foreground service");
            }
        }
    }

    /**
     * Return the concrete class for the foreground service.
     *
     * @return class that has the concrete implementation of {@link DeviceAsWebcamFgService}
     */
    protected abstract Class<? extends DeviceAsWebcamFgService> getForegroundServiceClass();
}
