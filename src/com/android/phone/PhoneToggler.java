
package com.android.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

public class PhoneToggler extends BroadcastReceiver {

    /** Used for brodcasting network data change and receive new mode **/
    public static final String NETWORK_MODE_CHANGED = "com.android.internal.telephony.NETWORK_MODE_CHANGED";
    public static final String REQUEST_NETWORK_MODE = "com.android.internal.telephony.REQUEST_NETWORK_MODE";
    public static final String MODIFY_NETWORK_MODE = "com.android.internal.telephony.MODIFY_NETWORK_MODE";
    public static final String MOBILE_DATA_CHANGED = "com.android.internal.telephony.MOBILE_DATA_CHANGED";
    public static final String NETWORK_MODE = "networkMode";

    public static final String CHANGE_NETWORK_MODE_PERM = "com.android.phone.CHANGE_NETWORK_MODE";
    private static final String LOG_TAG = "PhoneToggler";
    private static final boolean DBG = true;

    private MyHandler mHandler;
    private Phone mPhone;

    private Phone getPhone() {
        if (mPhone == null)
            mPhone = PhoneFactory.getDefaultPhone();
        return mPhone;
    }

    private MyHandler getHandler() {
        if (mHandler == null)
            mHandler = new MyHandler();
        return mHandler;
    }

    private int mLocalPreferredNetwork = Settings.preferredNetworkMode;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals(MODIFY_NETWORK_MODE)) {
            if (DBG) {
                Log.d(LOG_TAG, "Got modify intent");
            }
            if (intent.getExtras() != null) {
                int networkMode = intent.getExtras().getInt(NETWORK_MODE);
                if (networkMode == Phone.NT_MODE_WCDMA_PREF ||
                        networkMode == Phone.NT_MODE_GSM_ONLY ||
                        networkMode == Phone.NT_MODE_WCDMA_ONLY ||
                        networkMode == Phone.NT_MODE_GSM_UMTS ||
                        networkMode == Phone.NT_MODE_CDMA ||
                        networkMode == Phone.NT_MODE_CDMA_NO_EVDO ||
                        networkMode == Phone.NT_MODE_EVDO_NO_CDMA ||
                        networkMode == Phone.NT_MODE_GLOBAL ||
                        networkMode == Phone.NT_MODE_LTE_ONLY) {
                    if (DBG) {
                        Log.d(LOG_TAG, "Will modify it to: " + networkMode);
                    }
                    changeNetworkMode(networkMode);
                    if (DBG) {
                        log("Accepted modification of network mode to :" + networkMode);
                    }
                } else {
                    Log.e(LOG_TAG, "Not accepted network mode: " + networkMode);
                }
            }
        } else if (intent.getAction().equals(REQUEST_NETWORK_MODE)) {
            if (DBG) {
                Log.d(LOG_TAG, "Sending Intent with current phone network mode");
            }
            triggerIntent();
        } else {
            Log.e(LOG_TAG, "Not accepted intent: " + intent.getAction());
        }

    }

    private void changeNetworkMode(int modemNetworkMode) {
        getPhone().setPreferredNetworkType(modemNetworkMode, getHandler()
                .obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));

    }

    private void triggerIntent() {
        getPhone().getPreferredNetworkType(getHandler()
                .obtainMessage(MyHandler.MESSAGE_GET_PREFERRED_NETWORK_TYPE));
    }

    private class MyHandler extends Handler {

        private static final int MESSAGE_GET_PREFERRED_NETWORK_TYPE = 0;
        private static final int MESSAGE_SET_PREFERRED_NETWORK_TYPE = 1;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_PREFERRED_NETWORK_TYPE:
                    handleGetPreferredNetworkTypeResponse(msg);
                    break;

                case MESSAGE_SET_PREFERRED_NETWORK_TYPE:
                    handleSetPreferredNetworkTypeResponse(msg);
                    break;
            }
        }

        private void handleGetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                int modemNetworkMode = ((int[]) ar.result)[0];

                if (DBG) {
                    log("handleGetPreferredNetworkTypeResponse: modemNetworkMode = " +
                            modemNetworkMode);
                }

                int settingsNetworkMode = android.provider.Settings.Secure.getInt(
                        mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Secure.PREFERRED_NETWORK_MODE,
                        Settings.preferredNetworkMode);

                if (DBG) {
                    log("handleGetPreferredNetworkTypeReponse: settingsNetworkMode = " +
                            settingsNetworkMode);
                }

                // check that modemNetworkMode is from an accepted value
                if (modemNetworkMode == Phone.NT_MODE_WCDMA_PREF ||
                        modemNetworkMode == Phone.NT_MODE_GSM_ONLY ||
                        modemNetworkMode == Phone.NT_MODE_WCDMA_ONLY ||
                        modemNetworkMode == Phone.NT_MODE_GSM_UMTS ||
                        modemNetworkMode == Phone.NT_MODE_CDMA ||
                        modemNetworkMode == Phone.NT_MODE_CDMA_NO_EVDO ||
                        modemNetworkMode == Phone.NT_MODE_EVDO_NO_CDMA ||
                        modemNetworkMode == Phone.NT_MODE_GLOBAL) {
                    if (DBG) {
                        log("handleGetPreferredNetworkTypeResponse: if 1: modemNetworkMode = " +
                                modemNetworkMode);
                    }

                    // check changes in modemNetworkMode and updates settingsNetworkMode
                    if (modemNetworkMode != settingsNetworkMode) {
                        if (DBG) {
                            log("handleGetPreferredNetworkTypeResponse: if 2: " +
                                    "modemNetworkMode != settingsNetworkMode");
                        }

                        settingsNetworkMode = modemNetworkMode;

                        if (DBG) {
                            log("handleGetPreferredNetworkTypeResponse: if 2: " +
                                    "settingsNetworkMode = " + settingsNetworkMode);
                        }

                        // changes the Settings.System accordingly to modemNetworkMode
                        android.provider.Settings.Secure.putInt(
                                mPhone.getContext().getContentResolver(),
                                android.provider.Settings.Secure.PREFERRED_NETWORK_MODE,
                                settingsNetworkMode);
                    }

                    // changes the mButtonPreferredNetworkMode accordingly to modemNetworkMode
                    mLocalPreferredNetwork = modemNetworkMode;

                    Intent intent = new Intent(PhoneToggler.NETWORK_MODE_CHANGED);
                    intent.putExtra(PhoneToggler.NETWORK_MODE, modemNetworkMode);
                    mPhone.getContext()
                            .sendBroadcast(intent, PhoneToggler.CHANGE_NETWORK_MODE_PERM);

                } else if (modemNetworkMode == Phone.NT_MODE_LTE_ONLY) {
                    // LTE Only mode not yet supported on UI, but could be used for testing
                    if (DBG)
                        log("handleGetPreferredNetworkTypeResponse: lte only: no action");
                } else {
                    if (DBG)
                        log("handleGetPreferredNetworkTypeResponse: else: reset to default");
                    resetNetworkModeToDefault();
                }
            }
        }

        private void handleSetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                android.provider.Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Secure.PREFERRED_NETWORK_MODE,
                        mLocalPreferredNetwork);

                Intent intent = new Intent(PhoneToggler.NETWORK_MODE_CHANGED);
                intent.putExtra(PhoneToggler.NETWORK_MODE, mLocalPreferredNetwork);
                mPhone.getContext().sendBroadcast(intent, PhoneToggler.CHANGE_NETWORK_MODE_PERM);
            } else {
                mPhone.getPreferredNetworkType(obtainMessage(MESSAGE_GET_PREFERRED_NETWORK_TYPE));
            }
        }

        private void resetNetworkModeToDefault() {
            // set the mButtonPreferredNetworkMode
            mLocalPreferredNetwork = Settings.preferredNetworkMode;
            // set the Settings.System
            android.provider.Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.PREFERRED_NETWORK_MODE,
                    Settings.preferredNetworkMode);
            // Set the Modem
            mPhone.setPreferredNetworkType(Settings.preferredNetworkMode,
                    this.obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
        }

    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }

}
