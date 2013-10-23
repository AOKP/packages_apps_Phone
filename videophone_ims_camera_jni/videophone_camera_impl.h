/* Copyright (c) 2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#ifndef VT_CAMERA_JNI_INTERFACE_H
#define VT_CAMERA_JNI_INTERFACE_H

#include <jni.h>
#include <cutils/log.h>

typedef int16_t (*ImsCameraOpenFun)(uint32_t);
typedef int16_t (*ImsCamImplInt16VoidFunc)();
typedef int16_t (*ImsCameraSetPreviewSurface)(JNIEnv *, jobject);
typedef int16_t (*ImsCameraSetDisplayOrientation)(uint32_t);

typedef bool (*ImsCameraIsZoomSupported)();
typedef void (*ImsCameraSetZoom)(uint32_t);

typedef struct {
        int width;
        int height;
}Resolution;

typedef enum {
        INVALID_PARAM = 0,
        SET_FPS,
        SET_RESOLUTION
}eParamType;

typedef union {
        int fps;
        Resolution cameraResolution;
}CameraParams;

typedef struct {
        eParamType type;
        CameraParams params;
}CameraParamContainer;


typedef int16_t (*ImsCameraSetParameter)(CameraParamContainer);
typedef CameraParams (*ImsCameraGetParameter)(jobject);

struct ImsCameraImplApis {
        ImsCameraOpenFun cameraOpen;
        ImsCamImplInt16VoidFunc cameraRelease;
        ImsCamImplInt16VoidFunc startCameraPreview;
        ImsCamImplInt16VoidFunc stopCameraPreview;
        ImsCamImplInt16VoidFunc startCameraRecording;
        ImsCamImplInt16VoidFunc stopCameraRecording;
        ImsCameraSetPreviewSurface setPreviewTexture;
        ImsCameraSetDisplayOrientation setDisplayOrientation;
        ImsCameraSetParameter setCameraParameter;
        ImsCameraGetParameter getCameraParameter;
        ImsCameraIsZoomSupported isZoomSupported;
        ImsCamImplInt16VoidFunc getMaxZoom;
        ImsCameraSetZoom setZoom;
};

#endif
