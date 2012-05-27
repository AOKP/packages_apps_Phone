/*
 * Copyright (C) 2012 by Gary L Dezern
 *     with full usage rights granted to The Android Open Source Project
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

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;

import com.android.internal.telephony.EncodeException;
import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.util.HexDump;
import android.bluetooth.AtCommandHandler;
import android.bluetooth.AtCommandResult;
import android.bluetooth.AtParser;
import android.bluetooth.HeadsetBase;
import android.content.ContentValues;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.text.format.Time;
import android.provider.Telephony.Sms;
import android.util.Log;


public class BluetoothSMSAccess {

    private static final boolean DBG = false;

    /** helper structure for passing around info about an sms message */
    class SMSMsgInfo {
        int _id;
        int type;
        int read;
        String SMSCAddress;
        String OriginAddress;
        long date;
        String body;
    };

    /** constants for CMS errors */
    public static final int CMS_ME_FAILURE = 300;
    public static final int CMS_SERVICE_RESERVED = 301;
    public static final int CMS_OP_NOT_ALLOWED = 302;
    public static final int CMS_OP_NOT_SUPPORTED = 303;
    public static final int CMS_INVALID_PDU_PARAM = 304;
    public static final int CMS_INVALID_TEXT_PARAM = 305;
    public static final int CMS_SIM_NOT_INSERTED = 310;
    public static final int CMS_SIM_FAILURE = 313;
    public static final int CMS_INVALID_INDEX = 321;
    public static final int CMS_SMSC_ADDR_UNKNOWN = 330;
    public static final int CMS_NO_NETWORK_SERVICE = 331;
    public static final int CMS_NETWORK_TIMEOUT = 332;
    public static final int CMS_UNKNOWN_ERROR = 500;

    /** specific 4 char sequence to prompt CMGS sender to stream PDU (CR, LF, GreaterThan, Space) */
    private static final String CMGS_PDU_PROMPT = "\r\n> ";

    // TODO:  does it make sense to re-factor these mNMI_ variables into a local class?
    private int mNMI_mode;
    private int mNMI_mt;
    private int mNMI_bfr;
    private int mNMI_bm;
    private int mNMI_ds;

    private String mServiceCenterAddress;
    private int mServiceCenterType;

    // dummy service center number used to satisfy GSM requirements when we don't
    // have access to the actual service center number
    public static final String DUMMY_SERVICE_CENTER = "0000";

    private static final String TAG = "BluetoothSMSAccess";

    private final Context mContext;
    private final BluetoothHandsfree mHandsfree;

    // android messaging hooks
    private SmsContentObserverClass smsContentObserver = new SmsContentObserverClass();

    // the highest seen message date (used to determine what messages are newly received)
    private long mLastSeenSMSDate = 0;


    public BluetoothSMSAccess(Context context, BluetoothHandsfree handsfree) {
        mContext = context;
        mHandsfree = handsfree;

        mNMI_mode = 0;
        mNMI_mt = 0;
        mNMI_bm = mNMI_ds = 0;
        mNMI_bfr = 1;

        mServiceCenterAddress = DUMMY_SERVICE_CENTER;
        mServiceCenterType = 129;

    }

    /** registers SMS specific AT command parsers */
    public void register(AtParser parser) {

        // +CMFG: (0)
        parser.register("+CMGF", new AtCommandHandler() {
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                if (DBG) Log.d(TAG, "IN: +CMGF= " + Arrays.toString(args));

                if ((1 == args.length) && (args[0].equals(0))) {
                    return new AtCommandResult(AtCommandResult.OK);
                } else {
                    return new AtCommandResult(AtCommandResult.ERROR);
                }
            }
            @Override
            public AtCommandResult handleTestCommand() {
                return new AtCommandResult("+CMFG: (0)");
            }
            @Override
            public AtCommandResult handleReadCommand() {
                return new AtCommandResult("+CPMS: 0");
            }
        });

        // +CNMI: (0-2),(0-1),(0),(0),(0-1)
        parser.register("+CNMI", new AtCommandHandler() {
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                if (DBG) Log.d(TAG, "IN: +CNMI= " + Arrays.toString(args));

                if ((5 == args.length) &&
                        (args[0] instanceof Integer) &&
                        (args[1] instanceof Integer) &&
                        (args[2] instanceof Integer) &&
                        (args[3] instanceof Integer) &&
                        (args[4] instanceof Integer)) {
                    // validate the parameters so "OK" can be sent before initializing
                    // NMI - so that any notifications sent as a result of bfr=0 are
                    // sent AFTER the "OK" final result code.
                    if (((Integer)args[0] >= 0) && ((Integer)args[0] <= 2) &&
                            ((Integer)args[1] >= 0) && ((Integer)args[1] <= 1) &&
                            args[2].equals(0) && args[3].equals(0) &&
                            ((Integer)args[4] >= 0) && ((Integer)args[4] <= 1)) {
                        // return OK before actually initializing!
                        mHandsfree.sendURC("OK");
                        InitializeNMI((Integer)args[0], (Integer)args[1], 0, 0, (Integer)args[4]);
                        return new AtCommandResult(AtCommandResult.UNSOLICITED);
                    } else {
                        return reportCmsError(CMS_OP_NOT_SUPPORTED);
                    }
                } else {
                    return new AtCommandResult(AtCommandResult.ERROR);
                }
            }
            @Override
            public AtCommandResult handleTestCommand() {
                return new AtCommandResult("+CNMI: (0-2),(0-1),(0),(0),(0-1)");
            }
            @Override
            public AtCommandResult handleReadCommand() {
                return new AtCommandResult("+CNMI: " +
                        mNMI_mode + "," +
                        mNMI_mt + "," +
                        mNMI_bm + "," +
                        mNMI_ds + "," +
                        mNMI_bfr);
            }
        });

        // +CPMS: ("ME"),("ME"),("ME")
        parser.register("+CPMS", new AtCommandHandler() {
            // order of storage spaces.
            //  1.  storage area used when reading or deleting SMS messages
            //  2.  storage area used when sending or writing SMS messages
            //  3.  preferred storage area for storing newly rcvd SMS messages

            // storage area "ME" means the primary message storage.  We don't support
            // any other storage areas, so returns errors if they are seen.
            // The parameters are "# msgs" and "total # msg slots".  Being that
            // there are essentially an unlimited number of message "slots", just
            // return the current msg count + 10 so it always looks like there is
            // more room available.
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                if (DBG) Log.d(TAG, "IN: +CPMS= " + Arrays.toString(args));

                if ((1 <= args.length) && (3 >= args.length)) {
                    // if any args are passed, they each must be "\"ME\""
                    if (args[0].equals("\"ME\"") &&
                            ((2 > args.length) || args[1].equals("\"ME\"")) &&
                            ((3 > args.length) || args[2].equals("\"ME\""))
                            ) {
                        int msgCount = getMessageVirtualCount();
                        return new AtCommandResult("+CPMS: " + msgCount + "," + (msgCount + 10) +
                                "," + msgCount + "," + (msgCount + 10) +
                                "," + msgCount + "," + (msgCount + 10));
                    } else {
                        return reportCmsError(CMS_OP_NOT_ALLOWED);
                    }
                } else {
                    return new AtCommandResult(AtCommandResult.ERROR);
                }
            }
            @Override
            public AtCommandResult handleTestCommand() {
                return new AtCommandResult("+CPMS: (\"ME\"),(\"ME\"),(\"ME\")");
            }
            @Override
            public AtCommandResult handleReadCommand() {
                int msgCount = getMessageVirtualCount();
                return new AtCommandResult("+CPMS: \"ME\"," + msgCount + "," + (msgCount + 10) +
                        ",\"ME\"," + msgCount + "," + (msgCount + 10) +
                        ",\"ME\"," + msgCount + "," + (msgCount + 10));
            }
        });

        // +CSMS: <service>, <mt>, <mo>, <bm>
        parser.register("+CSMS", new AtCommandHandler() {
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                if (DBG) Log.d(TAG, "IN: +CSMS= " + Arrays.toString(args));
                // only service '0' is supported
                if ((1 == args.length) && (args[0] instanceof Integer)) {
                    if (args[0].equals(0))
                        return new AtCommandResult("+CSMS: 1,1,0");
                    else
                        return reportCmsError(CMS_OP_NOT_SUPPORTED);
                } else {
                    return new AtCommandResult(AtCommandResult.ERROR);
                }
            }
            @Override
            public AtCommandResult handleReadCommand() {
                // service 0, mt (mobile term msgs) support, mo (mobile origin msgs) support,
                // bm (broadcast type not support)
                return new AtCommandResult("+CSMS: 0,1,1,0");
            }
            @Override
            public AtCommandResult handleTestCommand() {
                return new AtCommandResult("+CSMS: (0)");
            }

        });

        // +CSCA: (setting the service center)
        parser.register("+CSCA", new AtCommandHandler() {
            // NOTE:  it doesn't appear possible to set/override the service center address
            // with android devices.  However, support of +CSCA is required in the gsm spec.
            // Therefore, fake it.
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                if (DBG) Log.d(TAG, "IN: +CSCA= " + Arrays.toString(args));

                if ((1 <= args.length) && (2 >= args.length)) {
                    mServiceCenterAddress = args[0].toString();
                    if (2 == args.length)
                        mServiceCenterType = (Integer)args[1];
                    return new AtCommandResult(AtCommandResult.OK);
                } else {
                    return new AtCommandResult(AtCommandResult.ERROR);
                }
            }
            @Override
            public AtCommandResult handleReadCommand() {
                return new AtCommandResult("+CSCA: " + mServiceCenterAddress + "," + mServiceCenterType);
            }
        });

        // TODO:  implement +CMGL (list messages)

        // +CMGS (send message)
        parser.register("+CMGS", new AtCommandHandler() {
            // NOTE:  Implementing this command requires a deviation from the the
            // pre-existing bluetooth line-based communication.  All other AT+ style
            // commands communicate line-based with CR line terminators. However,
            // +CMGS requires that the TE (radio, headset, etc) sends two lines: the
            // first terminated by CR and the second terminated by either ESC or
            // Ctrl-Z.  That required some low level modifications...
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                if (DBG) Log.d(TAG, "IN: +CMGS= " + Arrays.toString(args));

                if ((1 == args.length) && (args[0] instanceof Integer)) {
                    // record the parameter (should be the length of TPDU)
                    final int parameter = (Integer)args[0];
                    // a very specific string of chars should be sent to "prompt" the PDU stream

                    mHandsfree.setSpecialPDUInputHandler(CMGS_PDU_PROMPT,
                            new HeadsetBase.SpecialPDUInputHandler() {
                        @Override
                        public void handleInput(String input) {
                            // clear the special handler
                            mHandsfree.setSpecialPDUInputHandler(null, null);
                            if (DBG) {
                                Log.d(TAG, "InputHandler param: " + parameter);
                                Log.d(TAG, "InputHandler input: " + input);
                            }
                            // parsing the PDU is annoying, and must block
                            // the BT channel until a response.  This
                            // function should send any response(s) back.
                            HandleSMSSubmitInput(parameter, input);
                        }
                    });
                    // the setSpecialInputHandler call will send the CMGS_PDU_PROMPT, so
                    // do nothing here.
                    return new AtCommandResult(AtCommandResult.UNSOLICITED);
                } else {
                    return new AtCommandResult(AtCommandResult.ERROR);
                }
            }
        });

        // +CMGR
        parser.register("+CMGR", new AtCommandHandler() {
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                if (DBG) Log.d(TAG, "IN: +CMGR= " + Arrays.toString(args));

                if ((1 == args.length) && (args[0] instanceof Integer)) {
                    SMSMsgInfo smsInfo = new SMSMsgInfo();
                    // gather the sms message info
                    int iRet = getMessageAtIndex((Integer) args[0], smsInfo, true);
                    if (iRet < 0) {    // unknown error
                        return new AtCommandResult(AtCommandResult.ERROR);
                    } else if (iRet > 0) {    // specific CMS error
                        return reportCmsError(iRet);
                    }
                    // create the response with PDU stream
                    return CreatePDUResponse(smsInfo);
                } else {
                    return new AtCommandResult(AtCommandResult.ERROR);
                }
            }
            @Override
            public AtCommandResult handleTestCommand() {
                return new AtCommandResult("+CMGR: (1-" + getMessageVirtualCount() + ")");
            }
        });
    }

    /** called on disconnected bluetooth */
    synchronized void resetAtState() {
        TurnOffNMI();
    }

    /** helper similar to Integer.parseInt(string, 16), but specialized for PDU processing */
    private static int CharToByte(char c)
    {
        if (c >= '0' && c <= '9') return (c - '0');
        if (c >= 'A' && c <= 'F') return (c - 'A' + 10);
        if (c >= 'a' && c <= 'f') return (c - 'a' + 10);
        throw new RuntimeException ("Invalid hex char '" + c + "'");
    }

    /** helper that uses CharToByte to generate pdu octets */
    private static byte TwoCharToByte(char firstC, char secondC)
    {
        return (byte)((CharToByte(firstC) << 4) | CharToByte(secondC));
    }

    /** handles the PDU stream when creating/sending an SMS message */
    private void HandleSMSSubmitInput(int cmdParameter, String input)
    {
        AtCommandResult result = null;
        // first, validate the data from the TE before trying to parse
        // much of it.
        if ((1 == input.length()) && (27 == (int)input.charAt(0))) {
            result = new AtCommandResult(AtCommandResult.OK);
        } else if (2 > input.length()) {
            if (DBG) Log.d(TAG, "handleInput: <2 length input");
            result = new AtCommandResult(AtCommandResult.ERROR);
        } else  {
            // might be valid... do a bit more checking.

            // NOTE:  Some TE's (kenwood head units) are buggy in that they
            // may embed invalid characters in the destination address of the
            // PDU stream.  In particular, it won't strip invalid characters
            // from a destination phone number before shoving it into the PDU
            // stream.  Instead of "1234567890" in the stream, I'll get other
            // punctuation such as "(123) 456-7890".  For that reason, the entire
            // pdu stream can't be converted to a byte array at one time.

            boolean bError = false;
            String scAddress = null;
            String destAddress = null;
            String msgBody = null;

            char[] inputArray = input.toCharArray();
            int curInputChar = 0;  // where in "input" currently parsing

            int msgRefNum = 99;

            if (DBG) Log.d(TAG, "input stream: " + input);

            // first byte (pair of chars) should be the length of the SC address
            try {
                int scAddressLen = TwoCharToByte(inputArray[curInputChar++], inputArray[curInputChar++]);
                // ensure the input length is long enough for the scAddresslen, scaddress, and
                // at least 3 more pairs. (first octet, msg ref, dest addr len)
                if (((4 + scAddressLen) * 2) >= input.length()) {
                    // input too short
                    Log.e(TAG, "Input too short after SC Length");
                    result = reportCmsError(CMS_INVALID_PDU_PARAM);
                    bError = true;
                } else {
                    if (DBG) Log.d(TAG, "Parsing Status A:  curInputChar=" + curInputChar + ", scAddressLen=" + scAddressLen);

                    // actually extract the scAddress along with the 2 next bytes/pairs
                    byte[] pdu = new byte[scAddressLen + 2];
                    for (int i = 0; i <  (scAddressLen + 2); i++)
                    {
                        pdu[i] = TwoCharToByte(inputArray[curInputChar++], inputArray[curInputChar++]);
                    }
                    if (0 != scAddressLen) {
                        scAddress = PhoneNumberUtils.calledPartyBCDToString(pdu, 0, scAddressLen);
                    }
                    // next byte is the "first octet" flags - most ignored, but
                    // make sure bits 0-1 are set to 0x1
                    if (1 != (pdu[scAddressLen] & 0x3)) {
                        Log.e(TAG, "Invalid TP-Message-Type-Indicator flag");
                        result = reportCmsError(CMS_OP_NOT_SUPPORTED);
                        bError = true;
                    }
                    // the next byte (TP-Message-Reference) is completely ignored
                }
            }
            catch (RuntimeException ex) {
                Log.e(TAG, "Invalid SC Address");
                // fall through for CMS unknown err
                bError = true;
            }

            if (!bError) {
                try {
                    if (DBG) Log.d(TAG, "Parsing Status B:  curInputChar=" + curInputChar);
                    // curInputChar should point to the length of the destination address now...
                    int destAddrLen = TwoCharToByte(inputArray[curInputChar++], inputArray[curInputChar++]);
                    int destAddrBytes = (((destAddrLen + 1) / 2));
                    // ensure there's still enough input space for the destination address
                    // and 4 more pairs (destAddrType, protocol ID, data coding scheme, length of user data)
                    if (((4 + destAddrBytes) * 2) >= (input.length() - curInputChar)) {
                        // input too short
                        Log.e(TAG, "Input too short after Destination Address Length");
                        bError = true;
                    } else {
                        if (DBG) Log.d(TAG, "Parsing Status C:  curInputChar=" + curInputChar + ", destAddrLen=" + destAddrLen);

                        curInputChar += 2; // skip the dest address type.

                        // actually work to pull out the destination phone number.
                        StringBuilder sb = new StringBuilder(destAddrLen + 1);

                        for (int i = 0; i < destAddrBytes; i++) {
                            // get two characters and swap them
                            sb.append(inputArray[curInputChar + 1]);
                            sb.append(inputArray[curInputChar]);
                            curInputChar += 2;
                        }
                        destAddress = sb.substring(0, destAddrLen);

                        if (DBG) Log.d(TAG, "Parsing Status D:  curInputChar=" + curInputChar + ", destAddress=" + destAddress);

                        // the next byte contains the protocol identifier.  enforce this being 0
                        if (!bError && (0 != TwoCharToByte(inputArray[curInputChar++], inputArray[curInputChar++]))) {
                            Log.e(TAG, "Invalid TP-Protocol-Identifer");
                            result = reportCmsError(CMS_OP_NOT_SUPPORTED);
                            bError = true;
                        }
                        // next is a data coding scheme.  Handle any scheme the user wants, as
                        // long as they want 7bit GSM with no special features.
                        if (!bError && (0 != TwoCharToByte(inputArray[curInputChar++], inputArray[curInputChar++]))) {
                            Log.e(TAG, "Unsupported TP-Data-Coding-Scheme");
                            result = reportCmsError(CMS_OP_NOT_SUPPORTED);
                            bError = true;
                        }
                        // TP-Validy-Period bits not set, so don't try to read the period
                    }
                }
                catch (RuntimeException ex) {
                    Log.e(TAG, "Invalid Destination Address");
                    // fall through to result being cms unknown
                    bError = true;
                }
            }
            if (!bError) {
                try {
                    // only thing left is the user data length and actual user data...
                    int userDataLen = TwoCharToByte(inputArray[curInputChar++], inputArray[curInputChar++]);

                    msgBody = GsmAlphabet.gsm7BitPackedToString(
                            HexDump.hexStringToByteArray(input.substring(curInputChar)),
                            0, userDataLen);
                }
                catch (RuntimeException ex) {
                    Log.e(TAG, "Invalid Message Body");
                    // fall through to result being cms unknown
                    bError = true;
                }
            }

            if (!bError) {
                // send the message
                if (DBG) {
                    Log.d(TAG, "To: " + destAddress);
                    Log.d(TAG, "Body: " + msgBody);
                }
                SmsManager.getDefault().sendTextMessage(destAddress, scAddress, msgBody, null, null);

                // according to the spec, the code should block until the message sends or fails..
                //  not sure how to deal with that here.

                // some debugging code to allow testing for TE response to errors.
                if (DBG && (-1 != msgBody.toUpperCase().indexOf("CMSERROR"))) {
                    Log.d(TAG, "Forced CMS error final result");
                    result = reportCmsError(CMS_NO_NETWORK_SERVICE);
                }
                else if (DBG && (-1 != msgBody.toUpperCase().indexOf("ERROR"))) {
                    Log.d(TAG, "Forced non-CMS error final result");
                    result = new AtCommandResult(AtCommandResult.ERROR);
                } else {
                    if (DBG) Log.d(TAG, "OUT: +CMGS: " + msgRefNum);
                    mHandsfree.sendURC("+CMGS: " + msgRefNum);
                    result = new AtCommandResult(AtCommandResult.OK);
                }
            }
            if (null == result)
            {
                result = reportCmsError(CMS_UNKNOWN_ERROR);
            }
        }
        if (null == result)
            result = new AtCommandResult(AtCommandResult.OK);

        if (DBG) Log.d(TAG, "OUT: " + result.toString());
        mHandsfree.sendURC(result.toString());
    }

    /** generate +CMS ERROR: final responses. */
    private AtCommandResult reportCmsError(int error) {
        AtCommandResult result = new AtCommandResult(AtCommandResult.UNSOLICITED);
        result.addResponse("+CMS ERROR: " + error);
        return result;
    }

    /** helper function for creating PDU response */
    private static int swapBCDForTime( int inbyte )
    {
        inbyte = inbyte & 0xFF;

        int high = inbyte / 10;
        return ((inbyte - (high * 10)) << 4) | high;
    }

    /** given an smsInfo, creates +CMGR response including PDU stream */
    private AtCommandResult CreatePDUResponse(SMSMsgInfo smsInfo) {
        // Format of response:

        // +CMGR: <stat>,[<alpha>],<length><cr><lf><pdu>
        //        <stat> = 0 (incoming unread)
        //                 1 (incoming read)
        //                 2 (stored/unsent)
        //                 3 (stored/sent)
        //        <alpha> - string type alphanumeric representation of TP-Destination address or TP-originating address corresponding to the entry found in the phonebook
        //        <length> - length of the actual TP data unit in octets (bytes) (the SMSC address octets are not counted in the length)
        //        <pdu> - SC address followed by the TPDU in hex format

        ByteArrayOutputStream bo = new ByteArrayOutputStream(300);

        byte[] scData = PhoneNumberUtils.networkPortionToCalledPartyBCDWithLength("0000");
        bo.write(scData, 0, scData.length);
        bo.write(4);    // flags

        byte[] destData = PhoneNumberUtils.networkPortionToCalledPartyBCD(smsInfo.OriginAddress);
        int destLength = ((destData.length - 1) * 2
                - ((destData[destData.length - 1] & 0xf0) == 0xf0 ? 1 : 0));

        bo.write(destLength);
        bo.write(destData, 0, destData.length);
        bo.write(0);    // protocol ID
        bo.write(0);    // data coding scheme

        Time msgTime = new Time(Time.TIMEZONE_UTC);
        msgTime.set(smsInfo.date); // this is a UTC date!!
        msgTime.switchTimezone(Time.getCurrentTimezone()); // now its a local time

        bo.write(swapBCDForTime(msgTime.year % 100));
        bo.write(swapBCDForTime(msgTime.month + 1));
        bo.write(swapBCDForTime(msgTime.monthDay));
        bo.write(swapBCDForTime(msgTime.hour));
        bo.write(swapBCDForTime(msgTime.minute));
        if (msgTime.second > 59) msgTime.second = 59;
        bo.write(swapBCDForTime(msgTime.second));
        // tzoffset... measured in quarter hours with a plus/minus bit
        long tzOffset = (msgTime.gmtoff) / (60 * 15);
        // tzOffset is now the number of 15 minute intervals between UTC and local
        bo.write(swapBCDForTime((int) (tzOffset < 0 ? 128 - tzOffset : tzOffset)));

        if (smsInfo.body.length() >= 160)
            smsInfo.body = smsInfo.body.substring(0, 159);
        byte[] userData = {};
        try {
            userData = GsmAlphabet.stringToGsm7BitPacked(smsInfo.body, 0, false, 0, 0);
            bo.write(userData, 0, userData.length);
        }
        catch (EncodeException ex)
        {
            return new AtCommandResult(AtCommandResult.ERROR);
        }
        // the length of the PDU is bo size minus the scData length
        int pduSize = bo.size() - scData.length;

        int stat = 2; // outgoing unsent default
        if (Sms.MESSAGE_TYPE_SENT == smsInfo.type) {
            stat = 3;    // out/sent
        } else if (Sms.MESSAGE_TYPE_INBOX == smsInfo.type) {
            stat = (0 == smsInfo.read) ? 0 : 1;
        }
        return new AtCommandResult("+CMGR: " + stat + ",," + pduSize + "\r\n" +
                HexDump.toHexString(bo.toByteArray()));
    }

    /** shortcut to turn off new message indicators */
    private void TurnOffNMI() {
        InitializeNMI(0, 0, 0, 0, 1);
    }

    /** handles turning on/off new message indicator mode(s) */
    private void InitializeNMI(int mode, int mt, int bm, int ds, int bfr) {
        mNMI_mt = mt;    // only 0/1/2 supported
        mNMI_bm = bm;    // only 0 supported
        mNMI_ds = ds;    // only 0 supported
        mNMI_bfr = bfr;

        if (0 == mode) {
            // only unregister the observer if it exists to begin with
            if (0 != mNMI_mode) {
                mContext.getContentResolver().unregisterContentObserver(smsContentObserver);
                mNMI_mode = 0;
            }
        } else {
            // modes 1 thru 3 are all the same the BT connection as there isn't ever
            // any type of "reserved" mode (no DUN shares the link)

            // for the purpose of bfr, consider any message that is not "seen" to be buffered.

            mNMI_mode = mode;
            // oddly, mt=0 will result in no messages being sent, but still hook the content
            // observer for the purposes of future support of cell broadcast and/or delivery
            // reports.  Setting a non-0 mode and leaving bm/ds/mt all at zero is silly.

            Cursor sms = mContext.getContentResolver().query(Sms.CONTENT_URI,
                    new String[] {"_id", Sms.DATE},
                    Sms.TYPE + " + " + Sms.MESSAGE_TYPE_INBOX,
                    null, Sms.DATE + " desc, _id desc");
            if (null != sms) {
                int dateColumn = sms.getColumnIndex(Sms.DATE);
                mLastSeenSMSDate = 0;
                if (sms.moveToFirst()) {
                    mLastSeenSMSDate = sms.getLong(dateColumn);
                }
                sms.close();
            }

            // if bfr 0, anything marked as NOT SEEN should be notified (based on mt).
            // In theory, only the newest will be unseen, so try to optimize things
            // for that theory...  If I'm wrong, this will be out of spec.
            if (0 == mNMI_bfr)
            {
                sms = mContext.getContentResolver().query(Sms.CONTENT_URI,
                        new String[] {"_id", Sms.SEEN, Sms.TYPE},
                        /* no filter - it'll mess up the index */
                        null, null, Sms.DATE + " desc, _id desc");
                if (null != sms) {
                    int seenColumn = sms.getColumnIndex(Sms.SEEN);
                    int typeColumn = sms.getColumnIndex(Sms.TYPE);
                    int idColumn = sms.getColumnIndex("_id");

                    int curMsgIdx = sms.getCount();
                    if ((0 != curMsgIdx) && sms.moveToFirst()) {
                        do {
                            if (Sms.MESSAGE_TYPE_INBOX == sms.getLong(typeColumn)) {
                                if (0 == sms.getLong(seenColumn)) {
                                    // send notification
                                    SendNMINofitication(sms.getInt(idColumn), curMsgIdx);
                                    curMsgIdx--;
                                } else {
                                    // on the first seen message, break from the loop
                                    break;
                                }
                            }
                        } while (sms.moveToPrevious());
                    }
                    sms.close();
                }
            } // if 0 bfr

            // registering on Sms.Inbox should be more efficient than on the entire SMS db, but
            // it doesn't seem to get change notices when new SMS messages come in.
            mContext.getContentResolver().registerContentObserver(Sms.CONTENT_URI, true, smsContentObserver);
        }
    }

    /** gets a message count */
    private int getMessageVirtualCount() {
        int iRet = 0;
        Cursor smsCursor =
                mContext.getContentResolver().query(Sms.CONTENT_URI,
                        new String[] {"_id"},
                        null, null, null);
        if (null != smsCursor)
        {
            iRet = smsCursor.getCount();
            smsCursor.close();
        }
        return iRet;
    }

    /** find msg at a virtual index and populates smsInfo. Optionally marks msg as seen */
    private int getMessageAtIndex(int index, SMSMsgInfo smsInfo, boolean bMarkAsSeen) {

        int iRet = -1;

        // cursor is 0 based, but the 'index' parameter is 1 based.
        index--;

        Cursor smsCursor =
                mContext.getContentResolver().query(Sms.CONTENT_URI,
                        new String[] {"_id", Sms.BODY, Sms.DATE, Sms.ADDRESS, Sms.SERVICE_CENTER, Sms.TYPE, Sms.READ, Sms.SEEN },
                        null, null, Sms.DATE + " asc"); // in date ascending order for counting
        if (null != smsCursor) {
            if ((index < smsCursor.getCount()) && (smsCursor.moveToPosition(index))) {
                int smsID = smsCursor.getInt(smsCursor.getColumnIndex("_id"));
                smsInfo._id = smsID;
                smsInfo.body = smsCursor.getString(smsCursor.getColumnIndex(Sms.BODY));
                smsInfo.date = smsCursor.getLong(smsCursor.getColumnIndex(Sms.DATE));
                smsInfo.OriginAddress = smsCursor.getString(smsCursor.getColumnIndex(Sms.ADDRESS));
                smsInfo.SMSCAddress = smsCursor.getString(smsCursor.getColumnIndex(Sms.SERVICE_CENTER));
                smsInfo.type = smsCursor.getInt(smsCursor.getColumnIndex(Sms.TYPE));
                smsInfo.read = smsCursor.getInt(smsCursor.getColumnIndex(Sms.READ));

                //does java do ordered AND's?
                if (null != smsInfo.SMSCAddress)
                    if (smsInfo.SMSCAddress.trim().isEmpty())
                        smsInfo.SMSCAddress = null;

                iRet = 0; // success

                if (bMarkAsSeen && (0 == smsCursor.getLong(smsCursor.getColumnIndex(Sms.SEEN)))) {
                    if (DBG) Log.d(TAG, "Marking SMS record id " + smsInfo._id + " as seen.");
                    ContentValues updateSeen = new ContentValues();
                    updateSeen.put(Sms.SEEN, 1);
                    mContext.getContentResolver().update(Sms.CONTENT_URI, updateSeen,
                            "_id = " + smsInfo._id, null);
                }

            } else {
                iRet = CMS_INVALID_INDEX;
            }
            smsCursor.close();
        }
        return iRet;
    }

    /** Actually sends the unsolicited response indicating a new incoming SMS */
    private void SendNMINofitication(int _id, int index) {
        if (1 == mNMI_mt) {
            if (DBG) Log.d(TAG, "OUT: +CMTI: \"ME\"," + index);
            mHandsfree.sendURC("+CMTI: \"ME\"," + index);
        }
        // if mt > 1, this might be a +CMT response with the actual message included.
    }

    /** ContentObserver for watching the sms content db (specifically for new records */
    private class SmsContentObserverClass extends ContentObserver {
        public SmsContentObserverClass() {
            super(null);
        }
        @Override
        public void onChange(boolean selfChange) {

            // try to make things faster by first checking for new messages.  Only
            // do the full query if there are messages since mLastSeenSMSDate that are
            // also in the INBOX.  Funny how a content observer on Sms.Inbox.CONTENT_URI
            // doesn't send a notification on a new message, but this query (on the same
            // URI) will find it.
            Cursor sms = mContext.getContentResolver().query(Sms.Inbox.CONTENT_URI,
                    new String[] {"_id", Sms.DATE },
                    Sms.DATE + " > " + mLastSeenSMSDate,
                    null, null);

            int nNewCount = sms.getCount();
            if (DBG) Log.d(TAG, "content on change, new count = " + nNewCount);
            sms.close();

            if (nNewCount > 0) {
                // there is at least one new message since LastSeenSMSDate.  In order
                // to get the message index, the entire sms db has to be in a cursor
                // for the count.  (msg idx 'N' is the Nth message in a list sorted by date)

                sms = mContext.getContentResolver().query(Sms.CONTENT_URI,
                        new String[] {"_id", Sms.DATE, Sms.TYPE },
                        null, null, Sms.DATE + " desc, _id desc");

                int nCurMsgIdx = sms.getCount(); // the latest dated message has this index

                int dateColumn = sms.getColumnIndex(Sms.DATE);
                int typeColumn = sms.getColumnIndex(Sms.TYPE);
                int idColumn = sms.getColumnIndex("_id");

                // record the largest date seen while looping
                long nLatestDate = mLastSeenSMSDate;
                long nMsgDate;

                // this moveToFirst() should always succeed... but be paranoid
                if (sms.moveToFirst()) {
                    // the first seen will have the newest date stamp, so hang on to that
                    // for updating mLastSeenSMSDate later on
                    nMsgDate = nLatestDate = sms.getLong(dateColumn);

                    // walk the cursor from the newest message to the oldest (desc sort order)
                    while (nMsgDate > mLastSeenSMSDate) {
                        // only concerned about stuff in the INBOX
                        if (Sms.MESSAGE_TYPE_INBOX == sms.getLong(typeColumn)) {
                            SendNMINofitication(sms.getInt(idColumn), nCurMsgIdx);
                        }
                        // get the previous message (next cursor row)
                        if (sms.moveToPrevious()) {
                            nCurMsgIdx--;
                            // check if the date
                            nMsgDate = sms.getLong(dateColumn);
                        } else {
                            // if moveToPrevious fails, there are no earlier msgs - so
                            // a msg date of 0 actually makes sense (and results in the
                            // loop terminating.)
                            nMsgDate = 0;
                        }
                    }
                    if (nLatestDate > mLastSeenSMSDate)
                        mLastSeenSMSDate = nLatestDate;
                } // if moveToFirst
                sms.close();
            } // if newCount > 0
        }
    }
}
