/*
 * Copyright (c) 2012-2013, The Linux Foundation. All rights reserved.
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
 *     * Neither the name of The Linux Foundation, Inc. nor the names of its
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

import java.lang.Integer;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.telephony.MSimTelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout.LayoutParams;
import android.widget.TextView;
import android.widget.Toast;

import com.codeaurora.telephony.msim.CardSubscriptionManager;
import com.codeaurora.telephony.msim.SubscriptionData;
import com.codeaurora.telephony.msim.Subscription;
import com.codeaurora.telephony.msim.SubscriptionManager;

/**
 * Displays a dialer like interface to Set the Subscriptions.
 */
public class SetSubscription extends PreferenceActivity implements View.OnClickListener,
       DialogInterface.OnDismissListener, DialogInterface.OnClickListener {

    private static final String TAG = "SetSubscription";
    public static final int SUBSCRIPTION_INDEX_INVALID = 99999;

    private TextView mOkButton, mCancelButton;
    CheckBoxPreference subArray[];
    private boolean subErr = false;
    private SubscriptionData[] mCardSubscrInfo;
    private SubscriptionData mCurrentSelSub;
    private SubscriptionData mUserSelSub;
    private SubscriptionManager mSubscriptionManager;
    private CardSubscriptionManager mCardSubscriptionManager;
    //mIsForeground is added to track if activity is in foreground
    private boolean mIsForeground = false;

    //String keys for preference lookup
    private static final String PREF_PARENT_KEY = "subscr_parent";

    private final int MAX_SUBSCRIPTIONS = SubscriptionManager.NUM_SUBSCRIPTIONS;

    private final int EVENT_SET_SUBSCRIPTION_DONE = 1;

    private final int EVENT_SIM_STATE_CHANGED = 2;

    private final int DIALOG_SET_SUBSCRIPTION_IN_PROGRESS = 100;

    public void onCreate(Bundle icicle) {
        boolean newCardNotify = getIntent().getBooleanExtra("NOTIFY_NEW_CARD_AVAILABLE", false);
        if (!newCardNotify) {
            setTheme(android.R.style.Theme);
        }
        super.onCreate(icicle);

        mSubscriptionManager = SubscriptionManager.getInstance();
        mCardSubscriptionManager = CardSubscriptionManager.getInstance();

        if (newCardNotify) {
            Log.d(TAG, "onCreate: Notify new cards are available!!!!");
            notifyNewCardAvailable();
        } else {
            // get the card subscription info from the Proxy Manager.
            mCardSubscrInfo = new SubscriptionData[MAX_SUBSCRIPTIONS];
            for (int i = 0; i < MAX_SUBSCRIPTIONS; i++) {
                mCardSubscrInfo[i] = mCardSubscriptionManager.getCardSubscriptions(i);
            }

            addPreferencesFromResource(R.xml.set_subscription_pref);
            setContentView(R.layout.set_subscription_pref_layout);

            mOkButton = (TextView) findViewById(R.id.ok);
            mOkButton.setOnClickListener(this);
            mCancelButton = (TextView) findViewById(R.id.cancel);
            mCancelButton.setOnClickListener(this);

            // To store the selected subscriptions
            // index 0 for sub0 and index 1 for sub1
            subArray = new CheckBoxPreference[MAX_SUBSCRIPTIONS];

            if(mCardSubscrInfo != null) {
                populateList();

                mUserSelSub = new SubscriptionData(MAX_SUBSCRIPTIONS);

                updateCheckBoxes();
            } else {
                Log.d(TAG, "onCreate: Card info not available: mCardSubscrInfo == NULL");
            }

            mCardSubscriptionManager.registerForSimStateChanged(mHandler,
                    EVENT_SIM_STATE_CHANGED, null);
            if (mSubscriptionManager.isSetSubscriptionInProgress()) {
                Log.d(TAG, "onCreate: SetSubscription is in progress when started this activity");
                showDialog(DIALOG_SET_SUBSCRIPTION_IN_PROGRESS);
                mSubscriptionManager.registerForSetSubscriptionCompleted(
                        mHandler, EVENT_SET_SUBSCRIPTION_DONE, null);
            }
        }
        IntentFilter intentFilter =
                new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        registerReceiver(new AirplaneModeBroadcastReceiver(), intentFilter);
    }

    /**
     * * Receiver for Airplane mode changed intent broadcasts.
     **/
    private class AirplaneModeBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                if (isAirplaneModeOn()) {
                    Log.d(TAG, "Airplane mode is: on ");
                    finish();
                }
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mIsForeground = true;
    }

    @Override
    public void onPause() {
        super.onPause();
        mIsForeground = false;
    }

    protected void onDestroy () {
        super.onDestroy();
        mCardSubscriptionManager.unRegisterForSimStateChanged(mHandler);
        mSubscriptionManager.unRegisterForSetSubscriptionCompleted(mHandler);
    }

    private boolean isAirplaneModeOn() {
        return Settings.System.getInt(getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0) != 0;
    }

    private void notifyNewCardAvailable() {
        Log.d(TAG, "notifyNewCardAvailable()");

        new AlertDialog.Builder(this).setMessage(R.string.new_cards_available)
            .setTitle(R.string.config_sub_title)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        Log.d(TAG, "new card dialog box:  onClick");
                        //finish();
                    }
                })
            .show()
            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    public void onDismiss(DialogInterface dialog) {
                        Log.d(TAG, "new card dialog box:  onDismiss");
                        finish();
                    }
                });
    }

    private void updateCheckBoxes() {

        PreferenceScreen prefParent = (PreferenceScreen) getPreferenceScreen()
                                             .findPreference(PREF_PARENT_KEY);
        for (int i = 0; i < mCardSubscrInfo.length; i++) {
            PreferenceCategory subGroup = (PreferenceCategory) prefParent
                   .findPreference("sub_group_" + i);
            if (subGroup != null) {
                int count = subGroup.getPreferenceCount();
                Log.d(TAG, "updateCheckBoxes count = " + count);
                for (int j = 0; j < count; j++) {
                    CheckBoxPreference checkBoxPref =
                              (CheckBoxPreference) subGroup.getPreference(j);
                    checkBoxPref.setChecked(false);
                }
            }
        }

        mCurrentSelSub = new SubscriptionData(MAX_SUBSCRIPTIONS);
        for (int i = 0; i < MAX_SUBSCRIPTIONS; i++) {
            Subscription sub = mSubscriptionManager.getCurrentSubscription(i);
                    mCurrentSelSub.subscription[i].copyFrom(sub);
        }

        if (mCurrentSelSub != null) {
            for (int i = 0; i < MAX_SUBSCRIPTIONS; i++) {
                Log.d(TAG, "updateCheckBoxes: mCurrentSelSub.subscription[" + i + "] = "
                           + mCurrentSelSub.subscription[i]);
                subArray[i] = null;
                if (mCurrentSelSub.subscription[i].subStatus ==
                        Subscription.SubscriptionStatus.SUB_ACTIVATED) {
                    String key = "slot" + mCurrentSelSub.subscription[i].slotId
                                 + " index" + mCurrentSelSub.subscription[i].getAppIndex();

                    Log.d(TAG, "updateCheckBoxes: key = " + key);

                    PreferenceCategory subGroup = (PreferenceCategory) prefParent
                           .findPreference("sub_group_" + mCurrentSelSub.subscription[i].slotId);
                    if (subGroup != null) {
                        CheckBoxPreference checkBoxPref =
                               (CheckBoxPreference) subGroup.findPreference(key);
                        checkBoxPref.setChecked(true);
                        subArray[i] = checkBoxPref;
                    }
                }
            }
            mUserSelSub.copyFrom(mCurrentSelSub);
        }
    }

    /** add radio buttons to the group */
    private void populateList() {
        PreferenceScreen prefParent = (PreferenceScreen) getPreferenceScreen().
                findPreference(PREF_PARENT_KEY);
        int[] subGroupTitle = {R.string.card_01, R.string.card_02, R.string.card_03};

        Log.d(TAG, "populateList:  mCardSubscrInfo.length = " + mCardSubscrInfo.length);

        int k = 0;
        // Create PreferenceCatergory sub groups for each card.
        for (SubscriptionData cardSub : mCardSubscrInfo) {
            if ((cardSub != null ) && (cardSub.getLength() > 0)) {
                int i = 0;

                // Create a subgroup for the apps in card 01
                PreferenceCategory subGroup = new PreferenceCategory(this);
                subGroup.setKey("sub_group_" + k);
                subGroup.setTitle(subGroupTitle[k]);
                prefParent.addPreference(subGroup);

                // Add each element as a CheckBoxPreference to the group
                for (Subscription sub : cardSub.subscription) {
                    if (sub != null && sub.appType != null) {
                        Log.d(TAG, "populateList:  mCardSubscrInfo[" + k + "].subscription["
                                + i + "] = " + sub);
                        CheckBoxPreference newCheckBox = new CheckBoxPreference(this);
                        newCheckBox.setTitle((sub.appType).subSequence(0, (sub.appType).length()));
                        // Key is the string : "slot<SlotId> index<IndexId>"
                        newCheckBox.setKey(new String("slot" + k + " index" + i));
                        newCheckBox.setOnPreferenceClickListener(mCheckBoxListener);
                        subGroup.addPreference(newCheckBox);
                    }
                    i++;
                }
            }
            k++;
        }
    }

    Preference.OnPreferenceClickListener mCheckBoxListener =
            new Preference.OnPreferenceClickListener() {
        public boolean onPreferenceClick(Preference preference) {
            CheckBoxPreference subPref = (CheckBoxPreference)preference;
            String key = subPref.getKey();
            Log.d(TAG, "setSubscription: key = " + key);
            String splitKey[] = key.split(" ");
            String sSlotId = splitKey[0].substring(splitKey[0].indexOf("slot") + 4);
            int slotIndex = Integer.parseInt(sSlotId);

            if (subPref.isChecked()) {
                if (subArray[slotIndex] != null) {
                    subArray[slotIndex].setChecked(false);
                }
                subArray[slotIndex] = subPref;
            } else {
                subArray[slotIndex] = null;
            }
            return true;
        }
    };

    // for View.OnClickListener
    public void onClick(View v) {
        if (v == mOkButton) {
            setSubscription();
        } else if (v == mCancelButton) {
            finish();
        }
    }

    private void setSubscription() {
        Log.d(TAG, "setSubscription");

        int numSubSelected = 0;
        int deactRequiredCount = 0;
        subErr = false;

        for (int i = 0; i < subArray.length; i++) {
            if (subArray[i] != null) {
                numSubSelected++;
            }
        }

        Log.d(TAG, "setSubscription: numSubSelected = " + numSubSelected);

        if (numSubSelected == 0) {
            // Show a message to prompt the user to select atleast one.
            Toast toast = Toast.makeText(getApplicationContext(),
                    R.string.set_subscription_error_atleast_one,
                    Toast.LENGTH_SHORT);
            toast.show();
        } else if (isPhoneInCall()) {
            // User is not allowed to activate or deactivate the subscriptions
            // while in a voice call.
            displayErrorDialog(R.string.set_sub_not_supported_phone_in_call);
        } else {
            for (int i = 0; i < MAX_SUBSCRIPTIONS; i++) {
                if (subArray[i] == null) {
                    if (mCurrentSelSub.subscription[i].subStatus ==
                            Subscription.SubscriptionStatus.SUB_ACTIVATED) {
                        Log.d(TAG, "setSubscription: Sub " + i + " not selected. Setting 99999");
                        mUserSelSub.subscription[i].slotId = SUBSCRIPTION_INDEX_INVALID;
                        mUserSelSub.subscription[i].m3gppIndex = SUBSCRIPTION_INDEX_INVALID;
                        mUserSelSub.subscription[i].m3gpp2Index = SUBSCRIPTION_INDEX_INVALID;
                        mUserSelSub.subscription[i].subId = i;
                        mUserSelSub.subscription[i].subStatus = Subscription.
                                SubscriptionStatus.SUB_DEACTIVATE;

                        deactRequiredCount++;
                    }
                } else {
                    // Key is the string :  "slot<SlotId> index<IndexId>"
                    // Split the string into two and get the SlotId and IndexId.
                    String key = subArray[i].getKey();
                    Log.d(TAG, "setSubscription: key = " + key);
                    String splitKey[] = key.split(" ");
                    String sSlotId = splitKey[0].substring(splitKey[0].indexOf("slot") + 4);
                    int slotId = Integer.parseInt(sSlotId);
                    String sIndexId = splitKey[1].substring(splitKey[1].indexOf("index") + 5);
                    int subIndex = Integer.parseInt(sIndexId);

                    if (mCardSubscrInfo[slotId] == null) {
                        Log.d(TAG, "setSubscription: mCardSubscrInfo is not in sync "
                                + "with SubscriptionManager");
                        mUserSelSub.subscription[i].slotId = SUBSCRIPTION_INDEX_INVALID;
                        mUserSelSub.subscription[i].m3gppIndex = SUBSCRIPTION_INDEX_INVALID;
                        mUserSelSub.subscription[i].m3gpp2Index = SUBSCRIPTION_INDEX_INVALID;
                        mUserSelSub.subscription[i].subId = i;
                        mUserSelSub.subscription[i].subStatus = Subscription.
                                SubscriptionStatus.SUB_DEACTIVATE;

                        if (mCurrentSelSub.subscription[i].subStatus ==
                                Subscription.SubscriptionStatus.SUB_ACTIVATED) {
                            deactRequiredCount++;
                        }
                        continue;
                    }


                    // Compate the user selected subscriptio with the current subscriptions.
                    // If they are not matching, mark it to activate.
                    mUserSelSub.subscription[i].copyFrom(mCardSubscrInfo[slotId].
                            subscription[subIndex]);
                    mUserSelSub.subscription[i].subId = i;
                    if (mCurrentSelSub != null) {
                        // subStatus used to store the activation status as the mCardSubscrInfo
                        // is not keeping track of the activation status.
                        Subscription.SubscriptionStatus subStatus =
                                mCurrentSelSub.subscription[i].subStatus;
                        mUserSelSub.subscription[i].subStatus = subStatus;
                        if ((subStatus != Subscription.SubscriptionStatus.SUB_ACTIVATED) ||
                            (!mUserSelSub.subscription[i].equals(mCurrentSelSub.subscription[i]))) {
                            // User selected a new subscription.  Need to activate this.
                            mUserSelSub.subscription[i].subStatus = Subscription.
                            SubscriptionStatus.SUB_ACTIVATE;
                        }

                        if (mCurrentSelSub.subscription[i].subStatus == Subscription.
                                 SubscriptionStatus.SUB_ACTIVATED
                                 && mUserSelSub.subscription[i].subStatus == Subscription.
                                 SubscriptionStatus.SUB_ACTIVATE) {
                            deactRequiredCount++;
                        }
                    } else {
                        mUserSelSub.subscription[i].subStatus = Subscription.
                                SubscriptionStatus.SUB_ACTIVATE;
                    }
                }
            }

            if (deactRequiredCount >= MAX_SUBSCRIPTIONS) {
                displayErrorDialog(R.string.deact_all_sub_not_supported);
            } else {
                boolean ret = mSubscriptionManager.setSubscription(mUserSelSub);
                if (ret) {
                    if(mIsForeground){
                       showDialog(DIALOG_SET_SUBSCRIPTION_IN_PROGRESS);
                    }
                    mSubscriptionManager.registerForSetSubscriptionCompleted(mHandler,
                            EVENT_SET_SUBSCRIPTION_DONE, null);
                } else {
                    //TODO: Already some set sub in progress. Display a Toast?
                }
            }
        }
    }

    private boolean isPhoneInCall() {
        boolean phoneInCall = false;
        for (int i = 0; i < MAX_SUBSCRIPTIONS; i++) {
            if (MSimTelephonyManager.getDefault().getCallState(i)
                    != TelephonyManager.CALL_STATE_IDLE) {
                phoneInCall = true;
                break;
            }
        }
        return phoneInCall;
    }

    /**
     *  Displays an dialog box with error message.
     *  "Deactivation of both subscription is not supported"
     */
    private void displayErrorDialog(int messageId) {
        Log.d(TAG, "errorMutipleDeactivate(): " + getResources().getString(messageId));

        new AlertDialog.Builder(this)
            .setTitle(R.string.config_sub_title)
            .setMessage(messageId)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        Log.d(TAG, "errorMutipleDeactivate:  onClick");
                        updateCheckBoxes();
                    }
                })
            .show()
            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    public void onDismiss(DialogInterface dialog) {
                        Log.d(TAG, "errorMutipleDeactivate:  onDismiss");
                        updateCheckBoxes();
                    }
                });
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar;

            switch(msg.what) {
                case EVENT_SET_SUBSCRIPTION_DONE:
                    Log.d(TAG, "EVENT_SET_SUBSCRIPTION_DONE");
                    mSubscriptionManager.unRegisterForSetSubscriptionCompleted(mHandler);
                    dismissDialogSafely(DIALOG_SET_SUBSCRIPTION_IN_PROGRESS);

                    getPreferenceScreen().setEnabled(true);
                    ar = (AsyncResult) msg.obj;

                    String result[] = (String[]) ar.result;

                    if (result != null) {
                        displayAlertDialog(result);
                    } else {
                        finish();
                    }
                    break;
                case EVENT_SIM_STATE_CHANGED:
                    Log.d(TAG, "EVENT_SIM_STATE_CHANGED");
                    PreferenceScreen prefParent = (PreferenceScreen) getPreferenceScreen()
                                             .findPreference(PREF_PARENT_KEY);

                    for (int i = 0; i < mCardSubscrInfo.length; i++) {
                        PreferenceCategory subGroup = (PreferenceCategory) prefParent
                                 .findPreference("sub_group_" + i);
                        if (subGroup != null) {
                            subGroup.removeAll();
                        }
                    }
                    prefParent.removeAll();
                    populateList();
                    updateCheckBoxes();
                    break;
            }
        }
    };

    @Override
    protected Dialog onCreateDialog(int id) {
        if (id == DIALOG_SET_SUBSCRIPTION_IN_PROGRESS) {
            ProgressDialog dialog = new ProgressDialog(this);

            dialog.setMessage(getResources().getString(R.string.set_uicc_subscription_progress));
            dialog.setCancelable(false);
            dialog.setIndeterminate(true);

            return dialog;
        }
        return null;
    }

    @Override
    protected void onPrepareDialog(int id, Dialog dialog) {
        if (id == DIALOG_SET_SUBSCRIPTION_IN_PROGRESS) {
            // when the dialogs come up, we'll need to indicate that
            // we're in a busy state to disallow further input.
            getPreferenceScreen().setEnabled(false);
        }
    }
    private boolean isFailed(String status) {
        Log.d(TAG, "isFailed(" + status + ")");
        if (status == null ||
            (status != null &&
             (status.equals(SubscriptionManager.SUB_DEACTIVATE_FAILED)
              || status.equals(SubscriptionManager.SUB_DEACTIVATE_NOT_SUPPORTED)
              || status.equals(SubscriptionManager.SUB_ACTIVATE_FAILED)
              || status.equals(SubscriptionManager.SUB_ACTIVATE_NOT_SUPPORTED)))) {
            return true;
        }
        return false;
    }

    String setSubscriptionStatusToString(String status) {
        String retStr = null;
        if (status.equals(SubscriptionManager.SUB_ACTIVATE_SUCCESS)) {
            retStr = getResources().getString(R.string.set_sub_activate_success);
        } else if (status.equals(SubscriptionManager.SUB_DEACTIVATE_SUCCESS)) {
            retStr = getResources().getString(R.string.set_sub_deactivate_success);
        } else if (status.equals(SubscriptionManager.SUB_DEACTIVATE_FAILED)) {
            retStr = getResources().getString(R.string.set_sub_deactivate_failed);
        } else if (status.equals(SubscriptionManager.SUB_DEACTIVATE_NOT_SUPPORTED)) {
            retStr = getResources().getString(R.string.set_sub_deactivate_not_supported);
        } else if (status.equals(SubscriptionManager.SUB_ACTIVATE_FAILED)) {
            retStr = getResources().getString(R.string.set_sub_activate_failed);
        } else if (status.equals(SubscriptionManager.SUB_GLOBAL_ACTIVATE_FAILED)) {
            retStr = getResources().getString(R.string.set_sub_global_activate_failed);
        } else if (status.equals(SubscriptionManager.SUB_GLOBAL_DEACTIVATE_FAILED)) {
            retStr = getResources().getString(R.string.set_sub_global_deactivate_failed);
        } else if (status.equals(SubscriptionManager.SUB_ACTIVATE_NOT_SUPPORTED)) {
            retStr = getResources().getString(R.string.set_sub_activate_not_supported);
        } else if (status.equals(SubscriptionManager.SUB_NOT_CHANGED)) {
            retStr = getResources().getString(R.string.set_sub_no_change);
        }
        return retStr;
    }

    void displayAlertDialog(String msg[]) {
        int resSubId[] = {R.string.set_sub_1, R.string.set_sub_2, R.string.set_sub_3};
        String dispMsg = "";
        int title = R.string.set_sub_failed;

        if (msg[0] != null && isFailed(msg[0])) {
            subErr = true;
        }
        if (msg[1] != null && isFailed(msg[1])) {
            subErr = true;
        }

        for (int i = 0; i < msg.length; i++) {
            if (msg[i] != null) {
                dispMsg = dispMsg + getResources().getString(resSubId[i]) +
                                      setSubscriptionStatusToString(msg[i]) + "\n";
            }
        }

        if (!subErr) {
            title = R.string.set_sub_success;
        }

        Log.d(TAG, "displayAlertDialog:  dispMsg = " + dispMsg);
        new AlertDialog.Builder(this).setMessage(dispMsg)
            .setTitle(title)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(android.R.string.yes, this)
            .show()
            .setOnDismissListener(this);
    }

    // This is a method implemented for DialogInterface.OnDismissListener
    public void onDismiss(DialogInterface dialog) {
        // If the setSubscription failed for any of the sub, then don'd dismiss the
        // set subscription screen.
        if(!subErr) {
            finish();
        }
    }

    // This is a method implemented for DialogInterface.OnClickListener.
    // Used to dismiss the dialogs when they come up.
    public void onClick(DialogInterface dialog, int which) {
        // If the setSubscription failed for any of the sub, then don'd dismiss the
        // set subscription screen.
        if(!subErr) {
            finish();
        }
        updateCheckBoxes();
    }


    private void dismissDialogSafely(int id) {
        Log.d(TAG, "dismissDialogSafely: id = " + id);
        try {
            dismissDialog(id);
        } catch (IllegalArgumentException e) {
            // This is expected in the case where we were in the background
            // at the time we would normally have shown the dialog, so we didn't
            // show it.
        }
    }
}
