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

import android.animation.ObjectAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Range;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Preconditions;

import com.android.DeviceAsWebcam.R;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

/**
 * A custom zoom controller to allow users to adjust their preferred zoom ratio setting.
 */
public class ZoomController extends FrameLayout {

    /**
     * Zoom UI toggle mode.
     */
    public static final int ZOOM_UI_TOGGLE_MODE = 0;
    /**
     * Zoom UI seek bar mode.
     */
    public static final int ZOOM_UI_SEEK_BAR_MODE = 1;
    /**
     * The max zoom progress of the controller.
     */
    private static final int MAX_ZOOM_PROGRESS = 100000;
    /**
     * The toggle UI auto-show duration in ms.
     */
    private static final int TOGGLE_UI_AUTO_SHOW_DURATION_MS = 1000;
    /**
     * The invalid x position used when translating the motion events to the seek bar progress.
     */
    private static final float INVALID_X_POSITION = -1.0f;
    /**
     * Current zoom UI mode.
     */
    private int mZoomUiMode = ZOOM_UI_TOGGLE_MODE;
    private View mToggleUiOptions;
    private View mToggleUiBackground;
    private View mToggleButtonSelected;
    private SeekBar mSeekBar;
    private ZoomKnob mZoomKnob;
    private View mToggleOptionLowSideSpace;
    /**
     * TextView of the low sticky zoom ratio value option item.
     */
    private TextView mToggleOptionLow;
    /**
     * TextView of the middle sticky zoom ratio value option item.
     */
    private TextView mToggleOptionMiddle;
    /**
     * TextView of the high sticky zoom ratio value option item.
     */
    private TextView mToggleOptionHigh;
    /**
     * Default low sticky zoom ratio value.
     */
    private float mDefaultLowStickyZoomRatio;
    /**
     * Default middle sticky zoom ratio value.
     */
    private float mDefaultMiddleStickyZoomRatio = 1.0f;
    /**
     * Default high sticky zoom ratio value.
     */
    private float mDefaultHighStickyZoomRatio;
    /**
     * Current low sticky zoom ratio value.
     */
    private float mCurrentLowStickyZoomRatio;
    /**
     * Current middle sticky zoom ratio value.
     */
    private float mCurrentMiddleStickyZoomRatio;
    /**
     * Current high sticky zoom ratio value.
     */
    private float mCurrentHighStickyZoomRatio;
    /**
     * The min supported zoom ratio value.
     */
    private float mMinZoomRatio;
    /**
     * The max supported zoom ratio value.
     */
    private float mMaxZoomRatio;
    /**
     * Current zoom ratio value.
     */
    private float mCurrentZoomRatio;
    /**
     * Current toggle option count.
     */
    private int mToggleOptionCount = 3;
    private Runnable mToggleUiAutoShowRunnable;
    /**
     * The registered zoom ratio updated listener.
     */
    private OnZoomRatioUpdatedListener mOnZoomRatioUpdatedListener = null;
    private boolean mFirstPositionSkipped = false;
    private float mPreviousXPosition = INVALID_X_POSITION;

    public ZoomController(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ZoomController(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ZoomController(@NonNull Context context) {
        super(context);
    }

    /**
     * Initializes the controller.
     *
     * @param layoutInflater to inflate the zoom ui layout
     * @param zoomRatioRange the supported zoom ratio range
     */
    public void init(LayoutInflater layoutInflater, Range<Float> zoomRatioRange) {
        removeAllViews();
        addView(layoutInflater.inflate(R.layout.zoom_controller, null));

        mToggleUiOptions = findViewById(R.id.zoom_ui_toggle_options);
        mToggleUiBackground = findViewById(R.id.zoom_ui_toggle_background);
        mToggleButtonSelected = findViewById(R.id.zoom_ui_toggle_btn_selected);
        mSeekBar = findViewById(R.id.zoom_ui_seekbar_slider);
        mZoomKnob = findViewById(R.id.zoom_ui_knob);
        mToggleOptionLow = findViewById(R.id.zoom_ui_toggle_option_low);
        mToggleOptionMiddle = findViewById(R.id.zoom_ui_toggle_option_middle);
        mToggleOptionHigh = findViewById(R.id.zoom_ui_toggle_option_high);
        mToggleOptionLowSideSpace = findViewById(R.id.zoom_ui_toggle_option_low_side_space);

        switchZoomUiMode(mZoomUiMode);

        mSeekBar.setMax(MAX_ZOOM_PROGRESS);
        mZoomKnob.initialize(mSeekBar, MAX_ZOOM_PROGRESS);
        setSupportedZoomRatioRange(zoomRatioRange);

        // Monitors the touch events on the toggle UI to update the zoom ratio value.
        mToggleUiOptions.setOnTouchListener((v, event) -> {
            if (mZoomUiMode == ZOOM_UI_TOGGLE_MODE) {
                updateSelectedZoomToggleOptionByMotionEvent(event);
            } else {
                updateSeekBarProgressByMotionEvent(event);
            }

            return false;
        });

        // Long click events will trigger to switch the zoom ui mode
        mToggleUiOptions.setOnLongClickListener(v -> switchZoomUiMode(ZOOM_UI_SEEK_BAR_MODE));

        mSeekBar.setOnSeekBarChangeListener(
                new OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        updateZoomKnobByProgress(progress);
                        setZoomRatioInternal(convertProgressToZoomRatio(progress), true);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                        mZoomKnob.setElevated(true);
                        removeToggleUiAutoShowRunnable();
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        mZoomKnob.setElevated(false);
                        resetToggleUiAutoShowRunnable();
                    }
                }
        );
    }

    /**
     * Sets the supported zoom ratio range to the controller.
     */
    public void setSupportedZoomRatioRange(Range<Float> zoomRatioRange) {
        Preconditions.checkArgument(zoomRatioRange.getLower() > 0,
                "The minimal zoom ratio must be positive.");
        mMinZoomRatio = zoomRatioRange.getLower();
        mMaxZoomRatio = zoomRatioRange.getUpper();
        mCurrentZoomRatio = 1.0f;

        // The default low sticky value will always be the min supported zoom ratio
        mDefaultLowStickyZoomRatio = mMinZoomRatio;

        // Supports 3 toggle options if min supported zoom ratio is smaller than 1.0f and the max
        // supported zoom ratio is larger than 2.0f after rounding
        if (mMinZoomRatio < 0.95f && mMaxZoomRatio >= 2.05f) {
            transformToggleUiByOptionCount(3);
            // Sets the high sticky zoom ratio as 2.0f
            mDefaultHighStickyZoomRatio = 2.0f;
        } else {
            transformToggleUiByOptionCount(2);
            // Sets the high sticky zoom ratio as 2.0f if the max supported zoom ratio is larger
            // than 2.0f after rounding. Otherwise, sets it as the max supported zoom ratio value.
            mDefaultHighStickyZoomRatio = mMaxZoomRatio >= 2.05f ? 2.0f : mMaxZoomRatio;
        }
        updateToggleOptionValues();
        removeToggleUiAutoShowRunnable();
        switchZoomUiMode(ZOOM_UI_TOGGLE_MODE);
    }

    /**
     * Updates the toggle option values according to current zoom ratio value.
     *
     * <p>If the camera device supports the min zoom ratio smaller than 1.0 and the max zoom ratio
     * larger than 2.0, three toggle options are supported:
     *     - In the beginning, three sticky value options [smallest zoom ratio value, 1.0, 2.0]
     *     will be provided.
     *     - After end users change the zoom ratio:
     *       - If the new zoom ratio setting is smaller than 1.0, the sticky value options will
     *       become [new zoom ratio value, 1.0, 2.0].
     *       - If the new zoom ratio setting is ">= 1.0" and "< 2.0", the sticky value options will
     *       become [smallest zoom ratio value, new zoom ratio value, 2.0].
     *       - If the new zoom ratio setting is ">= 2.0", the sticky value options will become
     *       [smallest zoom ratio value, 1.0, new zoom ratio value].
     *
     * <p>Otherwise, two toggle options are supported:
     *     - In the beginning, two sticky value options [smallest zoom ratio value,
     *     min(2.0, largest zoom ratio value)] will be provided.
     *     - After end users change the zoom ratio:
     *       - If the new zoom ratio setting is ">= smallest zoom ratio value" and
     *       "< min(2.0, largest zoom ratio value)", the sticky value options will become
     *       [new zoom ratio value, min(2.0, largest zoom ratio value)].
     *       - If the new zoom ratio setting is ">= min92.0, largest zoom ratio value)", the sticky
     *       value options will become [smallest zoom ratio value, new zoom ratio value]
     */
    private void updateToggleOptionValues() {
        if (mToggleOptionCount == 3) {
            mToggleOptionMiddle.setText(convertZoomRatioToString(mCurrentMiddleStickyZoomRatio));
            if (mCurrentZoomRatio < (mDefaultMiddleStickyZoomRatio - 0.05f)) {
                setSelectedZoomToggleOption(0);
                mCurrentLowStickyZoomRatio = mCurrentZoomRatio;
                mCurrentMiddleStickyZoomRatio = mDefaultMiddleStickyZoomRatio;
                mCurrentHighStickyZoomRatio = mDefaultHighStickyZoomRatio;
            } else if (mCurrentZoomRatio >= (mDefaultMiddleStickyZoomRatio - 0.05f)
                    && mCurrentZoomRatio < (mDefaultHighStickyZoomRatio - 0.05f)) {
                setSelectedZoomToggleOption(1);
                mCurrentLowStickyZoomRatio = roundZoomRatio(mMinZoomRatio);
                mCurrentMiddleStickyZoomRatio = mCurrentZoomRatio;
                mCurrentHighStickyZoomRatio = mDefaultHighStickyZoomRatio;
            } else {
                setSelectedZoomToggleOption(2);
                mCurrentLowStickyZoomRatio = roundZoomRatio(mMinZoomRatio);
                mCurrentMiddleStickyZoomRatio = mDefaultMiddleStickyZoomRatio;
                mCurrentHighStickyZoomRatio = mCurrentZoomRatio;
            }
            mToggleOptionLow.setText(convertZoomRatioToString(mCurrentLowStickyZoomRatio));
            mToggleOptionMiddle.setText(convertZoomRatioToString(mCurrentMiddleStickyZoomRatio));
            mToggleOptionHigh.setText(convertZoomRatioToString(mCurrentHighStickyZoomRatio));
        } else {
            mToggleOptionLow.setText(convertZoomRatioToString(mCurrentLowStickyZoomRatio));
            if (mCurrentZoomRatio < (mDefaultHighStickyZoomRatio - 0.05f)) {
                setSelectedZoomToggleOption(0);
                mCurrentLowStickyZoomRatio = mCurrentZoomRatio;
                mCurrentHighStickyZoomRatio = mDefaultHighStickyZoomRatio;
            } else {
                setSelectedZoomToggleOption(2);
                mCurrentLowStickyZoomRatio = mDefaultLowStickyZoomRatio;
                mCurrentHighStickyZoomRatio = mCurrentZoomRatio;
            }
            mToggleOptionLow.setText(convertZoomRatioToString(mCurrentLowStickyZoomRatio));
            mToggleOptionHigh.setText(convertZoomRatioToString(mCurrentHighStickyZoomRatio));
        }
    }

    /**
     * Sets the text display rotation of the text in the controller.
     */
    public void setTextDisplayRotation(int rotation, int animationDurationMs) {
        ObjectAnimator anim1 = ObjectAnimator.ofFloat(mToggleOptionLow,
                        /*propertyName=*/"rotation", rotation)
                .setDuration(animationDurationMs);
        anim1.setInterpolator(new AccelerateDecelerateInterpolator());
        anim1.start();
        ObjectAnimator anim2 = ObjectAnimator.ofFloat(mToggleOptionMiddle,
                        /*propertyName=*/"rotation", rotation)
                .setDuration(animationDurationMs);
        anim2.setInterpolator(new AccelerateDecelerateInterpolator());
        anim2.start();
        ObjectAnimator anim3 = ObjectAnimator.ofFloat(mToggleOptionHigh,
                        /*propertyName=*/"rotation", rotation)
                .setDuration(animationDurationMs);
        anim3.setInterpolator(new AccelerateDecelerateInterpolator());
        anim3.start();
        ObjectAnimator animZoomKnob = ObjectAnimator.ofFloat(mZoomKnob,
                        /*propertyName=*/"rotation", rotation)
                .setDuration(animationDurationMs);
        animZoomKnob.setInterpolator(new AccelerateDecelerateInterpolator());
        animZoomKnob.start();
    }

    /**
     * Sets zoom ratio value to the controller.
     */
    public void setZoomRatio(float zoomRatio, int zoomUiMode) {
        setZoomRatioInternal(zoomRatio, false);
        updateZoomKnobByZoomRatio(zoomRatio);
        mSeekBar.setProgress(convertZoomRatioToProgress(zoomRatio));
        switchZoomUiMode(zoomUiMode);
        mSeekBar.setEnabled(zoomUiMode == ZOOM_UI_SEEK_BAR_MODE);
        resetToggleUiAutoShowRunnable();
    }

    /**
     * Sets zoom ratio value and notify the zoom ratio change to the listener according to the
     * input notifyZoomRatioChange value.
     */
    private void setZoomRatioInternal(float zoomRatio, boolean notifyZoomRatioChange) {
        float roundedZoomRatio = roundZoomRatio(
                Math.max(mMinZoomRatio, Math.min(zoomRatio, mMaxZoomRatio)));

        if (mCurrentZoomRatio != roundedZoomRatio && notifyZoomRatioChange
                && mOnZoomRatioUpdatedListener != null) {
            mOnZoomRatioUpdatedListener.onValueChanged(roundedZoomRatio);
        }

        mCurrentZoomRatio = roundedZoomRatio;
        updateToggleOptionValues();
    }

    /**
     * Sets an {@link OnZoomRatioUpdatedListener} to receive zoom ratio changes from the controller.
     */
    public void setOnZoomRatioUpdatedListener(OnZoomRatioUpdatedListener listener) {
        mOnZoomRatioUpdatedListener = listener;
    }

    /**
     * Converts the input float zoom ratio value to string which is rounded with
     * RoundingMode.HALF_UP to one decimal digit.
     */
    static String convertZoomRatioToString(float zoomRatio) {
        DecimalFormat zoomRatioDf = new DecimalFormat("0.0");
        zoomRatioDf.setRoundingMode(RoundingMode.HALF_UP);
        return zoomRatioDf.format(roundZoomRatio(zoomRatio));
    }

    /**
     * Rounds the input float zoom ratio value with RoundingMode.HALF_UP to one decimal digit.
     */
    static float roundZoomRatio(float zoomRatio) {
        float normalizedZoomValue = (float) (Math.round(zoomRatio * 100) / 100.0);
        BigDecimal bigDec = new BigDecimal(normalizedZoomValue);
        return bigDec.setScale(2, RoundingMode.HALF_UP).floatValue();
    }

    /**
     * Switches the UI to the toggle or seek bar mode.
     */
    private boolean switchZoomUiMode(int zoomUiMode) {
        mZoomUiMode = zoomUiMode;
        int toggleUiVisibility = (zoomUiMode == ZOOM_UI_TOGGLE_MODE) ? View.VISIBLE : View.GONE;
        mToggleUiOptions.setVisibility(toggleUiVisibility);
        mToggleButtonSelected.setVisibility(toggleUiVisibility);
        mToggleUiBackground.setVisibility(toggleUiVisibility);

        int seekBarUiVisibility = (zoomUiMode == ZOOM_UI_SEEK_BAR_MODE) ? View.VISIBLE : View.GONE;
        mSeekBar.setVisibility(seekBarUiVisibility);
        mZoomKnob.setVisibility(seekBarUiVisibility);

        if (zoomUiMode == ZOOM_UI_TOGGLE_MODE) {
            mSeekBar.setEnabled(false);
        }

        return false;
    }

    /**
     * Transforms the toggle button UI layout for the desired option count.
     *
     * <p>The medium toggle option will be hidden when toggle option count is 2. The layout width
     * will also be shorten to only keep the space for two toggle buttons.
     *
     * @param toggleOptionCount only 2 or 3 toggle option count is supported.
     */
    private void transformToggleUiByOptionCount(int toggleOptionCount) {
        mToggleOptionCount = toggleOptionCount;
        int layoutWidth;

        switch (toggleOptionCount) {
            case 2 -> {
                layoutWidth = getResources().getDimensionPixelSize(
                        R.dimen.zoom_ui_toggle_two_options_layout_width);
                mToggleOptionLowSideSpace.setVisibility(View.GONE);
                mToggleOptionMiddle.setVisibility(View.GONE);
                setSelectedZoomToggleOption(0);
            }
            case 3 -> {
                layoutWidth = getResources().getDimensionPixelSize(
                        R.dimen.zoom_ui_toggle_three_options_layout_width);
                mToggleOptionLowSideSpace.setVisibility(View.VISIBLE);
                mToggleOptionMiddle.setVisibility(View.VISIBLE);
                setSelectedZoomToggleOption(1);
            }
            default -> throw new IllegalArgumentException("Unsupported toggle option count!");
        }

        LayoutParams lp = (LayoutParams) mToggleUiOptions.getLayoutParams();
        lp.width = layoutWidth;
        mToggleUiOptions.setLayoutParams(lp);

        lp = (LayoutParams) mToggleUiBackground.getLayoutParams();
        lp.width = layoutWidth;
        mToggleUiBackground.setLayoutParams(lp);
    }

    /**
     * Updates the selected zoom toggle option by the motion events.
     *
     * <p>Mark the toggle option as selected when the motion event is in their own layout range.
     */
    private void updateSelectedZoomToggleOptionByMotionEvent(MotionEvent event) {
        int zoomToggleUiWidth = mToggleUiOptions.getWidth();
        float updatedZoomRatio;
        if (event.getX() <= zoomToggleUiWidth / mToggleOptionCount) {
            setSelectedZoomToggleOption(0);
            updatedZoomRatio = mCurrentLowStickyZoomRatio;
        } else if (event.getX()
                > zoomToggleUiWidth * (mToggleOptionCount - 1) / mToggleOptionCount) {
            setSelectedZoomToggleOption(2);
            updatedZoomRatio = mCurrentHighStickyZoomRatio;
        } else {
            setSelectedZoomToggleOption(1);
            updatedZoomRatio = mCurrentMiddleStickyZoomRatio;
        }

        // Updates the knob seek bar and zoom ratio value according to the newly selected option.
        if (updatedZoomRatio != mCurrentZoomRatio) {
            updateZoomKnobByZoomRatio(updatedZoomRatio);
            mSeekBar.setProgress(convertZoomRatioToProgress(updatedZoomRatio));
            setZoomRatioInternal(updatedZoomRatio, true);
        }
    }

    /**
     * Sets the specific zoom toggle option UI as selected.
     */
    private void setSelectedZoomToggleOption(int optionIndex) {
        LayoutParams lp = (LayoutParams) mToggleButtonSelected.getLayoutParams();
        switch (optionIndex) {
            case 0 -> {
                lp.leftMargin = getResources().getDimensionPixelSize(
                        R.dimen.zoom_ui_toggle_padding);
                lp.rightMargin = 0;
                lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.LEFT;
            }
            case 1 -> {
                lp.leftMargin = 0;
                lp.rightMargin = 0;
                lp.gravity = Gravity.CENTER_HORIZONTAL;
            }
            case 2 -> {
                lp.leftMargin = 0;
                lp.rightMargin = getResources().getDimensionPixelSize(
                        R.dimen.zoom_ui_toggle_padding);
                lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.RIGHT;
            }
            default -> throw new IllegalArgumentException("Unsupported toggle option index!");
        }
        mToggleButtonSelected.setLayoutParams(lp);
    }

    /**
     * Updates the seek bar progress by the motion events.
     *
     * <p>The seek bar is disabled until the end users long-click on the toggle button options and
     * a ACTION_UP motion event is received. When the seek bar is disabled, the motion events will
     * be translated to the new progress values and updated to the knob and seek bar.
     */
    private void updateSeekBarProgressByMotionEvent(MotionEvent event) {
        if (mPreviousXPosition == INVALID_X_POSITION) {
            if (!mFirstPositionSkipped) {
                mFirstPositionSkipped = true;
            } else {
                mPreviousXPosition = event.getX();
            }
            return;
        }

        mZoomKnob.setElevated(event.getAction() != MotionEvent.ACTION_UP);

        int seekBarWidth = mSeekBar.getWidth();
        float zoomRatio = roundZoomRatio(mCurrentZoomRatio
                + (mMaxZoomRatio - mMinZoomRatio) * (event.getX() - mPreviousXPosition)
                / (float) seekBarWidth);
        zoomRatio = Math.max(mMinZoomRatio, Math.min(zoomRatio, mMaxZoomRatio));
        updateZoomKnobByZoomRatio(zoomRatio);
        mSeekBar.setProgress(convertZoomRatioToProgress(zoomRatio));
        setZoomRatioInternal(zoomRatio, true);

        if (event.getAction() == MotionEvent.ACTION_UP) {
            mFirstPositionSkipped = false;
            mPreviousXPosition = INVALID_X_POSITION;
            resetToggleUiAutoShowRunnable();
            mSeekBar.setEnabled(true);
        } else {
            mPreviousXPosition = event.getX();
        }
    }

    /**
     * Updates the zoom knob by the progress value.
     */
    private void updateZoomKnobByProgress(int progress) {
        mZoomKnob.updateZoomProgress(progress, convertProgressToZoomRatio(progress));
    }

    /**
     * Converts the progress value to the zoom ratio value.
     */
    private float convertProgressToZoomRatio(int progress) {
        return roundZoomRatio(
                mMinZoomRatio + (mMaxZoomRatio - mMinZoomRatio) * progress / MAX_ZOOM_PROGRESS);
    }

    /**
     * Updates the zoom knob by the zoom ratio value.
     */
    private void updateZoomKnobByZoomRatio(float zoomRatio) {
        mZoomKnob.updateZoomProgress(convertZoomRatioToProgress(zoomRatio), zoomRatio);
    }

    /**
     * Converts the zoom ratio to the progress value value.
     */
    private int convertZoomRatioToProgress(float zoomRatio) {
        return (int) ((zoomRatio - mMinZoomRatio) / (mMaxZoomRatio - mMinZoomRatio)
                * MAX_ZOOM_PROGRESS);
    }

    /**
     * Creates a runnable to show the toggle UI.
     */
    private Runnable createToggleUiAutoShowRunnable() {
        return () -> switchZoomUiMode(ZOOM_UI_TOGGLE_MODE);
    }

    /**
     * Resets the toggle UI auto-show runnable.
     */
    private void resetToggleUiAutoShowRunnable() {
        removeToggleUiAutoShowRunnable();
        mToggleUiAutoShowRunnable = createToggleUiAutoShowRunnable();
        postDelayed(mToggleUiAutoShowRunnable, TOGGLE_UI_AUTO_SHOW_DURATION_MS);
    }

    /**
     * Removes the toggle UI auto-show runnable.
     */
    private void removeToggleUiAutoShowRunnable() {
        if (mToggleUiAutoShowRunnable != null) {
            removeCallbacks(mToggleUiAutoShowRunnable);
        }
    }

    /**
     * The listener to monitor the value change of the zoom controller.
     */
    public interface OnZoomRatioUpdatedListener {

        /**
         * Invoked when the zoom ratio value is changed.
         *
         * @param value the updated zoom ratio value.
         */
        void onValueChanged(float value);
    }
}
