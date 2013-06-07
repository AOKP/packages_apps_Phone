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
import android.telephony.MSimTelephonyManager;
import android.util.Log;

/**
 * SIM Address Book UI for the Phone app.
 */
public class MSimContacts extends SimContacts {
    private static final String LOG_TAG = "MSimContacts";

    @Override
    protected Uri resolveIntent() {
        String[] adn = {"adn", "adn_sub2", "adn_sub3"};
        Intent intent = getIntent();
        int sub = MSimTelephonyManager.getDefault().getPreferredVoiceSubscription();

        if (sub < MSimTelephonyManager.getDefault().getPhoneCount()) {
            intent.setData(Uri.parse("content://iccmsim/" + adn[sub]));
        } else {
            Log.e(LOG_TAG, "Error: received invalid sub =" + sub);
        }

        if (Intent.ACTION_PICK.equals(intent.getAction())) {
            // "index" is 1-based
            mInitialSelection = intent.getIntExtra("index", 0) - 1;
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            mInitialSelection = 0;
        }
        return intent.getData();
    }

}
