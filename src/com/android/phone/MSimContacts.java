/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.telephony.MSimTelephonyManager;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.View;
import android.widget.AdapterView;
import android.widget.TextView;

/**
 * SIM Address Book UI for the Phone app.
 */
public class MSimContacts extends SimContacts {
    private static final String LOG_TAG = "MSimContacts";
    private static final String SIM_INDEX = "sim_index";

    private int mSimIndex = 0;
    String[] mAdnString = {"adn", "adn_sub2", "adn_sub3"};
    //Import from all SIM's option is having the maximum index
    //we cannot take the phoneCount as maximum index as it will conflict
    //in DSDS and TSTS. So assigning to some constant value.
    private static int IMPORT_FROM_ALL = 8;

    @Override
    protected Uri resolveIntent() {
        Intent intent = getIntent();
        Bundle extras = intent.getExtras();
        mSimIndex  = extras.getInt(SIM_INDEX);

        if (mSimIndex == IMPORT_FROM_ALL) {
            intent.setData(Uri.parse("content://iccmsim/adn_all"));
        } else if (mSimIndex < MSimTelephonyManager.getDefault().getPhoneCount()) {
            intent.setData(Uri.parse("content://iccmsim/" + mAdnString[mSimIndex]));
        } else {
            Log.e(LOG_TAG, "Error: received invalid sub =" + mSimIndex);
        }

        if (Intent.ACTION_PICK.equals(intent.getAction())) {
            // "index" is 1-based
            mInitialSelection = intent.getIntExtra("index", 0) - 1;
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            mInitialSelection = 0;
        }
        return intent.getData();
    }

    @Override
    protected Uri getUri() {
        if (mSimIndex < MSimTelephonyManager.getDefault().getPhoneCount()) {
            return Uri.parse("content://iccmsim/" + mAdnString[mSimIndex]);
        } else {
            Log.e(TAG, "Invalid subcription:" + mSimIndex);
            return null;
        }
    }

    private boolean isImportFromAllSelection() {
        return (mSimIndex == IMPORT_FROM_ALL);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        if (isImportFromAllSelection()) {
            Log.i(LOG_TAG, "Only import is supported");
            menu.removeItem(MENU_DELETE_ALL);
            menu.removeItem(MENU_ADD_CONTACT);
        }
        return true;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
            ContextMenu.ContextMenuInfo menuInfo) {
        if (menuInfo instanceof AdapterView.AdapterContextMenuInfo) {
            AdapterView.AdapterContextMenuInfo itemInfo =
                    (AdapterView.AdapterContextMenuInfo) menuInfo;
            TextView textView = (TextView) itemInfo.targetView.findViewById(android.R.id.text1);
            if (textView != null) {
                menu.setHeaderTitle(textView.getText());
            }
            menu.add(0, MENU_IMPORT_ONE, 0, R.string.importSimEntry);
            if (!isImportFromAllSelection()) {
                menu.add(0, MENU_EDIT_CONTACT, 0, R.string.editContact);
                menu.add(0, MENU_SMS, 0, R.string.sendSms);
                menu.add(0, MENU_DIAL, 0, R.string.dial);
                menu.add(0, MENU_DELETE, 0, R.string.delete);
            } else {
                Log.i(LOG_TAG, "Only import is supported");
            }
        }
    }

}
