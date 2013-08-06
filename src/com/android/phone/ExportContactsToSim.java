/* Copyright (c) 2012-2013 The Linux Foundation. All rights reserved.
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
 *
 */

package com.android.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.ContactsContract;
import android.telephony.MSimTelephonyManager;
import android.telephony.PhoneNumberUtils;
import android.util.Log;
import android.view.Window;
import android.widget.TextView;

import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.provider.ContactsContract.CommonDataKinds.Phone;

import static android.view.Window.PROGRESS_VISIBILITY_OFF;
import static android.view.Window.PROGRESS_VISIBILITY_ON;

public class ExportContactsToSim extends Activity {
    private static final String TAG = "ExportContactsToSim";
    private static final int FAILURE = 0;
    private static final int SUCCESS = 1;
    private TextView mEmptyText;
    private int mResult = SUCCESS;
    protected boolean mIsForeground = false;
    private static final String SIM_INDEX = "sim_index";

    private static final int CONTACTS_EXPORTED = 1;
    private static final String[] COLUMN_NAMES = new String[] {
            "name",
            "number",
            "emails"
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().requestFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.export_contact_screen);
        mEmptyText = (TextView) findViewById(android.R.id.empty);
        doExportToSim();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mIsForeground = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        mIsForeground = false;
    }

    private void doExportToSim() {

        displayProgress(true);

        new Thread(new Runnable() {
            public void run() {
                Cursor contactsCursor = getContactsContentCursor();
                if ((contactsCursor.getCount()) < 1) {
                    // If there are no contacts in Phone book display as failed.
                    // Displaying only fail or success, there are no customized strings.
                    mResult = FAILURE;
                }

                for (int i = 0; contactsCursor.moveToNext(); i++) {
                    populateContactDataFromCursor(contactsCursor );
                }

                contactsCursor.close();
                Message message = Message.obtain(mHandler, CONTACTS_EXPORTED, (Integer)mResult);
                mHandler.sendMessage(message);
            }
        }).start();
    }

    private Cursor getContactsContentCursor() {
        Uri phoneBookContentUri = Phone.CONTENT_URI;
        String selection = ContactsContract.Contacts.HAS_PHONE_NUMBER +
                "='1' AND (account_type is NULL OR account_type !=?)";
        String[] selectionArg = new String[] {"SIM"};

        Cursor contactsCursor = getContentResolver().query(phoneBookContentUri, null, selection,
                selectionArg, null);
        return contactsCursor;
    }

    private void populateContactDataFromCursor(final Cursor dataCursor) {
        Uri uri = getUri();
        if (uri == null) {
            Log.d(TAG," populateContactDataFromCursor: uri is null, return ");
            return;
        }
        Uri contactUri;
        int nameIdx = dataCursor
                .getColumnIndex(ContactsContract.Data.DISPLAY_NAME);
        int phoneIdx = dataCursor
                .getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);

        // Extract the name.
        String name = dataCursor.getString(nameIdx);
        // Extract the phone number.
        String rawNumber = dataCursor.getString(phoneIdx);
        String number = PhoneNumberUtils.normalizeNumber(rawNumber);

        ContentValues values = new ContentValues();
        values.put("tag", name);
        values.put("number", number);
        Log.d("ExportContactsToSim", "name : " + name + " number : " + number);
        contactUri = getContentResolver().insert(uri, values);
        if (contactUri == null) {
            Log.e("ExportContactsToSim", "Failed to export contact to SIM for " +
                    "name : " + name + " number : " + number);
            mResult = FAILURE;
        }
    }

    private void showAlertDialog(String value) {
        if (!mIsForeground) {
            Log.d(TAG, "The activitiy is not in foreground. Do not display dialog!!!");
            return;
        }
        AlertDialog alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.setTitle("Result...");
        alertDialog.setMessage(value);
        alertDialog.setButton("OK", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                // finish contacts activity
                finish();
            }
        });
        alertDialog.show();
    }

    private void displayProgress(boolean loading) {
        mEmptyText.setText(R.string.exportContacts);
        getWindow().setFeatureInt(
                Window.FEATURE_INDETERMINATE_PROGRESS,
                loading ? PROGRESS_VISIBILITY_ON : PROGRESS_VISIBILITY_OFF);
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case CONTACTS_EXPORTED:
                    int result = (Integer)msg.obj;
                    if (result == 1) {
                        showAlertDialog(getString(R.string.exportAllcontatsSuccess));
                    } else {
                        showAlertDialog(getString(R.string.exportAllcontatsFailed));
                    }
                    break;
            }
        }
    };

    private Uri getUri() {
        Intent intent = getIntent();
        Bundle extras = intent.getExtras();
        if (MSimTelephonyManager.getDefault().isMultiSimEnabled()) {
            int subscription  = extras.getInt(SIM_INDEX);
            String[] adnString = {"adn", "adn_sub2", "adn_sub3"};
            Log.d("ExportContactsToSim"," subscription : " + subscription);

            if (subscription < MSimTelephonyManager.getDefault().getPhoneCount()) {
                return Uri.parse("content://iccmsim/" + adnString[subscription]);
            } else {
                Log.e(TAG, "Invalid subcription:" + subscription);
                return null;
            }
        } else {
            return Uri.parse("content://icc/adn");
        }
    }
}
