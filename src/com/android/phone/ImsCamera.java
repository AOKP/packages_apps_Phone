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
 *     * Neither the name of The Linux Foundation. nor the names of its
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

package com.android.phone;

import android.graphics.SurfaceTexture;
import android.util.Log;

/**
 * The class is used to hold an {@code android.hardware.Camera} instance.
 * <p>
 * The {@code open()} and {@code release()} calls are similar to the ones in
 * {@code android.hardware.Camera}.
 */

public class ImsCamera {
    private static final String TAG = "VideoCallImsCamera";
    private static final boolean DBG = true;
    private static final short IMS_CAMERA_OPERATION_SUCCESS = 0;

    static {
        System.loadLibrary("imscamera_jni");
    }

    public static native short native_open(int cameraId);

    public native short native_release();

    public native short native_startPreview();

    public native short native_stopPreview();

    public native short native_startRecording();

    public native short native_stopRecording();

    public native short native_setPreviewTexture(SurfaceTexture st);

    public native short native_setDisplayOrientation(int rotation);

    public native boolean native_isZoomSupported();

    public native int native_getMaxZoom();

    public native void native_setZoom(int zoomValue);

    public native short native_setPreviewSize(int width, int height);

    public native short native_setPreviewFpsRange(short fps);

    public static ImsCamera open(int cameraId) throws Exception {
        Log.d(TAG, "open cameraId=" + cameraId);
        short error = native_open(cameraId);
        if (error != IMS_CAMERA_OPERATION_SUCCESS) {
            Log.e(TAG, "open cameraId=" + cameraId + " failed with error=" + error);
            throw new Exception();
        } else {
            return new ImsCamera();
        }
    }

    public short release() {
        if(DBG) log("release");
        short error = native_release();
        logIfError("release", error);
        return error;
    }

    public short startPreview() {
        if (DBG) log("startPreview");
        short error = native_startPreview();
        logIfError("startPreview", error);
        return error;
    }

    public short stopPreview() {
        if(DBG) log("stopPreview");
        short error = native_stopPreview();
        logIfError("stopPreview", error);
        return error;
    }

    public short startRecording() {
        if(DBG) log("startRecording");
        short error = native_startRecording();
        logIfError("startRecording", error);
        return error;
    }

    public short stopRecording() {
        if(DBG) log("stopRecording");
        short error = native_stopRecording();
        logIfError("stopRecording", error);
        return error;
    }

    public short setPreviewTexture(SurfaceTexture st) {
        if(DBG) log("setPreviewTexture");
        short error = native_setPreviewTexture(st);
        logIfError("setPreviewTexture", error);
        return error;
    }

    public short setDisplayOrientation(int rotation) {
        if(DBG) log("setDisplayOrientation rotation=" + rotation);
        short error = native_setDisplayOrientation(rotation);
        logIfError("setDisplayOrientation", error);
        return error;
    }

    public boolean isZoomSupported() {
        boolean result = native_isZoomSupported();
        if(DBG) log("isZoomSupported result=" + result);
        return result;
    }

    public int getMaxZoom() {
        int result = native_getMaxZoom();
        if(DBG) log("getMaxZoom result = " + result);
        return result;
    }

    public void setZoom(int zoomValue) {
        if (DBG) log("setZoom " + zoomValue);
        native_setZoom(zoomValue);
    }

    public short setPreviewSize(int width, int height) {
        if(DBG) log("setPreviewSize");
        short error = native_setPreviewSize(width, height);
        logIfError("setPreviewSize", error);
        return error;
    }

    public short setPreviewFpsRange(short fps) {
        if(DBG) log("setPreviewFpsRange");
        short error = native_setPreviewFpsRange(fps);
        logIfError("setPreviewFpsRange", error);
        return error;
    }

    private void log(String msg) {
        Log.d(TAG, msg);
    }

    private void loge(String msg) {
        Log.e(TAG, msg);
    }

    private void logIfError(String methodName, short error) {
        if (error != IMS_CAMERA_OPERATION_SUCCESS) {
            loge(methodName + " failed with error=" + error);
        }
    }
}
