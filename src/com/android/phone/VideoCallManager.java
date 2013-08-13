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

package com.android.phone;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.android.phone.CameraHandler.CameraState;
import com.android.phone.CvoHandler.CvoEventListener;

import java.io.IOException;

/**
 * Provides an interface for the applications to interact with Camera for the
 * near end preview and sending the frames to the far end and also with Media
 * engine to render the far end video during a Video Call Session.
 */
public class VideoCallManager {
    private static final String TAG = "VideoCallManager";
    private static VideoCallManager mInstance; // Use a singleton
    private CameraHandler mCameraHandler;
    private MediaHandler mMediaHandler;
    private CvoHandler mCvoHandler;
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar;
            switch (msg.what) {
                case CVO_MODE_REQUEST_CHANGED:
                    ar = (AsyncResult) msg.obj;
                    if (ar != null && ar.result != null && ar.exception == null) {
                        boolean start = (Boolean) ar.result;
                        mCvoHandler.startOrientationListener(start);
                    }
                    break;
                case CVO_INFO_CHANGED:
                    ar = (AsyncResult) msg.obj;
                    if (ar != null && ar.result != null && ar.exception == null) {
                        int orientation = (Integer) ar.result;
                        mMediaHandler.sendCvoInfo(orientation);
                        notifyCvoClient(orientation);
                    }
                    break;
            }
        }
    };
    private CvoEventListener mCvoEventListener;

    public static final int MEDIA_INIT_SUCCESS = 0;
    private static final int CVO_MODE_REQUEST_CHANGED = 0;
    private static final int CVO_INFO_CHANGED = 2;

    /** @hide */
    private VideoCallManager(Context context) {
        log("Instantiating VideoCallManager");
        mCameraHandler = CameraHandler.getInstance(context);
        mMediaHandler = MediaHandler.getInstance();
        mCvoHandler = CvoHandler.getInstance(context);
        mMediaHandler.registerForCvoModeRequestChanged(mHandler, CVO_MODE_REQUEST_CHANGED, null);
        mCvoHandler.registerForCvoInfoChange(mHandler, CVO_INFO_CHANGED, null);
    }

    private void notifyCvoClient(int orientation) {
        int angle = mCvoHandler
                .convertMediaOrientationToActualAngle(orientation);
        log("handleMessage Device orientation angle=" + angle);
        if (mCvoEventListener != null) {
            mCvoEventListener.onDeviceOrientationChanged(angle);
        }
    }

    /**
     * This method returns the single instance of VideoCallManager object
     *
     * @param mContext
     */
    public static synchronized VideoCallManager getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new VideoCallManager(context);
        }
        return mInstance;
    }

    /**
     * Initialize the Media
     */
    public int mediaInit() {
        return mMediaHandler.init();
    }

    /**
     * Deinitialize the Media
     */
    public void mediaDeInit() {
        MediaHandler.deInit();
    }

    /**
     * Send the SurfaceTexture to Media module
     * @param st SurfaceTexture to be passed
     */
    public void setFarEndSurface(SurfaceTexture st) {
        MediaHandler.setSurface(st);
    }

    /**
     * Send the SurfaceTexture to Media module
     */
    public void setFarEndSurface() {
        MediaHandler.setSurface();
    }

    /**
     * Get negotiated height
     */
    public int getNegotiatedHeight() {
        return MediaHandler.getNegotiatedHeight();
    }

    /**
     * Get negotiated width
     */
    public int getNegotiatedWidth() {
        return MediaHandler.getNegotiatedWidth();
    }

    /**
     * Get negotiated width
     */
    public int getUIOrientationMode() {
        return mMediaHandler.getUIOrientationMode();
    }

    /**
     * Get negotiated FPS
     */
    public short getNegotiatedFps() {
        return MediaHandler.getNegotiatedFps();
    }

    public boolean isCvoModeEnabled() {
        return mMediaHandler.isCvoModeEnabled();
    }

    /**
     * Return the number of cameras supported by the device
     *
     * @return number of cameras
     */
    public int getNumberOfCameras() {
        return mCameraHandler.getNumberOfCameras();
    }

    /**
     * Open the camera hardware
     *
     * @param cameraId front or the back camera to open
     * @return true if the camera was opened successfully
     * @throws Exception
     */
    public synchronized boolean openCamera(int cameraId) throws Exception {
        return mCameraHandler.open(cameraId);
    }

    /**
     * Start the camera preview if camera was opened previously
     *
     * @param mSurfaceTexture Surface on which to draw the camera preview
     * @throws IOException
     */
    public void startCameraPreview(SurfaceTexture surfaceTexture) throws IOException {
        mCameraHandler.startPreview(surfaceTexture);
    }

    /**
     * Close the camera hardware if the camera was opened previously
     */
    public void closeCamera() {
        mCameraHandler.close();
    }

    /**
     * Stop the camera preview if the camera is open and the preview is not
     * already started
     */
    public void stopCameraPreview() {
        mCameraHandler.stopPreview();
    }

    /**
     * Get the camera ID for the back camera
     *
     * @return camera ID
     */
    public int getBackCameraId() {
        return mCameraHandler.getBackCameraId();
    }

    /**
     * Get the camera ID for the front camera
     *
     * @return camera ID
     */
    public int getFrontCameraId() {
        return mCameraHandler.getFrontCameraId();
    }

    /**
     * Return the current camera state
     *
     * @return current state of the camera state machine
     */
    public CameraState getCameraState() {
        return mCameraHandler.getCameraState();
    }

    /**
     * Set the display texture for the camera
     *
     * @param surfaceTexture
     */
    public void setDisplay(SurfaceTexture surfaceTexture) {
        mCameraHandler.setDisplay(surfaceTexture);
    }

    /**
     * Returns the direction of the currently open camera
     *
     * @return one of the following possible values
     *        - CameraInfo.CAMERA_FACING_FRONT
     *        - CameraInfo.CAMERA_FACING_BACK
     *        - -1 - No Camera active
     */
    public int getCameraDirection() {
        return mCameraHandler.getCameraDirection();
    }

    /**
     * Set the camera display orientation based on the screen rotation and the
     * camera direction
     */
    void setCameraDisplayOrientation() {
        mCameraHandler.setDisplayOrientation();
    }

    public ImsCamera getImsCameraInstance() {
        return mCameraHandler.getImsCameraInstance();
    }

    public void startCameraRecording() {
        mCameraHandler.startCameraRecording();
    }

    public void stopCameraRecording() {
        mCameraHandler.stopCameraRecording();
    }

    public void setOnParamReadyListener(VideoCallPanel.ParamReadyListener listener) {
        mMediaHandler.setMediaEventListener(listener);
    }

    /*
     * Setup a CVO Event listener for triggering UI callbacks like
     * onDeviceOrientationChanged to be invoked directly
     */
    public void setCvoEventListener(CvoEventListener listener) {
        log("setCvoEventListener");
        // TODO: Create a list of listeners or do not allow over-write
        mCvoEventListener = listener;
    }

    public void startOrientationListener(boolean start) {
        if (isCvoModeEnabled()) {
            mCvoHandler.startOrientationListener(start);
        }
    }

    private void log(String msg) {
        Log.d(TAG, msg);
    }
}
