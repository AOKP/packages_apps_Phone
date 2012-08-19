package com.android.phone;

import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;


public class MyPhoneNumber extends BroadcastReceiver {
    private final String LOG_TAG = "MyPhoneNumber";
    private final boolean DBG = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        TelephonyManager mTelephonyMgr = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        SharedPreferences prefs = context.getSharedPreferences(MyPhoneNumber.class.getPackage().getName() + "_preferences", Context.MODE_PRIVATE);

        String phoneNum = mTelephonyMgr.getLine1Number();
        String savedNum = prefs.getString(MSISDNEditPreference.PHONE_NUMBER, null);
        String simState = intent.getStringExtra(IccCard.INTENT_KEY_ICC_STATE);

        if (!IccCard.INTENT_VALUE_ICC_LOADED.equals(simState)) {
            /* Don't set line 1 number unless SIM_STATE is LOADED
             * (We're not using READY because the MSISDN record is not yet loaded on READY)
             */
            if (DBG)
                Log.d(LOG_TAG, "simState not correct. No modification to phone number. simState: " + simState);
        } else if (TextUtils.isEmpty(phoneNum)) {
            if (DBG)
                Log.d(LOG_TAG, "Trying to read the phone number from file");

            if (savedNum != null) {
                Phone mPhone = PhoneFactory.getDefaultPhone();
                String alphaTag = mPhone.getLine1AlphaTag();

                if (TextUtils.isEmpty(alphaTag)) {
                    // No tag, set it.
                    alphaTag = "Voice Line 1";
                }

                mPhone.setLine1Number(alphaTag, savedNum, null);

                if (DBG)
                    Log.d(LOG_TAG, "Phone number set to: " + savedNum);
            } else if (DBG) {
                    Log.d(LOG_TAG, "No phone number set yet");
            }
        } else if (DBG) {
            Log.d(LOG_TAG, "Phone number exists. No need to read it from file.");
        }
    }
}
