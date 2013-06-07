/*
 * Copyright (c) 2013 The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2008 The Android Open Source Project
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

import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewStub;
import android.widget.EditText;

import com.android.internal.telephony.Phone;

/**
 * Dialer class that encapsulates the DTMF twelve key behaviour.
 * This model backs up the UI behaviour in MSimDTMFTwelveKeyDialerView.java.
 */
public class MSimDTMFTwelveKeyDialer extends DTMFTwelveKeyDialer {
    private static final String LOG_TAG = "MSimDTMFTwelveKeyDialer";
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 1);

    /**
     * MSimDTMFTwelveKeyDialer constructor.
     *
     * @param parent the MSimInCallScreen instance that owns us.
     * @param dialerView the DTMFTwelveKeyDialerView we should use to display the dialpad.
     */
    public MSimDTMFTwelveKeyDialer(MSimInCallScreen parent,
                               DTMFTwelveKeyDialerView dialerView) {
        super(parent, dialerView);
        if (DBG) log("MSimDTMFTwelveKeyDialer constructor... this = " + this);
    }

    public MSimDTMFTwelveKeyDialer(MSimInCallScreen parent, ViewStub dialerStub) {
        super(parent, dialerStub);
        if (DBG) log("DTMFTwelveKeyDialer constructor... this = " + this);
    }

    protected void onDialerOpen(boolean animate) {
        if (DBG) log("onDialerOpen()...");

        // Any time the dialer is open, listen for "disconnect" events (so
        // we can close ourself.)
        mCM.registerForDisconnect(mSimHandler, PHONE_DISCONNECT, null);

        // On some devices the screen timeout is set to a special value
        // while the dialpad is up.
        MSimPhoneGlobals.getInstance().updateWakeState();

        // Give the InCallScreen a chance to do any necessary UI updates.
        if (mInCallScreen != null) {
            mInCallScreen.onDialerOpen(animate);
        } else {
            Log.e(LOG_TAG, "InCallScreen object was null during onDialerOpen()");
        }
    }

    /**
     * Our own handler to take care of the messages from the phone state changes
     */
    private final Handler mSimHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                // disconnect action
                // make sure to close the dialer on ALL disconnect actions.
                case PHONE_DISCONNECT:
                    if (DBG) log("disconnect message recieved, shutting down.");
                    // unregister since we are closing.
                    mCM.unregisterForDisconnect(this);
                    closeDialer(false);
                    break;
                case DTMF_SEND_CNF:
                    if (DBG) log("dtmf confirmation received from FW.");
                    // handle burst dtmf confirmation
                    handleBurstDtmfConfirmation();
                    break;
                case DTMF_STOP:
                    if (DBG) log("dtmf stop received");
                    stopTone();
                    break;
            }
        }
    };

    /**
     * static logging method
     */
    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}

