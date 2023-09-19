/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.DeviceAsWebcam;

import static android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.IBinder;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.accessibility.AccessibilityManager;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import androidx.cardview.widget.CardView;

import com.android.DeviceAsWebcam.view.ZoomController;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class DeviceAsWebcamPreview extends Activity {
    private static final String TAG = DeviceAsWebcamPreview.class.getSimpleName();
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);
    private static final int ROTATION_ANIMATION_DURATION_MS = 300;

    private final Executor mThreadExecutor = Executors.newFixedThreadPool(2);
    private final ConditionVariable mServiceReady = new ConditionVariable();

    private boolean mTextureViewSetup = false;
    private Size mPreviewSize;
    private DeviceAsWebcamFgService mLocalFgService;
    private AccessibilityManager mAccessibilityManager;

    private FrameLayout mTextureViewContainer;
    private CardView mTextureViewCard;
    private TextureView mTextureView;
    private View mFocusIndicator;
    private ZoomController mZoomController = null;
    private ImageButton mToggleCameraButton;
    // A listener to monitor the preview size change events. This might be invoked when toggling
    // camera or the webcam stream is started after the preview stream.
    Consumer<Size> mPreviewSizeChangeListener = size -> runOnUiThread(() -> {
                mPreviewSize = size;
                setTextureViewScale();
            }
    );

    // Listener for when Accessibility service are enabled or disabled.
    AccessibilityManager.AccessibilityServicesStateChangeListener mAccessibilityListener =
            accessibilityManager -> {
                List<AccessibilityServiceInfo> services =
                        accessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK);
                boolean areServicesEnabled = !services.isEmpty();
                runOnUiThread(() ->
                        mZoomController.onAccessibilityServicesEnabled(areServicesEnabled));
            };


    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture texture, int width,
                        int height) {
                    runOnUiThread(() -> {
                        if (VERBOSE) {
                            Log.v(TAG, "onSurfaceTextureAvailable " + width + " x " + height);
                        }
                        mServiceReady.block();

                        if (!mTextureViewSetup) {
                            setupTextureViewLayout();
                        }
                        if (mLocalFgService != null) {
                            mLocalFgService.setOnDestroyedCallback(() -> onServiceDestroyed());
                            if (mPreviewSize != null) {
                                mLocalFgService.setPreviewSurfaceTexture(texture, mPreviewSize,
                                        mPreviewSizeChangeListener);
                                if (mLocalFgService.canToggleCamera()) {
                                    mToggleCameraButton.setVisibility(View.VISIBLE);
                                    mToggleCameraButton.setOnClickListener(v -> toggleCamera());
                                } else {
                                    mToggleCameraButton.setVisibility(View.GONE);
                                }
                                rotateUiByRotationDegrees(mLocalFgService.getCurrentRotation());
                                mLocalFgService.setRotationUpdateListener(
                                        rotation -> rotateUiByRotationDegrees(rotation));
                            }
                        }
                    });
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width,
                        int height) {
                    if (VERBOSE) {
                        Log.v(TAG, "onSurfaceTextureSizeChanged " + width + " x " + height);
                    }
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                    runOnUiThread(() -> {
                        if (mLocalFgService != null) {
                            mLocalFgService.removePreviewSurfaceTexture();
                        }
                    });
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                }
            };

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            mLocalFgService = ((DeviceAsWebcamFgService.LocalBinder) service).getService();
            if (VERBOSE) {
                Log.v(TAG, "Got Fg service");
            }
            mServiceReady.open();
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            // Serialize updating mLocalFgService on UI Thread as all consumers of mLocalFgService
            // run on the UI Thread.
            runOnUiThread(() -> {
                mLocalFgService = null;
                finish();
            });
        }
    };

    private MotionEventToZoomRatioConverter mMotionEventToZoomRatioConverter = null;
    private final MotionEventToZoomRatioConverter.ZoomRatioUpdatedListener mZoomRatioListener =
            new MotionEventToZoomRatioConverter.ZoomRatioUpdatedListener() {
                @Override
                public void onZoomRatioUpdated(float updatedZoomRatio) {
                    if (mLocalFgService == null) {
                        return;
                    }

                    mLocalFgService.setZoomRatio(updatedZoomRatio);
                    mZoomController.setZoomRatio(updatedZoomRatio,
                            ZoomController.ZOOM_UI_SEEK_BAR_MODE);
                }
            };

    private GestureDetector.SimpleOnGestureListener mTapToFocusListener =
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapUp(MotionEvent motionEvent) {
                    return tapToFocus(motionEvent);
                }
            };

    private void setTextureViewScale() {
        FrameLayout.LayoutParams frameLayout = new FrameLayout.LayoutParams(mPreviewSize.getWidth(),
                mPreviewSize.getHeight(), Gravity.CENTER);
        mTextureView.setLayoutParams(frameLayout);

        int pWidth = mTextureViewContainer.getWidth();
        int pHeight = mTextureViewContainer.getHeight();
        float scaleYToUnstretched = (float) mPreviewSize.getWidth() / mPreviewSize.getHeight();
        float scaleXToUnstretched = (float) mPreviewSize.getHeight() / mPreviewSize.getWidth();
        float additionalScaleForX = (float) pWidth / mPreviewSize.getHeight();
        float additionalScaleForY = (float) pHeight / mPreviewSize.getWidth();

        // To fit the preview, either letterbox or pillar box.
        float additionalScaleChosen = Math.min(additionalScaleForX, additionalScaleForY);

        float texScaleX = scaleXToUnstretched * additionalScaleChosen;
        float texScaleY = scaleYToUnstretched * additionalScaleChosen;

        mTextureView.setScaleX(texScaleX);
        mTextureView.setScaleY(texScaleY);

        // Resize the card view to match TextureView's final size exactly. This is to clip
        // textureView corners.
        ViewGroup.LayoutParams cardLayoutParams = mTextureViewCard.getLayoutParams();
        // Reduce size by two pixels to remove any rounding errors from casting to int.
        cardLayoutParams.height = ((int) (mPreviewSize.getHeight() * texScaleY)) - 2;
        cardLayoutParams.width = ((int) (mPreviewSize.getWidth() * texScaleX)) - 2;
        mTextureViewCard.setLayoutParams(cardLayoutParams);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupZoomUiControl() {
        if (mLocalFgService == null || mLocalFgService.getCameraInfo() == null) {
            return;
        }

        Range<Float> zoomRatioRange = mLocalFgService.getCameraInfo().getZoomRatioRange();

        if (zoomRatioRange == null) {
            return;
        }

        // Retrieves current zoom ratio setting from CameraController so that the zoom ratio set by
        // the previous closed activity can be correctly restored
        float currentZoomRatio = mLocalFgService.getZoomRatio();

        mMotionEventToZoomRatioConverter = new MotionEventToZoomRatioConverter(
                getApplicationContext(), zoomRatioRange, currentZoomRatio,
                mZoomRatioListener);

        GestureDetector tapToFocusGestureDetector = new GestureDetector(getApplicationContext(),
                mTapToFocusListener);

        // Restores the focus indicator if tap-to-focus points exist
        float[] tapToFocusPoints = mLocalFgService.getTapToFocusPoints();
        if (tapToFocusPoints != null) {
            showFocusIndicator(tapToFocusPoints);
        }

        mTextureView.setOnTouchListener(
                (view, event) -> {
                    mMotionEventToZoomRatioConverter.onTouchEvent(event);
                    tapToFocusGestureDetector.onTouchEvent(event);
                    return true;
                });

        mZoomController.init(getLayoutInflater(), zoomRatioRange);
        mZoomController.setZoomRatio(currentZoomRatio, ZoomController.ZOOM_UI_TOGGLE_MODE);
        mZoomController.setOnZoomRatioUpdatedListener(
                value -> {
                    if (mLocalFgService != null) {
                        mLocalFgService.setZoomRatio(value);
                    }
                    mMotionEventToZoomRatioConverter.setZoomRatio(value);
                });
        if (mAccessibilityManager != null) {
            mAccessibilityListener.onAccessibilityServicesStateChanged(mAccessibilityManager);
        }
    }

    private void setupZoomRatioSeekBar() {
        if (mLocalFgService == null) {
            return;
        }

        mZoomController.setSupportedZoomRatioRange(
                mLocalFgService.getCameraInfo().getZoomRatioRange());
    }

    private void rotateUiByRotationDegrees(int rotation) {
        if (mLocalFgService == null) {
            // Don't do anything if no foreground service is connected
            return;
        }
        int finalRotation = calculateUiRotation(rotation);
        runOnUiThread(() -> {
            ObjectAnimator anim = ObjectAnimator.ofFloat(mToggleCameraButton,
                            /*propertyName=*/"rotation", finalRotation)
                    .setDuration(ROTATION_ANIMATION_DURATION_MS);
            anim.setInterpolator(new AccelerateDecelerateInterpolator());
            anim.start();
            mToggleCameraButton.performHapticFeedback(
                    HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE);

            mZoomController.setTextDisplayRotation(finalRotation, ROTATION_ANIMATION_DURATION_MS);
        });
    }

    private int calculateUiRotation(int rotation) {
        // Rotates the UI control container according to the device sensor rotation degrees and the
        // camera sensor orientation.
        int sensorOrientation = mLocalFgService.getCameraInfo().getSensorOrientation();
        if (mLocalFgService.getCameraInfo().getLensFacing()
                == CameraCharacteristics.LENS_FACING_BACK) {
            rotation = (rotation + sensorOrientation) % 360;
        } else {
            rotation = (360 + rotation - sensorOrientation) % 360;
        }

        // Rotation angle of the view must be [-179, 180] to ensure we always rotate the
        // view through the natural orientation (0)
        return rotation <= 180 ? rotation : rotation - 360;
    }

    private void setupTextureViewLayout() {
        mPreviewSize = mLocalFgService.getSuitablePreviewSize();
        if (mPreviewSize != null) {
            setTextureViewScale();
            setupZoomUiControl();
        }
    }

    private void onServiceDestroyed() {
        ConditionVariable cv = new ConditionVariable();
        cv.close();
        runOnUiThread(() -> {
            try {
                mLocalFgService = null;
                finish();
            } finally {
                cv.open();
            }
        });
        cv.block();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.preview_layout);
        mTextureViewContainer = findViewById(R.id.texture_view_container);
        mTextureViewCard = findViewById(R.id.texture_view_card);
        mTextureView = findViewById(R.id.texture_view);
        mFocusIndicator = findViewById(R.id.focus_indicator);
        mFocusIndicator.setBackground(createFocusIndicatorDrawable());
        mToggleCameraButton = findViewById(R.id.toggle_camera_button);
        mZoomController = findViewById(R.id.zoom_ui_controller);
        mAccessibilityManager = getSystemService(AccessibilityManager.class);
        if (mAccessibilityManager != null) {
            mAccessibilityManager.addAccessibilityServicesStateChangeListener(
                    mAccessibilityListener);
        }
        // Update view to allow for status bar. This let's us keep a consistent background color
        // behind the statusbar.
        mTextureViewContainer.setOnApplyWindowInsetsListener((view, inset) -> {
            Insets statusBarInset = inset.getInsets(WindowInsets.Type.statusBars());
            ViewGroup.MarginLayoutParams layoutParams =
                    (ViewGroup.MarginLayoutParams) mTextureViewContainer.getLayoutParams();
            // This callback will be called every time the window insets change,
            // including when the status bar is hidden. So apply the max statusbar height
            // we have seen
            layoutParams.topMargin = Math.max(layoutParams.topMargin, statusBarInset.top);
            mTextureViewContainer.setLayoutParams(layoutParams);
            return WindowInsets.CONSUMED;
        });

        bindService(new Intent(this, DeviceAsWebcamFgService.class), 0, mThreadExecutor,
                mConnection);
    }

    private Drawable createFocusIndicatorDrawable() {
        int indicatorSize = getResources().getDimensionPixelSize(R.dimen.focus_indicator_size);
        Bitmap bitmap = Bitmap.createBitmap(indicatorSize, indicatorSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        OvalShape ovalShape = new OvalShape();
        ShapeDrawable shapeDrawable = new ShapeDrawable(ovalShape);
        Paint paint = shapeDrawable.getPaint();
        paint.setAntiAlias(true);
        paint.setColor(getResources().getColor(R.color.focus_indicator_background_color, null));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3);

        int circleRadius = indicatorSize / 2;
        canvas.drawCircle(circleRadius, circleRadius, circleRadius - 1, paint);

        return new BitmapDrawable(getResources(), bitmap);
    }

    private void hideSystemUiAndActionBar() {
        // Hides status bar
        Window window = getWindow();
        window.setStatusBarColor(android.R.color.system_neutral1_800);
        window.setDecorFitsSystemWindows(false);
        WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        // Hides the action bar
        getActionBar().hide();
    }

    @Override
    public void onResume() {
        super.onResume();
        hideSystemUiAndActionBar();
        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (mTextureView.isAvailable()) {
            mServiceReady.block();
            if (!mTextureViewSetup) {
                setupTextureViewLayout();
                mTextureViewSetup = true;
            }
            if (mLocalFgService != null && mPreviewSize != null) {
                mLocalFgService.setPreviewSurfaceTexture(mTextureView.getSurfaceTexture(),
                        mPreviewSize, mPreviewSizeChangeListener);
                rotateUiByRotationDegrees(mLocalFgService.getCurrentRotation());
                mLocalFgService.setRotationUpdateListener(rotation ->
                        runOnUiThread(() -> rotateUiByRotationDegrees(rotation)));
                mZoomController.setZoomRatio(mLocalFgService.getZoomRatio(),
                        ZoomController.ZOOM_UI_TOGGLE_MODE);
            }
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        if (mLocalFgService != null) {
            mLocalFgService.removePreviewSurfaceTexture();
            mLocalFgService.setRotationUpdateListener(null);
        }
        super.onPause();
    }

    @Override
    public void onDestroy() {
        if (mAccessibilityManager != null) {
            mAccessibilityManager.removeAccessibilityServicesStateChangeListener(
                    mAccessibilityListener);
        }
        if (mLocalFgService != null) {
            mLocalFgService.setOnDestroyedCallback(null);
        }
        unbindService(mConnection);
        super.onDestroy();
    }

    private void toggleCamera() {
        if (mLocalFgService == null) {
            return;
        }

        mLocalFgService.toggleCamera();
        mFocusIndicator.setVisibility(View.GONE);
        mMotionEventToZoomRatioConverter.reset(mLocalFgService.getZoomRatio(),
                mLocalFgService.getCameraInfo().getZoomRatioRange());
        setupZoomRatioSeekBar();
        mZoomController.setZoomRatio(mLocalFgService.getZoomRatio(),
                ZoomController.ZOOM_UI_TOGGLE_MODE);
    }

    private boolean tapToFocus(MotionEvent motionEvent) {
        if (mLocalFgService == null || mLocalFgService.getCameraInfo() == null) {
            return false;
        }

        float[] normalizedPoint = calculateNormalizedPoint(motionEvent);

        if (isTapToResetAutoFocus(normalizedPoint)) {
            mFocusIndicator.setVisibility(View.GONE);
            mLocalFgService.resetToAutoFocus();
        } else {
            showFocusIndicator(normalizedPoint);
            mLocalFgService.tapToFocus(normalizedPoint);
        }

        return true;
    }

    /**
     * Returns whether the new points overlap with the original tap-to-focus points or not.
     */
    private boolean isTapToResetAutoFocus(float[] newNormalizedPoints) {
        float[] oldNormalizedPoints = mLocalFgService.getTapToFocusPoints();

        if (oldNormalizedPoints == null) {
            return false;
        }

        // Calculates the distance between the new and old points
        float distanceX = Math.abs(newNormalizedPoints[1] - oldNormalizedPoints[1])
                * mTextureViewCard.getWidth();
        float distanceY = Math.abs(newNormalizedPoints[0] - oldNormalizedPoints[0])
                * mTextureViewCard.getHeight();
        double distance = Math.sqrt(distanceX*distanceX + distanceY*distanceY);

        int indicatorRadius = getResources().getDimensionPixelSize(R.dimen.focus_indicator_size)
                / 2;

        // Checks whether the distance is less than the circle radius of focus indicator
        return indicatorRadius >= distance;
    }

    /**
     * Calculates the normalized point which will be the point between [0, 0] to [1, 1] mapping to
     * the preview size.
     */
    private float[] calculateNormalizedPoint(MotionEvent motionEvent) {
        return new float[]{motionEvent.getX() / mPreviewSize.getWidth(),
                motionEvent.getY() / mPreviewSize.getHeight()};
    }

    /**
     * Show the focus indicator and hide it automatically after a proper duration.
     */
    private void showFocusIndicator(float[] normalizedPoint) {
        int indicatorSize = getResources().getDimensionPixelSize(R.dimen.focus_indicator_size);
        float translationX =
                normalizedPoint[0] * mTextureViewCard.getWidth() - indicatorSize / 2f;
        float translationY = normalizedPoint[1] * mTextureViewCard.getHeight()
                - indicatorSize / 2f;
        mFocusIndicator.setTranslationX(translationX);
        mFocusIndicator.setTranslationY(translationY);
        mFocusIndicator.setVisibility(View.VISIBLE);
    }
}
