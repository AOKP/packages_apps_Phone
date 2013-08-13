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

import android.content.pm.ActivityInfo;
import android.graphics.SurfaceTexture;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.util.Log;

/**
 * Provides an interface to handle the media part of the video telephony call
 */
public class MediaHandler extends Handler {

    public static final int DPL_INIT_SUCCESSFUL = 0;
    public static final int DPL_INIT_FAILURE = -1;
    public static final int DPL_INIT_MULTIPLE = -2;

    private static final String TAG = "VideoCall_MediaHandler";

    private static SurfaceTexture mSurface;

    private static boolean mInitCalledFlag = false;

    private static native int nativeInit();
    private static native void nativeDeInit();
    private static native void nativeHandleRawFrame(byte[] frame);
    private static native int nativeSetSurface(SurfaceTexture st);
    private static native void nativeSetDeviceOrientation(int orientation);
    private static native short nativeGetNegotiatedFPS();
    private static native int nativeGetNegotiatedHeight();
    private static native int nativeGetNegotiatedWidth();
    private static native int nativeGetUIOrientationMode();
    private static native void nativeRegisterForMediaEvents(MediaHandler instance);

    public static final int PARAM_READY_EVT = 1;
    public static final int START_READY_EVT = 2;
    public static final int DISPLAY_MODE_EVT = 5;

    protected final RegistrantList mDisplayModeEventRegistrants
            = new RegistrantList();

    // UI Orientation Modes
    private static final int LANDSCAPE_MODE = 1;
    private static final int PORTRAIT_MODE = 2;
    private static final int CVO_MODE = 3;

    /*
     * Initializing default negotiated parameters to a working set of valuesso
     * that the application does not crash in case we do not get the Param ready
     * event
     */
    private static int mNegotiatedHeight = 240;
    private static int mNegotiatedWidth = 320;
    private static int mUIOrientationMode = PORTRAIT_MODE;
    private static short mNegotiatedFps = 20;

    // Use a singleton
    private static MediaHandler mInstance;

    private MediaEventListener mMediaEventListener;
    public RegistrantList mCvoModeOnRegistrant = new RegistrantList();

    /**
     * This method returns the single instance of MediaHandler object *
     */
    public static synchronized MediaHandler getInstance() {
        if (mInstance == null) {
            mInstance = new MediaHandler();
        }
        return mInstance;
    }

    /**
     * Private constructor for MediaHandler
     */
    private MediaHandler() {
    }

    public interface MediaEventListener {
        void onParamReadyEvent();
        void onDisplayModeEvent();
        void onStartReadyEvent();
    }

    static {
        System.loadLibrary("vt_jni");
    }

    /**
     * Initialize Media
     * @return
       DPL_INIT_SUCCESSFUL         0  initialization is successful.
       DPL_INIT_FAILURE   -1  error in initialization of QMI or other components.
       DPL_INIT_MULTIPLE   -2  trying to initialize an already initialized library.
     */
    public int init() {
        if (!mInitCalledFlag) {
            int error = nativeInit();
            Log.d(TAG, "init called error = " + error);
            switch (error) {
                case DPL_INIT_SUCCESSFUL:
                    mInitCalledFlag = true;
                    registerForMediaEvents(this);
                    break;
                case DPL_INIT_FAILURE:
                    mInitCalledFlag = false;
                    break;
                case DPL_INIT_MULTIPLE:
                    mInitCalledFlag = true;
                    Log.e(TAG, "Dpl init is called multiple times");
                    error = DPL_INIT_SUCCESSFUL;
                    break;
            }
            return error;
        }

        // Dpl is already initialized. So return success
        return DPL_INIT_SUCCESSFUL;
    }

    /*
     * Deinitialize Media
     */
    public static void deInit() {
        Log.d(TAG, "deInit called");
        nativeDeInit();
        mInitCalledFlag = false;
    }

    public void sendCvoInfo(int orientation) {
        Log.d(TAG, "sendCvoInfo orientation=" + orientation);
        nativeSetDeviceOrientation(orientation);
    }

    /**
     * Send the camera preview frames to the media module to be sent to the far
     * end party
     * @param frame raw frames from the camera
     */
    public static void sendPreviewFrame(byte[] frame) {
        nativeHandleRawFrame(frame);
    }

    /**
     * Send the SurfaceTexture to media module
     * @param st
     */
    public static void setSurface(SurfaceTexture st) {
        Log.d(TAG, "setSurface(SurfaceTexture " + st + ")");
        mSurface = st;
        nativeSetSurface(st);
    }

    /**
     * Send the SurfaceTexture to media module. This should be called only for
     * re-sending an already created surface
     */
    public static void setSurface() {
        Log.d(TAG, "setSurface()");
        if (mSurface == null) {
            Log.e(TAG, "sSurface is null. So not passing it down");
            return;
        }
        nativeSetSurface(mSurface);
    }

    /**
     * Get Negotiated Height
     */
    public static int getNegotiatedHeight() {
        Log.d(TAG, "Negotiated Height = " + mNegotiatedHeight);
        return mNegotiatedHeight;
    }

    /**
     * Get Negotiated Width
     */
    public static int getNegotiatedWidth() {
        Log.d(TAG, "Negotiated Width = " + mNegotiatedWidth);
        return mNegotiatedWidth;
    }

    /**
     * Get Negotiated Width
     */
    public int getUIOrientationMode() {
        Log.d(TAG, "UI Orientation Mode = " + mUIOrientationMode);
        return mUIOrientationMode;
    }

    public static short getNegotiatedFps() {
        return mNegotiatedFps;
    }

    /**
     * Register for event that will invoke
     * {@link MediaHandler#onMediaEvent(int)}
     */
    private static void registerForMediaEvents(MediaHandler instance) {
        Log.d(TAG, "Registering for Media Callback Events");
        nativeRegisterForMediaEvents(instance);
    }

    public void setMediaEventListener(MediaEventListener listener) {
        mMediaEventListener = listener;
    }

    /**
     * Callback method that is invoked when Media events occur
     */
    public void onMediaEvent(int eventId) {
        Log.d(TAG, "onMediaEvent eventId = " + eventId);
        switch (eventId) {
            case PARAM_READY_EVT:
                Log.d(TAG, "Received PARAM_READY_EVT. Updating negotiated values");
                mNegotiatedHeight = nativeGetNegotiatedHeight();
                mNegotiatedWidth = nativeGetNegotiatedWidth();
                mNegotiatedFps = nativeGetNegotiatedFPS();
                if (mMediaEventListener != null) {
                    mMediaEventListener.onParamReadyEvent();
                }
                break;
            case START_READY_EVT:
                Log.d(TAG, "Received START_READY_EVT. Camera frames can be sent now");
                if (mMediaEventListener != null) {
                    mMediaEventListener.onStartReadyEvent();
                }
                break;
            case DISPLAY_MODE_EVT:
                mUIOrientationMode = nativeGetUIOrientationMode();
                Log.d(TAG, "Received DISPLAY_MODE_EVT. mUIOrientationMode = " + mUIOrientationMode);
                processUIOrientationMode();
                if (mMediaEventListener != null) {
                    mMediaEventListener.onDisplayModeEvent();
                }
                // Since this is a callback,
                // post a message back to the current process.
                sendEmptyMessage(DISPLAY_MODE_EVT);
                break;
            default:
                Log.e(TAG, "Received unknown event id=" + eventId);
        }

    }

    public void handleMessage(Message msg) {
        switch(msg.what) {
            case DISPLAY_MODE_EVT:
                mDisplayModeEventRegistrants.notifyRegistrants(
                        new AsyncResult (null, convertMediaMode(mUIOrientationMode), null));
                break;

            default:
                Log.e(TAG, "Received unknown msg id = " + msg.what);
        }
    }

    private void processUIOrientationMode() {
        mCvoModeOnRegistrant.notifyRegistrants(new AsyncResult(null,
                isCvoModeEnabled(), null));
    }

    /**
     * Register for mode change notification from IMS media library to determine
     * if CVO mode needs to be activated or deactivated
     */
    public void registerForCvoModeRequestChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mCvoModeOnRegistrant.add(r);
    }

    /**
     * TODO Call all unregister methods Unregister for mode change notification
     * from IMS media library to determine if CVO mode needs to be activated or
     * deactivated
     */
    public void unregisterForCvoModeRequestChanged(Handler h) {
        mCvoModeOnRegistrant.remove(h);
    }

    public boolean isCvoModeEnabled() {
        return mUIOrientationMode == CVO_MODE;
    }

    public void registerForDisplayModeEvent(Handler h, int what, Object obj) {
        mDisplayModeEventRegistrants.addUnique(h, what, obj);
    }

    public void unregisterForDisplayModeEvent(Handler h) {
        mDisplayModeEventRegistrants.remove(h);
    }

    private int convertMediaMode(int mode) {
        switch(mode) {
            case LANDSCAPE_MODE:
                return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;

            case PORTRAIT_MODE:
                return ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;

            default:
                Log.d(TAG,"Received unknown mode = " + mode);
                return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
    }
}
