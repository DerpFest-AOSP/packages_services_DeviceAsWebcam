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

import android.util.Range;

import java.util.List;

/**
 * A class for providing camera related information.
 */
public class CameraInfo {
    private final int mLensFacing;
    private final int mSensorOrientation;
    private final Range<Float> mZoomRatioRange;
    private final List<VendorCameraPrefs.PhysicalCameraInfo> mPhysicalCameraInfos;
    public CameraInfo(int lensFacing, int sensorOrientation, Range<Float> zoomRatioRange,
            List<VendorCameraPrefs.PhysicalCameraInfo> physicalInfos) {
        mLensFacing = lensFacing;
        mSensorOrientation = sensorOrientation;
        mZoomRatioRange = zoomRatioRange;
        mPhysicalCameraInfos = physicalInfos;
    }

    /**
     * Returns lens facing.
     */
    public int getLensFacing() {
        return mLensFacing;
    }

    /**
     * Returns sensor orientation characteristics value.
     */
    public int getSensorOrientation() {
        return mSensorOrientation;
    }

    /**
     * Returns zoom ratio range characteristics value.
     */
    public Range<Float> getZoomRatioRange() {
        return mZoomRatioRange;
    }

    public List<VendorCameraPrefs.PhysicalCameraInfo> getPhysicalCameraInfos() {
        return mPhysicalCameraInfos;
    }
}
