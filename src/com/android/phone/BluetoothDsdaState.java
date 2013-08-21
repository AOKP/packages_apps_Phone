/*
 *
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *    * Redistributions of source code must retain the above copyright
 *      notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 *      copyright notice, this list of conditions and the following
 *      disclaimer in the documentation and/or other materials provided
 *      with the distribution.
 *    * Neither the name of The Linux Foundation nor the names of its
 *      contributors may be used to endorse or promote products derived
 *      from this software without specific prior written permission.
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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.telephony.PhoneNumberUtils;
import android.util.Log;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.CallManager;
import android.telephony.MSimTelephonyManager;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

/**
 * Bluetooth headset manager for the DSDA call state changes.
 * @hide
 */
class BluetoothDsdaState {
    private static final String TAG = "BluetoothDsdaState";
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 1)
            && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (PhoneGlobals.DBG_LEVEL >= 2);


    private static final String MODIFY_PHONE_STATE = android.Manifest.permission.MODIFY_PHONE_STATE;

    private BluetoothAdapter mAdapter;
    private CallManager mCM;

    private BluetoothHeadset mBluetoothHeadset;
    private BluetoothPhoneService mPhoneProxy;

   //At any time we can update only one active and one held
    private int mNumActive;  //must be initialized to zero
    private int mNumHeld; //must be initialized to zero
    private int mCallState;
    private boolean mFakeMultiParty;

    private BluetoothSub mSubscriptionOne;
    private BluetoothSub mSubscriptionTwo;

    //Stores the current SUB on which call state changed happened.
    private int mCurrentSub = SUB1;
    private boolean mCallSwitch = false;
    // CDMA specific flag used in context with BT devices having display capabilities
    // to show which Caller is active. This state might not be always true as in CDMA
    // networks if a caller drops off no update is provided to the Phone.
    // This flag is just used as a toggle to provide a update to the BT device to specify
    // which caller is active.
    private boolean mCdmaIsSecondCallActive = false;
    private boolean mCdmaCallsSwapped = false;

    private long[] mClccTimestamps; // Timestamps associated with each clcc index
    private boolean[] mClccUsed;     // Is this clcc index in use

    private static final int GSM_MAX_CONNECTIONS = 6;  // Max connections allowed by GSM
    private static final int CDMA_MAX_CONNECTIONS = 2;  // Max connections allowed by CDMA

    /* At present the SUBs are valued as 0 and 1 for DSDA*/
    private static final int SUB1 = 0; //SUB1 is by default CDMA in C+G.
    private static final int SUB2 = 1;
    private static final int MAX_SUBS = 2;

    // match up with bthf_call_state_t of bt_hf.h
    final static int CALL_STATE_ACTIVE = 0;
    final static int CALL_STATE_HELD = 1;
    final static int CALL_STATE_DIALING = 2;
    final static int CALL_STATE_ALERTING = 3;
    final static int CALL_STATE_INCOMING = 4;
    final static int CALL_STATE_WAITING = 5;
    final static int CALL_STATE_IDLE = 6;
    final static int CHLD_TYPE_RELEASEHELD = 0;
    final static int CHLD_TYPE_RELEASEACTIVE_ACCEPTHELD = 1;
    final static int CHLD_TYPE_HOLDACTIVE_ACCEPTHELD = 2;
    final static int CHLD_TYPE_ADDHELDTOCONF = 3;

    BluetoothDsdaState(BluetoothPhoneService context) {
        mPhoneProxy = context;
        mNumActive = 0;
        mNumHeld = 0;
        mFakeMultiParty = false;
        mCM = CallManager.getInstance();
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mAdapter == null) {
            if (VDBG) Log.d(TAG, "mAdapter null");
                return;
        }
        mSubscriptionOne = new BluetoothSub(SUB1);
        mSubscriptionTwo = new BluetoothSub(SUB2);
        //Get the HeadsetService Profile proxy
        mAdapter.getProfileProxy(context, mProfileListener, BluetoothProfile.HEADSET);
        //Initialize CLCC
        mClccTimestamps = new long[GSM_MAX_CONNECTIONS];
        mClccUsed = new boolean[GSM_MAX_CONNECTIONS];
        for (int i = 0; i < GSM_MAX_CONNECTIONS; i++) {
            mClccUsed[i] = false;
        }
    }

    //This will also register for getting the Bluetooth Headset Profile proxy
    private BluetoothProfile.ServiceListener mProfileListener =
            new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            Log.d(TAG, "Got the headset proxy for DSDA" );
            mBluetoothHeadset = (BluetoothHeadset) proxy;
            if (mBluetoothHeadset != null) {
                log("query phone state");
                processQueryPhoneState();
            }
        }
        public void onServiceDisconnected(int profile) {
            mBluetoothHeadset = null;
            Log.d(TAG, "Released the headset proxy for DSDA");
        }
    };

    /* Handles call state changes on each subscription. */
    public void handleMultiSimPreciseCallStateChange() {
        Log.d(TAG, "handleDSDAPreciseCallStateChange");
        //Handle call state changes of both subs separately
        int SubId = getCurrentSub();
        Log.d(TAG, "Call change of : " + SubId);
        if (SubId == SUB1)
            mSubscriptionOne.handleSubscriptionCallStateChange();
        else
            mSubscriptionTwo.handleSubscriptionCallStateChange();
    }

    /* Set the current SUB*/
    public void setCurrentSub(int sub) {
        log("Call state changed on SUB: " + sub);
        mCurrentSub = sub;
    }

    /* get the current SUB*/
    private int getCurrentSub() {
        return mCurrentSub;
    }

    /* Check if call swap can be done on active SUB*/
    public boolean canDoCallSwap() {
        int active = mCM.getActiveSubscription();
        if (active == SUB1) {
            if ((mSubscriptionOne.mActive + mSubscriptionOne.mHeld) > 1)
                return true;
        }
        else {
            if ((mSubscriptionTwo.mActive + mSubscriptionTwo.mHeld) > 1)
                return true;
        }
        return false;
    }

    /* SUB switch is allowed only when each sub has max
    of one call, either active or held*/
    public boolean isSwitchSubAllowed() {
        boolean allowed = false;
        if ((((mSubscriptionOne.mActive + mSubscriptionOne.mHeld) == 1)
            && (mSubscriptionOne.mCallState == CALL_STATE_IDLE))
            && (((mSubscriptionTwo.mActive + mSubscriptionTwo.mHeld) == 1)
            && (mSubscriptionTwo.mCallState == CALL_STATE_IDLE))) {
            allowed = true;
        }
        log("Is switch SUB allowed: " + allowed);
        return allowed;
    }

    /* Do the SwithSuB. */
    public void SwitchSub() {
        log("SwitchSub");
        PhoneUtils.switchToOtherActiveSub(PhoneUtils.getActiveSubscription());
        if (isSwitchSubAllowed() == true) {
            Log.d(TAG, "Update headset about switch sub");
            if (mBluetoothHeadset != null) {
                mBluetoothHeadset.phoneStateChanged(mNumActive, mNumHeld,
                    CALL_STATE_IDLE, null, 0);
            }
        }
    }

    /* Get the active or held call on other Sub. */
    public Call getCallOnOtherSub() {
        log("getCallOnOtherSub");
        int activeSub = mCM.getActiveSubscription();
        Call call = null;
        if (activeSub == SUB1) {
            if ((mSubscriptionTwo.mActive + mSubscriptionTwo.mHeld) == 1) {
                if (mCM.hasActiveFgCall(SUB2))
                    call = mCM.getActiveFgCall(SUB2);
                else if (mCM.hasActiveBgCall(SUB2))
                    call = mCM.getFirstActiveBgCall(SUB2);
            }
        } else {
            if ((mSubscriptionOne.mActive + mSubscriptionOne.mHeld) == 1) {
                if(mCM.hasActiveFgCall(SUB1))
                    call = mCM.getActiveFgCall(SUB1);
                else if(mCM.hasActiveBgCall(SUB1))
                    call = mCM.getFirstActiveBgCall(SUB1);
            }
        }
        return call;
    }

    boolean hasCallsOnBothSubs() {
        if (((mSubscriptionOne.mActive + mSubscriptionOne.mHeld) >= 1)
           && ((mSubscriptionTwo.mActive + mSubscriptionTwo.mHeld) >= 1)) {
            log("hasCallsOnBothSubs is true");
            return true;
        }
        return false;
    }

    public boolean answerOnThisSubAllowed() {
        log("answerOnThisSubAllowed.");
        int activeSub = mCM.getActiveSubscription();
        if (activeSub == SUB1) {
            if((mSubscriptionTwo.mActive + mSubscriptionTwo.mHeld) >= 1)
                return true;
        } else{
            if ((mSubscriptionOne.mActive + mSubscriptionOne.mHeld) >= 1)
                return true;
        }
        return false;
    }

    public Call getCallOnActiveSub() {
        log("getCallOnActiveSub");
        int activeSub = mCM.getActiveSubscription();
        Call call = null;
        if (activeSub == SUB1) {
            if((mSubscriptionTwo.mActive + mSubscriptionTwo.mHeld) < 2) {
                if (mCM.hasActiveFgCall(SUB1))
                    call = mCM.getActiveFgCall(SUB1);
                else if (mCM.hasActiveBgCall(SUB1))
                    call = mCM.getFirstActiveBgCall(SUB1);
            }
        } else {
            if ((mSubscriptionOne.mActive + mSubscriptionOne.mHeld) < 2) {
                if(mCM.hasActiveFgCall(SUB2))
                    call = mCM.getActiveFgCall(SUB2);
                else if (mCM.hasActiveBgCall(SUB2))
                    call = mCM.getFirstActiveBgCall(SUB2);
            }
        }
        return call;
    }

    /* CallState should be for the current active Sub*/
    public int getCallState() {
        int activeSub = mCM.getActiveSubscription();
        Call foregroundCall = mCM.getActiveFgCall(activeSub);
        Call ringingCall = mCM.getFirstActiveRingingCall(activeSub);
        Call.State mForegroundCallState;
        Call.State mRingingCallState;
        mForegroundCallState = foregroundCall.getState();
        mRingingCallState = ringingCall.getState();
        int callState = convertCallState(mRingingCallState, mForegroundCallState);
        return callState;
    }

    /* when HeadsetService is created,it queries for current phone
    state. This function provides the current state*/
    public void processQueryPhoneState() {
        if (mBluetoothHeadset != null) {
            mBluetoothHeadset.phoneStateChanged(mNumActive, mNumHeld,
            getCallState(),
            null, 0);
        }
    }

    /* Check if calls on single subscription only. */
    private boolean isSingleSubActive() {
        int activeSub = mCM.getActiveSubscription();
        if (((((mSubscriptionOne.mActive + mSubscriptionOne.mHeld) >= 1)
            || mSubscriptionOne.mCallState != CALL_STATE_IDLE)
            && (((mSubscriptionTwo.mActive + mSubscriptionTwo.mHeld) < 1)
            && (mSubscriptionTwo.mCallState == CALL_STATE_IDLE)))
            || ((((mSubscriptionTwo.mActive + mSubscriptionTwo.mHeld) >= 1)
            || mSubscriptionTwo.mCallState != CALL_STATE_IDLE)
            && ((mSubscriptionOne.mActive + mSubscriptionOne.mHeld) < 1)
            && (mSubscriptionOne.mCallState == CALL_STATE_IDLE))) {
            log("calls on single sub");
            return true;
        }
        return false;
    }

    /* Executes AT+CLCC for DSDA scenarios. */
    public void handleListCurrentCalls() {
        // Check if we are in DSDA mode.
        int activeSub = mCM.getActiveSubscription();
        boolean allowDsda = false;
        Call call = getCallOnOtherSub();
        Log.d(TAG, "handleListCurrentCalls");
        //For CDMA SUB, update CLCC separately if only this SUB is active.
        if (isSingleSubActive()) {
            if ((activeSub == SUB1) && (mSubscriptionOne.mPhonetype
                                    == PhoneConstants.PHONE_TYPE_CDMA)) {
                log("Only CDMA call list, on SUB1");
                listCurrentCallsCdma(activeSub);
                if (mBluetoothHeadset != null) {
                    log("last clcc update on SUB1");
                    mBluetoothHeadset.clccResponse(0, 0, 0, 0, false, "", 0);
                } else log("headset null, no clcc update");
                return;
            } else if ((activeSub == SUB1) && (mSubscriptionOne.mPhonetype
                                            == PhoneConstants.PHONE_TYPE_CDMA)) {
                log("Only CDMA call list, on SUB2");
                listCurrentCallsCdma(activeSub);
                if (mBluetoothHeadset != null) {
                    log("last clcc update on SUB2");
                    mBluetoothHeadset.clccResponse(0, 0, 0, 0, false, "", 0);
                } else log("headset null, no clcc update");
                return;
            }
        }
        //Send CLCC for both subs.
        //allowDsda will allow sending CLCC at call setup states too.
        if (activeSub == SUB1) {
            if ((call != null) && (mSubscriptionOne.mCallState !=
                CALL_STATE_IDLE))
                allowDsda = true;
        } else {
            if((call != null) && (mSubscriptionTwo.mCallState !=
                CALL_STATE_IDLE))
                allowDsda = true;
        }
        log("allowdsda: " + allowDsda);
        listCurrentCallsOnBothSubs(allowDsda);

        // end the result
        // when index is 0, other parameter does not matter
        if (mBluetoothHeadset != null) {
            log("send last clcc update");
            mBluetoothHeadset.clccResponse(0, 0, 0, 0, false, "", 0);
        } else log("headset null, no clcc update");
    }

    /*List CLCC for CDMA Subscription. */
    private void listCurrentCallsCdma(int sub) {
        // In CDMA at one time a user can have only two live/active connections
        Connection[] clccConnections = new Connection[CDMA_MAX_CONNECTIONS];// indexed by CLCC index
        Call foregroundCall = mCM.getActiveFgCall(sub);
        Call ringingCall = mCM.getFirstActiveRingingCall(sub);

        Call.State ringingCallState = ringingCall.getState();
        // If the Ringing Call state is INCOMING, that means this is the very first call
        // hence there should not be any Foreground Call
        if (ringingCallState == Call.State.INCOMING) {
            if (VDBG) log("Filling clccConnections[0] for INCOMING state");
            clccConnections[0] = ringingCall.getLatestConnection();
        } else if (foregroundCall.getState().isAlive()) {
            // Getting Foreground Call connection based on Call state
            if (ringingCall.isRinging()) {
                if (VDBG) log("Filling clccConnections[0] & [1] for CALL WAITING state");
                clccConnections[0] = foregroundCall.getEarliestConnection();
                clccConnections[1] = ringingCall.getLatestConnection();
            } else {
                if (foregroundCall.getConnections().size() <= 1) {
                    // Single call scenario
                    if (VDBG) {
                        log("Filling clccConnections[0] with ForgroundCall latest connection");
                    }
                    clccConnections[0] = foregroundCall.getLatestConnection();
                } else {
                    // Multiple Call scenario. This would be true for both
                    // CONF_CALL and THRWAY_ACTIVE state
                    if (VDBG) {
                        log("Filling clccConnections[0] & [1] with ForgroundCall connections");
                    }
                    clccConnections[0] = foregroundCall.getEarliestConnection();
                    clccConnections[1] = foregroundCall.getLatestConnection();
                }
            }
        }

        // Update the mCdmaIsSecondCallActive flag based on the Phone call state
        if (PhoneGlobals.getInstance().cdmaPhoneCallState.getCurrentCallState()
                == CdmaPhoneCallState.PhoneCallState.SINGLE_ACTIVE) {
            handleCdmaSetSecondCallState(false);
        } else if (PhoneGlobals.getInstance().cdmaPhoneCallState.getCurrentCallState()
                == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
            handleCdmaSetSecondCallState(true);
        }
        // send CLCC result
        for (int i = 0; (i < clccConnections.length) && (clccConnections[i] != null); i++) {
            sendClccResponseCdma(i, clccConnections[i]);
        }
    }

    /** Send ClCC results for a Connection object for CDMA phone */
    private void sendClccResponseCdma(int index, Connection connection) {
        int state;
        PhoneGlobals app = PhoneGlobals.getInstance();
        /* TODO..Can CDMA APIs be changed for DSDA*/
        CdmaPhoneCallState.PhoneCallState currCdmaCallState =
                app.cdmaPhoneCallState.getCurrentCallState();
        CdmaPhoneCallState.PhoneCallState prevCdmaCallState =
                app.cdmaPhoneCallState.getPreviousCallState();

        if ((prevCdmaCallState == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE)
                && (currCdmaCallState == CdmaPhoneCallState.PhoneCallState.CONF_CALL)) {
            // If the current state is reached after merging two calls
            // we set the state of all the connections as ACTIVE
            state = CALL_STATE_ACTIVE;
        } else {
            Call.State callState = connection.getState();
            switch (callState) {
            case ACTIVE:
                // For CDMA since both the connections are set as active by FW after accepting
                // a Call waiting or making a 3 way call, we need to set the state specifically
                // to ACTIVE/HOLDING based on the mCdmaIsSecondCallActive flag. This way the
                // CLCC result will allow BT devices to enable the swap or merge options
                if (index == 0) { // For the 1st active connection
                    state = mCdmaIsSecondCallActive ? CALL_STATE_HELD : CALL_STATE_ACTIVE;
                } else { // for the 2nd active connection
                    state = mCdmaIsSecondCallActive ? CALL_STATE_ACTIVE : CALL_STATE_HELD;
                }
                break;
            case HOLDING:
                state = CALL_STATE_HELD;
                break;
            case DIALING:
                state = CALL_STATE_DIALING;
                break;
            case ALERTING:
                state = CALL_STATE_ALERTING;
                break;
            case INCOMING:
                state = CALL_STATE_INCOMING;
                break;
            case WAITING:
                state = CALL_STATE_WAITING;
                break;
            default:
                Log.e(TAG, "bad call state: " + callState);
                return;
            }
        }

        boolean mpty = false;
        if (currCdmaCallState == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
            if (prevCdmaCallState == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                // If the current state is reached after merging two calls
                // we set the multiparty call true.
                mpty = true;
            } // else
                // CALL_CONF state is not from merging two calls, but from
                // accepting the second call. In this case first will be on
                // hold in most cases but in some cases its already merged.
                // However, we will follow the common case and the test case
                // as per Bluetooth SIG PTS
        }

        int direction = connection.isIncoming() ? 1 : 0;

        String number = connection.getAddress();
        int type = -1;
        if (number != null) {
            type = PhoneNumberUtils.toaFromString(number);
        } else {
            number = "";
        }
        mBluetoothHeadset.clccResponse(index + 1, direction, state, 0, mpty, number, type);
    }

    public void handleCdmaSwapSecondCallState() {
        log("cdmaSwapSecondCallState: Toggling mCdmaIsSecondCallActive");
        mCdmaIsSecondCallActive = !mCdmaIsSecondCallActive;
        mCdmaCallsSwapped = true;
    }

    public void handleCdmaSetSecondCallState(boolean state) {
        if (VDBG) log("cdmaSetSecondCallState: Setting mCdmaIsSecondCallActive to " + state);
        mCdmaIsSecondCallActive = state;
        if (!mCdmaIsSecondCallActive) {
            mCdmaCallsSwapped = false;
        }
    }

    /* List CLCC on both the subscription. The max call list is driven by
       GSM_MAX_CONNECTIONS, even in DSDA. */
    private void listCurrentCallsOnBothSubs(boolean allowDsda) {
        // Collect all known connections
        // clccConnections isindexed by CLCC index
        log("listCurrentCallsOnBothSubs");
        //In DSDA, call list is limited by GSM_MAX_CONNECTIONS.
        Connection[] clccConnections = new Connection[GSM_MAX_CONNECTIONS];
        LinkedList<Connection> newConnections = new LinkedList<Connection>();
        LinkedList<Connection> connections = new LinkedList<Connection>();
        //Get all calls on subscription one.
        Call foregroundCallSub1 = mCM.getActiveFgCall(SUB1);
        Call backgroundCallSub1 = mCM.getFirstActiveBgCall(SUB1);
        Call ringingCallSub1 = mCM.getFirstActiveRingingCall(SUB1);
        //Get all calls on subscription two.
        Call foregroundCallSub2 = mCM.getActiveFgCall(SUB2);
        Call backgroundCallSub2 = mCM.getFirstActiveBgCall(SUB2);
        Call ringingCallSub2 = mCM.getFirstActiveRingingCall(SUB2);

        Log.d(TAG, " SUB1:" + "foreground: " + foregroundCallSub1 +
            " background: " + backgroundCallSub1 + " ringing: " +  ringingCallSub1);
        Log.d(TAG, " SUB2:" + "foreground: " + foregroundCallSub2 +
            " background: " + backgroundCallSub2 + " ringing: " +  ringingCallSub2);

        //Get CDMA SUB call connections first.
        if (mSubscriptionOne.mPhonetype == PhoneConstants.PHONE_TYPE_CDMA) {
            Call.State ringingCallState = ringingCallSub1.getState();
            if (ringingCallState == Call.State.INCOMING) {
                log("Filling Connections for INCOMING state");
                connections.add(ringingCallSub1.getLatestConnection());
            } else if (foregroundCallSub1.getState().isAlive()) {
                // Getting Foreground Call connection based on Call state
                if (ringingCallSub1.isRinging()) {
                    log("Filling Connections for CALL WAITING state");
                    connections.add(foregroundCallSub1.getEarliestConnection());
                    connections.add(ringingCallSub1.getLatestConnection());
                } else {
                    if (foregroundCallSub1.getConnections().size() <= 1) {
                        // Single call scenario
                        if (VDBG) {
                            log("Filling Connections with ForgroundCall latest connection");
                        }
                        connections.add(foregroundCallSub1.getLatestConnection());
                    } else {
                        // Multiple Call scenario. This would be true for both
                        // CONF_CALL and THRWAY_ACTIVE state.
                        log("Filling Connections withForgroundCall connections");
                        connections.add(foregroundCallSub1.getEarliestConnection());
                        connections.add(foregroundCallSub1.getLatestConnection());
                    }
                }
            }
            /* Get calls on other Sub. They are non CDMA */
            if (ringingCallSub2.getState().isAlive()) {
                log("Add SUB2 ringing calls");
                connections.addAll(ringingCallSub2.getConnections());
            }
            if (foregroundCallSub2.getState().isAlive()) {
                log("Add SUB2 forground calls");
                connections.addAll(foregroundCallSub2.getConnections());
            }
            if (backgroundCallSub2.getState().isAlive()) {
                log("Add SUB2 background calls");
                connections.addAll(backgroundCallSub2.getConnections());
            }
        } else if (mSubscriptionTwo.mPhonetype == PhoneConstants.PHONE_TYPE_CDMA) {
            Call.State ringingCallState = ringingCallSub2.getState();
            if (ringingCallState == Call.State.INCOMING) {
                if (VDBG) log("Filling clccConnections[0] for INCOMING state");
                connections.add(ringingCallSub2.getLatestConnection());
            } else if (foregroundCallSub2.getState().isAlive()) {
                // Getting Foreground Call connection based on Call state
                if (ringingCallSub2.isRinging()) {
                    if (VDBG) log("Filling clccConnections for CALL WAITING state");
                    connections.add(foregroundCallSub2.getEarliestConnection());
                    connections.add(ringingCallSub2.getLatestConnection());
                } else {
                    if (foregroundCallSub2.getConnections().size() <= 1) {
                        // Single call scenario
                        if (VDBG) {
                            log("Filling clccConnections with ForgroundCall latest connection");
                        }
                        connections.add(foregroundCallSub2.getLatestConnection());
                    } else {
                        // Multiple Call scenario. This would be true for both
                        // CONF_CALL and THRWAY_ACTIVE state
                        log("Filling clccConnections withForgroundCall connections on sub2");
                        connections.add(foregroundCallSub2.getEarliestConnection());
                        connections.add(foregroundCallSub2.getLatestConnection());
                    }
                }
            }
            /* Get calls on other Sub. They are non CDMA*/
            if (ringingCallSub1.getState().isAlive()) {
                log("Add SUB1 ringing calls");
                connections.addAll(ringingCallSub1.getConnections());
            }
            if (foregroundCallSub1.getState().isAlive()) {
                log("Add SUB1 forground calls");
                connections.addAll(foregroundCallSub1.getConnections());
            }
            if (backgroundCallSub1.getState().isAlive()) {
                log("Add SUB1 background calls");
                connections.addAll(backgroundCallSub1.getConnections());
            }
        }
        // Update the mCdmaIsSecondCallActive flag based on the Phone call state
        if (PhoneGlobals.getInstance().cdmaPhoneCallState.getCurrentCallState()
              == CdmaPhoneCallState.PhoneCallState.SINGLE_ACTIVE) {
            handleCdmaSetSecondCallState(false);
        } else if (PhoneGlobals.getInstance().cdmaPhoneCallState.getCurrentCallState()
            == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
            handleCdmaSetSecondCallState(true);
        }
        log("calls added for both subscriptions");
        // Mark connections that we already known about
        boolean clccUsed[] = new boolean[GSM_MAX_CONNECTIONS];
        for (int i = 0; i < GSM_MAX_CONNECTIONS; i++) {
            clccUsed[i] = mClccUsed[i];
            log("add clcc about: " + i + " mClcc[]: " + mClccUsed[i]);
            mClccUsed[i] = false;
        }
        for (Connection c : connections) {
            boolean found = false;
            long timestamp = c.getCreateTime();
            for (int i = 0; i < GSM_MAX_CONNECTIONS; i++) {
                if (clccUsed[i] && timestamp == mClccTimestamps[i]) {
                    log("mClccUsed is true for: " + i);
                    mClccUsed[i] = true;
                    found = true;
                    clccConnections[i] = c;
                    break;
                }
            }
            if (!found) {
                log("add new conns");
                newConnections.add(c);
            }
        }
        // Find a CLCC index for new connections
        while (!newConnections.isEmpty()) {
            // Find lowest empty index
            int i = 0;
            while (mClccUsed[i]) i++;
            // Find earliest connection
            long earliestTimestamp = newConnections.get(0).getCreateTime();
            Connection earliestConnection = newConnections.get(0);
            for (int j = 0; j < newConnections.size(); j++) {
                long timestamp = newConnections.get(j).getCreateTime();
                if (timestamp < earliestTimestamp) {
                    earliestTimestamp = timestamp;
                    earliestConnection = newConnections.get(j);
                }
            }
            // update
            mClccUsed[i] = true;
            mClccTimestamps[i] = earliestTimestamp;
            clccConnections[i] = earliestConnection;
            newConnections.remove(earliestConnection);
        }
        // Send CLCC response to Bluetooth headset service
        for (int i = 0; i < clccConnections.length; i++) {
            if (mClccUsed[i]) {
                log("Send CLCC for Connection index: " + i);
                sendClccResponse(i, clccConnections[i], allowDsda);
            }
        }
    }

    /** Convert a Connection object into a single +CLCC result */
    private void sendClccResponse(int index, Connection connection,
                                  boolean allowDsda) {
        int state = convertCallState(connection.getState());
        boolean mpty = false;
        boolean active = false;
        Call call = connection.getCall();
        Phone phone = call.getPhone();
        int sub = phone.getSubscription();

        log("CLCC on this SUB: " + sub + " CallState: " + state);
        int activeSub =  mCM.getActiveSubscription();
        if (activeSub == SUB1 && (mSubscriptionOne.mPhonetype
            == PhoneConstants.PHONE_TYPE_CDMA)) {
            log("Active SUB1 is CDMA");
            Call foregroundCall = mCM.getActiveFgCall(SUB1);
            Connection fg = foregroundCall.getLatestConnection();
            Connection bg = foregroundCall.getEarliestConnection();
            if (mCdmaIsSecondCallActive == true) {
                log("Two calls on CDMA SUB");
                if ((fg != null) && (bg != null)) {
                    if ((fg != null) && (connection == fg)) {
                        active = true;
                        log("getEarliestConnection of CDMA calls");
                    }
                } else log ("Error in cdma connection");
            } else if ((fg != null) && (connection == fg)) {
                log("getEarliestConnection for single cdma call");
                active = true;
            } else if ((bg != null) && (connection == bg)) {
                log("getLatestConnection for single cdma call");
                active = true;
            }
        } else if (activeSub == SUB2 && (mSubscriptionTwo.mPhonetype
                   == PhoneConstants.PHONE_TYPE_CDMA)) {
            log("Active SUB2 is CDMA");
            Call foregroundCall = mCM.getActiveFgCall(SUB2);
            Connection fg = foregroundCall.getEarliestConnection();
            Connection bg = foregroundCall.getLatestConnection();
            if (mCdmaIsSecondCallActive == true) {
                log("Two calls on CDMA SUB");
                if ((fg != null) && (bg != null)) {
                     if ((fg != null) && (connection == fg)) {
                         active = true;
                         log("getEarliestConnection of CDMA calls");
                    }
                } else log ("Error in cdma connection");
            } else if ((fg != null) && (connection == fg)) {
                log("getEarliestConnection for single cdma call");
                active = true;
            } else if ((bg != null) && (connection == bg)) {
                log("getLatestConnection for single cdma call");
                active = true;
            }
        } else {
            log("CLCC for NON CDMA SUB");
            if (call == mCM.getFirstActiveRingingCall(activeSub)) {
                log("This is FG ringing call");
                active = true;
            } else if (call == mCM.getActiveFgCall(activeSub)) {
                active = true;
                log("This is first FG call");
            } else if (call == mCM.getFirstActiveBgCall(activeSub)) {
                log("BG call on GSM sub");
            }
        }
        //For CDMA subscription, mpty will be true soon after
        //two calls are active.
        if (call != null) {
            mpty = call.isMultiparty();
            log("call.isMultiparty: " + mpty);
            if((mFakeMultiParty == true) && !active) {
                log("A fake mparty scenario");
                if(!mpty)
                mpty = true;
            }
        }
        int direction = connection.isIncoming() ? 1 : 0;
        String number = connection.getAddress();
        int type = -1;
        if (number != null) {
            type = PhoneNumberUtils.toaFromString(number);
        }
        if (mNumActive + mNumHeld >= 1) {
            log("If Incoming call, change to waiting");
            if (state == CALL_STATE_INCOMING)
                state = CALL_STATE_WAITING; //DSDA
        }
        // If calls on both Subs, need to change call states on BG calls
        if (((hasCallsOnBothSubs() == true) || allowDsda) && !active) {
            //Fake call held for all background calls
            log("Check if this call state to b made held");
            Call activeSubForegroundCall = mCM.getActiveFgCall(activeSub);
            Call activeSubRingingCall = mCM.getFirstActiveRingingCall(activeSub);
            int activeCallState = convertCallState(activeSubRingingCall.getState(),
                                  activeSubForegroundCall.getState());
            if ((state == CALL_STATE_ACTIVE) &&
                (activeCallState != CALL_STATE_INCOMING))
                state = CALL_STATE_HELD;
            else if ((mpty == true)) {
                log("mtpy is true, manage call states on bg SUB");
                if(activeCallState != CALL_STATE_INCOMING)
                    state = CALL_STATE_HELD;
                else if (activeCallState == CALL_STATE_INCOMING)
                    state = CALL_STATE_ACTIVE;
            }
        }
        if (mBluetoothHeadset != null) {
            log("CLCC response to mBluetoothHeadset");
            mBluetoothHeadset.clccResponse(index+1, direction, state, 0, mpty,
            number, type);
        } else log("headset null, no need to send clcc");
    }

    /* Called to notify the Subscription change event from telephony.*/
    public void phoneSubChanged() {
        /*Could be used to notify switch SUB to headsets*/
        int sub = mCM.getActiveSubscription();
        Log.d(TAG, "Phone SUB changed, Active: " + sub);
        if (isSwitchSubAllowed() == true) {
            Log.d(TAG, "Update headset about switch sub");
            if (mBluetoothHeadset != null) {
                mBluetoothHeadset.phoneStateChanged(mNumActive, mNumHeld, CALL_STATE_IDLE,
                null, 0);
            }
        }
    }

    /*Process AT+CHLD command in DSDA. */
    public boolean processDsdaChld(int chld) {
        Phone phone;
        int phoneType;
        log("Get PhoneType of Active subscription");
        int activeSub = mCM.getActiveSubscription();
        boolean status = true;
        phone = MSimPhoneGlobals.getInstance().getPhone(activeSub);

        phoneType = phone.getPhoneType();
        log("processChld: " + chld + " for Phone type: " + phoneType);
        Call ringingCall = mCM.getFirstActiveRingingCall(activeSub);
        Call backgroundCall = mCM.getFirstActiveBgCall(activeSub);
        switch (chld) {
            case CHLD_TYPE_RELEASEHELD:
                if (ringingCall.isRinging()) {
                    status = PhoneUtils.hangupRingingCall(ringingCall);
                } else {
                    Call call = getCallOnOtherSub();
                    if (call != null) {
                        PhoneUtils.hangup(call);
                        status = true;
                    } else status = PhoneUtils.hangupHoldingCall(backgroundCall);
                }
                break;

            case CHLD_TYPE_RELEASEACTIVE_ACCEPTHELD:
                if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    Call call = getCallOnOtherSub();
                    if (ringingCall.isRinging() && (call != null)) {
                        //first answer the incoming call
                        PhoneUtils.answerCall(mCM.getFirstActiveRingingCall(activeSub));
                        //Try to Drop the call on the other SUB.
                        PhoneUtils.hangup(call);
                    } else if (isSwitchSubAllowed()) {
                        /* In case of Sub1=Active and Sub2=lch/held, drop call
                        on active  Sub*/
                        log("Drop the call on Active sub, move LCH to active");
                        call = getCallOnActiveSub();
                        if(call != null)
                            PhoneUtils.hangup(call);
                    } else {
                        //Operate on single SUB
                        if (ringingCall.isRinging()) {
                            // Hangup the active call and then answer call waiting call.
                            log("CHLD:1 Callwaiting Answer call");
                            PhoneUtils.hangupRingingAndActive(phone);
                        } else {
                            // If there is no Call waiting then just hangup
                            // the active call. In CDMA this mean that the complete
                            // call session would be ended
                            log("CHLD:1 Hangup Call");
                            PhoneUtils.hangup(PhoneGlobals.getInstance().mCM);
                        }
                    }
                    status = true;
                } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                    Call call = getCallOnOtherSub();
                    if (ringingCall.isRinging() && (call != null)) {
                        //first answer the incoming call
                        PhoneUtils.answerCall(mCM.getFirstActiveRingingCall(activeSub));
                        //Try to Drop the call on the other SUB.
                        PhoneUtils.hangup(call);
                    } else if (isSwitchSubAllowed()) {
                        /* In case of Sub1=Active and Sub2=lch/held, drop call
                        on active  Sub*/
                        log("processChld drop the call on Active sub, move LCH to active");
                        log("Drop call on active sub");
                        call = getCallOnActiveSub();
                        if(call != null)
                            PhoneUtils.hangup(call);
                    } else {
                        PhoneUtils.answerAndEndActive(PhoneGlobals.getInstance().mCM, ringingCall);
                    }
                    status = true;
                } else {
                    Log.e(TAG, "bad phone type: " + phoneType);
                    status = false;
                }
                break;

            case CHLD_TYPE_HOLDACTIVE_ACCEPTHELD:
                if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    if (canDoCallSwap()) {
                        log("Try to do call swap on same sub");
                        if (PhoneGlobals.getInstance().cdmaPhoneCallState.getCurrentCallState()
                            == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
                            log("CHLD:2 Swap Calls");
                            PhoneUtils.switchHoldingAndActive(backgroundCall);
                            // Toggle the second callers active state flag
                            handleCdmaSwapSecondCallState();
                        } else {
                            Log.e(TAG, "CDMA fail to do hold active and accept held");
                        }
                    } else if (isSwitchSubAllowed()) {
                        //Switch SUB.
                        log("CHLD = 2 Switch sub");
                        SwitchSub();
                    } else if (answerOnThisSubAllowed() == true) {
                        log("Can we answer the call on other SUB?");
                        // Answer the call on current SUB.
                        if (ringingCall.isRinging())
                            PhoneUtils.answerCall(ringingCall);
                    } else {
                        //On same sub
                        if (ringingCall.isRinging()) {
                            log("CHLD:2 Callwaiting Answer call");
                            PhoneUtils.answerCall(ringingCall);
                            PhoneUtils.setMute(false);
                            // Setting the second callers state flag to TRUE (i.e. active)
                            handleCdmaSetSecondCallState(true);
                        } else if (PhoneGlobals.getInstance().cdmaPhoneCallState
                            .getCurrentCallState()
                            == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
                            log("CHLD:2 Swap Calls");
                            PhoneUtils.switchHoldingAndActive(backgroundCall);
                            // Toggle the second callers active state flag
                            handleCdmaSwapSecondCallState();
                        }
                        Log.e(TAG, "CDMA fail to do hold active and accept held");
                    }
                    status = true;
                } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                    if (canDoCallSwap()) {
                        log("Try to do call swap on same sub");
                        PhoneUtils.switchHoldingAndActive(backgroundCall);
                    } else if (isSwitchSubAllowed()) {
                        /*Switch SUB*/
                        log("Switch sub");
                        SwitchSub();
                    } else if (answerOnThisSubAllowed() == true) {
                        log("Can we answer the call on other SUB?");
                        /* Answer the call on current SUB*/
                        if (ringingCall.isRinging())
                            PhoneUtils.answerCall(ringingCall);
                    } else {
                        log("CHLD=2, Answer the call on same sub");
                        if ((backgroundCall.mState == Call.State.HOLDING)
                            && ringingCall.isRinging()) {
                            log("Background is on hold when incoming call came");
                            PhoneUtils.answerCall(ringingCall);
                        } else PhoneUtils.switchHoldingAndActive(backgroundCall);
                    }
                    status = true;
                } else {
                    Log.e(TAG, "Unexpected phone type: " + phoneType);
                    status = false;
                }
                break;

            case CHLD_TYPE_ADDHELDTOCONF:
                if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    CdmaPhoneCallState.PhoneCallState state =
                    PhoneGlobals.getInstance().cdmaPhoneCallState.getCurrentCallState();
                    // For CDMA, we need to check if the call is in THRWAY_ACTIVE state
                    if (state == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                        if (VDBG) log("CHLD:3 Merge Calls");
                        PhoneUtils.mergeCalls();
                        status = true;
                    }   else if (state == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
                        // State is CONF_CALL already and we are getting a merge call
                        // This can happen when CONF_CALL was entered from a Call Waiting
                        // TODO(BT)
                        status = false;
                    } else {
                        Log.e(TAG, "GSG no call to add conference");
                        status = false;
                    }
                } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                    log("processChld fr CHLD = 3 for GSM, operate only on single sub");
                    if (mCM.hasActiveFgCall() && mCM.hasActiveBgCall()) {
                        PhoneUtils.mergeCalls();
                        status = true;
                    } else {
                        Log.e(TAG, "GSG no call to merge");
                        status = false;
                    }
                } else {
                    Log.e(TAG, "Unexpected phone type: " + phoneType);
                    status = false;
                }
                break;

            default:
                Log.e(TAG, "bad CHLD value: " + chld);
                status = false;
                break;
        }
        return status;
    }

    private class BluetoothSub {
        private Call.State mForegroundCallState;
        private Call.State mRingingCallState;
        private CallNumber mRingNumber;
        private int mSubscription;
        private int mActive;
        private int mHeld;
        private int mCallState;
        private int mPhonetype;
        private long mBgndEarliestConnectionTime = 0;
        CdmaPhoneCallState.PhoneCallState mCdmaThreeWayCallState =
                                            CdmaPhoneCallState.PhoneCallState.IDLE;

        private BluetoothSub(int SubId){
            Log.d(TAG, "Creating Bluetooth SUb for " + SubId);
            mForegroundCallState = Call.State.IDLE;
            mRingingCallState = Call.State.IDLE;
            mRingNumber = new CallNumber("", 0);;
            mSubscription = SubId;
            mActive = 0;
            mHeld = 0;
            mCallState = 0;
            for (Phone phone : mCM.getAllPhones()) {
               if (phone != null) {
                   if(phone.getSubscription() == SubId)
                       mPhonetype = phone.getPhoneType();
               }
            }
            Log.d(TAG, "Bluetooth SUB: " + SubId + " for PhoneType: " + mPhonetype);
        }
        /* Handles the single subscription call state changes.*/
        public void handleSubscriptionCallStateChange() {
            // get foreground call state
            int oldNumActive = mActive;
            int oldNumHeld = mHeld;
            Call.State oldRingingCallState = mRingingCallState;
            Call.State oldForegroundCallState = mForegroundCallState;
            CallNumber oldRingNumber = mRingNumber;

            Call foregroundCall = mCM.getActiveFgCall(mSubscription);

            Log.d(TAG, " SUB:" + mSubscription + "foreground: " + foregroundCall +
                  " background: " + mCM.getFirstActiveBgCall(mSubscription)
                  + " ringing: " + mCM.getFirstActiveRingingCall(mSubscription));
            Log.d(TAG, "mActive: " + mActive + " mHeld: " + mHeld);

            mForegroundCallState = foregroundCall.getState();
            /* if in transition, do not update */
            if (mForegroundCallState == Call.State.DISCONNECTING) {
                Log.d(TAG, "SUB1. Call disconnecting,wait before update");
                return;
            }
            else
                mActive = (mForegroundCallState == Call.State.ACTIVE) ? 1 : 0;

            Log.d(TAG, "New state of active call:= " + mActive);
            Call ringingCall = mCM.getFirstActiveRingingCall(mSubscription);
            mRingingCallState = ringingCall.getState();
            mRingNumber = getCallNumber(null, ringingCall);

            if (mPhonetype == PhoneConstants.PHONE_TYPE_CDMA) {
                Log.d(TAG, "CDMA. Get number of held calls on this SUB");
                mHeld = getNumHeldCdmaPhone();
            } else {
                Log.d(TAG, "GSM/WCDMA/UMTS. Get number of held calls on this SUB");
                mHeld = getNumHeldUmts(mSubscription);
            }

            mCallState = convertCallState(mRingingCallState, mForegroundCallState);
            boolean callsSwitched = false;
            if (mPhonetype == PhoneConstants.PHONE_TYPE_CDMA &&
                mCdmaThreeWayCallState ==
                CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
                callsSwitched = mCdmaCallsSwapped;
                Log.d(TAG, "Call switch value for cdma: " + callsSwitched);
            } else {
                Call backgroundCall = mCM.getFirstActiveBgCall(mSubscription);
                callsSwitched = (mHeld == 1 && ! (backgroundCall.getEarliestConnectTime() ==
                mBgndEarliestConnectionTime));
                mBgndEarliestConnectionTime = backgroundCall.getEarliestConnectTime();
                Log.d(TAG, "Call switch value: " + callsSwitched +
                    " mBgndEarliestConnectionTime " + mBgndEarliestConnectionTime);
            }
            mCallSwitch = callsSwitched;

            if (mActive != oldNumActive || mHeld != oldNumHeld ||
                mRingingCallState != oldRingingCallState ||
                mForegroundCallState != oldForegroundCallState ||
                !mRingNumber.equalTo(oldRingNumber) ||
                callsSwitched) {
                Log.d(TAG, "Update the handleSendcallStates for Sub: " + mSubscription);
                handleSendcallStates(mSubscription, mActive, mHeld,
                    convertCallState(mRingingCallState, mForegroundCallState),
                    mRingNumber.mNumber, mRingNumber.mType);
            }
        }

        private int getActive() {
            return mActive;
        }
        private int getHeld() {
            return mHeld;
        }
        public int getCallState() {
            return mCallState;
        }

        private int getNumHeldCdmaPhone() {
            mHeld = getNumHeldCdma();
            PhoneGlobals app = PhoneGlobals.getInstance();
            if (app.cdmaPhoneCallState != null) {
                CdmaPhoneCallState.PhoneCallState currCdmaThreeWayCallState =
                app.cdmaPhoneCallState.getCurrentCallState();
                CdmaPhoneCallState.PhoneCallState prevCdmaThreeWayCallState =
                app.cdmaPhoneCallState.getPreviousCallState();
                log("CDMA call state: " + currCdmaThreeWayCallState +
                    " prev state:" + prevCdmaThreeWayCallState);
                if ((mBluetoothHeadset != null) &&
                    (mCdmaThreeWayCallState != currCdmaThreeWayCallState)) {
                    // In CDMA, the network does not provide any feedback
                    // to the phone when the 2nd MO call goes through the
                    // stages of DIALING > ALERTING -> ACTIVE we fake the
                    // sequence
                    log("CDMA 3way call state change. mNumActive: " + mNumActive +
                    " mNumHeld: " + mNumHeld + "  IsThreeWayCallOrigStateDialing: " +
                    app.cdmaPhoneCallState.IsThreeWayCallOrigStateDialing());

                    if ((currCdmaThreeWayCallState == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE)
                        && app.cdmaPhoneCallState.IsThreeWayCallOrigStateDialing()) {
                        // Mimic dialing, put the call on hold, alerting
                        handleSendcallStates(mSubscription,0, mHeld,
                        convertCallState(Call.State.IDLE, Call.State.DIALING),
                        mRingNumber.mNumber, mRingNumber.mType);

                        handleSendcallStates(mSubscription,0, mHeld,
                        convertCallState(Call.State.IDLE, Call.State.ALERTING),
                        mRingNumber.mNumber, mRingNumber.mType);
                    }
                    // In CDMA, the network does not provide any feedback to
                    // the phone when a user merges a 3way call or swaps
                    // between two calls we need to send a CIEV response
                    // indicating that a call state got changed which should
                    // trigger a CLCC update request from the BT client.
                    if (currCdmaThreeWayCallState == CdmaPhoneCallState.PhoneCallState.CONF_CALL &&
                    prevCdmaThreeWayCallState == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE
                    ) {
                        log("CDMA 3way conf call. mNumActive: " + mNumActive +
                            " mNumHeld: " + mNumHeld);
                        handleSendcallStates(mSubscription, mActive, mHeld,
                        convertCallState(Call.State.IDLE,mForegroundCallState),
                        mRingNumber.mNumber, mRingNumber.mType);
                    }
                }
                mCdmaThreeWayCallState = currCdmaThreeWayCallState;
            }
            return mHeld;
        }

        private int getNumHeldCdma() {
            int numHeld = 0;
            PhoneGlobals app = PhoneGlobals.getInstance();
            if (app.cdmaPhoneCallState != null) {
                CdmaPhoneCallState.PhoneCallState curr3WayCallState =
                app.cdmaPhoneCallState.getCurrentCallState();
                CdmaPhoneCallState.PhoneCallState prev3WayCallState =
                app.cdmaPhoneCallState.getPreviousCallState();
                log("CDMA call state: " + curr3WayCallState + " prev state:" + prev3WayCallState);
                if (curr3WayCallState == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
                if (prev3WayCallState == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                        numHeld = 0; //0: no calls held, as now *both* the caller are active
                    } else {
                        numHeld = 1; //1: held call and active call, as on answering a
                        // Call Waiting, one of the caller *is* put on hold
                    }
                } else if (curr3WayCallState == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                    numHeld = 1; //1: held call and active call, as on make a 3 Way Call
                    // the first caller *is* put on hold
                } else {
                    numHeld = 0; //0: no calls held as this is a SINGLE_ACTIVE call
                }
            }
            return numHeld;
        }

        //Overloaded with Subscription param
        private int getNumHeldUmts(int Sub) {
            int countHeld = 0;
            //TODO..Telephony need to add API for below call
            Call backgroundCall = mCM.getFirstActiveBgCall(mSubscription);
            if (backgroundCall.getState() == Call.State.HOLDING) {
                log("There is a held call on :" + mSubscription);
                countHeld++;
            }
            return countHeld;
        }
    } /* BluetoothSub Class*/


    // DSDA state machine which takes care of sending indicators
    private void handleSendcallStates(int SUB, int numActive, int numHeld, int
                                      callState, String number,int type) {
        //SUB will give info that for which SUB these changes have to be updated
        //Get the states of other SUB..
        int otherSubActive;
        int otherSubHeld;
        int otherSubCallState;
        Log.d(TAG, "mNumActive: " + mNumActive + " mNumHeld: " + mNumHeld);
        Log.d(TAG, "numactive: " + numActive + " numHld: " + numHeld + " Callstate: " +
            callState + " Number: " + number + " type: " + type);

        if (SUB == SUB2) {
            Log.d(TAG, "get call states of sub1");
            otherSubActive = mSubscriptionOne.getActive();
            otherSubHeld = mSubscriptionOne.getHeld();
            //otherSubCallState = mSUB1ForegroundCallState;
            Log.d(TAG, "get call states of sub1 are" + " Active: " +
            otherSubActive + " Held: " + otherSubHeld);
        }
        else {
            Log.d(TAG, "get call states of sub2");
            otherSubActive = mSubscriptionTwo.getActive();
            otherSubHeld = mSubscriptionTwo.getHeld();
            //otherSubCallState = mSUB2ForegroundCallState;
            Log.d(TAG, "get call states of sub2 are" + " Active: " +
           otherSubActive + " Held: " + otherSubHeld);
        }
        if ((mNumActive + mNumHeld) == 2) {
            //Meaning, we are already in a state of max calls
            //Check the current call state.Already sent 4,1
            switch(callState){
                case CALL_STATE_INCOMING:
                    //This makes sure that the
                    // current SUB is not running in max calls
                    if ((numActive + numHeld) < 2) {
                        //Fake Indicator first about call join (callheld =0)
                        mNumHeld = 0;
                        mFakeMultiParty = true;
                        if (mBluetoothHeadset != null) {
                            mBluetoothHeadset.phoneStateChanged(1, 0,CALL_STATE_IDLE,
                                                                null, 0);
                            //Send new incoming call notification
                            mBluetoothHeadset.phoneStateChanged(1, 0,CALL_STATE_INCOMING,
                                                                number, type);
                        }
                    } else if ((numActive + numHeld) == 2) {
                        //Notify the same .HS may reject this call
                        //If this call is accepted, we fall in a case where
                        // telephony will drop one of the current call
                        if (mBluetoothHeadset != null)
                            mBluetoothHeadset.phoneStateChanged(1, 1,CALL_STATE_INCOMING,
                                                                number, type);
                    }
                    break;

                case CALL_STATE_IDLE:
                    //Could come when calls are being dropped/hanged OR
                    //This state could be reached when HF tried to receive the
                    // third call and telephony would have dropped held call on
                    // currnt SUB..OR. HS just rejected the call
                    //TODO
                    //This state is also seen in call switch on same sub
                    if ((numActive + numHeld + otherSubActive + otherSubHeld) >= 2) {
                        // greater than 2 means we have atleast one active one held
                        //no need to update the headset on this
                        //Add log to see which call is dropped
                        if (((numActive + numHeld) == 2) &&
                            (mCallSwitch == true)) {
                            log("Call switch happened on this SUB");
                            if (mBluetoothHeadset != null) {
                                log("update hs");
                                mBluetoothHeadset.phoneStateChanged(mNumActive, mNumHeld,
                                callState,number, type);
                            } else Log.d(TAG, "No need as headset is null");
                        } else if ((otherSubActive + otherSubHeld) >= 1) {
                            log("No update is needed");
                        } else if ((numActive + numHeld) == 1) {
                            log("Call position changed on this sub having single call");
                            //We dont get callSwitch true when call comes frm
                            // held to active
                            if (mBluetoothHeadset != null) {
                                log("update hs for this switch");
                                mBluetoothHeadset.phoneStateChanged(mNumActive, mNumHeld,
                                                          callState,number, type);
                            } else Log.d(TAG, "No need as headset is null");
                        } else log("No update for this call drop");
                    } else {
                         log("Some call may have dropped in current Sub");
                        //Send proper updates
                        // All calls on this SUB are ended now.
                        if (numActive + numHeld == 0) {
                            if (otherSubActive == 1)
                                mNumActive = 1;
                            else mNumActive = 0;
                            if (otherSubHeld == 1)
                                mNumHeld = 1;
                            else mNumHeld = 0;
                        } else {
                             log("the current SUB has more than one call, update");
                            //The current SUB has more than one call
                            //just update the same.
                            mNumActive = numActive;
                            mNumHeld = numHeld;
                        }
                        if (mBluetoothHeadset != null)
                            mBluetoothHeadset.phoneStateChanged(mNumActive,
                                              mNumHeld,CALL_STATE_IDLE,number, type);
                    }
                    break;

                case CALL_STATE_DIALING:
                    //Check if we can honor the dial from this SUB
                    if ((numActive + numHeld) < 2) {
                        // we would have sent 4,1 already before this
                        // It is slight different again compared to incoming call
                        // scenario. Need to check if even in Single SIM, if
                        //dial is allowed when we already have two calls.
                        //In this case we can send 4,0 as it is valid on this sub
                        //Very less chance to have a headset doing this, but if the
                        //user explicitly tries to dial, we may end up here.
                        //Even is Single SIM , this scenario is not well known and
                        if (mBluetoothHeadset != null) {
                            log("call dial,Call join first");
                            mFakeMultiParty = true;
                            if (mBluetoothHeadset != null) {
                                mBluetoothHeadset.phoneStateChanged(1, 0,
                                                  CALL_STATE_IDLE, null, 0);
                                log("call dial,Send dial with held call");
                                mBluetoothHeadset.phoneStateChanged(0, 1,
                                                  callState, number, type);
                            }
                        }
                        mNumActive = 0;
                        mNumHeld = 1;
                    } else if (numActive + numHeld == 2) {
                        // Tossed up case.
                    }
                    break;

                case CALL_STATE_ALERTING:
                    //numHeld may be 1 here
                    if ((numActive + numHeld) < 2) {
                        //Just update the call state
                        mBluetoothHeadset.phoneStateChanged(0, 1,
                        callState, number, type);
                    }
                    break;

                default:
                    break;
            }
        } else if ((mNumActive == 1) || (mNumHeld == 1)) {
            //We have atleast one call.It could be active or held
            //just notify about the incoming call.
            switch(callState){
                case CALL_STATE_INCOMING:
                    //No change now, just send the new call states
                    Log.d(TAG,"Incoming call while we have active or held already present");
                    Log.d(TAG, " just update the new incoming call state");
                    if (mBluetoothHeadset != null) {
                        mBluetoothHeadset.phoneStateChanged(mNumActive, mNumHeld,
                        callState,number, type);
                    } else Log.d(TAG, "No need as headset is null");
                    break;

                case CALL_STATE_DIALING:
                    //We should see that active call is put on hold
                    //Since, we alread have one call, make sure q
                    //we are getting dial in already having call
                    if ((numActive == 0) && (numHeld == 1)) {
                        Log.d(TAG, "we are getting dial in already having call");
                        mNumActive = numActive;
                        mNumHeld = numHeld;
                    }
                    if(((otherSubActive == 1) || (otherSubHeld == 1)) &&
                        (numActive == 0)) {
                        log("This is new dial on this sub when a call is active on other sub");
                        //Make sure we send held=1 and active = 0
                        //Before dialing, the active call becomes held.
                        //In DSDA we have to fake it
                        mNumActive = 0;
                        mNumHeld = 1;
                    }
                    //Send the update
                    if (mBluetoothHeadset != null) {
                        mBluetoothHeadset.phoneStateChanged(mNumActive, mNumHeld,
                        callState,number, type);
                    } else Log.d(TAG, "No need as headset is null here");

                    break;

                case CALL_STATE_ALERTING:
                    //Just send update
                    Log.d(TAG, "Just send update for ALERT");
                    if (mBluetoothHeadset != null) {
                        mBluetoothHeadset.phoneStateChanged(mNumActive, mNumHeld,
                        callState,number, type);
                    } else Log.d(TAG, "No need as headset is null");

                    break;

                case CALL_STATE_IDLE:
                    //Call could be dropped by now.
                    //Here we have to decide if we need to update held call for
                    // switch SUB etc. Idle could be call accept or reject.Act
                    // properly
                    //Update the active call and held call only when they are zero
                    if (mNumActive == 0) {
                        if ((numActive == 1) || (otherSubActive == 1)) {
                            mNumActive = 1;
                            Log.d(TAG,"New active call on SUB: " + SUB);
                        }
                    } else if (mNumActive == 1) { /* Call dropped, update mNumActive properly*/
                        if(numActive == 0) {
                            Log.d(TAG,"Active Call state changed to 0: " + SUB);
                            if(otherSubActive == 0)
                                mNumActive = numActive;
                        }
                    }
                    if (mNumHeld == 1) {
                        //Update the values properly
                        log("Update the values properly");
                        if ((numActive + numHeld + otherSubActive +
                             otherSubHeld) < 2)
                            mNumHeld = 0;
                    } else {
                        //There is no held call
                        log("There was no held call previously");
                        if (((otherSubActive == 1) || (otherSubHeld == 1)) &&
                        ((numActive == 1) || (numHeld == 1))) {
                            // Switch SUB happened
                            //This will come for single sub case of 1 active, 1
                            // new call
                            Log.d(TAG,"Switch SUB happened, fake callheld");
                            mNumHeld = 1; // Fake 1 active , 1held, TRICKY
                        } else if (mNumHeld == 0) {
                            Log.d(TAG,"Update Held as value on this sub: " + numHeld);
                            mNumHeld = numHeld;
                        }
                    }
                    //This could be tricky as we may move suddenly from 4,0 t0 4,1
                    // even when  the new call was rejected.
                    if (mBluetoothHeadset != null) {
                        Log.d(TAG, "updating headset");
                        mBluetoothHeadset.phoneStateChanged(mNumActive, mNumHeld,
                                          callState,number, type);
                    } else Log.d(TAG, "No need as headset is null");

                    break;
            }
        } else{
            //This is first of the calls, update properly
            Log.d(TAG, "This is first of the calls, update properly");
            mNumActive = numActive;
            mNumHeld = numHeld;
            if (mBluetoothHeadset != null) {
                Log.d(TAG, "updating headset");
                mBluetoothHeadset.phoneStateChanged(mNumActive, mNumHeld,
                                 callState,number, type);
            } else Log.d(TAG, "No need as headset is null");
        }
        if (mFakeMultiParty == true) {
            if (((mSubscriptionOne.mActive + mSubscriptionOne.mHeld
                + mSubscriptionTwo.mActive + mSubscriptionTwo.mHeld) <= 2)
                && ((mSubscriptionOne.mCallState == CALL_STATE_IDLE)
                && ((mSubscriptionTwo.mCallState == CALL_STATE_IDLE)))) {
                log("Reset mFakeMultiParty");
                mFakeMultiParty = false;
            }
        }
    }

    private int getNumHeldUmts() {
        int countHeld = 0;
        List<Call> heldCalls = mCM.getBackgroundCalls();

        for (Call call : heldCalls) {
            if (call.getState() == Call.State.HOLDING) {
                countHeld++;
            }
        }
        return countHeld;
    }

    private CallNumber getCallNumber(Connection connection, Call call) {
        String number = null;
        int type = 128;
        // find phone number and type
        if (connection == null) {
            connection = call.getEarliestConnection();
            if (connection == null) {
                Log.e(TAG, "Could not get a handle on Connection object for the call");
            }
        }
        if (connection != null) {
            Log.e(TAG, "get a handle on Connection object for the call");
            number = connection.getAddress();
            if (number != null) {
                type = PhoneNumberUtils.toaFromString(number);
            }
        }
        if (number == null) {
            number = "";
        }
        return new CallNumber(number, type);
    }

    private class CallNumber
    {
        private String mNumber = null;
        private int mType = 0;

        private CallNumber(String number, int type) {
            mNumber = number;
            mType = type;
        }

        private boolean equalTo(CallNumber callNumber)
        {
            if (mType != callNumber.mType) return false;

            if (mNumber != null && mNumber.compareTo(callNumber.mNumber) == 0) {
                return true;
            }
            return false;
        }
    }

    /* Convert telephony phone call state into hf hal call state */
    static int convertCallState(Call.State ringingState, Call.State foregroundState) {
        if ((ringingState == Call.State.INCOMING) ||
            (ringingState == Call.State.WAITING) )
            return CALL_STATE_INCOMING;
        else if (foregroundState == Call.State.DIALING)
            return CALL_STATE_DIALING;
        else if (foregroundState == Call.State.ALERTING)
            return CALL_STATE_ALERTING;
        else
            return CALL_STATE_IDLE;
    }

    static int convertCallState(Call.State callState) {
        switch (callState) {
        case IDLE:
        case DISCONNECTED:
        case DISCONNECTING:
            return CALL_STATE_IDLE;
        case ACTIVE:
            return CALL_STATE_ACTIVE;
        case HOLDING:
            return CALL_STATE_HELD;
        case DIALING:
            return CALL_STATE_DIALING;
        case ALERTING:
            return CALL_STATE_ALERTING;
        case INCOMING:
            return CALL_STATE_INCOMING;
        case WAITING:
            return CALL_STATE_WAITING;
        default:
            Log.e(TAG, "bad call state: " + callState);
            return CALL_STATE_IDLE;
        }
    }

    private static void log(String msg) {
        if (DBG) Log.d(TAG, msg);
    }
}
