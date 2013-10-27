/*
 * Copyright (c) 2013 The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.phone;

import com.android.phone.Constants.CallStatusCode;

import android.content.Intent;
import android.os.SystemProperties;
import android.util.Log;

import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;

public class MSimCallController extends CallController {
    private static final String TAG = "MSimCallController";
    private static final boolean DBG =
            (MSimPhoneGlobals.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    // Do not check in with VDBG = true, since that may write PII to the system log.
    private static final boolean VDBG = false;

    /**
     * Initialize the singleton MSimCallController instance.
     *
     * This is only done once, at startup, from MSimPhoneGlobals.onCreate().
     * From then on, the MSimCallController instance is available via the
     * PhoneGlobals's public "callController" field, which is why there's no
     * getInstance() method here.
     */
    /* package */ static CallController init(MSimPhoneGlobals app, CallLogger callLogger) {
        synchronized (MSimCallController.class) {
            if (sInstance == null) {
                sInstance = new MSimCallController(app, callLogger);
            } else {
                Log.wtf(TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    /**
     * Private constructor (this is a singleton).
     * @see init()
     */
    private MSimCallController(MSimPhoneGlobals app, CallLogger callLogger) {
        super(app, callLogger);
        if (DBG) log("CallController constructor: app = " + app);
    }

    /**
     * Actually make a call to whomever the intent tells us to.
     *
     * Note that there's no need to explicitly update (or refresh) the
     * in-call UI at any point in this method, since a fresh InCallScreen
     * instance will be launched automatically after we return (see
     * placeCall() above.)
     *
     * Before placing the call mark the received sub as active sub
     * call the base class method to place the call.
     *
     * @param intent the CALL intent describing whom to call
     * @return CallStatusCode.SUCCESS if we successfully initiated an
     *    outgoing call.  If there was some kind of failure, return one of
     *    the other CallStatusCode codes indicating what went wrong.
     */
    @Override
    protected CallStatusCode placeCallInternal(Intent intent) {
        if (DBG) log("placeCallInternal()...  intent = " + intent);
        int sub = intent.getIntExtra(SUBSCRIPTION_KEY, mApp.getVoiceSubscription());

        PhoneUtils.setActiveSubscription(sub);
        return super.placeCallInternal(intent);
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}

