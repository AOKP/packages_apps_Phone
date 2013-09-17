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

package com.android.phone;

import android.content.Context;
import android.hardware.SensorManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Registrant;
import android.os.RegistrantList;
import android.util.Log;
import android.view.OrientationEventListener;

/**
 * Provides an interface to handle the CVO - Coordinated Video Orientation part
 * of the video telephony call
 */
public class CvoHandler extends Handler {

    private static final String TAG = "VideoCall_CvoHandler";
    private static final boolean DBG = true;
    private static final int ORIENTATION_ANGLE_0 = 0;
    private static final int ORIENTATION_ANGLE_90 = 1;
    private static final int ORIENTATION_ANGLE_180 = 2;
    private static final int ORIENTATION_ANGLE_270 = 3;
    private static final int ORIENTATION_MODE_THRESHOLD = 45;

    // Use a singleton.
    private static CvoHandler mInstance;

    /**
     * Phone orientation angle which can take one of the 4 values
     * ORIENTATION_ANGLE_0, ORIENTATION_ANGLE_90, ORIENTATION_ANGLE_180,
     * ORIENTATION_ANGLE_270
     */
    private int mCurrentOrientation = 0;
    private Context mContext;
    OrientationEventListener mOrientationEventListener;
    public RegistrantList mCvoRegistrants = new RegistrantList();

    private CvoHandler(Context context) {
        mContext = context;
        mOrientationEventListener =
                new OrientationEventListener(mContext,
                        SensorManager.SENSOR_DELAY_NORMAL) {
                    @Override
                    public void onOrientationChanged(int angle) {
                        int newOrientation = calculateDeviceOrientation(angle);
                        if (hasDeviceOrientationChanged(newOrientation)) {
                            notifyCvoClient(newOrientation);
                        }
                    }
                };
        log("CvoHandler created");
    }

    /**
     * This method returns the single instance of CvoHandler object *
     */
    public static synchronized CvoHandler getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new CvoHandler(context);
        }
        return mInstance;
    }

    public interface CvoEventListener {
        /**
         * This callback method will be invoked when the device orientation
         * changes.
         */
        void onDeviceOrientationChanged(int rotation);
    }

    /**
     * Register for CVO device orientation changed event
     */
    public void registerForCvoInfoChange(Handler h, int what, Object obj) {
        log("registerForCvoInfoChange handler= " + h + " what= " + what + " obj= " + obj);
        Registrant r = new Registrant(h, what, obj);
        mCvoRegistrants.add(r);
    }

    public void unregisterForCvoInfoChange(Handler h) {
        log("unregisterForCvoInfoChange handler= " + h);
        mCvoRegistrants.remove(h);
    }

    /**
     * Enable sensor to listen for device orientation changes
     */
    public void startOrientationListener(boolean start) {
        Log.d(TAG, "startOrientationListener " + start);
        if (start) {
            if (mOrientationEventListener.canDetectOrientation()) {
                mOrientationEventListener.enable();
            } else {
                Log.d(TAG, "Cannot detect orientation");
            }
        } else {
            mOrientationEventListener.disable();
        }
    }

    /**
     * For CVO mode handling, phone is expected to have only 4 orientations The
     * orientation sensor gives every degree change angle. This needs to be
     * categorized to one of the 4 angles. This method does this calculation.
     * @param angle
     * @return one of the 4 orientation angles ORIENTATION_ANGLE_0,
     *         ORIENTATION_ANGLE_90, ORIENTATION_ANGLE_180,
     *         ORIENTATION_ANGLE_270
     */
    private int calculateDeviceOrientation(int angle) {
        int newOrientation = ORIENTATION_ANGLE_0;
        if ((angle >= 0
                && angle < 0 + ORIENTATION_MODE_THRESHOLD) ||
                (angle >= 360 - ORIENTATION_MODE_THRESHOLD &&
                angle < 360)) {
            newOrientation = ORIENTATION_ANGLE_0;
        } else if (angle >= 90 - ORIENTATION_MODE_THRESHOLD
                && angle < 90 + ORIENTATION_MODE_THRESHOLD) {
            newOrientation = ORIENTATION_ANGLE_90;
        } else if (angle >= 180 - ORIENTATION_MODE_THRESHOLD
                && angle < 180 + ORIENTATION_MODE_THRESHOLD) {
            newOrientation = ORIENTATION_ANGLE_180;
        } else if (angle >= 270 - ORIENTATION_MODE_THRESHOLD
                && angle < 270 + ORIENTATION_MODE_THRESHOLD) {
            newOrientation = ORIENTATION_ANGLE_270;
        }
        return newOrientation;
    }

    /**
     * Detect change in device orientation
     */
    private boolean hasDeviceOrientationChanged(int newOrientation) {
        if (DBG) {
            log("hasDeviceOrientationChanged mCurrentOrientation= "
                    + mCurrentOrientation + " newOrientation= " + newOrientation);
        }
        if (newOrientation != mCurrentOrientation) {
            mCurrentOrientation = newOrientation;
            return true;
        }
        return false;
    }

    /**
     * Send newOrientation to client
     */
    private void notifyCvoClient(int newOrientation) {
        AsyncResult ar = new AsyncResult(null, mCurrentOrientation, null);
        mCvoRegistrants.notifyRegistrants(ar);
    }

    public int convertMediaOrientationToActualAngle(int mCurrentOrientation) {
        int angle = 0;
        switch (mCurrentOrientation) {
            case ORIENTATION_ANGLE_0:
                angle = 0;
                break;
            case ORIENTATION_ANGLE_90:
                angle = 90;
                break;
            case ORIENTATION_ANGLE_180:
                angle = 180;
                break;
            case ORIENTATION_ANGLE_270:
                angle = 270;
                break;
            default:
                loge("getAngleFromOrientation: Undefined orientation");
        }
        return angle;
    }

    private void log(String msg) {
        Log.d(TAG, msg);
    }

    private void loge(String msg) {
        Log.e(TAG, msg);
    }

}