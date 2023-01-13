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
#include <stdlib.h>
#include <atomic>
#include <condition_variable>
#include <mutex>
#include <queue>
#include <thread>

#include <android/hardware_buffer.h>
#include <jni.h>
#include <jpeglib.h>

#include "Buffer.h"
#include "FrameProvider.h"
#include "Utils.h"

// Manages converting from formats available directly from the camera to standarized formats that
// transport mechanism eg: UVC over USB + host can understand.
namespace android {
namespace webcam {

struct FreeDeleter {
    void operator()(void* mem) { free(mem); }
};

struct EncodeRequest {
    EncodeRequest() {}
    EncodeRequest(HardwareBufferDesc& buffer, Buffer* producerBuffer)
        : srcBuffer(buffer), dstBuffer(producerBuffer) {}
    HardwareBufferDesc srcBuffer;
    Buffer* dstBuffer = nullptr;
};

struct I420 {
    std::unique_ptr<uint8_t[]> y;
    std::unique_ptr<uint8_t[]> u;
    std::unique_ptr<uint8_t[]> v;
    uint32_t yRowStride = 0;
    uint32_t uRowStride = 0;
    uint32_t vRowStride = 0;
};

struct YUYV {
    std::unique_ptr<uint8_t, FreeDeleter> yuyv;
    uint32_t yRowStride = 0;
    uint32_t uRowStride = 0;
    uint32_t vRowStride = 0;
};

class EncoderCallback {
  public:
    // Callback called by encoder into client when encoding is finished. The JNIEnv parameter is
    // valid for the thread the callback is called on only. So it must not be used on other threads
    // to make JNI calls.
    virtual void onEncoded(Buffer* producerBuffer, HardwareBufferDesc& srcBuffer, bool success,
                           JNIEnv* env) = 0;
    virtual ~EncoderCallback() {}
};

// Encoder for YUV_420_88 -> YUY2 / MJPEG conversion.
class Encoder {
  public:
    Encoder(CameraConfig& config, EncoderCallback* cb, JavaVM* jvm);
    bool isInited();
    ~Encoder();
    void startEncoderThread();
    void queueRequest(EncodeRequest& request);

  private:
    void encode(EncodeRequest& request);

    int convertToI420(EncodeRequest& request);
    void encodeToMJpeg(EncodeRequest& request);
    uint32_t i420ToJpeg(EncodeRequest& request);
    bool checkError(const char* msg, j_common_ptr jpeg_error_info_);
    void encodeToYUYV(EncodeRequest& request);
    void encodeThreadLoop();

    std::mutex mRequestLock;
    std::queue<EncodeRequest> mRequestQueue; // guarded by mRequestLock
    std::condition_variable mRequestCondition; // guarded by mRequestLock

    std::thread mEncoderThread;
    CameraConfig mConfig;
    EncoderCallback* mCb = nullptr;
    std::atomic<bool> mContinueEncoding = true;
    bool mInited = false;
    I420 mI420;
    JavaVM* mJVM = nullptr;
    // Can only be used by functions called by the encoder thread.
    JNIEnv* mEncoderThreadEnv = nullptr;
};

}  // namespace webcam
}  // namespace android
