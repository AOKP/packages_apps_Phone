/*
 * Copyright (c) 2012, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.phone.ims;

import com.android.internal.telephony.Phone;
import com.android.phone.R;

import android.app.ActionBar;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.Toast;

/**
 * The activity class for editing a new or existing IMS profile.
 */
public class ImsEditor extends PreferenceActivity
        implements Preference.OnPreferenceChangeListener {
    private static final int MENU_SAVE = Menu.FIRST;
    private static final int MENU_DISCARD = Menu.FIRST + 1;
    private static final int MENU_REMOVE = Menu.FIRST + 2;

    private static final String IMS_CALL_TYPE_VOICE = "Voice";
    private static final String IMS_CALL_TYPE_VIDEO = "Video";

    private static final String TAG = ImsEditor.class.getSimpleName();

    // Check if the tag is loggable
    private static final boolean DBG = Log.isLoggable("IMS", Log.DEBUG);

    private ImsSharedPreferences mSharedPreferences;
    private Button mRemoveButton;
    private CheckBoxPreference mCheckbox;

    enum PreferenceKey {
        DomainAddress(R.string.domain_address, 0, R.string.default_preference_summary),
        CallType(R.string.call_type, R.string.default_ims_call_type,
                R.string.default_preference_summary);

        final int text;
        final int initValue;
        final int defaultSummary;
        Preference preference;

        /**
         * @param key The key name of the preference.
         * @param initValue The initial value of the preference.
         * @param defaultSummary The default summary value of the preference
         *        when the preference value is empty.
         */
        PreferenceKey(int text, int initValue, int defaultSummary) {
            this.text = text;
            this.initValue = initValue;
            this.defaultSummary = defaultSummary;
        }

        String getValue() {
            if (preference instanceof EditTextPreference) {
                return ((EditTextPreference) preference).getText();
            } else if (preference instanceof ListPreference) {
                return ((ListPreference) preference).getValue();
            }
            throw new RuntimeException("getValue() for the preference " + this);
        }

        void setValue(String value) {
            if (preference instanceof EditTextPreference) {
                ((EditTextPreference) preference).setText(value);
            } else if (preference instanceof ListPreference) {
                ((ListPreference) preference).setValue(value);
            }

            if (TextUtils.isEmpty(value)) {
                preference.setSummary(defaultSummary);
            } else {
                preference.setSummary(value);
            }
            String oldValue = getValue();
            if (DBG) Log.v(TAG, this + ": setValue() " + value + ": " + oldValue
                    + " --> " + getValue());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceScreen().setEnabled(true);
        if (mRemoveButton != null) mRemoveButton.setEnabled(true);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (DBG) Log.v(TAG, "start profile editor");
        super.onCreate(savedInstanceState);

        mSharedPreferences = new ImsSharedPreferences(this);

        setContentView(R.layout.ims_settings_ui);
        addPreferencesFromResource(R.xml.ims_edit);

        PreferenceGroup screen = (PreferenceGroup) getPreferenceScreen();
        for (int i = 0, n = screen.getPreferenceCount(); i < n; i++) {
            setupPreference(screen.getPreference(i));
        }
        mCheckbox = (CheckBoxPreference) getPreferenceScreen()
                .findPreference(getString(R.string.use_ims_default));

        screen.setTitle(R.string.ims_edit_title);

        loadPreferences();

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public void onPause() {
        if (DBG) Log.v(TAG, "ImsEditor onPause(): finishing? " + isFinishing());
        if (!isFinishing()) {
            validateAndSetResult();
        }
        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, MENU_SAVE, 0, R.string.ims_menu_save)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, MENU_DISCARD, 0, R.string.ims_menu_discard)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, MENU_REMOVE, 0, R.string.remove_ims_account)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: // See ActionBar#setDisplayHomeAsUpEnabled()
                // This time just work as "back" or "save" capability.
            case MENU_SAVE:
                validateAndSetResult();
                return true;

            case MENU_DISCARD:
                finish();
                return true;

            case MENU_REMOVE: {
                removePreferencesAndFinish();
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                validateAndSetResult();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void loadPreferences() {
        String serverAddress = null;
        int imsCallType;
        boolean isImsDefault;

        // Get IMS server address
        serverAddress = mSharedPreferences.getServerAddress();
        PreferenceKey.DomainAddress.setValue((serverAddress == null) ? "" : serverAddress);

        // Get IMS call type
        imsCallType = mSharedPreferences.getCallType();
        if (imsCallType == Phone.CALL_TYPE_VOICE) {
            PreferenceKey.CallType.setValue(IMS_CALL_TYPE_VOICE);
        } else {
            PreferenceKey.CallType.setValue(IMS_CALL_TYPE_VIDEO);
        }

        // Get Use IMS by default
        isImsDefault = mSharedPreferences.getisImsDefault();
        mCheckbox.setChecked(isImsDefault);
    }

    private void validateAndSetResult() {
        // Set server address
        mSharedPreferences.setServerAddress(PreferenceKey.DomainAddress.getValue());

        // Set call type
        if (IMS_CALL_TYPE_VIDEO.equalsIgnoreCase(PreferenceKey.CallType.getValue())) {
            mSharedPreferences.setCallType(Phone.CALL_TYPE_VT);
        } else {
            mSharedPreferences.setCallType(Phone.CALL_TYPE_VOICE);
        }

        // Set is IMS default value
        mSharedPreferences.setIsImsDefault(mCheckbox.isChecked());

        setResult(RESULT_OK);
        Toast.makeText(this, R.string.saving_account, Toast.LENGTH_SHORT)
                .show();
        finish();
    }

    private void removePreferencesAndFinish() {
        mSharedPreferences.setServerAddress(null);
        mSharedPreferences.setCallType(Phone.CALL_TYPE_VOICE);
        mSharedPreferences.setIsImsDefault(false);

        setResult(RESULT_OK);
        finish();
    }

    public boolean onPreferenceChange(Preference pref, Object newValue) {
        if (pref instanceof CheckBoxPreference) return true;
        String value = (newValue == null) ? "" : newValue.toString();
        if (TextUtils.isEmpty(value)) {
            pref.setSummary(getPreferenceKey(pref).defaultSummary);
        } else {
            pref.setSummary(value);
        }

        return true;
    }

    private PreferenceKey getPreferenceKey(Preference pref) {
        for (PreferenceKey key : PreferenceKey.values()) {
            if (key.preference == pref) return key;
        }
        throw new RuntimeException("not possible to reach here");
    }

    private void setupPreference(Preference pref) {
        pref.setOnPreferenceChangeListener(this);
        for (PreferenceKey key : PreferenceKey.values()) {
            String name = getString(key.text);
            if (name.equals(pref.getKey())) {
                key.preference = pref;
                return;
            }
        }
    }
}
