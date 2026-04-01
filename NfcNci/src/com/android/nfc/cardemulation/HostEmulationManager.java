/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.nfc.cardemulation;

import static com.android.nfc.module.flags.Flags.nfcHceLatencyEvents;
import static com.android.nfc.module.flags.Flags.ceWakeLock;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TargetApi;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.nfc.ComponentNameAndUser;
import android.nfc.INfcOemExtensionCallback;
import android.nfc.NfcAdapter;
import android.nfc.OemLogItems;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.HostApduService;
import android.nfc.cardemulation.PollingFrame;
import android.nfc.cardemulation.Utils;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.WorkSource;
import android.sysprop.NfcProperties;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.proto.ProtoOutputStream;

import androidx.annotation.VisibleForTesting;

import com.android.nfc.DeviceConfigFacade;
import com.android.nfc.ForegroundUtils;
import com.android.nfc.NfcInjector;
import com.android.nfc.NfcService;
import com.android.nfc.NfcStatsLog;
import com.android.nfc.PerfettoTrigger;
import com.android.nfc.cardemulation.RegisteredAidCache.AidResolveInfo;
import com.android.nfc.cardemulation.util.StatsdUtils;
import com.android.nfc.flags.Flags;
import com.android.nfc.proto.NfcEventProto;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class HostEmulationManager {
    static final String TAG = "NfcHostEmulationManager";
    static final boolean DBG = NfcProperties.debug_enabled().orElse(true);
    static final boolean VDBG = NfcProperties.verbose_debug_enabled().orElse(false);

    static final int STATE_IDLE = 0;
    static final int STATE_W4_SELECT = 1;
    static final int STATE_W4_SERVICE = 2;
    static final int STATE_W4_DEACTIVATE = 3;
    static final int STATE_XFER = 4;
    static final int STATE_POLLING_LOOP = 5;

    /** Minimum AID length as per ISO7816 */
    static final int MINIMUM_AID_LENGTH = 5;

    /** Length of Select APDU header including length byte */
    static final int SELECT_APDU_HDR_LENGTH = 5;

    static final byte INSTR_SELECT = (byte)0xA4;

    static final String ANDROID_HCE_AID = "A000000476416E64726F6964484345";
    static final String NDEF_V1_AID = "D2760000850100";
    static final String NDEF_V2_AID = "D2760000850101";
    static final byte[] ANDROID_HCE_RESPONSE = {0x14, (byte)0x81, 0x00, 0x00, (byte)0x90, 0x00};

    static final byte[] AID_NOT_FOUND = {0x6A, (byte)0x82};
    static final byte[] UNKNOWN_ERROR = {0x6F, 0x00};

    static final int CE_HCE_PAYMENT =
            NfcStatsLog.NFC_CARDEMULATION_OCCURRED__CATEGORY__HCE_PAYMENT;
    static final int CE_HCE_OTHER =
            NfcStatsLog.NFC_CARDEMULATION_OCCURRED__CATEGORY__HCE_OTHER;
    static final String DATA_KEY = "data";
    static final int FIELD_OFF_IDLE_DELAY_MS = 2000;
    static final int RE_ENABLE_OBSERVE_MODE_DELAY_MS = 2000;
    static final int UNBIND_SERVICES_DELAY_MS = 10_000;

    static final String EVENT_HCE_ACTIVATED = "hce_active";
    static final String EVENT_HCE_BIND_PAYMENT_SERVICE = "hce_bind_payment_service";
    static final String EVENT_HCE_BIND_SERVICE = "hce_bind_service";
    static final String EVENT_HCE_COMMAND_APDU = "hce_command_apdu";
    static final String EVENT_POLLING_FRAMES = "hce_polling_frames";
    static final String TRIGGER_NAME_SLOW_TAP = "android.nfc.slow-tap";

    final Context mContext;
    final RegisteredAidCache mAidCache;
    final Messenger mMessenger = new Messenger (new MessageHandler());
    final KeyguardManager mKeyguard;
    final Object mLock;
    final PowerManager mPowerManager;
    final PowerManager.WakeLock mWakeLock;
    private final Looper mLooper;
    final DeviceConfigFacade mDeviceConfig;

    @Nullable
    private final StatsdUtils mStatsdUtils;

    private final Random mCookieRandom = new Random(System.currentTimeMillis());

    @ChangeId
    @EnabledSince(targetSdkVersion = 36 /*Build.VERSION_CODES.BAKLAVA*/)
    static final long DONT_IMMEDIATELY_UNBIND_SERVICES = 365533082L;

    INfcOemExtensionCallback mNfcOemExtensionCallback;

    long mFieldOnTime;

    // All variables below protected by mLock

    // Variables below are for a non-payment service,
    // that is typically only bound in the STATE_XFER state.
    Messenger mService;

    static class HostEmulationConnection {
        @UserIdInt int mUserId;
        ComponentName mComponentName;
        ServiceConnection mServiceConnection;
        Messenger mMessenger;

        HostEmulationConnection(@UserIdInt int userId,
                                ComponentName componentName,
                                ServiceConnection serviceConnection) {
            this(userId, componentName, serviceConnection, null);
        }

        HostEmulationConnection(
            @UserIdInt int userId,
                ComponentName componentName,
                ServiceConnection serviceConnection,
                Messenger messenger) {
            mUserId = userId;
            mComponentName = componentName;
            mServiceConnection = serviceConnection;
            mMessenger = messenger;
        }

        @Override
        public String toString() {
            return "{" + mComponentName + "(" + mUserId + "): "
                + mMessenger + ", " + mServiceConnection + "}";
        }
    }

    Map<ComponentNameAndUser, HostEmulationConnection> mComponentNameToConnectionsMap =
            new HashMap<>();
    boolean mServiceBound = false;
    ComponentName mServiceName = null;
    @UserIdInt int mServiceUserId; // The UserId of the non-payment service
    ArrayList<PollingFrame> mPendingPollingLoopFrames = null;
    ArrayList<PollingFrame> mUnprocessedPollingFrames = null;
    Map<ComponentName, ArrayList<PollingFrame>> mPollingFramesToSend = null;
    private Map<Integer, Map<String, List<ApduServiceInfo>>> mPollingLoopFilters;
    private Map<Integer, Map<Pattern, List<ApduServiceInfo>>> mPollingLoopPatternFilters;
    AutoDisableObserveModeRunnable mAutoDisableObserveModeRunnable = null;

    // Variables below are for a payment service,
    // which is typically bound persistently to improve on
    // latency.
    Messenger mPaymentService;
    boolean mPaymentServiceBound = false;

    boolean mEnableObserveModeAfterTransaction = false;
    boolean mEnableObserveModeOnFieldOff = false;
    PollingFrame mFirmwareExitFrame = null;
    ComponentName mPaymentServiceName = null;
    @UserIdInt int mPaymentServiceUserId; // The userId of the payment service
    ComponentName mLastBoundPaymentServiceName;

    // mActiveService denotes the service interface
    // that is the current active one, until a new SELECT AID
    // comes in that may be resolved to a different service.
    // On deactivation, mActiveService stops being valid.
    Messenger mActiveService;
    ComponentName mActiveServiceName;
    @UserIdInt int mActiveServiceUserId; // The UserId of the current active one

    String mLastSelectedAid;
    AtomicInteger mState = new AtomicInteger(STATE_IDLE);
    byte[] mSelectApdu;
    Handler mHandler;


    enum PollingLoopState {
        EVALUATING_POLLING_LOOP,
        FILTER_MATCHED,
        DELIVERING_TO_PREFERRED
    };

    PollingLoopState mPollingLoopState = PollingLoopState.EVALUATING_POLLING_LOOP;

    // Runnable to return to an IDLE_STATE and reset preferred service. This should be run after we
    // have left a field and gone a period of time without any HCE or polling frame data.
    Runnable mReturnToIdleStateRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                Log.d(TAG,
                        "mReturnToIdleStateRunnable: Have been outside field, "
                                + "returning to idle state");
                returnToIdleState();
            }
        }
    };

    Runnable mUnbindInactiveServicesRunnable =
            new Runnable() {
                @Override
                public void run() {
                    synchronized (mLock) {
                        if (isHostCardEmulationActivated()) {
                            // Skip in active state
                            rescheduleInactivityChecks();
                        } else {
                            unbindInactiveServicesLocked();
                        }
                    }
                }

                void unbindInactiveServicesLocked() {
                    ComponentNameAndUser preferredNameAndUser = mAidCache.getPreferredService();
                    Map<ComponentNameAndUser, HostEmulationConnection> retainedConnections =
                            new HashMap<>();
                    mComponentNameToConnectionsMap.keySet().forEach((key) -> {
                        if (!preferredNameAndUser.equals(key)) {
                            HostEmulationConnection connection =
                                mComponentNameToConnectionsMap.get(key);
                            if (connection.mMessenger != null) {
                                try {
                                    mContext.unbindService(connection.mServiceConnection);
                                } catch (IllegalArgumentException iae) {
                                    Log.wtf(TAG,
                                            "unbindInactiveServicesLocked: "
                                                    + "Exception while unbinding "
                                                    + key.getComponentName()
                                                    + " service connection",
                                            iae);
                                }
                            }
                        } else {
                            retainedConnections.put(key, mComponentNameToConnectionsMap.get(key));
                        }
                    });
                    mComponentNameToConnectionsMap = retainedConnections;
                }
            };

    // Runnable to re-enable observe mode after a transaction. This should be delayed after
    // HCE is deactivated to ensure we don't receive another select AID.
    Runnable mEnableObserveModeAfterTransactionRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                Log.d(TAG, "mEnableObserveModeAfterTransactionRunnable.run");
                if (!mEnableObserveModeAfterTransaction && !mEnableObserveModeOnFieldOff) {
                    return;
                }
                mEnableObserveModeAfterTransaction = false;
                mEnableObserveModeOnFieldOff = false;
            }
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
            if (adapter == null) {
                Log.e(TAG, "mEnableObserveModeAfterTransactionRunnable.run: "
                        + "adapter is null, returning");
                return;
            }
            adapter.setObserveModeEnabled(true);
        }
    };

    public HostEmulationManager(Context context, Looper looper, RegisteredAidCache aidCache,
            NfcInjector nfcInjector) {
        this(context, looper, aidCache, nfcInjector.getStatsdUtilsContext() != null ?
            new StatsdUtils(StatsdUtils.SE_NAME_HCE, nfcInjector.getStatsdUtilsContext()) : null,
            nfcInjector);
    }

    @VisibleForTesting
    HostEmulationManager(Context context, Looper looper, RegisteredAidCache aidCache,
                         StatsdUtils statsdUtils, NfcInjector nfcInjector) {
        mContext = context;
        mLooper = looper;
        mHandler = new Handler(looper);
        mLock = new Object();
        mAidCache = aidCache;
        mPollingLoopState = PollingLoopState.EVALUATING_POLLING_LOOP;
        mKeyguard = context.getSystemService(KeyguardManager.class);
        mPowerManager = context.getSystemService(PowerManager.class);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "HostEmulationManager:mWakeLock");
        mWakeLock.setReferenceCounted(false);
        mStatsdUtils = Flags.statsdCeEventsFlag() ? statsdUtils : null;
        mPollingLoopFilters = new HashMap<Integer, Map<String, List<ApduServiceInfo>>>();
        mPollingLoopPatternFilters = new HashMap<Integer, Map<Pattern, List<ApduServiceInfo>>>();
        mDeviceConfig = nfcInjector.getDeviceConfigFacade();

        if (isMultipleBindingSupported()) {
            mHandler.postDelayed(mUnbindInactiveServicesRunnable, UNBIND_SERVICES_DELAY_MS);
        }
    }

    public void setOemExtension(@Nullable INfcOemExtensionCallback nfcOemExtensionCallback) {
        mNfcOemExtensionCallback = nfcOemExtensionCallback;
    }

    public void onBootCompleted() {
        if (!mPaymentServiceBound) {
            ComponentNameAndUser preferredPaymentService = mAidCache.getPreferredPaymentService();
            // getPreferredPaymentService returns a non-null object even if there is no role holder,
            // check for package name explicitly.
            ComponentName preferredPaymentServiceName = preferredPaymentService.getComponentName();
            if (preferredPaymentServiceName != null) {
                Log.d(TAG, "onBootCompleted: payment service not bound, binding");
                onPreferredPaymentServiceChanged(preferredPaymentService);
            }
        }
    }

    /**
     *  Preferred payment service changed
     */
    public void onPreferredPaymentServiceChanged(final ComponentNameAndUser service) {
        if (DBG) {
            Log.d(TAG, "onPreferredPaymentServiceChanged: service=" + service);
        }
        mHandler.post(() -> {
            synchronized (mLock) {
                if (!isHostCardEmulationActivated()) {
                    Log.d(TAG, "onPreferredPaymentServiceChanged: resetting active service");
                    resetActiveService();
                }

                if (service != null && service.getComponentName() != null) {
                    bindPaymentServiceLocked(service.getUserId(), service.getComponentName());
                } else {
                    unbindPaymentServiceLocked();
                }
            }
        });
    }

    private Messenger getForegroundServiceOrDefault() {
        Pair<Messenger, ComponentName> pair = getForegroundServiceAndNameOrDefault();
        if (pair == null) {
            return null;
        }
        return pair.first;
    }

    private Pair<Messenger, ComponentName> getForegroundServiceAndNameOrDefault() {
        ComponentNameAndUser preferredService = mAidCache.getPreferredService();
        int preferredServiceUserId = preferredService.getUserId();
        ComponentName preferredServiceName = preferredService.getComponentName();

        if (preferredServiceName == null || preferredServiceUserId < 0) {
            return null;
        }
        return new Pair<>(bindServiceIfNeededLocked(preferredServiceUserId, preferredServiceName),
            preferredServiceName);
    }


    @TargetApi(35)
    public void updateForShouldDefaultToObserveMode(boolean enabled) {
        if (DBG) {
            Log.d(TAG, "updateForShouldDefaultToObserveMode: enabled=" + enabled);
        }

        synchronized (mLock) {
            if (isHostCardEmulationActivated()) {
                mEnableObserveModeAfterTransaction = enabled;
                return;
            }
            if (mHandler.hasCallbacks(mEnableObserveModeAfterTransactionRunnable)) {
                if (enabled) {
                    return;
                } else {
                    mHandler.removeCallbacks(mEnableObserveModeAfterTransactionRunnable);
                }
            }
            mEnableObserveModeAfterTransaction = false;
            mEnableObserveModeOnFieldOff = false;
        }
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        adapter.setObserveModeEnabled(enabled);
    }


    @TargetApi(35)
    public void updatePollingLoopFilters(@UserIdInt int userId, List<ApduServiceInfo> services) {
        HashMap<String, List<ApduServiceInfo>> pollingLoopFilters =
                new HashMap<String, List<ApduServiceInfo>>();
        HashMap<Pattern, List<ApduServiceInfo>> pollingLoopPatternFilters =
                new HashMap<Pattern, List<ApduServiceInfo>>();
        for (ApduServiceInfo serviceInfo : services) {
            if (DBG) {
                Log.d(TAG, "updatePollingLoopFilters: service=" + serviceInfo);
            }
            for (String plf : serviceInfo.getPollingLoopFilters()) {
                if (DBG) {
                    Log.d(TAG, "updatePollingLoopFilters: filter=" + plf);
                }
                List<ApduServiceInfo> list =
                        pollingLoopFilters.getOrDefault(plf, new ArrayList<ApduServiceInfo>());
                list.add(serviceInfo);
                pollingLoopFilters.putIfAbsent(plf, list);

            }
            for (Pattern plpf : serviceInfo.getPollingLoopPatternFilters()) {
                if (DBG) {
                    Log.d(TAG, "updatePollingLoopFilters: patternFilter=" + plpf);
                }
                List<ApduServiceInfo> list =
                        pollingLoopPatternFilters.getOrDefault(plpf,
                        new ArrayList<ApduServiceInfo>());
                list.add(serviceInfo);
                pollingLoopPatternFilters.putIfAbsent(plpf, list);

            }
        }
        mPollingLoopFilters.put(Integer.valueOf(userId), pollingLoopFilters);
        mPollingLoopPatternFilters.put(Integer.valueOf(userId), pollingLoopPatternFilters);
    }

    public void onObserveModeStateChange(boolean enabled) {
        synchronized(mLock) {
            if (!enabled) {
                clearAutoDisableObserveModeRunnableLocked();
            }
        }
    }

    class AutoDisableObserveModeRunnable implements Runnable {
        Set<String> mServicePackageNames;
        AutoDisableObserveModeRunnable(ComponentName componentName) {
            mServicePackageNames = new ArraySet<>(1);
            addServiceToList(componentName);
        }

        @Override
        public void run() {
            synchronized(mLock) {
                NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
                if (!adapter.isObserveModeEnabled()) {
                    return;
                }
                if (arePackagesInForeground()) {
                    return;
                }
                Log.w(TAG,
                        "AutoDisableObserveModeRunnable: Observe mode not disabled and "
                                + "no application from the following "
                                + "packages are in the foreground: "
                                + String.join(", ", mServicePackageNames));
                allowOneTransaction();
            }
        }


        void addServiceToList(ComponentName service) {
            mServicePackageNames.add(service.getPackageName());
            // If this is the payment service, also add the associated services to the list of
            // packages to monitor.
            if (service.equals(mAidCache.getPreferredPaymentService().getComponentName())) {
                for (ComponentNameAndUser preferredService
                        : mAidCache.getPreferredPaymentAssociatedServices()) {
                    mServicePackageNames.add(preferredService.getComponentName().getPackageName());
                }
            }
        }

        boolean arePackagesInForeground() {
            ActivityManager am = mContext.getSystemService(ActivityManager.class);
            if (am == null) {
                return false;
            }
            ForegroundUtils foregroundUtils = ForegroundUtils.getInstance(am);
            if (foregroundUtils == null) {
                return false;
            }
            PackageManager packageManager = mContext.getPackageManager();
            if (packageManager == null) {
                return false;
            }
            List<Integer> uids = foregroundUtils.getForegroundUids();
            if (uids != null && mServicePackageNames != null) {
                for (Integer uid : uids) {
                    String[] packageNames = packageManager.getPackagesForUid(uid);
                    if (packageNames != null) {
                        for (String packageName :  packageNames) {
                            if (packageName != null) {
                                for (String servicePackageName : mServicePackageNames) {
                                    if (Objects.equals(servicePackageName, packageName)) {
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return false;
        }
    }

    private void sendFrameToServiceLocked(Messenger service, ComponentName name,
        PollingFrame frame) {
        sendFramesToServiceLocked(service, name, Arrays.asList(frame));
    }

    private void sendFramesToServiceLocked(Messenger service, ComponentName name,
            List<PollingFrame> frames) {
        if (service != null) {
            sendPollingFramesToServiceLocked(service, new ArrayList<>(frames));
        } else {
            mUnprocessedPollingFrames = new ArrayList<PollingFrame>();
            if (mPollingFramesToSend == null) {
                mPollingFramesToSend = new HashMap<ComponentName, ArrayList<PollingFrame>>();
            }
            if (mPollingFramesToSend.containsKey(name)) {
                mPollingFramesToSend.get(name).addAll(frames);
            } else {
                mPollingFramesToSend.put(name, new ArrayList<>(frames));
            }
        }
        if (Flags.autoDisableObserveMode()) {
            if (mAutoDisableObserveModeRunnable == null) {
                mAutoDisableObserveModeRunnable = new AutoDisableObserveModeRunnable(name);
                mHandler.postDelayed(mAutoDisableObserveModeRunnable, 3000);
            } else {
                mAutoDisableObserveModeRunnable.addServiceToList(name);
            }
        }
    }

    private void clearAutoDisableObserveModeRunnableLocked() {
        if (mAutoDisableObserveModeRunnable != null) {
            mHandler.removeCallbacks(mAutoDisableObserveModeRunnable);
            mAutoDisableObserveModeRunnable = null;
        }
    }

    void onNfcFHostEmulationActivated() {
        synchronized (mLock) {
            clearAutoDisableObserveModeRunnableLocked();
        }
    }

    void onNfcFHostEmulationDeactivated() {
        synchronized (mLock) {
            clearAutoDisableObserveModeRunnableLocked();
        }
    }

    void rescheduleInactivityChecks() {
        mHandler.removeCallbacks(mReturnToIdleStateRunnable);
        if (isMultipleBindingSupported()) {
            mHandler.removeCallbacks(mUnbindInactiveServicesRunnable);
            mHandler.postDelayed(mUnbindInactiveServicesRunnable, UNBIND_SERVICES_DELAY_MS);
        }
    }

    private void setPollingLoopStateLocked(PollingLoopState state) {
        Log.d(TAG, "setPollingLoopStateLocked: " + mPollingLoopState + " -> " + state);
        mPollingLoopState = state;
    }

    @TargetApi(35)
    public void onPollingLoopDetected(List<PollingFrame> pollingFrames) {
        if (DBG) Log.d(TAG, "onPollingLoopDetected: " + pollingFrames);
        synchronized (mLock) {
            rescheduleInactivityChecks();
            // We need to have this check here in addition to the one in onFieldChangeDetected,
            // because we can receive an OFF frame after the field change is detected.
            if (!pollingFrames.isEmpty()
                    && pollingFrames.getLast().getType() == PollingFrame.POLLING_LOOP_TYPE_OFF) {
                mHandler.postDelayed(mReturnToIdleStateRunnable, FIELD_OFF_IDLE_DELAY_MS);
            }

            if (mState.get() == STATE_IDLE) {
                mState.set(STATE_POLLING_LOOP);
            }
            int onCount = 0;
            int offCount = 0;
            int aCount = 0;
            int bCount = 0;
            if (mPendingPollingLoopFrames == null) {
                mPendingPollingLoopFrames = new ArrayList<PollingFrame>(1);
            }
            for (PollingFrame pollingFrame : pollingFrames) {
                if (mUnprocessedPollingFrames != null) {
                    mUnprocessedPollingFrames.add(pollingFrame);
                } else if (pollingFrame.getType()
                        == PollingFrame.POLLING_LOOP_TYPE_F) {
                    Pair<Messenger, ComponentName> serviceAndName =
                        getForegroundServiceAndNameOrDefault();
                    if (serviceAndName != null) {
                        sendFrameToServiceLocked(serviceAndName.first, serviceAndName.second,
                            pollingFrame);
                    }
                } else if (pollingFrame.getType()
                        == PollingFrame.POLLING_LOOP_TYPE_UNKNOWN) {
                    if (DBG) Log.d(TAG, "onPollingLoopDetected: POLLING_LOOP_TYPE_UNKNOWN");
                    byte[] data = pollingFrame.getData();
                    String dataStr = HexFormat.of().formatHex(data).toUpperCase(Locale.ROOT);
                    List<ApduServiceInfo> serviceInfos =
                            mPollingLoopFilters.get(ActivityManager.getCurrentUser()).get(dataStr);
                    Map<Pattern, List<ApduServiceInfo>> patternMappingForUser =
                            mPollingLoopPatternFilters.get(ActivityManager.getCurrentUser());
                    Set<Pattern> patternSet = patternMappingForUser.keySet();
                    List<Pattern> matchedPatterns = patternSet.stream()
                            .filter(p -> p.matcher(dataStr).matches()).toList();
                    if (!matchedPatterns.isEmpty()) {
                        if (serviceInfos == null) {
                            serviceInfos = new ArrayList<ApduServiceInfo>();
                        }
                        for (Pattern matchedPattern : matchedPatterns) {
                            serviceInfos.addAll(patternMappingForUser.get(matchedPattern));
                        }
                    }
                    if (serviceInfos != null && serviceInfos.size() > 0) {
                        ApduServiceInfo serviceInfo;
                        if (serviceInfos.size() == 1) {
                            serviceInfo = serviceInfos.get(0);
                        } else {
                            serviceInfo = mAidCache.resolvePollingLoopFilterConflict(serviceInfos);
                            if (serviceInfo == null) {
                                /*  If neither the foreground or payments service can handle the plf,
                                *  pick the first in the list. */
                                serviceInfo = serviceInfos.get(0);
                            }
                        }
                        if (DBG) {
                            Log.d(TAG, "onPollingLoopDetected: service: "
                                    + serviceInfo.toString());
                        }
                        if (serviceInfo.getShouldAutoTransact(dataStr)) {
                            if (DBG) Log.d(TAG, "onPollingLoopDetected: Autotransact");

                            if (mStatsdUtils != null) {
                                mStatsdUtils.logAutoTransactReported(
                                    StatsdUtils.PROCESSOR_HOST, data);
                                mStatsdUtils.setNextObserveModeTriggerSource(
                                    StatsdUtils.TRIGGER_SOURCE_AUTO_TRANSACT);
                            }
                            if (mFirmwareExitFrame != null && Arrays.equals(
                                    mFirmwareExitFrame.getData(), pollingFrame.getData())) {
                                mFirmwareExitFrame = null;
                                mEnableObserveModeAfterTransaction = true;
                                // This is needed to ensure that we re-enable observe mode
                                // if the transaction does not start for some reason
                                // after disabling observe mode.
                                mEnableObserveModeOnFieldOff = true;
                                Log.d(TAG,
                                        "Polling frame matches exit frame, leaving observe mode "
                                                + "disabled");
                            } else {
                                allowOneTransaction();
                            }
                            pollingFrame.setTriggeredAutoTransact(true);
                        }
                        UserHandle user = UserHandle.getUserHandleForUid(serviceInfo.getUid());
                        if (serviceInfo.isOnHost()) {
                            Messenger service = bindServiceIfNeededLocked(user.getIdentifier(),
                                    serviceInfo.getComponent());
                            setPollingLoopStateLocked(PollingLoopState.FILTER_MATCHED);
                            sendFrameToServiceLocked(service, serviceInfo.getComponent(),
                                pollingFrame);
                        }
                    } else {
                        Pair<Messenger, ComponentName> serviceAndName =
                                getForegroundServiceAndNameOrDefault();
                        if (serviceAndName != null) {
                            sendFrameToServiceLocked(serviceAndName.first, serviceAndName.second,
                                pollingFrame);
                        }
                    }

                    if (mStatsdUtils != null) {
                        mStatsdUtils.tallyPollingFrame(dataStr, pollingFrame);
                    }
                } else {
                    mPendingPollingLoopFrames.add(pollingFrame);
                }
                if (mStatsdUtils != null) {
                    mStatsdUtils.logPollingFrames();
                }
            }
            mFirmwareExitFrame = null;

            if (mPollingLoopState == PollingLoopState.EVALUATING_POLLING_LOOP) {
                if (mPendingPollingLoopFrames.size() >= 3) {
                    for (PollingFrame frame : mPendingPollingLoopFrames) {
                        int type = frame.getType();
                        switch (type) {
                            case PollingFrame.POLLING_LOOP_TYPE_A:
                                aCount++;
                                if (aCount > 3) {
                                    setPollingLoopStateLocked(
                                            PollingLoopState.DELIVERING_TO_PREFERRED);
                                }
                                break;
                            case PollingFrame.POLLING_LOOP_TYPE_B:
                                bCount++;
                                if (bCount > 3) {
                                    setPollingLoopStateLocked(
                                            PollingLoopState.DELIVERING_TO_PREFERRED);
                                }
                                break;
                            case PollingFrame.POLLING_LOOP_TYPE_ON:
                                onCount++;
                                break;
                            case PollingFrame.POLLING_LOOP_TYPE_OFF:
                                // Send the loop data if we've seen at least one on before an off.
                                offCount++;
                                if (onCount >= 2 && offCount >=2) {
                                    setPollingLoopStateLocked(
                                            PollingLoopState.DELIVERING_TO_PREFERRED);
                                }
                                break;
                            default:
                        }
                        if (mPollingLoopState != PollingLoopState.EVALUATING_POLLING_LOOP) {
                            break;
                        }
                    }
                }
            }

            if (mPollingLoopState == PollingLoopState.DELIVERING_TO_PREFERRED) {
                Pair<Messenger, ComponentName> serviceAndName =
                        getForegroundServiceAndNameOrDefault();
                if (serviceAndName != null) {
                    sendFramesToServiceLocked(serviceAndName.first, serviceAndName.second,
                        mPendingPollingLoopFrames);
                    mPendingPollingLoopFrames = null;
                } else {
                    Log.i(TAG, "onPollingLoopDetected: No preferred service to deliver "
                            + "polling frames to, allowing transaction");
                    allowOneTransaction();
                }
            }
        }
    }

    private void allowOneTransaction() {
        Log.d(TAG, "allowOneTransaction");
        mEnableObserveModeAfterTransaction = true;
        // This is needed to ensure that we re-enable observe mode
        // if the transaction does not start for some reason
        // after disabling observe mode.
        mEnableObserveModeOnFieldOff = true;
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        mHandler.post(() -> adapter.setObserveModeEnabled(false));
    }

    /**
     * Observe mode was disabled in firmware, we shouldn't autotransact on the next frame.
     *
     * This assumes the exit frame will be in the next batch of processed polling frames.
     */
    public void onObserveModeDisabledInFirmware(PollingFrame exitFrame) {
        synchronized(mLock) {
            mFirmwareExitFrame = exitFrame;
            clearAutoDisableObserveModeRunnableLocked();
        }
    }

    /**
     *  Preferred foreground service changed
     */
    public void onPreferredForegroundServiceChanged(ComponentNameAndUser serviceAndUser) {
        if (DBG) {
            Log.d(TAG, "onPreferredForegroundServiceChanged: serviceAndUser=" + serviceAndUser);
        }
        synchronized (mLock) {
            int userId = serviceAndUser.getUserId();
            ComponentName service = serviceAndUser.getComponentName();

            mAidCache.onPreferredForegroundServiceChanged(serviceAndUser);

            if (!isHostCardEmulationActivated()) {
                Log.d(TAG, "onPreferredForegroundServiceChanged: resetting active service");
                resetActiveService();
            }
            if (service != null) {
                bindServiceIfNeededLocked(userId, service);
            } else {
                unbindServiceIfNeededLocked();
            }
         }
     }

    public void onFieldChangeDetected(boolean fieldOn) {
        rescheduleInactivityChecks();
        if (!fieldOn) {
            mHandler.postDelayed(mReturnToIdleStateRunnable, FIELD_OFF_IDLE_DELAY_MS);
        }
        if (!fieldOn && mEnableObserveModeOnFieldOff && mEnableObserveModeAfterTransaction) {
            Log.d(TAG, "onFieldChangeDetected: re-enable observe mode");
            mHandler.postDelayed(mEnableObserveModeAfterTransactionRunnable,
                RE_ENABLE_OBSERVE_MODE_DELAY_MS);
        }

        if (fieldOn && nfcHceLatencyEvents()) {
            mFieldOnTime = SystemClock.elapsedRealtime();
        }
        if (fieldOn) {
            // Acquire the wakelock when FIELD_ON is detected.
            acquireWakeLock();
        } else {
            // Release the wakelock when FIELD_OFF is detected.
            releaseWakeLock();
        }
    }

    public void onHostEmulationActivated() {
        Log.d(TAG, "onHostEmulationActivated");
        synchronized (mLock) {
            if (nfcHceLatencyEvents()) {
                Trace.beginAsyncSection(EVENT_HCE_ACTIVATED, 0);
            }
            rescheduleInactivityChecks();
            // Since transaction has started, we should only re-enable observe mode
            // at end of the transaction (not field off because we might get field off
            // in the middle of transaction)
            if (mEnableObserveModeAfterTransaction) {
                mEnableObserveModeOnFieldOff = false;
            }
            // Regardless of what happens, if we're having a tap again
            // activity up, close it
            Intent intent = new Intent(TapAgainDialog.ACTION_CLOSE);
            intent.setPackage(NfcInjector.getInstance().getNfcPackageName());
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            if (mState.get() != STATE_IDLE && mState.get() != STATE_POLLING_LOOP) {
                Log.e(TAG, "onHostEmulationActivated: Got activation event in non-idle state");
            }
            mState.set(STATE_W4_SELECT);
        }
    }

    public void onHostEmulationData(byte[] data) {
        Log.d(TAG, "onHostEmulationData");
        mHandler.removeCallbacks(mEnableObserveModeAfterTransactionRunnable);
        rescheduleInactivityChecks();
        String selectAid = findSelectAid(data);
        ComponentName resolvedService = null;
        ApduServiceInfo resolvedServiceInfo = null;
        AidResolveInfo resolveInfo = null;
        synchronized (mLock) {
            if (mState.get() == STATE_IDLE) {
                Log.e(TAG, "onHostEmulationData: Got data in idle state.");
                return;
            } else if (mState.get() == STATE_W4_DEACTIVATE) {
                Log.e(TAG, "onHostEmulationData: Dropping APDU in STATE_W4_DECTIVATE");
                return;
            }
            if (selectAid != null) {
                if (selectAid.equals(ANDROID_HCE_AID)) {
                    NfcService.getInstance().sendData(ANDROID_HCE_RESPONSE);
                    return;
                }
                resolveInfo = mAidCache.resolveAid(selectAid);
                if (resolveInfo == null || resolveInfo.services.size() == 0) {
                    if (selectAid.equals(NDEF_V1_AID) || selectAid.equals(NDEF_V2_AID)) {
                        Log.w(TAG,
                                "onHostEmulationData: Can't route NDEF AID, sending AID_NOT_FOUND");
                    } else if (!mPowerManager.isScreenOn()) {
                      Log.i(TAG,
                              "onHostEmulationData: Screen is off, sending AID_NOT_FOUND, "
                                      + "but not triggering bug report");
                    } else {
                        Log.w(TAG, "onHostEmulationData: Can't handle AID " + selectAid
                                + " sending AID_NOT_FOUND");
                        if (android.nfc.Flags.nfcEventListener()) {
                            notifyAidNotRoutedListener(selectAid);
                        }
                    }
                    NfcInjector.getInstance().getNfcEventLog().logEvent(
                            NfcEventProto.EventType.newBuilder()
                                    .setCeUnroutableAid(
                                        NfcEventProto.NfcCeUnroutableAid.newBuilder()
                                            .setAid(selectAid)
                                            .build())
                                    .build());
                    // Tell the remote we don't handle this AID
                    NfcService.getInstance().sendData(AID_NOT_FOUND);
                    return;
                }
                mLastSelectedAid = selectAid;
                if (resolveInfo.defaultService != null) {
                    // Resolve to default
                    // Check if resolvedService requires unlock
                    ApduServiceInfo defaultServiceInfo = resolveInfo.defaultService;
                    if (mStatsdUtils != null) {
                        mStatsdUtils.setCardEmulationEventCategory(resolveInfo.category);
                        mStatsdUtils.setCardEmulationEventUid(defaultServiceInfo.getUid());
                    }
                    if ((defaultServiceInfo.requiresUnlock()
                            || NfcService.getInstance().isSecureNfcEnabled())
                          && NfcInjector.getInstance().isDeviceLocked()) {
                        NfcService.getInstance().sendRequireUnlockIntent();
                        NfcService.getInstance().sendData(AID_NOT_FOUND);
                        if (DBG) Log.d(TAG, "onHostEmulationData: requiresUnlock()! show toast");
                        if (mStatsdUtils != null) {
                            mStatsdUtils.logCardEmulationWrongSettingEvent();
                        }
                        launchTapAgain(resolveInfo.defaultService, resolveInfo.category);
                        return;
                    }
                    if (defaultServiceInfo.requiresScreenOn() && !mPowerManager.isScreenOn()) {
                        NfcService.getInstance().sendData(AID_NOT_FOUND);
                        if (DBG) Log.d(TAG, "onHostEmulationData: requiresScreenOn()!");
                        if (mStatsdUtils != null) {
                            mStatsdUtils.logCardEmulationWrongSettingEvent();
                        }
                        return;
                    }
                    // In no circumstance should this be an OffHostService -
                    // we should never get this AID on the host in the first place
                    if (!defaultServiceInfo.isOnHost()) {
                        Log.e(TAG, "onHostEmulationData: AID that was meant to go off-host was "
                                + "routed to host. Check routing table configuration.");
                        NfcService.getInstance().sendData(AID_NOT_FOUND);
                        if (mStatsdUtils != null) {
                            mStatsdUtils.logCardEmulationNoRoutingEvent();
                        }
                        return;
                    }
                    resolvedService = defaultServiceInfo.getComponent();
                    resolvedServiceInfo = defaultServiceInfo;
                } else if (mActiveServiceName != null) {
                    for (ApduServiceInfo serviceInfo : resolveInfo.services) {
                        if (mActiveServiceName.equals(serviceInfo.getComponent())) {
                            resolvedService = mActiveServiceName;
                            resolvedServiceInfo = serviceInfo;
                            break;
                        }
                    }
                }
                if (resolvedService == null) {
                    // We have no default, and either one or more services.
                    // Ask the user to confirm.
                    // Just ignore all future APDUs until we resolve to only one
                    mState.set(STATE_W4_DEACTIVATE);
                    NfcStatsLog.write(NfcStatsLog.NFC_AID_CONFLICT_OCCURRED, selectAid);
                    if (android.nfc.Flags.nfcEventListener()) {
                        notifyAidConflictListener(selectAid);
                    }
                    if (mStatsdUtils != null) {
                        mStatsdUtils.setCardEmulationEventCategory(CardEmulation.CATEGORY_OTHER);
                        mStatsdUtils.logCardEmulationWrongSettingEvent();
                    }
                    if (DBG) Log.d(TAG, "onHostEmulationData: AID conflict, launch resolver");
                    launchResolver(selectAid, (ArrayList<ApduServiceInfo>)resolveInfo.services,
                        null, resolveInfo.category);
                    return;
                }
            }
            switch (mState.get()) {
                case STATE_W4_SELECT:
                    if (selectAid != null) {
                        int uid = resolvedServiceInfo.getUid();
                        if (mStatsdUtils != null) {
                            mStatsdUtils.setCardEmulationEventUid(uid);
                            mStatsdUtils.setCardEmulationEventCategory(resolveInfo.category);
                        }
                        UserHandle user =
                                UserHandle.getUserHandleForUid(uid);
                        Messenger existingService =
                                bindServiceIfNeededLocked(user.getIdentifier(), resolvedService);
                        if (existingService != null) {
                            Log.d(TAG, "onHostEmulationData: Send data to existing service");
                            NfcInjector.getInstance().getNfcEventLog().logEvent(
                                    NfcEventProto.EventType.newBuilder()
                                            .setCeRoutedAid(
                                                NfcEventProto.NfcCeRoutedAid.newBuilder()
                                                    .setAid(selectAid)
                                                    .setComponentInfo(
                                                        NfcEventProto.NfcComponentInfo.newBuilder()
                                                            .setPackageName(
                                                                resolvedService.getPackageName())
                                                            .setClassName(
                                                                resolvedService.getClassName())
                                                            .build())
                                                    .build())
                                            .build());
                            sendDataToServiceLocked(existingService, data);
                        } else {
                            // Waiting for service to be bound
                            Log.d(TAG, "onHostEmulationData: Waiting for new service.");
                            // Queue SELECT APDU to be used
                            mSelectApdu = data;
                            mState.set(STATE_W4_SERVICE);
                        }
                        if (mStatsdUtils != null) {
                            mStatsdUtils.notifyCardEmulationEventWaitingForResponse();
                        } else {
                            int statsdCategory =
                                    resolveInfo.category.equals(CardEmulation.CATEGORY_PAYMENT)
                                            ? CE_HCE_PAYMENT
                                            : CE_HCE_OTHER;
                            NfcStatsLog.write(
                                    NfcStatsLog.NFC_CARDEMULATION_OCCURRED,
                                    statsdCategory,
                                    "HCE",
                                    uid);
                        }
                    } else {
                        Log.d(TAG, "onHostEmulationData: Dropping non-select APDU "
                                + "in STATE_W4_SELECT");
                        NfcService.getInstance().sendData(UNKNOWN_ERROR);
                    }
                    break;
                case STATE_W4_SERVICE:
                    Log.d(TAG, "onHostEmulationData: Unexpected APDU in STATE_W4_SERVICE");
                    break;
                case STATE_XFER:
                    if (selectAid != null) {
                        UserHandle user =
                                UserHandle.getUserHandleForUid(resolvedServiceInfo.getUid());
                        Messenger existingService =
                                bindServiceIfNeededLocked(user.getIdentifier(), resolvedService);
                        if (existingService != null) {
                            sendDataToServiceLocked(existingService, data);
                        } else {
                            // Waiting for service to be bound
                            mSelectApdu = data;
                            mState.set(STATE_W4_SERVICE);
                        }
                    } else if (mActiveService != null) {
                        // Regular APDU data
                        sendDataToServiceLocked(mActiveService, data);
                    } else {
                        // No SELECT AID and no active service.
                        Log.d(TAG, "onHostEmulationData: Service no longer bound, dropping APDU");
                    }
                    break;
            }
        }
    }

    public void onHostEmulationDeactivated() {
        Log.d(TAG, "onHostEmulationDeactivated");
        synchronized (mLock) {
            if (mState.get() == STATE_IDLE) {
                Log.e(TAG, "onHostEmulationDeactivated: Got deactivation "
                        + "event while in idle state");
            }
            sendDeactivateToActiveServiceLocked(HostApduService.DEACTIVATION_LINK_LOSS);
            unbindServiceIfNeededLocked();
            returnToIdleState();

            clearAutoDisableObserveModeRunnableLocked();

            if (mEnableObserveModeAfterTransaction) {
                Log.d(TAG, "onHostEmulationDeactivated: re-enable observe mode.");
                mHandler.postDelayed(mEnableObserveModeAfterTransactionRunnable,
                    RE_ENABLE_OBSERVE_MODE_DELAY_MS);
            }

            if (mStatsdUtils != null) {
                mStatsdUtils.logCardEmulationDeactivatedEvent();
            }

            if (nfcHceLatencyEvents()) {
                Trace.endAsyncSection(EVENT_HCE_ACTIVATED, 0);

                long endTime = SystemClock.elapsedRealtime();
                long tapDuration = endTime - mFieldOnTime;
                if (tapDuration > mDeviceConfig.getSlowTapThresholdMillis()) {
                    PerfettoTrigger.trigger(TRIGGER_NAME_SLOW_TAP);
                }
            }
        }
    }

    public boolean isHostCardEmulationActivated() {
        int state = mState.get();
        return state != STATE_IDLE && state != STATE_POLLING_LOOP;
    }

    public void onOffHostAidSelectedOrTransaction() {
        Log.d(TAG, "onOffHostAidSelectedOrTransaction");
        synchronized (mLock) {
            mHandler.removeCallbacks(mEnableObserveModeAfterTransactionRunnable);
            rescheduleInactivityChecks();
            if (mState.get() != STATE_XFER || mActiveService == null) {
                // Don't bother telling, we're not bound to any service yet
            } else {
                sendDeactivateToActiveServiceLocked(HostApduService.DEACTIVATION_DESELECTED);
            }
            resetActiveService();
            unbindServiceIfNeededLocked();
            mState.set(STATE_W4_SELECT);

            //close the TapAgainDialog
            Intent intent = new Intent(TapAgainDialog.ACTION_CLOSE);
            intent.setPackage(NfcInjector.getInstance().getNfcPackageName());
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    private void acquireWakeLock() {
        if (!ceWakeLock() || mDeviceConfig.getCeWakeLockTimeoutMillis() == 0) return;
        Log.d(TAG, "acquireWakeLock");
        mWakeLock.setWorkSource(null); // reset work source from previous transaction
        mWakeLock.acquire(mDeviceConfig.getCeWakeLockTimeoutMillis());
    }

    private void updateWakeLockWorkSource(ComponentNameAndUser componentNameAndUser) {
        if (!ceWakeLock() || !mWakeLock.isHeld()) return;
        Log.d(TAG, "updateWakeLockWorkSource: " + componentNameAndUser);
        final String packageName = componentNameAndUser.getComponentName().getPackageName();
        try {
            int uid = mContext.getPackageManager().getPackageUidAsUser(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                    componentNameAndUser.getUserId()
            );
            // Re-acquire the wake lock with the new work source.
            mWakeLock.setWorkSource(new WorkSource(uid, packageName));
            mWakeLock.acquire(mDeviceConfig.getCeWakeLockTimeoutMillis());
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Failed to find uid for " + packageName + " and user "
                    + componentNameAndUser.getUserId());
        }
    }

    private void releaseWakeLock() {
        if (!ceWakeLock() || !mWakeLock.isHeld()) return;
        Log.d(TAG, "releaseWakeLock");
        mWakeLock.release();
        mWakeLock.setWorkSource(null);
    }

    Messenger bindServiceIfNeededLocked(@UserIdInt int userId, ComponentName service) {
        if (service == null) {
            Log.e(TAG, "bindServiceIfNeededLocked: service ComponentName is null");
            return null;
        } else {
            if (VDBG) Log.d(TAG, "bindServiceIfNeededLocked: service: " + service);
        }

        ComponentNameAndUser preferredPaymentService = mAidCache.getPreferredPaymentService();
        int preferredPaymentUserId = preferredPaymentService.getUserId();
        ComponentName preferredPaymentServiceName = preferredPaymentService.getComponentName();
        ComponentNameAndUser newServiceAndUser = new ComponentNameAndUser(userId, service);

        // When the service to handle this transaction is found, update the worksource
        // to share the power blame.
        updateWakeLockWorkSource(newServiceAndUser);

        if (mPaymentServiceName != null && mPaymentServiceName.equals(service)
                && mPaymentServiceUserId == userId) {
            if (VDBG) {
                Log.d(TAG, "bindServiceIfNeededLocked: Service already bound as payment service");
            }
            return mPaymentService;
        } else if (!mPaymentServiceBound && preferredPaymentServiceName != null
                && preferredPaymentServiceName.equals(service)
                && preferredPaymentUserId == userId) {
            Log.w(TAG, "bindServiceIfNeededLocked: Service should be bound as "
                    + "payment service but is not, binding now");
            bindPaymentServiceLocked(userId, preferredPaymentServiceName);
            return null;
        } else if (!isMultipleBindingSupported()
                && mServiceName != null
                && mServiceName.equals(service)
                && mServiceUserId == userId) {
            if (VDBG) {
                Log.d(TAG, "bindServiceIfNeededLocked: Service already bound as regular service");
            }
            return mService;
        } else if (isMultipleBindingSupported()
                && mComponentNameToConnectionsMap.containsKey(newServiceAndUser)
                && mComponentNameToConnectionsMap.get(newServiceAndUser).mMessenger != null) {
            if (VDBG) {
                Log.d(TAG, "bindServiceIfNeededLocked: Service" + service
                        + " already bound as regular service");
            }
            return mComponentNameToConnectionsMap.get(newServiceAndUser).mMessenger;
        } else {
            Log.d(TAG, "bindServiceIfNeededLocked: Binding to service " + service + " for userId:"
                    + userId);
            if (nfcHceLatencyEvents()) {
                Trace.beginAsyncSection(EVENT_HCE_BIND_SERVICE, 0);
            }
            if (mStatsdUtils != null) {
                mStatsdUtils.notifyCardEmulationEventWaitingForService();
            }
            unbindServiceIfNeededLocked();
            Intent aidIntent = new Intent(HostApduService.SERVICE_INTERFACE);
            aidIntent.setComponent(service);
            try {
                ServiceConnection connection = mConnection;
                if (isMultipleBindingSupported()) {
                    connection = new HostEmulationServiceConnection(userId);
                    mComponentNameToConnectionsMap.put(
                        new ComponentNameAndUser(userId, service),
                        new HostEmulationConnection(userId, service, connection));
                }
                boolean serviceBound =
                        mContext.bindServiceAsUser(
                                aidIntent,
                                connection,
                                Context.BIND_AUTO_CREATE
                                        | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS,
                                UserHandle.of(userId));
                if (!isMultipleBindingSupported()) {
                    mServiceBound = serviceBound;
                }
                if (!serviceBound) {
                    if (nfcHceLatencyEvents()) {
                        Trace.endAsyncSection(EVENT_HCE_BIND_SERVICE, 0);
                    }
                    Log.e(TAG, "bindServiceIfNeededLocked: Could not bind service");
                } else {
                    mServiceUserId = userId;
                }
            } catch (SecurityException e) {
                if (nfcHceLatencyEvents()) {
                    Trace.endAsyncSection(EVENT_HCE_BIND_SERVICE, 0);
                }
                Log.e(TAG, "bindServiceIfNeededLocked: Could not bind service "
                        + "due to security exception");
            }
            return null;
        }
    }

    private int generateApduAckCookie() {
        byte[] token = new byte[Integer.BYTES];
        mCookieRandom.nextBytes(token);
        return ByteBuffer.wrap(token).getInt();
    }

    void sendDataToServiceLocked(Messenger service, byte[] data) {
        if (DBG) Log.d(TAG, "sendDataToServiceLocked");

        mState.set(STATE_XFER);

        int cookie = 0;
        if (nfcHceLatencyEvents()) {
            cookie = generateApduAckCookie();
            Trace.beginAsyncSection(EVENT_HCE_COMMAND_APDU, cookie);
        }

        if (!Objects.equals(service, mActiveService)) {
            sendDeactivateToActiveServiceLocked(HostApduService.DEACTIVATION_DESELECTED);
            mActiveService = service;
            if (service.equals(mPaymentService)) {
                mActiveServiceName = mPaymentServiceName;
                mActiveServiceUserId = mPaymentServiceUserId;
            } else {
                if (isMultipleBindingSupported()) {
                    for (Map.Entry<ComponentNameAndUser, HostEmulationConnection> entry :
                        mComponentNameToConnectionsMap.entrySet()) {
                        if (service.equals(entry.getValue().mMessenger)) {
                            mActiveServiceName = entry.getKey().getComponentName();
                            mActiveServiceUserId = entry.getKey().getUserId();
                            break;
                        }
                    }
                } else {
                    mActiveServiceName = mServiceName;
                    mActiveServiceUserId = mServiceUserId;
                }
            }
        }
        Message msg = Message.obtain(null, HostApduService.MSG_COMMAND_APDU);
        Bundle dataBundle = new Bundle();
        dataBundle.putByteArray(DATA_KEY, data);
        msg.setData(dataBundle);
        msg.replyTo = mMessenger;
        if (nfcHceLatencyEvents()) {
            msg.arg1 = cookie;
        }
        try {
            NfcService.getInstance().notifyOemLogEvent(new OemLogItems
                    .Builder(OemLogItems.LOG_ACTION_HCE_DATA)
                    .setApduCommand(data)
                    .build());
            mActiveService.send(msg);
        } catch (RemoteException e) {
            if (nfcHceLatencyEvents()) {
                Trace.endAsyncSection(EVENT_HCE_COMMAND_APDU, cookie);
            }
            Log.e(TAG, "sendDataToServiceLocked: Remote service " + mActiveServiceName
                    + " has died, dropping APDU", e);
            if (Objects.equals(mActiveService, mPaymentService)) {
                Log.wtf(TAG, "sendDataToServiceLocked: Rebinding payment service", e);
                bindPaymentServiceLocked(mPaymentServiceUserId, mLastBoundPaymentServiceName);
            }
        }
    }

    void sendPollingFramesToServiceLocked(Messenger service,
            ArrayList<PollingFrame> pollingFrames) {
        if (!Objects.equals(service, mActiveService)) {
            if (!isMultipleBindingSupported()) {
                sendDeactivateToActiveServiceLocked(HostApduService.DEACTIVATION_DESELECTED);
            }
            mActiveService = service;
            if (service.equals(mPaymentService)) {
                mActiveServiceName = mPaymentServiceName;
                mActiveServiceUserId = mPaymentServiceUserId;
            } else {
                mActiveServiceName = mServiceName;
                mActiveServiceUserId = mServiceUserId;
            }
        }
        Message msg = Message.obtain(null, HostApduService.MSG_POLLING_LOOP);
        Bundle msgData = new Bundle();
        msgData.putParcelableArrayList(HostApduService.KEY_POLLING_LOOP_FRAMES_BUNDLE,
                pollingFrames);
        msg.setData(msgData);
        msg.replyTo = mMessenger;
        if (mState.get() == STATE_IDLE) {
            mState.set(STATE_POLLING_LOOP);
        }
        if (nfcHceLatencyEvents()) {
            int cookie = generateApduAckCookie();
            msg.arg1 = cookie;
            Trace.beginAsyncSection(EVENT_POLLING_FRAMES, cookie);
        }
        try {
            mActiveService.send(msg);
        } catch (RemoteException e) {
            Log.e(TAG, "sendPollingFramesToServiceLocked: Remote service " + mActiveServiceName
                    + " has died, dropping frames", e);
            allowOneTransaction();
            if (Objects.equals(mActiveService, mPaymentService)) {
                Log.wtf(TAG, "sendPollingFramesToServiceLocked: Rebinding payment service", e);
                bindPaymentServiceLocked(mPaymentServiceUserId, mLastBoundPaymentServiceName);
            }
        }
    }

    void sendDeactivateToActiveServiceLocked(int reason) {
        if (DBG) Log.d(TAG, "sendDeactivateToActiveServiceLocked: reason: " + reason);

        if (mActiveService == null) return;
        Message msg = Message.obtain(null, HostApduService.MSG_DEACTIVATED);
        msg.arg1 = reason;
        try {
            mActiveService.send(msg);
        } catch (RemoteException e) {
            // Don't care
        }
    }

    void unbindPaymentServiceLocked() {
        if (mPaymentServiceBound) {
            Log.d(TAG, "unbindPaymentServiceLocked: " + mPaymentServiceName);
            try {
                mContext.unbindService(mPaymentConnection);
                if (isMultipleBindingSupported()) {
                    mComponentNameToConnectionsMap.remove(
                        new ComponentNameAndUser(mPaymentServiceUserId, mPaymentServiceName));
                }
            } catch (Exception e) {
                Log.w(TAG, "unbindPaymentServiceLocked: Failed to unbind: " + mPaymentServiceName,
                        e);
            }
            mPaymentServiceBound = false;
        }

        mPaymentService = null;
        mPaymentServiceName = null;
        mPaymentServiceUserId = -1;
    }

    void bindPaymentServiceLocked(@UserIdInt int userId, ComponentName serviceName) {
        if (nfcHceLatencyEvents()) {
            Trace.beginAsyncSection(EVENT_HCE_BIND_PAYMENT_SERVICE, 0);
        }
        unbindPaymentServiceLocked();

        Log.d(TAG, "bindPaymentServiceLocked: " + serviceName + " for userId=" + userId);
        Intent intent = new Intent(HostApduService.SERVICE_INTERFACE);
        intent.setComponent(serviceName);
        try {
            if (isMultipleBindingSupported()) {
                mComponentNameToConnectionsMap.put(
                        new ComponentNameAndUser(userId, serviceName),
                        new HostEmulationConnection(userId, serviceName, mPaymentConnection));
            }
            if (mContext.bindServiceAsUser(intent, mPaymentConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS,
                    UserHandle.of(userId))) {
                mPaymentServiceBound = true;
                mPaymentServiceUserId = userId;
                mLastBoundPaymentServiceName = serviceName;
            } else {
                if (nfcHceLatencyEvents()) {
                    Trace.endAsyncSection(EVENT_HCE_BIND_PAYMENT_SERVICE, 0);
                }
                Log.e(TAG, "bindPaymentServiceLocked: Could not bind (persistent) "
                        + "payment service");
            }
        } catch (SecurityException e) {
            if (nfcHceLatencyEvents()) {
                Trace.endAsyncSection(EVENT_HCE_BIND_PAYMENT_SERVICE, 0);
            }
            Log.e(TAG, "bindPaymentServiceLocked: Could not bind service "
                    + "due to security exception");
        }
    }

    void unbindServiceIfNeededLocked() {
        if (isMultipleBindingSupported()) {
            if (mServiceName == null
                    || CompatChanges.isChangeEnabled(
                        DONT_IMMEDIATELY_UNBIND_SERVICES,
                        mServiceName.getPackageName(),
                        UserHandle.of(mServiceUserId))) {
                return;
            }
            if (mServiceName != null) {
                mComponentNameToConnectionsMap.remove(
                        new ComponentNameAndUser(mServiceUserId, mServiceName));
            }
        }

        if (mServiceBound) {
            Log.d(TAG, "unbindServiceIfNeededLocked: service " + mServiceName);
            try {
                mContext.unbindService(mConnection);
            } catch (Exception e) {
                Log.w(TAG, "unbindServiceIfNeededLocked: Failed to unbind " + mServiceName, e);
            }
            mServiceBound = false;
        }

        mService = null;
        mServiceName = null;
        mServiceUserId = -1;
    }

    void launchTapAgain(ApduServiceInfo service, String category) {
        if (DBG) {
            Log.d(TAG, "launchTapAgain: service=" + service.toString() + ", category=" + category);
        }
        if (mNfcOemExtensionCallback != null) {
            try {
                mNfcOemExtensionCallback.onLaunchHceTapAgainActivity(service, category);
                return;
            } catch (RemoteException e) {
                Log.e(TAG, "launchTapAgain: onLaunchHceTapAgainActivity failed", e);
            }
        }
        Intent dialogIntent = new Intent(mContext, TapAgainDialog.class);
        dialogIntent.putExtra(TapAgainDialog.EXTRA_CATEGORY, category);
        dialogIntent.putExtra(TapAgainDialog.EXTRA_APDU_SERVICE, service);
        dialogIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mContext.startActivityAsUser(dialogIntent,
                UserHandle.getUserHandleForUid(service.getUid()));
    }

    void launchResolver(String selectedAid, ArrayList<ApduServiceInfo> services,
        ComponentName failedComponent, String category) {
        if (mNfcOemExtensionCallback != null) {
            try {
                mNfcOemExtensionCallback.onLaunchHceAppChooserActivity(
                    selectedAid, services, failedComponent, category);
                return;
            } catch (RemoteException e) {
                Log.e(TAG, "launchResolver: onLaunchHceAppChooserActivity failed", e);
            }
        }
        Intent intent = new Intent(mContext, AppChooserActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putParcelableArrayListExtra(AppChooserActivity.EXTRA_APDU_SERVICES, services);
        intent.putExtra(AppChooserActivity.EXTRA_CATEGORY, category);
        if (failedComponent != null) {
            intent.putExtra(AppChooserActivity.EXTRA_FAILED_COMPONENT, failedComponent);
        }
        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
    }

    String findSelectAid(byte[] data) {
        if (data == null || data.length < SELECT_APDU_HDR_LENGTH + MINIMUM_AID_LENGTH) {
            if (DBG) Log.d(TAG, "findSelectAid: Data size too small for SELECT APDU");
            return null;
        }
        // To accept a SELECT AID for dispatch, we require the following:
        // Class byte must be 0x00: logical channel set to zero, no secure messaging, no chaining
        // Instruction byte must be 0xA4: SELECT instruction
        // P1: must be 0x04: select by application identifier
        // P2: File control information is only relevant for higher-level application,
        //     and we only support "first or only occurrence".
        if (data[0] == 0x00 && data[1] == INSTR_SELECT && data[2] == 0x04) {
            if (data[3] != 0x00) {
                Log.d(TAG, "findSelectAid: Selecting next, last or previous "
                        + "AID occurrence is not supported");
            }
            int aidLength = Byte.toUnsignedInt(data[4]);
            if (data.length < SELECT_APDU_HDR_LENGTH + aidLength) {
                return null;
            }
            return bytesToString(data, SELECT_APDU_HDR_LENGTH, aidLength);
        }
        return null;
    }

    @FlaggedApi(android.nfc.Flags.FLAG_NFC_EVENT_LISTENER)
    interface NfcAidRoutingListener {
        void onAidConflict(@NonNull String aid);
        void onAidNotRouted(@NonNull String aid);
    }

    @Nullable
    private NfcAidRoutingListener mAidRoutingListener = null;

    @FlaggedApi(android.nfc.Flags.FLAG_NFC_EVENT_LISTENER)
    void setAidRoutingListener(@Nullable NfcAidRoutingListener listener) {
        mAidRoutingListener = listener;
    }

    @FlaggedApi(android.nfc.Flags.FLAG_NFC_EVENT_LISTENER)
    private void notifyAidConflictListener(String aid) {
        if (mAidRoutingListener != null && aid != null) {
            mAidRoutingListener.onAidConflict(aid);
        }
    }

    @FlaggedApi(android.nfc.Flags.FLAG_NFC_EVENT_LISTENER)
    private void notifyAidNotRoutedListener(String aid) {
        if (mAidRoutingListener != null && aid != null) {
            mAidRoutingListener.onAidNotRouted(aid);
        }
    }

    private void returnToIdleStateLocked() {
        mPendingPollingLoopFrames = null;
        mPollingFramesToSend = null;
        mUnprocessedPollingFrames = null;
        resetActiveService();
        setPollingLoopStateLocked(PollingLoopState.EVALUATING_POLLING_LOOP);
        mState.set(STATE_IDLE);
    }

    private void resetActiveService() {
        mActiveService = null;
        mActiveServiceName = null;
        mActiveServiceUserId = -1;
    }

    private ServiceConnection mPaymentConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ComponentName paymentServiceName = new ComponentName("", "");
            synchronized (mLock) {
                /* Preferred Payment Service has been changed. */
                if (!mLastBoundPaymentServiceName.equals(name)) {
                    Log.i(TAG, "onServiceConnected: Ignoring bound payment service, " + name
                            + " != " + mLastBoundPaymentServiceName);
                    return;
                }
                mPaymentServiceName = name;
                mPaymentService = new Messenger(service);
                paymentServiceName = mPaymentServiceName;
                if (isMultipleBindingSupported()) {
                    if (mComponentNameToConnectionsMap.containsKey(
                        new ComponentNameAndUser(mPaymentServiceUserId, name))) {
                            mComponentNameToConnectionsMap.get(
                            new ComponentNameAndUser(mPaymentServiceUserId, name)).mMessenger =
                            mPaymentService;
                    } else {
                        mComponentNameToConnectionsMap.put(
                            new ComponentNameAndUser(mPaymentServiceUserId, name),
                            new HostEmulationConnection(mPaymentServiceUserId, name,
                                    this, mPaymentService));
                    }
                }
                Log.i(TAG, "onServiceConnected: Payment service bound: " + name);
                if (nfcHceLatencyEvents()) {
                    Trace.endAsyncSection(EVENT_HCE_BIND_PAYMENT_SERVICE, 0);
                }
            }
            NfcInjector.getInstance().getNfcEventLog().logEvent(
                    NfcEventProto.EventType.newBuilder()
                            .setPaymentServiceBindState(
                                NfcEventProto.NfcPaymentServiceBindState.newBuilder()
                                    .setBindState(NfcEventProto.BindState.SERVICE_CONNECTED)
                                    .setComponentInfo(
                                        NfcEventProto.NfcComponentInfo.newBuilder()
                                            .setPackageName(
                                                paymentServiceName.getPackageName())
                                            .setClassName(
                                                paymentServiceName.getClassName())
                                            .build())
                                    .build())
                            .build());

            if (mPollingFramesToSend != null && mPollingFramesToSend.containsKey(name)) {
                sendPollingFramesToServiceLocked(mPaymentService, mPollingFramesToSend.get(name));
                mPollingFramesToSend.remove(name);
                if (mUnprocessedPollingFrames != null) {
                    ArrayList unprocessedPollingFrames = mUnprocessedPollingFrames;
                    mUnprocessedPollingFrames = null;
                    onPollingLoopDetected(unprocessedPollingFrames);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "onServiceDisconnected: " + name);
            ComponentName paymentServiceName = new ComponentName("", "");
            synchronized (mLock) {
                if (isMultipleBindingSupported()) {
                    ComponentNameAndUser nameAndUser =
                            new ComponentNameAndUser(mPaymentServiceUserId, name);
                    mComponentNameToConnectionsMap.remove(nameAndUser);
                }
                if (mPaymentServiceName != null) paymentServiceName = mPaymentServiceName;
                mPaymentService = null;
                mPaymentServiceName = null;
            }
            NfcInjector.getInstance().getNfcEventLog().logEvent(
                    NfcEventProto.EventType.newBuilder()
                            .setPaymentServiceBindState(
                                NfcEventProto.NfcPaymentServiceBindState.newBuilder()
                                    .setBindState(NfcEventProto.BindState.SERVICE_DISCONNECTED)
                                    .setComponentInfo(
                                        NfcEventProto.NfcComponentInfo.newBuilder()
                                            .setPackageName(
                                                paymentServiceName.getPackageName())
                                            .setClassName(
                                                paymentServiceName.getClassName())
                                            .build())
                                    .build())
                            .build());
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.i(TAG, "onBindingDied: " + name);
            ComponentName paymentServiceName = new ComponentName("", "");
            synchronized (mLock) {
                if (mPaymentServiceName != null) paymentServiceName = mPaymentServiceName;
                if (mPaymentServiceUserId >= 0) {
                    bindPaymentServiceLocked(mPaymentServiceUserId, mLastBoundPaymentServiceName);
                }
            }
            NfcInjector.getInstance().getNfcEventLog().logEvent(
                    NfcEventProto.EventType.newBuilder()
                            .setPaymentServiceBindState(
                                NfcEventProto.NfcPaymentServiceBindState.newBuilder()
                                    .setBindState(NfcEventProto.BindState.SERVICE_BINDING_DIED)
                                    .setComponentInfo(
                                        NfcEventProto.NfcComponentInfo.newBuilder()
                                            .setPackageName(
                                                paymentServiceName.getPackageName())
                                            .setClassName(
                                                paymentServiceName.getClassName())
                                            .build())
                                    .build())
                            .build());
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.i(TAG, "onNullBinding: " + name);
            synchronized (mLock) {
                mContext.unbindService(this);
            }

            NfcInjector.getInstance().getNfcEventLog().logEvent(
                    NfcEventProto.EventType.newBuilder()
                            .setPaymentServiceBindState(
                                NfcEventProto.NfcPaymentServiceBindState.newBuilder()
                                    .setBindState(NfcEventProto.BindState.SERVICE_NULL_BINDING)
                                    .setComponentInfo(
                                        NfcEventProto.NfcComponentInfo.newBuilder()
                                            .setPackageName(
                                                name.getPackageName())
                                            .setClassName(
                                                name.getClassName())
                                            .build())
                                    .build())
                            .build());
        }
    };

    class HostEmulationServiceConnection implements ServiceConnection {
        @UserIdInt int mUserId;

        HostEmulationServiceConnection(@UserIdInt int userId) {
            mUserId = userId;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                ComponentNameAndUser preferredUserAndService = mAidCache.getPreferredService();
                ComponentName preferredServiceName =
                        preferredUserAndService == null ? null :
                                preferredUserAndService.getComponentName();
                /* Service is already deactivated and not preferred, don't bind */
                if (mState.get() == STATE_IDLE && !name.equals(preferredServiceName)) {
                    try {
                        mContext.unbindService(this);
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "Failed to unbind " + name, e);
                    }
                    mComponentNameToConnectionsMap.remove(
                            new ComponentNameAndUser(mUserId, name));
                    return;
                }
                Messenger messenger = new Messenger(service);
                if (isMultipleBindingSupported()) {
                    ComponentNameAndUser key = new ComponentNameAndUser(mUserId, name);
                    if (mComponentNameToConnectionsMap.containsKey(key)) {
                        mComponentNameToConnectionsMap.get(key).mMessenger = messenger;
                    } else {
                        mComponentNameToConnectionsMap.put(key,
                            new HostEmulationConnection(mUserId, name, this, messenger));
                    }
                } else {
                    mService = messenger;
                    mServiceName = name;
                    mServiceBound = true;
                }

                if (nfcHceLatencyEvents()) {
                    Trace.endAsyncSection(EVENT_HCE_BIND_SERVICE, 0);
                }
                Log.d(TAG, "onServiceConnected: Service bound: " + name);

                // Send pending select APDU
                if (mSelectApdu != null) {
                    if (mStatsdUtils != null) {
                        mStatsdUtils.notifyCardEmulationEventServiceBound();
                    }
                    NfcInjector.getInstance().getNfcEventLog().logEvent(
                            NfcEventProto.EventType.newBuilder()
                                    .setCeRoutedAid(
                                        NfcEventProto.NfcCeRoutedAid.newBuilder()
                                            .setAid(mLastSelectedAid == null
                                                        ? "" : mLastSelectedAid)
                                            .setComponentInfo(
                                                NfcEventProto.NfcComponentInfo.newBuilder()
                                                    .setPackageName(
                                                        name.getPackageName())
                                                    .setClassName(
                                                        name.getClassName())
                                                    .build())
                                            .build())
                                    .build());
                    sendDataToServiceLocked(messenger, mSelectApdu);
                    mSelectApdu = null;
                } else if (mPollingFramesToSend != null && mPollingFramesToSend.containsKey(name)) {
                    sendPollingFramesToServiceLocked(messenger, mPollingFramesToSend.get(name));
                    mPollingFramesToSend.remove(name);
                    if (mUnprocessedPollingFrames != null) {
                        ArrayList unprocessedPollingFrames = mUnprocessedPollingFrames;
                        mUnprocessedPollingFrames = null;
                        onPollingLoopDetected(unprocessedPollingFrames);
                    }
                } else {
                    Log.d(TAG, "onServiceConnected: bound with nothing to send");
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                Log.d(TAG, "onServiceDisconnected: unbound: " + name);
                if (isMultipleBindingSupported()) {
                    ComponentNameAndUser nameAndUser = new ComponentNameAndUser(mUserId, name);
                    mComponentNameToConnectionsMap.remove(nameAndUser);
                } else {
                    mService = null;
                    mServiceName = null;
                    mServiceBound = false;
                }
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.i(TAG, "onBindingDied: " + name);
            unbindServiceIfNeededLocked();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.i(TAG, "onNullBinding: " + name);
            synchronized (mLock) {
                mContext.unbindService(this);
            }
        }
    };

    private ServiceConnection mConnection =
        new HostEmulationServiceConnection(UserHandle.CURRENT.getIdentifier());

    class MessageHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            synchronized(mLock) {
                if (mActiveService == null) {
                    Log.d(TAG, "handleMessage: Dropping service response message; "
                            + "service no longer active.");
                    return;
                } else if (!msg.replyTo.getBinder().equals(mActiveService.getBinder())) {
                    Log.d(TAG, "handleMessage: Dropping service response message; "
                            + "service no longer bound.");
                    return;
                }
            }
            if (msg.what == HostApduService.MSG_RESPONSE_APDU) {
                if (DBG) Log.d(TAG, "handleMessage: MSG_RESPONSE_APDU");
                Bundle dataBundle = msg.getData();
                if (dataBundle == null) {
                    return;
                }
                byte[] data = dataBundle.getByteArray(DATA_KEY);
                if (data == null || data.length == 0) {
                    Log.e(TAG, "handleMessage: Dropping empty R-APDU");
                    return;
                }
                if (mState.get() == STATE_XFER) {
                    Log.d(TAG, "handleMessage: Sending data");
                    NfcService.getInstance().sendData(data);
                    if (mStatsdUtils != null) {
                        mStatsdUtils.notifyCardEmulationEventResponseReceived();
                    }
                } else {
                    Log.d(TAG,
                            "handleMessage: Dropping data, wrong state " + mState.get());
                }

                if (nfcHceLatencyEvents()) {
                    try {
                        Message ackMsg = Message.obtain(null,
                                HostApduService.MSG_RESPONSE_APDU_ACK);
                        ackMsg.arg1 = msg.arg1;
                        ackMsg.replyTo = mMessenger;
                        msg.replyTo.send(ackMsg);
                    } catch (RemoteException e) {
                        Log.e(TAG, "handleMessage: Failed to acknowledge MSG_RESPONSE_APDU", e);
                    }
                }
            } else if (msg.what == HostApduService.MSG_UNHANDLED) {
                synchronized (mLock) {
                    Log.d(TAG, "handleMessage: Received MSG_UNHANDLED");
                    AidResolveInfo resolveInfo = mAidCache.resolveAid(mLastSelectedAid);

                    if (resolveInfo.services.size() > 0) {
                        NfcStatsLog.write(NfcStatsLog.NFC_AID_CONFLICT_OCCURRED, mLastSelectedAid);
                        if (android.nfc.Flags.nfcEventListener()) {
                            notifyAidConflictListener(mLastSelectedAid);
                        }
                        launchResolver(mLastSelectedAid,
                            (ArrayList<ApduServiceInfo>)resolveInfo.services,
                            mActiveServiceName, resolveInfo.category);
                    }
                }
            } else if (msg.what == HostApduService.MSG_COMMAND_APDU_ACK) {
                if (DBG) {
                    Log.d(TAG, "handleMessage: MSG_COMMAND_APDU_ACK");
                }
                if (nfcHceLatencyEvents()) {
                    Trace.endAsyncSection(EVENT_HCE_COMMAND_APDU, msg.arg1);
                }
            } else if (msg.what == HostApduService.MSG_POLLING_LOOP_ACK) {
                if (nfcHceLatencyEvents()) {
                    Trace.endAsyncSection(EVENT_POLLING_FRAMES, msg.arg1);
                }
            }
        }
    }

    static String bytesToString(byte[] bytes, int offset, int length) {
        final char[] hexChars = {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
        char[] chars = new char[length * 2];
        int byteValue;
        for (int j = 0; j < length; j++) {
            byteValue = bytes[offset + j] & 0xFF;
            chars[j * 2] = hexChars[byteValue >>> 4];
            chars[j * 2 + 1] = hexChars[byteValue & 0x0F];
        }
        return new String(chars);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Bound HCE-A/HCE-B services: ");
        if (mPaymentServiceBound) {
            pw.println("    payment: " + mPaymentServiceName);
        }
        if (isMultipleBindingSupported() && !mComponentNameToConnectionsMap.isEmpty()) {
            pw.println("    others: ");
            for (Map.Entry<ComponentNameAndUser, HostEmulationConnection> entry :
               mComponentNameToConnectionsMap.entrySet()) {
                pw.println("            " + entry.getKey());
            }
        } else if (mServiceBound) {
            pw.println("    other: " + mServiceName);
        }
    }

    /**
     * Dump debugging information as a HostEmulationManagerProto
     *
     * Note:
     * See proto definition in frameworks/base/core/proto/android/nfc/card_emulation.proto
     * When writing a nested message, must call {@link ProtoOutputStream#start(long)} before and
     * {@link ProtoOutputStream#end(long)} after.
     * Never reuse a proto field number. When removing a field, mark it as reserved.
     */
    void dumpDebug(ProtoOutputStream proto) {
        if (mPaymentServiceBound) {
            Utils.dumpDebugComponentName(
                    mPaymentServiceName, proto, HostEmulationManagerProto.PAYMENT_SERVICE_NAME);
        }
        // TODO make this a repeated field and return all the services
        if (mServiceBound) {
            Utils.dumpDebugComponentName(
                    mServiceName, proto, HostEmulationManagerProto.SERVICE_NAME);
        }
    }

    boolean isMultipleBindingSupported() {
        return Flags.allowMultipleHceBindings();
    }

    @VisibleForTesting
    public int getState() {
        return mState.get();
    }

    @VisibleForTesting
    public ServiceConnection getServiceConnection() {
        return mConnection;
    }

    @VisibleForTesting
    public ServiceConnection getPaymentConnection() {
        return mPaymentConnection;
    }

    @VisibleForTesting
    public IBinder getMessenger() {
        if (mActiveService != null) {
            return mActiveService.getBinder();
        }
        return null;
    }

    @VisibleForTesting
    public Messenger getLocalMessenger() {
        return mMessenger;
    }

    @VisibleForTesting
    public ComponentName getServiceName() {
        return mLastBoundPaymentServiceName;
    }

    @VisibleForTesting
    public Boolean isServiceBounded(@UserIdInt int userId, ComponentName componentName) {
        if (isMultipleBindingSupported()) {
            return mComponentNameToConnectionsMap.containsKey(
                    new ComponentNameAndUser(userId, componentName));
        } else {
            return mServiceBound;
        }
    }

    @VisibleForTesting
    public Map<Integer, Map<String, List<ApduServiceInfo>>> getPollingLoopFilters() {
        return mPollingLoopFilters;
    }

    @VisibleForTesting
    public Map<Integer, Map<Pattern, List<ApduServiceInfo>>> getPollingLoopPatternFilters() {
        return mPollingLoopPatternFilters;
    }

    /**
     *  Return to idle state
     */
    public void returnToIdleState() {
        synchronized (mLock) {
            returnToIdleStateLocked();
        }
    }
}
