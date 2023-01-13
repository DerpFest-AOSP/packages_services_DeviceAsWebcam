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

//#define LOG_NDEBUG 0

#include <jni.h>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <vector>

#include <glob.h>
#include <linux/usb/g_uvc.h>
#include <linux/usb/video.h>
#include <stdio.h>
#include <sys/epoll.h>
#include <sys/mman.h>

#include <log/log.h>
#include "SdkFrameProvider.h"
#include "UVCProvider.h"
#include "Utils.h"

constexpr int MAX_EVENTS = 10;
constexpr uint32_t NUM_BUFFERS_ALLOC = 4;
constexpr uint32_t USB_PAYLOAD_TRANSFER_SIZE = 3072;
constexpr uint32_t kMaxDevicePathLen = 256;
constexpr char kDevicePath[] = "/dev/";
constexpr char kDeviceGlobPattern[] = "/dev/video*";
constexpr char kPrefix[] = "video";
constexpr uint32_t CONTROL_INTERFACE_IDX = 0;
constexpr uint32_t STREAMING_INTERFACE_IDX = 1;
constexpr uint32_t FRAME_INTERVAL_NUM = 10'000'000;

namespace android {
namespace webcam {

Status EpollW::init() {
    int fd = epoll_create1(EPOLL_CLOEXEC);
    if (fd < 0) {
        ALOGE("%s epoll_create failed: %s", __FUNCTION__, strerror(errno));
        return Status::ERROR;
    }
    mEpollFd.reset(fd);
    return Status::OK;
}

Status EpollW::add(int fd, uint32_t eventsIn) {
    struct epoll_event event;
    event.data.fd = fd;
    event.events = eventsIn;
    if (epoll_ctl(mEpollFd.get(), EPOLL_CTL_ADD, fd, &event) != 0) {
        ALOGE("%s Epoll_ctl ADD failed %s \n", __FUNCTION__, strerror(errno));
        return Status::ERROR;
    }
    return Status::OK;
}

Status EpollW::modify(int fd, uint32_t newEvents) {
    struct epoll_event event;
    event.data.fd = fd;
    event.events = newEvents;
    // TODO(b/267794640): Could we use CTL_MOD instead with UVC, reliably.
    if (epoll_ctl(mEpollFd.get(), EPOLL_CTL_DEL, fd, &event) != 0) {
        ALOGE("%s Epoll_ctl DEL failed %s \n", __FUNCTION__, strerror(errno));
        return Status::ERROR;
    }
    if (epoll_ctl(mEpollFd.get(), EPOLL_CTL_ADD, fd, &event) != 0) {
        ALOGE("%s Epoll_ctl ADD failed %s \n", __FUNCTION__, strerror(errno));
        return Status::ERROR;
    }
    return Status::OK;
}

Status EpollW::remove(int fd) {
    struct epoll_event event;
    if (epoll_ctl(mEpollFd.get(), EPOLL_CTL_DEL, fd, &event) != 0) {
        ALOGE("%s Epoll_ctl DEL failed %s \n", __FUNCTION__, strerror(errno));
        return Status::ERROR;
    }
    return Status::OK;
}

Events EpollW::wait() {
    struct epoll_event events[MAX_EVENTS];
    Events eventsVec;
    int nFds = epoll_wait(mEpollFd.get(), events, MAX_EVENTS, /*msWait for 15 fps*/ 66);

    if (nFds < 0) {
        ALOGE("%s nFds was < 0 %s", __FUNCTION__, strerror(errno));
        return eventsVec;
    }
    for (int i = 0; i < nFds; i++) {
        eventsVec.emplace_back(events[i]);
    }
    return eventsVec;
}

Status UVCProvider::UVCDevice::openV4L2DeviceAndSubscribe(const std::string& videoNode) {
    struct v4l2_capability cap;
    int fd = open(videoNode.c_str(), O_RDWR);
    if (fd < 0) {
        ALOGE("%s Couldn't open V4L2 device %s err: %d fd %d, err str %s", __FUNCTION__,
              videoNode.c_str(), errno, fd, strerror(errno));
        return Status::ERROR;
    }

    ALOGI("%s Start to listen to device fd %d", __FUNCTION__, fd);
    mUVCFd.reset(fd);
    memset(&cap, 0, sizeof(cap));

    if (ioctl(mUVCFd.get(), VIDIOC_QUERYCAP, &cap) < 0) {
        ALOGE("%s Couldn't get V4L2 device capabilities fd %d", __FUNCTION__, mUVCFd.get());
        return Status::ERROR;
    }

    // Check that its indeed a video output device
    if (!(cap.capabilities & V4L2_CAP_VIDEO_OUTPUT)) {
        ALOGE("%s V4L2 device capabilities don't contain video output, caps:  %zu", __FUNCTION__,
              (size_t)cap.capabilities);
        return Status::ERROR;
    }

    mUVCProperties = ParseUVCProperties();

    // Susbcribe to events
    struct v4l2_event_subscription subscription;
    memset(&subscription, 0, sizeof(subscription));
    std::vector<unsigned long> events = {UVC_EVENT_SETUP, UVC_EVENT_DATA, UVC_EVENT_STREAMON,
                                         UVC_EVENT_STREAMOFF, UVC_EVENT_DISCONNECT};

    for (auto event : events) {
        subscription.type = event;
        if (ioctl(mUVCFd.get(), VIDIOC_SUBSCRIBE_EVENT, &subscription) < 0) {
            ALOGE("%s Couldn't subscribe to V4L2 event %lu error %s", __FUNCTION__, event,
                  strerror(errno));
            return Status::ERROR;
        }
    }
    return Status::OK;
}

static boolean isVideoOutputDevice(const char* dev) {
    base::unique_fd fd(open(dev, O_RDWR));
    if (fd.get() < 0) {
        ALOGE("%s Opening V4L2 node %s failed %s", __FUNCTION__, dev, strerror(errno));
        return false;
    }

    struct v4l2_capability capability;
    int ret = ioctl(fd.get(), VIDIOC_QUERYCAP, &capability);
    if (ret < 0) {
        ALOGE("%s v4l2 QUERYCAP %s failed %s", __FUNCTION__, dev, strerror(errno));
        return false;
    }

    if (capability.device_caps & V4L2_CAP_VIDEO_OUTPUT) {
        ALOGV("%s device %s supports VIDEO_OUTPUT", __FUNCTION__, dev);
        return true;
    }
    ALOGV("%s device %s does not support VIDEO_OUTPUT", __FUNCTION__, dev);
    return false;
}

void UVCProvider::UVCDevice::getFrameIntervals(ConfigFrame* frame, ConfigFormat* format) {
    uint32_t index = 0;
    while (true) {
        struct v4l2_frmivalenum intervalDesc;
        memset(&intervalDesc, 0, sizeof(intervalDesc));
        intervalDesc.index = index;
        intervalDesc.pixel_format = format->fcc;
        intervalDesc.width = frame->width;
        intervalDesc.height = frame->height;
        int ret = ioctl(mUVCFd.get(), VIDIOC_ENUM_FRAMEINTERVALS, &intervalDesc);
        if (ret != 0) {
            return;
        }
        if (intervalDesc.index != index) {
            ALOGE("%s V4L2 api returned different index %u from expected %u", __FUNCTION__,
                  intervalDesc.index, index);
        }
        uint32_t ival = 0;
        switch (intervalDesc.type) {
            case V4L2_FRMIVAL_TYPE_DISCRETE:
                ival = ((float)intervalDesc.discrete.numerator * FRAME_INTERVAL_NUM) /
                       intervalDesc.discrete.denominator;
                break;
            case V4L2_FRMIVAL_TYPE_STEPWISE:
                // We only take the min here
                ival = intervalDesc.stepwise.min.numerator / intervalDesc.stepwise.min.denominator;
                break;
            default:
                ALOGE("%s frame type %u invalid", __FUNCTION__, intervalDesc.type);
                break;
        }
        frame->intervals.emplace_back(ival);
        index++;
    }
    return;
}

void UVCProvider::UVCDevice::getFormatFrames(ConfigFormat* format) {
    uint32_t index = 0;
    while (true) {
        struct v4l2_frmsizeenum frameDesc;
        memset(&frameDesc, 0, sizeof(frameDesc));
        frameDesc.index = index;
        frameDesc.pixel_format = format->fcc;
        ALOGI("Getting frames for format index %u", index);
        int ret = ioctl(mUVCFd.get(), VIDIOC_ENUM_FRAMESIZES, &frameDesc);
        if (ret != 0) {
            return;
        }
        if (frameDesc.index != index) {
            ALOGE("%s V4L2 api returned different index %u from expected %u", __FUNCTION__,
                  frameDesc.index, index);
        }
        ConfigFrame configFrame;
        configFrame.index = index;
        switch (frameDesc.type) {
            case V4L2_FRMSIZE_TYPE_DISCRETE:
                configFrame.width = frameDesc.discrete.width;
                configFrame.height = frameDesc.discrete.height;
                break;
            case V4L2_FRMSIZE_TYPE_STEPWISE:
                // We only take the min here
                configFrame.width = frameDesc.stepwise.min_width;
                configFrame.height = frameDesc.stepwise.min_height;
                break;
            default:
                ALOGE("%s frame type %u invalid", __FUNCTION__, frameDesc.type);
                break;
        }
        getFrameIntervals(&configFrame, format);
        format->frames.emplace_back(configFrame);
        index++;
    }
    return;
}

std::vector<ConfigFormat> UVCProvider::UVCDevice::getFormats() {
    uint32_t index = 0;
    std::vector<ConfigFormat> retVal;
    while (true) {
        struct v4l2_fmtdesc formatDesc;
        memset(&formatDesc, 0, sizeof(formatDesc));
        formatDesc.index = index;
        formatDesc.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
        ALOGI("Getting formats for index %u", index);
        int ret = ioctl(mUVCFd.get(), VIDIOC_ENUM_FMT, &formatDesc);
        if (ret != 0) {
            return retVal;
        }
        if (formatDesc.index != index) {
            ALOGE("%s V4L2 api returned different index %u from expected %u", __FUNCTION__,
                  formatDesc.index, index);
        }
        ConfigFormat configFormat;
        configFormat.formatIndex = index;
        configFormat.fcc = formatDesc.pixelformat;
        getFormatFrames(&configFormat);
        retVal.emplace_back(configFormat);
        index++;
    }
    return retVal;
}

std::shared_ptr<UVCProperties> UVCProvider::UVCDevice::ParseUVCProperties() {
    std::shared_ptr<UVCProperties> ret = std::make_shared<UVCProperties>();
    ret->videoNode = mVideoNode;
    ret->streaming.formats = getFormats();

    // Control interface number is defined as 0 and streaming as 1
    ret->controlInterfaceNumber = CONTROL_INTERFACE_IDX;
    ret->streaming.interfaceNumber = STREAMING_INTERFACE_IDX;
    return ret;
}

UVCProvider::UVCDevice::UVCDevice(jobject weakThiz, std::weak_ptr<UVCProvider> parent) {
    mParent = parent;

    // Initialize probe and commit controls with default values
    FormatTriplet defaultFormatTriplet(/*formatIndex*/ 1, /*frameIndex*/ 1, /*frameInterval*/ 0);

    mVideoNode = getVideoNode();

    if (mVideoNode.empty()) {
        ALOGE("%s: mVideoNode is empty, what ?", __FUNCTION__);
        return;
    }

    if (openV4L2DeviceAndSubscribe(mVideoNode) != Status::OK) {
        ALOGE("%s: Unable to open and subscribe to V4l2 node %s ?", __FUNCTION__,
              mVideoNode.c_str());
        return;
    }
    setStreamingControl(&mCommit, &defaultFormatTriplet);
    mInited = true;
    mWeakThiz = weakThiz;
}

void UVCProvider::UVCDevice::closeUVCFd() {
    if (mUVCFd.get() >= 0) {
        struct v4l2_event_subscription subscription;
        memset(&subscription, 0, sizeof(subscription));
        subscription.type = V4L2_EVENT_ALL;

        if (ioctl(mUVCFd.get(), VIDIOC_UNSUBSCRIBE_EVENT, subscription) < 0) {
            ALOGE("%s Couldn't unsubsrcibe from V4L2 events error %s", __FUNCTION__,
                  strerror(errno));
        }
    }
    mUVCFd.reset();
}

std::string UVCProvider::getVideoNode() {
    std::string devNode;
    ALOGV("%s start scanning for existing V4L2 OUTPUT devices", __FUNCTION__);
    glob_t globbuf;
    glob(kDeviceGlobPattern, /*flags*/ 0, /*errfunc*/ NULL, &globbuf);
    for (unsigned int i = 0; i < globbuf.gl_pathc; ++i) {
        ALOGV("%s file: %s", __FUNCTION__, globbuf.gl_pathv[i]);
        if (isVideoOutputDevice(globbuf.gl_pathv[i])) {
            devNode = globbuf.gl_pathv[i];
            break;
        }
    }
    globfree(&globbuf);
    return devNode;
}

bool UVCProvider::UVCDevice::isInited() {
    return mInited;
}

void UVCProvider::UVCDevice::processSetupControlEvent(const struct usb_ctrlrequest* control,
                                                      struct uvc_request_data* resp) {
    // TODO(b/267794640): Support control requests
    resp->data[0] = 0x3;
    resp->length = control->wLength;
    return;
}

void UVCProvider::UVCDevice::processSetupStreamingEvent(const struct usb_ctrlrequest* request,
                                                        struct uvc_request_data* response) {
    uint8_t requestType = request->bRequest;
    uint8_t controlSelect = request->wValue >> 8;
    if (controlSelect != UVC_VS_PROBE_CONTROL && controlSelect != UVC_VS_COMMIT_CONTROL) {
        ALOGE("%s: control select %u is invalid", __FUNCTION__, controlSelect);
        return;
    }

    struct uvc_streaming_control* ctrl =
            reinterpret_cast<struct uvc_streaming_control*>(response->data);
    response->length = sizeof(struct uvc_streaming_control);
    FormatTriplet maxFormatTriplet(UINT8_MAX, UINT8_MAX, UINT8_MAX);
    FormatTriplet defaultFormatTriplet(1, 1, 0);
    switch (requestType) {
        case UVC_SET_CUR:
            mCurrentControlState = controlSelect;
            // UVC 1.5 spec section 4.3.1
            response->length = 48;
            break;
        case UVC_GET_CUR:
            if (controlSelect == UVC_VS_PROBE_CONTROL) {
                memcpy(ctrl, &mProbe, sizeof(struct uvc_streaming_control));
            } else {
                memcpy(ctrl, &mCommit, sizeof(struct uvc_streaming_control));
            }
            break;
        case UVC_GET_MAX:
            setStreamingControl(ctrl, &maxFormatTriplet);
            break;
        case UVC_GET_MIN:
        case UVC_GET_DEF:
            setStreamingControl(ctrl, &defaultFormatTriplet);
            break;
        case UVC_GET_RES:
            memset(ctrl, 0, sizeof(struct uvc_streaming_control));
            break;
        case UVC_GET_LEN:
            response->data[0] = 0x00;
            // UVC 1.5 spec section 4.3.1
            response->data[0] = 0x30;
            response->length = 2;
            break;
        case UVC_GET_INFO:
            // UVC 1.5 Spec Section 4.1.2 "Get Request"
            response->data[0] = 0x3;
            response->length = 1;
            break;
        default:
            ALOGE("%s requestType %u not supported", __FUNCTION__, requestType);
            break;
    }
}

void UVCProvider::UVCDevice::processSetupClassEvent(const struct usb_ctrlrequest* request,
                                                    struct uvc_request_data* response) {
    uint8_t interface = request->wIndex & 0xff;
    ALOGV("%s: Interface %u", __FUNCTION__, interface);

    if ((request->bRequestType & USB_RECIP_MASK) != USB_RECIP_INTERFACE) {
        ALOGE("Invalid bRequestType byte %u", request->bRequestType);
        return;
    }

    if (interface == mUVCProperties->controlInterfaceNumber) {
        processSetupControlEvent(request, response);
    } else if (interface == mUVCProperties->streaming.interfaceNumber) {
        processSetupStreamingEvent(request, response);
    }
}

void UVCProvider::UVCDevice::processSetupEvent(const struct usb_ctrlrequest* request,
                                               struct uvc_request_data* response) {
    uint8_t type = request->bRequestType;
    uint8_t requestCode = request->bRequest;
    uint16_t length = request->wLength;
    uint16_t index = request->wIndex;
    uint16_t value = request->wValue;
    ALOGV("%s: type %u requestCode %u length %u index %u value %u", __FUNCTION__, type, requestCode,
          length, index, value);
    switch (type & USB_TYPE_MASK) {
        case USB_TYPE_STANDARD:
            ALOGW("USB_TYPE_STANDARD request not being handled");
            break;
        case USB_TYPE_CLASS:
            processSetupClassEvent(request, response);
            break;
        default:
            ALOGE("%s: Unknown request type %u", __FUNCTION__, type);
    }
}

void UVCProvider::UVCDevice::setStreamingControl(struct uvc_streaming_control* streamingControl,
                                                 const FormatTriplet* req) {
    // frame and format index start from 1.
    uint8_t chosenFormatIndex = req->formatIndex > mUVCProperties->streaming.formats.size()
                                        ? mUVCProperties->streaming.formats.size()
                                        : req->formatIndex;
    const ConfigFormat& chosenFormat = mUVCProperties->streaming.formats[chosenFormatIndex - 1];
    uint8_t chosenFrameIndex = req->frameSizeIndex > chosenFormat.frames.size()
                                       ? chosenFormat.frames.size()
                                       : req->frameSizeIndex;
    const ConfigFrame& chosenFrame = chosenFormat.frames[chosenFrameIndex - 1];
    uint32_t reqFrameInterval = req->frameInterval;
    bool largerFound = false;
    // Choose the first frame interval >= requested. Frame intervals are expected to be in
    // ascending order..
    for (auto frameInterval : chosenFrame.intervals) {
        if (reqFrameInterval <= frameInterval) {
            reqFrameInterval = frameInterval;
            largerFound = true;
            break;
        }
    }
    if (!largerFound) {
        reqFrameInterval = chosenFrame.intervals[chosenFrame.intervals.size() - 1];
    }
    memset(streamingControl, 0, sizeof(*streamingControl));

    // TODO((b/267794640): Add documentation for magic numbers used here and elsewhere.
    streamingControl->bmHint = 1;
    streamingControl->bmFramingInfo = 3;
    streamingControl->bPreferedVersion = 1;
    streamingControl->bMaxVersion = 1;

    streamingControl->bFormatIndex = chosenFormatIndex;
    streamingControl->bFrameIndex = chosenFrameIndex;
    streamingControl->dwFrameInterval = reqFrameInterval;
    streamingControl->dwMaxPayloadTransferSize = USB_PAYLOAD_TRANSFER_SIZE;
    switch (chosenFormat.fcc) {
        case V4L2_PIX_FMT_YUYV:
        case V4L2_PIX_FMT_MJPEG:
            streamingControl->dwMaxVideoFrameSize = chosenFrame.width * chosenFrame.height * 2;
            break;
        default:
            ALOGE("%s Video format is not YUYV or MJPEG ??", __FUNCTION__);
    }
}

void UVCProvider::UVCDevice::commitControls() {
    const ConfigFormat& commitFormat = mUVCProperties->streaming.formats[mCommit.bFormatIndex - 1];
    const ConfigFrame& commitFrame = commitFormat.frames[mCommit.bFrameIndex - 1];
    mFps = (float)FRAME_INTERVAL_NUM / mCommit.dwFrameInterval;

    memset(&mV4l2Format, 0, sizeof(mV4l2Format));
    mV4l2Format.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    mV4l2Format.fmt.pix.width = commitFrame.width;
    mV4l2Format.fmt.pix.height = commitFrame.height;
    mV4l2Format.fmt.pix.pixelformat = commitFormat.fcc;
    mV4l2Format.fmt.pix.field = V4L2_FIELD_ANY;
    mV4l2Format.fmt.pix.sizeimage = mCommit.dwMaxVideoFrameSize;

    // Call ioctl VIDIOC_S_FMT which may change the fields in mV4l2Format
    if (ioctl(mUVCFd.get(), VIDIOC_S_FMT, &mV4l2Format) < 0) {
        ALOGE("%s Unable to set pixel format with the uvc gadget driver: %s", __FUNCTION__,
              strerror(errno));
        return;
    }
    ALOGV("%s controls committed frame width %u, height %u, format %u, sizeimage %u"
          " frame rate %u mjpeg fourcc %u",
          __FUNCTION__, mV4l2Format.fmt.pix.width, mV4l2Format.fmt.pix.height,
          mV4l2Format.fmt.pix.pixelformat, mV4l2Format.fmt.pix.sizeimage, mFps, V4L2_PIX_FMT_MJPEG);
}

void UVCProvider::UVCDevice::processDataEvent(const struct uvc_request_data* data) {
    const struct uvc_streaming_control* controlReq =
            reinterpret_cast<const struct uvc_streaming_control*>(&data->data);

    FormatTriplet triplet(controlReq->bFormatIndex, controlReq->bFrameIndex,
                          controlReq->dwFrameInterval);

    switch (mCurrentControlState) {
        case UVC_VS_PROBE_CONTROL:
            setStreamingControl(&mProbe, &triplet);
            break;
        case UVC_VS_COMMIT_CONTROL:
            setStreamingControl(&mCommit, &triplet);
            commitControls();
            break;
        default:
            ALOGE("mCurrentControlState is UNDEFINED");
            break;
    }
}

std::shared_ptr<Buffer> UVCProvider::UVCDevice::mapBuffer(uint32_t i) {
    struct v4l2_buffer buffer;
    memset(&buffer, 0, sizeof(buffer));

    buffer.index = i;
    buffer.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    buffer.memory = V4L2_MEMORY_MMAP;

    if (ioctl(mUVCFd.get(), VIDIOC_QUERYBUF, &buffer) < 0) {
        ALOGE("%s: Unable to query V4L2 buffer index %u from gadget driver: %s", __FUNCTION__, i,
              strerror(errno));
        return nullptr;
    }

    void* mem = mmap(/*addr*/ 0, buffer.length, PROT_READ | PROT_WRITE, MAP_SHARED, mUVCFd.get(),
                     buffer.m.offset);
    if (mem == nullptr) {
        ALOGE("%s: Unable to map V4L2 buffer index %u from gadget driver: %s", __FUNCTION__, i,
              strerror(errno));
        return nullptr;
    }
    ALOGV("%s: Allocated and mapped buffer at %p of size %u", __FUNCTION__, mem, buffer.length);
    return std::make_shared<V4L2Buffer>(mem, &buffer);
}

Status UVCProvider::UVCDevice::allocateAndMapBuffers(
        std::shared_ptr<Buffer>* consumerBuffer,
        std::vector<std::shared_ptr<Buffer>>* producerBuffers) {
    if (consumerBuffer == nullptr || producerBuffers == nullptr) {
        ALOGE("%s: ConsumerBuffer / producerBuffers are null", __FUNCTION__);
        return Status::ERROR;
    }
    *consumerBuffer = nullptr;
    producerBuffers->clear();
    struct v4l2_requestbuffers requestBuffers;
    memset(&requestBuffers, 0, sizeof(requestBuffers));

    requestBuffers.count = NUM_BUFFERS_ALLOC;
    requestBuffers.memory = V4L2_MEMORY_MMAP;
    requestBuffers.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;

    // Request the driver to allocate buffers
    if (ioctl(mUVCFd.get(), VIDIOC_REQBUFS, &requestBuffers) < 0) {
        ALOGE("%s: Unable to request V4L2 buffers from gadget driver: %s", __FUNCTION__,
              strerror(errno));
        return Status::ERROR;
    }

    if (requestBuffers.count != NUM_BUFFERS_ALLOC) {
        ALOGE("%s: Gadget driver could only allocate %u buffers instead of %u", __FUNCTION__,
              requestBuffers.count, NUM_BUFFERS_ALLOC);
        // TODO: We should probably be freeing buffers here
        return Status::ERROR;
    }

    // First buffer is consumer buffer
    *consumerBuffer = mapBuffer(0);
    if (consumerBuffer == nullptr) {
        ALOGE("%s: Mapping consumer buffer failed", __FUNCTION__);
        return Status::ERROR;
    }

    // The rest are producer buffers
    for (uint32_t i = 1; i < NUM_BUFFERS_ALLOC; i++) {
        std::shared_ptr<Buffer> buffer = mapBuffer(i);
        if (buffer == nullptr) {
            ALOGE("%s: Mapping producer buffer index %u failed", __FUNCTION__, i);
            *consumerBuffer = nullptr;
            producerBuffers->clear();
            return Status::ERROR;
        }
        producerBuffers->push_back(buffer);
    }
    return Status::OK;
}

Status UVCProvider::UVCDevice::unmapBuffer(std::shared_ptr<Buffer>& buffer) {
    if (buffer->getMem() != nullptr) {
        if (munmap(buffer->getMem(), buffer->getLength()) < 0) {
            ALOGE("%s munmap failed for buffer with pointer %p", __FUNCTION__, buffer->getMem());
            return Status::ERROR;
        }
    }
    return Status::OK;
}

void UVCProvider::UVCDevice::destroyBuffers(std::shared_ptr<Buffer>& consumerBuffer,
                                            std::vector<std::shared_ptr<Buffer>>& producerBuffers) {
    if (unmapBuffer(consumerBuffer) != Status::OK) {
        ALOGE("%s: Failed to unmap consumer buffer, continuing producer buffer cleanup anyway",
              __FUNCTION__);
    }
    for (auto& buf : producerBuffers) {
        if (unmapBuffer(buf) != Status::OK) {
            ALOGE("%s: Failed to unmap producer buffer, continuing buffer cleanup anyway",
                  __FUNCTION__);
        }
    }
    struct v4l2_requestbuffers zeroRequest;
    memset(&zeroRequest, 0, sizeof(zeroRequest));
    zeroRequest.count = 0;
    zeroRequest.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    zeroRequest.memory = V4L2_MEMORY_MMAP;

    if (ioctl(mUVCFd.get(), VIDIOC_REQBUFS, &zeroRequest) < 0) {
        ALOGE("%s: request to free buffers from uvc gadget driver failed %s", __FUNCTION__,
              strerror(errno));
    }
}

Status UVCProvider::UVCDevice::getFrameAndQueueBufferToGadgetDriver(bool firstBuffer) {
    ALOGV("%s begin", __FUNCTION__);
    // If first buffer also, call the STREAMON ioctl on uvc device
    int type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    if (firstBuffer) {
        if (ioctl(mUVCFd.get(), VIDIOC_STREAMON, &type) < 0) {
            ALOGE("%s: VIDIOC_STREAMON failed %s", __FUNCTION__, strerror(errno));
            return Status::ERROR;
        }
    }
    Buffer* buffer = mBufferManager->getFilledBufferAndSwap();
    struct v4l2_buffer v4L2Buffer = *(static_cast<V4L2Buffer*>(buffer)->getV4L2Buffer());
    ALOGV("%s got buffer, queueing it index %u", __FUNCTION__, v4L2Buffer.index);

    if (ioctl(mUVCFd.get(), VIDIOC_QBUF, &v4L2Buffer) < 0) {
        ALOGE("%s: VIDIOC_QBUF failed on gadget driver: %s", __FUNCTION__, strerror(errno));
        return Status::ERROR;
    }
    ALOGV("%s end", __FUNCTION__);
    return Status::OK;
}

void UVCProvider::UVCDevice::processStreamOffEvent() {
    int type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    if (ioctl(mUVCFd.get(), VIDIOC_STREAMOFF, &type) < 0) {
        ALOGE("%s: uvc gadget driver request to switch stream off failed %s", __FUNCTION__,
              strerror(errno));
        return;
    }

    mFrameProvider.reset();
    mBufferManager.reset();
    memset(&mCommit, 0, sizeof(mCommit));
    memset(&mProbe, 0, sizeof(mProbe));
    memset(&mV4l2Format, 0, sizeof(mV4l2Format));
    mFps = 0;
}

void UVCProvider::UVCDevice::processStreamOnEvent() {
    // Allocate V4L2 and map buffers for circulation between camera and UVCDevice
    mBufferManager = std::make_shared<BufferManager>(this);
    CameraConfig config;
    config.width = mV4l2Format.fmt.pix.width;
    config.height = mV4l2Format.fmt.pix.height;
    config.fcc = mV4l2Format.fmt.pix.pixelformat;
    config.fps = mFps;

    mFrameProvider = std::make_shared<SdkFrameProvider>(mWeakThiz, mBufferManager, config);
    mFrameProvider->setStreamConfig();
    mFrameProvider->startStreaming();

    // Queue first buffer to start the stream
    if (getFrameAndQueueBufferToGadgetDriver(true) != Status::OK) {
        ALOGE("%s: Queueing first buffer to gadget driver failed, stream not started",
              __FUNCTION__);
        return;
    }
    auto parent = mParent.lock();
    parent->watchStreamEvent();
}

UVCProvider::UVCProvider(JNIEnv* env, JavaVM* jvm, jobject weakThiz,
                         DeviceAsWebcamServiceManager* mgr)
    : mJVM(jvm), mDawMgr(mgr) {
    mWeakThiz = env->NewGlobalRef(weakThiz);
}

UVCProvider::~UVCProvider() {
    // JNIEnv *env = nullptr;
    // ScopedAttach attach(mJVM, &env);
    if (mUVCListenerThread.joinable()) {
        mListenToUVCFds.store(false);
        mUVCListenerThread.join();
    }
    if (mUVCDevice) {
        int fd = mUVCDevice->getUVCFd();
        if (fd >= 0) {
            mEpollW.remove(fd);
        }
    }
    mUVCDevice = nullptr;
    // env->DeleteGlobalRef(mWeakThiz);
}

Status UVCProvider::init() {
    return mEpollW.init();
}

void UVCProvider::UVCDevice::processStreamEvent() {
    // Dequeue producer buffer
    struct v4l2_buffer v4L2Buffer;
    memset(&v4L2Buffer, 0, sizeof(v4L2Buffer));
    v4L2Buffer.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    v4L2Buffer.memory = V4L2_MEMORY_MMAP;

    if (ioctl(mUVCFd.get(), VIDIOC_DQBUF, &v4L2Buffer) < 0) {
        ALOGE("%s: VIDIOC_DQBUF failed %s", __FUNCTION__, strerror(errno));
        return;
    }
    // Get camera frame and queue it to gadget driver
    if (getFrameAndQueueBufferToGadgetDriver() != Status::OK) {
        return;
    }
}

void UVCProvider::processUVCEvent() {
    struct v4l2_event event;
    memset(&event, 0, sizeof(event));
    int deviceFd = mUVCDevice->getUVCFd();

    if (ioctl(deviceFd, VIDIOC_DQEVENT, &event) < 0) {
        ALOGE("%s Failed to dequeue V4L2 event : %s", __FUNCTION__, strerror(errno));
        return;
    }
    struct uvc_event* uvcEvent = reinterpret_cast<struct uvc_event*>(&event.u.data);
    struct uvc_request_data uvcResponse;
    memset(&uvcResponse, 0, sizeof(uvcResponse));
    switch (event.type) {
        case UVC_EVENT_CONNECT:
            return;
        case UVC_EVENT_DISCONNECT:
            ALOGI("%s Disconnect event", __FUNCTION__);
            stopService();
            return;
        case UVC_EVENT_SETUP:
            // ALOGV since we get multiple setup events in one session
            ALOGV(" %s: Setup event ", __FUNCTION__);
            mUVCDevice->processSetupEvent(&(uvcEvent->req), &uvcResponse);
            break;
        case UVC_EVENT_DATA:
            ALOGV(" %s: Data event", __FUNCTION__);
            mUVCDevice->processDataEvent(&(uvcEvent->data));
            return;
        case UVC_EVENT_STREAMON:
            ALOGI("%s STREAMON event", __FUNCTION__);
            mUVCDevice->processStreamOnEvent();
            return;
        case UVC_EVENT_STREAMOFF:
            ALOGI("%s STREAMOFF event", __FUNCTION__);
            mUVCDevice->processStreamOffEvent();
            mEpollW.modify(mUVCDevice->getUVCFd(), EPOLLPRI);
            return;
        default:
            ALOGI(" UVC Event unsupported %u", event.type);
            break;
    }

    if (ioctl(deviceFd, UVCIOC_SEND_RESPONSE, &uvcResponse) < 0) {
        ALOGE("%s Unable to send response to uvc gadget driver %s", __FUNCTION__, strerror(errno));
        return;
    }
}

void UVCProvider::watchStreamEvent() {
    mEpollW.modify(mUVCDevice->getUVCFd(), EPOLLPRI | EPOLLOUT);
}

void UVCProvider::ListenToUVCFds() {
    // For UVC events
    ALOGI("%s Start to listen to device fd %d", __FUNCTION__, mUVCDevice->getUVCFd());
    mEpollW.add(mUVCDevice->getUVCFd(), EPOLLPRI);
    // For stream events : dequeue and queue buffers
    while (mListenToUVCFds.load()) {
        Events events = mEpollW.wait();
        for (auto event : events) {
            if (event.events & EPOLLPRI) {
                processUVCEvent();
            }
            if (event.events & EPOLLOUT) {
                if (mUVCDevice != nullptr) {
                    mUVCDevice->processStreamEvent();
                } else {
                    ALOGW("mUVCDevice is null we've disconnected");
                }
            } else {
                ALOGW("Which event fd is %d ? event %u", event.data.fd, event.events);
            }
        }
    }
}

void UVCProvider::startUVCListenerThread() {
    mListenToUVCFds.store(true);
    mUVCListenerThread = std::thread(&UVCProvider::ListenToUVCFds, this);
    ALOGI("Started new UVCListenerThread");
}
void UVCProvider::stopAndWaitForUVCListenerThread() {
    // This thread stays alive till stopService is called.
    if (mUVCListenerThread.joinable()) {
        mListenToUVCFds.store(false);
        mUVCListenerThread.join();
    }
}

Status UVCProvider::startService() {
    // Resets old state for epoll since this is a new start for the service.
    mEpollW.init();
    if (mUVCDevice != nullptr) {
        mUVCDevice->closeUVCFd();
    }
    mUVCDevice = std::make_shared<UVCDevice>(mWeakThiz, shared_from_this());
    if (!mUVCDevice->isInited()) {
        return Status::ERROR;
    }
    stopAndWaitForUVCListenerThread();
    startUVCListenerThread();
    return Status::OK;
}

void UVCProvider::stopService() {
    mUVCDevice->processStreamOffEvent();
    mEpollW.remove(mUVCDevice->getUVCFd());
    // TODO(b/267794640): Delegate to central jni caller
    SdkFrameProvider::stopService(mWeakThiz);
    mUVCDevice = nullptr;
    mListenToUVCFds.store(false);
    mDawMgr->recordServiceStopped();
}

}  // namespace webcam
}  // namespace android
