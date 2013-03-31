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
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.Preference.OnPreferenceClickListener;
import android.provider.ContactsContract.CommonDataKinds;
import android.util.Log;
import android.view.MenuItem;

/**
 * Blacklist settings UI for the Phone app.
 */
public class BlacklistSetting extends PreferenceActivity implements
    EditPhoneNumberPreference.OnDialogClosedListener,
    EditPhoneNumberPreference.GetDefaultNumberListener {

    private static final String LOG_TAG = PhoneGlobals.LOG_TAG;
    private static final boolean DBG = false;

    private static final String BUTTON_ADD_BLACKLIST_NUMBER = "button_add_blacklist_number";
    private static final String CATEGORY_BLACKLIST          = "cat_blacklist";

    private static final String NUM_PROJECTION[] = {
        CommonDataKinds.Phone.NUMBER
    };

    private static final int ADD_BLACKLIST_ID = 3;

    private EditPhoneNumberPreference mButtonAddBlacklistNumber;
    private PreferenceCategory mCatBlacklist;
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

    private void updateBlacklist() {
        mCatBlacklist.removeAll();

        for (String number : mBlacklist.getItems()) {
            Preference pref = new Preference(this);
            pref.setTitle(number);
            pref.setOnPreferenceClickListener(blackPreferenceListener);
            mCatBlacklist.addPreference(pref);
        }
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
