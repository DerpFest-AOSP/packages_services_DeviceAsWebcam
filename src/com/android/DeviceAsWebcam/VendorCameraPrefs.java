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

import android.content.Context;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Range;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A class for providing camera related information overridden by vendors through resource overlays.
 */
public class VendorCameraPrefs {
    private static final String TAG = "VendorCameraPrefs";

    public static class PhysicalCameraInfo {
        public final String physicalCameraId;
        // String label which might help UI labelling while cycling through camera ids.
        public final String label;
        PhysicalCameraInfo(String physicalCameraIdI, String labelI) {
            physicalCameraId = physicalCameraIdI;
            label = labelI;
        }
    }

    public VendorCameraPrefs(ArrayMap<String, List<PhysicalCameraInfo>> logicalToPhysicalMap) {
        mLogicalToPhysicalMap = logicalToPhysicalMap;
    }

    public List<PhysicalCameraInfo> getPhysicalCameraInfos(String cameraId) {
        return mLogicalToPhysicalMap.get(cameraId);
    }

    // logical camera -> PhysicalCameraInfo. The list of PhysicalCameraInfos
    // is in order of preference for the physical streams that must be used by
    // DeviceAsWebcam service.
    private ArrayMap<String, List<PhysicalCameraInfo>> mLogicalToPhysicalMap;

    /**
     * Converts an InputStream into a String
     *
     * @param in InputStream
     * @return InputStream converted to a String
     */
    private static String inputStreamToString(InputStream in) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.lines().forEach(builder::append);
        }
        return builder.toString();
    }

    /**
     * Reads the map of physical camera ids from the input which is expected to be a valid
     * JSON stream.
     *
     * @param context Application context which can be used to retrieve resources.
     */
    public static VendorCameraPrefs getVendorCameraPrefsFromJson(Context context) {
        InputStream in = context.getResources().openRawResource(R.raw.physical_camera_mapping);
        ArrayMap<String, List<PhysicalCameraInfo>> logicalToPhysicalMap = new ArrayMap<>();
        try {
          JSONObject physicalCameraMapping = new JSONObject(inputStreamToString(in));
          for (String logCam : physicalCameraMapping.keySet()) {
              JSONObject physicalCameraObj = physicalCameraMapping.getJSONObject(logCam);
              List<PhysicalCameraInfo> physicalCameraIds = new ArrayList<>();
              for (String physCam : physicalCameraObj.keySet()) {
                  physicalCameraIds.add(
                          new PhysicalCameraInfo(physCam, physicalCameraObj.getString(physCam)));
              }
              logicalToPhysicalMap.put(logCam, physicalCameraIds);
          }
        } catch (JSONException | IOException e) {
            Log.e(TAG, "Failed to parse JSON", e);
        }
        return new VendorCameraPrefs(logicalToPhysicalMap);
    }
}
