/*
 * Copyright (c) 2011-2013 The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
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

import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.util.Log;
import android.view.ViewStub;
import android.view.WindowManager;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyCapabilities;
import com.android.phone.InCallUiState.InCallScreenMode;
import com.android.phone.OtaUtils.CdmaOtaScreenState;

import java.util.List;

/**
 * Phone app "multi sim in call" screen.
 */
public class MSimInCallScreen extends InCallScreen {
    private static final String LOG_TAG = "MSimInCallScreen";
    private static final boolean DBG =
            (MSimPhoneGlobals.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (MSimPhoneGlobals.DBG_LEVEL >= 2);

    private static final int PHONE_ACTIVE_SUBSCRIPTION_CHANGE = 131;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (mIsDestroyed) {
                if (DBG) log("Handler: ignoring message " + msg + "; we're destroyed!");
                return;
            }
            if (!mIsForegroundActivity) {
                if (DBG) log("Handler: handling message " + msg + " while not in foreground");
                // Continue anyway; some of the messages below *want* to
                // be handled even if we're not the foreground activity
                // (like DELAYED_CLEANUP_AFTER_DISCONNECT), and they all
                // should at least be safe to handle if we're not in the
                // foreground...
            }

            switch (msg.what) {
                case SUPP_SERVICE_FAILED:
                    onSuppServiceFailed((AsyncResult) msg.obj);
                    break;

                case PHONE_STATE_CHANGED:
                    onPhoneStateChanged((AsyncResult) msg.obj);
                    break;

                case PHONE_DISCONNECT:
                    onDisconnect((AsyncResult) msg.obj);
                    break;

                // TODO: sort out MMI code (probably we should remove this method entirely).
                // See also MMI handling code in onResume()
                // case PhoneApp.MMI_INITIATE:
                // onMMIInitiate((AsyncResult) msg.obj);
                //    break;

                case PhoneGlobals.MMI_CANCEL:
                    onMMICancel((Phone)msg.obj);
                    break;

                // handle the mmi complete message.
                // since the message display class has been replaced with
                // a system dialog in PhoneUtils.displayMMIComplete(), we
                // should finish the activity here to close the window.
                case PhoneGlobals.MMI_COMPLETE:
                    onMMIComplete((MmiCode) ((AsyncResult) msg.obj).result);
                    break;

                case POST_ON_DIAL_CHARS:
                    handlePostOnDialChars((AsyncResult) msg.obj, (char) msg.arg1);
                    break;

                case DELAYED_CLEANUP_AFTER_DISCONNECT:
                    delayedCleanupAfterDisconnect((Phone)msg.obj);
                    break;

                case PHONE_CDMA_CALL_WAITING:
                    AsyncResult ar = (AsyncResult) msg.obj;
                    int subscription = (Integer) ar.userObj;
                    if (DBG) log("Received PHONE_CDMA_CALL_WAITING event sub = " + subscription);
                    Connection cn = mCM.getFirstActiveRingingCall(subscription).
                            getLatestConnection();

                    // Only proceed if we get a valid connection object
                    if (cn != null) {
                        // Finally update screen with Call waiting info and request
                        // screen to wake up
                        updateScreen();
                        mApp.updateWakeState();
                    }
                    break;

                case PHONE_INCOMING_RING:
                    onIncomingRing();
                    break;

                case PHONE_NEW_RINGING_CONNECTION:
                    onNewRingingConnection();
                    break;

                case PHONE_ACTIVE_SUBSCRIPTION_CHANGE:
                    if (DBG) log("PHONE_ACTIVE_SUBSCRIPTION_CHANGE...");
                    AsyncResult r = (AsyncResult) msg.obj;
                    log(" Change in subscription " + (Integer) r.result);
                    // SUB switched, update the screen to display latest
                    // sub info
                    updateScreen();
                    break;

                default:
                    Log.wtf(LOG_TAG, "mHandler: unexpected message: " + msg);
                    break;
            }
        }
    };

    //TODO: optimize this function & reuse base function
    @Override
    protected void onDisconnect(AsyncResult r) {
        Connection c = (Connection) r.result;
        int subscription = c.getCall().getPhone().getSubscription();

        Connection.DisconnectCause cause = c.getDisconnectCause();
        log("onDisconnect: connection '" + c + "', cause = " + cause
            + ", showing screen: " + mApp.isShowingCallScreen());
        Phone phone = c.getCall().getPhone();
        boolean currentlyIdle = !phoneIsInUse();
        int autoretrySetting = AUTO_RETRY_OFF;
        boolean phoneIsCdma = (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA);
        if (phoneIsCdma) {
            // Get the Auto-retry setting only if Phone State is IDLE,
            // else let it stay as AUTO_RETRY_OFF
            if (currentlyIdle) {
                autoretrySetting = android.provider.Settings.Global.getInt(mPhone.getContext().
                        getContentResolver(), android.provider.Settings.Global.CALL_AUTO_RETRY, 0);
            }
        }

        // for OTA Call, only if in OTA NORMAL mode, handle OTA END scenario
        if ((mApp.inCallUiState.inCallScreenMode == InCallScreenMode.OTA_NORMAL)
                && ((mApp.cdmaOtaProvisionData != null)
                && (!mApp.cdmaOtaProvisionData.inOtaSpcState))) {
            setInCallScreenMode(InCallScreenMode.OTA_ENDED);
            updateScreen();
            return;
        } else if ((mApp.inCallUiState.inCallScreenMode == InCallScreenMode.OTA_ENDED)
                   || ((mApp.cdmaOtaProvisionData != null)
                       && mApp.cdmaOtaProvisionData.inOtaSpcState)) {
           if (DBG) log("onDisconnect: OTA Call end already handled");
           return;
        }

        // Any time a call disconnects, clear out the "history" of DTMF
        // digits you typed (to make sure it doesn't persist from one call
        // to the next.)
        mDialer.clearDigits();


        // Under certain call disconnected states, we want to alert the user
        // with a dialog instead of going through the normal disconnect
        // routine.
        if (cause == Connection.DisconnectCause.CALL_BARRED) {
            showGenericErrorDialog(R.string.callFailed_cb_enabled, false);
            return;
        } else if (cause == Connection.DisconnectCause.FDN_BLOCKED) {
            showGenericErrorDialog(R.string.callFailed_fdn_only, false);
            return;
        } else if (cause == Connection.DisconnectCause.CS_RESTRICTED) {
            showGenericErrorDialog(R.string.callFailed_dsac_restricted, false);
            return;
        } else if (cause == Connection.DisconnectCause.CS_RESTRICTED_EMERGENCY) {
            showGenericErrorDialog(R.string.callFailed_dsac_restricted_emergency, false);
            return;
        } else if (cause == Connection.DisconnectCause.CS_RESTRICTED_NORMAL) {
            showGenericErrorDialog(R.string.callFailed_dsac_restricted_normal, false);
            return;
        }

        if (phoneIsCdma) {
            Call.State callState = mApp.notifier.getPreviousCdmaCallState();
            if ((callState == Call.State.ACTIVE)
                    && (cause != Connection.DisconnectCause.INCOMING_MISSED)
                    && (cause != Connection.DisconnectCause.NORMAL)
                    && (cause != Connection.DisconnectCause.LOCAL)
                    && (cause != Connection.DisconnectCause.INCOMING_REJECTED)) {
                showCallLostDialog();
            } else if ((callState == Call.State.DIALING || callState == Call.State.ALERTING)
                        && (cause != Connection.DisconnectCause.INCOMING_MISSED)
                        && (cause != Connection.DisconnectCause.NORMAL)
                        && (cause != Connection.DisconnectCause.LOCAL)
                        && (cause != Connection.DisconnectCause.INCOMING_REJECTED)) {

                if (mApp.inCallUiState.needToShowCallLostDialog) {
                    // Show the dialog now since the call that just failed was a retry.
                    showCallLostDialog();
                    mApp.inCallUiState.needToShowCallLostDialog = false;
                } else {
                    if (autoretrySetting == AUTO_RETRY_OFF) {
                        // Show the dialog for failed call if Auto Retry is OFF in Settings.
                        showCallLostDialog();
                        mApp.inCallUiState.needToShowCallLostDialog = false;
                    } else {
                        // Set the needToShowCallLostDialog flag now, so we'll know to show
                        // the dialog if *this* call fails.
                        mApp.inCallUiState.needToShowCallLostDialog = true;
                    }
                }
            }
        }

        // Explicitly clean up up any DISCONNECTED connections
        // in a conference call.
        // [Background: Even after a connection gets disconnected, its
        // Connection object still stays around for a few seconds, in the
        // DISCONNECTED state.  With regular calls, this state drives the
        // "call ended" UI.  But when a single person disconnects from a
        // conference call there's no "call ended" state at all; in that
        // case we blow away any DISCONNECTED connections right now to make sure
        // the UI updates instantly to reflect the current state.]
        final Call call = c.getCall();
        if (call != null) {
            // We only care about situation of a single caller
            // disconnecting from a conference call.  In that case, the
            // call will have more than one Connection (including the one
            // that just disconnected, which will be in the DISCONNECTED
            // state) *and* at least one ACTIVE connection.  (If the Call
            // has *no* ACTIVE connections, that means that the entire
            // conference call just ended, so we *do* want to show the
            // "Call ended" state.)
            List<Connection> connections = call.getConnections();
            if (connections != null && connections.size() > 1) {
                for (Connection conn : connections) {
                    if (conn.getState() == Call.State.ACTIVE) {
                        // This call still has at least one ACTIVE connection!
                        // So blow away any DISCONNECTED connections
                        // (including, presumably, the one that just
                        // disconnected from this conference call.)

                        // We also force the wake state to refresh, just in
                        // case the disconnected connections are removed
                        // before the phone state change.
                        if (VDBG) log("- Still-active conf call; clearing DISCONNECTED...");
                        mApp.updateWakeState();
                        mCM.clearDisconnected();  // This happens synchronously.
                        break;
                    }
                }
            }
        }

        // Note: see CallNotifier.onDisconnect() for some other behavior
        // that might be triggered by a disconnect event, like playing the
        // busy/congestion tone.

        // Stash away some info about the call that just disconnected.
        // (This might affect what happens after we exit the InCallScreen; see
        // delayedCleanupAfterDisconnect().)
        // TODO: rather than stashing this away now and then reading it in
        // delayedCleanupAfterDisconnect(), it would be cleaner to just pass
        // this as an argument to delayedCleanupAfterDisconnect() (if we call
        // it directly) or else pass it as a Message argument when we post the
        // DELAYED_CLEANUP_AFTER_DISCONNECT message.
        mLastDisconnectCause = cause;

        // We bail out immediately (and *don't* display the "call ended"
        // state at all) if this was an incoming call.
        boolean bailOutImmediately =
                ((cause == Connection.DisconnectCause.INCOMING_MISSED)
                 || (cause == Connection.DisconnectCause.INCOMING_REJECTED))
                && currentlyIdle;

        boolean showingQuickResponseDialog =
                mRespondViaSmsManager != null && mRespondViaSmsManager.isShowingPopup();

        // Note: we also do some special handling for the case when a call
        // disconnects with cause==OUT_OF_SERVICE while making an
        // emergency call from airplane mode.  That's handled by
        // EmergencyCallHelper.onDisconnect().

        if (bailOutImmediately && showingQuickResponseDialog) {
            if (DBG) log("- onDisconnect: Respond-via-SMS dialog is still being displayed...");

            // Do *not* exit the in-call UI yet!
            // If the call was an incoming call that was missed *and* the user is using
            // quick response screen, we keep showing the screen for a moment, assuming the
            // user wants to reply the call anyway.
            //
            // For this case, we will exit the screen when:
            // - the message is sent (RespondViaSmsManager)
            // - the message is canceled (RespondViaSmsManager), or
            // - when the whole in-call UI becomes background (onPause())
        } else if (bailOutImmediately) {
            if (DBG) log("- onDisconnect: bailOutImmediately...");

            // Exit the in-call UI!
            // (This is basically the same "delayed cleanup" we do below,
            // just with zero delay.  Since the Phone is currently idle,
            // this call is guaranteed to immediately finish this activity.)
            delayedCleanupAfterDisconnect(c.getCall().getPhone());
        } else {
            if (DBG) log("- onDisconnect: delayed bailout...");
            // Stay on the in-call screen for now.  (Either the phone is
            // still in use, or the phone is idle but we want to display
            // the "call ended" state for a couple of seconds.)

            // Switch to the special "Call ended" state when the phone is idle
            // but there's still a call in the DISCONNECTED state:
            if (currentlyIdle
                && (mCM.hasDisconnectedFgCall(subscription)
                || mCM.hasDisconnectedBgCall(subscription))) {
                if (DBG) log("- onDisconnect: switching to 'Call ended' state...");
                setInCallScreenMode(InCallScreenMode.CALL_ENDED);
            }

            // Force a UI update in case we need to display anything
            // special based on this connection's DisconnectCause
            // (see CallCard.getCallFailedString()).
            updateScreen();

            // Some other misc cleanup that we do if the call that just
            // disconnected was the foreground call.
            final boolean hasActiveCall = mCM.hasActiveFgCall(subscription);
            if (!hasActiveCall) {
                if (DBG) log("- onDisconnect: cleaning up after FG call disconnect...");

                // Dismiss any dialogs which are only meaningful for an
                // active call *and* which become moot if the call ends.
                if (mWaitPromptDialog != null) {
                    if (VDBG) log("- DISMISSING mWaitPromptDialog.");
                    mWaitPromptDialog.dismiss();  // safe even if already dismissed
                    mWaitPromptDialog = null;
                }
                if (mWildPromptDialog != null) {
                    if (VDBG) log("- DISMISSING mWildPromptDialog.");
                    mWildPromptDialog.dismiss();  // safe even if already dismissed
                    mWildPromptDialog = null;
                }
                if (mPausePromptDialog != null) {
                    if (DBG) log("- DISMISSING mPausePromptDialog.");
                    mPausePromptDialog.dismiss();  // safe even if already dismissed
                    mPausePromptDialog = null;
                }
            }

            // Updating the screen wake state is done in onPhoneStateChanged().


            // CDMA: We only clean up if the Phone state is IDLE as we might receive an
            // onDisconnect for a Call Collision case (rare but possible).
            // For Call collision cases i.e. when the user makes an out going call
            // and at the same time receives an Incoming Call, the Incoming Call is given
            // higher preference. At this time framework sends a disconnect for the Out going
            // call connection hence we should *not* bring down the InCallScreen as the Phone
            // State would be RINGING
            if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
                if (!currentlyIdle) {
                    // Clean up any connections in the DISCONNECTED state.
                    // This is necessary cause in CallCollision the foreground call might have
                    // connections in DISCONNECTED state which needs to be cleared.
                    mCM.clearDisconnected();

                    // The phone is still in use.  Stay here in this activity.
                    // But we don't need to keep the screen on.
                    if (DBG) log("onDisconnect: Call Collision case - staying on InCallScreen.");
                    if (DBG) PhoneUtils.dumpCallState(mPhone);
                    return;
                }
            }

            // This is onDisconnect() request from the last phone call; no available call anymore.
            //
            // When the in-call UI is in background *because* the screen is turned off (unlike the
            // other case where the other activity is being shown), we wake up the screen and
            // show "DISCONNECTED" state once, with appropriate elapsed time. After showing that
            // we *must* bail out of the screen again, showing screen lock if needed.
            //
            // See also comments for isForegroundActivityForProximity()
            //
            // TODO: Consider moving this to CallNotifier. This code assumes the InCallScreen
            // never gets destroyed. For this exact case, it works (since InCallScreen won't be
            // destroyed), while technically this isn't right; Activity may be destroyed when
            // in background.
            if (currentlyIdle && !isForegroundActivity() && isForegroundActivityForProximity()) {
                log("Force waking up the screen to let users see \"disconnected\" state");
                if (call != null) {
                    mCallCard.updateElapsedTimeWidget(call);
                }
                // This variable will be kept true until the next InCallScreen#onPause(), which
                // forcibly turns it off regardless of the situation (for avoiding unnecessary
                // confusion around this special case).
                mApp.inCallUiState.showAlreadyDisconnectedState = true;

                // Finally request wake-up..
                mApp.wakeUpScreen();

                // InCallScreen#onResume() will set DELAYED_CLEANUP_AFTER_DISCONNECT message,
                // so skip the following section.
                return;
            }

            // Finally, arrange for delayedCleanupAfterDisconnect() to get
            // called after a short interval (during which we display the
            // "call ended" state.)  At that point, if the
            // Phone is idle, we'll finish out of this activity.
            final int callEndedDisplayDelay;
            switch (cause) {
                // When the local user hanged up the ongoing call, it is ok to dismiss the screen
                // soon. In other cases, we show the "hung up" screen longer.
                //
                // - For expected reasons we will use CALL_ENDED_LONG_DELAY.
                // -- when the peer hanged up the call
                // -- when the local user rejects the incoming call during the other ongoing call
                // (TODO: there may be other cases which should be in this category)
                //
                // - For other unexpected reasons, we will use CALL_ENDED_EXTRA_LONG_DELAY,
                //   assuming the local user wants to confirm the disconnect reason.
                case LOCAL:
                    callEndedDisplayDelay = CALL_ENDED_SHORT_DELAY;
                    break;
                case NORMAL:
                case INCOMING_REJECTED:
                    callEndedDisplayDelay = CALL_ENDED_LONG_DELAY;
                    break;
                default:
                    callEndedDisplayDelay = CALL_ENDED_EXTRA_LONG_DELAY;
                    break;
            }
            mHandler.removeMessages(DELAYED_CLEANUP_AFTER_DISCONNECT);
            mHandler.sendMessageDelayed(mHandler.obtainMessage(DELAYED_CLEANUP_AFTER_DISCONNECT,
                    phone), callEndedDisplayDelay);
        }

        // Remove 3way timer (only meaningful for CDMA)
        // TODO: this call needs to happen in the CallController, not here.
        // (It should probably be triggered by the CallNotifier's onDisconnect method.)
        // mHandler.removeMessages(THREEWAY_CALLERINFO_DISPLAY_DONE);
    }

    /**
     * End the current in call screen session.
     *
     * This must be called when an InCallScreen session has
     * complete so that the next invocation via an onResume will
     * not be in an old state.
     */
    @Override
    public void endInCallScreenSession() {
        if (DBG) log("endInCallScreenSession()... phone state = " + mCM.getState());

        // If other sub is active, do not end the call screen ans update the
        // inCall UI with other active subscription
        if (PhoneUtils.isAnyOtherSubActive(PhoneUtils.getActiveSubscription())) {
            PhoneUtils.switchToOtherActiveSub(PhoneUtils.getActiveSubscription());
            updateScreen();
            log(" We have a active sub , switching to it" );
        } else {
            // Do not end the session if a call is on progress.
            if (mCM.getState() == PhoneConstants.State.IDLE) {
                endInCallScreenSession(false);
            } else {
                Log.i(LOG_TAG, "endInCallScreenSession(): Call in progress");
            }
        }
    }

    @Override
    protected void initInCallScreen() {
        if (DBG) log("initInCallScreen()...");

        // Have the WindowManager filter out touch events that are "too fat".
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES);

        // Initialize the CallCard.
        mCallCard = (MSimCallCard) findViewById(R.id.mSimCallCard);
        if (VDBG) log("  - mCallCard = " + mCallCard);
        mCallCard.setInCallScreenInstance(this);

        // Initialize the onscreen UI elements.
        initInCallTouchUi();

        // Helper class to keep track of enabledness/state of UI controls
        mInCallControlState = new InCallControlState(this, mCM);

        // Helper class to run the "Manage conference" UI
        mManageConferenceUtils = new ManageConferenceUtils(this, mCM);

        // The DTMF Dialpad.
        ViewStub stub = (ViewStub) findViewById(R.id.dtmf_twelve_key_dialer_stub);
        mDialer = new MSimDTMFTwelveKeyDialer(this, stub);
        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
    }

    @Override
    protected void delayedCleanupAfterDisconnect(Phone phone) {
        if (VDBG) log("delayedCleanupAfterDisconnect()...  Phone state = " + mCM.getState());

        // Clean up any connections in the DISCONNECTED state.
        //
        // [Background: Even after a connection gets disconnected, its
        // Connection object still stays around, in the special
        // DISCONNECTED state.  This is necessary because we we need the
        // caller-id information from that Connection to properly draw the
        // "Call ended" state of the CallCard.
        //   But at this point we truly don't need that connection any
        // more, so tell the Phone that it's now OK to to clean up any
        // connections still in that state.]
        mCM.clearDisconnected();

        // There are two cases where we should *not* exit the InCallScreen:
        //   (1) Phone is still in use
        // or
        //   (2) There's an active progress indication (i.e. the "Retrying..."
        //       progress dialog) that we need to continue to display.

        boolean stayHere = phoneIsInUse() || mApp.inCallUiState.isProgressIndicationActive();

        // If other sub is active, do not end the call screen.
        if (!stayHere && PhoneUtils.isAnyOtherSubActive(PhoneUtils.getActiveSubscription())) {
            if (DBG) log("- delayedCleanupAfterDisconnect: othe sub is active , switching");
            PhoneUtils.switchToOtherActiveSub(PhoneUtils.getActiveSubscription());
            updateScreen();
            stayHere = true;
        }

        if (stayHere) {
            if (DBG) log("- delayedCleanupAfterDisconnect: staying on the InCallScreen...");
        } else {
            // Phone is idle!  We should exit the in-call UI now.
            if (DBG) log("- delayedCleanupAfterDisconnect: phone is idle...");

            // And (finally!) exit from the in-call screen
            // (but not if we're already in the process of pausing...)
            if (mIsForegroundActivity) {
                if (DBG) log("- delayedCleanupAfterDisconnect: finishing InCallScreen...");

                // In some cases we finish the call by taking the user to the
                // Call Log.  Otherwise, we simply call endInCallScreenSession,
                // which will take us back to wherever we came from.
                //
                // UI note: In eclair and earlier, we went to the Call Log
                // after outgoing calls initiated on the device, but never for
                // incoming calls.  Now we do it for incoming calls too, as
                // long as the call was answered by the user.  (We always go
                // back where you came from after a rejected or missed incoming
                // call.)
                //
                // And in any case, *never* go to the call log if we're in
                // emergency mode (i.e. if the screen is locked and a lock
                // pattern or PIN/password is set), or if we somehow got here
                // on a non-voice-capable device.

                if (VDBG) log("- Post-call behavior:");
                if (VDBG) log("  - mLastDisconnectCause = " + mLastDisconnectCause);
                if (VDBG) log("  - isPhoneStateRestricted() = " + isPhoneStateRestricted(phone));

                // DisconnectCause values in the most common scenarios:
                // - INCOMING_MISSED: incoming ringing call times out, or the
                //                    other end hangs up while still ringing
                // - INCOMING_REJECTED: user rejects the call while ringing
                // - LOCAL: user hung up while a call was active (after
                //          answering an incoming call, or after making an
                //          outgoing call)
                // - NORMAL: the other end hung up (after answering an incoming
                //           call, or after making an outgoing call)

                if ((mLastDisconnectCause != Connection.DisconnectCause.INCOMING_MISSED)
                        && (mLastDisconnectCause != Connection.DisconnectCause.INCOMING_REJECTED)
                        && !isPhoneStateRestricted(phone)
                        && PhoneGlobals.sVoiceCapable) {
                    final Intent intent = mApp.createPhoneEndIntentUsingCallOrigin();
                    ActivityOptions opts = ActivityOptions.makeCustomAnimation(this,
                            R.anim.activity_close_enter, R.anim.activity_close_exit);
                    if (VDBG) {
                        log("- Show Call Log (or Dialtacts) after disconnect. Current intent: "
                                + intent);
                    }
                    try {
                        startActivity(intent, opts.toBundle());
                    } catch (ActivityNotFoundException e) {
                        // Don't crash if there's somehow no "Call log" at
                        // all on this device.
                        // (This should never happen, though, since we already
                        // checked PhoneApp.sVoiceCapable above, and any
                        // voice-capable device surely *should* have a call
                        // log activity....)
                        Log.w(LOG_TAG, "delayedCleanupAfterDisconnect: "
                              + "transition to call log failed; intent = " + intent);
                        // ...so just return back where we came from....
                    }
                    // Even if we did go to the call log, note that we still
                    // call endInCallScreenSession (below) to make sure we don't
                    // stay in the activity history.
                }

            }
        if (VDBG) log("delayedCleanupAfterDisconnect()...  Phone state = 1");
            endInCallScreenSession();

            // Reset the call origin when the session ends and this in-call UI is being finished.
            mApp.setLatestActiveCallOrigin(null);
        }
    }

    @Override
    protected void bailOutAfterErrorDialog() {
        if (mGenericErrorDialog != null) {
            if (DBG) log("bailOutAfterErrorDialog: DISMISSING mGenericErrorDialog.");
            mGenericErrorDialog.dismiss();
            mGenericErrorDialog = null;
        }
        if (DBG) log("bailOutAfterErrorDialog(): end InCallScreen session...");

        // Now that the user has dismissed the error dialog (presumably by
        // either hitting the OK button or pressing Back, we can now reset
        // the pending call status code field.
        //
        // (Note that the pending call status is NOT cleared simply
        // by the InCallScreen being paused or finished, since the resulting
        // dialog is supposed to persist across orientation changes or if the
        // screen turns off.)
        //
        // See the "Error / diagnostic indications" section of
        // InCallUiState.java for more detailed info about the
        // pending call status code field.
        final InCallUiState inCallUiState = mApp.inCallUiState;

        inCallUiState.clearPendingCallStatusCode();

        // If other sub is active, do not end the call screen ans update the
        // inCall UI with other active subscription
        if (PhoneUtils.isAnyOtherSubActive(PhoneUtils.getActiveSubscription())) {
            PhoneUtils.switchToOtherActiveSub(PhoneUtils.getActiveSubscription());
            updateScreen();
            log(" Switch to other active sub" );
        } else {
            // Force the InCallScreen to truly finish(), rather than just
            // moving it to the back of the activity stack (which is what
            // our finish() method usually does.)
            // This is necessary to avoid an obscure scenario where the
            // InCallScreen can get stuck in an inconsistent state, somehow
            // causing a *subsequent* outgoing call to fail (bug 4172599).
            endInCallScreenSession(true /* force a real finish() call */);
        }
    }

    @Override
    protected void registerForPhoneStates() {
        if (!mRegisteredForPhoneStates) {
            mCM.registerForPreciseCallStateChanged(mHandler, PHONE_STATE_CHANGED, null);
            mCM.registerForDisconnect(mHandler, PHONE_DISCONNECT, null);
            // TODO: sort out MMI code (probably we should remove this method entirely).
            // See also MMI handling code in onResume()
            // mCM.registerForMmiInitiate(mHandler, PhoneApp.MMI_INITIATE, null);

            // register for the MMI complete message.  Upon completion,
            // PhoneUtils will bring up a system dialog instead of the
            // message display class in PhoneUtils.displayMMIComplete().
            // We'll listen for that message too, so that we can finish
            // the activity at the same time.
            mCM.registerForMmiComplete(mHandler, PhoneGlobals.MMI_COMPLETE, null);
            for (Phone phone : mCM.getAllPhones()) {
                if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA ) {
                    log("register for cdma call waiting " + phone.getSubscription());
                    mCM.registerForCallWaiting(mHandler, PHONE_CDMA_CALL_WAITING,
                            phone.getSubscription());
                    break;
                }
            }
            mCM.registerForPostDialCharacter(mHandler, POST_ON_DIAL_CHARS, null);
            mCM.registerForSuppServiceFailed(mHandler, SUPP_SERVICE_FAILED, null);
            mCM.registerForIncomingRing(mHandler, PHONE_INCOMING_RING, null);
            mCM.registerForNewRingingConnection(mHandler, PHONE_NEW_RINGING_CONNECTION, null);
            mCM.registerForSubscriptionChange(mHandler, PHONE_ACTIVE_SUBSCRIPTION_CHANGE, null);
            mRegisteredForPhoneStates = true;
        }
    }

    @Override
    protected void unregisterForPhoneStates() {
        mCM.unregisterForPreciseCallStateChanged(mHandler);
        mCM.unregisterForDisconnect(mHandler);
        mCM.unregisterForMmiInitiate(mHandler);
        mCM.unregisterForMmiComplete(mHandler);
        mCM.unregisterForCallWaiting(mHandler);
        mCM.unregisterForPostDialCharacter(mHandler);
        mCM.unregisterForSuppServiceFailed(mHandler);
        mCM.unregisterForIncomingRing(mHandler);
        mCM.unregisterForNewRingingConnection(mHandler);
        // remove locally posted message
        mHandler.removeMessages(DELAYED_CLEANUP_AFTER_DISCONNECT);
        mRegisteredForPhoneStates = false;
    }

    @Override
    protected void internalResolveIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        int subscription = PhoneUtils.getActiveSubscription();
        Phone phone = mApp.getPhone(subscription);
        String action = intent.getAction();
        log("internalResolveIntent: action=" + action);
        log("onNewIntent: intent = " + intent + ", phone state = " + mCM.getState(subscription));
        if (action.equals(intent.ACTION_MAIN)) {
            if (intent.hasExtra(SHOW_DIALPAD_EXTRA)) {
                // SHOW_DIALPAD_EXTRA can be used here to specify whether the DTMF
                // dialpad should be initially visible.  If the extra isn't
                // present at all, we just leave the dialpad in its previous state.

                boolean showDialpad = intent.getBooleanExtra(SHOW_DIALPAD_EXTRA, false);
                if (VDBG) log("- internalResolveIntent: SHOW_DIALPAD_EXTRA: " + showDialpad);

                // If SHOW_DIALPAD_EXTRA is specified, that overrides whatever
                // the previous state of inCallUiState.showDialpad was.
                mApp.inCallUiState.showDialpad = showDialpad;

                final boolean hasActiveCall = mCM.hasActiveFgCall(subscription);
                final boolean hasHoldingCall = mCM.hasActiveBgCall(subscription);

                // There's only one line in use, AND it's on hold, at which we're sure the user
                // wants to use the dialpad toward the exact line, so un-hold the holding line.
                if (showDialpad && !hasActiveCall && hasHoldingCall) {
                    PhoneUtils.switchHoldingAndActive(mCM.getFirstActiveBgCall(subscription));
                }
            }
            return;
        }

        if (action.equals(OtaUtils.ACTION_DISPLAY_ACTIVATION_SCREEN)) {
            if (!TelephonyCapabilities.supportsOtasp(phone)) {
                throw new IllegalStateException(
                    "Received ACTION_DISPLAY_ACTIVATION_SCREEN intent on non-OTASP-capable device: "
                    + intent);
            }

            setInCallScreenMode(InCallScreenMode.OTA_NORMAL);
            if ((mApp.cdmaOtaProvisionData != null)
                && (!mApp.cdmaOtaProvisionData.isOtaCallIntentProcessed)) {
                mApp.cdmaOtaProvisionData.isOtaCallIntentProcessed = true;
                mApp.cdmaOtaScreenState.otaScreenState =
                        CdmaOtaScreenState.OtaScreenState.OTA_STATUS_ACTIVATION;
            }
            return;
        }
        if (action.equals(OtaUtils.ACTION_PERFORM_CDMA_PROVISIONING)) {
            // This intent is now handled by the InCallScreenShowActivation
            // activity, which translates it into a call to
            // OtaUtils.startInteractiveOtasp().
            throw new IllegalStateException(
                "Unexpected ACTION_PERFORM_CDMA_PROVISIONING received by InCallScreen: "
                + intent);
        } else if (action.equals(Intent.ACTION_CALL)
                   || action.equals(Intent.ACTION_CALL_EMERGENCY)) {
            // ACTION_CALL* intents go to the OutgoingCallBroadcaster, which now
            // translates them into CallController.placeCall() calls rather than
            // launching the InCallScreen directly.
            throw new IllegalStateException("Unexpected CALL action received by InCallScreen: "
                                            + intent);
        } else if (action.equals(ACTION_UNDEFINED)) {
            // This action is only used for internal bookkeeping; we should
            // never actually get launched with it.
            Log.wtf(LOG_TAG, "internalResolveIntent: got launched with ACTION_UNDEFINED");
            return;
        } else {
            Log.wtf(LOG_TAG, "internalResolveIntent: unexpected intent action: " + action);
            // But continue the best we can (basically treating this case
            // like ACTION_MAIN...)
            return;
        }
    }

    @Override
    protected void initInCallTouchUi() {
        if (DBG) log("initInCallTouchUi()...");
        // TODO: we currently use the InCallTouchUi widget in at least
        // some states on ALL platforms.  But if some devices ultimately
        // end up not using *any* onscreen touch UI, we should make sure
        // to not even inflate the InCallTouchUi widget on those devices.
        mInCallTouchUi = (MSimInCallTouchUi) findViewById(R.id.inCallTouchUiMSim);
        mInCallTouchUi.setInCallScreenInstance(this);

        // RespondViaSmsManager implements the "Respond via SMS"
        // feature that's triggered from the incoming call widget.
        mRespondViaSmsManager = new RespondViaSmsManager();
        mRespondViaSmsManager.setInCallScreenInstance(this);
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
