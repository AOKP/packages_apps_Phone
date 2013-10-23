/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (c) 2011-2013 The Linux Foundation. All rights reserved.
 *
 * Not a Contribution, Apache license notifications and license are retained
 * for attribution purposes only
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

import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.content.res.Resources;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;

import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;

/**
 * List of Network-specific settings screens.
 */
public class GsmUmtsOptions {
    private static final String LOG_TAG = "GsmUmtsOptions";

    private PreferenceScreen mButtonAPNExpand;
    private PreferenceScreen mButtonOperatorSelectionExpand;
    private CheckBoxPreference mButtonPrefer2g;

    private static final String BUTTON_APN_EXPAND_KEY = "button_apn_key";
    private static final String BUTTON_OPERATOR_SELECTION_EXPAND_KEY = "button_carrier_sel_key";
    private static final String BUTTON_PREFER_2G_KEY = "button_prefer_2g_key";
    private PreferenceActivity mPrefActivity;
    private PreferenceScreen mPrefScreen;
    private int mSubscription = 0;
    private Phone mPhone;

    public GsmUmtsOptions(PreferenceActivity prefActivity, PreferenceScreen prefScreen) {
        this(prefActivity,  prefScreen, 0);
    }

    public GsmUmtsOptions(PreferenceActivity prefActivity,
            PreferenceScreen prefScreen, int subscription) {
        mPrefActivity = prefActivity;
        mPrefScreen = prefScreen;
        mSubscription = subscription;
        // TODO DSDS: Try to move DSDS changes to new file
        mPhone = PhoneGlobals.getInstance().getPhone(mSubscription);
        create();
    }

    protected void create() {
        mPrefActivity.addPreferencesFromResource(R.xml.gsm_umts_options);
        mButtonAPNExpand = (PreferenceScreen) mPrefScreen.findPreference(BUTTON_APN_EXPAND_KEY);
        mButtonAPNExpand.getIntent().putExtra(SUBSCRIPTION_KEY, mSubscription);
        mButtonOperatorSelectionExpand =
                (PreferenceScreen) mPrefScreen.findPreference(BUTTON_OPERATOR_SELECTION_EXPAND_KEY);
        mButtonOperatorSelectionExpand.getIntent().putExtra(SUBSCRIPTION_KEY, mSubscription);
        mButtonPrefer2g = (CheckBoxPreference) mPrefScreen.findPreference(BUTTON_PREFER_2G_KEY);
        Use2GOnlyCheckBoxPreference.updatePhone(mPhone);
        enableScreen();
    }

    public void onResume() {
        updateOperatorSelectionVisibility();
    }

    public void enableScreen() {
        if (mPhone.getPhoneType() != PhoneConstants.PHONE_TYPE_GSM) {
            log("Not a GSM phone, disabling GSM preferences (apn, use2g, select operator)");
            mButtonAPNExpand.setEnabled(false);
            mButtonOperatorSelectionExpand.setEnabled(false);
            mButtonPrefer2g.setEnabled(false);
        } else {
            log("Not a CDMA phone");
            Resources res = mPrefActivity.getResources();

            // Determine which options to display, for GSM these are defaulted
            // are defaulted to true in Phone/res/values/config.xml. But for
            // some operators like verizon they maybe overriden in operator
            // specific resources or device specifc overlays.
            if (!res.getBoolean(R.bool.config_apn_expand)) {
                mPrefScreen.removePreference(mPrefScreen.findPreference(BUTTON_APN_EXPAND_KEY));
            }
            if (!res.getBoolean(R.bool.config_operator_selection_expand)) {
                if (mButtonOperatorSelectionExpand != null) {
                    mPrefScreen.removePreference(mButtonOperatorSelectionExpand);
                    mButtonOperatorSelectionExpand = null;
               }
            }
            if (!res.getBoolean(R.bool.config_prefer_2g)) {
                mPrefScreen.removePreference(mPrefScreen.findPreference(BUTTON_PREFER_2G_KEY));
            }
        }
        updateOperatorSelectionVisibility();
    }

    private void updateOperatorSelectionVisibility() {
        log("updateOperatorSelectionVisibility. mPhone = " + mPhone.getPhoneName());
        Resources res = mPrefActivity.getResources();
        if (mButtonOperatorSelectionExpand == null) {
            android.util.Log.e(LOG_TAG, "mButtonOperatorSelectionExpand is null");
            return;
        }
        if (!mPhone.isManualNetSelAllowed()) {
            log("Manual network selection not allowed.Disabling Operator Selection menu.");
            mButtonOperatorSelectionExpand.setEnabled(false);
        } else if (res.getBoolean(R.bool.csp_enabled)) {
            if (mPhone.isCspPlmnEnabled()) {
                log("[CSP] Enabling Operator Selection menu.");
                mButtonOperatorSelectionExpand.setEnabled(true);
            } else {
                log("[CSP] Disabling Operator Selection menu.");
                if (mButtonOperatorSelectionExpand != null) {
                    mPrefScreen.removePreference(mButtonOperatorSelectionExpand);
                    mButtonOperatorSelectionExpand = null;
                }
            }
        }
    }

    public boolean preferenceTreeClick(Preference preference) {
        if (preference.getKey().equals(BUTTON_PREFER_2G_KEY)) {
            log("preferenceTreeClick: return true");
            return true;
        }
        log("preferenceTreeClick: return false");
        return false;
    }

    protected void log(String s) {
        android.util.Log.d(LOG_TAG, s);
    }
}
