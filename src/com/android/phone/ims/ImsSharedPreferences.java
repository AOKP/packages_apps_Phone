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

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Wrapper for IMS's shared preferences.
 */
public class ImsSharedPreferences {
    private static final String TAG = "IMSPreferences";
    private static final String IMS_SHARED_PREFERENCES = "IMS_PREFERENCES";
    private static final String KEY_IMS_SERVER_ADDRESS = "ims_server_address";
    private static final String KEY_IMS_CALL_TYPE = "ims_call_type";
    private static final String KEY_IMS_IS_DEFAULT = "ims_is_default";

    private SharedPreferences mPreferences;

    // Check if the tag is loggable
    private static final boolean DBG = Log.isLoggable("IMS", Log.DEBUG);

    public ImsSharedPreferences(Context context) {
        mPreferences = context.getSharedPreferences(
                IMS_SHARED_PREFERENCES, Context.MODE_WORLD_READABLE);
    }

    public void setServerAddress(String address) {
        if (DBG) Log.d(TAG, "setServerAddress: " + address);
        SharedPreferences.Editor editor = mPreferences.edit();
        editor.putString(KEY_IMS_SERVER_ADDRESS, (address == null) ? "" : address);
        editor.apply();
    }

    public String getServerAddress() {
        String address = mPreferences.getString(KEY_IMS_SERVER_ADDRESS, null);
        if (DBG) Log.d(TAG, "getServerAddress: " + address);
        return address;
    }

    public void setCallType(int callType) {
        if (DBG) Log.d(TAG, "setCallType: " + callType);
        SharedPreferences.Editor editor = mPreferences.edit();
        editor.putInt(KEY_IMS_CALL_TYPE, callType);
        editor.apply();
    }

    public int getCallType() {
        int callType = mPreferences.getInt(KEY_IMS_CALL_TYPE, Phone.CALL_TYPE_VOICE);
        if (DBG) Log.d(TAG, "getCallType: " + callType);
        return callType;
    }

    public boolean getisImsDefault() {
        boolean isImsDefault = mPreferences.getBoolean(KEY_IMS_IS_DEFAULT, false);
        if (DBG) Log.d(TAG, "getisImsDefault: " + isImsDefault);
        return isImsDefault;
    }

    public void setIsImsDefault(boolean isImsDefault) {
        if (DBG) Log.d(TAG, "setIsImsDefault: " + isImsDefault);
        SharedPreferences.Editor editor = mPreferences.edit();
        editor.putBoolean(KEY_IMS_IS_DEFAULT, isImsDefault);
        editor.apply();
    }
}
