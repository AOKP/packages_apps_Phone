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

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.CallLog.Calls;
import android.provider.Settings;
import android.telephony.MSimTelephonyManager;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyCapabilities;

import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;

/**
 * NotificationManager-related utility code for the Phone app.
 *
 * This is a singleton object which acts as the interface to the
 * framework's NotificationManager, and is used to display status bar
 * icons and control other status bar-related behavior.
 *
 * @see PhoneApp.notificationMgr
 */
public class MSimNotificationMgr extends NotificationMgr {
    private static final String LOG_TAG = "MSimNotificationMgr";

    static final int VOICEMAIL_NOTIFICATION_SUB2 = 20;
    static final int CALL_FORWARD_NOTIFICATION_SUB2 = 21;
    static final int CALL_FORWARD_XDIVERT = 22;
    static final int VOICEMAIL_NOTIFICATION_SUB3 = 23;
    static final int CALL_FORWARD_NOTIFICATION_SUB3 = 24;

    /**
     * Private constructor (this is a singleton).
     * @see init()
     */
    private MSimNotificationMgr(PhoneGlobals app) {
        super(app);
    }

    /**
     * Initialize the singleton NotificationMgr instance.
     *
     * This is only done once, at startup, from PhoneApp.onCreate().
     * From then on, the NotificationMgr instance is available via the
     * PhoneApp's public "notificationMgr" field, which is why there's no
     * getInstance() method here.
     */
    /* package */ static NotificationMgr init(PhoneGlobals app) {
        synchronized (MSimNotificationMgr.class) {
            if (sInstance == null) {
                sInstance = new MSimNotificationMgr(app);
                // Update the notifications that need to be touched at startup.
                sInstance.updateNotificationsAtStartup();
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    /**
     * Updates the message waiting indicator (voicemail) notification.
     *
     * @param visible true if there are messages waiting
     */
    /* package */
    void updateMwi(boolean visible, Phone phone) {
        int subscription = phone.getSubscription();
        if (DBG) log("updateMwi(): " + visible + " Subscription: "
                + subscription);
        int[] iconId = {R.drawable.stat_notify_voicemail_sub1,
                R.drawable.stat_notify_voicemail_sub2, R.drawable.stat_notify_voicemail_sub3};
        int resId = iconId[subscription];
        if (visible) {
            // This Notification can get a lot fancier once we have more
            // information about the current voicemail messages.
            // (For example, the current voicemail system can't tell
            // us the caller-id or timestamp of a message, or tell us the
            // message count.)

            // But for now, the UI is ultra-simple: if the MWI indication
            // is supposed to be visible, just show a single generic
            // notification.

            String notificationTitle = mContext.getString(R.string.notification_voicemail_title);
            String vmNumber = phone.getVoiceMailNumber();
            if (DBG) log("- got vm number: '" + vmNumber + "'");

            // Watch out: vmNumber may be null, for two possible reasons:
            //
            //   (1) This phone really has no voicemail number
            //
            //   (2) This phone *does* have a voicemail number, but
            //       the SIM isn't ready yet.
            //
                                       //
            // Case (2) *does* happen in practice if you have voicemail
            // messages when the device first boots: we get an MWI
            // notification as soon as we register on the network, but the
            // SIM hasn't finished loading yet.
            //
            // So handle case (2) by retrying the lookup after a short
            // delay.

            if ((vmNumber == null) && !phone.getIccRecordsLoaded()) {
                if (DBG) log("- Null vm number: SIM records not loaded (yet)...");

                // TODO: rather than retrying after an arbitrary delay, it
                // would be cleaner to instead just wait for a
                // SIM_RECORDS_LOADED notification.
                // (Unfortunately right now there's no convenient way to
                // get that notification in phone app code.  We'd first
                // want to add a call like registerForSimRecordsLoaded()
                // to Phone.java and GSMPhone.java, and *then* we could
                // listen for that in the CallNotifier class.)

                // Limit the number of retries (in case the SIM is broken
                // or missing and can *never* load successfully.)
                if (mVmNumberRetriesRemaining-- > 0) {
                    if (DBG) log("  - Retrying in " + VM_NUMBER_RETRY_DELAY_MILLIS + " msec...");
                    ((MSimCallNotifier)mApp.notifier).sendMwiChangedDelayed(
                            VM_NUMBER_RETRY_DELAY_MILLIS, phone);
                    return;
                } else {
                    Log.w(LOG_TAG, "NotificationMgr.updateMwi: getVoiceMailNumber() failed after "
                          + MAX_VM_NUMBER_RETRIES + " retries; giving up.");
                    // ...and continue with vmNumber==null, just as if the
                    // phone had no VM number set up in the first place.
                }
            }

            if (TelephonyCapabilities.supportsVoiceMessageCount(phone)) {
                int vmCount = phone.getVoiceMessageCount();
                String titleFormat = mContext.getString(
                        R.string.notification_voicemail_title_count);
                notificationTitle = String.format(titleFormat, vmCount);
            }

            String notificationText;
            if (TextUtils.isEmpty(vmNumber)) {
                notificationText = mContext.getString(
                        R.string.notification_voicemail_no_vm_number);
            } else {
                notificationText = String.format(
                        mContext.getString(R.string.notification_voicemail_text_format),
                        PhoneNumberUtils.formatNumber(vmNumber));
            }

            Intent intent = new Intent(Intent.ACTION_CALL,
                    Uri.fromParts(Constants.SCHEME_VOICEMAIL, "", null));
            PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, intent, 0);

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
            Uri ringtoneUri;

            String uriString = prefs.getString(
                    CallFeaturesSetting.BUTTON_VOICEMAIL_NOTIFICATION_RINGTONE_KEY, null);
            if (!TextUtils.isEmpty(uriString)) {
                ringtoneUri = Uri.parse(uriString);
            } else {
                ringtoneUri = Settings.System.DEFAULT_NOTIFICATION_URI;
            }

            Notification.Builder builder = new Notification.Builder(mContext);
            builder.setSmallIcon(resId)
                    .setWhen(System.currentTimeMillis())
                    .setContentTitle(notificationTitle)
                    .setContentText(notificationText)
                    .setContentIntent(pendingIntent)
                    .setSound(ringtoneUri);
            Notification notification = builder.getNotification();

            String vibrateWhen = prefs.getString(
                    CallFeaturesSetting.BUTTON_VOICEMAIL_NOTIFICATION_VIBRATE_WHEN_KEY, "never");
            boolean vibrateAlways = vibrateWhen.equals("always");
            boolean vibrateSilent = vibrateWhen.equals("silent");
            AudioManager audioManager =
                    (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            boolean nowSilent = audioManager.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE;
            if (vibrateAlways || (vibrateSilent && nowSilent)) {
                notification.defaults |= Notification.DEFAULT_VIBRATE;
            }

            notification.flags |= Notification.FLAG_NO_CLEAR;
            configureLedNotification(notification);
            mNotificationManager.notify(VOICEMAIL_NOTIFICATION, notification);
        } else {
            mNotificationManager.cancel(VOICEMAIL_NOTIFICATION);
        }
    }

    /**
     * Updates the message call forwarding indicator notification.
     *
     * @param visible true if there are messages waiting
     */
    /* package */ void updateCfi(boolean visible, int subscription) {
        if (DBG) log("updateCfi(): " + visible + "Sub: " + subscription);
        int [] callfwdIcon = {R.drawable.stat_sys_phone_call_forward_sub1,
                R.drawable.stat_sys_phone_call_forward_sub2,
                R.drawable.stat_sys_phone_call_forward_sub3};

        int notificationId = CALL_FORWARD_NOTIFICATION;
        switch (subscription) {
            case 0:
                notificationId =  CALL_FORWARD_NOTIFICATION;
                break;
            case 1:
                notificationId =  CALL_FORWARD_NOTIFICATION_SUB2;
                break;
            case 2:
                notificationId = CALL_FORWARD_NOTIFICATION_SUB3;
                break;
            default:
                //subscription should always be a vaild value and case
                //need to add in future for multiSIM (>3S) architecture, (if any).
                //Here, this default case should not hit in any of multiSIM scenario.
                Log.e(LOG_TAG, "updateCfi: This should not happen, subscription = "+subscription);
                return;
        }

        if (visible) {
            // If Unconditional Call Forwarding (forward all calls) for VOICE
            // is enabled, just show a notification.  We'll default to expanded
            // view for now, so the there is less confusion about the icon.  If
            // it is deemed too weird to have CF indications as expanded views,
            // then we'll flip the flag back.

            // TODO: We may want to take a look to see if the notification can
            // display the target to forward calls to.  This will require some
            // effort though, since there are multiple layers of messages that
            // will need to propagate that information.

            int resId = callfwdIcon[subscription];
            Notification notification;
            final boolean showExpandedNotification = true;
            if (showExpandedNotification) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setClassName("com.android.phone",
                        "com.android.phone.MSimCallFeaturesSetting");

                notification = new Notification(
                        resId,  // icon
                        null, // tickerText
                        0); // The "timestamp" of this notification is meaningless;
                            // we only care about whether CFI is currently on or not.
                notification.setLatestEventInfo(
                        mContext, // context
                        mContext.getString(R.string.labelCF), // expandedTitle
                        mContext.getString(R.string.sum_cfu_enabled_indicator), // expandedText
                        PendingIntent.getActivity(mContext, 0, intent, 0)); // contentIntent
            } else {
                notification = new Notification(
                        resId,  // icon
                        null,  // tickerText
                        System.currentTimeMillis()  // when
                        );
            }

            notification.flags |= Notification.FLAG_ONGOING_EVENT;  // also implies FLAG_NO_CLEAR

            mNotificationManager.notify(
                    notificationId,
                    notification);
        } else {
            mNotificationManager.cancel(notificationId);
        }
    }

    @Override
    protected void updateNotificationsAtStartup() {
        if (DBG) log("updateNotificationsAtStartup()...");

        // instantiate query handler
        mQueryHandler = new QueryHandler(mContext.getContentResolver());

        // setup query spec, look for all Missed calls that are new.
        StringBuilder where = new StringBuilder("type=");
        where.append(Calls.MISSED_TYPE);
        where.append(" AND new=1");

        // start the query
        if (DBG) log("- start call log query...");
        mQueryHandler.startQuery(CALL_LOG_TOKEN, null, Calls.CONTENT_URI,  CALL_LOG_PROJECTION,
                where.toString(), null, Calls.DEFAULT_SORT_ORDER);

        // Update (or cancel) the in-call notification
        if (DBG) log("- updating in-call notification at startup...");

        for (int i = 0; i < MSimTelephonyManager.getDefault().getPhoneCount(); i++) {
            updateInCallNotification(i, false);
        }

        // Depend on android.app.StatusBarManager to be set to
        // disable(DISABLE_NONE) upon startup.  This will be the
        // case even if the phone app crashes.
    }

    public void updateNotificationAndLaunchIncomingCallUi(int subscription) {
        // Set allowFullScreenIntent=true to indicate that we *should*
        // launch the incoming call UI if necessary.
        updateInCallNotification(subscription, true);
    }

    @Override
    public void updateInCallNotification() {
        updateInCallNotification(PhoneUtils.getActiveSubscription(), false);
    }

    @Override
    protected void updateInCallNotification(boolean allowFullScreenIntent) {
        updateInCallNotification(PhoneUtils.getActiveSubscription(), false);
    }

    private void updateInCallNotification(int subscription) {
        // allowFullScreenIntent=false means *don't* allow the incoming
        // call UI to be launched.
        updateInCallNotification(subscription, false);
    }

    private void updateMuteNotification(int subscription) {
        // Suppress the status bar icon if the the InCallScreen is the
        // foreground activity, since the in-call UI already provides an
        // onscreen indication of the mute state.  (This reduces clutter
        // in the status bar.)
        if (mApp.isShowingCallScreen()) {
            cancelMute();
            return;
        }

        if ((mCM.getState(subscription) == PhoneConstants.State.OFFHOOK) && PhoneUtils.getMute()) {
            if (DBG) log("updateMuteNotification: MUTED");
            notifyMute();
        } else {
            if (DBG) log("updateMuteNotification: not muted (or not offhook)");
            cancelMute();
        }
    }

    private void updateInCallNotification(int subscription, boolean allowFullScreenIntent) {
        int resId;
        if (DBG) log("updateInCallNotification(allowFullScreenIntent = "
                     + allowFullScreenIntent + ")...");

        // Never display the "ongoing call" notification on
        // non-voice-capable devices, even if the phone is actually
        // offhook (like during a non-interactive OTASP call.)
        if (!PhoneGlobals.sVoiceCapable) {
            if (DBG) log("- non-voice-capable device; suppressing notification.");
            return;
        }

        // If the phone is idle, completely clean up all call-related
        // notifications.
        if (mCM.getState(subscription) == PhoneConstants.State.IDLE) {
            cancelInCall();
            cancelMute();
            cancelSpeakerphone();
            return;
        }

        final boolean hasRingingCall = mCM.hasActiveRingingCall(subscription);
        final boolean hasActiveCall = mCM.hasActiveFgCall(subscription);
        final boolean hasHoldingCall = mCM.hasActiveBgCall(subscription);
        if (DBG) {
            log("  - hasRingingCall = " + hasRingingCall);
            log("  - hasActiveCall = " + hasActiveCall);
            log("  - hasHoldingCall = " + hasHoldingCall);
        }

        // Suppress the in-call notification if the InCallScreen is the
        // foreground activity, since it's already obvious that you're on a
        // call.  (The status bar icon is needed only if you navigate *away*
        // from the in-call UI.)
        boolean suppressNotification = mApp.isShowingCallScreen();
        if (DBG) log("- suppressNotification: initial value: " + suppressNotification);

        // ...except for a couple of cases where we *never* suppress the
        // notification:
        //
        //   - If there's an incoming ringing call: always show the
        //     notification, since the in-call notification is what actually
        //     launches the incoming call UI in the first place (see
        //     notification.fullScreenIntent below.)  This makes sure that we'll
        //     correctly handle the case where a new incoming call comes in but
        //     the InCallScreen is already in the foreground.
        if (hasRingingCall) suppressNotification = false;

        //   - If "voice privacy" mode is active: always show the notification,
        //     since that's the only "voice privacy" indication we have.
        boolean enhancedVoicePrivacy = mApp.notifier.getVoicePrivacyState();
        if (DBG) log("updateInCallNotification: enhancedVoicePrivacy = " + enhancedVoicePrivacy);
        if (enhancedVoicePrivacy) suppressNotification = false;

        if (suppressNotification) {
            if (DBG) log("- suppressNotification = true; reducing clutter in status bar...");
            cancelInCall();
            // Suppress the mute and speaker status bar icons too
            // (also to reduce clutter in the status bar.)
            cancelSpeakerphone();
            cancelMute();
            return;
        }

        // Display the appropriate icon in the status bar,
        // based on the current phone and/or bluetooth state.

        if (hasRingingCall) {
            // There's an incoming ringing call.
            resId = R.drawable.stat_sys_phone_call;
        } else if (!hasActiveCall && hasHoldingCall) {
            // There's only one call, and it's on hold.
            if (enhancedVoicePrivacy) {
                resId = R.drawable.stat_sys_vp_phone_call_on_hold;
            } else {
                resId = R.drawable.stat_sys_phone_call_on_hold;
            }
        } else {
            if (enhancedVoicePrivacy) {
                resId = R.drawable.stat_sys_vp_phone_call;
            } else {
                resId = R.drawable.stat_sys_phone_call;
            }
        }

        // Note we can't just bail out now if (resId == mInCallResId),
        // since even if the status icon hasn't changed, some *other*
        // notification-related info may be different from the last time
        // we were here (like the caller-id info of the foreground call,
        // if the user swapped calls...)

        if (DBG) log("- Updating status bar icon: resId = " + resId);
        mInCallResId = resId;

        // Even if both lines are in use, we only show a single item in
        // the expanded Notifications UI.  It's labeled "Ongoing call"
        // (or "On hold" if there's only one call, and it's on hold.)
        // Also, we don't have room to display caller-id info from two
        // different calls.  So if both lines are in use, display info
        // from the foreground call.  And if there's a ringing call,
        // display that regardless of the state of the other calls.

        Call currentCall;
        if (hasRingingCall) {
            currentCall = mCM.getFirstActiveRingingCall(subscription);
        } else if (hasActiveCall) {
            currentCall = mCM.getActiveFgCall(subscription);
        } else {
            currentCall = mCM.getFirstActiveBgCall(subscription);
        }
        Connection currentConn = currentCall.getEarliestConnection();

        final Notification.Builder builder = new Notification.Builder(mContext);
        builder.setSmallIcon(mInCallResId).setOngoing(true);

        // PendingIntent that can be used to launch the InCallScreen.  The
        // system fires off this intent if the user pulls down the windowshade
        // and clicks the notification's expanded view.  It's also used to
        // launch the InCallScreen immediately when when there's an incoming
        // call (see the "fullScreenIntent" field below).
        PendingIntent inCallPendingIntent =
                PendingIntent.getActivity(mContext, 0,
                                          PhoneGlobals.getInstance().createInCallIntent(
                                          currentCall.getPhone().getSubscription()), 0);
        builder.setContentIntent(inCallPendingIntent);

        // Update icon on the left of the notification.
        // - If it is directly available from CallerInfo, we'll just use that.
        // - If it is not, use the same icon as in the status bar.
        CallerInfo callerInfo = null;
        if (currentConn != null) {
            Object o = currentConn.getUserData();
            if (o instanceof CallerInfo) {
                callerInfo = (CallerInfo) o;
            } else if (o instanceof PhoneUtils.CallerInfoToken) {
                callerInfo = ((PhoneUtils.CallerInfoToken) o).currentInfo;
            } else {
                Log.w(LOG_TAG, "CallerInfo isn't available while Call object is available.");
            }
        }
        boolean largeIconWasSet = false;
        if (callerInfo != null) {
            // In most cases, the user will see the notification after CallerInfo is already
            // available, so photo will be available from this block.
            if (callerInfo.isCachedPhotoCurrent) {
                // .. and in that case CallerInfo's cachedPhotoIcon should also be available.
                // If it happens not, then try using cachedPhoto, assuming Drawable coming from
                // ContactProvider will be BitmapDrawable.
                if (callerInfo.cachedPhotoIcon != null) {
                    builder.setLargeIcon(callerInfo.cachedPhotoIcon);
                    largeIconWasSet = true;
                } else if (callerInfo.cachedPhoto instanceof BitmapDrawable) {
                    if (DBG) log("- BitmapDrawable found for large icon");
                    Bitmap bitmap = ((BitmapDrawable) callerInfo.cachedPhoto).getBitmap();
                    builder.setLargeIcon(bitmap);
                    largeIconWasSet = true;
                } else {
                    if (DBG) {
                        log("- Failed to fetch icon from CallerInfo's cached photo."
                                + " (cachedPhotoIcon: " + callerInfo.cachedPhotoIcon
                                + ", cachedPhoto: " + callerInfo.cachedPhoto + ")."
                                + " Ignore it.");
                    }
                }
            }

            if (!largeIconWasSet && callerInfo.photoResource > 0) {
                if (DBG) {
                    log("- BitmapDrawable nor person Id not found for large icon."
                            + " Use photoResource: " + callerInfo.photoResource);
                }
                Drawable drawable =
                        mContext.getResources().getDrawable(callerInfo.photoResource);
                if (drawable instanceof BitmapDrawable) {
                    Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
                    builder.setLargeIcon(bitmap);
                    largeIconWasSet = true;
                } else {
                    if (DBG) {
                        log("- PhotoResource was found but it didn't return BitmapDrawable."
                                + " Ignore it");
                    }
                }
            }
        } else {
            if (DBG) log("- CallerInfo not found. Use the same icon as in the status bar.");
        }

        // Failed to fetch Bitmap.
        if (!largeIconWasSet && DBG) {
            log("- No useful Bitmap was found for the photo."
                    + " Use the same icon as in the status bar.");
        }

        // If the connection is valid, then build what we need for the
        // content text of notification, and start the chronometer.
        // Otherwise, don't bother and just stick with content title.
        if (currentConn != null) {
            if (DBG) log("- Updating context text and chronometer.");
            if (hasRingingCall) {
                // Incoming call is ringing.
                builder.setContentText(mContext.getString(R.string.notification_incoming_call));
                builder.setUsesChronometer(false);
            } else if (hasHoldingCall && !hasActiveCall) {
                // Only one call, and it's on hold.
                builder.setContentText(mContext.getString(R.string.notification_on_hold));
                builder.setUsesChronometer(false);
            } else {
                // We show the elapsed time of the current call using Chronometer.
                builder.setUsesChronometer(true);
                // Determine the "start time" of the current connection.
                //   We can't use currentConn.getConnectTime(), because (1) that's
                // in the currentTimeMillis() time base, and (2) it's zero when
                // the phone first goes off hook, since the getConnectTime counter
                // doesn't start until the DIALING -> ACTIVE transition.
                //   Instead we start with the current connection's duration,
                // and translate that into the elapsedRealtime() timebase.
                long callDurationMsec = currentConn.getDurationMillis();
                builder.setWhen(System.currentTimeMillis() - callDurationMsec);
                builder.setContentText(mContext.getString(R.string.notification_ongoing_call));
            }
        } else if (DBG) {
            Log.w(LOG_TAG, "updateInCallNotification: null connection, can't set exp view line 1.");
        }

        // display conference call string if this call is a conference
        // call, otherwise display the connection information.

        // Line 2 of the expanded view (smaller text).  This is usually a
        // contact name or phone number.
        String expandedViewLine2 = "";
        // TODO: it may not make sense for every point to make separate
        // checks for isConferenceCall, so we need to think about
        // possibly including this in startGetCallerInfo or some other
        // common point.
        if (PhoneUtils.isConferenceCall(currentCall)) {
            // if this is a conference call, just use that as the caller name.
            expandedViewLine2 = mContext.getString(R.string.card_title_conf_call);
        } else {
            // If necessary, start asynchronous query to do the caller-id lookup.
            PhoneUtils.CallerInfoToken cit =
                PhoneUtils.startGetCallerInfo(mContext, currentCall, this, this);
            expandedViewLine2 = PhoneUtils.getCompactNameFromCallerInfo(cit.currentInfo, mContext);
            // Note: For an incoming call, the very first time we get here we
            // won't have a contact name yet, since we only just started the
            // caller-id query.  So expandedViewLine2 will start off as a raw
            // phone number, but we'll update it very quickly when the query
            // completes (see onQueryComplete() below.)
        }
        if (DBG) log("- Updating expanded view: line 2 '" + /*expandedViewLine2*/ "xxxxxxx" + "'");
        builder.setContentTitle(expandedViewLine2);

        // TODO: We also need to *update* this notification in some cases,
        // like when a call ends on one line but the other is still in use
        // (ie. make sure the caller info here corresponds to the active
        // line), and maybe even when the user swaps calls (ie. if we only
        // show info here for the "current active call".)

        // Activate a couple of special Notification features if an
        // incoming call is ringing:
        if (hasRingingCall || hasActiveCall) {
            if (DBG) log("- Using hi-pri notification for ringing/active call!");

            // This is a high-priority event that should be shown even if the
            // status bar is hidden or if an immersive activity is running.
            builder.setPriority(Notification.PRIORITY_HIGH);

            // If an immersive activity is running, we have room for a single
            // line of text in the small notification popup window.
            // We use expandedViewLine2 for this (i.e. the name or number of
            // the incoming caller), since that's more relevant than
            // expandedViewLine1 (which is something generic like "Incoming
            // call".)
            builder.setTicker(expandedViewLine2);

            if (allowFullScreenIntent) {
                // Ok, we actually want to launch the incoming call
                // UI at this point (in addition to simply posting a notification
                // to the status bar).  Setting fullScreenIntent will cause
                // the InCallScreen to be launched immediately *unless* the
                // current foreground activity is marked as "immersive".
                if (DBG) log("- Setting fullScreenIntent: " + inCallPendingIntent);
                builder.setFullScreenIntent(inCallPendingIntent, true);
                // Ugly hack alert:
                //
                // The NotificationManager has the (undocumented) behavior
                // that it will *ignore* the fullScreenIntent field if you
                // post a new Notification that matches the ID of one that's
                // already active.  Unfortunately this is exactly what happens
                // when you get an incoming call-waiting call:  the
                // "ongoing call" notification is already visible, so the
                // InCallScreen won't get launched in this case!
                // (The result: if you bail out of the in-call UI while on a
                // call and then get a call-waiting call, the incoming call UI
                // won't come up automatically.)
                //
                // The workaround is to just notice this exact case (this is a
                // call-waiting call *and* the InCallScreen is not in the
                // foreground) and manually cancel the in-call notification
                // before (re)posting it.
                //
                // TODO: there should be a cleaner way of avoiding this
                // problem (see discussion in bug 3184149.)
                Call ringingCall = mCM.getFirstActiveRingingCall(subscription);
                if ((ringingCall.getState() == Call.State.WAITING) && !mApp.isShowingCallScreen()) {
                    Log.i(LOG_TAG, "updateInCallNotification: call-waiting! force relaunch...");
                    // Cancel the IN_CALL_NOTIFICATION immediately before
                    // (re)posting it; this seems to force the
                    // NotificationManager to launch the fullScreenIntent.
                    mNotificationManager.cancel(IN_CALL_NOTIFICATION);
                }
            }
        } else { // not ringing call
            // Make the notification prioritized over the other normal notifications.
            builder.setPriority(Notification.PRIORITY_HIGH);

            // TODO: use "if (DBG)" for this comment.
            log("Will show \"hang-up\" action in the ongoing active call Notification");
            // TODO: use better asset.
            builder.addAction(R.drawable.stat_sys_phone_call_end,
                    mContext.getText(R.string.notification_action_end_call),
                    PhoneGlobals.createHangUpOngoingCallPendingIntent(mContext));
        }

        Notification notification = builder.getNotification();
        if (DBG) log("Notifying IN_CALL_NOTIFICATION: " + notification);
        mNotificationManager.notify(IN_CALL_NOTIFICATION, notification);

        // Finally, refresh the mute and speakerphone notifications (since
        // some phone state changes can indirectly affect the mute and/or
        // speaker state).
        updateSpeakerNotification();
        updateMuteNotification(subscription);
    }

    @Override
    protected void updateSpeakerNotification() {
        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        boolean showNotification = false;
        boolean state = false;
        for (int i = 0; i < MSimTelephonyManager.getDefault().getPhoneCount(); i++) {
            state = (state || (mCM.getState(i) == PhoneConstants.State.OFFHOOK));
        }
        showNotification = state && audioManager.isSpeakerphoneOn();

        if (DBG) log(showNotification
                     ? "updateSpeakerNotification: speaker ON"
                     : "updateSpeakerNotification: speaker OFF (or not offhook)");

        updateSpeakerNotification(showNotification);
    }

    /**
     * Updates the XDivert indicator notification.
     *
     * @param visible true if XDivert is enabled.
     */
    /* package */ void updateXDivert(boolean visible) {
        Log.d(LOG_TAG, "updateXDivert: " + visible);
        if (visible) {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setClassName("com.android.phone",
                    "com.android.phone.MSimCallFeaturesSetting");
            int resId = R.drawable.stat_sys_phone_call_forward_xdivert;
            Notification notification = new Notification(
                    resId,  // icon
                    null, // tickerText
                    System.currentTimeMillis()
                    );
            notification.setLatestEventInfo(
                    mContext, // context
                    mContext.getString(R.string.xdivert_title), // expandedTitle
                    mContext.getString(R.string.sum_xdivert_enabled), // expandedText
                    PendingIntent.getActivity(mContext, 0, intent, 0)); // contentIntent

            mNotificationManager.notify(CALL_FORWARD_XDIVERT, notification);
        } else {
            mNotificationManager.cancel(CALL_FORWARD_XDIVERT);
        }
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
