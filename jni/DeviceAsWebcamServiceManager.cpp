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
 *  Manage the listen-mode routing table.
 */

#include "DeviceAsWebcamServiceManager.h"
#include <log/log.h>

#include "UVCProvider.h"

#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>

constexpr uint32_t kMaxDevicePathLen = 256;
constexpr char kDevicePath[] = "/dev/";
constexpr char kPrefix[] = "video";

namespace android {
namespace webcam {

static JavaVM* gJVM = nullptr;

const JNINativeMethod DeviceAsWebcamServiceManager::sMethods[] = {
        {"setupServicesAndStartListeningNative", "(Ljava/lang/Object;)I",
         (void*)DeviceAsWebcamServiceManager::
                 com_android_DeviceAsWebcam_setupServicesAndStartListening},
        {"stopServiceNative", "()V",
         (void*)DeviceAsWebcamServiceManager::com_android_DeviceAsWebcam_stopService},
};

const JNINativeMethod DeviceAsWebcamServiceManager::sReceiverMethods[] = {
        {"shouldStartServiceNative", "()Z",
         (void*)DeviceAsWebcamServiceManager::com_android_DeviceAsWebcam_shouldStartService},
};

int DeviceAsWebcamServiceManager::registerJniFunctions(JNIEnv* e, JavaVM* jvm) {
    static const char fn[] = "DeviceAsWebcamServiceManager::registerJniFunctions";
    gJVM = jvm;

    int res = jniRegisterNativeMethods(e, "com/android/DeviceAsWebcam/DeviceAsWebcamFgService",
                                       sMethods, NELEM(sMethods));
    if (res != 0) {
        return res;
    }
    return jniRegisterNativeMethods(e, "com/android/DeviceAsWebcam/DeviceAsWebcamReceiver",
                                    sReceiverMethods, NELEM(sReceiverMethods));
}

jint DeviceAsWebcamServiceManager::com_android_DeviceAsWebcam_setupServicesAndStartListening(
        JNIEnv* env, jclass, jobject weakThiz) {
    return getInstance()->setupServicesAndStartListening(env, weakThiz);
}

void DeviceAsWebcamServiceManager::com_android_DeviceAsWebcam_stopService(JNIEnv*, jclass) {
    getInstance()->stopService();
}

jboolean DeviceAsWebcamServiceManager::shouldStartService() {
    if (mServiceRunning.load()) {
        ALOGW("Service already running, so we're not starting it again");
        return false;
    }

    return (UVCProvider::getVideoNode().length() != 0);
}

void DeviceAsWebcamServiceManager::recordServiceStopped() {
    mServiceRunning.store(false);
}

void DeviceAsWebcamServiceManager::stopService() {
    std::lock_guard<std::mutex> l(mSerializationLock);
    mUVCProvider = nullptr;
}

jboolean DeviceAsWebcamServiceManager::com_android_DeviceAsWebcam_shouldStartService(JNIEnv*,
                                                                                     jclass) {
    return getInstance()->shouldStartService();
}

int DeviceAsWebcamServiceManager::setupServicesAndStartListening(JNIEnv* env, jobject weakThiz) {
    ALOGV("%s ", __FUNCTION__);
    std::lock_guard<std::mutex> l(mSerializationLock);
    if (mUVCProvider == nullptr) {
        mUVCProvider = std::make_shared<UVCProvider>(env, gJVM, weakThiz, this);
    }
    // Set up UVC stack
    if ((mUVCProvider->init() != Status::OK) || (mUVCProvider->startService() != Status::OK)) {
        ALOGE("%s: Unable to init/ start service", __FUNCTION__);
        return -1;
    }
    mServiceRunning = true;
    return 0;
}

DeviceAsWebcamServiceManager::DeviceAsWebcamServiceManager() {}

DeviceAsWebcamServiceManager::~DeviceAsWebcamServiceManager() {}

DeviceAsWebcamServiceManager* DeviceAsWebcamServiceManager::getInstance() {
    static DeviceAsWebcamServiceManager* dm = new DeviceAsWebcamServiceManager();
    return dm;
}

}  // namespace webcam
}  // namespace android
