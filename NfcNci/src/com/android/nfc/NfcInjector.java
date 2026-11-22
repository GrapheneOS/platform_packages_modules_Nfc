/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.nfc;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.backup.BackupManager;
import android.content.ApexEnvironment;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.nfc.Constants;
import android.nfc.NfcFrameworkInitializer;
import android.nfc.NfcServiceManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.se.omapi.ISecureElementService;
import android.se.omapi.SeFrameworkInitializer;
import android.se.omapi.SeServiceManager;
import android.util.AtomicFile;
import android.util.Log;

import com.android.nfc.cardemulation.CardEmulationManager;
import com.android.nfc.cardemulation.util.StatsdUtils;
import com.android.nfc.cardemulation.util.StatsdUtilsContext;
import com.android.nfc.dhimpl.NativeNfcManager;
import com.android.nfc.flags.Flags;
import com.android.nfc.handover.HandoverDataParser;
import com.android.nfc.wlc.NfcCharging;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * To be used for dependency injection (especially helps mocking static dependencies).
 * TODO: Migrate more classes to injector to resolve circular dependencies in the NFC stack.
 */
public class NfcInjector {
    private static final String TAG = "NfcInjector";
    private static final String APEX_NAME = "com.android.nfcservices";
    private static final String NFC_DATA_DIR = "/data/nfc";
    private static final String EVENT_LOG_FILE_NAME = "event_log.binpb";

    private final Context mContext;
    private final Looper mMainLooper;
    private final NfcEventLog mNfcEventLog;
    private final RoutingTableParser mRoutingTableParser;
    private final RfDiscoverCmdParser mRfDiscoverCmdParser;
    private final ScreenStateHelper mScreenStateHelper;
    private final NfcUnlockManager mNfcUnlockManager;
    private final HandoverDataParser mHandoverDataParser;
    private final DeviceConfigFacade mDeviceConfigFacade;
    private final NfcDispatcher mNfcDispatcher;
    private final VibrationEffect mVibrationEffect;
    private final BackupManager mBackupManager;
    @Nullable
    private final StatsdUtils mStatsdUtils;
    @Nullable
    private final StatsdUtilsContext mStatsdUtilsContext;
    private final ForegroundUtils mForegroundUtils;
    private final NfcDiagnostics mNfcDiagnostics;
    private final NfcServiceManager.ServiceRegisterer mNfcManagerRegisterer;
    private final NfcWatchdog mNfcWatchdog;
    private KeyguardManager mKeyguardManager;
    private static NfcInjector sInstance;
    private CardEmulationManager mCardEmulationManager;

    public static NfcInjector getInstance() {
        if (sInstance == null) throw new IllegalStateException("Nfc injector instance null");
        return sInstance;
    }

    static void setNfcInjector(NfcInjector nfcInjector) {
        sInstance = nfcInjector;
    }


    public NfcInjector(@NonNull Context context, @NonNull Looper mainLooper) {
        if (sInstance != null) throw new IllegalStateException("Nfc injector instance not null");

        mContext = context;
        mMainLooper = mainLooper;
        mRoutingTableParser = new RoutingTableParser();
        mRfDiscoverCmdParser = new RfDiscoverCmdParser();
        mScreenStateHelper = new ScreenStateHelper(mContext);
        mNfcUnlockManager = NfcUnlockManager.getInstance();
        mHandoverDataParser = new HandoverDataParser();
        mDeviceConfigFacade = new DeviceConfigFacade(mContext, new Handler(mainLooper));
        mNfcDispatcher =
            new NfcDispatcher(mContext, mHandoverDataParser, this,
                    isInProvisionMode(), mDeviceConfigFacade);
        mVibrationEffect = VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE);
        mBackupManager = new BackupManager(mContext);
        mStatsdUtilsContext = Flags.statsdCeEventsFlag() ? new StatsdUtilsContext() : null;
        mStatsdUtils = Flags.statsdCeEventsFlag() ? new StatsdUtils(mStatsdUtilsContext) : null;
        mForegroundUtils =
                ForegroundUtils.getInstance(mContext.getSystemService(ActivityManager.class));
        mNfcDiagnostics = new NfcDiagnostics(mContext);

        NfcServiceManager manager = NfcFrameworkInitializer.getNfcServiceManager();
        if (manager == null) {
            Log.e(TAG, "NfcServiceManager is null");
            throw new UnsupportedOperationException();
        }
        mNfcManagerRegisterer = manager.getNfcManagerServiceRegisterer();

        // Create UWB event log thread.
        HandlerThread eventLogThread = new HandlerThread("NfcEventLog");
        eventLogThread.start();
        mNfcEventLog = new NfcEventLog(mContext, this, eventLogThread.getLooper(),
                new AtomicFile(new File(NFC_DATA_DIR, EVENT_LOG_FILE_NAME)));
        mNfcWatchdog = new NfcWatchdog(mContext);

        mKeyguardManager = mContext
                .createContextAsUser(UserHandle.of(ActivityManager.getCurrentUser()), 0)
                .getSystemService(KeyguardManager.class);
        sInstance = this;
    }

    public CardEmulationManager getCardEmulationManager() {
        if (mCardEmulationManager == null) {
            mCardEmulationManager = new CardEmulationManager(mContext, sInstance, mDeviceConfigFacade);
        }
        return mCardEmulationManager;
    }

    public NfcCharging getNfcCharging(DeviceHost deviceHost) {
        return new NfcCharging(mContext, deviceHost);
    }

    public Context getContext() {
        return mContext;
    }

    public Looper getMainLooper() {
        return mMainLooper;
    }

    public NfcEventLog getNfcEventLog() {
        return mNfcEventLog;
    }

    public ScreenStateHelper getScreenStateHelper() {
        return mScreenStateHelper;
    }

    public RoutingTableParser getRoutingTableParser() {
        return mRoutingTableParser;
    }

    public RfDiscoverCmdParser getRfDiscoverCmdParser() {
        return mRfDiscoverCmdParser;
    }

    public NfcUnlockManager getNfcUnlockManager() {
        return mNfcUnlockManager;
    }

    public HandoverDataParser getHandoverDataParser() {
        return mHandoverDataParser;
    }

    public DeviceConfigFacade getDeviceConfigFacade() {
        return mDeviceConfigFacade;
    }

    public NfcDispatcher getNfcDispatcher() {
        return mNfcDispatcher;
    }

    public VibrationEffect getVibrationEffect() {
        return mVibrationEffect;
    }

    public BackupManager getBackupManager() {
        return mBackupManager;
    }

    @Nullable
    public StatsdUtils getStatsdUtils() {
        return mStatsdUtils;
    }

    @Nullable
    public StatsdUtilsContext getStatsdUtilsContext() {
        return mStatsdUtilsContext;
    }

    public ForegroundUtils getForegroundUtils() {
        return mForegroundUtils;
    }

    public NfcDiagnostics getNfcDiagnostics() {
        return mNfcDiagnostics;
    }

    public NfcServiceManager.ServiceRegisterer getNfcManagerRegisterer() {
        return mNfcManagerRegisterer;
    }

    public NfcWatchdog getNfcWatchdog() {
        return mNfcWatchdog;
    }

    public DeviceHost makeDeviceHost(DeviceHost.DeviceHostListener listener) {
        return new NativeNfcManager(mContext, listener);
    }

    /**
     * NFC apex DE folder.
     */
    public static File getDeviceProtectedDataDir() {
        return ApexEnvironment.getApexEnvironment(APEX_NAME)
                .getDeviceProtectedDataDir();
    }

    public LocalDateTime getLocalDateTime() {
        return LocalDateTime.now();
    }

    public String getNfcPackageName() {
        return mContext.getPackageName();
    }

    public boolean isInProvisionMode() {
        boolean isNfcProvisioningEnabled = false;
        try {
            isNfcProvisioningEnabled = mContext.getResources().getBoolean(
                    R.bool.enable_nfc_provisioning);
        } catch (Resources.NotFoundException e) {
        }

        if (isNfcProvisioningEnabled) {
            return Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.DEVICE_PROVISIONED, 0) == 0;
        } else {
            return false;
        }
    }

    public ISecureElementService connectToSeService() throws RemoteException {
        SeServiceManager manager = SeFrameworkInitializer.getSeServiceManager();
        if (manager == null) {
            Log.e(TAG, "connectToSeService: SEServiceManager is null");
            return null;
        }
        return ISecureElementService.Stub.asInterface(
                manager.getSeManagerServiceRegisterer().get());
    }

    /**
     * Kill the NFC stack.
     */
    public void killNfcStack() {
        System.exit(0);
    }

    public boolean isSatelliteModeSensitive() {
        final String satelliteRadios =
                Settings.Global.getString(mContext.getContentResolver(),
                        Constants.SETTINGS_SATELLITE_MODE_RADIOS);
        return satelliteRadios == null || satelliteRadios.contains(Settings.Global.RADIO_NFC);
    }

    /** Returns true if satellite mode is turned on. */
    public boolean isSatelliteModeOn() {
        if (!isSatelliteModeSensitive()) return false;
        return Settings.Global.getInt(
                mContext.getContentResolver(), Constants.SETTINGS_SATELLITE_MODE_ENABLED, 0) == 1;
    }

    int getAutoOffTimeoutMillis() {
        return Settings.Global.getInt(
                mContext.getContentResolver(), Settings.Global.NFC_AUTO_OFF, 0);
    }

    public static boolean isPrivileged(int callingUid) {
        // Check for root uid to help invoking privileged APIs from rooted shell only.
        return callingUid == Process.SYSTEM_UID || callingUid == Process.NFC_UID
                || callingUid == Process.ROOT_UID;
    }

    /**
     * Get the current time of the clock in milliseconds.
     *
     * @return Current time in milliseconds.
     */
    public long getWallClockMillis() {
        return System.currentTimeMillis();
    }

    /**
     * Returns milliseconds since boot, including time spent in sleep.
     *
     * @return Current time since boot in milliseconds.
     */
    public long getElapsedSinceBootMillis() {
        return SystemClock.elapsedRealtime();
    }

    /**
     * Returns nanoseconds since boot, including time spent in sleep.
     *
     * @return Current time since boot in milliseconds.
     */
    public long getElapsedSinceBootNanos() {
        return SystemClock.elapsedRealtimeNanos();
    }

    /**
     * Ensure that the watchdog is monitoring the NFC process.
     */
    public void ensureWatchdogMonitoring() {
        mNfcWatchdog.ensureWatchdogMonitoring();
    }

    public AtomicBoolean createAtomicBoolean() {
        return new AtomicBoolean();
    }

    /**
     * Temporary location to store nfc properties being added in Android 16 for OEM convergence.
     * Will move all of these together to libsysprop later to avoid multiple rounds of API reviews.
     */
    public static final class NfcProperties {
        private static final String NFC_EUICC_SUPPORTED_PROP_KEY = "ro.nfc.euicc_supported";

        public static boolean isEuiccSupported() {
            return SystemProperties.getBoolean(NFC_EUICC_SUPPORTED_PROP_KEY, true);
        }

    }

    /**
     * Returns whether the device unlocked or not.
     *
     * Need to update mKeyguardManager when user swithed
     * @see #onUserSwitched()
     */
    public boolean isDeviceLocked() {
        return (isInProvisionMode()
            || android.app.Flags.deviceUnlockListener())
                            ? mKeyguardManager.isDeviceLocked()
                            : mKeyguardManager.isKeyguardLocked();
    }

    /**
     * Returns whether boot completed or not (used to detect if NFC stack crashed and came back up).
     * @return
     */
    public boolean isBootCompleted() {
        return "1".equals(SystemProperties.get("sys.boot_completed"));
    }

    /**
     * Returns whether the uid is signed with the same key as the platform.
     */
    public boolean isSignedWithPlatformKey(int uid) {
        return mContext.getPackageManager().checkSignatures(uid, Process.SYSTEM_UID)
                == PackageManager.SIGNATURE_MATCH;
    }

    /** Creates a looper to handle broadcasts within the {@link NfcService}. */
    public Looper getNfcBroadcastLooper() {
        HandlerThread handlerThread = new HandlerThread("NfcBroadcastThread");
        handlerThread.start();
        return handlerThread.getLooper();
    }

    /**
     * Refresh context when user switched
     *
     * isDeviceLocked() is based on context userId.
     */
    public void onUserSwitched() {
        mKeyguardManager = mContext
                .createContextAsUser(UserHandle.of(ActivityManager.getCurrentUser()), 0)
                .getSystemService(KeyguardManager.class);
    }

    /** Creates a NfcTagAllowNotification object */
    public NfcTagAllowNotification createNfcTagAllowNotification(
            Context context, List<String> appNames, boolean allow) {
        return new NfcTagAllowNotification(context, appNames, allow);
    }

}
