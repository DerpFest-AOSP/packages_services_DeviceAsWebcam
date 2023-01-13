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

#pragma once
#include <mutex>
#include <unordered_map>

#include <android/hardware_buffer.h>
#include <jni.h>

#include "Encoder.h"
#include "FrameProvider.h"

namespace android {
namespace webcam {

// Class which controls camera operation using sdk.
class SdkFrameProvider : public FrameProvider,
                         public EncoderCallback,
                         public std::enable_shared_from_this<SdkFrameProvider> {
  public:
    SdkFrameProvider(jobject weakThiz, std::shared_ptr<BufferProducer> producer,
                     CameraConfig config);
    virtual ~SdkFrameProvider();
    void setStreamConfig() override;
    Status startStreaming() override;
    Status stopStreaming() final;

    virtual void onEncoded(Buffer* producerBuffer, HardwareBufferDesc& hardwareBufferDesc,
                           bool success, JNIEnv* env) override;

    Status getHardwareBufferDescFromHardwareBuffer(JNIEnv* env, jobject hardwareBufferObj,
                                                   HardwareBufferDesc& hardwareBufferDesc);

    // TODO(b/267794640): Move to central JNI method manager
    static void stopService(jobject mWeakThiz);
    static jint com_android_DeviceAsWebcam_encodeImage(JNIEnv* env, jclass thiz,
                                                       jobject hardwareBuffer, jlong timestamp);
    static int registerJniFunctions(JNIEnv* env, JavaVM* jvm);

  private:
    int encodeImage(HardwareBufferDesc desc, jlong timestamp);
    static const JNINativeMethod sMethods[];

    std::mutex mMapLock;
    std::unordered_map<uint32_t, AHardwareBuffer*>
            mBufferIdToAHardwareBuffer;  // guarded by mMapLock
    uint32_t mNextBufferId = 0;          // guarded by mMapLock
    std::shared_ptr<Encoder> mEncoder;
    jobject mWeakThiz;
};

}  // namespace webcam
}  // namespace android
