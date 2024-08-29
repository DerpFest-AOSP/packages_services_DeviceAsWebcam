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
#include <linux/videodev2.h>
#include <utility>
#include <vector>
#include <android/hardware_buffer.h>

#include "Buffer.h"
#include "Utils.h"

namespace android {
namespace webcam {

struct CameraConfig {
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t fps = 0;
    uint32_t fcc = V4L2_PIX_FMT_MJPEG;
};

// Abstract class which maps camera operations
class FrameProvider {
  public:
    FrameProvider(std::shared_ptr<BufferProducer> producer, CameraConfig config)
        : mBufferProducer(std::move(producer)), mConfig(config) {}
    virtual ~FrameProvider() = default;
    virtual void setStreamConfig() = 0;
    virtual Status startStreaming() = 0;
    virtual Status stopStreaming() = 0;
    virtual Status encodeImage(AHardwareBuffer* hardwareBuffer, long timestamp, int rotation) = 0;
    [[nodiscard]] virtual bool isInited() const { return mInited; }

  protected:
    std::shared_ptr<BufferProducer> mBufferProducer;
    CameraConfig mConfig;
    bool mInited = false;
};

}  // namespace webcam
}  // namespace android
