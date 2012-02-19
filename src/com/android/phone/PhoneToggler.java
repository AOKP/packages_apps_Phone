package com.android.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;

public class PhoneToggler extends BroadcastReceiver  {

    /** Used for brodcasting network data change and receive new mode **/
    public static final String NETWORK_MODE_CHANGED="com.android.internal.telephony.NETWORK_MODE_CHANGED";
    public static final String REQUEST_NETWORK_MODE="com.android.internal.telephony.REQUEST_NETWORK_MODE";
    public static final String MODIFY_NETWORK_MODE="com.android.internal.telephony.MODIFY_NETWORK_MODE";
    public static final String MOBILE_DATA_CHANGED="com.android.internal.telephony.MOBILE_DATA_CHANGED";
    public static final String NETWORK_MODE = "networkMode";

    public static final String CHANGE_NETWORK_MODE_PERM= "com.android.phone.CHANGE_NETWORK_MODE";
    private static final String LOG_TAG = "PhoneToggler";
    private static final boolean DBG = true;

    private Phone mPhone;
    private MyHandler mHandler;


    private Phone getPhone() {
        if (mPhone==null) mPhone = PhoneFactory.getDefaultPhone();
        return mPhone;
    }

    private MyHandler getHandler() {
        if (mHandler==null) mHandler = new MyHandler();
        return mHandler;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals(MODIFY_NETWORK_MODE)) {
            if (DBG) Log.d(LOG_TAG,"Got modify intent");
            if (intent.getExtras()!=null) {
                int networkMode = intent.getExtras().getInt(NETWORK_MODE);
                boolean networkModeOk = false;
                int phoneType = getPhone().getPhoneType();
                boolean isLteOnCdma = getPhone().getLteOnCdmaMode() == PhoneConstants.LTE_ON_CDMA_TRUE;

                if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                    if (networkMode == Phone.NT_MODE_GSM_ONLY
                            || networkMode == Phone.NT_MODE_GSM_UMTS
                            || networkMode == Phone.NT_MODE_WCDMA_PREF
                            || networkMode == Phone.NT_MODE_LTE_GSM_WCDMA
                            || networkMode == Phone.NT_MODE_WCDMA_ONLY) {
                        networkModeOk = true;
                    }
                } else if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    if (networkMode == Phone.NT_MODE_CDMA
                            || networkMode == Phone.NT_MODE_CDMA_NO_EVDO
                            || networkMode == Phone.NT_MODE_EVDO_NO_CDMA) {
                        networkModeOk = true;
                    }
                }
                if (context.getResources().getBoolean(R.bool.world_phone) || isLteOnCdma) {
                    if (networkMode == Phone.NT_MODE_GLOBAL) {
                        networkModeOk = true;
                    }
                }

                if (networkModeOk) {
                    if (DBG) Log.d(LOG_TAG,"Will modify it to: "+networkMode);
                    changeNetworkMode(networkMode);
                    if (DBG) Log.d(LOG_TAG,"Accepted modification of network mode to : "+networkMode);
                } else {
                    Log.e(LOG_TAG,"Not accepted network mode: "+networkMode);
                }
            }
        } else if (intent.getAction().equals(REQUEST_NETWORK_MODE)) {
            if (DBG) Log.d(LOG_TAG,"Sending Intent with current phone network mode");
            triggerIntent();
        } else {
            Log.e(LOG_TAG,"Not accepted intent: "+intent.getAction());
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
                int modemNetworkMode = ((int[])ar.result)[0];
                if (DBG) Log.d(LOG_TAG,"handleGetPreferredNetworkTypeResponse: modemNetworkMode = "+modemNetworkMode);
                int settingsNetworkMode = android.provider.Settings.Global.getInt(
                        getPhone().getContext().getContentResolver(),
                        android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                        MobileNetworkSettings.preferredNetworkMode);

                if (DBG) Log.d(LOG_TAG,"handleGetPreferredNetworkTypeReponse: settingsNetworkMode = "+settingsNetworkMode);

                //check that modemNetworkMode is from an accepted value
                if (modemNetworkMode == Phone.NT_MODE_WCDMA_PREF ||
                        modemNetworkMode == Phone.NT_MODE_GSM_ONLY ||
                        modemNetworkMode == Phone.NT_MODE_WCDMA_ONLY ||
                        modemNetworkMode == Phone.NT_MODE_GSM_UMTS ||
                        modemNetworkMode == Phone.NT_MODE_CDMA ||
                        modemNetworkMode == Phone.NT_MODE_CDMA_NO_EVDO ||
                        modemNetworkMode == Phone.NT_MODE_EVDO_NO_CDMA ||
                        //A modem might report world phone sometimes
                        //but it's not true. Double check here
                        ((getPhone().getContext().getResources().getBoolean(R.bool.world_phone) == true) &&
                            modemNetworkMode == Phone.NT_MODE_GLOBAL) ||
                        modemNetworkMode == Phone.NT_MODE_LTE_CDMA_AND_EVDO ||
                        modemNetworkMode == Phone.NT_MODE_LTE_GSM_WCDMA ||
                        modemNetworkMode == Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA ||
                        modemNetworkMode == Phone.NT_MODE_LTE_WCDMA ) {
                    if (DBG) Log.d(LOG_TAG,"handleGetPreferredNetworkTypeResponse: if 1: modemNetworkMode = "+modemNetworkMode);

                    //check changes in modemNetworkMode and updates settingsNetworkMode
                    if (modemNetworkMode != settingsNetworkMode) {
                        if (DBG) Log.d(LOG_TAG,"handleGetPreferredNetworkTypeResponse: if 2: modemNetworkMode != settingsNetworkMode");
                        settingsNetworkMode = modemNetworkMode;
                        if (DBG) Log.d(LOG_TAG,"handleGetPreferredNetworkTypeResponse: if 2: settingsNetworkMode = "+settingsNetworkMode);

                        //changes the Settings.System accordingly to modemNetworkMode
                        android.provider.Settings.Global.putInt(
                                getPhone().getContext().getContentResolver(),
                                android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                                settingsNetworkMode );
                    }
                    Intent intent = new Intent(NETWORK_MODE_CHANGED);
                    intent.putExtra(NETWORK_MODE, settingsNetworkMode);
                    getPhone().getContext().sendBroadcast(intent,CHANGE_NETWORK_MODE_PERM);
                } else if (modemNetworkMode == Phone.NT_MODE_LTE_ONLY) {
                    if (DBG) Log.d(LOG_TAG,"handleGetPreferredNetworkTypeResponse: lte only: no action");
                } else {
                    if (DBG) Log.d(LOG_TAG,"handleGetPreferredNetworkTypeResponse: else: reset to default");
                    resetNetworkModeToDefault();
                }
            }
        }

        private void handleSetPreferredNetworkTypeResponse(Message msg) {
            //PSAFS - TODO: For now no status is stored, so we will always get the real status from Phone.
            getPhone().getPreferredNetworkType(obtainMessage(MESSAGE_GET_PREFERRED_NETWORK_TYPE));
        }

        private void resetNetworkModeToDefault() {
            android.provider.Settings.Global.putInt(getPhone().getContext().getContentResolver(),
                        android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                        MobileNetworkSettings.preferredNetworkMode );
            //Set the Modem
            getPhone().setPreferredNetworkType(MobileNetworkSettings.preferredNetworkMode,
                    this.obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
        }
    }
}
