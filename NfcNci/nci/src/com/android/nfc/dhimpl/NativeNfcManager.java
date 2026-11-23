/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.nfc.dhimpl;

import static com.android.nfc.NfcStatsLog.NFC_PROPRIETARY_CAPABILITIES_REPORTED__PASSIVE_OBSERVE_MODE__MODE_UNKNOWN;
import static com.android.nfc.NfcStatsLog.NFC_PROPRIETARY_CAPABILITIES_REPORTED__PASSIVE_OBSERVE_MODE__SUPPORT_WITHOUT_RF_DEACTIVATION;
import static com.android.nfc.NfcStatsLog.NFC_PROPRIETARY_CAPABILITIES_REPORTED__PASSIVE_OBSERVE_MODE__SUPPORT_WITH_RF_DEACTIVATION;

import android.content.Context;
import android.nfc.cardemulation.PollingFrame;
import android.nfc.tech.Ndef;
import android.nfc.tech.TagTechnology;
import android.os.Bundle;
import android.os.Trace;
import android.sysprop.NfcProperties;
import android.util.Log;

import com.android.nfc.DeviceHost;
import com.android.nfc.ExitFrame;
import com.android.nfc.NfcDiscoveryParameters;
import com.android.nfc.NfcProprietaryCaps;
import com.android.nfc.NfcService;
import com.android.nfc.NfcStatsLog;
import com.android.nfc.NfcVendorNciResponse;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;

/** Native interface to the NFC Manager functions */
public class NativeNfcManager implements DeviceHost {
    private static final String TAG = "NativeNfcManager";
    static final String PREF = "NciDeviceHost";

    static final String DRIVER_NAME = "android-nci";

    /* Native structure */
    private long mNative;

    private int mIsoDepMaxTransceiveLength;
    private final DeviceHostListener mListener;
    private final NativeT4tNfceeManager mT4tNfceeMgr;
    private final Context mContext;

    private final Object mLock = new Object();
    private final HashMap<Integer, byte[]> mT3tIdentifiers = new HashMap<Integer, byte[]>();
    private NfcProprietaryCaps mProprietaryCaps = null;
    private static final int MIN_POLLING_FRAME_TLV_SIZE = 5;
    private static final int TAG_FIELD_CHANGE = 0;
    private static final int TAG_NFC_A = 1;
    private static final int TAG_NFC_B = 2;
    private static final int TAG_NFC_F = 3;
    private static final int TAG_NFC_UNKNOWN = 7;
    private static final int NCI_HEADER_MIN_LEN = 3;
    private static final int NCI_GID_INDEX = 0;
    private static final int NCI_OID_INDEX = 1;
    private static final int OP_CODE_INDEX = 3;

    private static final int OBSERVE_MODE_SUSPENDED_FRAME_TYPE_A = 0x00;
    private static final int OBSERVE_MODE_SUSPENDED_FRAME_TYPE_B = 0x01;

    private void loadLibrary() {
        System.loadLibrary("nfc_nci_jni");
    }

    private static NativeNfcManager sInstance;

    public static NativeNfcManager getInstance() {
        if (sInstance == null) throw new IllegalStateException("NativeNfcManager instance null");
        return sInstance;
    }

    public NativeNfcManager(Context context, DeviceHostListener listener) {
        mListener = listener;
        loadLibrary();
        initializeNativeStructure();
        mContext = context;
        mT4tNfceeMgr = new NativeT4tNfceeManager();
        sInstance = this;
    }

    public native boolean initializeNativeStructure();

    private native boolean doDownload();

    @Override
    public boolean checkFirmware() {
        return doDownload();
    }

    private native boolean doInitialize();

    private native int getIsoDepMaxTransceiveLength();

    @Override
    public boolean initialize() {
        boolean ret = doInitialize();
        if (ret && isProprietaryGetCapsSupported()) {
            mProprietaryCaps = NfcProprietaryCaps.createFromByteArray(getProprietaryCaps());
            Log.i(TAG, "initialize: mProprietaryCaps: " + mProprietaryCaps);
            logProprietaryCaps(mProprietaryCaps);
        }
        mIsoDepMaxTransceiveLength = getIsoDepMaxTransceiveLength();
        return ret;
    }

    boolean isObserveModeSupportedWithoutRfDeactivation() {
        if (!com.android.nfc.flags.Flags.observeModeWithoutRf()) {
            return false;
        }
        return mProprietaryCaps != null &&
                mProprietaryCaps.getPassiveObserveMode() ==
                        NfcProprietaryCaps.PassiveObserveMode.SUPPORT_WITHOUT_RF_DEACTIVATION;
    }

    private native void doSetPartialInitMode(int mode);

    @Override
    public void setPartialInitMode(int mode) {
        doSetPartialInitMode(mode);
    }

    private native void doEnableDtaMode();

    @Override
    public void enableDtaMode() {
        doEnableDtaMode();
    }

    private native void doDisableDtaMode();

    @Override
    public void disableDtaMode() {
        Log.d(TAG, "disableDtaMode:");
        doDisableDtaMode();
    }

    private native void doFactoryReset();

    @Override
    public void factoryReset() {
        doFactoryReset();
    }

    @Override
    public boolean isPowerSavingModeSupported() {
        return mProprietaryCaps != null && mProprietaryCaps.isPowerSavingModeSupported();
    }

    private native boolean doSetPowerSavingMode(boolean flag);

    @Override
    public boolean setPowerSavingMode(boolean flag) {
        return doSetPowerSavingMode(flag);
    }

    private native void doShutdown();

    @Override
    public void shutdown() {
        doShutdown();
    }

    private native boolean doDeinitialize();

    @Override
    public boolean deinitialize() {
        return doDeinitialize();
    }

    @Override
    public String getName() {
        return DRIVER_NAME;
    }

    @Override
    public native boolean sendRawFrame(byte[] data);

    @Override
    public native boolean routeAid(byte[] aid, int route, int aidInfo, int power);

    @Override
    public native boolean unrouteAid(byte[] aid);

    @Override
    public native int commitRouting();

    public native int doRegisterT3tIdentifier(byte[] t3tIdentifier);

    /**
     * Injects a NTF to the HAL.
     *
     * This is only used for testing.
     */
    public native void injectNtf(byte[] data);

    public boolean isProprietaryGetCapsSupported() {
        return mContext.getResources()
                .getBoolean(com.android.nfc.R.bool.nfc_proprietary_getcaps_supported)
                && NfcProperties.get_caps_supported().orElse(true);
    }

    @Override
    public boolean isObserveModeSupported() {
        // Check if the device overlay and HAL capabilities indicate that observe
        // mode is supported.
        if (!mContext.getResources().getBoolean(
                com.android.nfc.R.bool.nfc_observe_mode_supported)) {
            return false;
        }
        if (!NfcProperties.observe_mode_supported().orElse(true)) {
            return false;
        }
        if (com.android.nfc.flags.Flags.observeModeWithoutRf()) {
            if (isProprietaryGetCapsSupported()) {
                return isObserveModeSupportedWithoutRfDeactivation();
            }
            return false;
        } else {
            if (isProprietaryGetCapsSupported()) {
                return isObserveModeSupportedCaps(mProprietaryCaps);
            }
            return true;
        }
    }

    @Override
    public boolean isFirmwareExitFramesSupported() {
        if (!com.android.nfc.flags.Flags.exitFrames()) {
            return false;
        }
        if (isProprietaryGetCapsSupported()) {
            return mProprietaryCaps != null
                    && mProprietaryCaps.isAutotransactPollingLoopFilterSupported()
                    && mProprietaryCaps.getNumberOfExitFramesSupported() > 0;
        }
        return false;
    }

    @Override
    public native boolean setObserveMode(boolean enabled);

    @Override
    public native boolean isObserveModeEnabled();

    @Override
    public int getNumberOfFirmwareExitFramesSupported() {
        return mProprietaryCaps != null ? mProprietaryCaps.getNumberOfExitFramesSupported() : -1;
    }

    @Override
    public native boolean setFirmwareExitFrameTable(ExitFrame[] exitFrames, byte[] timeoutMs);

    @Override
    public int   getT4TNfceePowerState() {
        return mT4tNfceeMgr.getT4TNfceePowerState();
    }

    @Override
    public int getNdefNfceeRouteId() {
        return mT4tNfceeMgr.getNdefNfceeRouteId();
    }

    @Override
    public boolean isNdefNfceefeatureEnabled() {
        return mT4tNfceeMgr.isNdefNfceefeatureEnabled();
    }

    @Override
    public byte[] getT4tNfceeAid() {
        return mT4tNfceeMgr.getT4tNfceeAid();
    }

    @Override
    public int doWriteData(byte[] fileId, byte[] data) {
        return mT4tNfceeMgr.doWriteData(fileId, data);
    }

    @Override
    public byte[] doReadData(byte[] fileId) {
        return mT4tNfceeMgr.doReadData(fileId);
    }

    @Override
    public boolean doClearNdefData() {
        return mT4tNfceeMgr.doClearNdefData();
    }

    @Override
    public boolean isNdefOperationOngoing() {
        return mT4tNfceeMgr.isNdefOperationOngoing();
    }

    @Override
    public boolean isNdefNfceeEmulationSupported() {
        return mT4tNfceeMgr.isNdefNfceeEmulationSupported();
    }

    @Override
    public void registerT3tIdentifier(byte[] t3tIdentifier) {
        synchronized (mLock) {
            int handle = doRegisterT3tIdentifier(t3tIdentifier);
            if (handle != 0xffff) {
                mT3tIdentifiers.put(Integer.valueOf(handle), t3tIdentifier);
            }
        }
    }

    public native void doDeregisterT3tIdentifier(int handle);

    @Override
    public void deregisterT3tIdentifier(byte[] t3tIdentifier) {
        synchronized (mLock) {
            Iterator<Integer> it = mT3tIdentifiers.keySet().iterator();
            while (it.hasNext()) {
                int handle = it.next().intValue();
                byte[] value = mT3tIdentifiers.get(handle);
                if (Arrays.equals(value, t3tIdentifier)) {
                    doDeregisterT3tIdentifier(handle);
                    mT3tIdentifiers.remove(handle);
                    break;
                }
            }
        }
    }

    public boolean isT3TIdentifierRegistered() {
        synchronized (mLock) {
            return mT3tIdentifiers.size() > 0;
        }
    }

    @Override
    public void clearT3tIdentifiersCache() {
        synchronized (mLock) {
            Iterator<Integer> it = mT3tIdentifiers.keySet().iterator();
            while (it.hasNext()) {
                int handle = it.next().intValue();
                doDeregisterT3tIdentifier(handle);
            }
            mT3tIdentifiers.clear();
        }
    }

    @Override
    public native int getLfT3tMax();

    @Override
    public native void doSetScreenState(int screen_state_mask, boolean alwaysPoll);

    @Override
    public native int getNciVersion();

    private native void doEnableDiscovery(
            int techMask,
            boolean enableLowPowerPolling,
            boolean enableReaderMode,
            boolean enableHostRouting,
            byte[] techAPollingLoopAnnotation,
            byte[] extraAnnotation,
            boolean restart);


    @Override
    public void enableDiscovery(NfcDiscoveryParameters params, boolean restart) {
        doEnableDiscovery(
                params.getTechMask(),
                params.shouldEnableLowPowerDiscovery(),
                params.shouldEnableReaderMode(),
                params.shouldEnableHostRouting(),
                params.techAPollingLoopAnnotation(),
                params.extraAnnotation(),
                restart);
    }

    @Override
    public native void disableDiscovery();

    private native void doResetTimeouts();

    @Override
    public void resetTimeouts() {
        doResetTimeouts();
    }

    @Override
    public native void doAbort(String msg);

    private native boolean doSetTimeout(int tech, int timeout);

    @Override
    public boolean setTimeout(int tech, int timeout) {
        return doSetTimeout(tech, timeout);
    }

    private native int doGetTimeout(int tech);

    @Override
    public int getTimeout(int tech) {
        return doGetTimeout(tech);
    }

    @Override
    public boolean canMakeReadOnly(int ndefType) {
        return (ndefType == Ndef.TYPE_1 || ndefType == Ndef.TYPE_2);
    }

    @Override
    public int getMaxTransceiveLength(int technology) {
        switch (technology) {
            case (TagTechnology.NFC_A):
            case (TagTechnology.MIFARE_CLASSIC):
            case (TagTechnology.MIFARE_ULTRALIGHT):
                return 253; // PN544 RF buffer = 255 bytes, subtract two for CRC
            case (TagTechnology.NFC_B):
                /////////////////////////////////////////////////////////////////
                // Broadcom: Since BCM2079x supports this, set NfcB max size.
                // return 0; // PN544 does not support transceive of raw NfcB
                return 253; // PN544 does not support transceive of raw NfcB
            case (TagTechnology.NFC_V):
                return 253; // PN544 RF buffer = 255 bytes, subtract two for CRC
            case (TagTechnology.ISO_DEP):
                return mIsoDepMaxTransceiveLength;
            case (TagTechnology.NFC_F):
                return 255;
            default:
                return 0;
        }
    }

    public native int getAidTableSize();

    @Override
    public boolean getExtendedLengthApdusSupported() {
        /* 261 is the default size if extended length frames aren't supported */
        if (getMaxTransceiveLength(TagTechnology.ISO_DEP) > 261) return true;
        return false;
    }

    private native void doDump(FileDescriptor fd);

    @Override
    public void dump(PrintWriter pw, FileDescriptor fd) {
        pw.println("Firmware version=" + NfcProperties.fw_version().orElse("<Unknown>"));
        pw.println("Native Proprietary Caps=" + mProprietaryCaps);
        doDump(fd);
    }

    private native void doRestartRfDiscovery();

    @Override
    public void restartRfDiscovery() {
        doRestartRfDiscovery();
    }

    private native boolean doSetNfcSecure(boolean enable);

    @Override
    public boolean setNfcSecure(boolean enable) {
        return doSetNfcSecure(enable);
    }

    private native void doStartStopPolling(boolean start);

    @Override
    public void startStopPolling(boolean start) {
        doStartStopPolling(start);
    }

    private native void doSetNfceePowerAndLinkCtrl(boolean enable);

    @Override
    public void setNfceePowerAndLinkCtrl(boolean enable) {
        doSetNfceePowerAndLinkCtrl(enable);
    }

    @Override
    public native byte[] getRoutingTable();

    @Override
    public native int getMaxRoutingTableSize();

    public native boolean isMultiTag();

    @Override
    public native Map<String, Integer> dofetchActiveNfceeList();

    private native NfcVendorNciResponse nativeSendRawVendorCmd(
            int mt, int gid, int oid, byte[] payload);

    @Override
    public NfcVendorNciResponse sendRawVendorCmd(int mt, int gid, int oid, byte[] payload) {
        NfcVendorNciResponse res= nativeSendRawVendorCmd(mt, gid, oid, payload);
        return res;
    }

    /** Notifies Ndef Message (TODO: rename into notifyTargetDiscovered) */
    private void notifyNdefMessageListeners(NativeNfcTag tag) {
        mListener.onRemoteEndpointDiscovered(tag);
    }

    private void notifyHostEmuActivated(int technology) {
        mListener.onHostCardEmulationActivated(technology);
    }

    private void notifyHostEmuData(int technology, byte[] data) {
        mListener.onHostCardEmulationData(technology, data);
    }

    private void notifyHostEmuDeactivated(int technology) {
        mListener.onHostCardEmulationDeactivated(technology);
    }

    private void notifyRfFieldActivated() {
        mListener.onRemoteFieldActivated();
    }

    private void notifyRfFieldDeactivated() {
        mListener.onRemoteFieldDeactivated();
    }

    private void notifyTransactionListeners(byte[] aid, byte[] data, String evtSrc) {
        mListener.onNfcTransactionEvent(aid, data, evtSrc);
    }

    private void notifyEeUpdated() {
        mListener.onEeUpdated();
    }

    private void notifyHwErrorReported() {
        mListener.onHwErrorReported();
    }

    private void notifyEeAidSelected(byte[] aid, String eeName) {
        Log.i(TAG, "notifyEeAidSelected: AID= " + HexFormat.of().formatHex(aid) + " selected by "
                + eeName);
        mListener.onSeSelected(NfcService.SE_SELECTED_AID, aid, eeName);
    }

    private void notifyEeProtocolSelected(int protocol, String eeName) {
        Log.i(TAG, "notifyEeProtocolSelected: Protocol: " + protocol + " selected by " + eeName);
        mListener.onSeSelected(NfcService.SE_SELECTED_PROTOCOL, null, eeName);
    }

    private void notifyEeTechSelected(int tech, String eeName) {
        Log.i(TAG, "notifyEeTechSelected: Tech: " + tech + " selected by " + eeName);
        mListener.onSeSelected(NfcService.SE_SELECTED_TECH, null, eeName);
    }

    public void notifyPollingLoopFrame(int data_len, byte[] p_data) {
        if (data_len < MIN_POLLING_FRAME_TLV_SIZE) {
            return;
        }
        Trace.beginSection("notifyPollingLoopFrame");
        final int header_len = 4;
        int pos = header_len;
        final int TLV_header_len = 3;
        final int TLV_type_offset = 0;
        final int TLV_len_offset = 2;
        final int TLV_timestamp_offset = 3;
        final int TLV_gain_offset = 7;
        final int TLV_data_offset = 8;
        ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>();
        if (data_len >= TLV_header_len) {
            int tlv_len = Byte.toUnsignedInt(p_data[TLV_len_offset]) + TLV_header_len;
            if (tlv_len < data_len) {
                data_len = tlv_len;
            }
        }
        while (pos + TLV_len_offset < data_len) {
            @PollingFrame.PollingFrameType int frameType;
            Bundle frame = new Bundle();
            int type = p_data[pos + TLV_type_offset];
            int length = p_data[pos + TLV_len_offset];
            if (TLV_len_offset + length < TLV_gain_offset) {
                Log.e(TAG, "notifyPollingLoopFrame: Length (" + length
                        + ") is less than a polling frame, dropping.");
                break;
            }
            if (pos + TLV_header_len + length > data_len) {
                // Frame is bigger than buffer.
                Log.e(TAG, "notifyPollingLoopFrame: Polling frame data (" + pos + ", " + length
                        + ") is longer than buffer data length (" + data_len + ").");
                break;
            }
            switch (type) {
                case TAG_FIELD_CHANGE:
                    frameType = p_data[pos + TLV_data_offset] != 0x00
                                    ? PollingFrame.POLLING_LOOP_TYPE_ON
                                    : PollingFrame.POLLING_LOOP_TYPE_OFF;
                    break;
                case TAG_NFC_A:
                    frameType = PollingFrame.POLLING_LOOP_TYPE_A;
                    break;
                case TAG_NFC_B:
                    frameType = PollingFrame.POLLING_LOOP_TYPE_B;
                    break;
                case TAG_NFC_F:
                    frameType = PollingFrame.POLLING_LOOP_TYPE_F;
                    break;
                case TAG_NFC_UNKNOWN:
                    frameType = PollingFrame.POLLING_LOOP_TYPE_UNKNOWN;
                    break;
                default:
                    Log.e(TAG, "notifyPollingLoopFrame: Unknown polling loop tag type.");
                    return;
            }
            byte[] frameData = null;
            if (pos + TLV_header_len + length <= data_len) {
                frameData = Arrays.copyOfRange(p_data, pos + TLV_data_offset,
                    pos + TLV_header_len + length);
            }
            int gain = -1;
            if (pos + TLV_gain_offset <= data_len) {
                gain = Byte.toUnsignedInt(p_data[pos + TLV_gain_offset]);
                if (gain == 0XFF) {
                    gain = -1;
                }
            }
            long timestamp = 0;
            if (pos + TLV_timestamp_offset + 3 < data_len) {
                timestamp = Integer.toUnsignedLong(ByteBuffer.wrap(p_data,
                        pos + TLV_timestamp_offset, 4).order(ByteOrder.BIG_ENDIAN).getInt());
            }
            pos += (TLV_header_len + length);
            frames.add(new PollingFrame(frameType, frameData, gain, timestamp, false));
        }
        mListener.onPollingLoopDetected(frames);
        Trace.endSection();
    }

    private void onObserveModeEnabledInFirmware() {
        mListener.onObserveModeEnabledInFirmware();
    }

    private void onObserveModeDisabledInFirmware(int type, byte[] data) {
        int pollingFrameType = PollingFrame.POLLING_LOOP_TYPE_UNKNOWN;
        if (type == OBSERVE_MODE_SUSPENDED_FRAME_TYPE_A) {
            pollingFrameType = PollingFrame.POLLING_LOOP_TYPE_A;
        } else if (type == OBSERVE_MODE_SUSPENDED_FRAME_TYPE_B) {
            pollingFrameType = PollingFrame.POLLING_LOOP_TYPE_B;
        }

        mListener.onObserveModeDisabledInFirmware(
                new PollingFrame(pollingFrameType, data, -1, -1, true));
    }

    private native boolean doDetectEpRemoval(int waiting_time_int);

    @Override
    public boolean detectEpRemoval(int waiting_time_int) {
        return doDetectEpRemoval(waiting_time_int);
    }

    private void notifyWlcStopped(int wpt_end_condition) {
        mListener.onWlcStopped(wpt_end_condition);
    }
    private void notifyTagDiscovered(boolean discovered) {
        mListener.onTagRfDiscovered(discovered);
    }
    private void notifyTagConnectionChanged(boolean connected) {
        mListener.onTagConnectionChanged(connected);
    }
    private void notifyVendorSpecificEvent(int event, int dataLen, byte[] pData) {
        if (pData.length < NCI_HEADER_MIN_LEN || dataLen != pData.length) {
            Log.e(TAG, "notifyVendorSpecificEvent: Invalid data");
            return;
        }
        if (android.nfc.Flags.nfcVendorCmd()) {
            mListener.onVendorSpecificEvent(pData[NCI_GID_INDEX], pData[NCI_OID_INDEX],
                    Arrays.copyOfRange(pData, OP_CODE_INDEX, pData.length));
        }
    }

    private void notifyRFDiscoveryEvent(boolean isDiscoveryStarted) {
        mListener.onRfDiscoveryEvent(isDiscoveryStarted);
    }

    private void notifyEeListenActivated(boolean isActivated) {
        mListener.onEeListenActivated(isActivated);
    }

    @Override
    public native void setDiscoveryTech(int pollTech, int listenTech);

    @Override
    public native void resetDiscoveryTech();

    @Override
    public native void clearRoutingEntry(int clearFlags);

    @Override
    public native void setIsoDepProtocolRoute(int route);

    @Override
    public native void setTechnologyABFRoute(int route, int felicaRoute);

    @Override
    public native void setSystemCodeRoute(int route);

    private native byte[] getProprietaryCaps();

    @Override
    public native void enableVendorNciNotifications(boolean enabled);

    private void notifyCommandTimeout() {
        if (android.nfc.Flags.nfcEventListener()) {
            mListener.onCommandTimeout();
        }
        NfcService.getInstance().storeNativeCrashLogs();
    }

    @Override
    public native void setNciConfig(int paramId, byte[] param, int length, boolean custom);

    @Override
    public native byte[] getRfDiscoverConfig();

    private native void doSetDefaultFrame(byte[] frame);

    @Override
    public void setDefaultFrame(byte[] frame) {
        doSetDefaultFrame(frame);
    }

    /** wrappers for values */
    private static final int CAPS_OBSERVE_MODE_UNKNOWN =
            NFC_PROPRIETARY_CAPABILITIES_REPORTED__PASSIVE_OBSERVE_MODE__MODE_UNKNOWN;
    private static final int CAPS_OBSERVE_MODE_SUPPORT_WITH_RF_DEACTIVATION =
          NFC_PROPRIETARY_CAPABILITIES_REPORTED__PASSIVE_OBSERVE_MODE__SUPPORT_WITH_RF_DEACTIVATION;
    private static final int CAPS_OBSERVE_MODE_SUPPORT_WITHOUT_RF_DEACTIVATION =
       NFC_PROPRIETARY_CAPABILITIES_REPORTED__PASSIVE_OBSERVE_MODE__SUPPORT_WITHOUT_RF_DEACTIVATION;
    private static final int CAPS_OBSERVE_MODE_NOT_SUPPORTED =
            NfcStatsLog.NFC_PROPRIETARY_CAPABILITIES_REPORTED__PASSIVE_OBSERVE_MODE__NOT_SUPPORTED;

    private static boolean isObserveModeSupportedCaps(NfcProprietaryCaps proprietaryCaps) {
        return proprietaryCaps.getPassiveObserveMode()
            != NfcProprietaryCaps.PassiveObserveMode.NOT_SUPPORTED;
    }

    private  boolean isReaderModeAnnotationSupportedCaps() {
        return mProprietaryCaps != null && mProprietaryCaps.isReaderModeAnnotationSupported();
    }

    @Override
    public  boolean isReaderModeAnnotationSupported() {
        return isReaderModeAnnotationSupportedCaps();
    }

    private static void logProprietaryCaps(NfcProprietaryCaps proprietaryCaps) {
        int observeModeStatsd = CAPS_OBSERVE_MODE_UNKNOWN;

        NfcProprietaryCaps.PassiveObserveMode mode = proprietaryCaps.getPassiveObserveMode();

        if (mode == NfcProprietaryCaps.PassiveObserveMode.SUPPORT_WITH_RF_DEACTIVATION) {
            observeModeStatsd = CAPS_OBSERVE_MODE_SUPPORT_WITH_RF_DEACTIVATION;
        } else if (mode == NfcProprietaryCaps.PassiveObserveMode.SUPPORT_WITHOUT_RF_DEACTIVATION) {
            observeModeStatsd = CAPS_OBSERVE_MODE_SUPPORT_WITHOUT_RF_DEACTIVATION;
        } else if (mode == NfcProprietaryCaps.PassiveObserveMode.NOT_SUPPORTED) {
            observeModeStatsd = CAPS_OBSERVE_MODE_NOT_SUPPORTED;
        }

        NfcStatsLog.write(NfcStatsLog.NFC_PROPRIETARY_CAPABILITIES_REPORTED,
                observeModeStatsd,
                proprietaryCaps.isPollingFrameNotificationSupported(),
                proprietaryCaps.isPowerSavingModeSupported(),
                proprietaryCaps.isAutotransactPollingLoopFilterSupported(),
                proprietaryCaps.getNumberOfExitFramesSupported(),
                proprietaryCaps.isReaderModeAnnotationSupported());
    }

    public void notifyObserveModeChanged(boolean enabled) {
        mListener.onObserveModeStateChanged(enabled);
    }
    /** Notifies remote endpoint removed */
    private void notifyEndpointRemoved(int reason) {
        mListener.onEndpointRemoved(reason);

    }

    @Override
    public native boolean isRemovalDetectionInPollModeSupported();

    public void onRestartRfDiscovery() {
        mListener.onRestartRfDiscovery();
    }
}
