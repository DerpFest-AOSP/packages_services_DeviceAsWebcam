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
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * Concrete implementation of [DeviceAsWebcamFgService] which uses [WebcamControllerImpl] for camera
 * controls, and [DeviceAsWebcamPreview] for the user facing activity.
 */
class DeviceAsWebcamFgServiceImpl : DeviceAsWebcamFgService() {
    private val mBinder: IBinder = LocalBinder()
    lateinit var mWebcamController: WebcamControllerImpl

    override fun getWebcamController(context: Context): WebcamController {
        if (!::mWebcamController.isInitialized) {
            mWebcamController = WebcamControllerImpl(context)
        }
        return mWebcamController
    }

    override fun getPreviewActivityIntent(context: Context): Intent {
        return Intent(context, DeviceAsWebcamPreview::class.java)
    }

    override fun onBind(intent: Intent?): IBinder {
        return mBinder
    }

    /**
     * Returns the backing [WebcamControllerImpl] to allow [DeviceAsWebcamPreview] to interact with
     * the camera controls.
     */
    fun getWebcamControllerImpl(): WebcamControllerImpl {
        return mWebcamController
    }

    /**
     * Simple class to hold a reference to [DeviceAsWebcamFgService] instance and have it be
     * accessible from [android.content.ServiceConnection.onServiceConnected] callback.
     */
    inner class LocalBinder : Binder() {
        val service: DeviceAsWebcamFgServiceImpl
            get() = this@DeviceAsWebcamFgServiceImpl
    }
}
