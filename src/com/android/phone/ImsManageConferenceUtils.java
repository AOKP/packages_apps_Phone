/*
 * Copyright (c) 2013 The Linux Foundation. All rights reserved.
 * Not a Contribution
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

import android.os.RemoteException;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.ImageButton;
import android.widget.TextView;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.CallerInfoAsyncQuery;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.CallStateException;

import java.util.List;

/**
 * Helper class to initialize and run the InCallScreen's "Manage conference" UI.
 */
public class ImsManageConferenceUtils extends ManageConferenceUtils {

    private static final String LOG_TAG = "ImsManageConferenceUtils";
    private static final boolean DBG = true;

    private String[] mUriListInConf;

    private PhoneGlobals mApp;

    // TODO Limitation - only 5 users can be supported
    private static final int MAX_CALLERS_IN_CONFERENCE = 5;

    public ImsManageConferenceUtils(InCallScreen inCallScreen, CallManager cm) {
        super(inCallScreen, cm);
        mApp = PhoneGlobals.getInstance();
    }

    /**
     *
     * Updates the "Manage conference" UI based on the specified List of
     * connections. This is the method used for both for CS voice calls and IMS
     * if IMS is supported For CS calls this method will simply fall through to
     * super class method The purpose of this method is to handle IMS specific
     * scenarios
     *
     * @param connections the List of connections belonging to the current
     *            foreground call; size must be greater than 1 (or it wouldn't
     *            be a conference call in the first place.)
     */
    @Override
    public void updateManageConferencePanel(List<Connection> connections) {
        Call call = mCM.getActiveFgCall();
        Connection connection = null;
        if (call != null)
            connection = call.getLatestConnection();

        if (connection != null && call.isMultiparty() &&
                call.getPhone().getPhoneType() == PhoneConstants.PHONE_TYPE_IMS)
        {
            if (mApp.mImsService != null) {
                try {
                    mUriListInConf = mApp.mImsService.getUriListinConf();
                } catch (RemoteException ex) {
                    Log.d(LOG_TAG, "Ims Service getUriListinConf exception", ex);
                }
                if (mUriListInConf != null) {
                    mNumCallersInConference = mUriListInConf.length;
                    Log.d(LOG_TAG, "mNumCallersInConference " + mNumCallersInConference);
                    for (int i = 0; i < MAX_CALLERS_IN_CONFERENCE; i++) {
                        if (i < mNumCallersInConference) {
                            // Fill in the row in the UI for this caller.
                            updateManageConferenceRow(i, connection, false, mUriListInConf[i]);
                        } else {
                            // Blank out this row in the UI
                            updateManageConferenceRow(i, null, false);
                        }
                    }
                    return;
                }
            }
        }
        super.updateManageConferencePanel(connections);
    }

    /**
     * Updates a single row of the "Manage conference" UI. (One
     * row in this UI represents a single caller in the conference.)
     *
     * @param i the row to update
     * @param connection the Connection corresponding to this caller. If null,
     *            that means this is an "empty slot" in the conference, so hide
     *            this row in the UI.
     * @param canSeparate if true, show a "Separate" (i.e. "Private") button on
     *            this row in the UI.
     */
    private void updateManageConferenceRow(final int i,
            final Connection connection,
            boolean canSeparate,
            final String uri) {

        if (connection != null) {
            // Activate this row of the Manage conference panel:
            mConferenceCallList[i].setVisibility(View.VISIBLE);

            // get the relevant children views
            View endButton = mConferenceCallList[i].findViewById(R.id.conferenceCallerDisconnect);
            View separateButton = mConferenceCallList[i].findViewById(
                    R.id.conferenceCallerSeparate);
            TextView nameTextView = (TextView) mConferenceCallList[i].findViewById(
                    R.id.conferenceCallerName);
            TextView numberTextView = (TextView) mConferenceCallList[i].findViewById(
                    R.id.conferenceCallerNumber);
            TextView numberTypeTextView = (TextView) mConferenceCallList[i].findViewById(
                    R.id.conferenceCallerNumberType);

            // Hook up this row's buttons.
            View.OnClickListener endThisConnection = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    endConferenceConnection(i, connection, uri);
                    PhoneGlobals.getInstance().pokeUserActivity();
                }
            };
            endButton.setOnClickListener(endThisConnection);

            // Separate not supported for IMS
            separateButton.setVisibility(View.INVISIBLE);

            // display the CallerInfo.
            displayCallerInfoForConferenceRow(uri, nameTextView, numberTypeTextView, numberTextView);

        } else {
            // Disable this row of the Manage conference panel:
            mConferenceCallList[i].setVisibility(View.GONE);
        }
    }

    /**
     * Ends the specified connection on a conference call. This method is run
     * (via a closure containing a row index and Connection) when the user
     * clicks the "End" button on a specific row in the Manage conference UI.
     * This is used only for IMS calls to hangup the user through the uri
     */
    private void endConferenceConnection(int i, Connection connection, String uri) {
        // if (DBG) log("===> ENDING conference connection " + i
        // + ": Connection " + connection);
        // The actual work of ending the connection:
        // No need to manually update the "Manage conference" UI here;
        // that'll happen automatically very soon (when we get the
        // onDisconnect() callback triggered by this hangup() call.)

        int connId = 0;
        try {
            connId = (connection != null) ? connection.getIndex() : connId;
        } catch (CallStateException ex) {
            Log.d(LOG_TAG, "Conn id for end is not found " + ex);
        }

        if (mApp.mImsService != null) {
            try {
                mApp.mImsService.hangupUri(connId, uri, null);
            } catch (RemoteException ex) {
                Log.d(LOG_TAG, "Ims Service hangupUri exception", ex);
            }
        }
    }

    /**
     * Helper function to fill out the Conference Call(er) information for each
     * item in the "Manage Conference Call" list.
     *
     * @param presentation presentation specified by {@link Connection}.
     */
    private final void displayCallerInfoForConferenceRow(String uri,
            TextView nameTextView, TextView numberTypeTextView, TextView numberTextView) {
        // gather the correct name and number information.
        String callerName = "";
        String callerNumber = "";
        String callerNumberType = "";

        callerNumber = uri;
        // set the caller name
        nameTextView.setText(callerName);

        // TODO lookup conference event xml/phonebook for other display
        // information. Currently name lookup is not supported
        if (TextUtils.isEmpty(callerNumber)) {
            numberTextView.setVisibility(View.GONE);
            numberTypeTextView.setVisibility(View.GONE);
        } else {
            numberTextView.setVisibility(View.VISIBLE);
            numberTextView.setText(callerNumber);
            numberTypeTextView.setVisibility(View.VISIBLE);
            numberTypeTextView.setText(callerNumberType);
        }
    }
}