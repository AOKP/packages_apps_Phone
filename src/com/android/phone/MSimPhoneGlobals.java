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

import android.app.KeyguardManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.IPowerManager;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UpdateLock;
import android.preference.PreferenceManager;
import android.provider.Settings.System;
import android.telephony.ServiceState;
import android.util.Log;
import android.view.KeyEvent;

import android.telephony.TelephonyManager;
import android.telephony.MSimTelephonyManager;
import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyCapabilities;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.cdma.TtyIntent;
import com.android.phone.common.CallLogAsync;
import com.android.phone.OtaUtils.CdmaOtaScreenState;
import com.android.internal.telephony.PhoneConstants;
import com.codeaurora.telephony.msim.MSimPhoneFactory;
import com.codeaurora.telephony.msim.SubscriptionManager;
import com.codeaurora.telephony.msim.MSimTelephonyIntents;

import java.util.ArrayList;

import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;

/**
 * Top-level Application class for the Phone app.
 */
public class MSimPhoneGlobals extends PhoneGlobals {
    /* package */ static final String LOG_TAG = "MSimPhoneGlobals";

    /**
     * Phone app-wide debug level:
     *   0 - no debug logging
     *   1 - normal debug logging if ro.debuggable is set (which is true in
     *       "eng" and "userdebug" builds but not "user" builds)
     *   2 - ultra-verbose debug logging
     *
     * Most individual classes in the phone app have a local DBG constant,
     * typically set to
     *   (PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1)
     * or else
     *   (PhoneApp.DBG_LEVEL >= 2)
     * depending on the desired verbosity.
     */
    /* package */ static final int DBG_LEVEL = 1;

    //TODO DSDS,restore the logging levels
    private static final boolean DBG =
            (MSimPhoneGlobals.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (MSimPhoneGlobals.DBG_LEVEL >= 2);

    // Broadcast receiver for various intent broadcasts (see onCreate())
    private BroadcastReceiver mReceiver = new PhoneAppBroadcastReceiver();

    // Broadcast receiver purely for ACTION_MEDIA_BUTTON broadcasts
    private BroadcastReceiver mMediaButtonReceiver = new MediaButtonBroadcastReceiver();

    /* Array of MSPhone Objects to store each phoneproxy and associated objects */
    private static MSPhone[] mMSPhones;

    private int mDefaultSubscription = 0;

    MSimPhoneInterfaceManager phoneMgrMSim;

    MSimPhoneGlobals(Context context) {
        super(context);
        Log.d(LOG_TAG,"MSPhoneApp creation"+this);
    }

    public void onCreate() {
        if (VDBG) Log.v(LOG_TAG, "onCreate()...");
        Log.d(LOG_TAG, "MSimPhoneApp:"+this);

        ContentResolver resolver = getContentResolver();

        // Cache the "voice capable" flag.
        // This flag currently comes from a resource (which is
        // overrideable on a per-product basis):
        sVoiceCapable =
                getResources().getBoolean(com.android.internal.R.bool.config_voice_capable);
        // ...but this might eventually become a PackageManager "system
        // feature" instead, in which case we'd do something like:
        // sVoiceCapable =
        //   getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_VOICE_CALLS);

        if (phone == null) {
            // Initialize the telephony framework
            MSimPhoneFactory.makeMultiSimDefaultPhones(this);

            // Get the default phone
            phone = MSimPhoneFactory.getDefaultPhone();

            // Start TelephonyDebugService After the default phone is created.
            Intent intent = new Intent(this, TelephonyDebugService.class);
            startService(intent);

            mCM = CallManager.getInstance();

            int numPhones = MSimTelephonyManager.getDefault().getPhoneCount();
            // Create MSPhone which hold phone proxy and its corresponding memebers.
            mMSPhones = new MSPhone[numPhones];
            for(int i = 0; i < numPhones; i++) {
                mMSPhones [i] = new MSPhone(i);
                mCM.registerPhone(mMSPhones[i].mPhone);
            }

            // Get the default subscription from the system property
            mDefaultSubscription = getDefaultSubscription();

            // Set Default PhoneApp variables
            setDefaultPhone(mDefaultSubscription);
            mCM.registerPhone(phone);

            // Create the NotificationMgr singleton, which is used to display
            // status bar icons and control other status bar behavior.
            notificationMgr = MSimNotificationMgr.init(this);

            phoneMgr = PhoneInterfaceManager.init(this, phone);
            phoneMgrMSim = MSimPhoneInterfaceManager.init(this, phone);

            mHandler.sendEmptyMessage(EVENT_START_SIP_SERVICE);

            int phoneType = phone.getPhoneType();

            if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                // Create an instance of CdmaPhoneCallState and initialize it to IDLE
                cdmaPhoneCallState = new CdmaPhoneCallState();
                cdmaPhoneCallState.CdmaPhoneCallStateInit();
            }

            if (BluetoothAdapter.getDefaultAdapter() != null) {
                // Start BluetoothPhoneService even if device is not voice capable.
                // The device can still support VOIP.
                startService(new Intent(this, BluetoothPhoneService.class));
                bindService(new Intent(this, BluetoothPhoneService.class),
                            mBluetoothPhoneConnection, 0);
            } else {
                // Device is not bluetooth capable
                mBluetoothPhone = null;
            }

            ringer = Ringer.init(this);

            mReceiver = new MSimPhoneAppBroadcastReceiver();
            mMediaButtonReceiver = new MSimMediaButtonBroadcastReceiver();

            // before registering for phone state changes
            mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            mWakeLock = mPowerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, LOG_TAG);
            // lock used to keep the processor awake, when we don't care for the display.
            mPartialWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK
                    | PowerManager.ON_AFTER_RELEASE, LOG_TAG);
            // Wake lock used to control proximity sensor behavior.
            if (mPowerManager.isWakeLockLevelSupported(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                mProximityWakeLock = mPowerManager.newWakeLock(
                        PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, LOG_TAG);
            }
            if (DBG) Log.d(LOG_TAG, "onCreate: mProximityWakeLock: " + mProximityWakeLock);

            // create mAccelerometerListener only if we are using the proximity sensor
            if (proximitySensorModeEnabled()) {
                mAccelerometerListener = new AccelerometerListener(this, this);
            }

            mKeyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

            // get a handle to the service so that we can use it later when we
            // want to set the poke lock.
            mPowerManagerService = IPowerManager.Stub.asInterface(
                    ServiceManager.getService("power"));

            // TODO DSDS: See if something specific needs to be done for DSDS
            // Get UpdateLock to suppress system-update related events (e.g. dialog show-up)
            // during phone calls.
            mUpdateLock = new UpdateLock("phone");

            if (DBG) Log.d(LOG_TAG, "onCreate: mUpdateLock: " + mUpdateLock);

            CallLogger callLogger = new CallLogger(this, new CallLogAsync());

            // Create the CallController singleton, which is the interface
            // to the telephony layer for user-initiated telephony functionality
            // (like making outgoing calls.)
            callController = MSimCallController.init(this, callLogger);
            // ...and also the InCallUiState instance, used by the CallController to
            // keep track of some "persistent state" of the in-call UI.
            inCallUiState = InCallUiState.init(this);

            // Create the CallerInfoCache singleton, which remembers custom ring tone and
            // send-to-voicemail settings.
            //
            // The asynchronous caching will start just after this call.
            callerInfoCache = CallerInfoCache.init(this);

            // Create the CallNotifer singleton, which handles
            // asynchronous events from the telephony layer (like
            // launching the incoming-call UI when an incoming call comes
            // in.)
            notifier = MSimCallNotifier.init(this, phone, ringer, callLogger);

            // Create the Managed Roaming singleton class, used to show popup
            // to user for initiating network search when location update is rejected
            mManagedRoam = ManagedRoaming.init(this);

            XDivertUtility.init(this, phone, (MSimCallNotifier)notifier, this);

            // register for ICC status
            for (int i = 0; i < MSimTelephonyManager.getDefault().getPhoneCount(); i++) {
                IccCard sim = getPhone(i).getIccCard();
                if (sim != null) {
                    if (VDBG) Log.v(LOG_TAG, "register for ICC status on subscription: " + i);
                    sim.registerForPersoLocked(mHandler,
                            EVENT_PERSO_LOCKED, new Integer(i));
                }
            }

            // register for MMI/USSD
            mCM.registerForMmiComplete(mHandler, MMI_COMPLETE, null);

            // register connection tracking to PhoneUtils
            PhoneUtils.initializeConnectionHandler(mCM);

            // Read platform settings for TTY feature
            mTtyEnabled = getResources().getBoolean(R.bool.tty_enabled);

            // Register for misc other intent broadcasts.
            IntentFilter intentFilter =
                    new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            intentFilter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
            intentFilter.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
            intentFilter.addAction(Intent.ACTION_HEADSET_PLUG);
            intentFilter.addAction(Intent.ACTION_DOCK_EVENT);
            intentFilter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
            intentFilter.addAction(MSimTelephonyIntents.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);
            if (mTtyEnabled) {
                intentFilter.addAction(TtyIntent.TTY_PREFERRED_MODE_CHANGE_ACTION);
            }
            intentFilter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
            registerReceiver(mReceiver, intentFilter);

            // Use a separate receiver for ACTION_MEDIA_BUTTON broadcasts,
            // since we need to manually adjust its priority (to make sure
            // we get these intents *before* the media player.)
            IntentFilter mediaButtonIntentFilter =
                    new IntentFilter(Intent.ACTION_MEDIA_BUTTON);
            // TODO verify the independent priority doesn't need to be handled thanks to the
            //  private intent handler registration
            // Make sure we're higher priority than the media player's
            // MediaButtonIntentReceiver (which currently has the default
            // priority of zero; see apps/Music/AndroidManifest.xml.)
            mediaButtonIntentFilter.setPriority(1);
            //
            registerReceiver(mMediaButtonReceiver, mediaButtonIntentFilter);
            // register the component so it gets priority for calls
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            am.registerMediaButtonEventReceiverForCalls(new ComponentName(this.getPackageName(),
                    MediaButtonBroadcastReceiver.class.getName()));

            //set the default values for the preferences in the phone.
            PreferenceManager.setDefaultValues(this, R.xml.network_setting, false);

            PreferenceManager.setDefaultValues(this, R.xml.call_feature_setting, false);

            // Make sure the audio mode (along with some
            // audio-mode-related state of our own) is initialized
            // correctly, given the current state of the phone.
            PhoneUtils.setAudioMode(mCM);
        }

        for (int i = 0; i < MSimTelephonyManager.getDefault().getPhoneCount(); i++) {
            updatePhoneAppCdmaVariables(i);
        }

        // XXX pre-load the SimProvider so that it's ready
        resolver.getType(Uri.parse("content://icc/adn"));

        // start with the default value to set the mute state.
        mShouldRestoreMuteOnInCallResume = false;

        // TODO: Register for Cdma Information Records
        // phone.registerCdmaInformationRecord(mHandler, EVENT_UNSOL_CDMA_INFO_RECORD, null);

        // Read TTY settings and store it into BP NV.
        // AP owns (i.e. stores) the TTY setting in AP settings database and pushes the setting
        // to BP at power up (BP does not need to make the TTY setting persistent storage).
        // This way, there is a single owner (i.e AP) for the TTY setting in the phone.
        if (mTtyEnabled) {
            mPreferredTtyMode = android.provider.Settings.Secure.getInt(
                    phone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.PREFERRED_TTY_MODE,
                    Phone.TTY_MODE_OFF);
            mHandler.sendMessage(mHandler.obtainMessage(EVENT_TTY_PREFERRED_MODE_CHANGED, 0));
        }
        // Read HAC settings and configure audio hardware
        if (getResources().getBoolean(R.bool.hac_enabled)) {
            int hac = android.provider.Settings.System.getInt(
                    phone.getContext().getContentResolver(),
                    android.provider.Settings.System.HEARING_AID, 0);
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            audioManager.setParameter(CallFeaturesSetting.HAC_KEY, hac != 0 ?
                                      CallFeaturesSetting.HAC_VAL_ON :
                                      CallFeaturesSetting.HAC_VAL_OFF);
        }

    }

    /**
     * Returns an Intent that can be used to go to the "Call log"
     * UI (aka CallLogActivity) in the Contacts app.
     *
     * Watch out: there's no guarantee that the system has any activity to
     * handle this intent.  (In particular there may be no "Call log" at
     * all on on non-voice-capable devices.)
     */
    /* package */ Intent createCallLogIntent(int subscription) {
        Intent  intent = new Intent(Intent.ACTION_VIEW, null);
        intent.putExtra(SUBSCRIPTION_KEY, subscription);
        intent.setType("vnd.android.cursor.dir/calls");
        return intent;
    }

    /**
     * Return an Intent that can be used to bring up the in-call screen.
     *
     * This intent can only be used from within the Phone app, since the
     * InCallScreen is not exported from our AndroidManifest.
     */
    @Override
    /* package */ Intent createInCallIntent(int subscription) {
        Log.d(LOG_TAG, "createInCallIntent subscription:");
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.putExtra(SUBSCRIPTION_KEY, subscription);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        intent.setClassName("com.android.phone", getCallScreenClassName());
        return intent;
    }

    protected static String getCallScreenClassName() {
        return MSimInCallScreen.class.getName();
    }

    @Override
    /* package */ void displayCallScreen() {
        if (VDBG) Log.d(LOG_TAG, "displayCallScreen()...");

        // On non-voice-capable devices we shouldn't ever be trying to
        // bring up the InCallScreen in the first place.
        if (!sVoiceCapable) {
            Log.w(LOG_TAG, "displayCallScreen() not allowed: non-voice-capable device",
                  new Throwable("stack dump"));  // Include a stack trace since this warning
                                                 // indicates a bug in our caller
            return;
        }

        try {
            startActivity(createInCallIntent(mCM.getPhoneInCall().getSubscription()));
        } catch (ActivityNotFoundException e) {
            // It's possible that the in-call UI might not exist (like on
            // non-voice-capable devices), so don't crash if someone
            // accidentally tries to bring it up...
            Log.w(LOG_TAG, "displayCallScreen: transition to InCallScreen failed: " + e);
        }
        Profiler.callScreenRequested();
    }

    boolean isSimPinEnabled(int subscription) {
        MSPhone msPhone = getMSPhone(subscription);
        return msPhone.mIsSimPinEnabled;
    }

    /**
     * Dismisses the in-call UI.
     *
     * This also ensures that you won't be able to get back to the in-call
     * UI via the BACK button (since this call removes the InCallScreen
     * from the activity history.)
     * For OTA Call, it call InCallScreen api to handle OTA Call End scenario
     * to display OTA Call End screen.
     */
    /* package */
    void dismissCallScreen(Phone phone) {
        if (mInCallScreen != null) {
            if ((TelephonyCapabilities.supportsOtasp(phone)) &&
                    (mInCallScreen.isOtaCallInActiveState()
                    || mInCallScreen.isOtaCallInEndState()
                    || ((cdmaOtaScreenState != null)
                    && (cdmaOtaScreenState.otaScreenState
                            != CdmaOtaScreenState.OtaScreenState.OTA_STATUS_UNDEFINED)))) {
                // TODO: During OTA Call, display should not become dark to
                // allow user to see OTA UI update. Phone app needs to hold
                // a SCREEN_DIM_WAKE_LOCK wake lock during the entire OTA call.
                wakeUpScreen();
                // If InCallScreen is not in foreground we resume it to show the OTA call end screen
                // Fire off the InCallScreen intent
                displayCallScreen();

                mInCallScreen.handleOtaCallEnd();
                return;
            } else {
                mInCallScreen.finish();
            }
        }
    }

    @Override
    /* package */ PhoneConstants.State getPhoneState(int subscription) {
        return getMSPhone(subscription).mLastPhoneState;
    }

    @Override
    public void onMMIComplete(AsyncResult r) {
        if (VDBG) Log.d(LOG_TAG, "onMMIComplete()...");
        MmiCode mmiCode = (MmiCode) r.result;
        Phone localPhone = (Phone) mmiCode.getPhone();
        PhoneUtils.displayMMIComplete(localPhone, getInstance(), mmiCode, null, null);
    }

    void initForNewRadioTechnology(int subscription) {
        if (DBG) Log.d(LOG_TAG, "initForNewRadioTechnology...");
        MSPhone msPhone = getMSPhone(subscription);

        Phone phone = msPhone.mPhone;

        if (TelephonyCapabilities.supportsOtasp(phone)) {
           // Create an instance of CdmaPhoneCallState and initialize it to IDLE
           msPhone.initializeCdmaVariables();
           updatePhoneAppCdmaVariables(subscription);
           clearOtaState();
        }

        ringer.updateRingerContextAfterRadioTechnologyChange(this.phone);
        notifier.updateCallNotifierRegistrationsAfterRadioTechnologyChange();
        if (mBluetoothPhone != null) {
            try {
                mBluetoothPhone.updateBtHandsfreeAfterRadioTechnologyChange();
            } catch (RemoteException e) {
                Log.e(LOG_TAG, Log.getStackTraceString(new Throwable()));
            }
        }
        if (mInCallScreen != null) {
            mInCallScreen.updateAfterRadioTechnologyChange();
        }

        // Update registration for ICC status after radio technology change
        IccCard sim = phone.getIccCard();
        if (sim != null) {
            if (DBG) Log.d(LOG_TAG, "Update registration for ICC status...");

            //Register all events new to the new active phone
            sim.registerForPersoLocked(mHandler, EVENT_PERSO_LOCKED, null);
        }
    }

    /**
     * Receiver for misc intent broadcasts the Phone app cares about.
     */
    private class MSimPhoneAppBroadcastReceiver extends PhoneGlobals.PhoneAppBroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.v(LOG_TAG,"Action intent recieved:"+action);
            //gets the subscription information ( "0" or "1")
            int subscription = intent.getIntExtra(SUBSCRIPTION_KEY, getDefaultSubscription());
            if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                boolean enabled = System.getInt(getContentResolver(),
                        System.AIRPLANE_MODE_ON, 0) == 0;
                // Set the airplane mode property for RIL to read on boot up
                // to know if the phone is in airplane mode so that RIL can
                // power down the ICC card.
                Log.d(LOG_TAG, "Setting property " + PROPERTY_AIRPLANE_MODE_ON);
                // enabled here implies airplane mode is OFF from above condition
                SystemProperties.set(PROPERTY_AIRPLANE_MODE_ON, (enabled ? "0" : "1"));
                for (int i = 0; i < MSimTelephonyManager.getDefault().getPhoneCount(); i++) {
                    getPhone(i).setRadioPower(enabled);
                }

            } else if ((action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) &&
                    (mPUKEntryActivity != null)) {
                // if an attempt to un-PUK-lock the device was made, while we're
                // receiving this state change notification, notify the handler.
                // NOTE: This is ONLY triggered if an attempt to un-PUK-lock has
                // been attempted.
                mHandler.sendMessage(mHandler.obtainMessage(EVENT_SIM_STATE_CHANGED,
                        intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE)));
            } else if (action.equals(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED)) {
                String newPhone = intent.getStringExtra(PhoneConstants.PHONE_NAME_KEY);
                Log.d(LOG_TAG, "Radio technology switched. Now " + newPhone + " is active.");
                initForNewRadioTechnology(subscription);
            } else if (action.equals(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED)) {
                Phone phone = getPhone(subscription);
                handleServiceStateChanged(intent, phone);
            } else if (action.equals(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED)) {
                Phone phone = getPhone(subscription);
                if (TelephonyCapabilities.supportsEcm(phone)) {
                    Log.d(LOG_TAG, "Emergency Callback Mode arrived in PhoneApp"
                            + " on Sub =" + subscription);
                    // Start Emergency Callback Mode service
                    if (intent.getBooleanExtra("phoneinECMState", false)) {
                        Intent ecbmIntent = new Intent(context, EmergencyCallbackModeService.class);
                        ecbmIntent.putExtra(SUBSCRIPTION_KEY, subscription);
                        context.startService(ecbmIntent);
                    }
                } else {
                    // It doesn't make sense to get ACTION_EMERGENCY_CALLBACK_MODE_CHANGED
                    // on a device that doesn't support ECM in the first place.
                    Log.e(LOG_TAG, "Got ACTION_EMERGENCY_CALLBACK_MODE_CHANGED, "
                          + "but ECM isn't supported for phone: " + phone.getPhoneName());
                }
            } else if (action.equals(MSimTelephonyIntents.ACTION_DEFAULT_SUBSCRIPTION_CHANGED)) {
                Log.d(LOG_TAG, "Default subscription changed, subscription: " + subscription);
                mDefaultSubscription = subscription;
                setDefaultPhone(subscription);
                phoneMgr.setPhone(phone);
            } else {
                super.onReceive(context, intent);
            }
        }
    }

    /**
     * Broadcast receiver for the ACTION_MEDIA_BUTTON broadcast intent.
     *
     * This functionality isn't lumped in with the other intents in
     * PhoneAppBroadcastReceiver because we instantiate this as a totally
     * separate BroadcastReceiver instance, since we need to manually
     * adjust its IntentFilter's priority (to make sure we get these
     * intents *before* the media player.)
     */
    private class MSimMediaButtonBroadcastReceiver extends
            PhoneGlobals.MediaButtonBroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            KeyEvent event = (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            Log.d(LOG_TAG, "MediaButtonBroadcastReceiver.onReceive() event = " + event);
            if ((event != null)
                    && (event.getKeyCode() == KeyEvent.KEYCODE_HEADSETHOOK)) {
                if (VDBG) Log.d(LOG_TAG, "MediaButtonBroadcastReceiver: HEADSETHOOK");

                for (int i = 0; i < MSimTelephonyManager.getDefault().getPhoneCount(); i++) {
                    boolean consumed = PhoneUtils.handleHeadsetHook(getPhone(i), event);
                    Log.d(LOG_TAG, "handleHeadsetHook(): consumed = " + consumed +
                            " on SUB ["+i+"]");
                    if (consumed) {
                        // If a headset is attached and the press is consumed, also update
                        // any UI items (such as an InCallScreen mute button) that may need to
                        // be updated if their state changed.
                        updateInCallScreen();  // Has no effect if the InCallScreen isn't visible
                        abortBroadcast();
                        break;
                    }
                }
            } else {
                if (mCM.getState() != PhoneConstants.State.IDLE) {
                    // If the phone is anything other than completely idle,
                    // then we consume and ignore any media key events,
                    // Otherwise it is too easy to accidentally start
                    // playing music while a phone call is in progress.
                    if (VDBG) Log.d(LOG_TAG, "MediaButtonBroadcastReceiver: consumed");
                    abortBroadcast();
                }
            }
        }
    }

    // updates cdma variables of PhoneApp
    private void updatePhoneAppCdmaVariables(int subscription) {
        Log.v(LOG_TAG,"updatePhoneAppCdmaVariables for SUB" + subscription);
        MSPhone msPhone = getMSPhone(subscription);

        if ((msPhone != null) &&(msPhone.mPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA)) {
            cdmaPhoneCallState = msPhone.mCdmaPhoneCallState;
            cdmaOtaProvisionData = msPhone.mCdmaOtaProvisionData;
            cdmaOtaConfigData = msPhone.mCdmaOtaConfigData;
            cdmaOtaScreenState = msPhone.mCdmaOtaScreenState;
            cdmaOtaInCallScreenUiState = msPhone.mCdmaOtaInCallScreenUiState;
        }
    }

    private void clearCdmaVariables(int subscription) {
        MSPhone msPhone = getMSPhone(subscription);
        msPhone.clearCdmaVariables();
        cdmaPhoneCallState = null;
        cdmaOtaProvisionData = null;
        cdmaOtaConfigData = null;
        cdmaOtaScreenState = null;
        cdmaOtaInCallScreenUiState = null;
    }

    private void handleServiceStateChanged(Intent intent, Phone phone) {
        // This function used to handle updating EriTextWidgetProvider

        // If service just returned, start sending out the queued messages
        ServiceState ss = ServiceState.newFromBundle(intent.getExtras());

        if (ss != null) {
            int state = ss.getState();
            notificationMgr.updateNetworkSelection(state, phone);
        }
    }

    // gets the MSPhone corresponding to a subscription
    static private MSPhone getMSPhone(int subscription) {
        try {
            return mMSPhones[subscription];
        } catch (IndexOutOfBoundsException e) {
            Log.e(LOG_TAG,"subscripton Index out of bounds "+e);
            return null;
        }
    }

    // gets the Default Phone
    Phone getDefaultPhone() {
        return getPhone(getDefaultSubscription());
    }

    // Gets the Phone correspoding to a subscription
    // Access this method using MSimPhoneGlobals.getInstance().getPhone(sub);
    Phone getPhone(int subscription) {
        MSPhone msPhone= getMSPhone(subscription);
        if (msPhone != null) {
            return msPhone.mPhone;
        } else {
            Log.w(LOG_TAG, "msPhone object is null returning default phone");
            return phone;
        }
    }

    /**
      * Get the subscription that has service
      * Following are the conditions applicable when deciding the subscription for dial
      * 1. Place E911 call on a sub whichever is IN_SERVICE/Limited Service(sub need not be
      *    user preferred voice sub)
      * 2. If both subs are activated and out of service(i.e. other than limited/in service)
      *    place call on voice pref sub.
      * 3. If both subs are not activated(i.e NO SIM/PIN/PUK lock state) then choose
      *    the first sub by default for placing E911 call.
      */
    @Override
    public int getVoiceSubscriptionInService() {
        int voiceSub = getVoiceSubscription();
        //Emergency Call should always go on 1st sub .i.e.0
        //when both the subscriptions are out of service
        int sub = -1;
        MSimTelephonyManager tm = MSimTelephonyManager.getDefault();
        int count = tm.getPhoneCount();
        SubscriptionManager subManager = SubscriptionManager.getInstance();

        for (int i = 0; i < count; i++) {
            Phone phone = getPhone(i);
            int ss = phone.getServiceState().getState();
            if ((ss == ServiceState.STATE_IN_SERVICE)
                    || (phone.getServiceState().isEmergencyOnly())) {
                sub = i;
                if (sub == voiceSub) break;
            }
        }
        if (DBG) Log.d(LOG_TAG, "Voice sub in service = "+ sub);

        if (sub == -1) {
            for (int i = 0; i < count; i++) {
                if (tm.getSimState(i) == TelephonyManager.SIM_STATE_READY) {
                    sub = i;
                    if (sub == voiceSub) break;
                }
            }
            if (sub == -1)
                sub = 0;
        }
        Log.d(LOG_TAG, "Voice sub in service="+ sub +" preferred sub=" + voiceSub);

        return sub;
    }

    CdmaPhoneCallState getCdmaPhoneCallState (int subscription) {
        MSPhone msPhone = getMSPhone(subscription);
        if (msPhone == null) {
            return null;
        }
        return msPhone.mCdmaPhoneCallState;
    }

    //Sets the default phoneApp variables
    void setDefaultPhone(int subscription){
        //When default phone dynamically changes need to handle
        MSPhone msPhone = getMSPhone(subscription);
        phone = msPhone.mPhone;
        mLastPhoneState = msPhone.mLastPhoneState;
        updatePhoneAppCdmaVariables(subscription);
        mDefaultSubscription = subscription;
    }
    /*
     * Gets the default subscription
     */
    @Override
    public int getDefaultSubscription() {
        return MSimPhoneFactory.getDefaultSubscription();
    }

    /*
     * Gets User preferred Voice subscription setting
     */
    @Override
    public int getVoiceSubscription() {
        return MSimPhoneFactory.getVoiceSubscription();
    }

    /*
     * Gets User preferred Data subscription setting
     */
    @Override
    public int getDataSubscription() {
        return MSimPhoneFactory.getDataSubscription();
    }

    /*
     * Gets User preferred SMS subscription setting
     */
    public int getSMSSubscription() {
        return MSimPhoneFactory.getSMSSubscription();
    }
}
