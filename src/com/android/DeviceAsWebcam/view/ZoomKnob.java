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

package com.android.DeviceAsWebcam.view;

import static com.android.DeviceAsWebcam.view.ZoomController.convertZoomRatioToString;

import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.FrameLayout.LayoutParams;
import android.widget.SeekBar;

import androidx.appcompat.widget.AppCompatTextView;

import com.android.DeviceAsWebcam.R;

/** A knob that aims to provide the sliding gesture control on the SeekBar. */
public class ZoomKnob extends AppCompatTextView {
    private final Resources mResources;
    /**
     * The max zoom progress value.
     */
    private int mMaxZoomProgress;
    /**
     * The knob size expressed in pixels.
     */
    private final int mKnobSize;
    /**
     * The seek bar width expressed in pixels.
     */
    private final int mSeekBarWidth;
    /**
     * The knob's bottom margin expressed in pixels when it is not elevated.
     */
    private int mNoElevatedBottomMargin;

    public ZoomKnob(Context context, AttributeSet attrs) {
        super(context, attrs);
        mResources = context.getResources();
        mSeekBarWidth = mResources.getDimensionPixelSize(R.dimen.zoom_seekbar_width);
        mKnobSize = getResources().getDimensionPixelSize(R.dimen.zoom_knob_size);
    }

    void initialize(SeekBar zoomSeekBar, int maxZoomProgress) {
        mMaxZoomProgress = maxZoomProgress;
        float textSizePx = mResources.getDimensionPixelSize(R.dimen.zoom_knob_text_size);
        float textSizeSp = textSizePx / mResources.getDisplayMetrics().scaledDensity;

        int elevation = mResources.getDimensionPixelSize(R.dimen.zoom_knob_elevation);
        setElevation(mResources.getDimensionPixelSize(R.dimen.zoom_thumb_elevation));
        setGravity(Gravity.CENTER);
        setTextAlignment(TEXT_ALIGNMENT_CENTER);
        setTextSize(textSizeSp);

        int sliderHeight = zoomSeekBar.getLayoutParams().height;
        mNoElevatedBottomMargin = ((sliderHeight - mKnobSize) / 2) - elevation / 2;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        lp.bottomMargin = mNoElevatedBottomMargin;
        setLayoutParams(lp);

        zoomSeekBar.setSplitTrack(false);
    }

    /**
     * Update the zoom ratio text which shows on the knob.
     *
     * @param zoomProgress the progress on the SeekBar.
     * @param zoomRatio    the zoom ratio.
     */
    public void updateZoomProgress(int zoomProgress, float zoomRatio) {
        int halfSeekBarWidth = mSeekBarWidth / 2;
        FrameLayout.LayoutParams lp = (LayoutParams) getLayoutParams();
        int midProgress = mMaxZoomProgress / 2;
        int margin =
                (int)
                        (halfSeekBarWidth
                                * (float) (zoomProgress - midProgress)
                                / (mMaxZoomProgress / 2));
        lp.leftMargin = margin;
        lp.rightMargin = 0;
        setLayoutParams(lp);
        setText(convertZoomRatioToString(zoomRatio));
    }

    /** Elevate the knob above SeekBar. */
    public void setElevated(boolean elevated) {
        FrameLayout.LayoutParams lp = (LayoutParams) getLayoutParams();
        int liftDistance =
                mResources.getDimensionPixelSize(R.dimen.zoom_knob_lift)
                        + mResources.getDimensionPixelSize(R.dimen.zoom_icon_size) / 2
                        + mNoElevatedBottomMargin;
        lp.bottomMargin = elevated ? liftDistance : mNoElevatedBottomMargin;
        setLayoutParams(lp);
    }
}

