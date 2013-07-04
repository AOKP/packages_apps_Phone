/*
 * Copyright (C) 2013 The CyanogenMod Project
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
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.location.CountryDetector;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.Preference.OnPreferenceClickListener;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.PhoneLookup;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;

/**
 * Blacklist settings UI for the Phone app.
 */
public class BlacklistSetting extends PreferenceActivity implements
    Preference.OnPreferenceChangeListener,
    EditPhoneNumberPreference.OnDialogClosedListener,
    EditPhoneNumberPreference.GetDefaultNumberListener {

    private static final String LOG_TAG = PhoneGlobals.LOG_TAG;
    private static final boolean DBG = false;

    private static final String BUTTON_ADD_BLACKLIST_NUMBER = "button_add_blacklist_number";
    private static final String BUTTON_BLACKLIST_PRIVATE    = "button_blacklist_private_numbers";
    private static final String BUTTON_BLACKLIST_UNKNOWN    = "button_blacklist_unknown_numbers";
    private static final String CATEGORY_BLACKLIST          = "cat_blacklist";

    private static final String NUM_PROJECTION[] = {
        CommonDataKinds.Phone.NUMBER
    };

    private static final int ADD_BLACKLIST_ID = 3;

    private EditPhoneNumberPreference mButtonAddBlacklistNumber;
    private PreferenceCategory mCatBlacklist;
    private CheckBoxPreference mBlacklistPrivate;
    private CheckBoxPreference mBlacklistUnknown;
    private Blacklist mBlacklist;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.blacklist_setting);

        //get UI object references
        PreferenceScreen prefSet = getPreferenceScreen();
        mButtonAddBlacklistNumber = (EditPhoneNumberPreference) prefSet.findPreference(BUTTON_ADD_BLACKLIST_NUMBER);
        mButtonAddBlacklistNumber.setParentActivity(this, ADD_BLACKLIST_ID, this);
        mButtonAddBlacklistNumber.setDialogOnClosedListener(this);
        mCatBlacklist = (PreferenceCategory) prefSet.findPreference(CATEGORY_BLACKLIST);
        mBlacklistPrivate = (CheckBoxPreference) prefSet.findPreference(BUTTON_BLACKLIST_PRIVATE);
        mBlacklistPrivate.setOnPreferenceChangeListener(this);
        mBlacklistUnknown = (CheckBoxPreference) prefSet.findPreference(BUTTON_BLACKLIST_UNKNOWN);

        mBlacklist = PhoneGlobals.getInstance().blackList;

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        PhoneGlobals.getInstance().notificationMgr.cancelBlacklistedCallNotification();
        updateBlacklist();
    }

    private OnPreferenceClickListener blackPreferenceListener = new OnPreferenceClickListener() {
        public boolean onPreferenceClick(Preference p) {
            final String phone = p.getTitle().toString();
            final String message = BlacklistSetting.this.getString(R.string.remove_blacklist_number, phone);
            AlertDialog dialog = new AlertDialog.Builder(BlacklistSetting.this)
                    .setTitle(R.string.remove_blacklist_number_title)
                    .setMessage(message)
                    .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            mBlacklist.delete(phone);
                            updateBlacklist();
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .create();
            dialog.show();
            return true;
        }
    };

    @Override
    public boolean onPreferenceChange(Preference preference, Object objValue) {
        if (preference == mBlacklistPrivate) {
            boolean checked = (Boolean) objValue;
            if (!checked) {
                mBlacklistUnknown.setChecked(false);
            }
        }

        return true;
    }

    @Override
    public void onDialogClosed(EditPhoneNumberPreference preference, int buttonClicked) {
        if (preference == mButtonAddBlacklistNumber) {
            if (mBlacklist.add(preference.getRawPhoneNumber())) {
                updateBlacklist();
            }
            preference.setPhoneNumber("");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (DBG) {
            log("onActivityResult: requestCode: " + requestCode
                    + ", resultCode: " + resultCode
                    + ", data: " + data);
        }

        if (resultCode != RESULT_OK) {
            if (DBG) log("onActivityResult: contact picker result not OK.");
            return;
        }

        if (requestCode == ADD_BLACKLIST_ID) {
            Cursor cursor = getContentResolver().query(data.getData(),
                    NUM_PROJECTION, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                mButtonAddBlacklistNumber.onPickActivityResult(cursor.getString(0));
            } else {
                if (DBG) log("onActivityResult: bad contact data, no results found.");
            }
            if (cursor != null) {
                cursor.close();
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private static final class ContactNameHolder {
        Preference pref;
        String name;
    }

    private void updateBlacklist() {
        mCatBlacklist.removeAll();

        final CountryDetector detector = (CountryDetector) getSystemService(COUNTRY_DETECTOR);
        final String currentCountryIso = detector.detectCountry().getCountryIso();

        for (String number : mBlacklist.getItems()) {
            Preference pref = new Preference(this);
            pref.setTitle(number);

            String normalizedNumber = number;
            if (!TextUtils.isEmpty(currentCountryIso)) {
                // Normalize the number: this is needed because the PhoneLookup query below does not
                // accept a country code as an input.
                String numberE164 = PhoneNumberUtils.formatNumberToE164(number, currentCountryIso);
                if (!TextUtils.isEmpty(numberE164)) {
                    // Only use it if the number could be formatted to E164.
                    normalizedNumber = numberE164;
                }
            }
            pref.setKey(normalizedNumber);
            pref.setOnPreferenceClickListener(blackPreferenceListener);
            mCatBlacklist.addPreference(pref);
        }

        final ContentResolver cr = getContentResolver();
        final AsyncTask<Preference, ContactNameHolder, Void> lookupTask =
                new AsyncTask<Preference, ContactNameHolder, Void>() {
            @Override
            protected Void doInBackground(Preference... params) {
                final String[] projection = new String[] { PhoneLookup.DISPLAY_NAME };

                for (Preference pref : params) {
                    Uri uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI,
                            Uri.encode(pref.getKey()));
                    Cursor cursor = cr.query(uri, projection, null, null, null);
                    if (cursor != null) {
                        if (cursor.moveToFirst()) {
                            ContactNameHolder holder = new ContactNameHolder();
                            holder.pref = pref;
                            holder.name = cursor.getString(0);
                            publishProgress(holder);
                        }
                        cursor.close();
                    }
                }

                return null;
            }

            @Override
            protected void onProgressUpdate(ContactNameHolder... entries) {
                for (ContactNameHolder entry : entries) {
                    entry.pref.setSummary(entry.name);
                }
            }
        };

        final int count = mCatBlacklist.getPreferenceCount();
        final Preference[] prefs = new Preference[count];
        for (int i = 0; i < count; i++) {
            prefs[i] = mCatBlacklist.getPreference(i);
        }
        lookupTask.execute(prefs);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {  // See ActionBar#setDisplayHomeAsUpEnabled()
            CallFeaturesSetting.goUpToTopLevelSetting(this);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void log(String msg) {
        Log.d(LOG_TAG, "BlacklistSetting: " + msg);
    }

    @Override
    public String onGetDefaultNumber(EditPhoneNumberPreference preference) {
        // Nothing to return
        return null;
    }

}
