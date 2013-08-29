/*
 * Copyright (c) 2012-2013 The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2009 The Android Open Source Project
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

import android.content.Context;
import android.telephony.MSimTelephonyManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;

import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.PhoneConstants;
import com.android.phone.PhoneUtils;

/**
 * In-call onscreen touch UI elements, used on some platforms.
 *
 * This widget is a fullscreen overlay, drawn on top of the
 * non-touch-sensitive parts of the in-call UI (i.e. the call card).
 */
public class MSimInCallTouchUi extends InCallTouchUi {
    private static final String LOG_TAG = "MSimInCallTouchUi";
    protected static final boolean DBG = (MSimPhoneGlobals.DBG_LEVEL >= 1);

    private CompoundButton mSwitchButton;
    // Times of the most recent "answer" or "reject" action (see updateState())
    // In multisim scenarios, we maintain the times for each sub.
    private long[] mLastIncomingCallActionTimes;  // in SystemClock.uptimeMillis() time base

    public MSimInCallTouchUi(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLastIncomingCallActionTimes = new long[MSimTelephonyManager.getDefault().getPhoneCount()];
    }

    void setInCallScreenInstance(MSimInCallScreen inCallScreen) {
        super.setInCallScreenInstance(inCallScreen);
    }

    /**
     * Set the time of the most recent incoming call action.
     */
    @Override
    protected void setLastIncomingCallActionTime(long time) {
        mLastIncomingCallActionTimes[PhoneUtils.getActiveSubscription()] = time;
    }

    /**
     * Retrieve the time of the most recent incoming call action.
     */
    @Override
    protected long getLastIncomingCallActionTime() {
        return mLastIncomingCallActionTimes[PhoneUtils.getActiveSubscription()];
    }

    protected void onFinishInflate() {
        super.onFinishInflate();
        if (DBG) log("MSimInCallTouchUi onFinishInflate(this = " + this + ")...");

        mSwitchButton = (CompoundButton) mInCallControls.findViewById(R.id.switchButton);
        mSwitchButton.setOnClickListener(this);
        mSwitchButton.setOnLongClickListener(this);
    }

    /**
     * Updates the Switch subscription button based on the
     * active phone count.
     */
    private void updateSwitchButton() {
        int phoneCount = MSimTelephonyManager.getDefault().getPhoneCount();
        int activePhoneCount = 0;

        for (int i = 0; i < phoneCount; i++) {
            if (mApp.mCM.getState(i) != PhoneConstants.State.IDLE)
                activePhoneCount++;
        }

        if ((phoneCount > 1) && (activePhoneCount > 1)) {
            mSwitchButton.setVisibility(View.VISIBLE);
            mSwitchButton.setEnabled(true);
        } else {
            mSwitchButton.setVisibility(View.GONE);
        }
        log("updateSwitchButton count =" + phoneCount + " active count =" + activePhoneCount);
    }

    /**
     * Updates the enabledness and "checked" state of the buttons on the
     * "inCallControls" panel, based on the current telephony state.
     */
    @Override
    protected void updateInCallControls(CallManager cm) {
        super.updateInCallControls(cm);

        updateSwitchButton();
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (DBG) log("onClick(View " + view + ", id " + id + ")...");

        switch (id) {
            case R.id.switchButton:
                handleSwitchButtonClick();
                break;

            default:
                super.onClick(view);
                break;
        }
    }

    private void handleSwitchButtonClick() {
        PhoneUtils.switchToOtherActiveSub(PhoneUtils.getActiveSubscription());
        mInCallScreen.updateScreen();
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
