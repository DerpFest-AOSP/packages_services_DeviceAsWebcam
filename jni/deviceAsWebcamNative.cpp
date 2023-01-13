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

#include <jni.h>
#include <nativehelper/JNIHelp.h>

#include "DeviceAsWebcamServiceManager.h"
#include "SdkFrameProvider.h"

jint JNI_OnLoad(JavaVM* jvm, void*) {
    using namespace android::webcam;
    JNIEnv* e = NULL;
    if (jvm->GetEnv((void**)&e, JNI_VERSION_1_6)) {
        return JNI_ERR;
    }
    if (DeviceAsWebcamServiceManager::getInstance()->registerJniFunctions(e, jvm) != 0) {
        return JNI_ERR;
    }
    if (SdkFrameProvider::registerJniFunctions(e, jvm) != 0) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
