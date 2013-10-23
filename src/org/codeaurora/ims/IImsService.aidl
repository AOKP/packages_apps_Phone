/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above
 *    copyright notice, this list of conditions and the following
 *    disclaimer in the documentation and/or other materials provided
 *    with the distribution.
 *  * Neither the name of The Linux Foundation nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.

 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.codeaurora.ims;

import org.codeaurora.ims.IImsServiceListener;

/**
 * Interface used to interact with IMS Service.
 *
 * {@hide}
 */
interface IImsService {

    /**
     * Dial a number. This doesn't place the call. It displays
     * the Dialer screen.
     * @param number the number to be dialed. If null, this
     * would display the Dialer screen with no number pre-filled.
     */
    void dial(String number);

    /**
     * Register callback
     * @param imsServListener - IMS Service Listener
     */
    int registerCallback(IImsServiceListener imsServListener);

    /**
     * Deregister callback
     * @param imsServListener - IMS Service Listener
     */
    int deregisterCallback(IImsServiceListener imsServListener);

    /**
     * Set IMS Registration state
     * @param imsRegState - IMS Registration state
     */
    void setRegistrationState(int imsRegState);

    /**
     * Get IMS Registration state
     */
    int getRegistrationState();

    /**
     * HangupUri in an IMS Conference Call
     */
    void hangupUri(int connectionId, String userUri, String confUri);

    /**
     * Hangup for rejecting incoming call with a reason
     * This api will be used for following two scenarios -
     * - Reject incoming call
     * - While on active call, receive incoming call. Reject this
     *   incoming call.
     * connectionId - call id for the call
     * userUri - dial string or uri
     * confUri - uri associated with conference/multiparty call
     * mpty - true for conference/multiparty call
     * failCause - reason for hangup. Refer to CallFailCause.java for details
     * errorInfo - extra information associated with hangup
     */
    void hangupWithReason(int connectionId, String userUri, String confUri,
            boolean mpty, in int failCause, in String errorInfo);

    /**
     * Get the Call Details extras for the Call ID
     * @param callId - ID of the Call
     */
    String[] getCallDetailsExtrasinCall(int callId);

    /**
     * Get the Disconnect cause for Connection
     * @param callId - ID of the Call
     */

    String getImsDisconnectCauseInfo(int callId);

    /**
     * Get List of User Uri in an IMS Conference Call
     */
    String[] getUriListinConf();

    /**
     * Get the Service State for SMS service
     */
    boolean isVTModifyAllowed();

    /**
     * The system notifies about the failure (e.g. timeout) of the previous request to
     * change the type of the connection by re-sending the modify connection type request
     * with the status set to fail. After receiving an indication of call modify request
     * it will be possible to query for the status of the request.(see
     * {@link CallManager#registerForConnectionTypeChangeRequest(Handler, int, Object)}
     * ) If no request has been received, this function returns false, no error.
     *
     * @return true if the proposed connection type request failed (e.g. timeout).
     */
    boolean getProposedConnectionFailed(int connIndex);

    /**
     * Returns true if the current phone supports the ability to add participant
     *
     */
    boolean isAddParticipantAllowed();

    /**
     * Api for adding participant
     * dialString - can be either a number or single or multiple uri
     * clir - will be default value. This is for future usage
     * callType - will be UNKNOWN. But in ImsPhone, this will take value of fg call.
     *            This is for future usage, in case call type should be passed through UI
     * String[] - can be made Parcelable incase its being used across process boundaries,
     *            which currently is not the case.
     */
    void addParticipant(String dialString, int clir, int callType, in String[] extra);
}

