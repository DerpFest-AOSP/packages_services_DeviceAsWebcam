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

package com.android.deviceaswebcam.utils;

import android.content.Context;
import android.util.JsonReader;
import android.util.Log;

import com.android.DeviceAsWebcam.R;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Class to parse and store the V4L2 nodes that must be ignored when looking for V4L2 node mounted
 * by UVC Gadget Driver. These can be configured using RROs. More information on how to use RROs at
 * {@code impl/res/raw/README.md}
 */
public class IgnoredV4L2Nodes {
    private static final String TAG = IgnoredV4L2Nodes.class.getSimpleName();
    private static final String V4L2_NODE_PREFIX = "/dev/video";

    private static String[] sIgnoredNodes = null;

    /**
     * Returns the list of V4L2 nodes that must be ignored when looking for UVC Gadget Driver's V4L2
     * node.
     *
     * @param context context to access service resources with.
     * @return Array of nodes to ignore. Ex. ["/dev/video33", "/dev/video44", ...]
     */
    public static synchronized String[] getIgnoredNodes(Context context) {
        if (sIgnoredNodes != null) {
            return sIgnoredNodes;
        }

        List<String> ignoredNodes = new ArrayList<>();
        try (InputStream in = context.getResources().openRawResource(R.raw.ignored_v4l2_nodes);
                JsonReader jsonReader = new JsonReader(new InputStreamReader(in))) {
            jsonReader.beginArray();
            while (jsonReader.hasNext()) {
                String node = jsonReader.nextString();
                // Don't track nodes that won't be in our search anyway.
                if (node.startsWith(V4L2_NODE_PREFIX)) {
                    ignoredNodes.add(node);
                }
            }
            jsonReader.endArray();
        } catch (IOException e) {
            Log.e(TAG, "Failed to parse JSON. Running with a partial ignored list", e);
        }

        sIgnoredNodes = ignoredNodes.toArray(new String[0]);
        return sIgnoredNodes;
    }
}
