/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (c) 2011-2013 The Linux Foundation. All rights reserved.
 *
 * Not a Contribution.
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

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.AudioManager;
import android.net.sip.SipManager;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.telephony.MSimTelephonyManager;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;

import com.android.internal.telephony.cdma.TtyIntent;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.phone.sip.SipSharedPreferences;
import com.codeaurora.telephony.msim.SubscriptionManager;

/**
 * Top level "Call settings" UI; see res/xml/call_feature_setting.xml
 *
 * This preference screen is the root of the "MSim Call settings" hierarchy
 * available from the Phone app; the settings here let you control various
 * features related to phone calls (including voicemail settings, SIP
 * settings, the "Respond via SMS" feature, and others.)  It's used only
 * on voice-capable phone devices.
 *
 * Note that this activity is part of the package com.android.phone, even
 * though you reach it from the "Phone" app (i.e. DialtactsActivity) which
 * is from the package com.android.contacts.
 *
 * For the "MSim Mobile network settings" screen under the main Settings app,
 * See {@link MSimMobileNetworkSettings}.
 *
 * @see com.android.phone.MSimMobileNetworkSettings
 */
public class MSimCallFeaturesSetting extends PreferenceActivity
        implements Preference.OnPreferenceChangeListener{
    private static final String LOG_TAG = "MSimCallFeaturesSetting";
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    //Information about logical "up" Activity
    private static final String UP_ACTIVITY_PACKAGE = "com.android.dialer";
    private static final String UP_ACTIVITY_CLASS =
            "com.android.dialer.DialtactsActivity";

    // String keys for preference lookup
    // TODO: Naming these "BUTTON_*" is confusing since they're not actually buttons(!)
    private static final String BUTTON_PLAY_DTMF_TONE  = "button_play_dtmf_tone";
    private static final String BUTTON_DTMF_KEY        = "button_dtmf_settings";
    private static final String BUTTON_RETRY_KEY       = "button_auto_retry_key";
    private static final String BUTTON_TTY_KEY         = "button_tty_mode_key";
    private static final String BUTTON_HAC_KEY         = "button_hac_key";
    private static final String BUTTON_DIALPAD_AUTOCOMPLETE = "button_dialpad_autocomplete";
    private static final String BUTTON_SELECT_SUB_KEY  = "button_call_independent_serv";
    private static final String BUTTON_XDIVERT_KEY     = "button_xdivert";

    private static final String BUTTON_SIP_CALL_OPTIONS =
            "sip_call_options_key";
    private static final String BUTTON_SIP_CALL_OPTIONS_WIFI_ONLY =
            "sip_call_options_wifi_only_key";
    private static final String SIP_SETTINGS_CATEGORY_KEY =
            "sip_settings_category_key";

    // preferred TTY mode
    // Phone.TTY_MODE_xxx
    static final int preferredTtyMode = Phone.TTY_MODE_OFF;

    public static final String HAC_KEY = "HACSetting";
    public static final String HAC_VAL_ON = "ON";
    public static final String HAC_VAL_OFF = "OFF";

    private Phone mPhone;
    private boolean mForeground;
    private AudioManager mAudioManager;
    private SipManager mSipManager;

    /** Whether dialpad plays DTMF tone or not. */
    private CheckBoxPreference mPlayDtmfTone;
    private CheckBoxPreference mDialpadAutocomplete;
    private CheckBoxPreference mButtonAutoRetry;
    private CheckBoxPreference mButtonHAC;
    private ListPreference mButtonDTMF;
    private ListPreference mButtonTTY;
    private ListPreference mButtonSipCallOptions;
    private SipSharedPreferences mSipSharedPreferences;

    private PreferenceScreen mButtonXDivert;
    private int mNumPhones;
    private SubscriptionManager mSubManager;

    /*
     * Click Listeners, handle click based on objects attached to UI.
     */

    // Click listener for all toggle events
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mButtonDTMF) {
            return true;
        } else if (preference == mDialpadAutocomplete) {
            Settings.Secure.putInt(getContentResolver(), Settings.Secure.DIALPAD_AUTOCOMPLETE,
                    mDialpadAutocomplete.isChecked() ? 1 : 0);
        } else if (preference == mButtonTTY) {
            return true;
        } else if (preference == mButtonAutoRetry) {
            android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Global.CALL_AUTO_RETRY,
                    mButtonAutoRetry.isChecked() ? 1 : 0);
            return true;
        } else if (preference == mButtonHAC) {
            int hac = mButtonHAC.isChecked() ? 1 : 0;
            // Update HAC value in Settings database
            Settings.System.putInt(mPhone.getContext().getContentResolver(),
                    Settings.System.HEARING_AID, hac);

            // Update HAC Value in AudioManager
            mAudioManager.setParameter(HAC_KEY, hac != 0 ? HAC_VAL_ON : HAC_VAL_OFF);
            return true;
        } else if (preference == mButtonXDivert) {
            processXDivert();
            return true;
        }
        return false;
    }

    /**
     * Implemented to support onPreferenceChangeListener to look for preference
     * changes.
     *
     * @param preference is the preference to be changed
     * @param objValue should be the value of the selection, NOT its localized
     * display value.
     */
    @Override
    public boolean onPreferenceChange(Preference preference, Object objValue) {
        if (DBG) {
            log("onPreferenceChange(). preferenece: \"" + preference + "\""
                    + ", value: \"" + objValue + "\"");
        }
        if (preference == mButtonDTMF) {
            int index = mButtonDTMF.findIndexOfValue((String) objValue);
            Settings.System.putInt(mPhone.getContext().getContentResolver(),
                    Settings.System.DTMF_TONE_TYPE_WHEN_DIALING, index);
        } else if (preference == mButtonTTY) {
            handleTTYChange(preference, objValue);
        } else if (preference == mButtonSipCallOptions) {
            handleSipCallOptionsChange(objValue);
        }
        // always let the preference setting proceed.
        return true;
    }

    /*
     * Activity class methods
     */

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        if (DBG) log("onCreate(). Intent: " + getIntent());
        mPhone = PhoneGlobals.getInstance().getPhone();

        addPreferencesFromResource(R.xml.msim_call_feature_setting);

        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        mSubManager = SubscriptionManager.getInstance();

        // get buttons
        PreferenceScreen prefSet = getPreferenceScreen();

        mPlayDtmfTone = (CheckBoxPreference) findPreference(BUTTON_PLAY_DTMF_TONE);
        mDialpadAutocomplete = (CheckBoxPreference) findPreference(BUTTON_DIALPAD_AUTOCOMPLETE);
        mButtonDTMF = (ListPreference) findPreference(BUTTON_DTMF_KEY);
        mButtonAutoRetry = (CheckBoxPreference) findPreference(BUTTON_RETRY_KEY);
        mButtonHAC = (CheckBoxPreference) findPreference(BUTTON_HAC_KEY);
        mButtonTTY = (ListPreference) findPreference(BUTTON_TTY_KEY);
        mButtonXDivert = (PreferenceScreen) findPreference(BUTTON_XDIVERT_KEY);

        final ContentResolver contentResolver = getContentResolver();

        if (mPlayDtmfTone != null) {
            mPlayDtmfTone.setChecked(Settings.System.getInt(contentResolver,
                    Settings.System.DTMF_TONE_WHEN_DIALING, 1) != 0);
        }

        if (mDialpadAutocomplete != null) {
            mDialpadAutocomplete.setChecked(Settings.Secure.getInt(contentResolver,
                    Settings.Secure.DIALPAD_AUTOCOMPLETE, 0) != 0);
        }

        if (mButtonDTMF != null) {
            if (getResources().getBoolean(R.bool.dtmf_type_enabled)) {
                mButtonDTMF.setOnPreferenceChangeListener(this);
            } else {
                prefSet.removePreference(mButtonDTMF);
                mButtonDTMF = null;
            }
        }

        if (mButtonAutoRetry != null) {
            if (getResources().getBoolean(R.bool.auto_retry_enabled)) {
                mButtonAutoRetry.setOnPreferenceChangeListener(this);
            } else {
                prefSet.removePreference(mButtonAutoRetry);
                mButtonAutoRetry = null;
            }
        }

        if (mButtonHAC != null) {
            if (getResources().getBoolean(R.bool.hac_enabled)) {

                mButtonHAC.setOnPreferenceChangeListener(this);
            } else {
                prefSet.removePreference(mButtonHAC);
                mButtonHAC = null;
            }
        }

        if (mButtonTTY != null) {
            if (getResources().getBoolean(R.bool.tty_enabled)) {
                mButtonTTY.setOnPreferenceChangeListener(this);
            } else {
                prefSet.removePreference(mButtonTTY);
                mButtonTTY = null;
            }
        }

        createSipCallSettings();

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        PreferenceScreen selectSub = (PreferenceScreen) findPreference(BUTTON_SELECT_SUB_KEY);
        if (selectSub != null) {
            Intent intent = selectSub.getIntent();
            intent.putExtra(SelectSubscription.PACKAGE, "com.android.phone");
            intent.putExtra(SelectSubscription.TARGET_CLASS,
                    "com.android.phone.MSimCallFeaturesSubSetting");
        }

        mNumPhones = MSimTelephonyManager.getDefault().getPhoneCount();
        if (mButtonXDivert != null) {
            mButtonXDivert.setOnPreferenceChangeListener(this);
        }
    }

    private boolean isAllSubActive() {
        for (int i = 0; i < mNumPhones; i++) {
            if (!mSubManager.isSubActive(i)) return false;
        }
        return true;
    }

    private boolean isAnySubCdma() {
        for (int i = 0; i < mNumPhones; i++) {
            Phone phone = MSimPhoneGlobals.getInstance().getPhone(i);
            if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) return true;
        }
        return false;
    }

    private boolean isValidLine1Number(String[] line1Numbers) {
        for (int i = 0; i < mNumPhones; i++) {
            if (TextUtils.isEmpty(line1Numbers[i])) return false;
        }
        return true;
    }

    private void processXDivert() {
        String[] line1Numbers = new String[mNumPhones];
        for (int i = 0; i < mNumPhones; i++) {
            Phone phone = MSimPhoneGlobals.getInstance().getPhone(i);
            String msisdn = phone.getLine1Number();  // may be null or empty
            if (!TextUtils.isEmpty(msisdn)) {
                //Populate the line1Numbers only if it is not null
                line1Numbers[i] = PhoneNumberUtils.formatNumber(msisdn);
            }

            Log.d(LOG_TAG, "SUB:" + i + " phonetype = " + phone.getPhoneType()
                    + " isSubActive = " + mSubManager.isSubActive(i)
                    + " line1Number = " + line1Numbers[i]);
        }
        if (!isAllSubActive()) {
            //Is a subscription is deactived/or only one SIM is present,
            //dialog would be displayed stating the same.
            displayAlertDialog(R.string.xdivert_sub_absent);
        } else if (isAnySubCdma()) {
            //X-Divert is not supported for CDMA phone.Hence for C+G / C+C,
            //dialog would be displayed stating the same.
            displayAlertDialog(R.string.xdivert_not_supported);
        } else if (!isValidLine1Number(line1Numbers)) {
            //SIM records does not have msisdn, hence ask user to enter
            //the phone numbers.
            Intent intent = new Intent();
            intent.setClass(this, XDivertPhoneNumbers.class);
            startActivity(intent);
        } else {
            //SIM records have msisdn.Hence directly process
            //XDivert feature
            processXDivertCheckBox(line1Numbers);
        }
    }

    private void displayAlertDialog(int resId) {
        new AlertDialog.Builder(this).setMessage(resId)
            .setTitle(R.string.xdivert_title)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        Log.d(LOG_TAG, "X-Divert onClick");
                    }
                })
            .show()
            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    public void onDismiss(DialogInterface dialog) {
                        Log.d(LOG_TAG, "X-Divert onDismiss");
                    }
            });
    }

    private void processXDivertCheckBox(String[] line1Numbers) {
        Log.d(LOG_TAG,"processXDivertCheckBox line1Numbers = "
                + java.util.Arrays.toString(line1Numbers));
        Intent intent = new Intent();
        intent.setClass(this, XDivertSetting.class);
        intent.putExtra(XDivertUtility.LINE1_NUMBERS, line1Numbers);
        startActivity(intent);
    }

    private void createSipCallSettings() {
        // Add Internet call settings.
        if (PhoneUtils.isVoipSupported()) {
            mSipManager = SipManager.newInstance(this);
            mSipSharedPreferences = new SipSharedPreferences(this);
            addPreferencesFromResource(R.xml.sip_settings_category);
            mButtonSipCallOptions = getSipCallOptionPreference();
            mButtonSipCallOptions.setOnPreferenceChangeListener(this);
            mButtonSipCallOptions.setValueIndex(
                    mButtonSipCallOptions.findIndexOfValue(
                            mSipSharedPreferences.getSipCallOption()));
            mButtonSipCallOptions.setSummary(mButtonSipCallOptions.getEntry());
        }
    }

    // Gets the call options for SIP depending on whether SIP is allowed only
    // on Wi-Fi only; also make the other options preference invisible.
    private ListPreference getSipCallOptionPreference() {
        ListPreference wifiAnd3G = (ListPreference)
                findPreference(BUTTON_SIP_CALL_OPTIONS);
        ListPreference wifiOnly = (ListPreference)
                findPreference(BUTTON_SIP_CALL_OPTIONS_WIFI_ONLY);
        PreferenceGroup sipSettings = (PreferenceGroup)
                findPreference(SIP_SETTINGS_CATEGORY_KEY);
        if (SipManager.isSipWifiOnly(this)) {
            sipSettings.removePreference(wifiAnd3G);
            return wifiOnly;
        } else {
            sipSettings.removePreference(wifiOnly);
            return wifiAnd3G;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mForeground = true;

        if (isAirplaneModeOn()) {
            Preference sipSettings = findPreference(SIP_SETTINGS_CATEGORY_KEY);
            PreferenceScreen screen = getPreferenceScreen();
            int count = screen.getPreferenceCount();
            for (int i = 0 ; i < count ; ++i) {
                Preference pref = screen.getPreference(i);
                if (pref != sipSettings) pref.setEnabled(false);
            }
            return;
        }

        if (mButtonDTMF != null) {
            int dtmf = Settings.System.getInt(getContentResolver(),
                    Settings.System.DTMF_TONE_TYPE_WHEN_DIALING, Constants.DTMF_TONE_TYPE_NORMAL);
            mButtonDTMF.setValueIndex(dtmf);
        }

        if (mButtonAutoRetry != null) {
            int autoretry = Settings.Global.getInt(getContentResolver(),
                    Settings.Global.CALL_AUTO_RETRY, 0);
            mButtonAutoRetry.setChecked(autoretry != 0);
        }

        if (mButtonHAC != null) {
            int hac = Settings.System.getInt(getContentResolver(), Settings.System.HEARING_AID, 0);
            mButtonHAC.setChecked(hac != 0);
        }

        if (mButtonTTY != null) {
            int settingsTtyMode = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.PREFERRED_TTY_MODE,
                    Phone.TTY_MODE_OFF);
            mButtonTTY.setValue(Integer.toString(settingsTtyMode));
            updatePreferredTtyModeSummary(settingsTtyMode);
        }

        if (mButtonXDivert != null) {
            if (!isAllSubActive()) mButtonXDivert.setEnabled(false);
        }
    }

    private boolean isAirplaneModeOn() {
        return Settings.System.getInt(getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0) != 0;
    }

    private void handleTTYChange(Preference preference, Object objValue) {
        int buttonTtyMode;
        buttonTtyMode = Integer.valueOf((String) objValue).intValue();
        int settingsTtyMode = android.provider.Settings.Secure.getInt(
                getContentResolver(),
                android.provider.Settings.Secure.PREFERRED_TTY_MODE, preferredTtyMode);
        if (DBG) log("handleTTYChange: requesting set TTY mode enable (TTY) to" +
                Integer.toString(buttonTtyMode));

        if (buttonTtyMode != settingsTtyMode) {
            switch(buttonTtyMode) {
            case Phone.TTY_MODE_OFF:
            case Phone.TTY_MODE_FULL:
            case Phone.TTY_MODE_HCO:
            case Phone.TTY_MODE_VCO:
                android.provider.Settings.Secure.putInt(getContentResolver(),
                        android.provider.Settings.Secure.PREFERRED_TTY_MODE, buttonTtyMode);
                break;
            default:
                buttonTtyMode = Phone.TTY_MODE_OFF;
            }

            mButtonTTY.setValue(Integer.toString(buttonTtyMode));
            updatePreferredTtyModeSummary(buttonTtyMode);
            Intent ttyModeChanged = new Intent(TtyIntent.TTY_PREFERRED_MODE_CHANGE_ACTION);
            ttyModeChanged.putExtra(TtyIntent.TTY_PREFFERED_MODE, buttonTtyMode);
            sendBroadcast(ttyModeChanged);
        }
    }

    private void handleSipCallOptionsChange(Object objValue) {
        String option = objValue.toString();
        mSipSharedPreferences.setSipCallOption(option);
        mButtonSipCallOptions.setValueIndex(
                mButtonSipCallOptions.findIndexOfValue(option));
        mButtonSipCallOptions.setSummary(mButtonSipCallOptions.getEntry());
    }

    private void updatePreferredTtyModeSummary(int TtyMode) {
        String [] txts = getResources().getStringArray(R.array.tty_mode_entries);
        switch(TtyMode) {
            case Phone.TTY_MODE_OFF:
            case Phone.TTY_MODE_HCO:
            case Phone.TTY_MODE_VCO:
            case Phone.TTY_MODE_FULL:
                mButtonTTY.setSummary(txts[TtyMode]);
                break;
            default:
                mButtonTTY.setEnabled(false);
                mButtonTTY.setSummary(txts[Phone.TTY_MODE_OFF]);
        }
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {  // See ActionBar#setDisplayHomeAsUpEnabled()
            Intent intent = new Intent();
            intent.setClassName(UP_ACTIVITY_PACKAGE, UP_ACTIVITY_CLASS);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Finish current Activity and go up to the top level Settings ({@link CallFeaturesSetting}).
     * This is useful for implementing "HomeAsUp" capability for second-level Settings.
     */
    public static void goUpToTopLevelSetting(Activity activity) {
        Intent intent = new Intent(activity, CallFeaturesSetting.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.startActivity(intent);
        activity.finish();
    }
}
