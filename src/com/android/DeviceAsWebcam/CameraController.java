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
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.HardwareBuffer;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;

import com.android.DeviceAsWebcam.utils.UserPrefs;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * This class controls the operation of the camera - primarily through the public calls
 * - startPreviewStreaming
 * - startWebcamStreaming
 * - stopPreviewStreaming
 * - stopWebcamStreaming
 * These calls do what they suggest - that is start / stop preview and webcam streams. They
 * internally book-keep whether they need to start a preview stream alongside a webcam stream or
 * by itself, and vice-versa.
 * For the webcam stream, it delegates the job of interacting with the native service
 * code - used for encoding ImageReader image callbacks, to the Foreground service (it stores a weak
 * reference to the foreground service during construction).
 */
public class CameraController {
    private static final String TAG = "CameraController";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    private static final int NO_STREAMING = 0;
    private static final int WEBCAM_STREAMING = 1;
    private static final int PREVIEW_STREAMING = 2;
    private static final int PREVIEW_AND_WEBCAM_STREAMING = 3;

    private static final int MAX_BUFFERS = 4;

    private String mBackCameraId = null;
    private String mFrontCameraId = null;

    private ImageReader mImgReader;
    private int mCurrentState = NO_STREAMING;
    private Context mContext;
    private WeakReference<DeviceAsWebcamFgService> mServiceWeak;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private CameraManager mCameraManager;
    private CameraDevice mCameraDevice;
    private Handler mImageReaderHandler;
    private Executor mCameraCallbacksExecutor;
    private Executor mServiceEventsExecutor;
    private SurfaceTexture mPreviewSurfaceTexture;
    /**
     * Registered by the Preview Activity, and called by CameraController when preview size changes
     * as a result of the webcam stream changing.
     */
    private Consumer<Size> mPreviewSizeChangeListener;
    private Surface mPreviewSurface;
    private Size mPreviewSize;
    private OutputConfiguration mPreviewOutputConfiguration;
    private OutputConfiguration mWebcamOutputConfiguration;
    private List<OutputConfiguration> mOutputConfigurations;
    private CameraCaptureSession mCaptureSession;
    private ConditionVariable mCameraOpened = new ConditionVariable();
    private ConditionVariable mCaptureSessionReady = new ConditionVariable();
    private AtomicBoolean mStartCaptureWebcamStream = new AtomicBoolean(false);
    private final Object mSerializationLock = new Object();
    // timestamp -> Image
    private ConcurrentHashMap<Long, ImageAndBuffer> mImageMap = new ConcurrentHashMap<>();
    private int mFps;
    // TODO(b/267794640): UI to select camera id
    private String mCameraId = null;
    private ArrayMap<String, CameraInfo> mCameraInfoMap = new ArrayMap<>();

    private CameraDevice.StateCallback mCameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            if (VERBOSE) {
                Log.v(TAG, "Camera device opened, creating capture session now");
            }
            mCameraDevice = cameraDevice;
            mCameraOpened.open();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
        }
    };
    private CameraCaptureSession.CaptureCallback mCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {};

    private CameraCaptureSession.StateCallback mCameraCaptureSessionCallback =
            new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    if (mCameraDevice == null) {
                        return;
                    }
                    mCaptureSession = cameraCaptureSession;
                    try {
                            mCaptureSession.setSingleRepeatingRequest(
                                    mPreviewRequestBuilder.build(), mCameraCallbacksExecutor,
                                    mCaptureCallback);

                    } catch (CameraAccessException e) {
                        Log.e(TAG, "setSingleRepeatingRequest failed", e);
                    }
                    mCaptureSessionReady.open();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession captureSession) {
                    Log.e(TAG, "Failed to configure CameraCaptureSession");
                }
            };

    private ImageReader.OnImageAvailableListener mOnImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    DeviceAsWebcamFgService service = mServiceWeak.get();
                    if (service == null) {
                        Log.e(TAG, "Service is dead, what ?");
                        return;
                    }
                    if (mImageMap.size() >= MAX_BUFFERS) {
                            Log.w(TAG, "Too many buffers acquired in onImageAvailable, returning");
                            return;
                    }
                    // Get native HardwareBuffer from the latest image and send it to
                    // the native layer for the encoder to process.
                    // Acquire latest Image and get the HardwareBuffer
                    Image image = reader.acquireLatestImage();
                    if (VERBOSE) {
                        Log.v(TAG, "Got acquired Image in onImageAvailable callback");
                    }
                    if (image == null) {
                        if (VERBOSE) {
                            Log.e(TAG, "More images than MAX acquired ?");
                        }
                        return;
                    }
                    long ts = image.getTimestamp();
                    HardwareBuffer hardwareBuffer = image.getHardwareBuffer();
                    mImageMap.put(ts, new ImageAndBuffer(image, hardwareBuffer));
                    // Callback into DeviceAsWebcamFgService to encode image
                    if ((!mStartCaptureWebcamStream.get()) || (service.nativeEncodeImage(
                            hardwareBuffer, ts, getCurrentRotation()) != 0)) {
                        if (VERBOSE) {
                            Log.v(TAG,
                                    "Couldn't get buffer immediately, returning image images. "
                                            + "acquired size "
                                            + mImageMap.size());
                        }
                        returnImage(ts);
                    }
                }
            };

    private volatile float mZoomRatio;
    private RotationProvider mRotationProvider;
    private RotationUpdateListener mRotationUpdateListener = null;
    private CameraInfo mCameraInfo = null;
    private UserPrefs mUserPrefs;

    public CameraController(Context context, WeakReference<DeviceAsWebcamFgService> serviceWeak) {
        mContext = context;
        mServiceWeak = serviceWeak;
        if (mContext == null) {
            Log.e(TAG, "Application context is null!, something is going to go wrong");
            return;
        }
        startBackgroundThread();
        mCameraManager = mContext.getSystemService(CameraManager.class);
        refreshLensFacingCameraIds();

        mUserPrefs = new UserPrefs(mContext);
        mCameraId = mUserPrefs.fetchCameraId(/*defaultCameraId*/ mBackCameraId);
        mZoomRatio = mUserPrefs.fetchZoomRatio(mCameraId, /*defaultZoom*/ 1.0f);

        mCameraInfo = mCameraInfoMap.get(mCameraId);
        mRotationProvider = new RotationProvider(context.getApplicationContext(),
                mCameraInfo.getSensorOrientation(), mCameraInfo.getLensFacing());
        // Adds a listener to enable the RotationProvider so that we can get the rotation
        // degrees info to rotate the webcam stream images.
        mRotationProvider.addListener(mCameraCallbacksExecutor, rotation -> {
            if (mRotationUpdateListener != null) {
                mRotationUpdateListener.onRotationUpdated(rotation);
            }
        });
    }

    private void refreshLensFacingCameraIds() {
        VendorCameraPrefs rroCameraInfo =
                VendorCameraPrefs.getVendorCameraPrefsFromJson(mContext);
        try {
            String[] cameraIdList = mCameraManager.getCameraIdList();
            if (cameraIdList == null) {
                return;
            }
            for (String cameraId : cameraIdList) {
                int lensFacing = getCameraCharacteristic(cameraId,
                        CameraCharacteristics.LENS_FACING);
                if (mBackCameraId == null && lensFacing == CameraMetadata.LENS_FACING_BACK) {
                    mBackCameraId = cameraId;
                } else if (mFrontCameraId == null
                        && lensFacing == CameraMetadata.LENS_FACING_FRONT) {
                    mFrontCameraId = cameraId;
                }
                mCameraInfoMap.put(cameraId,
                        createCameraInfo(cameraId, rroCameraInfo.getPhysicalCameraInfos(cameraId)));
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to retrieve camera id list.", e);
        }
    }

    private CameraInfo createCameraInfo(String cameraId,
            List<VendorCameraPrefs.PhysicalCameraInfo> physicalInfos) {
        String workingCameraId =
                (physicalInfos != null && !physicalInfos.isEmpty()) ? physicalInfos.get(
                        0).physicalCameraId : cameraId;
        return cameraId == null ? null : new CameraInfo(
                getCameraCharacteristic(cameraId, CameraCharacteristics.LENS_FACING),
                getCameraCharacteristic(cameraId, CameraCharacteristics.SENSOR_ORIENTATION),
                // TODO: b/269644311 Need to find a way to correct the available zoom ratio range
                //  when a specific physical camera is used.
                getCameraCharacteristic(workingCameraId,
                        CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE),
                physicalInfos,
                getCameraCharacteristic(cameraId,
                        CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        );
    }

    private <T> T getCameraCharacteristic(String cameraId, CameraCharacteristics.Key<T> key) {
        try {
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(
                    cameraId);
            return characteristics.get(key);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to get" + key.getName() + "characteristics for camera " + cameraId
                    + ".");
        }
        return null;
    }

    public void setWebcamStreamConfig(boolean mjpeg, int width, int height, int fps) {
        if (VERBOSE) {
            Log.v(TAG, "Set stream config service : mjpeg  ? " + mjpeg + " width" + width +
                    " height " + height + " fps " + fps);
        }
        synchronized (mSerializationLock) {
            long usage = HardwareBuffer.USAGE_CPU_READ_OFTEN;
            mFps = fps;
            mImgReader = new ImageReader.Builder(width, height)
                    .setMaxImages(MAX_BUFFERS)
                    .setDefaultHardwareBufferFormat(HardwareBuffer.YCBCR_420_888)
                    .setUsage(usage)
                    .build();
            mImgReader.setOnImageAvailableListener(mOnImageAvailableListener,
                    mImageReaderHandler);

        }
    }

    /**
     * Must be called with mSerializationLock held.
     */
    private void openCameraBlocking() {
        if (mCameraManager == null) {
            Log.e(TAG, "CameraManager is not initialized, aborting");
            return;
        }
        if (mCameraId == null) {
            Log.e(TAG, "No camera is found on the device, aborting");
            return;
        }
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        try {
            mCameraManager.openCamera(mCameraId, mCameraCallbacksExecutor, mCameraStateCallback);
        } catch (CameraAccessException e) {
            Log.e(TAG, "openCamera failed for cameraId : " + mCameraId, e);
        }
        mCameraOpened.block();
        mCameraOpened.close();
    }

    private void setupPreviewOnlyStreamLocked(SurfaceTexture previewSurfaceTexture) {
        setupPreviewOnlyStreamLocked(new Surface(previewSurfaceTexture));
    }

    private void setupPreviewOnlyStreamLocked(Surface previewSurface) {
        mPreviewSurface = previewSurface;
        openCameraBlocking();
        mPreviewRequestBuilder = createInitialPreviewRequestBuilder(mPreviewSurface);
        if (mPreviewRequestBuilder == null) {
            return;
        }
        mPreviewOutputConfiguration = new OutputConfiguration(mPreviewSurface);
        // So that we don't have to reconfigure if / when the preview activity is turned off /
        // on again.
        mOutputConfigurations = Arrays.asList(mPreviewOutputConfiguration);
        createCaptureSessionBlocking();
        mCurrentState = PREVIEW_STREAMING;
    }

    private CaptureRequest.Builder createInitialPreviewRequestBuilder(Surface targetSurface) {
        CaptureRequest.Builder captureRequestBuilder;
        try {
            captureRequestBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        } catch (CameraAccessException e) {
            Log.e(TAG, "createCaptureRequest failed", e);
            return null;
        }

        Range<Integer> fpsRange;
        if (mFps != 0) {
            fpsRange = new Range<>(mFps, mFps);
        } else {
            fpsRange = new Range<>(30, 30);
        }
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
        captureRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, mZoomRatio);
        captureRequestBuilder.addTarget(targetSurface);
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        if (isFacePrioritySupported()) {
            captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE,
                    CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY);
        }

        return captureRequestBuilder;
    }

    private boolean isFacePrioritySupported() {
        int[] availableSceneModes = getCameraCharacteristic(mCameraId,
                CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);

        if (availableSceneModes == null) {
            return false;
        }

        for (int availableSceneMode : availableSceneModes) {
            if (availableSceneMode == CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY) {
                return true;
            }
        }

        return false;
    }

    private void setupPreviewStreamAlongsideWebcamStreamLocked(
            SurfaceTexture previewSurfaceTexture) {
        setupPreviewStreamAlongsideWebcamStreamLocked(new Surface(previewSurfaceTexture));
    }

    private void setupPreviewStreamAlongsideWebcamStreamLocked(Surface previewSurface) {
        if (VERBOSE) {
            Log.v(TAG, "setupPreviewAlongsideWebcam");
        }
        mPreviewSurface = previewSurface;
        mPreviewOutputConfiguration = new OutputConfiguration(mPreviewSurface);
        mPreviewRequestBuilder.addTarget(mPreviewSurface);
        mOutputConfigurations = Arrays.asList(mPreviewOutputConfiguration,
                mWebcamOutputConfiguration);
        createCaptureSessionBlocking();
        mCurrentState = PREVIEW_AND_WEBCAM_STREAMING;
    }

    public void startPreviewStreaming(SurfaceTexture surfaceTexture, Size previewSize,
            Consumer<Size> previewSizeChangeListener) {
        // Started on a background thread since we don't want to be blocking either the activity's
        // or the service's main thread (we call blocking camera open in these methods internally)
        mServiceEventsExecutor.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (mSerializationLock) {
                    mPreviewSurfaceTexture = surfaceTexture;
                    mPreviewSize = previewSize;
                    mPreviewSizeChangeListener = previewSizeChangeListener;
                    switch (mCurrentState) {
                        case NO_STREAMING:
                            setupPreviewOnlyStreamLocked(surfaceTexture);
                            break;
                        case WEBCAM_STREAMING:
                            setupPreviewStreamAlongsideWebcamStreamLocked(surfaceTexture);
                            break;
                        case PREVIEW_STREAMING:
                        case PREVIEW_AND_WEBCAM_STREAMING:
                            Log.e(TAG, "Incorrect current state for startPreviewStreaming " +
                                    mCurrentState);
                    }
                }
            }
        });
    }

    private void setupWebcamOnlyStreamAndOpenCameraLocked() {
        // Setup outputs
        if (VERBOSE) {
            Log.v(TAG, "setupWebcamOnly");
        }
        Surface surface = mImgReader.getSurface();
        openCameraBlocking();
        mPreviewRequestBuilder = createInitialPreviewRequestBuilder(surface);
        if (mPreviewRequestBuilder == null) {
            Log.e(TAG, "Failed to create the webcam stream.");
            return;
        }
        mWebcamOutputConfiguration = new OutputConfiguration(surface);
        mOutputConfigurations = Arrays.asList(mWebcamOutputConfiguration);
        createCaptureSessionBlocking();
        mCurrentState = WEBCAM_STREAMING;
    }

    private void setupWebcamStreamAndReconfigureSessionLocked() {
        // Setup outputs
        if (VERBOSE) {
            Log.v(TAG, "setupWebcamStreamAndReconfigureSession");
        }
        Surface surface = mImgReader.getSurface();
        mPreviewRequestBuilder.addTarget(surface);
        mWebcamOutputConfiguration = new OutputConfiguration(surface);
        mOutputConfigurations =
                Arrays.asList(mWebcamOutputConfiguration, mPreviewOutputConfiguration);
        createCaptureSessionBlocking();
        mCurrentState = PREVIEW_AND_WEBCAM_STREAMING;
    }

    /**
     * Adjust preview output configuration when preview size is changed.
     */
    private void adjustPreviewOutputConfiguration() {
        if (mPreviewSurfaceTexture == null || mPreviewSurface == null) {
            return;
        }

        Size suitablePreviewSize = getSuitablePreviewSize();
        // If the required preview size is the same, don't need to adjust the output configuration
        if (Objects.equals(suitablePreviewSize, mPreviewSize)) {
            return;
        }

        // Removes the original preview surface
        mPreviewRequestBuilder.removeTarget(mPreviewSurface);
        // Adjusts the SurfaceTexture default buffer size to match the new preview size
        mPreviewSurfaceTexture.setDefaultBufferSize(suitablePreviewSize.getWidth(),
                suitablePreviewSize.getHeight());
        mPreviewSize = suitablePreviewSize;
        mPreviewRequestBuilder.addTarget(mPreviewSurface);
        mPreviewOutputConfiguration = new OutputConfiguration(mPreviewSurface);
        mOutputConfigurations = mWebcamOutputConfiguration != null ? Arrays.asList(
                mWebcamOutputConfiguration, mPreviewOutputConfiguration) : Arrays.asList(
                mPreviewOutputConfiguration);

        // Invokes the preview size change listener so that the preview activity can adjust its
        // size and scale to match the new size.
        if (mPreviewSizeChangeListener != null) {
            mPreviewSizeChangeListener.accept(suitablePreviewSize);
        }
    }

    public void startWebcamStreaming() {
        // Started on a background thread since we don't want to be blocking the service's main
        // thread (we call blocking camera open in these methods internally)
        mServiceEventsExecutor.execute(() -> {
            mStartCaptureWebcamStream.set(true);
            synchronized (mSerializationLock) {
                if (mImgReader == null) {
                    Log.e(TAG,
                            "Webcam streaming requested without ImageReader initialized");
                    return;
                }
                switch (mCurrentState) {
                    case NO_STREAMING:
                        setupWebcamOnlyStreamAndOpenCameraLocked();
                        break;
                    case PREVIEW_STREAMING:
                        adjustPreviewOutputConfiguration();
                        // Its okay to recreate an already running camera session with
                        // preview since the 'glitch' that we see will not be on the webcam
                        // stream.
                        setupWebcamStreamAndReconfigureSessionLocked();
                        break;
                    case PREVIEW_AND_WEBCAM_STREAMING:
                    case WEBCAM_STREAMING:
                        Log.e(TAG, "Incorrect current state for startWebcamStreaming "
                                + mCurrentState);
                }
            }
        });
    }

    private void stopPreviewStreamOnlyLocked() {
        mPreviewRequestBuilder.removeTarget(mPreviewSurface);
        mOutputConfigurations = Arrays.asList(mWebcamOutputConfiguration);
        createCaptureSessionBlocking();
        mPreviewSurfaceTexture = null;
        mPreviewSizeChangeListener = null;
        mPreviewSurface = null;
        mPreviewSize = null;
        mCurrentState = WEBCAM_STREAMING;
    }

    public void stopPreviewStreaming() {
        // Started on a background thread since we don't want to be blocking either the activity's
        // or the service's main thread (we call blocking camera open in these methods internally)
        mServiceEventsExecutor.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (mSerializationLock) {
                    switch (mCurrentState) {
                        case PREVIEW_AND_WEBCAM_STREAMING:
                            stopPreviewStreamOnlyLocked();
                            break;
                        case PREVIEW_STREAMING:
                            stopStreamingAltogetherLocked();
                            break;
                        case NO_STREAMING:
                        case WEBCAM_STREAMING:
                            Log.e(TAG,
                                    "Incorrect current state for stopPreviewStreaming " +
                                            mCurrentState);
                    }
                }
            }
        });
    }

    private void stopWebcamStreamOnlyLocked() {
        // Re-configure session to have only the preview stream
        // Setup outputs
        mPreviewRequestBuilder.removeTarget(mImgReader.getSurface());
        mOutputConfigurations =
                Arrays.asList(mPreviewOutputConfiguration);
        createCaptureSessionBlocking();
        mWebcamOutputConfiguration = null;
        mCurrentState = PREVIEW_STREAMING;
    }

    private void stopStreamingAltogetherLocked() {
        if (VERBOSE) {
            Log.v(TAG, "StopStreamingAltogether");
        }
        if (mImgReader != null) {
            mImgReader.close();
        }
        mCameraDevice.close();
        mCameraDevice = null;
        mImgReader = null;
        mWebcamOutputConfiguration = null;
        mPreviewOutputConfiguration = null;
        mCurrentState = NO_STREAMING;
    }

    public void stopWebcamStreaming() {
        // Started on a background thread since we don't want to be blocking the service's main
        // thread (we call blocking camera open in these methods internally)
        mServiceEventsExecutor.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (mSerializationLock) {
                    switch (mCurrentState) {
                        case PREVIEW_AND_WEBCAM_STREAMING:
                            stopWebcamStreamOnlyLocked();
                            break;
                        case WEBCAM_STREAMING:
                            stopStreamingAltogetherLocked();
                            break;
                        case PREVIEW_STREAMING:
                        case NO_STREAMING:
                            Log.e(TAG,
                                    "Incorrect current state for stopWebcamStreaming " +
                                            mCurrentState);
                            return;
                    }
                }
                mStartCaptureWebcamStream.set(false);
            }
        });
    }

    private void startBackgroundThread() {
        HandlerThread imageReaderThread = new HandlerThread("SdkCameraFrameProviderThread");
        imageReaderThread.start();
        mImageReaderHandler = new Handler(imageReaderThread.getLooper());
        // We need two executor threads since the surface texture add / remove calls from the fg
        // service are going to be served on the main thread. To not wait on capture session
        // creation, onCaptureSequenceCompleted we need a new thread to cater to preview surface
        // addition / removal.
        // b/277099495 has additional context.
        mCameraCallbacksExecutor = Executors.newSingleThreadExecutor();
        mServiceEventsExecutor = Executors.newSingleThreadExecutor();
    }

    private void createCaptureSessionBlocking() {
        CameraInfo cameraInfo = mCameraInfoMap.get(mCameraId);
        List<VendorCameraPrefs.PhysicalCameraInfo> physicalInfos =
                cameraInfo.getPhysicalCameraInfos();
        if (physicalInfos != null && physicalInfos.size() != 0) {
            // For now we just consider the first physical camera id.
            String physicalCameraId = physicalInfos.get(0).physicalCameraId;
            // TODO: b/269644311 charcoalchen@google.com : Allow UX to display labels
            // and choose amongst physical camera ids if offered by vendor.
            for (OutputConfiguration config : mOutputConfigurations) {
                config.setPhysicalCameraId(physicalCameraId);
            }
        }

        try {
            mCameraDevice.createCaptureSession(
                    new SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR, mOutputConfigurations,
                            mCameraCallbacksExecutor, mCameraCaptureSessionCallback));
            mCaptureSessionReady.block();
            mCaptureSessionReady.close();
        } catch (CameraAccessException e) {
            Log.e(TAG, "createCaptureSession failed", e);
        }
    }

    public void returnImage(long timestamp) {
        ImageAndBuffer imageAndBuffer = mImageMap.remove(timestamp);
        if (imageAndBuffer == null) {
            Log.e(TAG, "Image with timestamp " + timestamp +
                    " was never encoded / already returned");
            return;
        }
        imageAndBuffer.buffer.close();
        imageAndBuffer.image.close();
        if (VERBOSE) {
            Log.v(TAG, "Returned image " + timestamp);
        }
    }

    /**
     * Returns the {@link CameraInfo} of the working camera.
     */
    public CameraInfo getCameraInfo() {
        return mCameraInfo;
    }

    /**
     * Sets the new zoom ratio setting to the working camera.
     */
    public void setZoomRatio(float zoomRatio) {
        mZoomRatio = zoomRatio;
        mServiceEventsExecutor.execute(() -> {
            synchronized (mSerializationLock) {
                if (mCameraDevice == null || mCaptureSession == null) {
                    return;
                }

                try {
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio);
                    mCaptureSession.setSingleRepeatingRequest(mPreviewRequestBuilder.build(),
                            mCameraCallbacksExecutor, mCaptureCallback);
                    mUserPrefs.storeZoomRatio(mCameraId, mZoomRatio);
                } catch (CameraAccessException e) {
                    Log.e(TAG, "Failed to set zoom ratio to the working camera.", e);
                }
            }
        });
    }

    /**
     * Returns current zoom ratio setting.
     */
    public float getZoomRatio() {
        return mZoomRatio;
    }

    /**
     * Returns whether the device can support toggle camera function.
     *
     * @return {@code true} if the device has both back and front cameras. Otherwise, returns
     * {@code false}.
     */
    public boolean canToggleCamera() {
        return mBackCameraId != null && mFrontCameraId != null;
    }

    /**
     * Toggles camera between the back and front cameras.
     *
     * The new camera is set up and configured asynchronously, but the camera state (as queried by
     * other methods in {@code CameraController}) is updated synchronously. So querying camera
     * state and metadata immediately after this method returns, returns values associated with the
     * new camera, even if the new camera hasn't started streaming.
     */
    public void toggleCamera() {
        synchronized (mSerializationLock) {
            if (mCameraId.equals(mBackCameraId)) {
                mCameraId = mFrontCameraId;
            } else {
                mCameraId = mBackCameraId;
            }
            mCameraInfo = mCameraInfoMap.get(mCameraId);
            mUserPrefs.storeCameraId(mCameraId);
            mZoomRatio = mUserPrefs.fetchZoomRatio(mCameraId, /*defaultZoom*/ 1.0f);
        }
        mServiceEventsExecutor.execute(() -> {
            synchronized (mSerializationLock) {
                mCaptureSession.close();
                if (mCameraInfo != null) {
                    mRotationProvider.updateSensorOrientation(mCameraInfo.getSensorOrientation(),
                            mCameraInfo.getLensFacing());
                }
                switch (mCurrentState) {
                    case WEBCAM_STREAMING:
                        setupWebcamOnlyStreamAndOpenCameraLocked();
                        break;
                    case PREVIEW_STREAMING:
                        // Preview size might change after toggling the camera.
                        adjustPreviewOutputConfiguration();
                        setupPreviewOnlyStreamLocked(mPreviewSurface);
                        break;
                    case PREVIEW_AND_WEBCAM_STREAMING:
                        setupWebcamOnlyStreamAndOpenCameraLocked();
                        // Preview size might change after toggling the camera.
                        adjustPreviewOutputConfiguration();
                        setupPreviewStreamAlongsideWebcamStreamLocked(mPreviewSurface);
                        break;
                }
            }
        });
    }

    /**
     * Sets a {@link RotationUpdateListener} to monitor the rotation changes.
     */
    public void setRotationUpdateListener(RotationUpdateListener listener) {
        mRotationUpdateListener = listener;
    }

    /**
     * Returns current rotation degrees value.
     */
    public int getCurrentRotation() {
        return mRotationProvider.getRotation();
    }

    /**
     * Returns the best suitable output size for preview.
     *
     * <p>If the webcam stream doesn't exist, find the largest 16:9 supported output size which is
     * not larger than 1080p. If the webcam stream exists, find the largest supported output size
     * which matches the aspect ratio of the webcam stream size and is not larger than the webcam
     * stream size.
     */
    public Size getSuitablePreviewSize() {
        if (mCameraId == null) {
            Log.e(TAG, "No camera is found on the device.");
            return null;
        }

        Size maxPreviewSize = mImgReader != null ? new Size(mImgReader.getWidth(),
                mImgReader.getHeight()) : new Size(1920, 1080);

        // If webcam stream exists, find an output size matching its aspect ratio. Otherwise, find
        // an output size with 16:9 aspect ratio.
        final Rational targetAspectRatio = new Rational(maxPreviewSize.getWidth(),
                maxPreviewSize.getHeight());

        StreamConfigurationMap map = getCameraCharacteristic(mCameraId,
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        if (map == null) {
            Log.e(TAG, "Failed to retrieve StreamConfigurationMap. Return null preview size.");
            return null;
        }

        Size[] outputSizes = map.getOutputSizes(SurfaceTexture.class);

        if (outputSizes == null || outputSizes.length == 0) {
            Log.e(TAG, "Empty output sizes. Return null preview size.");
            return null;
        }

        Size previewSize = Arrays.stream(outputSizes)
                .filter(size -> targetAspectRatio.equals(
                        new Rational(size.getWidth(), size.getHeight())))
                .filter(size -> size.getWidth() * size.getHeight()
                        <= maxPreviewSize.getWidth() * maxPreviewSize.getHeight())
                .max(Comparator.comparingInt(s -> s.getWidth() * s.getHeight()))
                .orElse(null);

        Log.d(TAG, "Suitable preview size is " + previewSize);
        return previewSize;
    }

    /**
     * Trigger tap-to-focus operation for the specified metering rectangles.
     *
     * <p>The specified metering rectangles will be applied for AF, AE and AWB.
     */
    public void tapToFocus(MeteringRectangle[] meteringRectangles) {
        mServiceEventsExecutor.execute(() -> {
            synchronized (mSerializationLock) {
                if (mCameraDevice == null || mCaptureSession == null) {
                    return;
                }

                try {
                    // Updates the metering rectangles to the repeating request
                    updateTapToFocusParameters(mPreviewRequestBuilder, meteringRectangles,
                            /* afTriggerStart */ false);
                    mCaptureSession.setSingleRepeatingRequest(mPreviewRequestBuilder.build(),
                            mCameraCallbacksExecutor, mCaptureCallback);

                    // Creates a capture request to trigger AF start for the metering rectangles.
                    CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(
                            CameraDevice.TEMPLATE_PREVIEW);
                    CaptureRequest previewCaptureRequest = mPreviewRequestBuilder.build();

                    for (CaptureRequest.Key<?> key : previewCaptureRequest.getKeys()) {
                        builder.set((CaptureRequest.Key) key, previewCaptureRequest.get(key));
                    }

                    if (mImgReader != null && previewCaptureRequest.containsTarget(
                            mImgReader.getSurface())) {
                        builder.addTarget(mImgReader.getSurface());
                    }

                    if (mPreviewSurface != null && previewCaptureRequest.containsTarget(
                            mPreviewSurface)) {
                        builder.addTarget(mPreviewSurface);
                    }

                    updateTapToFocusParameters(builder, meteringRectangles,
                            /* afTriggerStart */ true);

                    mCaptureSession.captureSingleRequest(builder.build(),
                            mCameraCallbacksExecutor, mCaptureCallback);
                } catch (CameraAccessException e) {
                    Log.e(TAG, "Failed to execute tap-to-focus to the working camera.", e);
                }
            }
        });
    }

    /**
     * Updates tap-to-focus parameters to the capture request builder.
     *
     * @param builder            the capture request builder to apply the parameters
     * @param meteringRectangles the metering rectangles to apply to the capture request builder
     * @param afTriggerStart     sets CONTROL_AF_TRIGGER as CONTROL_AF_TRIGGER_START if this
     *                           parameter is {@code true}. Otherwise, sets nothing to
     *                           CONTROL_AF_TRIGGER.
     */
    private void updateTapToFocusParameters(CaptureRequest.Builder builder,
            MeteringRectangle[] meteringRectangles, boolean afTriggerStart) {
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, meteringRectangles);
        builder.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, meteringRectangles);
        builder.set(CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.CONTROL_AWB_REGIONS, meteringRectangles);

        if (afTriggerStart) {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_START);
        }
    }

    private static class ImageAndBuffer {
        public Image image;
        public HardwareBuffer buffer;
        public ImageAndBuffer(Image i, HardwareBuffer b) {
            image = i;
            buffer = b;
        }
    }

    /**
     * An interface to monitor the rotation changes.
     */
    interface RotationUpdateListener {
        /**
         * Called when the physical rotation of the device changes to cause the corresponding
         * rotation degrees value is changed.
         *
         * @param rotation the updated rotation degrees value.
         */
        void onRotationUpdated(int rotation);
    }
}
