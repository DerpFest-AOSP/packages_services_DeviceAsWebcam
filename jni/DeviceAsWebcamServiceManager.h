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

/*
 *  Manage the remote camera service native functions.
 */
#pragma once
#include <jni.h>

#include <atomic>
#include <mutex>

#include <android-base/unique_fd.h>
#include <linux/usb/g_uvc.h>
#include <linux/usb/video.h>

namespace android {
namespace webcam {

class UVCProvider;
class DeviceAsWebcamServiceManager {
  public:
    static DeviceAsWebcamServiceManager* getInstance();
    int registerJniFunctions(JNIEnv* e, JavaVM* jvm);
    void recordServiceStopped();
    ~DeviceAsWebcamServiceManager();

  private:
    DeviceAsWebcamServiceManager();
    static jint com_android_DeviceAsWebcam_setupServicesAndStartListening(JNIEnv* env, jclass clazz,
                                                                          jobject weakThiz);
    static jboolean com_android_DeviceAsWebcam_shouldStartService(JNIEnv*, jclass);
    static void com_android_DeviceAsWebcam_stopService(JNIEnv*, jclass);
    static const JNINativeMethod sMethods[];
    static const JNINativeMethod sReceiverMethods[];

    int setupServicesAndStartListening(JNIEnv* env, jobject weakThiz);
    jboolean shouldStartService();
    void stopService();

    std::mutex mSerializationLock; //serializes service start and stop methods
    std::shared_ptr<UVCProvider> mUVCProvider;
    std::atomic<bool> mServiceRunning = false;
};

}  // namespace webcam
}  // namespace android
