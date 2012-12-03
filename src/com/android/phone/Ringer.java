/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.VibrationPattern;
import android.net.Uri;
import android.provider.Settings;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.SystemVibrator;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;

import java.util.Calendar;

import com.android.internal.telephony.Phone;
/**
 * Ringer manager for the Phone app.
 */
public class Ringer {
    private static final String LOG_TAG = "Ringer";
    private static final boolean DBG =
            (PhoneGlobals.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);

    private static final int PLAY_RING_ONCE = 1;
    private static final int STOP_RING = 3;
    private static final int INCREASE_RING_VOLUME = 4;

    private static final int VIBRATE_LENGTH = 1000; // ms
    private static final int PAUSE_LENGTH = 1000; // ms

    /** The singleton instance. */
    private static Ringer sInstance;

    // Uri for the ringtone.
    Uri mCustomRingtoneUri = Settings.System.DEFAULT_RINGTONE_URI;
    Uri mCustomVibrationUri = Settings.System.DEFAULT_VIBRATION_URI;

    Ringtone mRingtone;
    VibrationPattern mVibrationPattern;
    Vibrator mVibrator;
    AudioManager mAudioManager;
    IPowerManager mPowerManager;
    volatile boolean mContinueVibrating;
    VibratorThread mVibratorThread;
    Context mContext;
    private Worker mRingThread;
    private Handler mHandler;
    private Handler mRingHandler;
    private long mFirstRingEventTime = -1;
    private long mFirstRingStartTime = -1;
    private int mRingerVolumeSetting = -1;
    private int mRingIncreaseInterval;

    /**
     * Initialize the singleton Ringer instance.
     * This is only done once, at startup, from PhoneApp.onCreate().
     */
    /* package */ static Ringer init(Context context) {
        synchronized (Ringer.class) {
            if (sInstance == null) {
                sInstance = new Ringer(context);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    /** Private constructor; @see init() */
    private Ringer(Context context) {
        mContext = context;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mPowerManager = IPowerManager.Stub.asInterface(ServiceManager.getService(Context.POWER_SERVICE));
        // We don't rely on getSystemService(Context.VIBRATOR_SERVICE) to make sure this
        // vibrator object will be isolated from others.
        mVibrator = new SystemVibrator();
    }

    /**
     * After a radio technology change, e.g. from CDMA to GSM or vice versa,
     * the Context of the Ringer has to be updated. This is done by that function.
     *
     * @parameter Phone, the new active phone for the appropriate radio
     * technology
     */
    void updateRingerContextAfterRadioTechnologyChange(Phone phone) {
        if(DBG) Log.d(LOG_TAG, "updateRingerContextAfterRadioTechnologyChange...");
        mContext = phone.getContext();
    }

    /**
     * @return true if we're playing a ringtone and/or vibrating
     *     to indicate that there's an incoming call.
     *     ("Ringing" here is used in the general sense.  If you literally
     *     need to know if we're playing a ringtone or vibrating, use
     *     isRingtonePlaying() or isVibrating() instead.)
     *
     * @see isVibrating
     * @see isRingtonePlaying
     */
    boolean isRinging() {
        synchronized (this) {
            return (isRingtonePlaying() || isVibrating());
        }
    }

    /**
     * @return true if the ringtone is playing
     * @see isVibrating
     * @see isRinging
     */
    private boolean isRingtonePlaying() {
        synchronized (this) {
            return (mRingtone != null && mRingtone.isPlaying()) ||
                    (mRingHandler != null && mRingHandler.hasMessages(PLAY_RING_ONCE));
        }
    }

    /**
     * @return true if we're vibrating in response to an incoming call
     * @see isVibrating
     * @see isRinging
     */
    private boolean isVibrating() {
        synchronized (this) {
            return (mVibratorThread != null);
        }
    }

    /**
     * Starts the ringtone and/or vibrator
     */
    void ring() {
        if (DBG) log("ring()...");

        synchronized (this) {
            try {
                if (PhoneGlobals.getInstance().showBluetoothIndication()) {
                    mPowerManager.setAttentionLight(true, 0x000000ff);
                } else {
                    mPowerManager.setAttentionLight(true, 0x00ffffff);
                }
            } catch (RemoteException ex) {
                // the other end of this binder call is in the system process.
            }

            if (shouldVibrate() && mVibratorThread == null) {
                mVibrationPattern = new VibrationPattern(mCustomVibrationUri, mContext);
                if (mVibrationPattern.getPattern() == null) {
                    mVibrationPattern = VibrationPattern.getFallbackVibration(mContext);
                }
                mContinueVibrating = true;
                mVibratorThread = new VibratorThread();
                if (DBG) log("- starting vibrator...");
                mVibratorThread.start();
            }
            AudioManager audioManager =
                    (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            int ringerVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_RING);
            if (ringerVolume == 0 && mRingerVolumeSetting <= 0 || inQuietHours()) {
                if (DBG) log("skipping ring because volume is zero");
                return;
            }

            makeLooper();
            if (mFirstRingEventTime < 0) {
                ContentResolver cr = mContext.getContentResolver();
                boolean increasing = Settings.System.getInt(cr,
                        Settings.System.INCREASING_RING, 0) == 1;
                int minVolume = Settings.System.getInt(cr,
                        Settings.System.INCREASING_RING_MIN_VOLUME, 1);
                if (increasing && minVolume < ringerVolume) {
                    mRingIncreaseInterval = Settings.System.getInt(cr,
                            Settings.System.INCREASING_RING_INTERVAL, 0);
                    mRingerVolumeSetting = ringerVolume;
                    mAudioManager.setStreamVolume(AudioManager.STREAM_RING, minVolume, 0);
                    if (DBG) {
                        log("increasing ring is enabled, starting at " +
                                  minVolume + "/" + ringerVolume);
                    }
                    if (mRingIncreaseInterval > 0) {
                        mHandler.sendEmptyMessageDelayed(
                                  INCREASE_RING_VOLUME, mRingIncreaseInterval);
                    }
                } else {
                    mRingerVolumeSetting = -1;
                }
                mFirstRingEventTime = SystemClock.elapsedRealtime();
                mRingHandler.sendEmptyMessage(PLAY_RING_ONCE);
            } else {
                // For repeat rings, figure out by how much to delay
                // the ring so that it happens the correct amount of
                // time after the previous ring
                if (mFirstRingStartTime > 0) {
                    // Delay subsequent rings by the delta between event
                    // and play time of the first ring
                    if (DBG) {
                        log("delaying ring by " + (mFirstRingStartTime - mFirstRingEventTime));
                    }
                    if (mRingerVolumeSetting > 0 && mRingIncreaseInterval == 0) {
                        mHandler.sendEmptyMessage(INCREASE_RING_VOLUME);
                    }
                    mRingHandler.sendEmptyMessageDelayed(PLAY_RING_ONCE,
                            mFirstRingStartTime - mFirstRingEventTime);
                } else {
                    // We've gotten two ring events so far, but the ring
                    // still hasn't started. Reset the event time to the
                    // time of this event to maintain correct spacing.
                    mFirstRingEventTime = SystemClock.elapsedRealtime();
                }
            }
        }
    }

    boolean shouldVibrate() {
        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = audioManager.getRingerMode();
        if (CallFeaturesSetting.getVibrateWhenRinging(mContext)) {
            return ringerMode != AudioManager.RINGER_MODE_SILENT;
        } else {
            return ringerMode == AudioManager.RINGER_MODE_VIBRATE;
        }
    }

    /**
     * Stops the ringtone and/or vibrator if any of these are actually
     * ringing/vibrating.
     */
    void stopRing() {
        synchronized (this) {
            if (DBG) log("stopRing()...");

            try {
                mPowerManager.setAttentionLight(false, 0x00000000);
            } catch (RemoteException ex) {
                // the other end of this binder call is in the system process.
            }
            if (mHandler != null) {
                mHandler.removeCallbacksAndMessages(null);
                mHandler = null;
            }
            if (mRingerVolumeSetting >= 0) {
                if (DBG) log("- stopRing: resetting ring volume to " + mRingerVolumeSetting);
                mAudioManager.setStreamVolume(AudioManager.STREAM_RING, mRingerVolumeSetting, 0);
                mRingerVolumeSetting = -1;
            }
            if (mRingHandler != null) {
                mRingHandler.removeCallbacksAndMessages(null);
                Message msg = mRingHandler.obtainMessage(STOP_RING);
                msg.obj = mRingtone;
                mRingHandler.sendMessage(msg);
                PhoneUtils.setAudioMode();
                mRingThread = null;
                mRingHandler = null;
                mRingtone = null;
                mFirstRingEventTime = -1;
                mFirstRingStartTime = -1;
            } else {
                if (DBG) log("- stopRing: null mRingHandler!");
            }

            if (mVibratorThread != null) {
                if (DBG) log("- stopRing: cleaning up vibrator thread...");
                mVibrationPattern.stop();
                mContinueVibrating = false;
                mVibratorThread = null;
            }
            // Also immediately cancel any vibration in progress.
            mVibrator.cancel();
        }
    }

    private class VibratorThread extends Thread {
        public void run() {
            while (mContinueVibrating) {
                mVibrationPattern.play();
                SystemClock.sleep(mVibrationPattern.getLength() + PAUSE_LENGTH);
            }
        }
    }
    private class Worker implements Runnable {
        private final Object mLock = new Object();
        private Looper mLooper;

        Worker(String name) {
            Thread t = new Thread(null, this, name);
            t.start();
            synchronized (mLock) {
                while (mLooper == null) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException ex) {
                    }
                }
            }
        }

        public Looper getLooper() {
            return mLooper;
        }

        public void run() {
            synchronized (mLock) {
                Looper.prepare();
                mLooper = Looper.myLooper();
                mLock.notifyAll();
            }
            Looper.loop();
        }

        public void quit() {
            mLooper.quit();
        }
    }

    /**
     * Sets the ringtone uri in preparation for ringtone creation
     * in makeLooper().  This uri is defaulted to the phone-wide
     * default ringtone.
     */
    void setCustomRingtoneUri (Uri uri) {
        if (uri != null) {
            mCustomRingtoneUri = uri;
        }
    }

    /**
     * Sets the vibration uri in preparation for vibrating.
     * This uri is defaulted to the phone-wide default vibration.
     */
    void setCustomVibrationUri (Uri uri) {
        if (uri != null) {
            mCustomVibrationUri = uri;
        }
    }

    private void makeLooper() {
        if (mHandler == null) {
            mHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case INCREASE_RING_VOLUME:
                            int ringerVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_RING);
                            if (mRingerVolumeSetting > 0 && ringerVolume < mRingerVolumeSetting) {
                                ringerVolume++;
                                if (DBG) {
                                    log("increasing ring volume to " +
                                            ringerVolume + "/" + mRingerVolumeSetting);
                                }
                                mAudioManager.setStreamVolume(AudioManager.STREAM_RING, ringerVolume, 0);
                                if (mRingIncreaseInterval > 0) {
                                    sendEmptyMessageDelayed(INCREASE_RING_VOLUME, mRingIncreaseInterval);
                                }
                            }
                            break;
                    }
                }
            };
        }
        if (mRingThread == null) {
            mRingThread = new Worker("ringer");
            mRingHandler = new Handler(mRingThread.getLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    Ringtone r = null;
                    switch (msg.what) {
                        case PLAY_RING_ONCE:
                            if (DBG) log("mRingHandler: PLAY_RING_ONCE...");
                            if (mRingtone == null && !hasMessages(STOP_RING)) {
                                // create the ringtone with the uri
                                if (DBG) log("creating ringtone: " + mCustomRingtoneUri);
                                r = RingtoneManager.getRingtone(mContext, mCustomRingtoneUri);
                                synchronized (Ringer.this) {
                                    if (!hasMessages(STOP_RING)) {
                                        mRingtone = r;
                                    }
                                }
                            }
                            r = mRingtone;
                            if (r != null && !hasMessages(STOP_RING) && !r.isPlaying()) {
                                PhoneUtils.setAudioMode();
                                r.play();
                                synchronized (Ringer.this) {
                                    if (mFirstRingStartTime < 0) {
                                        mFirstRingStartTime = SystemClock.elapsedRealtime();
                                    }
                                }
                            }
                            break;
                        case STOP_RING:
                            if (DBG) log("mRingHandler: STOP_RING...");
                            r = (Ringtone) msg.obj;
                            if (r != null) {
                                r.stop();
                            } else {
                                if (DBG) log("- STOP_RING with null ringtone!  msg = " + msg);
                            }
                            getLooper().quit();
                            break;
                    }
                }
            };
        }
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }

    private boolean inQuietHours() {
        boolean quietHoursEnabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.QUIET_HOURS_ENABLED, 0) != 0;
        int quietHoursStart = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.QUIET_HOURS_START, 0);
        int quietHoursEnd = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.QUIET_HOURS_END, 0);
        boolean quietHoursRinger = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.QUIET_HOURS_RINGER, 0) != 0;
        if (quietHoursEnabled && quietHoursRinger && (quietHoursStart != quietHoursEnd)) {
            // Get the date in "quiet hours" format.
            Calendar calendar = Calendar.getInstance();
            int minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
            if (quietHoursEnd < quietHoursStart) {
                // Starts at night, ends in the morning.
                return (minutes > quietHoursStart) || (minutes < quietHoursEnd);
            } else {
                return (minutes > quietHoursStart) && (minutes < quietHoursEnd);
            }
        }
        return false;
    }
}
