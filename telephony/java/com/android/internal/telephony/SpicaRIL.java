package com.android.internal.telephony;

import java.util.ArrayList;
import java.util.Collections;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Message;
import android.os.AsyncResult;
import android.os.Parcel;
import android.os.SystemProperties;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.text.TextUtils;
import static com.android.internal.telephony.RILConstants.*;

import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.DataCallState;
import com.android.internal.telephony.OperatorInfo;
import com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;
import com.android.internal.telephony.gsm.SuppServiceNotification;
import com.android.internal.telephony.IccCardApplication;
import com.android.internal.telephony.IccCardStatus;
import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.SmsResponse;
import com.android.internal.telephony.cdma.CdmaCallWaitingNotification;
import com.android.internal.telephony.cdma.CdmaInformationRecords;

import android.util.Log;

public class SpicaRIL extends RIL implements CommandsInterface {

    private boolean mIsSamsungCdma = SystemProperties.getBoolean("ro.ril.samsung_cdma", false);

    // Is this the first radio state change?
    private boolean mInitialRadioStateChange = true;

    private int mNetworkMode;

    private String[] cidToIfname = {"","","","",""};
    private String[][] cidToAddresses;

    public SpicaRIL(Context context, int networkMode, int cdmaSubscription) {
        super(context, networkMode, cdmaSubscription);
        mNetworkMode = networkMode;
	cidToAddresses = new String[5][1];
	for (int i = 0; i < 5; i++) {
		cidToAddresses[i][0] = new String();
	}
    }

    //SAMSUNG SGS STATES
    static final int RIL_UNSOL_STK_SEND_SMS_RESULT = 11002;
    static final int RIL_UNSOL_O2_HOME_ZONE_INFO = 11007;
    static final int RIL_UNSOL_DEVICE_READY_NOTI = 11008;
    static final int RIL_UNSOL_SAMSUNG_UNKNOWN_MAGIC_REQUEST_2 = 11011;
    static final int RIL_UNSOL_HSDPA_STATE_CHANGED = 11016;
    static final int RIL_UNSOL_SAMSUNG_UNKNOWN_MAGIC_REQUEST = 11012;
    static final int RIL_REQUEST_DIAL_EMERGENCY = 10016;

    @Override
    public void
    setRadioPower(boolean on, Message result) {
        //if radio is OFF set preferred NW type and cmda subscription
        if(mInitialRadioStateChange) {
            synchronized (mStateMonitor) {
                if (!mState.isOn()) {
                    RILRequest rrPnt = RILRequest.obtain(
                        RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE, null);

                    rrPnt.mp.writeInt(1);
                    rrPnt.mp.writeInt(mNetworkMode);
                    if (RILJ_LOGD) riljLog(rrPnt.serialString() + "> "
                        + requestToString(rrPnt.mRequest) + " : " + mNetworkMode);

                    send(rrPnt);

                    RILRequest rrCs = RILRequest.obtain(
                        RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE, null);
                    rrCs.mp.writeInt(1);
                    rrCs.mp.writeInt(mCdmaSubscription);
                    if (RILJ_LOGD) riljLog(rrCs.serialString() + "> "
                        + requestToString(rrCs.mRequest) + " : " + mCdmaSubscription);
                    send(rrCs);
                }
            }
        }
        RILRequest rr
        = RILRequest.obtain(RIL_REQUEST_RADIO_POWER, result);

        //samsung crap for airplane mode
        if (on)
        {
            rr.mp.writeInt(1);
            rr.mp.writeInt(1);
        } else {
            rr.mp.writeInt(2);
            rr.mp.writeInt(0);
            rr.mp.writeInt(0);
        }
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    @Override
    public void
    deactivateDataCall(int cid, int reason, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_DEACTIVATE_DATA_CALL, result);

        rr.mp.writeInt(1);
        rr.mp.writeString(Integer.toString(cid));

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " +
                requestToString(rr.mRequest) + " " + cid + " " + reason);

        send(rr);
    }

    @Override
    protected void switchToRadioState(RadioState newState) {

        if (mInitialRadioStateChange) {
            if (newState.isOn()) {
                /* If this is our first notification, make sure the radio
                 * is powered off.  This gets the radio into a known state,
                 * since it's possible for the phone proc to have restarted
                 * (eg, if it or the runtime crashed) without the RIL
                 * and/or radio knowing.
                 */
                if (RILJ_LOGD) Log.d(LOG_TAG, "Radio ON @ init; reset to OFF");
                setRadioPower(false, null);
            } else {
                if (RILJ_LOGD) Log.d(LOG_TAG, "Radio OFF @ init");
                setRadioState(newState);
            }
            mInitialRadioStateChange = false;
        } else {
            setRadioState(newState);
        }
    }

    @Override
    protected void
    processSolicited (Parcel p) {
        int serial, error;
        boolean found = false;

        serial = p.readInt();
        error = p.readInt();

        Log.d(LOG_TAG,"Serial: "+ serial);
        Log.d(LOG_TAG,"Error: "+ error);

        RILRequest rr;

        rr = findAndRemoveRequestFromList(serial);

        if (rr == null) {
            Log.w(LOG_TAG, "Unexpected solicited response! sn: "
            + serial + " error: " + error);
            return;
        }

        Object ret = null;

        if (error == 0 || p.dataAvail() > 0) {
            // either command succeeds or command fails but with data payload
            try {
                switch (rr.mRequest) {
                    /*
                     *            cat libs/telephony/ril_commands.h \
                     *            | egrep "^ *{RIL_" \
                     *            | sed -re 's/\{([^,]+),[^,]+,([^}]+).+/case \1: ret = \2(p); break;/'
                     */
                    case RIL_REQUEST_GET_SIM_STATUS: ret =  responseIccCardStatus(p); break;
                    case RIL_REQUEST_ENTER_SIM_PIN: ret =  responseInts(p); break;
                    case RIL_REQUEST_ENTER_SIM_PUK: ret =  responseInts(p); break;
                    case RIL_REQUEST_ENTER_SIM_PIN2: ret =  responseInts(p); break;
                    case RIL_REQUEST_ENTER_SIM_PUK2: ret =  responseInts(p); break;
                    case RIL_REQUEST_CHANGE_SIM_PIN: ret =  responseInts(p); break;
                    case RIL_REQUEST_CHANGE_SIM_PIN2: ret =  responseInts(p); break;
                    case RIL_REQUEST_ENTER_NETWORK_DEPERSONALIZATION: ret =  responseInts(p); break;
                    case RIL_REQUEST_GET_CURRENT_CALLS: ret =  responseCallList(p); break;
                    case RIL_REQUEST_DIAL: ret =  responseVoid(p); break;
                    case RIL_REQUEST_GET_IMSI: ret =  responseIMSI(p); break;
                    case RIL_REQUEST_HANGUP: ret =  responseVoid(p); break;
                    case RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND: ret =  responseVoid(p); break;
                    case RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND: ret =  responseVoid(p); break;
                    case RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE: ret =  responseVoid(p); break;
                    case RIL_REQUEST_CONFERENCE: ret =  responseVoid(p); break;
                    case RIL_REQUEST_UDUB: ret =  responseVoid(p); break;
                    case RIL_REQUEST_LAST_CALL_FAIL_CAUSE: ret =  responseInts(p); break;
                    case RIL_REQUEST_SIGNAL_STRENGTH: ret =  responseSignalStrength(p); break;
                    case RIL_REQUEST_VOICE_REGISTRATION_STATE: ret =  responseStrings(p); break;
                    case RIL_REQUEST_DATA_REGISTRATION_STATE: ret =  responseStrings(p); break;
                    case RIL_REQUEST_OPERATOR: ret =  responseStrings(p); break;
                    case RIL_REQUEST_RADIO_POWER: ret =  responseVoid(p); break;
                    case RIL_REQUEST_DTMF: ret =  responseVoid(p); break;
                    case RIL_REQUEST_SEND_SMS: ret =  responseSMS(p); break;
                    case RIL_REQUEST_SEND_SMS_EXPECT_MORE: ret =  responseSMS(p); break;
                    case RIL_REQUEST_SETUP_DATA_CALL: ret =  responseSetupDataCall(p); break;
                    case RIL_REQUEST_SIM_IO: ret =  responseICC_IO(p); break;
                    case RIL_REQUEST_SEND_USSD: ret =  responseVoid(p); break;
                    case RIL_REQUEST_CANCEL_USSD: ret =  responseVoid(p); break;
                    case RIL_REQUEST_GET_CLIR: ret =  responseInts(p); break;
                    case RIL_REQUEST_SET_CLIR: ret =  responseVoid(p); break;
                    case RIL_REQUEST_QUERY_CALL_FORWARD_STATUS: ret =  responseCallForward(p); break;
                    case RIL_REQUEST_SET_CALL_FORWARD: ret =  responseVoid(p); break;
                    case RIL_REQUEST_QUERY_CALL_WAITING: ret =  responseInts(p); break;
                    case RIL_REQUEST_SET_CALL_WAITING: ret =  responseVoid(p); break;
                    case RIL_REQUEST_SMS_ACKNOWLEDGE: ret =  responseVoid(p); break;
                    case RIL_REQUEST_GET_IMEI: ret =  responseString(p); break;
                    case RIL_REQUEST_GET_IMEISV: ret =  responseString(p); break;
                    case RIL_REQUEST_ANSWER: ret =  responseVoid(p); break;
                    case RIL_REQUEST_DEACTIVATE_DATA_CALL: ret =  responseVoid(p); break;
                    case RIL_REQUEST_QUERY_FACILITY_LOCK: ret =  responseInts(p); break;
                    case RIL_REQUEST_SET_FACILITY_LOCK: ret =  responseInts(p); break;
                    case RIL_REQUEST_CHANGE_BARRING_PASSWORD: ret =  responseVoid(p); break;
                    case RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE: ret =  responseInts(p); break;
                    case RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC: ret =  responseVoid(p); break;
                    case RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL: ret =  responseVoid(p); break;
                    case RIL_REQUEST_QUERY_AVAILABLE_NETWORKS : ret =  responseOperatorInfos(p); break;
                    case RIL_REQUEST_DTMF_START: ret =  responseVoid(p); break;
                    case RIL_REQUEST_DTMF_STOP: ret =  responseVoid(p); break;
                    case RIL_REQUEST_BASEBAND_VERSION: ret =  responseString(p); break;
                    case RIL_REQUEST_SEPARATE_CONNECTION: ret =  responseVoid(p); break;
                    case RIL_REQUEST_SET_MUTE: ret =  responseVoid(p); break;
                    case RIL_REQUEST_GET_MUTE: ret =  responseInts(p); break;
                    case RIL_REQUEST_QUERY_CLIP: ret =  responseInts(p); break;
                    case RIL_REQUEST_LAST_DATA_CALL_FAIL_CAUSE: ret =  responseInts(p); break;
                    case RIL_REQUEST_DATA_CALL_LIST: ret =  responseDataCallList(p); break;
                    case RIL_REQUEST_RESET_RADIO: ret =  responseVoid(p); break;
                    case RIL_REQUEST_OEM_HOOK_RAW: ret =  responseRaw(p); break;
                    case RIL_REQUEST_OEM_HOOK_STRINGS: ret =  responseStrings(p); break;
                    case RIL_REQUEST_SCREEN_STATE: ret =  responseVoid(p); break;
                    case RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION: ret =  responseVoid(p); break;
                    case RIL_REQUEST_WRITE_SMS_TO_SIM: ret =  responseInts(p); break;
                    case RIL_REQUEST_DELETE_SMS_ON_SIM: ret =  responseVoid(p); break;
                    case RIL_REQUEST_SET_BAND_MODE: ret =  responseVoid(p); break;
                    case RIL_REQUEST_QUERY_AVAILABLE_BAND_MODE: ret =  responseInts(p); break;
                    case RIL_REQUEST_STK_GET_PROFILE: ret =  responseString(p); break;
                    case RIL_REQUEST_STK_SET_PROFILE: ret =  responseVoid(p); break;
                    case RIL_REQUEST_STK_SEND_ENVELOPE_COMMAND: ret =  responseString(p); break;
                    case RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE: ret =  responseVoid(p); break;
                    case RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM: ret =  responseInts(p); break;
                    case RIL_REQUEST_EXPLICIT_CALL_TRANSFER: ret =  responseVoid(p); break;
                    case RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE: ret =  responseVoid(p); break;
                    case RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE: ret =  responseGetPreferredNetworkType(p); break;
                    case RIL_REQUEST_GET_NEIGHBORING_CELL_IDS: ret = responseCellList(p); break;
                    case RIL_REQUEST_SET_LOCATION_UPDATES: ret =  responseVoid(p); break;
                    case RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE: ret =  responseVoid(p); break;
                    case RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE: ret =  responseVoid(p); break;
                    case RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE: ret =  responseInts(p); break;
                    case RIL_REQUEST_SET_TTY_MODE: ret =  responseVoid(p); break;
                    case RIL_REQUEST_QUERY_TTY_MODE: ret =  responseInts(p); break;
                    case RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE: ret =  responseVoid(p); break;
                    case RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE: ret =  responseInts(p); break;
                    case RIL_REQUEST_CDMA_FLASH: ret =  responseVoid(p); break;
                    case RIL_REQUEST_CDMA_BURST_DTMF: ret =  responseVoid(p); break;
                    case RIL_REQUEST_CDMA_SEND_SMS: ret =  responseSMS(p); break;
                    case RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE: ret =  responseVoid(p); break;
                    case RIL_REQUEST_GSM_GET_BROADCAST_CONFIG: ret =  responseGmsBroadcastConfig(p); break;
                    case RIL_REQUEST_GSM_SET_BROADCAST_CONFIG: ret =  responseVoid(p); break;
                    case RIL_REQUEST_GSM_BROADCAST_ACTIVATION: ret =  responseVoid(p); break;
                    case RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG: ret =  responseCdmaBroadcastConfig(p); break;
                    case RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG: ret =  responseVoid(p); break;
                    case RIL_REQUEST_CDMA_BROADCAST_ACTIVATION: ret =  responseVoid(p); break;
                    case RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY: ret =  responseVoid(p); break;
                    case RIL_REQUEST_CDMA_SUBSCRIPTION: ret =  responseStrings(p); break;
                    case RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM: ret =  responseInts(p); break;
                    case RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM: ret =  responseVoid(p); break;
                    case RIL_REQUEST_DEVICE_IDENTITY: ret =  responseStrings(p); break;
                    case RIL_REQUEST_GET_SMSC_ADDRESS: ret = responseString(p); break;
                    case RIL_REQUEST_SET_SMSC_ADDRESS: ret = responseVoid(p); break;
                    case RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE: ret = responseVoid(p); break;
                    case RIL_REQUEST_REPORT_SMS_MEMORY_STATUS: ret = responseVoid(p); break;
                    case RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING: ret = responseVoid(p); break;
                    case RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE: ret =  responseInts(p); break;
                    case RIL_REQUEST_ISIM_AUTHENTICATION: ret =  responseString(p); break;
                    default:
                        throw new RuntimeException("Unrecognized solicited response: " + rr.mRequest);
                        //break;
                }
            } catch (Throwable tr) {
                // Exceptions here usually mean invalid RIL responses

                Log.w(LOG_TAG, rr.serialString() + "< "
                + requestToString(rr.mRequest)
                + " exception, possible invalid RIL response", tr);

                if (rr.mResult != null) {
                    AsyncResult.forMessage(rr.mResult, null, tr);
                    rr.mResult.sendToTarget();
                }
                rr.release();
                return;
            }
        }

        if (error != 0) {
            rr.onError(error, ret);
            rr.release();
            return;
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "< " + requestToString(rr.mRequest)
            + " " + retToString(rr.mRequest, ret));

        if (rr.mResult != null) {
            AsyncResult.forMessage(rr.mResult, ret, null);
            rr.mResult.sendToTarget();
        }

        rr.release();
    }

    @Override
    protected void
    processUnsolicited (Parcel p) {
        int response;
        Object ret;

        response = p.readInt();

        try {switch(response) {
            /*
             *                cat libs/telephony/ril_unsol_commands.h \
             *                | egrep "^ *{RIL_" \
             *                | sed -re 's/\{([^,]+),[^,]+,([^}]+).+/case \1: \2(rr, p); break;/'
             */

            case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED: ret =  responseVoid(p); break;
            case RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED: ret =  responseVoid(p); break;
            case RIL_UNSOL_RESPONSE_VOICE_NETWORK_STATE_CHANGED: ret =  responseVoid(p); break;
            case RIL_UNSOL_RESPONSE_NEW_SMS: ret =  responseString(p); break;
            case RIL_UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT: ret =  responseString(p); break;
            case RIL_UNSOL_RESPONSE_NEW_SMS_ON_SIM: ret =  responseInts(p); break;
            case RIL_UNSOL_ON_USSD: ret =  responseStrings(p); break;
            case RIL_UNSOL_NITZ_TIME_RECEIVED: ret =  responseString(p); break;
            case RIL_UNSOL_SIGNAL_STRENGTH: ret = responseSignalStrength(p); break;
            case RIL_UNSOL_DATA_CALL_LIST_CHANGED: ret = responseDataCallList(p);break;
            case RIL_UNSOL_SUPP_SVC_NOTIFICATION: ret = responseSuppServiceNotification(p); break;
            case RIL_UNSOL_STK_SESSION_END: ret = responseVoid(p); break;
            case RIL_UNSOL_STK_PROACTIVE_COMMAND: ret = responseString(p); break;
            case RIL_UNSOL_STK_EVENT_NOTIFY: ret = responseString(p); break;
            case RIL_UNSOL_STK_CALL_SETUP: ret = responseInts(p); break;
            case RIL_UNSOL_SIM_SMS_STORAGE_FULL: ret =  responseVoid(p); break;
            case RIL_UNSOL_SIM_REFRESH: ret =  responseInts(p); break;
            case RIL_UNSOL_CALL_RING: ret =  responseCallRing(p); break;
            case RIL_UNSOL_RESTRICTED_STATE_CHANGED: ret = responseInts(p); break;
            case RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED:  ret =  responseVoid(p); break;
            case RIL_UNSOL_RESPONSE_CDMA_NEW_SMS:  ret =  responseCdmaSms(p); break;
            case RIL_UNSOL_RESPONSE_NEW_BROADCAST_SMS:  ret =  responseString(p); break;
            case RIL_UNSOL_CDMA_RUIM_SMS_STORAGE_FULL:  ret =  responseVoid(p); break;
            case RIL_UNSOL_ENTER_EMERGENCY_CALLBACK_MODE: ret = responseVoid(p); break;
            case RIL_UNSOL_CDMA_CALL_WAITING: ret = responseCdmaCallWaiting(p); break;
            case RIL_UNSOL_CDMA_OTA_PROVISION_STATUS: ret = responseInts(p); break;
            case RIL_UNSOL_CDMA_INFO_REC: ret = responseCdmaInformationRecord(p); break;
            case RIL_UNSOL_OEM_HOOK_RAW: ret = responseRaw(p); break;
            case RIL_UNSOL_RINGBACK_TONE: ret = responseInts(p); break;
            case RIL_UNSOL_RESEND_INCALL_MUTE: ret = responseVoid(p); break;
            case RIL_UNSOL_HSDPA_STATE_CHANGED: ret = responseVoid(p); break;
            case RIL_UNSOL_CDMA_SUBSCRIPTION_SOURCE_CHANGED: ret = responseInts(p); break;
            case RIL_UNSOl_CDMA_PRL_CHANGED: ret = responseInts(p); break;
            case RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE: ret = responseVoid(p); break;
            case RIL_UNSOL_RIL_CONNECTED: ret = responseInts(p); break;

            //fixing anoying Exceptions caused by the new Samsung states
            //FIXME figure out what the states mean an what data is in the parcel

            case RIL_UNSOL_O2_HOME_ZONE_INFO: ret = responseVoid(p); break;
            case RIL_UNSOL_SAMSUNG_UNKNOWN_MAGIC_REQUEST: ret = responseVoid(p); break;
            case RIL_UNSOL_STK_SEND_SMS_RESULT: ret = responseVoid(p); break;
            case RIL_UNSOL_DEVICE_READY_NOTI: ret = responseVoid(p); break;
            case RIL_UNSOL_SAMSUNG_UNKNOWN_MAGIC_REQUEST_2: ret = responseVoid(p); break;

            default:
                throw new RuntimeException("Unrecognized unsol response: " + response);
                //break; (implied)
        }} catch (Throwable tr) {
            Log.e(LOG_TAG, "Exception processing unsol response: " + response +
            "Exception:" + tr.toString());
            return;
        }

        switch(response) {
            case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED:
                /* has bonus radio state int */
                RadioState newState = getRadioStateFromInt(p.readInt());
                if (RILJ_LOGD) unsljLogMore(response, newState.toString());

                switchToRadioState(newState);
            break;
            case RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED:
                if (RILJ_LOGD) unsljLog(response);

                mCallStateRegistrants
                    .notifyRegistrants(new AsyncResult(null, null, null));
            break;
            case RIL_UNSOL_RESPONSE_VOICE_NETWORK_STATE_CHANGED:
                if (RILJ_LOGD) unsljLog(response);

                mVoiceNetworkStateRegistrants
                    .notifyRegistrants(new AsyncResult(null, null, null));
            break;
            case RIL_UNSOL_RESPONSE_NEW_SMS: {
                if (RILJ_LOGD) unsljLog(response);

                // FIXME this should move up a layer
                String a[] = new String[2];

                a[1] = (String)ret;

                SmsMessage sms;

                sms = SmsMessage.newFromCMT(a);
                if (mGsmSmsRegistrant != null) {
                    mGsmSmsRegistrant
                        .notifyRegistrant(new AsyncResult(null, sms, null));
                }
            break;
            }
            case RIL_UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mSmsStatusRegistrant != null) {
                    mSmsStatusRegistrant.notifyRegistrant(
                            new AsyncResult(null, ret, null));
                }
            break;
            case RIL_UNSOL_RESPONSE_NEW_SMS_ON_SIM:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                int[] smsIndex = (int[])ret;

                if(smsIndex.length == 1) {
                    if (mSmsOnSimRegistrant != null) {
                        mSmsOnSimRegistrant.
                                notifyRegistrant(new AsyncResult(null, smsIndex, null));
                    }
                } else {
                    if (RILJ_LOGD) riljLog(" NEW_SMS_ON_SIM ERROR with wrong length "
                            + smsIndex.length);
                }
            break;
            case RIL_UNSOL_ON_USSD:
                String[] resp = (String[])ret;

                if (resp.length < 2) {
                    resp = new String[2];
                    resp[0] = ((String[])ret)[0];
                    resp[1] = null;
                }
                if (RILJ_LOGD) unsljLogMore(response, resp[0]);
                if (mUSSDRegistrant != null) {
                    mUSSDRegistrant.notifyRegistrant(
                        new AsyncResult (null, resp, null));
                }
            break;
            case RIL_UNSOL_NITZ_TIME_RECEIVED:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                // has bonus long containing milliseconds since boot that the NITZ
                // time was received
                long nitzReceiveTime = p.readLong();

                Object[] result = new Object[2];

                result[0] = ret;
                result[1] = Long.valueOf(nitzReceiveTime);

                if (mNITZTimeRegistrant != null) {

                    mNITZTimeRegistrant
                        .notifyRegistrant(new AsyncResult (null, result, null));
                } else {
                    // in case NITZ time registrant isnt registered yet
                    mLastNITZTimeInfo = result;
                }
            break;

            case RIL_UNSOL_SIGNAL_STRENGTH:
                // Note this is set to "verbose" because it happens
                // frequently
                if (RILJ_LOGV) unsljLogvRet(response, ret);

                if (mSignalStrengthRegistrant != null) {
                    mSignalStrengthRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
            break;
            case RIL_UNSOL_DATA_CALL_LIST_CHANGED:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                mDataNetworkStateRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
            break;

            case RIL_UNSOL_SUPP_SVC_NOTIFICATION:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mSsnRegistrant != null) {
                    mSsnRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_STK_SESSION_END:
                if (RILJ_LOGD) unsljLog(response);

                if (mCatSessionEndRegistrant != null) {
                    mCatSessionEndRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_STK_PROACTIVE_COMMAND:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mCatProCmdRegistrant != null) {
                    mCatProCmdRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_STK_EVENT_NOTIFY:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mCatEventRegistrant != null) {
                    mCatEventRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_STK_CALL_SETUP:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mCatCallSetUpRegistrant != null) {
                    mCatCallSetUpRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_SIM_SMS_STORAGE_FULL:
                if (RILJ_LOGD) unsljLog(response);

                if (mIccSmsFullRegistrant != null) {
                    mIccSmsFullRegistrant.notifyRegistrant();
                }
                break;

            case RIL_UNSOL_SIM_REFRESH:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mIccRefreshRegistrants != null) {
                    mIccRefreshRegistrants.notifyRegistrants(
                            new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_CALL_RING:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mRingRegistrant != null) {
                    mRingRegistrant.notifyRegistrant(
                            new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_RESTRICTED_STATE_CHANGED:
                if (RILJ_LOGD) unsljLogvRet(response, ret);
                if (mRestrictedStateRegistrant != null) {
                    mRestrictedStateRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED:
                if (RILJ_LOGD) unsljLog(response);

                if (mIccStatusChangedRegistrants != null) {
                    mIccStatusChangedRegistrants.notifyRegistrants();
                }
                break;

            case RIL_UNSOL_RESPONSE_CDMA_NEW_SMS:
                if (RILJ_LOGD) unsljLog(response);

                SmsMessage sms = (SmsMessage) ret;

                if (mCdmaSmsRegistrant != null) {
                    mCdmaSmsRegistrant
                        .notifyRegistrant(new AsyncResult(null, sms, null));
                }
                break;

            case RIL_UNSOL_RESPONSE_NEW_BROADCAST_SMS:
                if (RILJ_LOGD) unsljLog(response);

                if (mGsmBroadcastSmsRegistrant != null) {
                    mGsmBroadcastSmsRegistrant
                        .notifyRegistrant(new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_CDMA_RUIM_SMS_STORAGE_FULL:
                if (RILJ_LOGD) unsljLog(response);

                if (mIccSmsFullRegistrant != null) {
                    mIccSmsFullRegistrant.notifyRegistrant();
                }
                break;

            case RIL_UNSOL_ENTER_EMERGENCY_CALLBACK_MODE:
                if (RILJ_LOGD) unsljLog(response);

                if (mEmergencyCallbackModeRegistrant != null) {
                    mEmergencyCallbackModeRegistrant.notifyRegistrant();
                }
                break;

            case RIL_UNSOL_CDMA_CALL_WAITING:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mCallWaitingInfoRegistrants != null) {
                    mCallWaitingInfoRegistrants.notifyRegistrants(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_CDMA_OTA_PROVISION_STATUS:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mOtaProvisionRegistrants != null) {
                    mOtaProvisionRegistrants.notifyRegistrants(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_CDMA_INFO_REC:
                ArrayList<CdmaInformationRecords> listInfoRecs;

                try {
                    listInfoRecs = (ArrayList<CdmaInformationRecords>)ret;
                } catch (ClassCastException e) {
                    Log.e(LOG_TAG, "Unexpected exception casting to listInfoRecs", e);
                    break;
                }

                for (CdmaInformationRecords rec : listInfoRecs) {
                    if (RILJ_LOGD) unsljLogRet(response, rec);
                    notifyRegistrantsCdmaInfoRec(rec);
                }
                break;

            case RIL_UNSOL_OEM_HOOK_RAW:
                if (RILJ_LOGD) unsljLogvRet(response, IccUtils.bytesToHexString((byte[])ret));
                if (mUnsolOemHookRawRegistrant != null) {
                    mUnsolOemHookRawRegistrant.notifyRegistrant(new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_RINGBACK_TONE:
                if (RILJ_LOGD) unsljLogvRet(response, ret);
                if (mRingbackToneRegistrants != null) {
                    boolean playtone = (((int[])ret)[0] == 1);
                    mRingbackToneRegistrants.notifyRegistrants(
                                        new AsyncResult (null, playtone, null));
                }
                break;

            case RIL_UNSOL_RESEND_INCALL_MUTE:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mResendIncallMuteRegistrants != null) {
                    mResendIncallMuteRegistrants.notifyRegistrants(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_CDMA_SUBSCRIPTION_SOURCE_CHANGED:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mCdmaSubscriptionChangedRegistrants != null) {
                    mCdmaSubscriptionChangedRegistrants.notifyRegistrants(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOl_CDMA_PRL_CHANGED:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mCdmaPrlChangedRegistrants != null) {
                    mCdmaPrlChangedRegistrants.notifyRegistrants(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mExitEmergencyCallbackModeRegistrants != null) {
                    mExitEmergencyCallbackModeRegistrants.notifyRegistrants(
                                        new AsyncResult (null, null, null));
                }
                break;

            case RIL_UNSOL_RIL_CONNECTED: {
                if (RILJ_LOGD) unsljLogRet(response, ret);

                // Initial conditions
                setRadioPower(false, null);
                setPreferredNetworkType(mPreferredNetworkType, null);
                setCdmaSubscriptionSource(mCdmaSubscription, null);
                notifyRegistrantsRilConnectionChanged(((int[])ret)[0]);
                break;
            }
        }
    }

    protected Object
    responseIMSI(Parcel p) {
        String response;

        response = p.readString();
        if (response.length() > 8)
            response = response.substring(0, 8);

        return response;
    }

    @Override
    protected Object
    responseOperatorInfos(Parcel p) {
        String strings[] = (String [])responseStrings(p);
        ArrayList<OperatorInfo> ret;

        if (strings.length % 5 != 0) {
            throw new RuntimeException(
                "RIL_REQUEST_QUERY_AVAILABLE_NETWORKS: invalid response. Got "
                + strings.length + " strings, expected multible of 5");
        }

        ret = new ArrayList<OperatorInfo>(strings.length / 5);

        for (int i = 0 ; i < strings.length ; i += 5) {
            ret.add (
                new OperatorInfo(
                    strings[i+0],
                    strings[i+1],
                    strings[i+2],
                    strings[i+3]));
        }

        return ret;
    }

    @Override
    protected Object
    responseCallList(Parcel p) {
        int num;
        int voiceSettings;
        ArrayList<DriverCall> response;
        DriverCall dc;

        num = p.readInt();
        response = new ArrayList<DriverCall>(num);

        for (int i = 0 ; i < num ; i++) {
            dc = new DriverCall();

            dc.state = DriverCall.stateFromCLCC(p.readInt());
            dc.index = p.readInt();
            dc.TOA = p.readInt();
            dc.isMpty = (0 != p.readInt());
            dc.isMT = (0 != p.readInt());
            dc.als = p.readInt();
            voiceSettings = p.readInt();
            dc.isVoice = (0 == voiceSettings) ? false : true;
            dc.isVoicePrivacy = (0 != p.readInt());
            dc.number = p.readString();
            int np = p.readInt();
            dc.numberPresentation = DriverCall.presentationFromCLIP(np);
            dc.name = p.readString();
            dc.namePresentation = p.readInt();

            // Make sure there's a leading + on addresses with a TOA of 145
            dc.number = PhoneNumberUtils.stringFromStringAndTOA(dc.number, dc.TOA);

            response.add(dc);

            if (dc.isVoicePrivacy) {
                mVoicePrivacyOnRegistrants.notifyRegistrants();
                Log.d(LOG_TAG, "InCall VoicePrivacy is enabled");
            } else {
                mVoicePrivacyOffRegistrants.notifyRegistrants();
                Log.d(LOG_TAG, "InCall VoicePrivacy is disabled");
            }
        }

        Collections.sort(response);

        return response;
    }

    @Override
    protected Object
    responseSignalStrength(Parcel p) {
        int numInts = 12;
        int response[];

        /* TODO: Add SignalStrength class to match RIL_SignalStrength */
        response = new int[numInts];
        for (int i = 0 ; i < 7 ; i++) {
            response[i] = p.readInt();
        }
        for (int i = 7 ; i < numInts ; i++) {
            response[i] = 0;
        }

        return response;
    }

    @Override
    protected Object
    responseCdmaCallWaiting(Parcel p) {
        CdmaCallWaitingNotification notification = new CdmaCallWaitingNotification();

        notification.number = p.readString();
        notification.numberPresentation = notification.presentationFromCLIP(p.readInt());
        notification.name = p.readString();
        notification.namePresentation = notification.numberPresentation;
        notification.isPresent = p.readInt();
        notification.signalType = p.readInt();
        notification.alertPitch = p.readInt();
        notification.signal = p.readInt();
        notification.numberType = 0;
        notification.numberPlan = 0;

        return notification;
    }

    @Override
    protected Object
    responseIccCardStatus(Parcel p) {
        IccCardApplication ca;

        IccCardStatus status = new IccCardStatus();
        status.setCardState(p.readInt());
        status.setUniversalPinState(p.readInt());
        status.setGsmUmtsSubscriptionAppIndex(p.readInt());
        status.setCdmaSubscriptionAppIndex(p.readInt());
        status.setImsSubscriptionAppIndex(0);
        int numApplications = p.readInt();

        // limit to maximum allowed applications
        if (numApplications > IccCardStatus.CARD_MAX_APPS) {
            numApplications = IccCardStatus.CARD_MAX_APPS;
        }
        status.setNumApplications(numApplications);

        for (int i = 0 ; i < numApplications ; i++) {
            ca = new IccCardApplication();
            ca.app_type       = ca.AppTypeFromRILInt(p.readInt());
            ca.app_state      = ca.AppStateFromRILInt(p.readInt());
            ca.perso_substate = ca.PersoSubstateFromRILInt(p.readInt());
            ca.aid            = p.readString();
            ca.app_label      = p.readString();
            ca.pin1_replaced  = p.readInt();
            ca.pin1           = ca.PinStateFromRILInt(p.readInt());
            ca.pin2           = ca.PinStateFromRILInt(p.readInt());
            status.addApplication(ca);
        }
        return status;
    }

    @Override
    protected Object
    responseSetupDataCall(Parcel p) {
        String[] response = (String [])responseStrings(p);
        if (RILJ_LOGV) riljLog("responseSetupDataCall");

        if (response.length < 2)
            throw new RuntimeException(
                        "RIL_REQUEST_SETUP_DATA_CALL invalid response legth < 2");

        DataCallState dataCall = new DataCallState();
        dataCall.version = 2;
        dataCall.cid = Integer.parseInt(response[0]);

        if (response.length > 2) {
//            String prefix = "net." + response[1] + ".";
            String[] ipAddress = { response[2] };
//            String[] gateway = new String[1];
//            String[] dnsServers = new String[2];

//            gateway[0] = SystemProperties.get(prefix + "gw");
//            dnsServers[0] = SystemProperties.get(prefix + "dns1");
//            dnsServers[1] = SystemProperties.get(prefix + "dns2");

            dataCall.ifname = response[1];
            dataCall.addresses = ipAddress;
//            dataCall.gateways = gateway;
//            dataCall.dnses = dnsServers;

            if (dataCall.cid < cidToIfname.length) {
                cidToIfname[dataCall.cid] = dataCall.ifname;
                cidToAddresses[dataCall.cid] = dataCall.addresses;
            }
        }

        return dataCall;
    }

    @Override
    protected Object
    responseDataCallList(Parcel p) {
        int num;
        ArrayList<DataCallState> response;

        num = p.readInt();
        response = new ArrayList<DataCallState>(num);

        for (int i = 0; i < num; i++) {
            DataCallState dataCall = new DataCallState();

            dataCall.version = 2;
            dataCall.cid = p.readInt();
            dataCall.active = p.readInt();
            dataCall.type = p.readString();
            String apn = p.readString();
            String addresses = p.readString();
            if (!TextUtils.isEmpty(addresses)) {
                dataCall.addresses = addresses.split(" ");
            } else if (dataCall.cid < cidToIfname.length) {
                dataCall.addresses = cidToAddresses[dataCall.cid];
            }
            if (dataCall.cid < cidToIfname.length) {
                dataCall.ifname = cidToIfname[dataCall.cid];
            }

            response.add(dataCall);
        }

        return response;
    }

    @Override
    public void
    setNetworkSelectionModeAutomatic(Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC,
                                    response);

        Message DeactivateDataCallMessage = null;
        for(int DeactivateDataCallCid = 0; DeactivateDataCallCid < 5; DeactivateDataCallCid++) {
            deactivateDataCall(DeactivateDataCallCid, 0, DeactivateDataCallMessage);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    setNetworkSelectionModeManual(String operatorNumeric, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL,
                                    response);

        Message DeactivateDataCallMessage = null;
        for(int DeactivateDataCallCid = 0; DeactivateDataCallCid < 5; DeactivateDataCallCid++) {
            deactivateDataCall(DeactivateDataCallCid, 0, DeactivateDataCallMessage);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + operatorNumeric);

        rr.mp.writeString(operatorNumeric);

        send(rr);
    }

    @Override
    public void
    getAvailableNetworks(Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_QUERY_AVAILABLE_NETWORKS,
                                    response);

        Message DeactivateDataCallMessage = null;
        for(int DeactivateDataCallCid = 0; DeactivateDataCallCid < 5; DeactivateDataCallCid++) {
            deactivateDataCall(DeactivateDataCallCid, 0, DeactivateDataCallMessage);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override public void
    supplyIccPinForApp(String pin, String aid, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ENTER_SIM_PIN, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mp.writeInt(1);
        rr.mp.writeString(pin);

        send(rr);
    }

    @Override public void
    supplyIccPukForApp(String puk, String newPin, String aid, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ENTER_SIM_PUK, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mp.writeInt(2);
        rr.mp.writeString(puk);
        rr.mp.writeString(newPin);

        send(rr);
    }

    @Override public void
    supplyIccPin2ForApp(String pin, String aid, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ENTER_SIM_PIN2, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mp.writeInt(1);
        rr.mp.writeString(pin);

        send(rr);
    }

    @Override public void
    supplyIccPuk2ForApp(String puk, String newPin2, String aid, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ENTER_SIM_PUK2, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mp.writeInt(2);
        rr.mp.writeString(puk);
        rr.mp.writeString(newPin2);

        send(rr);
    }

    @Override public void
    changeIccPinForApp(String oldPin, String newPin, String aid, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CHANGE_SIM_PIN, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mp.writeInt(2);
        rr.mp.writeString(oldPin);
        rr.mp.writeString(newPin);

        send(rr);
    }

    @Override public void
    changeIccPin2ForApp(String oldPin2, String newPin2, String aid, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CHANGE_SIM_PIN2, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mp.writeInt(2);
        rr.mp.writeString(oldPin2);
        rr.mp.writeString(newPin2);

        send(rr);
    }

    @Override
    public void
    queryFacilityLockForApp(String facility, String password, int serviceClass, String appId,
                            Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_QUERY_FACILITY_LOCK, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        // count strings
        rr.mp.writeInt(3);

        rr.mp.writeString(facility);
        rr.mp.writeString(password);

        rr.mp.writeString(Integer.toString(serviceClass));

        send(rr);
    }

    @Override
    public void
    setFacilityLockForApp(String facility, boolean lockState, String password,
                        int serviceClass, String appId, Message response) {
        String lockString;
         RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_FACILITY_LOCK, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        // count strings
        rr.mp.writeInt(4);

        rr.mp.writeString(facility);
        lockString = (lockState)?"1":"0";
        rr.mp.writeString(lockString);
        rr.mp.writeString(password);
        rr.mp.writeString(Integer.toString(serviceClass));

        send(rr);

    }

    @Override
    public void setPreferredNetworkType(int networkType , Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE, response);

        rr.mp.writeInt(1);
        rr.mp.writeInt(networkType);

        mPreferredNetworkType = networkType;

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " : " + networkType);

        send(rr);
    }

}
