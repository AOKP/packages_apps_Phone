
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
            log("Got modify intent");
            if (intent.getExtras() != null) {
                int networkMode = intent.getExtras().getInt(NETWORK_MODE);

                if (isValidNetwork(networkMode)) {
                    log("Intent received with valid network mode: " + networkMode);
                    changeNetworkMode(networkMode);
                } else {
                    log("Not accepted network mode: " + networkMode);
                }
            }
        } else if (intent.getAction().equals(REQUEST_NETWORK_MODE)) {
            log("Sending Intent with current phone network mode");
            triggerIntent();
        } else {
            log("Not accepted intent: " + intent.getAction());
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

    private boolean isValidNetwork(int networkType) {
        boolean isCdma = (getPhone().getPhoneType() == Phone.PHONE_TYPE_CDMA);

        switch (networkType) {
            case Phone.NT_MODE_CDMA:
            case Phone.NT_MODE_CDMA_NO_EVDO:
            case Phone.NT_MODE_EVDO_NO_CDMA:
            case Phone.NT_MODE_GLOBAL:
            case Phone.NT_MODE_LTE_ONLY:
                return (isCdma);
            case Phone.NT_MODE_GSM_ONLY:
            case Phone.NT_MODE_GSM_UMTS:
            case Phone.NT_MODE_WCDMA_ONLY:
            case Phone.NT_MODE_WCDMA_PREF:
                return (!isCdma);
        }
        return false;
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

                int settingsNetworkMode = android.provider.Settings.Secure.getInt(
                        mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Secure.PREFERRED_NETWORK_MODE,
                        -1);

                log("handleGetPreferredNetworkTypeResponse: modemNetworkMode = " +
                        modemNetworkMode);
                log("handleGetPreferredNetworkTypeReponse: settingsNetworkMode = " +
                        settingsNetworkMode);

                // check that modemNetworkMode is from an accepted value
                if (isValidNetwork(modemNetworkMode)) {
                    log("New modem mode is valid, of type: " + modemNetworkMode);

                    // check changes in modemNetworkMode and updates settingsNetworkMode
                    if (modemNetworkMode != settingsNetworkMode) {
                        log("requested modem mode and current setting modes mismatch, setting setting mode to: "
                                + modemNetworkMode);

                        settingsNetworkMode = modemNetworkMode;

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
                    log("handleGetPreferredNetworkTypeResponse: lte only: no action");
                } else {
                    log("Some weird setting, don't touch anything!");
                    // resetNetworkModeToDefault();
                }
            }
        }

        private void handleSetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                // android.provider.Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                // android.provider.Settings.Secure.PREFERRED_NETWORK_MODE,
                // mLocalPreferredNetwork);
                log("Modem mode should have been successfully set now.");
                Intent intent = new Intent(PhoneToggler.NETWORK_MODE_CHANGED);
                intent.putExtra(PhoneToggler.NETWORK_MODE, mLocalPreferredNetwork);
                mPhone.getContext().sendBroadcast(intent, PhoneToggler.CHANGE_NETWORK_MODE_PERM);
            } else {
                // mPhone.getPreferredNetworkType(obtainMessage(MESSAGE_GET_PREFERRED_NETWORK_TYPE));
            }
        }

    }

    private static void log(String msg) {
        if (DBG)
            Log.d(LOG_TAG, msg);
    }

}
