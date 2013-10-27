/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.telephony.MSimTelephonyManager;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;

import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;

import com.codeaurora.telephony.msim.MSimPhoneFactory;

/**
 * Activity to let the user add or edit an FDN contact.
 */
public class MSimEditFdnContactScreen extends EditFdnContactScreen {
    private static final String LOG_TAG = "MSimEditFdnContactScreen";
    private static final boolean DBG = false;

    private static int mSubscription = 0;

    private Handler mHandler = new Handler();

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

    }

    @Override
    protected void resolveIntent() {
        Intent intent = getIntent();

        mName =  intent.getStringExtra(INTENT_EXTRA_NAME);
        mNumber =  intent.getStringExtra(INTENT_EXTRA_NUMBER);
        mSubscription = getIntent().getIntExtra(SUBSCRIPTION_KEY, 0);
        mAddContact = TextUtils.isEmpty(mNumber);
    }

    @Override
    protected Uri getContentURI() {
        String[] fdn = {"fdn", "fdn_sub2", "fdn_sub3"};

        if (mSubscription < MSimTelephonyManager.getDefault().getPhoneCount()) {
            return Uri.parse("content://iccmsim/" + fdn[mSubscription]);
        } else {
            Log.e(LOG_TAG, "Error received invalid sub =" + mSubscription);
            return null;
        }
    }

    @Override
    protected void addContact() {
        if (DBG) log("addContact");

        final String number = PhoneNumberUtils.convertAndStrip(getNumberFromTextField());

        if (!isValidNumber(number)) {
            handleResult(false, true);
            return;
        }

        Uri uri = getContentURI();

        ContentValues bundle = new ContentValues(4);
        bundle.put("tag", getNameFromTextField());
        bundle.put("number", number);
        bundle.put("pin2", mPin2);
        bundle.put(SUBSCRIPTION_KEY, mSubscription);

        mQueryHandler = new QueryHandler(getContentResolver());
        mQueryHandler.startInsert(0, null, uri, bundle);
        displayProgress(true);
        showStatus(getResources().getText(R.string.adding_fdn_contact));
    }

    @Override
    protected void updateContact() {
        if (DBG) log("updateContact");

        final String name = getNameFromTextField();
        final String number = PhoneNumberUtils.convertAndStrip(getNumberFromTextField());

        if (!isValidNumber(number)) {
            handleResult(false, true);
            return;
        }
        Uri uri = getContentURI();

        ContentValues bundle = new ContentValues();
        bundle.put("tag", mName);
        bundle.put("number", mNumber);
        bundle.put("newTag", name);
        bundle.put("newNumber", number);
        bundle.put("pin2", mPin2);
        bundle.put(SUBSCRIPTION_KEY, mSubscription);

        mQueryHandler = new QueryHandler(getContentResolver());
        mQueryHandler.startUpdate(0, null, uri, bundle, null, null);
        displayProgress(true);
        showStatus(getResources().getText(R.string.updating_fdn_contact));
    }

    /**
     * Handle the delete command, based upon the state of the Activity.
     */
    @Override
    protected void deleteSelected() {
        // delete ONLY if this is NOT a new contact.
        if (!mAddContact) {
            Intent intent = new Intent();
            intent.setClass(this, MSimDeleteFdnContactScreen.class);
            intent.putExtra(INTENT_EXTRA_NAME, mName);
            intent.putExtra(INTENT_EXTRA_NUMBER, mNumber);
            intent.putExtra(SUBSCRIPTION_KEY, mSubscription);
            startActivity(intent);
        }
        finish();
    }

    /**
     * This method will handleResult for MSIM cases
    */
    @Override
    protected void handleResult(boolean success, boolean invalidNumber) {
        if (success) {
            if (DBG) log("handleResult: success!");
            showStatus(getResources().getText(mAddContact ?
                    R.string.fdn_contact_added : R.string.fdn_contact_updated));
        } else {
            if (DBG) log("handleResult: failed!");
            if (invalidNumber) {
                showStatus(getResources().getText(R.string.fdn_invalid_number));
            } else {
                if (MSimPhoneFactory.getPhone(mSubscription).getIccCard().getIccPin2Blocked()) {
                    showStatus(getResources().getText(R.string.fdn_enable_puk2_requested));
                } else if (MSimPhoneFactory.getPhone(mSubscription).getIccCard()
                        .getIccPuk2Blocked()) {
                    showStatus(getResources().getText(R.string.puk2_blocked));
                } else {
                    // There's no way to know whether the failure is due to incorrect PIN2 or
                    // an inappropriate phone number.
                    showStatus(getResources().getText(R.string.pin2_or_fdn_invalid));
                }
            }
        }

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 2000);

    }

    @Override
    protected void log(String msg) {
        Log.d(LOG_TAG, "[MSimEditFdnContact] " + msg);
    }
}
