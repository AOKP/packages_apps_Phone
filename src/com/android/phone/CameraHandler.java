/* Copyright (c) 2012, The Linux Foundation. All rights reserved.
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

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.Size;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;

import java.io.IOException;
import java.util.List;

/**
 * The class is used to hold an {@code android.hardware.Camera} instance.
 * <p>
 * The {@code open()} and {@code release()} calls are similar to the ones in
 * {@code android.hardware.Camera}.
 */

public class CameraHandler implements Camera.PreviewCallback{
    public static final int CAMERA_UNKNOWN = -1;
    private static final String TAG = "VideoCallCameraManager";
    private static final boolean DBG = true;
    private android.hardware.Camera mCameraDevice;
    private int mNumberOfCameras;
    private int mCameraId = CAMERA_UNKNOWN; // current camera id
    private int mBackCameraId = CAMERA_UNKNOWN, mFrontCameraId = CAMERA_UNKNOWN;
    private CameraInfo[] mInfo;
    private CameraState mCameraState = CameraState.CAMERA_CLOSED;
    private Parameters mParameters;
    private Context mContext;

    // Use a singleton.
    private static CameraHandler mInstance;

    /**
     * Enum that defines the various camera states
     */
    public enum CameraState {
        CAMERA_CLOSED, // Camera is not yet opened or is closed
        PREVIEW_STOPPED, // Camera is open and preview not started
        PREVIEW_STARTED, // Preview is active
    };

    /**
     * This method returns the single instance of CameraManager object
     * @param mContext
     */
    public static synchronized CameraHandler getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new CameraHandler(context);
        }
        return mInstance;
    }

    /**
     * Private constructor for CameraManager
     * @param mContext
     */
    private CameraHandler(Context context) {
        mContext = context;
        mNumberOfCameras = android.hardware.Camera.getNumberOfCameras();
        log("Number of cameras supported is: " + mNumberOfCameras);
        mInfo = new CameraInfo[mNumberOfCameras];
        for (int i = 0; i < mNumberOfCameras; i++) {
            mInfo[i] = new CameraInfo();
            android.hardware.Camera.getCameraInfo(i, mInfo[i]);
            if (mBackCameraId == CAMERA_UNKNOWN
                    && mInfo[i].facing == CameraInfo.CAMERA_FACING_BACK) {
                mBackCameraId = i;
                log("Back camera ID is: " + mBackCameraId);
            }
            if (mFrontCameraId == CAMERA_UNKNOWN
                    && mInfo[i].facing == CameraInfo.CAMERA_FACING_FRONT) {
                mFrontCameraId = i;
                log("Front camera ID is: " + mFrontCameraId);
            }
        }
    }

    /**
     * Return the number of cameras supported by the device
     *
     * @return number of cameras
     */
    public int getNumberOfCameras() {
        return mNumberOfCameras;
    }

    /**
     * Open the camera hardware
     *
     * @param cameraId front or the back camera to open
     * @return true if the camera was opened successfully
     * @throws Exception
     */
    public synchronized boolean open(int cameraId)
            throws Exception {
        // Check if device policy has disabled the camera.
        DevicePolicyManager dpm = (DevicePolicyManager) mContext.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            throw new Exception("DevicePolicyManager not available");
        }

        if (dpm.getCameraDisabled(null)) {
            throw new Exception("Camera is disabled");
        }

        if (mCameraDevice != null && mCameraId != cameraId) {
            mCameraDevice.release();
            mCameraDevice = null;
            mCameraId = CAMERA_UNKNOWN;
        }
        if (mCameraDevice == null) {
            try {
                if (DBG) log("opening camera " + cameraId);
                mCameraDevice = android.hardware.Camera.open(cameraId);
                mCameraId = cameraId;
            } catch (RuntimeException e) {
                loge("fail to connect Camera" + e);
                throw new Exception(e);
            }
            mParameters = mCameraDevice.getParameters();
        } else {
            try {
                mCameraDevice.reconnect();
            } catch (IOException e) {
                loge("reconnect failed.");
                throw new Exception(e);
            }
            setCameraParameters(mParameters);
        }
        mCameraState = CameraState.PREVIEW_STOPPED;
        return true;
    }

    /**
     * Start the camera preview if camera was opened previously
     *
     * @param mSurfaceTexture Surface on which to draw the camera preview
     * @throws IOException
     */
    public void startPreview(SurfaceTexture mSurfaceTexture) throws IOException {
        if (mCameraState != CameraState.PREVIEW_STOPPED) {
            loge("startPreview: Camera state " + mCameraState
                    + " is not the right camera state for this operation");
            return;
        }
        if (mCameraDevice != null) {
            if (DBG) log("starting preview");

            // Set the SurfaceTexture to be used for preview
            mCameraDevice.setPreviewTexture(mSurfaceTexture);

            // Set the Preview Call Back to show the camera frames on UI
            mCameraDevice.setPreviewCallback(this);

            setDisplayOrientation();
            mCameraDevice.startPreview();
            mCameraState = CameraState.PREVIEW_STARTED;
        }
    }

    /**
     * Close the camera hardware if the camera was opened previously
     */
    public synchronized void close() {
        if (mCameraState == CameraState.CAMERA_CLOSED) {
            loge("close: Camera state " + mCameraState
                    + " is not the right camera state for this operation");
            return;
        }

        if (mCameraDevice != null) {
            if (DBG) log("closing camera");
            mCameraDevice.stopPreview(); // Stop preview
            mCameraDevice.release();
        }
        mCameraDevice = null;
        mParameters = null;
        mCameraId = CAMERA_UNKNOWN;
        mCameraState = CameraState.CAMERA_CLOSED;
    }

    /**
     * Stop the camera preview if the camera is open and the preview is not
     * already started
     */
    public void stopPreview() {
        if (mCameraState != CameraState.PREVIEW_STARTED) {
            loge("stopPreview: Camera state " + mCameraState
                    + " is not the right camera state for this operation");
            return;
        }
        if (mCameraDevice != null) {
            if (DBG) log("stopping preview");
            mCameraDevice.setPreviewCallback(null);
            mCameraDevice.stopPreview();
        }
        mCameraState = CameraState.PREVIEW_STOPPED;
    }

    /**
     * Return the camera parameters that specifies the current settings of the
     * camera
     *
     * @return camera parameters
     */
    public Parameters getCameraParameters() {
        if (mCameraDevice == null) {
            return null;
        }
        return mParameters;
    }

    /**
     * Set the camera parameters
     *
     * @param parameters to be set
     */
    public void setCameraParameters(Parameters parameters) {
        log("setCameraParameters mCameraDevice=" + mCameraDevice + "parameters =" + parameters);
        if (mCameraDevice == null || parameters == null) {
            return;
        }
        mParameters = parameters;
        mCameraDevice.setParameters(parameters);
    }

    /**
     * Get the camera ID for the back camera
     *
     * @return camera ID
     */
    public int getBackCameraId() {
        return mBackCameraId;
    }

    /**
     * Get the camera ID for the front camera
     *
     * @return camera ID
     */
    public int getFrontCameraId() {
        return mFrontCameraId;
    }

    /**
     * Return the current camera state
     *
     * @return current state of the camera state machine
     */
    public CameraState getCameraState() {
        return mCameraState;
    }

    /**
     * Set the display texture for the camera
     *
     * @param surfaceTexture
     */
    public void setDisplay(SurfaceTexture surfaceTexture) {
        // Set the SurfaceTexture to be used for preview
        if (mCameraDevice == null) return;
        try {
            mCameraDevice.setPreviewTexture(surfaceTexture);
        } catch (IOException e) {
            throw new RuntimeException("setPreviewDisplay failed", e);
        }
    }

    /**
     * Set the texture view for the camera
     *
     * @param textureView
     */
    public void setDisplay(TextureView textureView) {
        // Set the SurfaceTexture to be used for preview
        if (mCameraDevice == null) return;
        try {
            mCameraDevice.setPreviewTexture(textureView.getSurfaceTexture());
        } catch (IOException e) {
            throw new RuntimeException("setPreviewDisplay failed", e);
        }
    }

    /**
     * Gets the supported preview sizes.
     *
     * @return a list of Size object. This method will always return a list
     *         with at least one element.
     */
    public List<Size> getSupportedPreviewSizes() {
        if (mCameraDevice == null) return null;
        return mCameraDevice.getParameters().getSupportedPreviewSizes();
    }

    /**
     * Returns the direction of the currently open camera
     *
     * @return one of the following possible values
     *        - CameraInfo.CAMERA_FACING_FRONT
     *        - CameraInfo.CAMERA_FACING_BACK
     *        - CAMERA_UNKNOWN - No Camera active
     */
    public int getCameraDirection() {
        if (mCameraDevice == null) return CAMERA_UNKNOWN;

        return (mCameraId == mFrontCameraId) ? CameraInfo.CAMERA_FACING_FRONT
                : CameraInfo.CAMERA_FACING_BACK;
    }

    /**
     * Set the camera display orientation based on the screen rotation
     * and the camera direction
     */
    public void setDisplayOrientation() {
        android.hardware.Camera.CameraInfo info =
                new android.hardware.Camera.CameraInfo();
        int result;
        int degrees = 0;
        int rotation = 0;

        // Get display rotation
        WindowManager wm = (WindowManager) mContext.getSystemService(
                Context.WINDOW_SERVICE);
        if (wm == null) {
            loge("WindowManager not available");
            return;
        }

        rotation = wm.getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_0: degrees = 0; break;
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
            default:
                loge("setDisplayOrientation: Unexpected rotation: " + rotation);
        }

        android.hardware.Camera.getCameraInfo(mCameraId, info);
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;  // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360;
        }
        mCameraDevice.setDisplayOrientation(result);
    }

    /**
     * Called as preview frames are displayed. The frames are passed to IMS DPL
     * layer to be sent to the far end device
     */
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (MediaHandler.canSendPreview()) {
            MediaHandler.sendPreviewFrame(data);
        }
    }

    private void log(String msg) {
        Log.d(TAG, msg);
    }

    private void loge(String msg) {
        Log.e(TAG, msg);
    }
}
