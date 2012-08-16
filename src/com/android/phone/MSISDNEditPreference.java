
package com.android.phone;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.preference.EditTextPreference;
import android.util.AttributeSet;
import android.util.Log;

public class MSISDNEditPreference extends EditTextPreference {

    private static final String LOG_TAG = "MSISDNListPreference";
    public static final String PHONE_NUMBER = "phone_number";

    private final boolean DBG = (PhoneApp.DBG_LEVEL >= 2);

    private MyHandler mHandler = new MyHandler();

    private Phone mPhone;
    private Context mContext;

    private TimeConsumingPreferenceListener tcpListener;

    public MSISDNEditPreference(Context context, AttributeSet attrs) {
        super(context, attrs);

        mPhone = PhoneFactory.getDefaultPhone();
        mContext = context;
    }

    public MSISDNEditPreference(Context context) {
        this(context, null);
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);

        if (positiveResult) {
            String alphaTag = mPhone.getLine1AlphaTag();
            if (alphaTag == null || "".equals(alphaTag)) {
                // No tag, set it.
                alphaTag = "Voice Line 1";
            }

            mPhone.setLine1Number(alphaTag, getText(),
                    mHandler.obtainMessage(MyHandler.MESSAGE_SET_MSISDN));
            if (tcpListener != null) {
                tcpListener.onStarted(this, false);
            }

            // Save the number into the system property
            SharedPreferences prefs = mContext.getSharedPreferences(MSISDNEditPreference.class.getPackage().getName() + "_preferences", Context.MODE_PRIVATE);
            Editor editor = prefs.edit();

            String phoneNum = getText().trim();
            String savedNum = prefs.getString(PHONE_NUMBER, null);

            // If there is no string, treat it as null
            if (phoneNum.length() == 0) {
                phoneNum = null;
            }

            if (phoneNum == null && savedNum == null) {
                Log.d(LOG_TAG, "No phone number set yet");
            } else {
                if (phoneNum != null && phoneNum.equals(savedNum) == false) {
                    /* Save phone number only if there is some number set and
                       it is not equal to the already saved one */
                    if (DBG)
                        Log.d(LOG_TAG, "Saving phone number: " + phoneNum);

                    editor.putString(PHONE_NUMBER, phoneNum);
                    editor.commit();
                } else if (phoneNum == null && savedNum != null) {
                    /* Remove saved number only if there is some saved and
                       there is no number set */
                    if (DBG)
                        Log.d(LOG_TAG, "Removing phone number");

                    editor.remove(PHONE_NUMBER);
                    editor.commit();
                } else if (DBG) {
                    Log.d(LOG_TAG, "No change");
                }
            }
        }
    }

    void init(TimeConsumingPreferenceListener listener, boolean skipReading) {
        tcpListener = listener;
        if (!skipReading) {
            setText(mPhone.getLine1Number());
        }
    }

    private class MyHandler extends Handler {
        private static final int MESSAGE_SET_MSISDN = 0;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_SET_MSISDN:
                    handleSetMSISDNResponse(msg);
                    break;
            }
        }

        private void handleSetMSISDNResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception != null) {
                if (DBG)
                    Log.d(LOG_TAG, "handleSetMSISDNResponse: ar.exception=" + ar.exception);
                // setEnabled(false);
            }
            if (DBG)
                Log.d(LOG_TAG, "handleSetMSISDNResponse: re get");

            tcpListener.onFinished(MSISDNEditPreference.this, false);
        }
    }
}
