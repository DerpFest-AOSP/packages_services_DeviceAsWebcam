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

#include <android/hardware_buffer_jni.h>
#include <jni.h>
#include <linux/videodev2.h>
#include <log/log.h>
#include <nativehelper/ScopedLocalRef.h>
#include <vector>

#include "Buffer.h"
#include "SdkFrameProvider.h"
#include "Utils.h"

namespace android {
namespace webcam {

const JNINativeMethod SdkFrameProvider::sMethods[] = {
        {"nativeEncodeImage", "(Landroid/hardware/HardwareBuffer;J)I",
         (void*)SdkFrameProvider::com_android_DeviceAsWebcam_encodeImage},
};

static struct sdkCameraInfo {
    jclass mSdkFrameProviderClazz;
    jmethodID mSdkFrameProviderSetConfig;
    jmethodID mSdkStartStreamingMethod;
    jmethodID mSdkStopStreamingMethod;
    jmethodID mSdkReturnImageMethod;
    jmethodID mStopServiceMethod;
    JavaVM* mJVM = nullptr;
    std::weak_ptr<SdkFrameProvider> mSdkFrameProviderWeakRef;
} gSdkCameraInfo;

static inline jclass FindClassOrDie(JNIEnv* env, const char* class_name) {
    jclass clazz = env->FindClass(class_name);
    LOG_ALWAYS_FATAL_IF(clazz == NULL, "Unable to find class %s", class_name);
    return clazz;
}

static inline jfieldID GetFieldIDOrDie(JNIEnv* env, jclass clazz, const char* field_name,
                                       const char* field_signature) {
    jfieldID res = env->GetFieldID(clazz, field_name, field_signature);
    LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to find field %s with signature %s", field_name,
                        field_signature);
    return res;
}

static inline jmethodID GetStaticMethodIDOrDie(JNIEnv* env, jclass clazz, const char* method_name,
                                               const char* method_signature) {
    jmethodID res = env->GetStaticMethodID(clazz, method_name, method_signature);
    LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to find method %s with signature %s", method_name,
                        method_signature);
    return res;
}

template <typename T>
static inline T MakeGlobalRefOrDie(JNIEnv* env, T in) {
    jobject res = env->NewGlobalRef(in);
    LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to create global reference.");
    return static_cast<T>(res);
}

Status SdkFrameProvider::getHardwareBufferDescFromHardwareBuffer(
        JNIEnv* env, jobject hardwareBufferObj, HardwareBufferDesc& hardwareBufferDesc) {
    AHardwareBuffer_Planes planes;
    AHardwareBuffer* hardwareBuffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObj);
    // Just to not depend on java to keep this alive.
    AHardwareBuffer_acquire(hardwareBuffer);
    if (AHardwareBuffer_lockPlanes(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                                   /*fence*/ -1, /*rect*/ nullptr, &planes) != 0) {
        ALOGE("Couldn't get hardware buffer planes from hardware buffer");
        return Status::ERROR;
    }
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(hardwareBuffer, &desc);
    uint32_t height = desc.height;
    uint32_t width = desc.width;
    hardwareBufferDesc.yData = (uint8_t*)planes.planes[0].data;
    hardwareBufferDesc.yDataLength = planes.planes[0].rowStride * (height - 1) + width;
    hardwareBufferDesc.yRowStride = planes.planes[0].rowStride;

    hardwareBufferDesc.uData = (uint8_t*)planes.planes[1].data;
    hardwareBufferDesc.uDataLength = planes.planes[1].rowStride * (height / 2 - 1) +
                                     (planes.planes[1].pixelStride * width / 2 - 1) + 1;
    hardwareBufferDesc.uRowStride = planes.planes[1].rowStride;

    hardwareBufferDesc.vData = (uint8_t*)planes.planes[2].data;
    hardwareBufferDesc.vDataLength = planes.planes[2].rowStride * (height / 2 - 1) +
                                     (planes.planes[2].pixelStride * width / 2 - 1) + 1;
    hardwareBufferDesc.vRowStride = planes.planes[2].rowStride;

    // Pixel stride is the same for u and v planes
    hardwareBufferDesc.uvPixelStride = planes.planes[1].pixelStride;
    {
        std::lock_guard<std::mutex> l(mMapLock);
        hardwareBufferDesc.bufferId = mNextBufferId++;
        mBufferIdToAHardwareBuffer[hardwareBufferDesc.bufferId] = hardwareBuffer;
    }

    return Status::OK;
}

jint SdkFrameProvider::com_android_DeviceAsWebcam_encodeImage(JNIEnv* env, jclass,
                                                              jobject hardwareBuffer,
                                                              jlong timestamp) {
    auto strongThis = gSdkCameraInfo.mSdkFrameProviderWeakRef.lock();
    if (strongThis == nullptr) {
        ALOGW("%s SdkFrameProvider is dead :(", __FUNCTION__);
        return -1;
    }

    HardwareBufferDesc desc;

    if (strongThis->getHardwareBufferDescFromHardwareBuffer(env, hardwareBuffer, desc) !=
        Status::OK) {
        ALOGE("%s Couldn't get hardware buffer descriptor", __FUNCTION__);
        return -1;
    }
    return strongThis->encodeImage(desc, timestamp);
}

int SdkFrameProvider::encodeImage(HardwareBufferDesc desc, jlong timestamp) {
    Buffer* producerBuffer = mBufferProducer->getFreeBufferIfAvailable();
    if (producerBuffer == nullptr) {
        // Not available so don't compress
        ALOGW("%s Producer buffer not available, returning", __FUNCTION__);
        return -1;
    }
    producerBuffer->setTimestamp(static_cast<uint64_t>(timestamp));
    // send to the Encoder.
    EncodeRequest encodeRequest(desc, producerBuffer);
    mEncoder->queueRequest(encodeRequest);
    return 0;
}

SdkFrameProvider::SdkFrameProvider(jobject weakThiz, std::shared_ptr<BufferProducer> producer,
                                   CameraConfig config)
    : FrameProvider(producer, config) {
    // Set stream configuration in java service.
    mWeakThiz = weakThiz;
    mEncoder = std::make_shared<Encoder>(config, this, gSdkCameraInfo.mJVM);
    if (!(mEncoder->isInited())) {
        ALOGE("%s: Encoder initialization failed", __FUNCTION__);
        return;
    }
    mEncoder->startEncoderThread();

    mInited = true;
}

void SdkFrameProvider::setStreamConfig() {
    JNIEnv* env = nullptr;
    ScopedAttach attach(gSdkCameraInfo.mJVM, &env);
    if (env == nullptr) {
        ALOGE("%s Wasn't able to get JNIEnv for thread", __FUNCTION__);
        return;
    }
    gSdkCameraInfo.mSdkFrameProviderWeakRef = weak_from_this();
    env->CallStaticVoidMethod(gSdkCameraInfo.mSdkFrameProviderClazz,
                              gSdkCameraInfo.mSdkFrameProviderSetConfig, mWeakThiz,
                              (mConfig.fcc == V4L2_PIX_FMT_MJPEG) ? JNI_TRUE : JNI_FALSE,
                              static_cast<jint>(mConfig.width), static_cast<jint>(mConfig.height),
                              static_cast<jint>(mConfig.fps));
}

SdkFrameProvider::~SdkFrameProvider() {
    stopStreaming();
    mEncoder.reset();
}

void SdkFrameProvider::onEncoded(Buffer* producerBuffer, HardwareBufferDesc& desc, bool success,
                                 JNIEnv* env) {
    // Unlock and release
    {
        std::lock_guard<std::mutex> l(mMapLock);
        auto it = mBufferIdToAHardwareBuffer.find(desc.bufferId);
        if (it == mBufferIdToAHardwareBuffer.end()) {
            // Continue anyway to let java call HardwareBuffer.close();
            ALOGE("Couldn't find AHardwareBuffer for buffer id %u, what ?", desc.bufferId);
        } else {
            AHardwareBuffer_unlock(it->second, /*fence*/ nullptr);
            AHardwareBuffer_release(it->second);
            mBufferIdToAHardwareBuffer.erase(it->first);
        }
    }
    env->CallStaticVoidMethod(gSdkCameraInfo.mSdkFrameProviderClazz,
                              gSdkCameraInfo.mSdkReturnImageMethod, mWeakThiz,
                              static_cast<jlong>(producerBuffer->getTimestamp()));

    if (!success) {
        ALOGE("%s Encoding was unsuccessful", __FUNCTION__);
        mBufferProducer->cancelBuffer(producerBuffer);
        return;
    }
    if (mBufferProducer->queueFilledBuffer(producerBuffer) != Status::OK) {
        ALOGE("%s Queueing filled buffer failed, something is wrong", __FUNCTION__);
        return;
    }
}

void SdkFrameProvider::stopService(jobject weakThiz) {
    JNIEnv* env = nullptr;
    ScopedAttach attach(gSdkCameraInfo.mJVM, &env);
    if (env == nullptr) {
        ALOGE("%s Wasn't able to get JNIEnv for thread", __FUNCTION__);
        return;
    }

    env->CallStaticVoidMethod(gSdkCameraInfo.mSdkFrameProviderClazz,
                              gSdkCameraInfo.mStopServiceMethod, weakThiz);
}

Status SdkFrameProvider::startStreaming() {
    JNIEnv* env = nullptr;
    ScopedAttach attach(gSdkCameraInfo.mJVM, &env);
    if (env == nullptr) {
        ALOGE("%s Wasn't able to get JNIEnv for thread", __FUNCTION__);
        return Status::ERROR;
    }
    env->CallStaticVoidMethod(gSdkCameraInfo.mSdkFrameProviderClazz,
                              gSdkCameraInfo.mSdkStartStreamingMethod, mWeakThiz);
    return Status::OK;
}

Status SdkFrameProvider::stopStreaming() {
    JNIEnv* env = nullptr;
    ScopedAttach attach(gSdkCameraInfo.mJVM, &env);
    if (env == nullptr) {
        ALOGE("%s Wasn't able to get JNIEnv for thread", __FUNCTION__);
        return Status::ERROR;
    }
    env->CallStaticVoidMethod(gSdkCameraInfo.mSdkFrameProviderClazz,
                              gSdkCameraInfo.mSdkStopStreamingMethod, mWeakThiz);
    return Status::OK;
}

int SdkFrameProvider::registerJniFunctions(JNIEnv* env, JavaVM* jvm) {
    jclass sdkFrameProviderClazz =
            FindClassOrDie(env, "com/android/DeviceAsWebcam/DeviceAsWebcamFgService");
    // FindClass gives us a local reference.
    gSdkCameraInfo.mSdkFrameProviderClazz = (jclass)env->NewGlobalRef(sdkFrameProviderClazz);

    gSdkCameraInfo.mSdkFrameProviderSetConfig =
            GetStaticMethodIDOrDie(env, gSdkCameraInfo.mSdkFrameProviderClazz, "setStreamConfig",
                                   "(Ljava/lang/Object;ZIII)V");
    gSdkCameraInfo.mSdkStartStreamingMethod = GetStaticMethodIDOrDie(
            env, gSdkCameraInfo.mSdkFrameProviderClazz, "startStreaming", "(Ljava/lang/Object;)V");
    gSdkCameraInfo.mSdkStopStreamingMethod = GetStaticMethodIDOrDie(
            env, gSdkCameraInfo.mSdkFrameProviderClazz, "stopStreaming", "(Ljava/lang/Object;)V");
    gSdkCameraInfo.mStopServiceMethod = GetStaticMethodIDOrDie(
            env, gSdkCameraInfo.mSdkFrameProviderClazz, "stopService", "(Ljava/lang/Object;)V");
    gSdkCameraInfo.mSdkReturnImageMethod = GetStaticMethodIDOrDie(
            env, gSdkCameraInfo.mSdkFrameProviderClazz, "returnImage", "(Ljava/lang/Object;J)V");

    gSdkCameraInfo.mJVM = jvm;
    return jniRegisterNativeMethods(env, "com/android/DeviceAsWebcam/DeviceAsWebcamFgService",
                                    sMethods, NELEM(sMethods));
};

}  // namespace webcam
}  // namespace android
