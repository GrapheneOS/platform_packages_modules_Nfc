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

package com.android.nfc;

import static android.Manifest.permission.BIND_NFC_SERVICE;
import static android.Manifest.permission.NFC_PREFERRED_PAYMENT_INFO;
import static android.Manifest.permission.NFC_TRANSACTION_EVENT;
import static android.content.Intent.ACTION_BOOT_COMPLETED;
import static android.content.Intent.ACTION_LOCKED_BOOT_COMPLETED;
import static android.nfc.OemLogItems.EVENT_DISABLE;
import static android.nfc.OemLogItems.EVENT_ENABLE;

import static com.android.nfc.ScreenStateHelper.SCREEN_STATE_ON_LOCKED;
import static com.android.nfc.ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED;
import static com.android.nfc.module.flags.Flags.nfcstack26q2Updates;
import static com.android.nfc.module.nonexported.flags.Flags.coalesceRfFieldOnOffBroadcasts;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Application;
import android.app.BroadcastOptions;
import android.app.KeyguardManager;
import android.app.KeyguardManager.DeviceLockedStateListener;
import android.app.KeyguardManager.KeyguardLockedStateListener;
import android.app.PendingIntent;
import android.app.admin.SecurityLog;
import android.app.backup.BackupManager;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.media.SoundPool.OnLoadCompleteListener;
import android.net.Uri;
import android.nfc.AvailableNfcAntenna;
import android.nfc.Constants;
import android.nfc.Entry;
import android.nfc.ErrorCodes;
import android.nfc.FormatException;
import android.nfc.IAppCallback;
import android.nfc.INfcAdapter;
import android.nfc.INfcAdapterExtras;
import android.nfc.INfcCardEmulation;
import android.nfc.INfcControllerAlwaysOnListener;
import android.nfc.INfcDta;
import android.nfc.INfcFCardEmulation;
import android.nfc.INfcOemExtensionCallback;
import android.nfc.INfcTag;
import android.nfc.INfcUnlockHandler;
import android.nfc.INfcVendorNciCallback;
import android.nfc.INfcWlcStateListener;
import android.nfc.IReaderCallback;
import android.nfc.IT4tNdefNfcee;
import android.nfc.ITagRemovedCallback;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.NfcAntennaInfo;
import android.nfc.NfcOemExtension;
import android.nfc.OemLogItems;
import android.nfc.RfDiscoverConfig;
import android.nfc.T4tNdefNfcee;
import android.nfc.T4tNdefNfceeCcFileInfo;
import android.nfc.Tag;
import android.nfc.TechListParcel;
import android.nfc.TransceiveResult;
import android.nfc.WlcListenerDeviceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.PollingFrame;
import android.nfc.tech.Ndef;
import android.nfc.tech.TagTechnology;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.PowerManager.OnThermalStatusChangedListener;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.se.omapi.ISecureElementService;
import android.sysprop.NfcProperties;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;
import android.util.proto.ProtoOutputStream;
import android.widget.Toast;

import androidx.annotation.VisibleForTesting;

import com.android.internal.annotations.GuardedBy;
import com.android.modules.utils.build.SdkLevel;
import com.android.nfc.DeviceHost.DeviceHostListener;
import com.android.nfc.DeviceHost.TagEndpoint;
import com.android.nfc.cardemulation.CardEmulationManager;
import com.android.nfc.cardemulation.HostEmulationManager;
import com.android.nfc.cardemulation.RoutingOptionManager;
import com.android.nfc.cardemulation.util.StatsdUtils;
import com.android.nfc.dhimpl.NativeNfcManager;
import com.android.nfc.flags.Flags;
import com.android.nfc.handover.HandoverDataParser;
import com.android.nfc.proto.NfcEventProto;
import com.android.nfc.wlc.NfcCharging;

import com.google.protobuf.ByteString;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class NfcService implements DeviceHostListener, ForegroundUtils.Callback {
    static final boolean DBG = NfcProperties.debug_enabled().orElse(true);
    static final boolean VDBG = NfcProperties.verbose_debug_enabled().orElse(false);
    static final String TAG = "NfcService";
    private static final int APP_INFO_FLAGS_SYSTEM_APP =
            ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;

    public static final String SERVICE_NAME = "nfc";

    private static final String SYSTEM_UI = "com.android.systemui";

    public static final String PREF = "NfcServicePrefs";
    public static final String PREF_TAG_APP_LIST = nfcstack26q2Updates()
                ? "TagIntentAppPreferenceListPrefsV2" : "TagIntentAppPreferenceListPrefs";
    public static final String GESTURE_EXCHAGE_AID = "A00000047609";
    public static final String GESTURE_EXCHAGE_SECONDARY_AID_SETTINGS_KEY =
            "nfc.gesture_exchange_secondary_aid";
    public static final String GESTURE_EXCHANGE_COMPONENT_SETTINGS_KEY =
            "nfc.gesture_exchange_component";
    static final String PREF_NFC_ON = "nfc_on";

    static final String PREF_NFC_READER_OPTION_ON = "nfc_reader_on";

    static final String PREF_NFC_CHARGING_ON = "nfc_charging_on";
    static final boolean NFC_CHARGING_ON_DEFAULT = true;

    static final String PREF_MIGRATE_TO_DE_COMPLETE = "migrate_to_de_complete";
    static final String PREF_SECURE_NFC_ON = "secure_nfc_on";
    static final String PREF_FIRST_BOOT = "first_boot";

    static final String PREF_ANTENNA_BLOCKED_MESSAGE_SHOWN = "antenna_blocked_message_shown";
    static final boolean ANTENNA_BLOCKED_MESSAGE_SHOWN_DEFAULT = false;

    static final String NATIVE_LOG_FILE_NAME = "native_crash_logs";
    static final String NATIVE_LOG_FILE_PATH = "/data/misc/nfc/logs";
    static final int NATIVE_CRASH_FILE_SIZE = 1024 * 1024;
    private static final String WAIT_FOR_OEM_ALLOW_BOOT_TIMER_TAG = "NfcWaitForSimTag";
    static final byte[] T4T_NFCEE_CC_FILE_ID = {(byte) (0xE1), (byte) (0x03)};
    public static final int T4T_NFCEE_MAPPING_VERSION_2_0 = 0x20;
    @VisibleForTesting
    public static final int WAIT_FOR_OEM_ALLOW_BOOT_TIMEOUT_MS = 5_000;

    static final int MSG_NDEF_TAG = 0;
    // Previously used: MSG_LLCP_LINK_ACTIVATION = 1
    // Previously used: MSG_LLCP_LINK_DEACTIVATED = 2
    static final int MSG_MOCK_NDEF = 3;
    // Previously used: MSG_LLCP_LINK_FIRST_PACKET = 4
    static final int MSG_ROUTE_AID = 5;
    static final int MSG_UNROUTE_AID = 6;
    static final int MSG_COMMIT_ROUTING = 7;
    // Previously used: MSG_INVOKE_BEAM = 8
    static final int MSG_RF_FIELD_ACTIVATED = 9;
    static final int MSG_RF_FIELD_DEACTIVATED = 10;
    static final int MSG_RESUME_POLLING = 11;
    static final int MSG_REGISTER_T3T_IDENTIFIER = 12;
    static final int MSG_DEREGISTER_T3T_IDENTIFIER = 13;
    static final int MSG_TAG_DEBOUNCE = 14;
    // Previously used: MSG_UPDATE_STATS = 15
    static final int MSG_APPLY_SCREEN_STATE = 16;
    static final int MSG_TRANSACTION_EVENT = 17;
    static final int MSG_PREFERRED_PAYMENT_CHANGED = 18;
    static final int MSG_TOAST_DEBOUNCE_EVENT = 19;
    static final int MSG_DELAY_POLLING = 20;
    static final int MSG_CLEAR_ROUTING_TABLE = 21;
    static final int MSG_UPDATE_ISODEP_PROTOCOL_ROUTE = 22;
    static final int MSG_UPDATE_TECHNOLOGY_ABF_ROUTE = 23;
    static final int MSG_WATCHDOG_PING = 24;
    static final int MSG_SE_SELECTED_EVENT = 25;
    static final int MSG_UPDATE_SYSTEM_CODE_ROUTE = 26;
    static final int MSG_PREFERRED_SIM_CHANGED = 27;
    static final String MSG_ROUTE_AID_PARAM_TAG = "power";
    static final int MSG_RESTART_DISCOVERY = 28;

    // Negative value for NO polling delay
    static final int NO_POLL_DELAY = -1;

    static final long MAX_POLLING_PAUSE_TIMEOUT = 40000;

    static final int MAX_TOAST_DEBOUNCE_TIME = 10000;

    static final int DISABLE_POLLING_FLAGS = 0x1000;

    static final int GESTURE_EXCHANGE_FLAG = 0x4000;
    static final int GESTURE_EXCHANGE_SECONDARY_FLAG = 0x8000;

    static final int RF_COALESCING_WINDOW_1 = 50;
    static final int RF_COALESCING_WINDOW_2 = 150;

    static final int TASK_ENABLE = 1;
    static final int TASK_DISABLE = 2;
    static final int TASK_BOOT = 3;
    static final int TASK_ENABLE_ALWAYS_ON = 4;
    static final int TASK_DISABLE_ALWAYS_ON = 5;

    // SE selected types
    public static final int SE_SELECTED_AID = 0x01;
    public static final int SE_SELECTED_TECH = 0x02;
    public static final int SE_SELECTED_PROTOCOL = 0x04;

    // Polling technology masks
    static final int NFC_POLL_A = 0x01;
    static final int NFC_POLL_B = 0x02;
    static final int NFC_POLL_F = 0x04;
    static final int NFC_POLL_V = 0x08;
    static final int NFC_POLL_B_PRIME = 0x10;
    static final int NFC_POLL_KOVIO = 0x20;

    // Listen technology masks
    static final int NFC_LISTEN_A = 0x01;
    static final int NFC_LISTEN_B = 0x02;
    static final int NFC_LISTEN_F = 0x04;
    static final int NFC_LISTEN_V = 0x08;

    static final String PREF_POLL_TECH = "polling_tech_dfl";

    // Default polling tech mask
    static final int DEFAULT_POLL_TECH = 0x2f; // See: Polling technology masks above

    static final String PREF_LISTEN_TECH = "listen_tech_dfl";
    // Default listening tech mask
    static final int DEFAULT_LISTEN_TECH = 0xf; // See: Listen technology masks above

    // minimum screen state that enables NFC polling
    static final int NFC_POLLING_MODE = ScreenStateHelper.SCREEN_STATE_OFF_UNLOCKED;

    // Time to wait for NFC controller to initialize before watchdog
    // goes off. This time is chosen large, because firmware download
    // may be a part of initialization.
    static final int INIT_WATCHDOG_MS = 90000;

    // Time to wait for routing to be applied before watchdog
    // goes off
    static final int ROUTING_WATCHDOG_MS = 6000;

    // Default delay used for presence checks
    static final int DEFAULT_PRESENCE_CHECK_DELAY = 125;

    // Removal Detection Wait Time Range
    static final int MIN_RF_REMOVAL_DETECTION_TIMEOUT = 0x00;
    static final int MAX_RF_REMOVAL_DETECTION_TIMEOUT = 0x13;

    static final NfcProperties.snoop_log_mode_values NFC_SNOOP_LOG_MODE =
            NfcProperties.snoop_log_mode().orElse(NfcProperties.snoop_log_mode_values.FILTERED);
    static final boolean NFC_VENDOR_DEBUG_ENABLED = NfcProperties.vendor_debug_enabled().orElse(false);

    // RF field events as defined in NFC extras
    public static final String ACTION_RF_FIELD_ON_DETECTED =
            "com.android.nfc_extras.action.RF_FIELD_ON_DETECTED";
    public static final String ACTION_RF_FIELD_OFF_DETECTED =
            "com.android.nfc_extras.action.RF_FIELD_OFF_DETECTED";

    /**
     * BroadcastOptions used for sending the broadcasts {@link #ACTION_RF_FIELD_ON_DETECTED}
     * and {@link #ACTION_RF_FIELD_OFF_DETECTED}.
     *
     * Adding {@link BroadcastOptions#DELIVERY_GROUP_POLICY_MOST_RECENT} as the delivery group
     * policy allows the broadcasting system to discard broadcasts waiting to be delivered
     * to a process. The delivery group matching key set via
     * {@link BroadcastOptions#setDeliveryGroupMatchingKey(String, String)} determines which older
     * broadcasts can be discarded. We are using the same key for both broadcasts, which means
     * a {@link #ACTION_RF_FIELD_OFF_DETECTED} broadcast can result in discarding of older
     * {@link #ACTION_RF_FIELD_ON_DETECTED} broadcasts.
     */
    @VisibleForTesting
    static final Bundle RF_FIELD_ON_OFF_BROADCAST_OPTIONS = BroadcastOptions.makeBasic()
            .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT)
            .setDeliveryGroupMatchingKey(UUID.randomUUID().toString(), ACTION_RF_FIELD_ON_DETECTED)
            .toBundle();

    public static final String APP_NAME_ENABLING_NFC =
            "com.android.nfc.PACKAGE_NAME_ENABLING_NFC";

    public static boolean sIsShortRecordLayout = false;

    public static boolean sIsNfcRestore = false;

    // for use with playSound()
    public static final int SOUND_END = 1;
    public static final int SOUND_ERROR = 2;

    public static final int NCI_VERSION_2_0 = 0x20;

    public static final int NCI_VERSION_1_0 = 0x10;

    // Timeout to re-apply routing if a tag was present and we postponed it
    private static final int APPLY_ROUTING_RETRY_TIMEOUT_MS = 5000;

    private static final VibrationAttributes HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK);

    private static final int NCI_STATUS_OK = 0x00;
    private static final int NCI_STATUS_REJECTED = 0x01;
    private static final int NCI_STATUS_MESSAGE_CORRUPTED = 0x02;
    private static final int NCI_STATUS_FAILED = 0x03;
    private static final int SEND_VENDOR_CMD_TIMEOUT_MS = 3_000;
    private static final int CHECK_FIRMWARE_TIMEOUT_MS = 60_000;
    private static final int NCI_GID_PROP = 0x0F;
    private static final int NCI_MSG_PROP_ANDROID = 0x0C;
    private static final int NCI_MSG_PROP_ANDROID_POWER_SAVING = 0x01;
    private static final int NCI_PROP_ANDROID_QUERY_POWER_SAVING_STATUS_CMD = 0x0A;
    private static final int POWER_STATE_SWITCH_ON = 0x01;

    public static final int WAIT_FOR_OEM_CALLBACK_TIMEOUT_MS = 3000;

    public static final int WAIT_FOR_COMMIT_ROUTING_TIMEOUT_MS = 3_000;

    private static final long TIME_TO_MONITOR_AFTER_FIELD_ON_MS = 10000L;

    private final Looper mLooper;
    private final UserManager mUserManager;
    private final ActivityManager mActivityManager;

    private static int nci_version = NCI_VERSION_1_0;
    // NFC Execution Environment
    // fields below are protected by this
    private final boolean mPollingDisableAllowed;
    private HashMap<Integer, ReaderModeDeathRecipient> mPollingDisableDeathRecipients =
            new HashMap<Integer, ReaderModeDeathRecipient>();
    private final ReaderModeDeathRecipient mReaderModeDeathRecipient =
            new ReaderModeDeathRecipient();
    private final SeServiceDeathRecipient mSeServiceDeathRecipient =
            new SeServiceDeathRecipient();
    private final DiscoveryTechDeathRecipient mDiscoveryTechDeathRecipient =
            new DiscoveryTechDeathRecipient();
    private final NfcUnlockManager mNfcUnlockManager;

    private final BackupManager mBackupManager;

    private final SecureRandom mCookieGenerator = new SecureRandom();

    // Tag app preference list for the target UserId.
    HashMap<Integer, HashMap<String, Boolean>> mTagAppPrefList =
            new HashMap<Integer, HashMap<String, Boolean>>();

    // Tag app blocklist hash
    static final String PREF_TAG_APP_BLOCK_LIST_HASH = "tag_app_block_list_hash";
    // Tag app blocklist hash default
    static final int PREF_TAG_APP_BLOCK_LIST_HASH_DEFAULT = 0;
    // Tag app preference blocked list.
    static final List<String> TAG_APP_BLOCKLIST = new ArrayList<String>();

    // cached version of installed packages requesting Android.permission.NFC_TRANSACTION_EVENTS
    // for current user and profiles. The Integer part is the userId.
    HashMap<Integer, List<String>> mNfcEventInstalledPackages =
            new HashMap<Integer, List<String>>();

    // cached version of installed packages requesting
    // Android.permission.NFC_PREFERRED_PAYMENT_INFO for current user and profiles.
    // The Integer part is the userId.
    HashMap<Integer, List<String>> mNfcPreferredPaymentChangedInstalledPackages =
            new HashMap<Integer, List<String>>();

    // fields below are used in multiple threads and protected by synchronized(this)
    final HashMap<Integer, Object> mObjectMap = new HashMap<Integer, Object>();
    final HashMap<Integer, Object> mTagObjectMap = new HashMap<Integer, Object>();
    int mScreenState;
    boolean mInProvisionMode; // whether we're in setup wizard and enabled NFC provisioning
    boolean mIsSecureNfcEnabled;
    boolean mSkipNdefRead;
    boolean mGestureExchangeEnabled;
    NfcDiscoveryParameters mCurrentDiscoveryParameters =
            NfcDiscoveryParameters.getNfcOffParameters();

    ReaderModeParams mReaderModeParams;
    DiscoveryTechParams mDiscoveryTechParams;

    private int mUserId;
    boolean mPollingPaused;

    // True if nfc notification message already shown
    boolean mAntennaBlockedMessageShown;
    private static int mDispatchFailedCount;
    private static int mDispatchFailedMax;

    static final int INVALID_NATIVE_HANDLE = -1;
    static final int MOCK_NATIVE_HANDLE = 0;
    byte mDebounceTagUid[];
    int mDebounceTagDebounceMs;
    int mDebounceTagNativeHandle = INVALID_NATIVE_HANDLE;
    ITagRemovedCallback mDebounceTagRemovedCallback;

    // Only accessed on one thread so doesn't need locking
    NdefMessage mLastReadNdefMessage;

    // mState is protected by this, however it is only modified in onCreate()
    // and the default AsyncTask thread so it is read unprotected from that
    // thread
    final AtomicInteger mState = new AtomicInteger();
    // one of NfcAdapter.STATE_ON, STATE_TURNING_ON, etc
    // mAlwaysOnState is protected by this, however it is only modified in onCreate()
    // and the default AsyncTask thread so it is read unprotected from that thread
    int mAlwaysOnState;  // one of NfcAdapter.STATE_ON, STATE_TURNING_ON, etc
    int mAlwaysOnMode; // one of NfcOemExtension.ENABLE_DEFAULT, ENABLE_TRANSPARENT, etc
    private final Object mOemExtensionCallbackLock = new Object();
    private final Object mNfcVendorNciCallbackLock = new Object();
    private final Object mPowerSavingModeLock = new Object();
    @GuardedBy("mPowerSavingModeLock")
    private @NfcAdapter.AdapterState int mPowerSavingState = NfcAdapter.STATE_OFF;

    // fields below are final after onCreate()
    boolean mIsReaderOptionEnabled = true;
    boolean mReaderOptionCapable;
    Context mContext;
    NfcInjector mNfcInjector;
    NfcEventLog mNfcEventLog;
    private DeviceHost mDeviceHost;
    private GesturePollFrameObserver mGesturePollFrameObserver;
    private SharedPreferences mPrefs;
    private SharedPreferences.Editor mPrefsEditor;
    private SharedPreferences mTagAppPrefListPrefs;

    private PowerManager.WakeLock mRoutingWakeLock;
    private PowerManager.WakeLock mRequireUnlockWakeLock;

    private long mLastFieldOnTimestamp = 0;

    int mEndSound;
    int mErrorSound;
    SoundPool mSoundPool; // playback synchronized on this
    TagService mNfcTagService;
    T4tNdefNfceeService mT4tNdefNfceeService;
    NfcAdapterService mNfcAdapter;
    NfcDtaService mNfcDtaService;
    RoutingTableParser mRoutingTableParser;
    RfDiscoverCmdParser mRfDiscoverCmdParser;
    boolean mIsDebugBuild;
    boolean mIsHceCapable;
    boolean mIsHceFCapable;
    boolean mIsSecureNfcCapable;
    boolean mIsRequestUnlockShowed;
    boolean mIsRecovering;
    boolean mIsNfcUserRestricted;
    boolean mIsWatchType;
    boolean mPendingPowerStateUpdate;
    boolean mIsWlcCapable;
    boolean mIsWlcEnabled;
    boolean mIsRWCapable;
    boolean mIsRDCapable;
    boolean mIsEuiccCapable;
    boolean mIsKeyguardLocked;
    WlcListenerDeviceInfo mWlcListenerDeviceInfo;
    public NfcDiagnostics  mNfcDiagnostics;

    // polling delay control variables
    private final int mPollDelayTime;
    private final int mPollDelayTimeLong;
    private final int mAppInActivityDetectionTime;
    private final int mTagRemovalDetectionWaitTime;
    private Timer mAppInActivityDetectionTimer;
    private final int mPollDelayCountMax;
    private int mPollDelayCount;
    private int mReadErrorCount;
    private int mReadErrorCountMax;
    private boolean mPollDelayed;
    private Handler mNfcBroadcastHandler;

    boolean mNotifyDispatchFailed;
    boolean mNotifyReadFailed;

    // for recording the latest Tag object cookie
    long mCookieUpToDate = -1;

    private DeviceConfigFacade mDeviceConfigFacade;
    private NfcDispatcher mNfcDispatcher;
    private PowerManager mPowerManager;
    private KeyguardManager mKeyguard;
    private HandoverDataParser mHandoverDataParser;
    private ContentResolver mContentResolver;
    private WatchDogThread mRoutingWatchDog;

    @VisibleForTesting
    CardEmulationManager mCardEmulationManager;
    private NfcCharging mNfcCharging;
    private Vibrator mVibrator;
    private VibrationEffect mVibrationEffect;
    private ISecureElementService mSEService;
    private final AlarmManager mAlarmManager;

    private ScreenStateHelper mScreenStateHelper;
    private ForegroundUtils mForegroundUtils;

    private final NfcPermissions mNfcPermissions;
    private static NfcService sService;
    private static boolean sToast_debounce = false;
    private static int sToast_debounce_time_ms = 3000;
    public  static boolean sIsDtaMode = false;

    private final boolean mIsTagAppPrefSupported;
    private int mTagAppBlockListHash;

    private final boolean mIsAlwaysOnSupported;
    private final Set<INfcControllerAlwaysOnListener> mAlwaysOnListeners =
            Collections.synchronizedSet(new HashSet<>());

    private int mAidMatchingExactOnly = 0x02;
    public static final int T4TNFCEE_STATUS_FAILED = -1;
    private Object mT4tNdefNfcEeObj = new Object();
    private Bundle mT4tNdefNfceeReturnBundle = new Bundle();
    private final Set<INfcWlcStateListener> mWlcStateListener =
            Collections.synchronizedSet(new HashSet<>());
    @Nullable
    private final StatsdUtils mStatsdUtils;
    private final boolean mCheckDisplayStateForScreenState;

    private INfcVendorNciCallback mNfcVendorNciCallBack = null;
    private INfcOemExtensionCallback mNfcOemExtensionCallback = null;
    private IReaderCallback mNfcGestureExchangeCallback = null;

    private final DisplayListener mDisplayListener = new DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            handleScreenStateChanged();
        }
    };

    private Object mDiscoveryLock = new Object();
    private final Object mObjectMapLock = new Object();
    private final Object mRoutingWatchDogLock = new Object();

    private boolean mCardEmulationActivated = false;
    private boolean mRfFieldActivated = false;
    private boolean mRfDiscoveryStarted = false;
    private boolean mEeListenActivated = false;
    private boolean mTagConnected = false;
    // Scheduled executor for routing table update
    private final ScheduledExecutorService mRtUpdateScheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> mRtUpdateScheduledTask = null;

    private static final int STATUS_OK = NfcOemExtension.STATUS_OK;
    private static final int STATUS_UNKNOWN_ERROR = NfcOemExtension.STATUS_UNKNOWN_ERROR;

    private static final int ACTION_ON_ENABLE = 0;
    private static final int ACTION_ON_DISABLE = 1;
    private static final int ACTION_ON_TAG_DISPATCH = 2;
    private static final int ACTION_ON_READ_NDEF = 3;
    private static final int ACTION_ON_APPLY_ROUTING = 4;
    private static final int ACTION_ON_ROUTING_CHANGED = 5;

    public static NfcService getInstance() {
        return sService;
    }

    public String getT4tNfceeAid() {
        byte[] t4tNfceeAid = mDeviceHost.getT4tNfceeAid();
        return t4tNfceeAid != null ? HexFormat.of().formatHex(t4tNfceeAid).toUpperCase() : null;
    }

    @Override
    public void onRemoteEndpointDiscovered(TagEndpoint tag) {
        Log.d(TAG, "onRemoteEndpointDiscovered");
        sendMessage(MSG_NDEF_TAG, tag);
    }

    /**
     * Notifies transaction
     */
    @Override
    public void onHostCardEmulationActivated(int technology) {
        mCardEmulationActivated = true;
        try {
            if (mNfcOemExtensionCallback != null) {
                mNfcOemExtensionCallback.onCardEmulationActivated(mCardEmulationActivated);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "onHostCardEmulationActivated: error: ", e);
        }
        if (mCardEmulationManager != null) {
            mCardEmulationManager.onHostCardEmulationActivated(technology);
            if (android.nfc.Flags.nfcPersistLog()) {
                mNfcEventLog.logEvent(
                        NfcEventProto.EventType.newBuilder()
                                .setHostCardEmulationStateChange(
                                        NfcEventProto.NfcHostCardEmulationStateChange.newBuilder()
                                                .setTechnology(technology)
                                                .setEnable(true)
                                                .build())
                                .build());
            }
        }
    }

    @Override
    public void onHostCardEmulationData(int technology, byte[] data) {
        if (mCardEmulationManager != null) {
            mCardEmulationManager.onHostCardEmulationData(technology, data);
            if (android.nfc.Flags.nfcPersistLog() && NFC_VENDOR_DEBUG_ENABLED) {
                mNfcEventLog.logEvent(
                        NfcEventProto.EventType.newBuilder()
                                .setHostCardEmulationData(
                                        NfcEventProto.NfcHostCardEmulationData.newBuilder()
                                                .setTechnology(technology)
                                                .setData(ByteString.copyFrom(data))
                                                .build())
                                .build());
            }
        }
    }

    @Override
    public void onHostCardEmulationDeactivated(int technology) {
        mCardEmulationActivated = false;
        try {
            if (mNfcOemExtensionCallback != null) {
                mNfcOemExtensionCallback.onCardEmulationActivated(mCardEmulationActivated);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "onHostCardEmulationDeactivated: e=", e);
        }
        if (mCardEmulationManager != null) {
            mCardEmulationManager.onHostCardEmulationDeactivated(technology);
            if (android.nfc.Flags.nfcPersistLog()) {
                mNfcEventLog.logEvent(
                        NfcEventProto.EventType.newBuilder()
                                .setHostCardEmulationStateChange(
                                        NfcEventProto.NfcHostCardEmulationStateChange.newBuilder()
                                                .setTechnology(technology)
                                                .setEnable(false)
                                                .build())
                                .build());
            }
        }
    }


    /** Notifies Removal Detection procedure completed,
        endpoint deactivation reason returned by NFCC */
    public void onEndpointRemoved(int reason) {
        Log.d(TAG, "onEndpointRemoved: Deactivation reason is " + reason);
        if (mIsWlcEnabled && mNfcCharging.NfcChargingMode) {
            mNfcCharging.onEndpointRemoved(reason);
        }
        /* else send intent to notify other applications */
    }

    public boolean startRemovalDetection(int waiting_time_int) {
      return mDeviceHost.detectEpRemoval(waiting_time_int);
    }

    @Override
    public void onRemoteFieldActivated() {
        mRfFieldActivated = true;
        mLastFieldOnTimestamp = mNfcInjector.getWallClockMillis();
        mNfcInjector.ensureWatchdogMonitoring();
        try {
            if (mNfcOemExtensionCallback != null) {
                mNfcOemExtensionCallback.onRfFieldDetected(mRfFieldActivated);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "onRemoteFieldActivated: e=", e);
        }
        if (Flags.coalesceRfEvents() && mHandler.hasMessages(MSG_RF_FIELD_DEACTIVATED)) {
            mHandler.removeMessages(MSG_RF_FIELD_DEACTIVATED);
        } else {
            sendMessage(MSG_RF_FIELD_ACTIVATED, null);
        }
        if (mStatsdUtils != null) {
            mStatsdUtils.logFieldChanged(true, 0);
        }
        if (android.nfc.Flags.nfcPersistLog() && NFC_VENDOR_DEBUG_ENABLED) {
            mNfcEventLog.logEvent(
                    NfcEventProto.EventType.newBuilder()
                            .setRemoteFieldStateChange(
                                    NfcEventProto.NfcRemoteFieldStateChange.newBuilder()
                                            .setFieldOn(true)
                                            .build())
                            .build());
        }
        if (android.nfc.Flags.nfcEventListener() && mCardEmulationManager != null) {
            mCardEmulationManager.onRemoteFieldChanged(true);
        }
    }

    @Override
    public void onRemoteFieldDeactivated() {
        mRfFieldActivated = false;
        try {
            if (mNfcOemExtensionCallback != null) {
                mNfcOemExtensionCallback.onRfFieldDetected(mRfFieldActivated);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "onRemoteFieldDeactivated: e=", e);
        }
        if (Flags.coalesceRfEvents()) {
            mHandler.sendMessageDelayed(
                    mHandler.obtainMessage(MSG_RF_FIELD_DEACTIVATED),
                    coalesceRfFieldOnOffBroadcasts()
                            ? RF_COALESCING_WINDOW_2
                            : RF_COALESCING_WINDOW_1);
        } else {
            sendMessage(MSG_RF_FIELD_DEACTIVATED, null);
        }
        if (mStatsdUtils != null) {
            mStatsdUtils.logFieldChanged(false, 0);
        }
        if (android.nfc.Flags.nfcPersistLog() && NFC_VENDOR_DEBUG_ENABLED) {
            mNfcEventLog.logEvent(
                    NfcEventProto.EventType.newBuilder()
                            .setRemoteFieldStateChange(
                                    NfcEventProto.NfcRemoteFieldStateChange.newBuilder()
                                            .setFieldOn(false)
                                            .build())
                            .build());
        }

        if (android.nfc.Flags.nfcEventListener() && mCardEmulationManager != null) {
            mCardEmulationManager.onRemoteFieldChanged(false);
        }
    }

    List<PollingFrame> mPollingFramesToBeSent = new ArrayList<>();
    final Runnable mPollingLoopsDetectedRunnable = new Runnable() {
        public void run() {
            List<PollingFrame> frames;
            synchronized (mPollingLoopsDetectedRunnable) {
                frames = mPollingFramesToBeSent;
                mPollingFramesToBeSent = new ArrayList<>();
            }
            if (mCardEmulationManager != null) {
                mCardEmulationManager.onPollingLoopDetected(new ArrayList<>(frames));
            }
        }
    };

    @Override
    public void onPollingLoopDetected(List<PollingFrame> frames) {
        if (mCardEmulationManager != null) {
            synchronized (mPollingLoopsDetectedRunnable) {
                mPollingFramesToBeSent.addAll(frames);
                if (!mHandler.hasCallbacks(mPollingLoopsDetectedRunnable)) {
                    mHandler.post(mPollingLoopsDetectedRunnable);
                }
            }
        }
    }

    @Override
    public void onNfcTransactionEvent(byte[] aid, byte[] data, String seName) {
        byte[][] dataObj = {aid, data, seName.getBytes()};
        sendMessage(MSG_TRANSACTION_EVENT, dataObj);
    }

    @Override
    public void onEeUpdated() {
        if (mNfcOemExtensionCallback != null) {
            try {
                mNfcOemExtensionCallback.onEeUpdated();
            } catch (RemoteException e) {
                Log.e(TAG, "onEeUpdated: e=", e);
            }
        }
        if (mRtUpdateScheduledTask != null && !mRtUpdateScheduledTask.isDone()) {
            mRtUpdateScheduledTask.cancel(false);
        }
        // Delay routing table update to allow remove useless operations when several
        // ntf are received
        mRtUpdateScheduledTask =
                mRtUpdateScheduler.schedule(
                    () -> {
                        if (mIsHceCapable) {
                            if (DBG) Log.d(TAG, "onEeUpdated: trigger routing table update");
                            mCardEmulationManager.onTriggerRoutingTableUpdate();
                        }
                    },
                    50,
                    TimeUnit.MILLISECONDS);
    }

    private void restartStack() {
        synchronized (NfcService.this) {
            if (DBG) {
                Log.d(TAG, "restartStack: mIsRecovering=" + mIsRecovering);
            }
            if (!mIsRecovering) {
                mIsRecovering = true;
            } else {
                return;
            }
        }

        if (DBG) {
            Log.d(TAG, "restartStack: Restarting NFC Service");
        }

        cancelRoutingWatchDog();

        try {
            mContext.unregisterReceiver(mReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "restartStack: Failed to unregisterScreenState BroadCastReceiver: " + e);
        }
        new EnableDisableTask().execute(TASK_DISABLE);
        new EnableDisableTask().execute(TASK_ENABLE);
    }

    @Override
    public void onHwErrorReported() {
        if (android.nfc.Flags.nfcEventListener() && mCardEmulationManager != null) {
            mCardEmulationManager.onInternalErrorReported(
                    CardEmulation.NFC_INTERNAL_ERROR_NFC_HARDWARE_ERROR);
        }
        restartStack();
    }

    @FlaggedApi(android.nfc.Flags.FLAG_NFC_EVENT_LISTENER)
    @Override
    public void onCommandTimeout() {
        if (android.nfc.Flags.nfcEventListener() && mCardEmulationManager != null) {
            mCardEmulationManager.onInternalErrorReported(
                    CardEmulation.NFC_INTERNAL_ERROR_COMMAND_TIMEOUT);
        }
    }

    @Override
    public void onVendorSpecificEvent(int gid, int oid, byte[] payload) {
        mHandler.post(() -> mNfcAdapter.sendVendorNciNotification(gid, oid, payload));
    }

    @Override
    public void onObserveModeStateChanged(boolean enable) {
        mHandler.post(() -> {
            if (mCardEmulationManager != null) {
                mCardEmulationManager.onObserveModeStateChange(enable);
            }
        });
    }

    @Override
    public void onObserveModeDisabledInFirmware(PollingFrame exitFrame) {
        Log.d(TAG, "onObserveModeDisabledInFirmware: " + exitFrame);
        mCardEmulationManager.onObserveModeDisabledInFirmware(exitFrame);
        onObserveModeStateChanged(false);
        mNfcEventLog.logEvent(
                NfcEventProto.EventType.newBuilder()
                        .setObserveModeChange(
                                NfcEventProto.NfcObserveModeChange.newBuilder()
                                        .setEnable(false)
                                        .setLatencyMs(0)
                                        .setResult(true)
                                        .setFwTriggered(true)
                                        .build())
                        .build());
    }

    @Override
    public void onObserveModeEnabledInFirmware() {
        Log.d(TAG, "onObserveModeEnabledInFirmware");
        onObserveModeStateChanged(true);
        mNfcEventLog.logEvent(
                NfcEventProto.EventType.newBuilder()
                        .setObserveModeChange(
                                NfcEventProto.NfcObserveModeChange.newBuilder()
                                        .setEnable(true)
                                        .setLatencyMs(0)
                                        .setResult(true)
                                        .setFwTriggered(true)
                                        .build())
                        .build());
    }

    @Override
    public void onEeListenActivated(boolean isActivated) {
        mEeListenActivated = isActivated;
        mCardEmulationManager.onEeListenActivated(isActivated);
        try {
            if (mNfcOemExtensionCallback != null) {
                mNfcOemExtensionCallback.onEeListenActivated(isActivated);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "onEeListenActivated: e=", e);
        }
    }

    @Override
    public void onRfDiscoveryEvent(boolean isDiscoveryStarted) {
        synchronized (mDiscoveryLock) {
            mRfDiscoveryStarted = isDiscoveryStarted;
        }
        try {
            if (mNfcOemExtensionCallback != null) {
                mNfcOemExtensionCallback.onRfDiscoveryStarted(isDiscoveryStarted);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "onRfDiscoveryEvent: e=", e);
        }
        mHandler.post(() -> {
            if (!isDiscoveryStarted) {
                StopPresenceChecking(false);
            }
        });
    }

    @Override
    public void onSeSelected(int type, @Nullable byte[] aid, @NonNull String eeName) {
        sendMessage(MSG_SE_SELECTED_EVENT, type, Pair.create(aid, eeName));
    }

    @Override
    public void onRestartRfDiscovery() {
        sendMessage(NfcService.MSG_RESTART_DISCOVERY, null);
    }

    /**
     * Enable or Disable PowerSaving Mode based on flag
     */
    private void setPowerSavingModeInternal(boolean enable) {
        synchronized (mPowerSavingModeLock) {
            if (!mDeviceHost.isPowerSavingModeSupported()) {
                throw new UnsupportedOperationException(
                        "Device does not support power saving mode");
            }
            Log.d(TAG, "setPowerSavingModeInternal: " + enable
                    + ", isPowerSavingModeEnabled: " + isPowerSavingModeEnabled());

            if (enable && isPowerSavingModeEnabled()) return;
            if (!enable && !isPowerSavingModeEnabled()) return;

            EnableDisableTask chip = new EnableDisableTask();
            if (mState.get() == NfcAdapter.STATE_OFF && enable) {
                if (!chip.enableInternal()) {
                    throw new IllegalStateException(
                            "Failed to temporarily enable chip for power saving mode update");
                }
            }

            mPowerSavingState = enable
                    ? NfcAdapter.STATE_TURNING_ON
                    : NfcAdapter.STATE_TURNING_OFF;
            if (enable) {
                if (mDeviceHost.setPowerSavingMode(enable)) {
                    chip.disableInternal();
                    mPowerSavingState = NfcAdapter.STATE_ON;
                } else {
                    mPowerSavingState = NfcAdapter.STATE_OFF;
                }
            } else {
                chip.enableInternal();
                mPowerSavingState = NfcAdapter.STATE_OFF;
            }
        }
    }

    boolean isPowerSavingModeEnabled() {
        synchronized (mPowerSavingModeLock) {
            return mPowerSavingState == NfcAdapter.STATE_ON
                    || mPowerSavingState == NfcAdapter.STATE_TURNING_ON;
        }
    }

    boolean isPowerSavingModeChanging() {
        synchronized (mPowerSavingModeLock) {
            return mPowerSavingState == NfcAdapter.STATE_TURNING_ON
                    || mPowerSavingState == NfcAdapter.STATE_TURNING_OFF;
        }
    }

    public void onWlcData(Map<String, Integer> WlcDeviceInfo) {
        for (String key : WlcDeviceInfo.keySet()) {
            Log.d(TAG, "onWlcData:  " + key + " =  " + WlcDeviceInfo.get(key));
        }
        synchronized (mWlcStateListener) {
            mWlcListenerDeviceInfo = new WlcListenerDeviceInfo(
                    WlcDeviceInfo.get(mNfcCharging.VendorId),
                    WlcDeviceInfo.get(mNfcCharging.TemperatureListener),
                    WlcDeviceInfo.get(mNfcCharging.BatteryLevel),
                    WlcDeviceInfo.get(mNfcCharging.State));
            for (INfcWlcStateListener listener : mWlcStateListener) {
                try {
                    listener.onWlcStateChanged(mWlcListenerDeviceInfo);
                } catch (RemoteException e) {
                    Log.e(TAG, "onWlcData: error");
                }
            }
        }
    }

    /** Notifies WLC procedure stopped */
    @Override
    public void onWlcStopped(int wpt_end_condition) {
        Log.d(TAG, "onWlcStopped: End condition is " + wpt_end_condition);
        mNfcCharging.onWlcStopped(wpt_end_condition);
    }

    public void onTagRfDiscovered(boolean discovered) {
        Log.d(TAG, "onTagRfDiscovered: " + discovered);
        executeOemOnTagConnectedCallback(discovered);
        mHandler.post(() -> {
            if (!discovered) {
                StopPresenceChecking(false);
            }
        });
    }

    final class ReaderModeParams {
        public int flags;
        public IAppCallback callback;
        public int presenceCheckDelay;
        public IBinder binder;
        public int uid;
        public byte[] annotation;
        public byte[] extra_annotation;
    }

    final class DiscoveryTechParams {
        public IBinder binder;
        public int uid;
    }

    void saveNfcOnSetting(boolean on) {
        synchronized (NfcService.this) {
            mPrefsEditor.putBoolean(PREF_NFC_ON, on);
            mPrefsEditor.apply();
            mBackupManager.dataChanged();
        }
    }

    boolean getNfcOnSetting() {
        synchronized (NfcService.this) {
            return mPrefs.getBoolean(PREF_NFC_ON, mDeviceConfigFacade.getNfcDefaultState());
        }
    }

    void saveNfcListenTech(int tech) {
        synchronized (NfcService.this) {
            mPrefsEditor.putInt(PREF_LISTEN_TECH, tech);
            mPrefsEditor.apply();
            mBackupManager.dataChanged();
        }
    }

    int getNfcListenTech() {
        synchronized (NfcService.this) {
            return mPrefs.getInt(PREF_LISTEN_TECH, DEFAULT_LISTEN_TECH);
        }
    }

    void saveNfcPollTech(int tech) {
        synchronized (NfcService.this) {
            mPrefsEditor.putInt(PREF_POLL_TECH, tech);
            mPrefsEditor.apply();
            mBackupManager.dataChanged();
        }
    }

    int getNfcPollTech() {
        synchronized (NfcService.this) {
            return isReaderOptionEnabled()
                    ? mPrefs.getInt(PREF_POLL_TECH, DEFAULT_POLL_TECH) : 0x00;
        }
    }



    /**
     * Returns the SharedPreferences key for the Secure NFC setting for a specific user.
     * @param userId The user ID.
     * @return The user-specific preference key.
     */
    private String getSecureNfcPreferenceKeyForUser(int userId) {
        return PREF_SECURE_NFC_ON + "_" + userId;
    }

    /**
     * Loads the Secure NFC setting for the given user.
     * @param userId The user ID for which to load the settings.
     */
    private void loadSecureNfcSettings(int userId) {
        String secureNfcPreferenceKey = getSecureNfcPreferenceKeyForUser(userId);
        // Get the user-specific preference.
        // Fall back to the device's default setting if not found.
        mIsSecureNfcEnabled = mPrefs.getBoolean(secureNfcPreferenceKey,
                mDeviceConfigFacade.getDefaultSecureNfcState())
                && mIsSecureNfcCapable;

        Log.i(TAG, "Reloaded Secure NFC setting for user "
                + userId + ". Enabled: " + mIsSecureNfcEnabled);

        // Apply the newly loaded setting to the NFC controller
        mDeviceHost.setNfcSecure(mIsSecureNfcEnabled);
    }

    /** Returns true if NFC has user restriction set. */
    private boolean isNfcUserRestricted() {
        return mUserManager.getUserRestrictions().getBoolean(
                UserManager.DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO);
    }

    /** Returns true if NFC state change by user is restricted. */
    private boolean isNfcUserChangeRestricted() {
        return mUserManager.getUserRestrictions().getBoolean(
                UserManager.DISALLOW_CHANGE_NEAR_FIELD_COMMUNICATION_RADIO
        );
    }

    boolean shouldEnableNfc() {
        return getNfcOnSetting() && !mNfcInjector.isSatelliteModeOn()
                && !isNfcUserRestricted() && allowOemEnable();
    }

    boolean allowOemEnable() {
        if (mNfcOemExtensionCallback == null) return true;
        return receiveOemCallbackResult(ACTION_ON_ENABLE);
    }

    boolean allowOemDisable() {
        if (mNfcOemExtensionCallback == null) return true;
        return receiveOemCallbackResult(ACTION_ON_DISABLE);
    }

    boolean receiveOemCallbackResult(int action) {
        CountDownLatch latch = new CountDownLatch(1);
        NfcCallbackResultReceiver.OnReceiveResultListener listener =
                new NfcCallbackResultReceiver.OnReceiveResultListener();
        ResultReceiver receiver = new NfcCallbackResultReceiver(latch, listener);
        try {
            switch (action) {
                case ACTION_ON_ENABLE:
                    mNfcOemExtensionCallback.onEnable(receiver);
                    break;
                case ACTION_ON_DISABLE:
                    mNfcOemExtensionCallback.onDisable(receiver);
                    break;
                case ACTION_ON_TAG_DISPATCH:
                    mNfcOemExtensionCallback.onTagDispatch(receiver);
                    break;
                case ACTION_ON_READ_NDEF:
                    mNfcOemExtensionCallback.onNdefRead(receiver);
                    break;
                case ACTION_ON_APPLY_ROUTING:
                    mNfcOemExtensionCallback.onApplyRouting(receiver);
                    break;
                case ACTION_ON_ROUTING_CHANGED:
                    mNfcOemExtensionCallback.onRoutingChanged(receiver);
                    break;
            }
        } catch (RemoteException remoteException) {
            return false;
        }
        try {
            boolean success = latch.await(WAIT_FOR_OEM_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!success) {
                return false;
            } else {
                return listener.getResultCode() == 1;
            }
        } catch (InterruptedException ie) {
            return false;
        }
    }

    private void registerGlobalBroadcastsReceiver() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(ACTION_BOOT_COMPLETED);
        filter.addAction(ACTION_LOCKED_BOOT_COMPLETED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        mContext.registerReceiverForAllUsers(mReceiver, filter, null, null);
    }

    public NfcService(Application nfcApplication, NfcInjector nfcInjector) {
        mUserId = ActivityManager.getCurrentUser();
        mContext = nfcApplication;
        mNfcInjector = nfcInjector;
        mLooper = mNfcInjector.getMainLooper();
        mHandler = new NfcServiceHandler(mLooper);
        mNfcEventLog = mNfcInjector.getNfcEventLog();

        mNfcTagService = new TagService();
        mNfcAdapter = new NfcAdapterService();
        mRoutingTableParser = mNfcInjector.getRoutingTableParser();
        mRfDiscoverCmdParser = mNfcInjector.getRfDiscoverCmdParser();
        mT4tNdefNfceeService = new T4tNdefNfceeService();
        Log.i(TAG, "Starting NFC service");

        sService = this;

        mScreenStateHelper = mNfcInjector.getScreenStateHelper();
        mContentResolver = mContext.getContentResolver();
        mDeviceHost = mNfcInjector.makeDeviceHost(this);

        mNfcUnlockManager = mNfcInjector.getNfcUnlockManager();

        mHandoverDataParser = mNfcInjector.getHandoverDataParser();
        mInProvisionMode = mNfcInjector.isInProvisionMode();
        mDeviceConfigFacade = mNfcInjector.getDeviceConfigFacade();

        mNfcDispatcher = mNfcInjector.getNfcDispatcher();

        mPrefs = mContext.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        mPrefsEditor = mPrefs.edit();

        mState.set(NfcAdapter.STATE_OFF);
        mAlwaysOnState = NfcAdapter.STATE_OFF;
        mAlwaysOnMode = NfcOemExtension.ENABLE_DEFAULT;

        mIsDebugBuild = "userdebug".equals(Build.TYPE) || "eng".equals(Build.TYPE);

        mPowerManager = mContext.getSystemService(PowerManager.class);

        mRoutingWakeLock = mPowerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "NfcService:mRoutingWakeLock");

        mRequireUnlockWakeLock = mPowerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP
                        | PowerManager.ON_AFTER_RELEASE, "NfcService:mRequireUnlockWakeLock");

        mKeyguard = mContext.getSystemService(KeyguardManager.class);
        mUserManager = mContext.getSystemService(UserManager.class);
        mActivityManager = mContext.getSystemService(ActivityManager.class);
        mVibrator = mContext.getSystemService(Vibrator.class);
        mVibrationEffect = mNfcInjector.getVibrationEffect();

        PackageManager pm = mContext.getPackageManager();
        mIsWatchType = pm.hasSystemFeature(PackageManager.FEATURE_WATCH);

        mNfcDiagnostics = mNfcInjector.getNfcDiagnostics();

        mAlarmManager = mContext.getSystemService(AlarmManager.class);

        mCheckDisplayStateForScreenState = mDeviceConfigFacade.getCheckDisplayStateForScreenState();
        if (mInProvisionMode) {
            mScreenState = mScreenStateHelper.checkScreenStateProvisionMode();
        } else {
            mScreenState = mScreenStateHelper.checkScreenState(mCheckDisplayStateForScreenState);
        }
        if (mCheckDisplayStateForScreenState) {
            DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
            displayManager.registerDisplayListener(mDisplayListener, mHandler);
        }

        mBackupManager = mNfcInjector.getBackupManager();

        mStatsdUtils = mNfcInjector.getStatsdUtils();

        // Intents for all users
        registerGlobalBroadcastsReceiver();

        // Listen for work profile adds or removes.
        IntentFilter managedProfileFilter = new IntentFilter();
        managedProfileFilter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
        managedProfileFilter.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);
        managedProfileFilter.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
        managedProfileFilter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        mContext.registerReceiverForAllUsers(mManagedProfileReceiver,
                managedProfileFilter, null, null);

        IntentFilter ownerFilter = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        ownerFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        ownerFilter.addAction(Intent.ACTION_SHUTDOWN);
        mContext.registerReceiverForAllUsers(mOwnerReceiver, ownerFilter, null, null);

        ownerFilter = new IntentFilter();
        ownerFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        ownerFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        ownerFilter.addDataScheme("package");
        mContext.registerReceiverForAllUsers(mOwnerReceiver, ownerFilter, null, null);

        addDeviceLockedStateListener();
        addKeyguardLockedStateListener();
        mIsKeyguardLocked = mKeyguard.isKeyguardLocked();

        updatePackageCache();

        mIsRWCapable = pm.hasSystemFeature(PackageManager.FEATURE_NFC);
        mIsWlcCapable = android.nfc.Flags.enableNfcCharging() &&
                pm.hasSystemFeature(PackageManager.FEATURE_NFC_CHARGING);
        if (mIsWlcCapable) {
            mNfcCharging = mNfcInjector.getNfcCharging(mDeviceHost);
            mIsWlcEnabled = mPrefs.getBoolean(PREF_NFC_CHARGING_ON, NFC_CHARGING_ON_DEFAULT);
            // Register ThermalStatusChangedListener
            addThermalStatusListener();
        }

        mIsRDCapable = mContext.getResources().getBoolean(R.bool.removal_detection_default);

        mIsHceCapable =
                pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION) ||
                pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF);
        mIsHceFCapable =
                pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF);
        if (mIsHceCapable) {
            mCardEmulationManager = mNfcInjector.getCardEmulationManager();
        }
        mIsEuiccCapable = mContext.getResources().getBoolean(R.bool.enable_euicc_support)
                && NfcInjector.NfcProperties.isEuiccSupported();
        mForegroundUtils = mNfcInjector.getForegroundUtils();
        mIsSecureNfcCapable = mIsHceCapable && mDeviceConfigFacade.isSecureNfcCapable();
        String secureNfcPreferenceKey = getSecureNfcPreferenceKeyForUser(mUserId);
        if (mUserId == UserHandle.SYSTEM.getIdentifier() && mPrefs.contains(PREF_SECURE_NFC_ON)) {
            boolean oldSecureNfcEnabled = mPrefs.getBoolean(PREF_SECURE_NFC_ON,
                    mDeviceConfigFacade.getDefaultSecureNfcState())
                    && mIsSecureNfcCapable;
            mPrefsEditor.putBoolean(secureNfcPreferenceKey, oldSecureNfcEnabled);
            mPrefsEditor.remove(PREF_SECURE_NFC_ON);
            mPrefsEditor.apply();
            Log.i(TAG, "OTA Migration: Successfully migrated legacy PREF_SECURE_NFC_ON ("
                + oldSecureNfcEnabled + ") to " + secureNfcPreferenceKey + " and removed old key.");
        }
        mIsSecureNfcEnabled = mPrefs.getBoolean(secureNfcPreferenceKey,
            mDeviceConfigFacade.getDefaultSecureNfcState())
            && mIsSecureNfcCapable;
        mDeviceHost.setNfcSecure(mIsSecureNfcEnabled);

        sToast_debounce_time_ms =
                mContext.getResources().getInteger(R.integer.toast_debounce_time_ms);
        if (sToast_debounce_time_ms > MAX_TOAST_DEBOUNCE_TIME) {
            sToast_debounce_time_ms = MAX_TOAST_DEBOUNCE_TIME;
        }

        mNfcBroadcastHandler = new Handler(mNfcInjector.getNfcBroadcastLooper());

        // Notification message variables
        mDispatchFailedCount = 0;
        if (mDeviceConfigFacade.isAntennaBlockedAlertEnabled() &&
            !mPrefs.getBoolean(PREF_ANTENNA_BLOCKED_MESSAGE_SHOWN, ANTENNA_BLOCKED_MESSAGE_SHOWN_DEFAULT)) {
            mAntennaBlockedMessageShown = false;
            mDispatchFailedMax =
                mContext.getResources().getInteger(R.integer.max_antenna_blocked_failure_count);
        } else {
            mAntennaBlockedMessageShown = true;
        }

        // Polling delay count for switching from stage one to stage two.
        mPollDelayCountMax = mDeviceConfigFacade.getUnknownTagPollingDelayMax();
        // Stage one: polling delay time for the first few unknown tag detections
        mPollDelayTime = mDeviceConfigFacade.getUnknownTagPollingDelay();
        // Stage two: longer polling delay time after max_poll_delay_count
        mPollDelayTimeLong = mDeviceConfigFacade.getUnknownTagPollingDelayLong();
        // Polling delay if read error found more than max count.
        mReadErrorCountMax =
                mContext.getResources().getInteger(R.integer.unknown_tag_read_error_count_max);

        mNotifyDispatchFailed = mContext.getResources().getBoolean(R.bool.enable_notify_dispatch_failed);
        mNotifyReadFailed = mContext.getResources().getBoolean(R.bool.enable_notify_read_failed);

        mPollingDisableAllowed = mDeviceConfigFacade.getPollingDisableAllowed();
        mAppInActivityDetectionTime =
            mContext.getResources().getInteger(R.integer.inactive_presence_check_allowed_time);
        mTagRemovalDetectionWaitTime =
            mContext.getResources().getInteger(R.integer.removal_detection_waiting_time);
        // Make sure this is only called when object construction is complete.
        mNfcInjector.getNfcManagerRegisterer().register(mNfcAdapter);

        mIsAlwaysOnSupported = mDeviceConfigFacade.getNfccAlwaysOnAllowed();

        mIsTagAppPrefSupported =
            mContext.getResources().getBoolean(R.bool.tag_intent_app_pref_supported);
        mTagAppBlockListHash = mPrefs.getInt(PREF_TAG_APP_BLOCK_LIST_HASH,
                PREF_TAG_APP_BLOCK_LIST_HASH_DEFAULT);

        // Get default blocked package list from resource file
        TAG_APP_BLOCKLIST.addAll(
                Arrays.asList(mContext.getResources().getStringArray(
                        R.array.tag_intent_blocked_app_list)));

        Uri uri = Settings.Global.getUriFor(Constants.SETTINGS_SATELLITE_MODE_ENABLED);
        if (uri == null) {
            Log.e(TAG, "NfcService(constructor): satellite mode key does not exist in Settings");
        } else {
            mContext.getContentResolver().registerContentObserver(
                    uri,
                    false,
                    new ContentObserver(null) {
                        @Override
                        public void onChange(boolean selfChange) {
                            if (mNfcInjector.isSatelliteModeSensitive()) {
                                Log.i(TAG,
                                        "NfcService(constructor): Satellite mode change detected");
                                if (isTaskBootCompleted()) {
                                    if (shouldEnableNfc()) {
                                        new EnableDisableTask().execute(TASK_ENABLE);
                                    } else {
                                        new EnableDisableTask().execute(TASK_DISABLE);
                                    }
                                } else {
                                    Log.i(TAG,
                                            "NfcService(constructor): Satellite mode change "
                                                    + "detected, skip NFC init is not completed");
                                }
                            }
                        }
                    });
        }

        mIsNfcUserRestricted = isNfcUserRestricted();
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (mIsNfcUserRestricted == isNfcUserRestricted()) {
                            return;
                        }
                        Log.i(TAG,
                                "NfcService(constructor): Disallow NFC user restriction "
                                        + "changed from " + mIsNfcUserRestricted + " to "
                                        + !mIsNfcUserRestricted + ".");
                        mIsNfcUserRestricted = !mIsNfcUserRestricted;
                        if (isTaskBootCompleted()) {
                            if (shouldEnableNfc()) {
                                new EnableDisableTask().execute(TASK_ENABLE);
                            } else {
                                new EnableDisableTask().execute(TASK_DISABLE);
                            }
                        } else {
                            Log.i(TAG, "NfcService(constructor): Disallow NFC user restriction "
                                    + "change detected - skip NFC init is not completed");
                        }
                    }
                },
                new IntentFilter(UserManager.ACTION_USER_RESTRICTIONS_CHANGED)
        );

        mNfcPermissions = new NfcPermissions(mContext);
        mReaderOptionCapable = mDeviceConfigFacade.isReaderOptionCapable();
        if (mIsRWCapable) {
            if (mReaderOptionCapable) {
                mIsReaderOptionEnabled =
                        mPrefs.getBoolean(PREF_NFC_READER_OPTION_ON,
                                mDeviceConfigFacade.getDefaultReaderOption() || mInProvisionMode);
            }
        } else {
            // Turn off reader option if the device does not support reader mode.
            mIsReaderOptionEnabled = false;
        }

        mGesturePollFrameObserver = new GesturePollFrameObserver(mHandler);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(HostEmulationManager.GESTURE_POLL_FRAME_SETTINGS_KEY),
                false, mGesturePollFrameObserver);
        mGesturePollFrameObserver.updateGesturePollFrame();

        executeTaskBoot();  // do blocking boot tasks

        if ((NFC_SNOOP_LOG_MODE.equals(NfcProperties.snoop_log_mode_values.FULL) ||
            NFC_VENDOR_DEBUG_ENABLED) &&
                mDeviceConfigFacade.getEnableDeveloperNotification()) {
            new NfcDeveloperOptionNotification(mContext).startNotification();
        }

        connectToSeService();
    }

    private static Boolean isTaskBootCompleted() {
        return NfcProperties.initialized().orElse(Boolean.FALSE);
    }

    private void executeTaskBoot() {
        // If overlay is set, delay the NFC boot up until the OEM extension indicates it is ready to
        // proceed with NFC bootup.
        if (mDeviceConfigFacade.getEnableOemExtension()) {
            // Send intent for OEM extension to initialize.
            Intent intent = new Intent(NfcOemExtension.ACTION_OEM_EXTENSION_INIT);
            mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT, BIND_NFC_SERVICE);
            Log.i(TAG, "executeTaskBoot: Sent intent for OEM extension to initialize");
            return;
        }
        new EnableDisableTask().execute(TASK_BOOT);
    }

    private List<Integer> getEnabledUserIds() {
        List<Integer> userIds = new ArrayList<Integer>();
        UserManager um =
                mContext.createContextAsUser(UserHandle.of(ActivityManager.getCurrentUser()), 0)
                        .getSystemService(UserManager.class);
        List<UserHandle> luh = um.getEnabledProfiles();
        for (UserHandle uh : luh) {
            userIds.add(uh.getIdentifier());
        }
        return userIds;
    }

    private void initTagAppPrefList() {
        if (!mIsTagAppPrefSupported) return;
        boolean force = mTagAppBlockListHash != TAG_APP_BLOCKLIST.hashCode();
        if (force) {
            mTagAppBlockListHash = TAG_APP_BLOCKLIST.hashCode();
            mPrefsEditor.putInt(PREF_TAG_APP_BLOCK_LIST_HASH,
                    mTagAppBlockListHash);
            mPrefsEditor.apply();
        }
        mTagAppPrefList.clear();
        mTagAppPrefListPrefs = mContext.getSharedPreferences(PREF_TAG_APP_LIST,
                Context.MODE_PRIVATE);
        boolean changed = false;
        if (mTagAppPrefListPrefs == null) {
            Log.e(TAG, "initTagAppPrefList: Can't get PREF_TAG_APP_LIST");
            return;
        }
        try {
            for (Integer userId : getEnabledUserIds()) {
                HashMap<String, Boolean> map = new HashMap<>();
                String jsonString =
                        mTagAppPrefListPrefs.getString(Integer.toString(userId),
                                (new JSONObject()).toString());
                if (jsonString != null) {
                    JSONObject jsonObject = new JSONObject(jsonString);
                    Iterator<String> keysItr = jsonObject.keys();
                    while (keysItr.hasNext()) {
                        String key = keysItr.next();
                        Boolean value = jsonObject.getBoolean(key);
                        map.put(key, value);
                        if (DBG) {
                            Log.d(TAG, "initTagAppPrefList: uid:" + userId + "key:" + key + ": "
                                    + value);
                        }
                    }
                }
                // Put default blocked pkgs
                for (String pkg : TAG_APP_BLOCKLIST) {
                    if ((force || !map.containsKey(pkg))
                            && isPackageInstalled(pkg, userId)) {
                        map.put(pkg, false);
                        changed = true;
                    }
                }
                mTagAppPrefList.put(userId, map);
            }
        } catch (JSONException e) {
            Log.e(TAG, "initTagAppPrefList: JSONException=" + e);
        }
        if (changed) storeTagAppPrefList();
    }

    private void storeTagAppPrefList() {
        if (!mIsTagAppPrefSupported) return;
        mTagAppPrefListPrefs = mContext.getSharedPreferences(PREF_TAG_APP_LIST,
                Context.MODE_PRIVATE);
        if (mTagAppPrefListPrefs != null) {
            for (Integer userId : getEnabledUserIds()) {
                SharedPreferences.Editor editor = mTagAppPrefListPrefs.edit();
                HashMap<String, Boolean> map;
                synchronized (NfcService.this) {
                    map = mTagAppPrefList.getOrDefault(userId, new HashMap<>());
                }
                if (map.size() > 0) {
                    String userIdStr = Integer.toString(userId);
                    JSONObject jsonObject = new JSONObject(map);
                    String jsonString = jsonObject.toString();
                    editor.remove(userIdStr).putString(userIdStr, jsonString).apply();
                }
            }
        } else {
            Log.e(TAG, "storeTagAppPrefList: Can't get PREF_TAG_APP_LIST");
        }
    }
    private boolean isPackageInstalled(String pkgName, int userId) {
        final PackageInfo info;
        try {
            info = mContext.createContextAsUser(UserHandle.of(userId), 0)
                    .getPackageManager().getPackageInfo(pkgName, PackageManager.MATCH_ALL);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (IllegalStateException e) {
            Log.e(TAG, "Fail to create context for user " + userId + ": " + e);
            return false;
        }
        return info != null;
    }
    // Remove obsolete entries
    private void renewTagAppPrefList(String action) {
        if (!mIsTagAppPrefSupported) return;
        if (!action.equals(Intent.ACTION_PACKAGE_ADDED)
                && !action.equals(Intent.ACTION_PACKAGE_REMOVED)) return;
        boolean changed = false;
        for (Integer userId : getEnabledUserIds()) {
            synchronized (NfcService.this) {
                if (action.equals(Intent.ACTION_PACKAGE_ADDED)) {
                    HashMap<String, Boolean> map =
                            mTagAppPrefList.getOrDefault(userId, new HashMap<>());
                    for (String pkg : TAG_APP_BLOCKLIST) {
                        if (!map.containsKey(pkg) && isPackageInstalled(pkg, userId)) {
                            map.put(pkg, false);
                            changed = true;
                            mTagAppPrefList.put(userId, map);
                        }
                    }
                } else if (action.equals(Intent.ACTION_PACKAGE_REMOVED)) {
                    changed |= mTagAppPrefList.getOrDefault(userId, new HashMap<>())
                            .keySet().removeIf(k2 -> !isPackageInstalled(k2, userId));
                }
            }
        }
        if (DBG) Log.d(TAG, "renewTagAppPrefList: TagAppPreference changed " + changed);
        if (changed) storeTagAppPrefList();
    }

    private boolean isSEServiceAvailable() {
        if (mSEService == null) {
            connectToSeService();
        }
        return (mSEService != null);
    }

    private void connectToSeService() {
        try {
            mSEService = mNfcInjector.connectToSeService();
            if (mSEService != null) {
                IBinder seServiceBinder = mSEService.asBinder();
                seServiceBinder.linkToDeath(mSeServiceDeathRecipient, 0);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "connectToSeService: e=" + e);
        }
    }

    void initSoundPoolIfNeededAndPlaySound(Runnable playSoundRunnable) {
        if (mSoundPool == null) {
            // For the first sound play which triggers the sound pool initialization, play the
            // sound after sound pool load is complete.
            OnLoadCompleteListener onLoadCompleteListener = new OnLoadCompleteListener() {
                private int mNumLoadComplete = 0;
                @Override
                public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                    // Check that both end/error sounds are loaded before playing the sound.
                    if (++mNumLoadComplete == 2) {
                        Log.d(TAG, "initSoundPoolIfNeededAndPlaySound: playing sound");
                        playSoundRunnable.run();
                    }
                }
            };
            mSoundPool = new SoundPool.Builder()
                    .setMaxStreams(1)
                    .setAudioAttributes(
                            new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build())
                    .build();
            mSoundPool.setOnLoadCompleteListener(onLoadCompleteListener);
            mEndSound = mSoundPool.load(mContext, R.raw.end, 1);
            mErrorSound = mSoundPool.load(mContext, R.raw.error, 1);
        } else {
            // sound pool already loaded, play the sound.
            Log.d(TAG, "initSoundPoolIfNeededAndPlaySound: Sound pool is already loaded, "
                    + "playing sound");
            playSoundRunnable.run();
        }
    }

    void releaseSoundPool() {
        synchronized (this) {
            if (mSoundPool != null) {
                mSoundPool.release();
                mSoundPool = null;
            }
        }
    }

    /**
     * If a package requested a permission and package's sharedUserId is {@code android.uid.system},
     * the other packages having the same sharedUserId are considered to request the permission. So
     * {@code PackageManager::getPackagesHoldingPermissions} returns these packages even though they
     * don't request the permission. We need to check permissions of returned packages separately.
     */
    private List<String> getPackagesHoldingPermission(PackageManager pm, String permission) {
        List<String> packages = pm.getPackagesHoldingPermissions(
                new String[] { permission }, PackageManager.GET_PERMISSIONS)
                .stream()
                .filter((pkg) -> pkg.requestedPermissions != null
                        && Arrays.asList(pkg.requestedPermissions).contains(permission))
                .map((pkg) -> pkg.packageName)
                .collect(Collectors.toList());
        if (VDBG) {
            Log.v(TAG, "getPackagesHoldingPermission: got " + packages.size()
                    + " packages holding permission " + permission);
        }
        return packages;
    }

    void updatePackageCache() {
        UserManager um = mContext.createContextAsUser(
                UserHandle.of(ActivityManager.getCurrentUser()), /*flags=*/0)
                .getSystemService(UserManager.class);
        List<UserHandle> luh = um.getEnabledProfiles();

        synchronized (this) {
            mNfcEventInstalledPackages.clear();
            mNfcPreferredPaymentChangedInstalledPackages.clear();
            for (UserHandle uh : luh) {
                if (um.isQuietModeEnabled(uh)) continue;

                PackageManager pm;
                try {
                    pm = mContext.createContextAsUser(uh, /*flags=*/0).getPackageManager();
                } catch (IllegalStateException e) {
                    Log.d(TAG, "updatePackageCache: Fail to get PackageManager for user: " + uh);
                    continue;
                }

                List<String> nfcEventInstalledPackages =
                        getPackagesHoldingPermission(pm, NFC_TRANSACTION_EVENT);
                // Add "android" to the list of installed packages.
                if (nfcEventInstalledPackages != null
                        && !nfcEventInstalledPackages.contains("android")) {
                    nfcEventInstalledPackages.add("android");
                }
                mNfcEventInstalledPackages.put(uh.getIdentifier(), nfcEventInstalledPackages);
                List<String> nfcPreferredPaymentChangedInstalledPackages =
                        getPackagesHoldingPermission(pm, NFC_PREFERRED_PAYMENT_INFO);
                // Add "android" to the list of installed packages.
                if (nfcPreferredPaymentChangedInstalledPackages != null
                        && !nfcPreferredPaymentChangedInstalledPackages.contains("android")) {
                    nfcPreferredPaymentChangedInstalledPackages.add("android");
                }
                mNfcPreferredPaymentChangedInstalledPackages.put(
                        uh.getIdentifier(), nfcPreferredPaymentChangedInstalledPackages);
            }
        }
    }

    /**
     * Manages tasks that involve turning on/off the NFC controller.
     * <p/>
     * <p>All work that might turn the NFC adapter on or off must be done
     * through this task, to keep the handling of mState simple.
     * In other words, mState is only modified in these tasks (and we
     * don't need a lock to read it in these tasks).
     * <p/>
     * <p>These tasks are all done on the same AsyncTask background
     * thread, so they are serialized. Each task may temporarily transition
     * mState to STATE_TURNING_OFF or STATE_TURNING_ON, but must exit in
     * either STATE_ON or STATE_OFF. This way each task can be guaranteed
     * of starting in either STATE_OFF or STATE_ON, without needing to hold
     * NfcService.this for the entire task.
     * <p/>
     * <p>AsyncTask's are also implicitly queued. This is useful for corner
     * cases like turning airplane mode on while TASK_ENABLE is in progress.
     * The TASK_DISABLE triggered by airplane mode will be correctly executed
     * immediately after TASK_ENABLE is complete. This seems like the most sane
     * way to deal with these situations.
     * <p/>
     * <p>{@link #TASK_ENABLE} enables the NFC adapter, without changing
     * preferences
     * <p>{@link #TASK_DISABLE} disables the NFC adapter, without changing
     * preferences
     * <p>{@link #TASK_BOOT} does first boot work and may enable NFC
     */
    class EnableDisableTask extends AsyncTask<Integer, Void, Boolean> {
        int action;
        @Override
        protected Boolean doInBackground(Integer... params) {
            // Quick check mState
            switch (mState.get()) {
                case NfcAdapter.STATE_TURNING_OFF:
                case NfcAdapter.STATE_TURNING_ON:
                    Log.e(TAG, "Processing EnableDisable task " + params[0] + " from bad state " +
                            mState.get());
                    return false;
            }

            action = params[0].intValue();
            boolean result = true;
            /* AsyncTask sets this thread to THREAD_PRIORITY_BACKGROUND,
             * override with the default. THREAD_PRIORITY_BACKGROUND causes
             * us to service software I2C too slow for firmware download
             * with the NXP PN544.
             * TODO: move this to the DAL I2C layer in libnfc-nxp, since this
             * problem only occurs on I2C platforms using PN544
             */
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
            switch (action) {
                case TASK_ENABLE:
                    if (shouldEnableNfc()) {
                        onOemPreExecute();
                        result = enableInternal();
                        if (sIsNfcRestore && mIsTagAppPrefSupported) {
                            synchronized (NfcService.this) {
                                initTagAppPrefList();
                                sIsNfcRestore = false;
                            }
                        }
                    } else {
                        result = false;
                    }
                    break;
                case TASK_DISABLE:
                    if (allowOemDisable()) {
                        onOemPreExecute();
                        result = disableInternal();
                    } else {
                        result = false;
                    }
                    break;
                case TASK_BOOT:
                    // Initialize the event log cache.
                    boolean initialized;
                    if (mPrefs.getBoolean(PREF_FIRST_BOOT, true)) {
                        Log.i(TAG, "First Boot");
                        mPrefsEditor.putBoolean(PREF_FIRST_BOOT, false);
                        mPrefsEditor.apply();
                        mDeviceHost.factoryReset();
                        setPaymentForegroundPreference(mUserId);
                    }
                    Log.d(TAG, "checking on firmware download");
                    boolean enableNfc = shouldEnableNfc();
                    onOemPreExecute();
                    if (enableNfc) {
                        Log.d(TAG, "NFC is on. Doing normal stuff");
                        initialized = enableInternal();
                    } else {
                        Log.d(TAG, "NFC is off.  Checking firmware version");
                        initialized = mDeviceHost.checkFirmware();
                    }
                    mNfcEventLog.logEvent(
                            NfcEventProto.EventType.newBuilder()
                                    .setBootupState(NfcEventProto.NfcBootupState.newBuilder()
                                            .setEnabled(enableNfc)
                                            .build())
                            .build());
                    if (initialized) {
                        // TODO(279846422) The system property will be temporary
                        // available for vendors that depend on it.
                        // Remove this code when a replacement API is added.
                        NfcProperties.initialized(true);
                    }
                    synchronized (NfcService.this) {
                        initTagAppPrefList();
                    }
                    result = initialized;
                    break;
                case TASK_ENABLE_ALWAYS_ON:
                    /* Get mode from AsyncTask params */
                    result = enableAlwaysOnInternal(params[1]);
                    break;
                case TASK_DISABLE_ALWAYS_ON:
                    result = disableAlwaysOnInternal();
                    break;
                default:
                    break;
            }

            // Restore default AsyncTask priority
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            return result;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            Log.d(TAG, "onPostExecute: result - " + result);
            if (mNfcOemExtensionCallback != null) {
                try {
                    if (action == TASK_BOOT)
                        mNfcOemExtensionCallback
                                .onBootFinished(result ? STATUS_OK : STATUS_UNKNOWN_ERROR);
                    else if (action == TASK_ENABLE)
                        mNfcOemExtensionCallback
                                .onEnableFinished(result ? STATUS_OK : STATUS_UNKNOWN_ERROR);
                    else if (action == TASK_DISABLE)
                        mNfcOemExtensionCallback
                                .onDisableFinished(result ? STATUS_OK : STATUS_UNKNOWN_ERROR);
                } catch (RemoteException remoteException) {
                    Log.e(TAG, "onPostExecute: Failed to call remote oem extension callback");
                }
            }
        }

        void onOemPreExecute() {
            if (mNfcOemExtensionCallback != null) {
                try {
                    if (action == TASK_BOOT)
                        mNfcOemExtensionCallback.onBootStarted();
                    else if (action == TASK_ENABLE)
                        mNfcOemExtensionCallback.onEnableStarted();
                    else if (action == TASK_DISABLE)
                        mNfcOemExtensionCallback.onDisableStarted();
                } catch (RemoteException remoteException) {
                    Log.e(TAG, "onOemPreExecute: Failed to call remote oem extension callback");
                }
            }
        }

        boolean isAlwaysOnInDefaultMode() {
            return mAlwaysOnMode == NfcOemExtension.ENABLE_DEFAULT;
        }

        /**
         * Enable NFC adapter functions.
         * Does not toggle preferences.
         */
        boolean enableInternal() {
            if (DBG) Log.d(TAG, "EnableDisableTask.enableInternal: begin");
            if (mState.get() == NfcAdapter.STATE_ON) {
                return true;
            } else if (mAlwaysOnState == NfcAdapter.STATE_ON) {
                if (!isAlwaysOnInDefaultMode()) {
                    Log.i(TAG, "enableInternal: ControllerAlwaysOn Not In DEFAULT_MODE "
                            + "- disableAlwaysOn!");
                    disableAlwaysOnInternal();
                }
            }
            Log.i(TAG, "Enabling NFC");
            NfcStatsLog.write(NfcStatsLog.NFC_STATE_CHANGED,
                    mIsSecureNfcEnabled ? NfcStatsLog.NFC_STATE_CHANGED__STATE__ON_LOCKED :
                    NfcStatsLog.NFC_STATE_CHANGED__STATE__ON);
            updateState(NfcAdapter.STATE_TURNING_ON);

            WatchDogThread watchDog = new WatchDogThread("enableInternal", INIT_WATCHDOG_MS);
            watchDog.start();

            if (mIsHceCapable) {
                mCardEmulationManager.updateForDefaultSwpToEuicc();
            }
            try {
                mRoutingWakeLock.acquire();
                try {
                    boolean isRecovering;
                    synchronized (NfcService.this) {
                        isRecovering = mIsRecovering;
                    }
                    if (!mIsAlwaysOnSupported || isRecovering
                            || (mAlwaysOnState != NfcAdapter.STATE_ON
                                && mAlwaysOnState != NfcAdapter.STATE_TURNING_OFF)) {
                        if (isRecovering) {
                            // Recovering needs the full init. Put default value
                            mAlwaysOnState = NfcAdapter.STATE_OFF;
                            synchronized (mPowerSavingModeLock) {
                                mPowerSavingState = NfcAdapter.STATE_OFF;
                            }
                        }
                        if (!mDeviceHost.initialize()) {
                            Log.w(TAG, "enableInternal: Error enabling NFC");
                            updateState(NfcAdapter.STATE_OFF);
                            return false;
                        }
                    } else if (mAlwaysOnState == NfcAdapter.STATE_ON
                            || mAlwaysOnState == NfcAdapter.STATE_TURNING_OFF) {
                        Log.i(TAG, "enableInternal: Already initialized");
                    } else {
                        Log.e(TAG, "enableInternal: Unexpected bad state " + mAlwaysOnState);
                        updateState(NfcAdapter.STATE_OFF);
                        return false;
                    }
                } finally {
                    if (mRoutingWakeLock.isHeld()) {
                        mRoutingWakeLock.release();
                    }
                }
            } finally {
                watchDog.cancel();
            }

            mSkipNdefRead = NfcProperties.skipNdefRead().orElse(false);
            nci_version = getNciVersion();
            Log.d(TAG, "enableInternal: NCI_Version: " + nci_version);

            mPendingPowerStateUpdate = false;

            if (isPowerSavingModeEnabled()) {
                mDeviceHost.setPowerSavingMode(false);
            }
            synchronized (mPowerSavingModeLock) {
                mPowerSavingState = NfcAdapter.STATE_OFF;
            }
            synchronized (mObjectMapLock) {
                mObjectMap.clear();
                mTagObjectMap.clear();
            }
            synchronized (NfcService.this) {
                updateState(NfcAdapter.STATE_ON);

                onPreferredPaymentChanged(NfcAdapter.PREFERRED_PAYMENT_LOADED);
            }

            if (mInProvisionMode) {
                mScreenState = mScreenStateHelper.checkScreenStateProvisionMode();
            } else {
                mScreenState = mScreenStateHelper.checkScreenState(mCheckDisplayStateForScreenState);
            }
            int screen_state_mask = mScreenState;

            if (mNfcUnlockManager.isLockscreenPollingEnabled()) {
                screen_state_mask |= ScreenStateHelper.SCREEN_POLLING_TAG_MASK;
            }
            if (mScreenState == ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED) {
                if (android.app.Flags.deviceUnlockListener()
                        && mIsKeyguardLocked) {
                    Log.d(TAG, "enableInternal: Don't start polling when KeyguardLocked");
                } else {
                    screen_state_mask |= ScreenStateHelper.SCREEN_POLLING_TAG_MASK;
                }
            }

            mDeviceHost.doSetScreenState(screen_state_mask, mIsWlcEnabled);

            sToast_debounce = false;

            synchronized (NfcService.this) {
                restoreSavedTech();
            }

            // to synchronized between service and mw
            mDeviceHost.setNfcSecure(mIsSecureNfcEnabled);

            /* Skip applyRouting if always on state is switching */
            if (!mIsAlwaysOnSupported
                    || (mAlwaysOnState != NfcAdapter.STATE_TURNING_ON
                        && mAlwaysOnState != NfcAdapter.STATE_TURNING_OFF)) {
                /* Start polling loop */
                applyRouting(true);

                if (mIsHceCapable) {
                    // Generate the initial card emulation routing table
                    mCardEmulationManager.onNfcEnabled();
                }
            }
            synchronized (NfcService.this) {
                if (mIsRecovering) {
                    // Intents for all users
                    registerGlobalBroadcastsReceiver();
                    mIsRecovering = false;
                }
            }

            if (DBG) Log.d(TAG, "EnableDisableTask.enableInternal: end");
            return true;
        }

        /**
         * Disable all NFC adapter functions.
         * Does not toggle preferences.
         */
        boolean disableInternal() {
            if (DBG) Log.d(TAG, "EnableDisableTask.disableInternal: beging");
            if (mState.get() == NfcAdapter.STATE_OFF) {
                return true;
            }
            Log.i(TAG, "Disabling NFC");
            NfcStatsLog.write(
                    NfcStatsLog.NFC_STATE_CHANGED, NfcStatsLog.NFC_STATE_CHANGED__STATE__OFF);
            updateState(NfcAdapter.STATE_TURNING_OFF);

            /* Sometimes mDeviceHost.deinitialize() hangs, use a watch-dog.
             * Implemented with a new thread (instead of a Handler or AsyncTask),
             * because the UI Thread and AsyncTask thread-pools can also get hung
             * when the NFC controller stops responding */
            WatchDogThread watchDog = new WatchDogThread("disableInternal", ROUTING_WATCHDOG_MS);
            watchDog.start();

            if (mIsWlcEnabled) {
                if (mNfcCharging.NfcChargingOnGoing == true) {
                    mNfcCharging.disconnectNfcCharging();
                    mNfcCharging.NfcChargingOnGoing = false;
                }
                mNfcCharging.resetInternalValues();
            }

            if (mIsHceCapable) {
                mCardEmulationManager.onNfcDisabled();
            }

            // Stop watchdog if tag present
            // A convenient way to stop the watchdog properly consists of
            // disconnecting the tag. The polling loop shall be stopped before
            // to avoid the tag being discovered again.
            maybeDisconnectTarget();

            boolean isRecovering;
            synchronized (NfcService.this) {
                // Disable delay polling when disabling
                mPollDelayed = false;
                mPollDelayCount = 0;
                mReadErrorCount = 0;
                mHandler.removeMessages(MSG_DELAY_POLLING);
                mPollingDisableDeathRecipients.clear();
                mReaderModeParams = null;
                mDiscoveryTechParams = null;

                // Handle mIsRecovering under synchronized lock
                isRecovering = mIsRecovering;
            }
            mNfcDispatcher.resetForegroundDispatch();

            boolean result;
            if (!mIsAlwaysOnSupported || isRecovering
                    || (mAlwaysOnState == NfcAdapter.STATE_OFF)
                    || (mAlwaysOnState == NfcAdapter.STATE_TURNING_OFF)) {
                result = mDeviceHost.deinitialize();
                if (DBG) Log.d(TAG, "disableInternal: mDeviceHost.deinitialize() = " + result);
            } else {
                mDeviceHost.disableDiscovery();
                result = true;
                Log.i(TAG, "disableInternal: AlwaysOn set, disableDiscovery()");
            }

            watchDog.cancel();

            synchronized (NfcService.this) {
                mCurrentDiscoveryParameters = NfcDiscoveryParameters.getNfcOffParameters();
                updateState(NfcAdapter.STATE_OFF);
            }

            releaseSoundPool();
            if (DBG) Log.d(TAG, "EnableDisableTask.disableInternal: end");
            return result;
        }

        /**
         * Enable always on feature.
         */
        boolean enableAlwaysOnInternal(int mode) {
            if (mAlwaysOnState == NfcAdapter.STATE_ON) {
                return true;
            } else if (mState.get() == NfcAdapter.STATE_TURNING_ON
                    || mAlwaysOnState == NfcAdapter.STATE_TURNING_OFF) {
                Log.e(TAG, "enableAlwaysOnInternal: Processing from bad state");
                return false;
            } else if (mState.get() == NfcAdapter.STATE_ON) {
                updateAlwaysOnState(NfcAdapter.STATE_TURNING_ON);
                mDeviceHost.setNfceePowerAndLinkCtrl(true);
                updateAlwaysOnState(NfcAdapter.STATE_ON);
            } else if (mState.get() == NfcAdapter.STATE_OFF) {
                /* Special case when NFCC is OFF without initialize.
                 * Temporarily enable NfcAdapter but don't applyRouting.
                 * Then disable NfcAdapter without deinitialize to keep the NFCC stays initialized.
                 * mState will switch back to OFF in the end.
                 * And the NFCC stays initialized.
                 */
                updateAlwaysOnState(NfcAdapter.STATE_TURNING_ON);
                if (mode != NfcOemExtension.ENABLE_DEFAULT) {
                    mDeviceHost.setPartialInitMode(mode);
                    mAlwaysOnMode = mode;
                }
                if (!enableInternal()) {
                    updateAlwaysOnState(NfcAdapter.STATE_OFF);
                    return false;
                }
                disableInternal();
                mDeviceHost.setNfceePowerAndLinkCtrl(true);
                updateAlwaysOnState(NfcAdapter.STATE_ON);
            }
            return true;
        }

        /**
         * Disable always on feature.
         */
        boolean disableAlwaysOnInternal() {
            if (mAlwaysOnState == NfcAdapter.STATE_OFF) {
                return true;
            } else if ((mState.get() == NfcAdapter.STATE_TURNING_ON
                    || mAlwaysOnState == NfcAdapter.STATE_TURNING_OFF)
                    && (!(mAlwaysOnState == NfcAdapter.STATE_ON))) {
                if (!isAlwaysOnInDefaultMode()) {
                    Log.e(TAG, "disableAlwaysOnInternal: Processing from bad state");
                    return false;
                }
            } else if (mState.get() == NfcAdapter.STATE_ON) {
                updateAlwaysOnState(NfcAdapter.STATE_TURNING_OFF);
                mDeviceHost.setNfceePowerAndLinkCtrl(false);
                updateAlwaysOnState(NfcAdapter.STATE_OFF);
            } else if (mState.get() == NfcAdapter.STATE_OFF
                        || (mAlwaysOnState == NfcAdapter.STATE_ON)) {
                /* Special case when mState is OFF but NFCC is already initialized.
                 * Deinitialize mDevicehost directly.
                 */
                updateAlwaysOnState(NfcAdapter.STATE_TURNING_OFF);
                mDeviceHost.setNfceePowerAndLinkCtrl(false);
                boolean result = mDeviceHost.deinitialize();
                if (DBG) {
                    Log.d(TAG, "disableAlwaysOnInternal: mDeviceHost.deinitialize() = " + result);
                }
                updateAlwaysOnState(NfcAdapter.STATE_OFF);
                return result;
            }
            return true;
        }

        void updateState(int newState) {
            if (newState == mState.get()) {
                return;
            }
            mState.set(newState);
            synchronized (NfcService.this) {
                if (android.nfc.Flags.nfcEventListener() && mCardEmulationManager != null) {
                    mCardEmulationManager.onNfcStateChanged(newState);
                }
                if (mState.get() == NfcAdapter.STATE_ON && mCardEmulationManager != null) {
                    // Update default observe mode and exit frames lazily to avoid blocking on
                    // NfcService.this for a long duration.
                    mHandler.post(() -> {
                        Log.d(TAG, "updateState: Update default observe mode and"
                                + " exit frames after NFC enable");
                        mCardEmulationManager.updateForShouldDefaultToObserveMode(getUserId());
                        mCardEmulationManager.updateFirmwareExitFramesForWalletRole(getUserId());
                    });
                }
                if (mAlwaysOnState != NfcAdapter.STATE_TURNING_ON && !isPowerSavingModeChanging()) {
                    Intent intent = new Intent(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
                    intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                    intent.putExtra(NfcAdapter.EXTRA_ADAPTER_STATE, mState.get());
                    mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
                    if (mNfcOemExtensionCallback != null) {
                        try {
                            mNfcOemExtensionCallback.onStateUpdated(mState.get());
                        } catch (RemoteException remoteException) {
                            Log.e(TAG, "updateState: Failed to invoke onStateUpdated "
                                    + "oem callback");
                        }
                    }
                }
            }
        }

        void updateAlwaysOnState(int newState) {
            synchronized (NfcService.this) {
                if (newState == mAlwaysOnState) {
                    return;
                }
                if (newState == NfcAdapter.STATE_OFF) {
                    mAlwaysOnMode = NfcOemExtension.ENABLE_DEFAULT;
                    mDeviceHost.setPartialInitMode(NfcOemExtension.ENABLE_DEFAULT);
                }
                mAlwaysOnState = newState;
                if (mAlwaysOnState == NfcAdapter.STATE_OFF
                        || mAlwaysOnState == NfcAdapter.STATE_ON) {
                    synchronized (mAlwaysOnListeners) {
                        for (INfcControllerAlwaysOnListener listener
                                : mAlwaysOnListeners) {
                            try {
                                listener.onControllerAlwaysOnChanged(
                                        mAlwaysOnState == NfcAdapter.STATE_ON);
                            } catch (RemoteException e) {
                                Log.e(TAG, "updateAlwaysOnState: error");
                            }
                        }
                    }
                }
            }
        }
    }

    private void clearListenPollTech(boolean keepListenTech) {
        if (getNfcListenTech() != DEFAULT_LISTEN_TECH) {
            int listenTech = -1;
            int pollTech = (NfcAdapter.FLAG_READER_KEEP | NfcAdapter.FLAG_USE_ALL_TECH
                    | NfcAdapter.FLAG_SET_DEFAULT_TECH);
            if (keepListenTech) {
                Log.d(TAG, "clearListenPollTech: keep listenTech");
                listenTech = NfcAdapter.FLAG_LISTEN_KEEP;
            } else {
                Log.d(TAG, "clearListenPollTech: clear listenTech");
                listenTech = (NfcAdapter.FLAG_LISTEN_KEEP | NfcAdapter.FLAG_USE_ALL_TECH
                    | NfcAdapter.FLAG_SET_DEFAULT_TECH);
            }
            setDiscoveryTech(pollTech, listenTech);
        }
    }

    private void restoreSavedTech() {
        Log.i(TAG, "restoreSavedTech");
        int pollTech = -1;
        if (mPrefs.contains(PREF_POLL_TECH)) {
            pollTech = getNfcPollTech();
        }
        int listenTech = -1;
        if (mPrefs.contains(PREF_LISTEN_TECH)) {
            listenTech = getNfcListenTech();
        }
        if (listenTech == -1 || listenTech == DEFAULT_LISTEN_TECH)
            listenTech = (NfcAdapter.FLAG_LISTEN_KEEP|NfcAdapter.FLAG_USE_ALL_TECH);

        if (pollTech == -1 || pollTech == DEFAULT_POLL_TECH)
            pollTech = (NfcAdapter.FLAG_READER_KEEP|NfcAdapter.FLAG_USE_ALL_TECH);

        setDiscoveryTech(pollTech|NfcAdapter.FLAG_SET_DEFAULT_TECH,
                listenTech|NfcAdapter.FLAG_SET_DEFAULT_TECH);
    }

    private void setDiscoveryTech(int pollTech, int listenTech) {
        if(mReaderModeParams != null) {
            Log.d(TAG, "setDiscoveryTech mReaderModeParams.flags = 0x"
                + Integer.toHexString(mReaderModeParams.flags));
            mDeviceHost.setDiscoveryTech(
                    mReaderModeParams.flags == DISABLE_POLLING_FLAGS
                        ? NfcAdapter.FLAG_READER_DISABLE
                        : mReaderModeParams.flags, listenTech);
        } else {
            mDeviceHost.setDiscoveryTech(pollTech, listenTech);
        }
    }

    public void playSound(int sound) {
        synchronized (this) {
            switch (sound) {
                case SOUND_END:
                    // Lazy init sound pool when needed.
                    initSoundPoolIfNeededAndPlaySound(() -> {
                        int playReturn = mSoundPool.play(mEndSound, 1.0f, 1.0f, 0, 0, 1.0f);
                        Log.d(TAG, "playSound: Sound pool play return: " + playReturn);
                    });
                    break;
                case SOUND_ERROR:
                    // Lazy init sound pool when needed.
                    initSoundPoolIfNeededAndPlaySound(() -> {
                        int playReturn = mSoundPool.play(mErrorSound, 1.0f, 1.0f, 0, 0, 1.0f);
                        Log.d(TAG, "playSound: Sound pool play return: " + playReturn);
                    });
                    break;
            }
        }
    }

    synchronized int getUserId() {
        return mUserId;
    }

    private void resetReaderModeParams() {
        synchronized (NfcService.this) {
            if (mPollingDisableDeathRecipients.size() == 0) {
                Log.d(TAG, "resetReaderModeParams: Disabling reader mode because app died"
                        + " or moved to background");
                mReaderModeParams = null;
                StopPresenceChecking(false);
                // listenTech is different from the default value, the stored listenTech will be included.
                // When using enableReaderMode, change listenTech to default & restore to the previous value.
                if (isNfcEnabled()) {
                    restoreSavedTech();
                }
                mNfcEventLog.logEvent(
                        NfcEventProto.EventType.newBuilder()
                                .setReaderModeChange(NfcEventProto.NfcReaderModeChange.newBuilder()
                                        .setFlags(0)
                                        .build())
                                .build());
                if (isNfcEnabled()) {
                    applyRouting(false);
                }
            }
        }
    }

    @Override
    public void onUidToBackground(int uid) {
        Log.i(TAG, "onUidToBackground: Uid " + uid);
        synchronized (NfcService.this) {
            if (mReaderModeParams != null && mReaderModeParams.uid == uid) {
                mReaderModeParams.binder.unlinkToDeath(mReaderModeDeathRecipient, 0);
                resetReaderModeParams();
            }
            if (mDiscoveryTechParams != null && mDiscoveryTechParams.uid == uid) {
                try {
                    mDiscoveryTechParams.binder.unlinkToDeath(mDiscoveryTechDeathRecipient, 0);
                } catch (NoSuchElementException e) {
                    // ignore
                }
                mDeviceHost.resetDiscoveryTech();
                mDiscoveryTechParams = null;
                if (isNfcEnabled()) {
                  applyRouting(true);
                }
            }
        }
    }

    public void enableNfc() {
        saveNfcOnSetting(true);

        new EnableDisableTask().execute(TASK_ENABLE);
    }

    private @NonNull CharSequence getAppName(@NonNull String packageName, int uid) {
        ApplicationInfo applicationInfo = null;
        try {
            applicationInfo = mContext.getPackageManager().getApplicationInfoAsUser(
                    packageName, 0, UserHandle.getUserHandleForUid(uid));
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "getAppName: Failed for " + packageName);
            return "";
        }
        return mContext.getPackageManager().getApplicationLabel(applicationInfo);
    }

    public boolean isSecureNfcEnabled() {
        return mIsSecureNfcEnabled;
    }

    /** Helper method to check if the entity initiating the binder call is a DO/PO app. */
    private boolean isDeviceOrProfileOwner(int uid, String packageName) {
        return mNfcPermissions.isDeviceOwner(uid, packageName)
                || mNfcPermissions.isProfileOwner(uid, packageName);
    }

    /**
     * Method to check observe mode for any internal observe mode checks.
     */
    public boolean isObserveModeEnabled() {
        return mDeviceHost.isObserveModeEnabled();
    }

    /**
     * Method to toggle observe mode for any internal observe mode transitions, don't use this
     * for calls done on behalf of apps state toggle request.
     */
    public boolean setObserveMode(boolean enable) {
        return setObserveModeInternal(
                enable, Binder.getCallingUid(), mContext.getPackageName(),
                StatsdUtils.TRIGGER_SOURCE_UNKNOWN);
    }

    /**
     * Helper method to toggle observe mode and log the event.
     */
    private boolean setObserveModeInternal(boolean enable, int callingUid, String packageName,
            int triggerSource) {
        Log.d(TAG, "setObserveModeInternal: " + enable + ", uid: " + callingUid + ", pkg: "
                + packageName + ", triggerSrc: " + triggerSource);
        synchronized (NfcService.this) {
            if (mCardEmulationManager.isHostCardEmulationActivated()) {
                Log.w(TAG, "setObserveModeInternal: Cannot set observe mode during a transaction.");
                return false;
            }
            if (mTagConnected) {
                Log.w(TAG, "setObserveModeInternal: Cannot set observe mode "
                        + "during tag operations.");
                return false;
            }
            long start = SystemClock.elapsedRealtime();
            Trace.beginSection("setObserveModeInternal: " + enable);
            boolean result = mDeviceHost.setObserveMode(enable);
            Trace.endSection();
            int latency = Math.toIntExact(SystemClock.elapsedRealtime() - start);
            if (mStatsdUtils != null) {
                mStatsdUtils.logObserveModeStateChanged(enable, triggerSource, latency);
            }
            mNfcEventLog.logEvent(
                    NfcEventProto.EventType.newBuilder()
                            .setObserveModeChange(
                                    NfcEventProto.NfcObserveModeChange.newBuilder()
                                            .setAppInfo(
                                                    NfcEventProto.NfcAppInfo.newBuilder()
                                                            .setPackageName(packageName)
                                                            .setUid(callingUid)
                                                            .build())
                                            .setEnable(enable)
                                            .setLatencyMs(latency)
                                            .setResult(result)
                                            .build())
                            .build());
            return result;
        }
    }

    final class NfcAdapterService extends INfcAdapter.Stub {
        @Override
        public boolean enable(String pkg) throws RemoteException {
            mNfcPermissions.checkPackage(Binder.getCallingUid(), pkg);
            boolean isDeviceOrProfileOwner = isDeviceOrProfileOwner(Binder.getCallingUid(), pkg);
            if (!NfcPermissions.checkAdminPermissions(mContext)
                    && !isDeviceOrProfileOwner) {
                throw new SecurityException(
                        "caller is not a system app, device owner or profile owner!");
            }
            if (!isDeviceOrProfileOwner && isNfcUserChangeRestricted()) {
                throw new SecurityException("Change nfc state by system app is not allowed!");
            }

            if (!isTaskBootCompleted()) {
                Log.e(TAG, "enable: NFC is not initialized yet:" + isTaskBootCompleted());
                return false;
            }

            notifyOemLogEvent(new OemLogItems.Builder(OemLogItems.LOG_ACTION_NFC_TOGGLE)
                    .setCallingPid(Binder.getCallingPid())
                    .setCallingEvent(EVENT_ENABLE)
                    .build());

            Log.i(TAG, "enable: enabling, package:" + pkg);
            List<String> allowlist = new ArrayList<>(
                    Arrays.asList(mContext.getResources().getStringArray(R.array.nfc_allow_list)));
            if (!allowlist.isEmpty() && !allowlist.contains(pkg)) {
                Intent allowUsingNfcIntent = new Intent()
                        .putExtra(APP_NAME_ENABLING_NFC, getAppName(pkg, getUserId()))
                        .setClass(mContext, NfcEnableAllowlistActivity.class);

                mContext.startActivityAsUser(allowUsingNfcIntent, UserHandle.CURRENT);
                return true;
            }
            mNfcEventLog.logEvent(
                    NfcEventProto.EventType.newBuilder()
                            .setStateChange(NfcEventProto.NfcStateChange.newBuilder()
                                    .setAppInfo(NfcEventProto.NfcAppInfo.newBuilder()
                                            .setPackageName(pkg)
                                            .setUid(Binder.getCallingUid())
                                            .build())
                                    .setEnabled(true)
                                    .build())
                            .build());
            if (android.nfc.Flags.nfcStateChangeSecurityLogEventEnabled()) {
                SecurityLog.writeEvent(SecurityLog.TAG_NFC_ENABLED);
            }
            enableNfc();
            return true;
        }

        @Override
        public boolean disable(boolean saveState, String pkg) throws RemoteException {
            mNfcPermissions.checkPackage(Binder.getCallingUid(), pkg);

            boolean isDeviceOrProfileOwner = isDeviceOrProfileOwner(Binder.getCallingUid(), pkg);
            if (!NfcPermissions.checkAdminPermissions(mContext)
                    && !isDeviceOrProfileOwner) {
                throw new SecurityException(
                        "caller is not a system app, device owner or profile owner!");
            }
            if (!isDeviceOrProfileOwner && isNfcUserChangeRestricted()) {
                throw new SecurityException("Change nfc state by system app is not allowed!");
            }

            notifyOemLogEvent(new OemLogItems.Builder(OemLogItems.LOG_ACTION_NFC_TOGGLE)
                    .setCallingPid(Binder.getCallingPid())
                    .setCallingEvent(EVENT_DISABLE)
                    .build());

            Log.i(TAG, "disable: disabling, package:" + pkg);
            if (saveState) {
                saveNfcOnSetting(false);
            }

            mNfcEventLog.logEvent(
                    NfcEventProto.EventType.newBuilder()
                            .setStateChange(NfcEventProto.NfcStateChange.newBuilder()
                                    .setAppInfo(NfcEventProto.NfcAppInfo.newBuilder()
                                            .setPackageName(pkg)
                                            .setUid(Binder.getCallingUid())
                                            .build())
                                    .setEnabled(false)
                                    .build())
                            .build());
            if (android.nfc.Flags.nfcStateChangeSecurityLogEventEnabled()) {
                SecurityLog.writeEvent(SecurityLog.TAG_NFC_DISABLED);
            }
            new EnableDisableTask().execute(TASK_DISABLE);

            return true;
        }

        @Override
        public boolean isReaderModeAnnotationSupported() {
            return mDeviceHost.isReaderModeAnnotationSupported();
        }

        @Override
        public boolean isObserveModeSupported() {
            if (!isNfcEnabled()) {
                Log.e(TAG, "isObserveModeSupported: NFC must be enabled but is: " + mState.get());
                return false;
            }
            return mDeviceHost.isObserveModeSupported();
        }

        @Override
        public boolean isObserveModeEnabled() {
            if (!isNfcEnabled()) {
                Log.e(TAG, "isObserveModeEnabled: NFC must be enabled but is: " + mState.get());
                return false;
            }
            synchronized (NfcService.this) {
                NfcPermissions.enforceUserPermissions(mContext);
                return NfcService.this.isObserveModeEnabled();
            }
        }

        @Override
        public boolean setObserveMode(boolean enable, String packageName) {
            synchronized (NfcService.this) {
                mNfcPermissions.checkPackage(Binder.getCallingUid(), packageName);
                if (!isNfcEnabled()) {
                    Log.e(TAG, "setObserveMode: NFC must be enabled but is: " + mState.get());
                    return false;
                }
                int callingUid = Binder.getCallingUid();
                UserHandle callingUser = Binder.getCallingUserHandle();
                int triggerSource = StatsdUtils.TRIGGER_SOURCE_UNKNOWN;

                if (!NfcInjector.isPrivileged(callingUid)) {
                    NfcPermissions.enforceUserPermissions(mContext);
                    if (packageName == null) {
                        Log.e(TAG, "setObserveMode: no package name associated with "
                                + "non-privileged calling UID");
                    }
                    if (mCardEmulationManager.isPreferredServicePackageNameForUser(
                            packageName, callingUser.getIdentifier())) {
                        if (android.permission.flags.Flags.walletRoleEnabled()) {
                            if (packageName != null) {
                                triggerSource =
                                        packageName.equals(getWalletRoleHolder(callingUser))
                                                ? StatsdUtils.TRIGGER_SOURCE_WALLET_ROLE_HOLDER
                                                : StatsdUtils.TRIGGER_SOURCE_FOREGROUND_APP;
                            }
                        } else {
                            if (mForegroundUtils.isInForeground(callingUid)) {
                                triggerSource = StatsdUtils.TRIGGER_SOURCE_FOREGROUND_APP;
                            }
                        }
                    } else {
                        Log.e(TAG, "setObserveMode: Caller not preferred NFC service.");
                        return false;
                    }
                }
                Log.d(TAG, "setObserveMode: " + enable + ", uid: " + callingUid + ", pkg: "
                        + packageName + ", triggerSrc: " + triggerSource);
                return NfcService.this.setObserveModeInternal(
                        enable, callingUid, packageName, triggerSource);
            }
        }

        private String getWalletRoleHolder(UserHandle user) {
            RoleManager roleManager = mContext.createContextAsUser(user, 0)
                    .getSystemService(RoleManager.class);
            List<String> roleHolders = roleManager.getRoleHolders(RoleManager.ROLE_WALLET);
            return roleHolders.isEmpty() ? null : roleHolders.get(0);
        }

        @Override
        public boolean isExitFramesSupported() {
            synchronized (NfcService.this) {
                return NfcService.this.isFirmwareExitFramesSupported()
                        && NfcService.this.getNumberOfFirmwareExitFramesSupported() > 0;
            }
        }

        @Override
        public boolean isPowerSavingModeSupported() {
            if (!isNfcEnabled()) {
                Log.e(TAG, "isPowerSavingModeSupported: NFC must be enabled but is: "
                        + mState.get());
                return false;
            }
            synchronized (NfcService.this) {
                NfcPermissions.enforceUserPermissions(mContext);
                return mDeviceHost.isPowerSavingModeSupported();
            }
        }

        @Override
        public boolean isPowerSavingModeEnabled() {
            synchronized (NfcService.this) {
                return NfcService.this.isPowerSavingModeEnabled();
            }
        }

        @Override
        public void setPowerSavingMode(boolean enabled) {
            synchronized (NfcService.this) {
                NfcService.this.setPowerSavingModeInternal(enabled);
            }
        }

        @Override
        public int pausePolling(long timeoutInMs) {
            NfcPermissions.enforceAdminPermissions(mContext);

            checkAndHandleRemovalDetectionMode(false);
            synchronized (mDiscoveryLock) {
                if (!mRfDiscoveryStarted) {
                    if (DBG) Log.d(TAG, "pausePolling: already disabled!");
                    return NfcOemExtension.POLLING_STATE_CHANGE_ALREADY_IN_REQUESTED_STATE;
                }
            }
            synchronized (NfcService.this) {
                mPollingPaused = true;
                StopPresenceChecking(false);
                mDeviceHost.disableDiscovery();
                /* timeoutInMs 0 will stop discovery without any timeout
                 * polling will not auto resume */
                if (timeoutInMs == 0) {
                    if (DBG) Log.d(TAG, "pausePolling: without timeout");
                    return NfcOemExtension.POLLING_STATE_CHANGE_SUCCEEDED;
                }
                if (timeoutInMs < 0 || timeoutInMs > this.getMaxPausePollingTimeoutMs()) {
                    throw new IllegalArgumentException(
                        "Invalid timeout " + timeoutInMs + " ms!");
                }
                mHandler.sendMessageDelayed(
                        mHandler.obtainMessage(MSG_RESUME_POLLING), timeoutInMs);
                return NfcOemExtension.POLLING_STATE_CHANGE_SUCCEEDED;
            }
        }

        @Override
        public int resumePolling() {
            NfcPermissions.enforceAdminPermissions(mContext);
            boolean rfDiscoveryStarted;
            synchronized (mDiscoveryLock) {
                rfDiscoveryStarted = mRfDiscoveryStarted;
            }
            synchronized (NfcService.this) {
                if (!mPollingPaused) {
                    if (rfDiscoveryStarted) {
                        if (DBG) Log.d(TAG, "resumePolling: already enabled!");
                        return NfcOemExtension.POLLING_STATE_CHANGE_ALREADY_IN_REQUESTED_STATE;
                    } else {
                        if (DBG) Log.d(TAG, "resumePolling: Enable explicitly!");
                    }
                }
                mHandler.removeMessages(MSG_RESUME_POLLING);
                if (mCardEmulationManager != null) {
                    mCardEmulationManager.resetToIdleState();
                }
                mPollingPaused = false;
                new ApplyRoutingTask().execute();
                if (DBG) Log.d(TAG, "resumePolling: done");
                return NfcOemExtension.POLLING_STATE_CHANGE_SUCCEEDED;
            }
        }

        @Override
        public boolean isNfcSecureEnabled() throws RemoteException {
            synchronized (NfcService.this) {
                int current_userId = ActivityManager.getCurrentUser();
                if (mUserId != current_userId) {
                    loadSecureNfcSettings(current_userId);
                }
                return mIsSecureNfcEnabled;
            }
        }

        @Override
        public boolean setNfcSecure(boolean enable) {
            NfcPermissions.enforceAdminPermissions(mContext);
            if (mNfcInjector.isDeviceLocked() && !enable) {
                Log.i(TAG,
                        "setNfcSecure: Device need to be unlocked before setting Secure NFC OFF");
                return false;
            }

            synchronized (NfcService.this) {
                if (mIsSecureNfcEnabled == enable) {
                    Log.e(TAG, "setNfcSecure: error, can't apply the same state twice!");
                    return false;
                }
                Log.i(TAG, "setNfcSecure: " + enable);
                final int currentUserId = getUserId();
                final String secureNfcPreferenceKey =
                        getSecureNfcPreferenceKeyForUser(currentUserId);
                mPrefsEditor.putBoolean(secureNfcPreferenceKey, enable);
                Log.i(TAG, "currentUserId: " + currentUserId);
                // If the change is made by the Primary User (SYSTEM), use the setting
                // as new default.
                // Check for System User ID
                if (currentUserId == UserHandle.SYSTEM.getIdentifier()) {
                    mDeviceConfigFacade.setDefaultSecureNfcState(enable);
                }

                mPrefsEditor.apply();
                mIsSecureNfcEnabled = enable;
                mBackupManager.dataChanged();
                mDeviceHost.setNfcSecure(enable);
                if (android.nfc.Flags.nfcPersistLog()) {
                    mNfcEventLog.logEvent(
                            NfcEventProto.EventType.newBuilder()
                                    .setSecureChange(
                                            NfcEventProto.NfcSecureChange.newBuilder()
                                                    .setEnable(enable)
                                                    .build())
                                    .build());
                }
                if (mIsHceCapable) {
                    // update HCE/HCEF routing and commitRouting if Nfc is enabled
                    mCardEmulationManager.onTriggerRoutingTableUpdate();
                } else if (isNfcEnabled()) {
                    // commit only tech/protocol route without HCE support
                    mDeviceHost.commitRouting();
                }
            }

            NfcStatsLog.write(NfcStatsLog.NFC_STATE_CHANGED,
                    mIsSecureNfcEnabled ? NfcStatsLog.NFC_STATE_CHANGED__STATE__ON_LOCKED :
                    NfcStatsLog.NFC_STATE_CHANGED__STATE__ON);
            return true;
        }

        @Override
        public void setForegroundDispatch(PendingIntent intent,
                IntentFilter[] filters, TechListParcel techListsParcel) {
            NfcPermissions.enforceUserPermissions(mContext);
            if (!mForegroundUtils.isInForeground(Binder.getCallingUid())) {
                Log.e(TAG, "setForegroundDispatch: Caller not in foreground.");
                return;
            }
            // Short-cut the disable path
            if (intent == null && filters == null && techListsParcel == null) {
                mNfcDispatcher.resetForegroundDispatch();
                return;
            }

            // Validate the IntentFilters
            if (filters != null) {
                if (filters.length == 0) {
                    filters = null;
                } else {
                    for (IntentFilter filter : filters) {
                        if (filter == null) {
                            throw new IllegalArgumentException("null IntentFilter");
                        }
                    }
                }
            }

            // Validate the tech lists
            String[][] techLists = null;
            if (techListsParcel != null) {
                techLists = techListsParcel.getTechLists();
            }

            mNfcDispatcher.setForegroundDispatch(intent, filters, techLists);
        }


        @Override
        public void setAppCallback(IAppCallback callback) {
            NfcPermissions.enforceUserPermissions(mContext);
        }

        @Override
        public boolean ignore(int nativeHandle, int debounceMs, ITagRemovedCallback callback)
                throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            if (nativeHandle == MOCK_NATIVE_HANDLE
                    || (debounceMs == 0 && mDebounceTagNativeHandle != INVALID_NATIVE_HANDLE
                        && nativeHandle == mDebounceTagNativeHandle)) {
                // Remove any previous messages and immediately debounce.
                mHandler.removeMessages(MSG_TAG_DEBOUNCE);
                synchronized (NfcService.this) {
                    mDebounceTagRemovedCallback = callback;
                }
                mHandler.sendEmptyMessage(MSG_TAG_DEBOUNCE);
                return true;
            }

            TagEndpoint tag = (TagEndpoint) findAndRemoveObject(nativeHandle);
            if (tag != null) {
                // Store UID and params
                int uidLength = tag.getUid().length;
                synchronized (NfcService.this) {
                    mDebounceTagDebounceMs = debounceMs;
                    mDebounceTagNativeHandle = nativeHandle;
                    mDebounceTagUid = new byte[uidLength];
                    mDebounceTagRemovedCallback = callback;
                    System.arraycopy(tag.getUid(), 0, mDebounceTagUid, 0, uidLength);
                }

                // Disconnect from this tag; this should resume the normal
                // polling loop (and enter listen mode for a while), before
                // we pick up any tags again.
                tag.disconnect();
                mHandler.sendEmptyMessageDelayed(MSG_TAG_DEBOUNCE, debounceMs);
                return true;
            } else {
                return false;
            }
        }

        @Override
        public void verifyNfcPermission() {
            NfcPermissions.enforceUserPermissions(mContext);
        }

        @Override
        public INfcTag getNfcTagInterface() throws RemoteException {
            return mNfcTagService;
        }

        @Override
        public INfcCardEmulation getNfcCardEmulationInterface() {
            if (mIsHceCapable) {
                return mCardEmulationManager.getNfcCardEmulationInterface();
            } else {
                return null;
            }
        }

        @Override
        public INfcFCardEmulation getNfcFCardEmulationInterface() {
            if (mIsHceFCapable) {
                return mCardEmulationManager.getNfcFCardEmulationInterface();
            } else {
                return null;
            }
        }

        @Override
        public int getState() throws RemoteException {
            return mState.get();
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            NfcService.this.dump(fd, pw, args);
        }

        @Override
        public void dispatch(Tag tag) throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            mNfcDispatcher.dispatchTag(tag);
        }

        @Override
        public void updateDiscoveryTechnology(
                IBinder binder, int pollTech, int listenTech, String packageName)
                throws RemoteException {
            mNfcPermissions.checkPackage(Binder.getCallingUid(), packageName);
            NfcPermissions.enforceUserPermissions(mContext);
            int callingUid = Binder.getCallingUid();
            boolean privilegedCaller = NfcInjector.isPrivileged(callingUid)
                    || NfcPermissions.checkAdminPermissions(mContext);
            // Allow non-foreground callers with system uid or systemui
            privilegedCaller |= packageName.equals(SYSTEM_UI);
            Log.d(TAG, "updateDiscoveryTechnology: uid=" + callingUid +
                    ", packageName: " + packageName);

            boolean isSetDefault = (pollTech & NfcAdapter.FLAG_SET_DEFAULT_TECH) != 0
                    || (listenTech & NfcAdapter.FLAG_SET_DEFAULT_TECH) != 0;

            if (privilegedCaller && isSetDefault) {
                handleSetDefaultTechnology(pollTech, listenTech);
                return;
            }

            if (!privilegedCaller) {
                if (isSetDefault) {
                    Log.e(TAG, "updateDiscoveryTechnology: "
                            + "Requires admin permissions to set default technology.");
                    return;
                }
                if (!mForegroundUtils.registerUidToBackgroundCallback(
                            NfcService.this, callingUid)) {
                    Log.e(TAG,
                          "updateDiscoveryTechnology: Unprivileged caller shall be in foreground");
                    return;
                }
            }
            handleTemporaryTechnologyUpdate(binder, pollTech, listenTech, packageName, callingUid);
        }

        private void handleSetDefaultTechnology(int pollTech, int listenTech) {
            if (!isNfcEnabled()) {
                Log.d(TAG, "handleSetDefaultTechnology: NFC is not enabled.");
                return;
            }
            if ((pollTech & NfcAdapter.FLAG_SET_DEFAULT_TECH) != 0) {
                if ((pollTech & NfcAdapter.FLAG_READER_KEEP) == 0
                        && (pollTech & NfcAdapter.FLAG_USE_ALL_TECH)
                        != NfcAdapter.FLAG_USE_ALL_TECH) {
                    pollTech = getReaderModeTechMask(pollTech);
                    saveNfcPollTech(pollTech & ~NfcAdapter.FLAG_SET_DEFAULT_TECH);
                    Log.i(TAG, "handleSetDefaultTechnology: Default pollTech is set to 0x"
                            + Integer.toHexString(pollTech));
                } else if ((pollTech
                        & (NfcAdapter.FLAG_READER_KEEP | NfcAdapter.FLAG_USE_ALL_TECH))
                        == (NfcAdapter.FLAG_READER_KEEP | NfcAdapter.FLAG_USE_ALL_TECH)) {
                    saveNfcPollTech(DEFAULT_POLL_TECH);
                }
            }
            if ((listenTech & NfcAdapter.FLAG_SET_DEFAULT_TECH) != 0) {
                if ((listenTech & NfcAdapter.FLAG_LISTEN_KEEP) == 0
                        && (listenTech & NfcAdapter.FLAG_USE_ALL_TECH)
                        != NfcAdapter.FLAG_USE_ALL_TECH) {
                    saveNfcListenTech(listenTech & ~NfcAdapter.FLAG_SET_DEFAULT_TECH);
                    Log.i(TAG, "handleSetDefaultTechnology: Default listenTech is set to 0x"
                            + Integer.toHexString(listenTech));
                } else if ((listenTech
                        & (NfcAdapter.FLAG_LISTEN_KEEP | NfcAdapter.FLAG_USE_ALL_TECH))
                        == (NfcAdapter.FLAG_LISTEN_KEEP | NfcAdapter.FLAG_USE_ALL_TECH)) {
                    saveNfcListenTech(DEFAULT_LISTEN_TECH);
                }
            }
            if ((pollTech & NfcAdapter.FLAG_READER_KEEP) != 0) {
                pollTech = getNfcPollTech();
            }
            if ((listenTech & NfcAdapter.FLAG_LISTEN_KEEP) != 0) {
                listenTech = getNfcListenTech();
            }

            if (mCardEmulationManager != null) {
                mCardEmulationManager.resetToIdleState();
            }
            setDiscoveryTech(pollTech, listenTech);
            applyRouting(true);
        }

        private void handleTemporaryTechnologyUpdate(IBinder binder, int pollTech, int listenTech,
                String packageName, int callingUid) {
            checkAndHandleRemovalDetectionMode(false);
            synchronized (NfcService.this) {
                if (!isNfcEnabled()) {
                    Log.d(TAG, "handleTemporaryTechnologyUpdate: NFC is not enabled.");
                    return;
                }

                Log.d(TAG, "handleTemporaryTechnologyUpdate: pollTech=0x"
                        + Integer.toHexString(pollTech) + ", listenTech=0x"
                        + Integer.toHexString(listenTech));
                if (pollTech == NfcAdapter.FLAG_USE_ALL_TECH &&
                        listenTech == NfcAdapter.FLAG_USE_ALL_TECH &&
                        mDiscoveryTechParams != null) {
                    try {
                        mDiscoveryTechParams.binder.unlinkToDeath(mDiscoveryTechDeathRecipient, 0);
                    } catch (NoSuchElementException e) {
                        Log.e(TAG, "handleTemporaryTechnologyUpdate: "
                                + "Change Tech Binder was never registered");
                    }
                    mDeviceHost.resetDiscoveryTech();
                    mDiscoveryTechParams = null;
                } else if (!(pollTech == NfcAdapter.FLAG_USE_ALL_TECH
                        && listenTech == NfcAdapter.FLAG_USE_ALL_TECH)) {
                    if (mDiscoveryTechParams != null) {
                        try {
                            mDiscoveryTechParams.binder.unlinkToDeath(
                                    mDiscoveryTechDeathRecipient, 0);
                        } catch (NoSuchElementException e) {
                            // ignore
                        }
                    }
                    if ((pollTech & NfcAdapter.FLAG_READER_KEEP) != 0) {
                        pollTech = getNfcPollTech();
                    } else {
                        pollTech = getReaderModeTechMask(pollTech);
                    }
                    if ((listenTech & NfcAdapter.FLAG_LISTEN_KEEP) != 0) {
                        listenTech = getNfcListenTech();
                    }
                    try {
                        setDiscoveryTech(pollTech, listenTech);
                        mDiscoveryTechParams = new DiscoveryTechParams();
                        mDiscoveryTechParams.uid = callingUid;
                        mDiscoveryTechParams.binder = binder;
                        binder.linkToDeath(mDiscoveryTechDeathRecipient, 0);
                    } catch (RemoteException e) {
                        Log.e(TAG, "handleTemporaryTechnologyUpdate: "
                                + "Remote binder has already died");
                        return;
                    }
                } else {
                    return;
                }
                mNfcEventLog.logEvent(
                        NfcEventProto.EventType.newBuilder()
                                .setDiscoveryTechnologyUpdate(NfcEventProto
                                        .NfcDiscoveryTechnologyUpdate.newBuilder()
                                        .setAppInfo(NfcEventProto.NfcAppInfo
                                                .newBuilder()
                                                .setPackageName(packageName)
                                                .setUid(callingUid)
                                                .build())
                                        .setPollTech(pollTech)
                                        .setListenTech(listenTech)
                                        .build())
                                .build());
                if (mCardEmulationManager != null) {
                    mCardEmulationManager.resetToIdleState();
                }
                applyRouting(true);
            }
        }

        @Override
        public void setReaderMode(
                IBinder binder, IAppCallback callback, int flags, Bundle extras, String packageName)
                throws RemoteException {
            mNfcPermissions.checkPackage(Binder.getCallingUid(), packageName);
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            boolean privilegedCaller = NfcInjector.isPrivileged(callingUid)
                    || NfcPermissions.checkAdminPermissions(mContext);
            // Allow non-foreground callers with system uid or systemui
            privilegedCaller |= packageName.equals(SYSTEM_UI);

            String annotationStr = "null";
            if (extras != null
                    && extras.containsKey(NfcAdapter.EXTRA_READER_TECH_A_POLLING_LOOP_ANNOTATION)) {

                byte[] annotationBytes = extras.getByteArray(
                        NfcAdapter.EXTRA_READER_TECH_A_POLLING_LOOP_ANNOTATION);

                if (annotationBytes != null) {
                    annotationStr = HexFormat.of().formatHex(annotationBytes);
                } else {
                    Log.i(TAG, "Annotation bytes are null");
                }
            }
            Log.d(TAG, "setReaderMode: uid=" + callingUid + ", packageName: "
                    + packageName + ", flags: " + flags + ", annotation: " + annotationStr);
            if (!privilegedCaller
                    && !mForegroundUtils.registerUidToBackgroundCallback(
                            NfcService.this, callingUid)) {
                Log.e(TAG, "setReaderMode: Caller is not in foreground and is not system process");
                return;
            }
            boolean disablePolling = flags != 0 && getReaderModeTechMask(flags) == 0;
            // Only allow to disable polling for specific callers
            if (disablePolling && !(privilegedCaller && mPollingDisableAllowed)) {
                Log.e(TAG, "setReaderMode: called with invalid flag parameter.");
                return;
            }
            if (extras != null
                    && extras.containsKey(NfcAdapter.EXTRA_READER_TECH_A_POLLING_LOOP_ANNOTATION)
                    && !isReaderModeAnnotationSupported()) {
                Log.e(TAG, "setReaderMode() called with annotation on an unsupported device.");
                return;
            }
            synchronized (NfcService.this) {
                if (!isNfcEnabled() && !privilegedCaller) {
                    Log.e(TAG, "setReaderMode: called while NFC is not enabled.");
                    return;
                }
                if (flags != 0) {
                    try {
                        if (disablePolling) {
                            ReaderModeDeathRecipient pollingDisableDeathRecipient =
                                    new ReaderModeDeathRecipient();
                            binder.linkToDeath(pollingDisableDeathRecipient, 0);
                            mPollingDisableDeathRecipients.put(
                                    callingPid, pollingDisableDeathRecipient);
                        } else {
                            if (mPollingDisableDeathRecipients.size() != 0) {
                                Log.e(TAG,
                                        "setReaderMode: active polling is forced to disable now");
                                return;
                            }
                            binder.linkToDeath(mReaderModeDeathRecipient, 0);
                        }
                        if (mPollDelayed) {
                            mHandler.removeMessages(MSG_DELAY_POLLING);
                            mPollDelayCount = 0;
                            mReadErrorCount = 0;
                            mPollDelayed = false;
                            mDeviceHost.startStopPolling(true);
                            if (DBG) Log.d(TAG, "setReaderMode: polling is started");
                        }
                        // listenTech is different from the default value, the stored listenTech will be included.
                        // When using setReaderMode, change listenTech to default & restore to previous value.
                        if (isNfcEnabled()) {
                            clearListenPollTech(disablePolling);
                        }
                        updateReaderModeParams(callback, flags, extras, binder, callingUid);
                    } catch (RemoteException e) {
                        Log.e(TAG, "setReaderMode: Remote binder has already died");
                        return;
                    }
                } else {
                    try {
                        ReaderModeDeathRecipient pollingDisableDeathRecipient =
                                mPollingDisableDeathRecipients.get(callingPid);
                        mPollingDisableDeathRecipients.remove(callingPid);

                        if (mPollingDisableDeathRecipients.size() == 0) {
                            mReaderModeParams = null;
                            StopPresenceChecking(false);
                        }

                        if (pollingDisableDeathRecipient != null) {
                            binder.unlinkToDeath(pollingDisableDeathRecipient, 0);
                        } else {
                            binder.unlinkToDeath(mReaderModeDeathRecipient, 0);
                        }
                    } catch (NoSuchElementException e) {
                        Log.e(TAG, "setReaderMode: Reader mode Binder was never registered");
                    } finally {
                        // listenTech is different from the default value, the stored listenTech will be included.
                        // When using enableReaderMode, change listenTech to default & restore to the previous value.
                        if (isNfcEnabled()) {
                            restoreSavedTech();
                        }
                    }
                }
                mNfcEventLog.logEvent(
                        NfcEventProto.EventType.newBuilder()
                                .setReaderModeChange(NfcEventProto.NfcReaderModeChange.newBuilder()
                                        .setAppInfo(NfcEventProto.NfcAppInfo.newBuilder()
                                                .setPackageName(packageName)
                                                .setUid(callingUid)
                                                .build())
                                        .setFlags(flags)
                                        .build())
                                .build());
                if (isNfcEnabled()) {
                    if (mCardEmulationManager != null) {
                        mCardEmulationManager.resetToIdleState();
                    }
                    applyRouting(false);
                }
            }
            checkAndHandleRemovalDetectionMode(true);
        }

        @Override
        public INfcAdapterExtras getNfcAdapterExtrasInterface(String pkg) throws RemoteException {
            // nfc-extras implementation is no longer present in AOSP.
            return null;
        }

        @Override
        public INfcDta getNfcDtaInterface(String pkg) throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            if (mNfcDtaService == null) {
                mNfcDtaService = new NfcDtaService();
            }
            return mNfcDtaService;
        }

        @Override
        public IT4tNdefNfcee getT4tNdefNfceeInterface() throws RemoteException {
            return mT4tNdefNfceeService;
        }

        @Override
        public void addNfcUnlockHandler(INfcUnlockHandler unlockHandler, int[] techList) {
            NfcPermissions.enforceAdminPermissions(mContext);

            int lockscreenPollMask = computeLockscreenPollMask(techList);
            synchronized (NfcService.this) {
                mNfcUnlockManager.addUnlockHandler(unlockHandler, lockscreenPollMask);
            }

            applyRouting(false);
        }

        @Override
        public void removeNfcUnlockHandler(INfcUnlockHandler token) throws RemoteException {
            synchronized (NfcService.this) {
                mNfcUnlockManager.removeUnlockHandler(token.asBinder());
            }

            applyRouting(false);
        }

        @Override
        public boolean deviceSupportsNfcSecure() {
            return mIsSecureNfcCapable;
        }

        @Override
        public NfcAntennaInfo getNfcAntennaInfo() {
            int positionX[] = mContext.getResources().getIntArray(
                    R.array.antenna_x);
            int positionY[] = mContext.getResources().getIntArray(
                    R.array.antenna_y);
            int width = mContext.getResources().getInteger(R.integer.device_width);
            int height = mContext.getResources().getInteger(R.integer.device_height);
            boolean isFoldable = mContext.getResources().getBoolean(R.bool.device_foldable);

            // If overlays are not set, try reading properties.
            if (positionX.length == 0 || positionY.length == 0) {
                positionX = NfcProperties.info_antpos_X().stream()
                        .mapToInt(Integer::intValue)
                        .toArray();
                positionY = NfcProperties.info_antpos_Y().stream()
                        .mapToInt(Integer::intValue)
                        .toArray();
                width = NfcProperties.info_antpos_device_width().orElse(0);
                height = NfcProperties.info_antpos_device_height().orElse(0);
                isFoldable = NfcProperties.info_antpos_device_foldable().orElse(false);
            }
            if (positionX.length != positionY.length) {
                return null;
            }
            List<AvailableNfcAntenna> availableNfcAntennas = new ArrayList<>();
            for (int i = 0; i < positionX.length; i++) {
                if (positionX[i] >= width || positionY[i] >= height) {
                    return null;
                }
                availableNfcAntennas.add(new AvailableNfcAntenna(positionX[i], positionY[i]));
            }
            return new NfcAntennaInfo(
                    width,
                    height,
                    isFoldable,
                    availableNfcAntennas);
        }

        @Override
        public boolean setWlcEnabled(boolean enable) {
            if (!mIsWlcCapable) {
                return false;
            }
            NfcPermissions.enforceAdminPermissions(mContext);
            // enable or disable WLC
            if (DBG) Log.d(TAG, "setWlcEnabled: " + enable);
            synchronized (NfcService.this) {
                // check whether NFC is enabled
                if (!isNfcEnabled()) {
                    return false;
                }
                mPrefsEditor.putBoolean(PREF_NFC_CHARGING_ON, enable);
                mPrefsEditor.apply();
                mIsWlcEnabled = enable;
                mBackupManager.dataChanged();
            }
            if (android.nfc.Flags.nfcPersistLog()) {
                mNfcEventLog.logEvent(
                        NfcEventProto.EventType.newBuilder()
                                .setWlcStateChange(
                                        NfcEventProto.NfcWlcStateChange.newBuilder()
                                                .setEnable(enable)
                                                .build())
                                .build());
            }
            return true;
        }

        @Override
        public boolean isWlcEnabled() throws RemoteException {
            if (!mIsWlcCapable) {
                return false;
            }
            // check whether WLC is enabled or disabled
            synchronized (NfcService.this) {
                return mIsWlcEnabled;
            }
        }

        @Override
        public WlcListenerDeviceInfo getWlcListenerDeviceInfo() {
            if (!mIsWlcCapable) {
                return null;
            }
            synchronized (NfcService.this) {
                return mWlcListenerDeviceInfo;
            }
        }

        private int computeLockscreenPollMask(int[] techList) {

            Map<Integer, Integer> techCodeToMask = new HashMap<Integer, Integer>();

            techCodeToMask.put(TagTechnology.NFC_A, NFC_POLL_A);
            techCodeToMask.put(TagTechnology.NFC_B, NFC_POLL_B);
            techCodeToMask.put(TagTechnology.NFC_V, NFC_POLL_V);
            techCodeToMask.put(TagTechnology.NFC_F, NFC_POLL_F);
            techCodeToMask.put(TagTechnology.NFC_BARCODE, NFC_POLL_KOVIO);

            int mask = 0;

            for (int i = 0; i < techList.length; i++) {
                if (techCodeToMask.containsKey(techList[i])) {
                    mask |= techCodeToMask.get(techList[i]).intValue();
                }
            }

            return mask;
        }

        private int getReaderModeTechMask(int flags) {
            int techMask = 0;
            if ((flags & NfcAdapter.FLAG_READER_NFC_A) != 0) {
                techMask |= NFC_POLL_A;
            }
            if ((flags & NfcAdapter.FLAG_READER_NFC_B) != 0) {
                techMask |= NFC_POLL_B;
            }
            if ((flags & NfcAdapter.FLAG_READER_NFC_F) != 0) {
                techMask |= NFC_POLL_F;
            }
            if ((flags & NfcAdapter.FLAG_READER_NFC_V) != 0) {
                techMask |= NFC_POLL_V;
            }
            if ((flags & NfcAdapter.FLAG_READER_NFC_BARCODE) != 0) {
                techMask |= NFC_POLL_KOVIO;
            }

            return techMask;
        }

        private void updateReaderModeParams(
                IAppCallback callback, int flags, Bundle extras, IBinder binder, int uid) {
            synchronized (NfcService.this) {
                mReaderModeParams = new ReaderModeParams();
                mReaderModeParams.callback = callback;
                mReaderModeParams.flags = flags;
                mReaderModeParams.presenceCheckDelay = extras != null
                        ? (extras.getInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY,
                                DEFAULT_PRESENCE_CHECK_DELAY))
                        : DEFAULT_PRESENCE_CHECK_DELAY;
                mReaderModeParams.binder = binder;
                mReaderModeParams.uid = uid;
                mReaderModeParams.annotation = extras == null ? null : extras.getByteArray(
                        NfcAdapter.EXTRA_READER_TECH_A_POLLING_LOOP_ANNOTATION);
                mReaderModeParams.extra_annotation = extras == null ? null : extras.getByteArray(
                        NfcAdapter.EXTRA_READER_TECH_A_POLLING_LOOP_ANNOTATION_VENDOR_EXTENSION);
            }
        }

        private int setTagAppPreferenceInternal(int userId, String pkg, boolean allow) {
            if (!isPackageInstalled(pkg, userId)) {
                return NfcAdapter.TAG_INTENT_APP_PREF_RESULT_PACKAGE_NOT_FOUND;
            }
            if (DBG) {
                Log.i(TAG, "setTagAppPreferenceInternal: UserId:" + userId + " pkg:" + pkg + ":"
                        + allow);
            }
            synchronized (NfcService.this) {
                mTagAppPrefList.computeIfAbsent(userId, key -> new HashMap<String, Boolean>())
                        .put(pkg, allow);
            }
            storeTagAppPrefList();
            return NfcAdapter.TAG_INTENT_APP_PREF_RESULT_SUCCESS;
        }

        @Override
        public void setControllerAlwaysOn(int mode) throws RemoteException {
            NfcPermissions.enforceSetControllerAlwaysOnPermissions(mContext);
            if (!mIsAlwaysOnSupported) {
                throw new UnsupportedOperationException("isControllerAlwaysOn not supported");
            }
            if (mode != NfcOemExtension.DISABLE) {
                /* AsyncTask params */
                Integer[] paramIntegers = {TASK_ENABLE_ALWAYS_ON, mode};
                new EnableDisableTask().execute(paramIntegers);
            } else {
                new EnableDisableTask().execute(TASK_DISABLE_ALWAYS_ON);
            }
        }

        @Override
        public boolean isControllerAlwaysOn() throws RemoteException {
            NfcPermissions.enforceSetControllerAlwaysOnPermissions(mContext);
            return mIsAlwaysOnSupported && mAlwaysOnState == NfcAdapter.STATE_ON;
        }

        @Override
        public boolean isControllerAlwaysOnSupported() throws RemoteException {
            NfcPermissions.enforceSetControllerAlwaysOnPermissions(mContext);
            return mIsAlwaysOnSupported;
        }

        @Override
        public void registerControllerAlwaysOnListener(
                INfcControllerAlwaysOnListener listener) throws RemoteException {
            NfcPermissions.enforceSetControllerAlwaysOnPermissions(mContext);
            if (!mIsAlwaysOnSupported) return;

            mAlwaysOnListeners.add(listener);
        }

        @Override
        public void unregisterControllerAlwaysOnListener(
                INfcControllerAlwaysOnListener listener) throws RemoteException {
            NfcPermissions.enforceSetControllerAlwaysOnPermissions(mContext);
            if (!mIsAlwaysOnSupported) return;

            mAlwaysOnListeners.remove(listener);
        }

        @Override
        public boolean isTagIntentAppPreferenceSupported() throws RemoteException {
            return mIsTagAppPrefSupported;
        }

        @Override
        public Map getTagIntentAppPreferenceForUser(int userId) throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            if (!mIsTagAppPrefSupported) throw new UnsupportedOperationException();
            synchronized (NfcService.this) {
                return mTagAppPrefList.getOrDefault(userId, new HashMap<>());
            }
        }

        @Override
        public int setTagIntentAppPreferenceForUser(int userId,
                String pkg, boolean allow) throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            if (!mIsTagAppPrefSupported) throw new UnsupportedOperationException();
            return setTagAppPreferenceInternal(userId, pkg, allow);
        }

        @Override
        public boolean isTagIntentAllowed(String pkg, int userId) throws RemoteException {
            if (!android.nfc.Flags.nfcCheckTagIntentPreference()) {
                return true;
            }
            if (!mIsTagAppPrefSupported) {
                return true;
            }
            HashMap<String, Boolean> map;
            synchronized (NfcService.this) {
                map = mTagAppPrefList.getOrDefault(userId, new HashMap<>());
            }
            return map.getOrDefault(pkg, true);
        }

        public boolean enableReaderOption(boolean enable, String pkg) {
            Log.d(TAG, "enableReaderOption: enabled = " + enable + " calling uid = "
                    + Binder.getCallingUid());
            if (!mReaderOptionCapable) return false;
            NfcPermissions.enforceAdminPermissions(mContext);
            synchronized (NfcService.this) {
                mPrefsEditor.putBoolean(PREF_NFC_READER_OPTION_ON, enable);
                mPrefsEditor.apply();
                mIsReaderOptionEnabled = enable;
                mBackupManager.dataChanged();
                if (isNfcEnabled()) {
                    setDiscoveryTech(getNfcPollTech(), getNfcListenTech());
                }
            }
            applyRouting(true);
            if (mNfcOemExtensionCallback != null) {
                try {
                    mNfcOemExtensionCallback.onReaderOptionChanged(enable);
                } catch (RemoteException e) {
                    Log.e(TAG,
                            "enableReaderOption: onReaderOptionChanged failed e = " + e.toString());
                }
            }

            if (android.nfc.Flags.nfcPersistLog()) {
                mNfcEventLog.logEvent(
                        NfcEventProto.EventType.newBuilder()
                                .setReaderOptionChange(
                                        NfcEventProto.NfcReaderOptionChange.newBuilder()
                                                .setEnable(enable)
                                                .setAppInfo(
                                                        NfcEventProto.NfcAppInfo.newBuilder()
                                                    .setPackageName(pkg)
                                                    .setUid(Binder.getCallingUid())
                                                    .build())
                                                .build())
                                .build());
            }
            return true;
        }

        @Override
        public boolean isReaderOptionSupported() {
            return mReaderOptionCapable;
        }

        @Override
        public boolean isReaderOptionEnabled() {
            return mIsReaderOptionEnabled;
        }

        @Override
        public void registerWlcStateListener(
                INfcWlcStateListener listener) throws RemoteException {
            if (!mIsWlcCapable) {
                return;
            }
            NfcPermissions.enforceAdminPermissions(mContext);

            mWlcStateListener.add(listener);
        }

        @Override
        public void unregisterWlcStateListener(
                INfcWlcStateListener listener) throws RemoteException {
            if (!mIsWlcCapable) {
                return;
            }
            NfcPermissions.enforceAdminPermissions(mContext);

            mWlcStateListener.remove(listener);
        }

        @Override
        public void notifyPollingLoop(PollingFrame frame) {
            try {
                byte[] data;
                int type = frame.getType();
                int gain = frame.getVendorSpecificGain();
                byte[] frame_data = frame.getData();

                long timestamp = frame.getTimestamp();
                HexFormat format = HexFormat.ofDelimiter(" ");
                String timestampBytes = format.formatHex(new byte[] {
                        (byte) (timestamp >>> 24),
                        (byte) (timestamp >>> 16),
                        (byte) (timestamp >>> 8),
                        (byte) timestamp });
                int frame_data_length = frame_data == null ? 0 : frame_data.length;
                String frame_data_str =
                        frame_data_length == 0 ? "" : " " + format.formatHex(frame_data);
                String type_str = "FF";
                switch (type) {
                    case PollingFrame.POLLING_LOOP_TYPE_ON:
                        type_str = "00";
                        data = new byte[] { 0x01 };
                        break;
                    case PollingFrame.POLLING_LOOP_TYPE_OFF:
                        type_str = "00";
                        data = new byte[] { 0x00 };
                        break;
                    case PollingFrame.POLLING_LOOP_TYPE_A:
                        type_str = "01";
                        break;
                    case PollingFrame.POLLING_LOOP_TYPE_B:
                        type_str = "02";
                        break;
                    case PollingFrame.POLLING_LOOP_TYPE_F:
                        type_str = "03";
                        break;
                    case PollingFrame.POLLING_LOOP_TYPE_UNKNOWN:
                        type_str = "07";
                        break;
                }
                data = format.parseHex("6f 0C " + String.format("%02x", 9 + frame_data_length)
                        + " 03 " + type_str
                        + " 00 " + String.format("%02x", 5 + frame_data_length) + " "
                        + timestampBytes + " " + String.format("%02x", gain) + frame_data_str);
                ((NativeNfcManager) mDeviceHost).injectNtf(data);
            } catch (Exception ex) {
                Log.e(TAG, "notifyPollingLoop: error when notifying polling loop", ex);
            }
        }

        @Override
        public void notifyTestHceData(int technology, byte[] data) {
            onHostCardEmulationData(technology, data);
        }

        @Override
        public void notifyHceDeactivated() {
            try {
                mCardEmulationManager.onHostCardEmulationDeactivated(1);
            } catch (Exception ex) {
                Log.e(TAG, "notifyHceDeactivated: e=", ex);
            }
        }

        @Override
        public int handleShellCommand(@NonNull ParcelFileDescriptor in,
                @NonNull ParcelFileDescriptor out, @NonNull ParcelFileDescriptor err,
                @NonNull String[] args) {

            NfcShellCommand shellCommand = new NfcShellCommand(NfcService.this, mContext);
            return shellCommand.exec(this, in.getFileDescriptor(), out.getFileDescriptor(),
                    err.getFileDescriptor(), args);
        }

        private static boolean isPowerSavingModeCmd(int gid, int oid, byte[] payload) {
            return gid == NCI_GID_PROP && oid == NCI_MSG_PROP_ANDROID && payload.length > 0
                    && payload[0] == NCI_MSG_PROP_ANDROID_POWER_SAVING;
        }

        private static boolean isQueryPowerSavingStatusCmd(int gid, int oid, byte[] payload) {
          return gid == NCI_GID_PROP && oid == NCI_MSG_PROP_ANDROID && payload.length > 0
                    && payload[0] == NCI_PROP_ANDROID_QUERY_POWER_SAVING_STATUS_CMD;
        }

        @Override
        public int sendVendorNciMessage(int mt, int gid, int oid, byte[] payload)
                throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            if (!isNfcEnabled() && !isControllerAlwaysOn()) {
                Log.e(TAG, "sendVendorNciMessage: Nfc is not enabled");
                return NCI_STATUS_FAILED;
            }

            FutureTask<Integer> sendVendorCmdTask = new FutureTask<>(
                () -> { synchronized (NfcService.this) {
                            if (isPowerSavingModeCmd(gid, oid, payload)) {
                                try {
                                    NfcService.this.setPowerSavingModeInternal(payload[1] == 0x01);
                                } catch (Exception e) {
                                    Log.e(TAG,
                                            "sendVendorNciMessage: "
                                            + "Failed to set power saving mode "
                                            + e);
                                    return NCI_STATUS_FAILED;
                                }
                                return NCI_STATUS_OK;
                            } else if (isQueryPowerSavingStatusCmd(gid, oid, payload)) {
                                byte[] responsePayload = new byte[] {
                                        (byte) NCI_PROP_ANDROID_QUERY_POWER_SAVING_STATUS_CMD,
                                        0x00,
                                        isPowerSavingModeEnabled() ? (byte) 0x01 : (byte) 0x00
                                };
                                NfcVendorNciResponse response = new NfcVendorNciResponse(
                                        (byte) NCI_STATUS_OK, NCI_GID_PROP, NCI_MSG_PROP_ANDROID,
                                        responsePayload);
                                if (response.status == NCI_STATUS_OK) {
                                    mHandler.post(() -> mNfcAdapter.sendVendorNciResponse(
                                            response.gid, response.oid, response.payload));
                                }
                                return Integer.valueOf(response.status);
                            } else {
                                NfcVendorNciResponse response =
                                        mDeviceHost.sendRawVendorCmd(mt, gid, oid, payload);
                                if (response.status == NCI_STATUS_OK) {
                                    mHandler.post(() -> mNfcAdapter.sendVendorNciResponse(
                                            response.gid, response.oid, response.payload));
                                }
                                return Integer.valueOf(response.status);
                            }
                }});
            int status = NCI_STATUS_FAILED;
            try {
                status = runTaskOnSingleThreadExecutor(sendVendorCmdTask,
                        SEND_VENDOR_CMD_TIMEOUT_MS);
            } catch (TimeoutException e) {
                Log.e(TAG, "sendVendorNciMessage: Failed , status : TIMEOUT", e);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
            return status;
        }

        @Override
        public void registerVendorExtensionCallback(INfcVendorNciCallback callbacks)
                throws RemoteException {
            if (DBG) Log.i(TAG, "registerVendorExtensionCallback");
            NfcPermissions.enforceAdminPermissions(mContext);
            synchronized (mNfcVendorNciCallbackLock) {
                mNfcVendorNciCallBack = callbacks;
                mNfcVendorNciCallBack.asBinder().linkToDeath(mNfcVendorNciCbDeathRecipient, 0);
            }
            mDeviceHost.enableVendorNciNotifications(true);
        }

        @Override
        public void unregisterVendorExtensionCallback(INfcVendorNciCallback callbacks)
                throws RemoteException {
            if (DBG) Log.i(TAG, "unregisterVendorExtensionCallback");
            NfcPermissions.enforceAdminPermissions(mContext);
            synchronized (mNfcVendorNciCallbackLock) {
                if (mNfcVendorNciCallBack == null) {
                    return;
                }
                mNfcVendorNciCallBack.asBinder().unlinkToDeath(mNfcVendorNciCbDeathRecipient, 0);
                mNfcVendorNciCallBack = null;
            }
            mDeviceHost.enableVendorNciNotifications(false);
        }

        @Override
        public void registerOemExtensionCallback(INfcOemExtensionCallback callbacks)
                throws RemoteException {
            if (DBG) Log.i(TAG, "registerOemExtensionCallback");
            NfcPermissions.enforceAdminPermissions(mContext);
            synchronized (mOemExtensionCallbackLock) {
                mNfcOemExtensionCallback = callbacks;
                mNfcOemExtensionCallback.asBinder().linkToDeath(mOemExtensionCbDeathRecipient, 0);
            }
            updateNfCState();
            if (mCardEmulationManager != null) {
                mCardEmulationManager.setOemExtension(mNfcOemExtensionCallback);
            }
            if (mNfcDispatcher != null) {
                mNfcDispatcher.setOemExtension(mNfcOemExtensionCallback);
            }
        }

        @Override
        public void unregisterOemExtensionCallback(INfcOemExtensionCallback callbacks)
                throws RemoteException {
            if (DBG) Log.i(TAG, "unregisterOemExtensionCallback");
            NfcPermissions.enforceAdminPermissions(mContext);
            synchronized (mOemExtensionCallbackLock) {
                if (mNfcOemExtensionCallback == null) return;
                mNfcOemExtensionCallback.asBinder().unlinkToDeath(mOemExtensionCbDeathRecipient, 0);
                mNfcOemExtensionCallback = null;
            }
            if (mCardEmulationManager != null) {
                mCardEmulationManager.setOemExtension(mNfcOemExtensionCallback);
            }
            if (mNfcDispatcher != null) {
                mNfcDispatcher.setOemExtension(mNfcOemExtensionCallback);
            }
        }

        @Override
        public void registerGestureExchangeCallback(IReaderCallback callback)
                throws RemoteException {
            synchronized (NfcService.this) {
                if (DBG) Log.i(TAG, "registerGestureExchangeCallback");
                NfcPermissions.enforceGestureExchangePermissions(mContext);
                mNfcGestureExchangeCallback = callback;
            }
        }

        @Override
        public void unregisterGestureExchangeCallback(IReaderCallback callback)
                throws RemoteException {
            synchronized (NfcService.this) {
                if (DBG) Log.i(TAG, "unregisterGestureExchangeCallback");
                NfcPermissions.enforceGestureExchangePermissions(mContext);
                mNfcGestureExchangeCallback = null;
            }
        }

        @Override
        public String getGestureExchangeAid() throws RemoteException {
            return GESTURE_EXCHAGE_AID;
        }

        @Override
        public Map<String, Integer> fetchActiveNfceeList() throws RemoteException {
            Map<String, Integer> map = new HashMap<String, Integer>();
            if (isNfcEnabled()) {
                map = mDeviceHost.dofetchActiveNfceeList();
            }
            return map;
        }

        @Override
        public void clearPreference() throws RemoteException {
            if (DBG) Log.i(TAG, "clearPreference");
            NfcPermissions.enforceAdminPermissions(mContext);
            if (android.nfc.Flags.nfcPersistLog()) {
                mNfcEventLog.logEvent(NfcEventProto.EventType.newBuilder()
                                .setClearPreference(
                                        NfcEventProto.NfcClearPreference.newBuilder()
                                                .build())
                                .build());
            }
            mPrefsEditor.clear();
            if (isNfcUserChangeRestricted()) {
                mPrefsEditor.putBoolean(PREF_NFC_ON, getNfcOnSetting());
            }
            mPrefsEditor.putBoolean(
                PREF_NFC_READER_OPTION_ON, mDeviceConfigFacade.getDefaultReaderOption());
            mPrefsEditor.commit();
        }

        @Override
        public void setScreenState() throws RemoteException {
            if (DBG) Log.i(TAG, "setScreenState");
            NfcPermissions.enforceAdminPermissions(mContext);
            applyScreenState(mScreenStateHelper.checkScreenState(mCheckDisplayStateForScreenState));
        }

        @Override
        public void checkFirmware() throws RemoteException {
            if (DBG) Log.i(TAG, "checkFirmware");
            NfcPermissions.enforceAdminPermissions(mContext);

            if (isNfcEnabled()) {
                if (DBG) Log.i(TAG, "Check firmware by restarting Nfc stack");
                restartStack();
                return;
            }
            FutureTask<Integer> checkFirmwareTask =
                new FutureTask<>(() -> {
                    if (DBG) Log.i(TAG, "Nfc is disabled, checking Firmware");
                    mDeviceHost.checkFirmware();
                    return 0;
                });
            try {
                runTaskOnSingleThreadExecutor(
                    checkFirmwareTask, CHECK_FIRMWARE_TIMEOUT_MS);
            } catch (TimeoutException e) {
                Log.e(TAG, "checkFirmware: failed, status : TIMEOUT", e);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void triggerInitialization() throws RemoteException {
            if (DBG) Log.i(TAG, "triggerInitialization");
            NfcPermissions.enforceAdminPermissions(mContext);
            new EnableDisableTask().execute(TASK_BOOT);
        }

        @Override
        public boolean getSettingStatus() throws RemoteException {
            if (DBG) Log.i(TAG, "getSettingStatus");
            NfcPermissions.enforceAdminPermissions(mContext);
            return getNfcOnSetting();
        }

        @Override
        public boolean isTagPresent() throws RemoteException {
            if (DBG) Log.i(TAG, "isTagPresent");
            NfcPermissions.enforceAdminPermissions(mContext);
            return NfcService.this.isTagPresent();
        }

        @Override
        public List<Entry> getRoutingTableEntryList() throws RemoteException {
            if (DBG) Log.i(TAG, "getRoutingTableEntry");
            NfcPermissions.enforceAdminPermissions(mContext);
            return mRoutingTableParser.getRoutingTableEntryList(mDeviceHost);
        }

        @Override
        public List<RfDiscoverConfig> getRfDiscoverConfigurations() throws RemoteException {
            if (DBG) Log.i(TAG, "getRfDiscoverConfigurations");
            NfcPermissions.enforceAdminPermissions(mContext);
            return mRfDiscoverCmdParser.getRfDiscoverConfigurations(mDeviceHost);
        }

        @Override
        public void indicateDataMigration(boolean inProgress, String pkg) throws RemoteException {
            mNfcPermissions.checkPackage(Binder.getCallingUid(), pkg);
            if (DBG) Log.i(TAG, "indicateDataMigration: inProgress: " + inProgress);
            NfcPermissions.enforceAdminPermissions(mContext);
            mNfcEventLog.logEvent(
                    NfcEventProto.EventType.newBuilder()
                            .setDataMigrationInProgress(NfcEventProto.NfcDataMigrationInProgress
                                    .newBuilder()
                                    .setAppInfo(NfcEventProto.NfcAppInfo.newBuilder()
                                            .setPackageName(pkg)
                                            .setUid(Binder.getCallingUid())
                                            .build())
                                    .setInProgress(inProgress)
                                    .build())
                            .build());
        }

        @Override
        public int commitRouting() throws RemoteException {
            if (isNfcDisabledOrDisabling()) {
                Log.d(TAG, "commitRouting: Skip commit routing when NFCC is off "
                        + "or turning off");
                return STATUS_UNKNOWN_ERROR;
            }
            if (DBG) Log.i(TAG, "commitRouting");
            NfcPermissions.enforceAdminPermissions(mContext);

            @NfcOemExtension.StatusCode int status = mDeviceHost.commitRouting();
            if (mCardEmulationManager.onRoutingChangeCompleted(status)) {
                return status;
            } else {
                return NfcOemExtension.STATUS_UNKNOWN_ERROR;
            }
        }

        @Override
        public long getMaxPausePollingTimeoutMs() {
            if (DBG) Log.i(TAG, "getMaxPausePollingTimeoutMs");
            NfcPermissions.enforceAdminPermissions(mContext);
            int timeoutOverlay = mContext.getResources()
                    .getInteger(R.integer.max_pause_polling_time_out_ms);
            return timeoutOverlay > 0 ? (long) timeoutOverlay : MAX_POLLING_PAUSE_TIMEOUT;
        }

        @Override
        public int emulateNfcATag(boolean setConfig, byte bitFrameSdd, byte platformConfig,
                byte selInfo, byte[] nfcid1, byte rats, byte[] histBytes) {
            Log.i(TAG, "emulateNfcACard: setConfig:" + setConfig);
            NfcPermissions.enforceAdminPermissions(mContext);
            if (!isNfcEnabled()) {
                Log.e(TAG, "emulateNfcACard: NFC is not enabled, ignore");
                return NfcOemExtension.EMULATE_NFC_A_TAG_STATUS_FAILED_NFC_NOT_ENABLED;
            }
            byte[] param = new byte[1];
            synchronized (NfcService.this) {
                // stop discovery
                mDeviceHost.disableDiscovery();

                // Set parameters
                if (setConfig) {
                    // Indicate custom config
                    param[0] = 0x00;
                    mDeviceHost.setNciConfig(0x85, param, param.length, false);

                    // LA_BIT_FRAME_SDD
                    param[0] = bitFrameSdd;
                    mDeviceHost.setNciConfig(0x30, param, param.length, true);

                    // LA_PLATFORM_CONFIG
                    param[0] = platformConfig;
                    mDeviceHost.setNciConfig(0x31, param, param.length, true);

                    // LA_SEL_INFO
                    param[0] = selInfo;
                    mDeviceHost.setNciConfig(0x32, param, param.length, true);

                    // LA_NFCID1
                    mDeviceHost.setNciConfig(0x33, nfcid1, nfcid1.length, true);

                    // LI_A_RATS_TB1
                    param[0] = rats;
                    mDeviceHost.setNciConfig(0x58, param, param.length, true);

                    // LI_A_HIST_BY
                    if (histBytes != null) {
                        mDeviceHost.setNciConfig(0x59, histBytes, histBytes.length, true);
                    }
                } else {
                    // Reset custom config
                    param[0] = 0x01;
                    mDeviceHost.setNciConfig(0x85, param, param.length, false);

                    nfcid1 = new byte[]{};
                    mDeviceHost.setNciConfig(0x33, nfcid1, nfcid1.length, false);

                    // LI_A_RATS_TB1
                    param[0] = 0x0;
                    mDeviceHost.setNciConfig(0x58, param, param.length, false);

                    histBytes = new byte[]{};
                    mDeviceHost.setNciConfig(0x59, histBytes, histBytes.length, false);

                }

                applyRouting(true);
            }

            return NfcOemExtension.EMULATE_NFC_A_TAG_STATUS_OK;
        }

        private void updateNfCState() {
            if (mNfcOemExtensionCallback != null) {
                try {
                    if (DBG) Log.i(TAG, "updateNfCState");
                    mNfcOemExtensionCallback.onCardEmulationActivated(mCardEmulationActivated);
                    mNfcOemExtensionCallback.onRfFieldDetected(mRfFieldActivated);
                    mNfcOemExtensionCallback.onRfDiscoveryStarted(mRfDiscoveryStarted);
                    mNfcOemExtensionCallback.onEeListenActivated(mEeListenActivated);
                    mNfcOemExtensionCallback.onTagConnected(mTagConnected);
                } catch (RemoteException e) {
                    Log.e(TAG, "updateNfCState: Failed to update, e=", e);
                }
            }
        }

        private synchronized void sendVendorNciResponse(int gid, int oid, byte[] payload) {
            if (VDBG) Log.i(TAG, "onVendorNciResponseReceived");
            if (mNfcVendorNciCallBack != null) {
                try {
                    mNfcVendorNciCallBack.onVendorResponseReceived(gid, oid, payload);
                } catch (RemoteException e) {
                    Log.e(TAG, "onVendorNciResponseReceived: failed, e=", e);
                }
            }
        }

        private synchronized void sendVendorNciNotification(int gid, int oid, byte[] payload) {
            if (VDBG) Log.i(TAG, "sendVendorNciNotification");
            if (mNfcVendorNciCallBack != null) {
                try {
                    mNfcVendorNciCallBack.onVendorNotificationReceived(gid, oid, payload);
                } catch (RemoteException e) {
                    Log.e(TAG, "sendVendorNciNotification: failed, e=", e);
                }
            }
        }

        @Override
        public void allowOneTransaction() {
            synchronized (NfcService.this) {
                if (!isNfcEnabled()) {
                    Log.e(TAG, "allowOneTransaction: NFC must be enabled but is: " + mState.get());
                    return;
                }

                int callingUid = Binder.getCallingUid();
                UserHandle callingUser = Binder.getCallingUserHandle();

                if (!NfcInjector.isPrivileged(callingUid)) {
                    NfcPermissions.enforceUserPermissions(mContext);

                    String[] packages = mContext.getPackageManager().getPackagesForUid(callingUid);
                    if (packages == null || packages.length == 0) {
                        throw new SecurityException("Caller with UID " + callingUid
                                + " is not the Wallet Role holder or the "
                                + "preferred payment service.");
                    }

                    boolean isAllowed = false;
                    if (mCardEmulationManager != null) {
                        for (String packageName : packages) {
                            if (mCardEmulationManager.isPreferredServicePackageNameForUser(
                                    packageName, callingUser.getIdentifier())) {
                                isAllowed = true;
                                break;
                            }
                        }
                    }

                    if (!isAllowed) {
                        throw new SecurityException("Caller with UID " + callingUid
                                + " is not the Wallet Role holder or the "
                                + "preferred payment service.");
                    }
                }

                if (mCardEmulationManager.isHostCardEmulationActivated()) {
                    Log.w(TAG, "allowOneTransaction: Cannot allow transaction "
                            + "while one is already active.");
                    return;
                }

                if (mCardEmulationManager != null) {
                    mCardEmulationManager.allowOneTransaction();
                } else {
                    Log.e(TAG, "CardEmulationManager not available.");
                }
            }
        }
    }

    private final IBinder.DeathRecipient mOemExtensionCbDeathRecipient = () -> {
        synchronized (mOemExtensionCallbackLock) {
            Log.w(TAG, "binderDied: OEM extension died");
            mNfcOemExtensionCallback = null;
            if (mCardEmulationManager != null) {
                mCardEmulationManager.setOemExtension(null);
            }
            if (mNfcDispatcher != null) {
                mNfcDispatcher.setOemExtension(null);
            }
        }
        restartStack();
    };

    private final IBinder.DeathRecipient mNfcVendorNciCbDeathRecipient = () -> {
        synchronized (mNfcVendorNciCallbackLock) {
            Log.w(TAG, "binderDied: OEM extension died (mNfcVendorNciCallBack)");
            mNfcVendorNciCallBack = null;
        }
    };

    final class SeServiceDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            synchronized (NfcService.this) {
                Log.i(TAG, "binderDied: SE Service died");
                mSEService = null;
            }
        }
    }

    final class ReaderModeDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            synchronized (NfcService.this) {
                if (mReaderModeParams != null) {
                    mPollingDisableDeathRecipients.values().remove(this);
                    resetReaderModeParams();
                }
            }
        }
    }

    final class DiscoveryTechDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            if (DBG) Log.d(TAG, "binderDied: setDiscoveryTech death recipient");
            synchronized (NfcService.this) {
                if (isNfcEnabled() && mDiscoveryTechParams != null) {
                    mDeviceHost.resetDiscoveryTech();
                    mDiscoveryTechParams = null;
                }
            }
            applyRouting(true);
        }
    }

    final class TagService extends INfcTag.Stub {
        @Override
        public int connect(int nativeHandle, int technology) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            TagEndpoint tag = null;

            if (!isNfcEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (!isReaderOptionEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_DISCONNECT;
            }

            if (!tag.isPresent()) {
                return ErrorCodes.ERROR_DISCONNECT;
            }

            // Note that on most tags, all technologies are behind a single
            // handle. This means that the connect at the lower levels
            // will do nothing, as the tag is already connected to that handle.
            if (tag.connect(technology)) {
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_DISCONNECT;
            }
        }

        @Override
        public int reconnect(int nativeHandle) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            TagEndpoint tag = null;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (!isReaderOptionEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                if (tag.reconnect()) {
                    return ErrorCodes.SUCCESS;
                } else {
                    return ErrorCodes.ERROR_DISCONNECT;
                }
            }
            return ErrorCodes.ERROR_DISCONNECT;
        }

        @Override
        public int[] getTechList(int nativeHandle) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return null;
            }

            if (!isReaderOptionEnabled()) {
                return null;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                return null;
            }

            /* find the tag in the hmap */
            TagEndpoint tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                return tag.getTechList();
            }
            return null;
        }

        @Override
        public boolean isPresent(int nativeHandle) throws RemoteException {
            TagEndpoint tag = null;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return false;
            }

            if (!isReaderOptionEnabled()) {
                return false;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                return false;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag == null) {
                return false;
            }

            return tag.isPresent();
        }

        @Override
        public boolean isNdef(int nativeHandle) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            TagEndpoint tag = null;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return false;
            }

            if (!isReaderOptionEnabled()) {
                return false;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                return false;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            int[] ndefInfo = new int[2];
            if (tag == null) {
                return false;
            }
            return tag.checkNdef(ndefInfo);
        }

        @Override
        public TransceiveResult transceive(int nativeHandle, byte[] data, boolean raw)
                throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            TagEndpoint tag = null;
            byte[] response;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return null;
            }

            if (!isReaderOptionEnabled()) {
                return null;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                // Check if length is within limits
                if (data.length > getMaxTransceiveLength(tag.getConnectedTechnology())) {
                    return new TransceiveResult(TransceiveResult.RESULT_EXCEEDED_LENGTH, null);
                }
                int[] targetLost = new int[1];
                response = tag.transceive(data, raw, targetLost);
                int result;
                if (response != null) {
                    result = TransceiveResult.RESULT_SUCCESS;
                } else if (targetLost[0] == 1) {
                    result = TransceiveResult.RESULT_TAGLOST;
                } else {
                    result = TransceiveResult.RESULT_FAILURE;
                }
                return new TransceiveResult(result, response);
            }
            return null;
        }

        @Override
        public NdefMessage ndefRead(int nativeHandle) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            TagEndpoint tag;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return null;
            }

            if (!isReaderOptionEnabled()) {
                return null;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                byte[] buf = tag.readNdef();
                if (buf == null) {
                    return null;
                }

                /* Create an NdefMessage */
                try {
                    return new NdefMessage(buf);
                } catch (FormatException e) {
                    return null;
                }
            }
            return null;
        }

        @Override
        public int ndefWrite(int nativeHandle, NdefMessage msg) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            TagEndpoint tag;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (!isReaderOptionEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_IO;
            }

            if (msg == null) return ErrorCodes.ERROR_INVALID_PARAM;

            if (tag.writeNdef(msg.toByteArray())) {
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_IO;
            }

        }

        @Override
        public boolean ndefIsWritable(int nativeHandle) throws RemoteException {
            if (checkAndHandleRemovalDetectionMode(true)) {
                return false;
            }
            throw new UnsupportedOperationException();
        }

        @Override
        public int ndefMakeReadOnly(int nativeHandle) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            TagEndpoint tag;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (!isReaderOptionEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_IO;
            }

            if (tag.makeReadOnly()) {
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_IO;
            }
        }

        @Override
        public int formatNdef(int nativeHandle, byte[] key) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            TagEndpoint tag;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (!isReaderOptionEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_IO;
            }

            if (tag.formatNdef(key)) {
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_IO;
            }
        }

        @Override
        public Tag rediscover(int nativeHandle) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            TagEndpoint tag = null;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return null;
            }

            if (!isReaderOptionEnabled()) {
                return null;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                // For now the prime usecase for rediscover() is to be able
                // to access the NDEF technology after formatting without
                // having to remove the tag from the field, or similar
                // to have access to NdefFormatable in case low-level commands
                // were used to remove NDEF. So instead of doing a full stack
                // rediscover (which is poorly supported at the moment anyway),
                // we simply remove these two technologies and detect them
                // again.
                tag.removeTechnology(TagTechnology.NDEF);
                tag.removeTechnology(TagTechnology.NDEF_FORMATABLE);
                tag.findAndReadNdef();
                // Build a new Tag object to return
                try {
                    /* Avoid setting mCookieUpToDate to negative values */
                    mCookieUpToDate = mCookieGenerator.nextLong() >>> 1;
                    Tag newTag = new Tag(tag.getUid(), tag.getTechList(),
                            tag.getTechExtras(), tag.getHandle(), mCookieUpToDate, this);
                    return newTag;
                } catch (Exception e) {
                    Log.e(TAG, "Tag creation exception.", e);
                    return null;
                }
            }
            return null;
        }

        @Override
        public int setTimeout(int tech, int timeout) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);
            boolean success = mDeviceHost.setTimeout(tech, timeout);
            if (success) {
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_INVALID_PARAM;
            }
        }

        @Override
        public int getTimeout(int tech) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            return mDeviceHost.getTimeout(tech);
        }

        @Override
        public void resetTimeouts() throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            mDeviceHost.resetTimeouts();
        }

        @Override
        public boolean canMakeReadOnly(int ndefType) throws RemoteException {
            return mDeviceHost.canMakeReadOnly(ndefType);
        }

        @Override
        public int getMaxTransceiveLength(int tech) throws RemoteException {
            return mDeviceHost.getMaxTransceiveLength(tech);
        }

        @Override
        public boolean getExtendedLengthApdusSupported() throws RemoteException {
            return mDeviceHost.getExtendedLengthApdusSupported();
        }

        @Override
        public boolean isTagUpToDate(long cookie) throws RemoteException {
            if (mCookieUpToDate != -1 && mCookieUpToDate == cookie) {
                if (DBG) {
                    Log.d(TAG, "isTagUpToDate: Tag " + Long.toString(cookie) + " is up to date");
                }
                return true;
            }

            if (DBG) {
                Log.d(TAG, "isTagUpToDate: Tag " + Long.toString(cookie) + " is out of date");
            }
            EventLog.writeEvent(0x534e4554, "199291025", -1,
                    "The obsolete tag was attempted to be accessed");
            return false;
        }
    }

    final class NfcDtaService extends INfcDta.Stub {
        public void enableDta() throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            if (!sIsDtaMode) {
                mDeviceHost.enableDtaMode();
                sIsDtaMode = true;
                Log.d(TAG, "enableDta: DTA Mode is Enabled");
            } else {
                Log.d(TAG, "enableDta: DTA Mode is already Enabled");
            }
        }

        public void disableDta() throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            if (sIsDtaMode) {
                mDeviceHost.disableDtaMode();
                sIsDtaMode = false;
                Log.d(TAG, "disableDta: DTA Mode is Disabled");
            } else {
                Log.d(TAG, "disableDta: DTA Mode is already Disabled");
            }
        }

        public boolean enableServer(String serviceName, int serviceSap, int miu,
                int rwSize,int testCaseId) throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            return false;
        }

        public void disableServer() throws RemoteException {
        }

        public boolean enableClient(String serviceName, int miu, int rwSize,
                int testCaseId) throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            return false;
        }

        public void disableClient() throws RemoteException {
            return;
        }

        public boolean registerMessageService(String msgServiceName)
                throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            if (msgServiceName == null) return false;

            DtaServiceConnector.setMessageService(msgServiceName);
            return true;
        }

    };

    class T4tNdefNfceeService extends IT4tNdefNfcee.Stub {

        @Override
        public int writeData(final int fileId, byte[] data) {
            NfcPermissions.enforceAdminPermissions(mContext);
            if (mDeviceHost.isNdefOperationOngoing()) {
                return T4tNdefNfcee.WRITE_DATA_ERROR_DEVICE_BUSY;
            }
            int status = T4tNdefNfcee.WRITE_DATA_ERROR_INTERNAL;
            try {
                ByteBuffer fileIdInBytes = ByteBuffer.allocate(2);
                fileIdInBytes.putShort((short)fileId);
                status = mDeviceHost.doWriteData(fileIdInBytes.array(), data);
                if (status > 0) status = T4tNdefNfcee.WRITE_DATA_SUCCESS;
            } catch (Exception e) {
                Log.e(TAG, "writeData: e=", e);
            }
            Log.i(TAG, "writeData : " + status);
            return status;
        }

        @Override
        public byte[] readData(final int fileId) {
            NfcPermissions.enforceAdminPermissions(mContext);
            if (mDeviceHost.isNdefOperationOngoing()) {
                throw new IllegalStateException("Device is busy");
            }
            byte[] readData = {};
            ByteBuffer fileIdInBytes = ByteBuffer.allocate(2);
            fileIdInBytes.putShort((short)fileId);
            readData = mDeviceHost.doReadData(fileIdInBytes.array());
            if (readData == null) {
                throw new IllegalStateException("Ndef Nfcee read failed");
            }
            return readData;
        }

        @Override
        public T4tNdefNfceeCcFileInfo readCcfile() {
            NfcPermissions.enforceAdminPermissions(mContext);
            if (mDeviceHost.isNdefOperationOngoing()) {
                throw new IllegalStateException("Device is busy");
            }
            T4tNdefNfceeCcFileInfo ccFileInfo = null;
            byte[] readData = {};

            try {
                readData = mDeviceHost.doReadData(T4T_NFCEE_CC_FILE_ID);
            } catch (Exception e) {
                Log.e(TAG, "readCcfile: Exception e=", e);
                return ccFileInfo;
            }
            if (readData.length >= 15) {
                int cclen =
                        ((Byte.toUnsignedInt(readData[0])) << 8) +
                                (Byte.toUnsignedInt(readData[1]));
                int version = Byte.toUnsignedInt(readData[2]);
                if (version == T4T_NFCEE_MAPPING_VERSION_2_0) {
                    int ndefFileId =
                            ((Byte.toUnsignedInt(readData[9])) << 8) +
                                    Byte.toUnsignedInt(readData[10]);
                    int ndefMaxFileSize =
                            ((Byte.toUnsignedInt(readData[11])) << 8) +
                                    Byte.toUnsignedInt(readData[12]);
                    boolean isReadAllowed = readData[13] == 0;
                    boolean isWriteAllowed = readData[14] == 0;

                    ccFileInfo = new T4tNdefNfceeCcFileInfo(
                            cclen, version, ndefFileId, ndefMaxFileSize, isReadAllowed,
                            isWriteAllowed);
                } else {
                    Log.e(TAG,
                            "readCcfile: Unsupported NDEF mapping version received. "
                                    + "Versions otherthan 2.0 are not supported.");
                    throw new UnsupportedOperationException(
                            "Unsupported NDEF mapping version received");
                }
            } else {
                Log.e(TAG, "readCcfile: Empty data");
            }
            return ccFileInfo;
        }

        @Override
        public int clearNdefData() {
            NfcPermissions.enforceAdminPermissions(mContext);
            if (mDeviceHost.isNdefOperationOngoing()) {
                return T4tNdefNfcee.CLEAR_DATA_FAILED_DEVICE_BUSY;
            }
            boolean isEnabled = (isNfcEnabled()
                    || (((mIsAlwaysOnSupported && mAlwaysOnState == NfcAdapter.STATE_ON))
                    && (mAlwaysOnMode == NfcOemExtension.ENABLE_EE)));
            if (!isEnabled) {
                mDeviceHost.setPartialInitMode(NfcOemExtension.ENABLE_EE);
                mDeviceHost.initialize();
            }
            boolean status  = mDeviceHost.doClearNdefData();
            if (!isEnabled) {
                mDeviceHost.deinitialize();
                mDeviceHost.setPartialInitMode(NfcOemExtension.ENABLE_DEFAULT);
            }
            Log.i(TAG, "clearNdefData: " + status);
            return status
                    ? T4tNdefNfcee.CLEAR_DATA_SUCCESS
                    : T4tNdefNfcee.CLEAR_DATA_FAILED_INTERNAL;
        }

        @Override
        public boolean isNdefOperationOngoing() {
            NfcPermissions.enforceAdminPermissions(mContext);
            boolean status  = mDeviceHost.isNdefOperationOngoing();
            Log.i(TAG, "isNdefOperationOngoing: " + status);
            return status;
        }

        @Override
        public boolean isNdefNfceeEmulationSupported() {
            NfcPermissions.enforceAdminPermissions(mContext);
            boolean status  = mDeviceHost.isNdefNfceeEmulationSupported();
            Log.i(TAG, "isT4tNdefNfceeEmulationSupported: " + status);
            return status;
        }
    }

    boolean isNfcEnabledOrShuttingDown() {
        return (mState.get() == NfcAdapter.STATE_ON
            || mState.get() == NfcAdapter.STATE_TURNING_OFF);
    }

    boolean isNfcDisabledOrDisabling() {
        return (mState.get() == NfcAdapter.STATE_OFF
            || mState.get() == NfcAdapter.STATE_TURNING_OFF);
    }

    boolean isNfcEnabled() {
        return mState.get() == NfcAdapter.STATE_ON;
    }

    boolean isReaderOptionEnabled() {
        synchronized (this) {
            return mIsReaderOptionEnabled || mReaderModeParams != null;
        }
    }

    class WatchDogThread extends Thread {
        final Object mCancelWaiter = new Object();
        final int mTimeout;
        boolean mCanceled = false;

        public WatchDogThread(String threadName, int timeout) {
            super(threadName);
            mTimeout = timeout;
        }

        @Override
        public void run() {
            try {
                synchronized (mCancelWaiter) {
                    mCancelWaiter.wait(mTimeout);
                    if (mCanceled) {
                        return;
                    }
                }
            } catch (InterruptedException e) {
                // Should not happen; fall-through to abort.
                Log.w(TAG, "run: Watchdog thread interrupted");
                interrupt();
            }
            if (mRoutingWakeLock.isHeld()) {
                Log.e(TAG, "run: Watchdog triggered, release lock before aborting");
                mRoutingWakeLock.release();
            }
            Log.e(TAG, "run: Watchdog triggered, aborting");
            NfcStatsLog.write(NfcStatsLog.NFC_STATE_CHANGED,
                    NfcStatsLog.NFC_STATE_CHANGED__STATE__CRASH_RESTART);
            if (android.nfc.Flags.nfcEventListener() && mCardEmulationManager != null) {
                mCardEmulationManager.onInternalErrorReported(
                        CardEmulation.NFC_INTERNAL_ERROR_NFC_CRASH_RESTART);
            }
            storeNativeCrashLogs();
            mDeviceHost.doAbort(getName());
        }

        public synchronized void cancel() {
            synchronized (mCancelWaiter) {
                mCanceled = true;
                mCancelWaiter.notify();
            }
        }
    }

    static byte[] hexStringToBytes(String s) {
        if (s == null || s.length() == 0) return null;
        int len = s.length();
        if (len % 2 != 0) {
            s = '0' + s;
            len++;
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int high = Character.digit(s.charAt(i), 16);
            int low = Character.digit(s.charAt(i + 1), 16);
            if (high == -1 || low == -1) {
                Log.e(TAG, "hexStringToBytes: Invalid hex character found.");
                return null;
            }
            data[i / 2] = (byte) ((high << 4) + low);
        }
        return data;
    }

    private void addDeviceLockedStateListener() {
        try {
            mKeyguard.addDeviceLockedStateListener(
                    mContext.getMainExecutor(), mDeviceLockedStateListener);
        } catch (Exception e) {
            Log.e(TAG, "addDeviceLockedStateListener: e=" + e);
        }
    }

    private void addKeyguardLockedStateListener() {
        try {
            mKeyguard.addKeyguardLockedStateListener(mContext.getMainExecutor(),
                    mIKeyguardLockedStateListener);
        } catch (Exception e) {
            Log.e(TAG, "addKeyguardLockedStateListener: e=" + e);
        }
    }

    /**
     * Receives KeyGuard lock state updates
     */
    private KeyguardLockedStateListener mIKeyguardLockedStateListener =
            new KeyguardLockedStateListener() {
                @Override
                public void onKeyguardLockedStateChanged(boolean isKeyguardLocked) {
                    if (mIsKeyguardLocked == isKeyguardLocked) return;

                    Log.d(TAG, "onKeyguardLockedStateChanged: isKeyguardLocked ="
                            + isKeyguardLocked);
                    mIsKeyguardLocked = isKeyguardLocked;

                    if (mIsWlcCapable && mNfcCharging.NfcChargingOnGoing) {
                        Log.d(TAG, "onKeyguardLockedStateChanged: Charging ongoing, "
                                + "skip screen state update");
                        mPendingPowerStateUpdate = true;
                        return;
                    }

                    if (android.app.Flags.deviceUnlockListener()) {
                        int screenState = mScreenStateHelper.checkScreenState(
                                mCheckDisplayStateForScreenState);
                        // Update screen state when keyguard unlocked/locked
                        sendMessage(NfcService.MSG_APPLY_SCREEN_STATE, screenState);
                    }
                }
    };

    /**
     * Receives Device lock state updates
     */
    private DeviceLockedStateListener mDeviceLockedStateListener =
            new DeviceLockedStateListener() {
        @Override
        public void onDeviceLockedStateChanged(boolean isDeviceLocked) {
            if (!mIsWlcCapable || !mNfcCharging.NfcChargingOnGoing) {
                applyScreenState(mScreenStateHelper.checkScreenState(
                                     mCheckDisplayStateForScreenState));
            }
        }
    };

    private void addThermalStatusListener() {
        try {
            if (mPowerManager != null) {
                mPowerManager.addThermalStatusListener(mContext.getMainExecutor(),
                        mOnThermalStatusChangedListener);
            }
        } catch (Exception e) {
            Log.e(TAG, "addThermalStatusListener: e=" + e);
        }
    }

    /**
     * Receives Thermal state updates
     */
    private OnThermalStatusChangedListener mOnThermalStatusChangedListener =
            new OnThermalStatusChangedListener() {
                @Override
                public void onThermalStatusChanged(int status) {
                    switch (status) {
                        case PowerManager.THERMAL_STATUS_MODERATE:
                            Log.d(TAG, "onThermalStatusChanged: THERMAL_STATUS_MODERATE");
                            break;
                        case PowerManager.THERMAL_STATUS_SEVERE:
                            Log.d(TAG, "onThermalStatusChanged: THERMAL_STATUS_SEVERE");
                            break;
                        case PowerManager.THERMAL_STATUS_CRITICAL:
                            Log.d(TAG, "onThermalStatusChanged: THERMAL_STATUS_CRITICAL");
                            break;
                        default:
                            Log.d(TAG, "onThermalStatusChanged: : " + status);
                            break;
                    }
                }
    };


    /**
     * Read mScreenState and apply NFC-C polling and NFC-EE routing
     */
    void applyRouting(boolean force) {
        if (DBG) {
            Log.d(TAG, "applyRouting");
        }
        synchronized (this) {
            if (!isNfcEnabled()) {
                Log.d(TAG, "applyRouting: skip because nfc is not enabled");
                return;
            }
            if (mNfcOemExtensionCallback != null
                   && receiveOemCallbackResult(ACTION_ON_APPLY_ROUTING)) {
                Log.d(TAG, "applyRouting: skip due to oem callback");
                return;
            }
            refreshTagDispatcherInProvisionMode();
            boolean privilegedCaller = NfcInjector.isPrivileged(Binder.getCallingUid())
                    || NfcPermissions.checkAdminPermissions(mContext);
            if (mPollingPaused && !privilegedCaller) {
                Log.d(TAG, "applyRouting: Not updating discovery parameters, polling paused");
                return;
            }
            // Special case: if we're transitioning to unlocked state while
            // still talking to a tag, postpone re-configuration.
            if (mScreenState == ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED && isTagPresent()) {
                Log.d(TAG, "applyRouting: Not updating discovery parameters, tag connected");
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_RESUME_POLLING),
                        APPLY_ROUTING_RETRY_TIMEOUT_MS);
                return;
            }

            synchronized (mRoutingWatchDogLock) {
                mRoutingWatchDog = new WatchDogThread("applyRouting", ROUTING_WATCHDOG_MS);
                mRoutingWatchDog.start();
            }

            try {
                // Compute new polling parameters
                NfcDiscoveryParameters newParams = computeDiscoveryParameters(mScreenState);
                if (force || !newParams.equals(mCurrentDiscoveryParameters)) {
                    if (newParams.shouldEnableDiscovery()) {
                        boolean shouldRestart = mCurrentDiscoveryParameters.shouldEnableDiscovery();
                        mDeviceHost.enableDiscovery(newParams, shouldRestart);
                    } else {
                        mDeviceHost.disableDiscovery();
                    }
                    mCurrentDiscoveryParameters = newParams;
                } else {
                    Log.d(TAG, "applyRouting: Discovery configuration equal, not updating");
                }
            } finally {
                cancelRoutingWatchDog();
            }
        }
    }

    private NfcDiscoveryParameters computeDiscoveryParameters(int screenState) {
        // Recompute discovery parameters based on screen state
        NfcDiscoveryParameters.Builder paramsBuilder = NfcDiscoveryParameters.newBuilder();
        // Polling
        if (screenState >= NFC_POLLING_MODE && isReaderOptionEnabled()) {
            // Check if reader-mode is enabled
            if (mReaderModeParams != null) {
                int techMask = 0;
                if ((mReaderModeParams.flags & NfcAdapter.FLAG_READER_NFC_A) != 0)
                    techMask |= NFC_POLL_A;
                if ((mReaderModeParams.flags & NfcAdapter.FLAG_READER_NFC_B) != 0)
                    techMask |= NFC_POLL_B;
                if ((mReaderModeParams.flags & NfcAdapter.FLAG_READER_NFC_F) != 0)
                    techMask |= NFC_POLL_F;
                if ((mReaderModeParams.flags & NfcAdapter.FLAG_READER_NFC_V) != 0)
                    techMask |= NFC_POLL_V;
                if ((mReaderModeParams.flags & NfcAdapter.FLAG_READER_NFC_BARCODE) != 0)
                    techMask |= NFC_POLL_KOVIO;

                paramsBuilder.setTechMask(techMask);
                paramsBuilder.setEnableReaderMode(true);
                if (mReaderModeParams.flags != 0 && techMask == 0) {
                    paramsBuilder.setEnableHostRouting(true);
                }
            } else {
                paramsBuilder.setTechMask(NfcDiscoveryParameters.NFC_POLL_DEFAULT);
            }
        } else if (screenState == SCREEN_STATE_ON_LOCKED && mInProvisionMode) {
            paramsBuilder.setTechMask(NfcDiscoveryParameters.NFC_POLL_DEFAULT);
        } else if (screenState == SCREEN_STATE_ON_LOCKED &&
            mNfcUnlockManager.isLockscreenPollingEnabled() && isReaderOptionEnabled()) {
            int techMask = 0;
            if (mNfcUnlockManager.isLockscreenPollingEnabled())
                techMask |= mNfcUnlockManager.getLockscreenPollMask();
            paramsBuilder.setTechMask(techMask);
            paramsBuilder.setEnableLowPowerDiscovery(false);
        }
        if (mReaderModeParams != null && mReaderModeParams.annotation != null) {
            paramsBuilder.setTechAPollingLoopAnnotation(mReaderModeParams.annotation);
        }
        if (mReaderModeParams != null && mReaderModeParams.extra_annotation != null) {
            paramsBuilder.setExtraAnnotation(mReaderModeParams.extra_annotation);
        }
        if (mIsHceCapable) {
            // Host routing is always enabled, provided we aren't in reader mode
            if (mReaderModeParams == null || mReaderModeParams.flags == DISABLE_POLLING_FLAGS) {
                paramsBuilder.setEnableHostRouting(true);
            }
        }

        return paramsBuilder.build();
    }

    private boolean isTagPresent() {
        for (Object object : mObjectMap.values()) {
            if (object instanceof TagEndpoint) {
                return ((TagEndpoint) object).isPresent();
            }
        }
        return false;
    }

    private void refreshTagDispatcherInProvisionMode() {
        if (mInProvisionMode) {
            mInProvisionMode = mNfcInjector.isInProvisionMode();
            if (!mInProvisionMode) {
                mNfcDispatcher.disableProvisioningMode();
            }
        }
    }

    private boolean isPresenceCheckStopped() {
        boolean isStopped = false;
        synchronized (mObjectMapLock) {
            Object[] objectValues = mObjectMap.values().toArray();
            for (Object object : objectValues) {
                if (object instanceof TagEndpoint) {
                    if (((TagEndpoint) object).isPresenceCheckStopped()) {
                        isStopped = true;
                    }
                }
            }
        }
        return isStopped;
    }

    /**
     * Stops the Presence check thread without calling
     * Disconnect API and onTagDisconnect callback
     */
    private void prepareForRemovalDetectionMode() {
        synchronized (mObjectMapLock) {
            Object[] objectValues = mObjectMap.values().toArray();
            for (Object object : objectValues) {
                if (object instanceof TagEndpoint) {
                    ((TagEndpoint) object).prepareForRemovalDetectionMode();
                }
            }
        }
    }

    private void StopPresenceChecking(boolean isShutdown) {
        Object[] objectValues = mObjectMap.values().toArray();
        if (!ArrayUtils.isEmpty(objectValues)) {
            // If there are some tags connected, we need to execute the callback to indicate
            // the tag is being forcibly disconnected.
            executeOemOnTagConnectedCallback(false);
            executeReaderModeOnTagLostCallback();
        }
        for (Object object : objectValues) {
            if (object instanceof TagEndpoint) {
                TagEndpoint tag = (TagEndpoint) object;
                ((TagEndpoint) object).stopPresenceChecking(isShutdown);
            }
        }
    }

    /**
     * Disconnect any target if present
     */
    void maybeDisconnectTarget() {
        if (!isNfcEnabledOrShuttingDown()) {
            return;
        }
        clearAppInactivityDetectionContext();
        Object[] objectsToDisconnect;
        synchronized (mObjectMapLock) {
            Object[] objectValues = mObjectMap.values().toArray();
            // Copy the array before we clear mObjectMap,
            // just in case the HashMap values are backed by the same array
            objectsToDisconnect = Arrays.copyOf(objectValues, objectValues.length);
            mObjectMap.clear();
            mTagObjectMap.clear();
        }
        for (Object o : objectsToDisconnect) {
            if (DBG) Log.d(TAG, "maybeDisconnectTarget: " + o.getClass().getName());
            if (o instanceof TagEndpoint) {
                // Disconnect from tags
                TagEndpoint tag = (TagEndpoint) o;
                tag.disconnect();
            }
        }
    }

    Object findObject(int key) {
        synchronized (mObjectMapLock) {
            Object device = mObjectMap.get(key);
            if (device == null) {
                Log.w(TAG, "maybeDisconnectTarget: Handle not found");
            }
            return device;
        }
    }

    Object findAndRemoveObject(int handle) {
        synchronized (mObjectMapLock) {
            Object device = mObjectMap.get(handle);
            if (device == null) {
                Log.w(TAG, "findAndRemoveObject: Handle not found");
            } else {
                mObjectMap.remove(handle);
                mTagObjectMap.remove(handle);
            }
            return device;
        }
    }

    void registerTagObject(TagEndpoint tag) {
        synchronized (mObjectMapLock) {
            mObjectMap.put(tag.getHandle(), tag);

        }
    }

    void unregisterObject(int handle) {
        synchronized (mObjectMapLock) {
            mObjectMap.remove(handle);
        }
    }

    void registerTag(Tag tag) {
        synchronized (mObjectMapLock) {
            mTagObjectMap.put(tag.getServiceHandle(), tag);
        }
    }

    void unregisterTag(int handle) {
        synchronized (mObjectMapLock) {
            mTagObjectMap.remove(handle);
        }
    }

    public int getAidRoutingTableSize ()
    {
        int aidTableSize = 0x00;
        aidTableSize = mDeviceHost.getAidTableSize();
        return aidTableSize;
    }

    public void sendMockNdefTag(NdefMessage msg) {
        sendMessage(MSG_MOCK_NDEF, msg);
    }

    public void routeAids(String aid, int route, int aidInfo, int power) {
        Message msg = mHandler.obtainMessage();
        msg.what = MSG_ROUTE_AID;
        msg.arg1 = route;
        msg.obj = aid;
        msg.arg2 = aidInfo;

        Bundle aidPowerState = new Bundle();
        aidPowerState.putInt(MSG_ROUTE_AID_PARAM_TAG, power);
        msg.setData(aidPowerState);

        mHandler.sendMessage(msg);
    }

    public void unrouteAids(String aid) {
        sendMessage(MSG_UNROUTE_AID, aid);
    }

    public int getNciVersion() {
        return mDeviceHost.getNciVersion();
    }

    private byte[] getT3tIdentifierBytes(String systemCode, String nfcId2, String t3tPmm) {
        ByteBuffer buffer = ByteBuffer.allocate(2 + 8 + 8); /* systemcode + nfcid2 + t3tpmm */
        buffer.put(hexStringToBytes(systemCode));
        buffer.put(hexStringToBytes(nfcId2));
        buffer.put(hexStringToBytes(t3tPmm));
        byte[] t3tIdBytes = new byte[buffer.position()];
        buffer.position(0);
        buffer.get(t3tIdBytes);

        return t3tIdBytes;
    }

    public void registerT3tIdentifier(String systemCode, String nfcId2, String t3tPmm) {
        Log.d(TAG, "registerT3tIdentifier");

        byte[] t3tIdentifier = getT3tIdentifierBytes(systemCode, nfcId2, t3tPmm);
        sendMessage(MSG_REGISTER_T3T_IDENTIFIER, t3tIdentifier);
    }

    public void deregisterT3tIdentifier(String systemCode, String nfcId2, String t3tPmm) {
        Log.d(TAG, "deregisterT3tIdentifier");

        byte[] t3tIdentifier = getT3tIdentifierBytes(systemCode, nfcId2, t3tPmm);
        sendMessage(MSG_DEREGISTER_T3T_IDENTIFIER, t3tIdentifier);
    }

    public void clearT3tIdentifiersCache() {
        if (NativeNfcManager.getInstance().isT3TIdentifierRegistered()) {
            Log.d(TAG, "clearT3tIdentifiersCache");
            mDeviceHost.disableDiscovery();

            mDeviceHost.clearT3tIdentifiersCache();
            NfcDiscoveryParameters params = computeDiscoveryParameters(mScreenState);
            boolean shouldRestart = mCurrentDiscoveryParameters.shouldEnableDiscovery();
            mDeviceHost.enableDiscovery(params, shouldRestart);
        }
    }

    public int getLfT3tMax() {
        return mDeviceHost.getLfT3tMax();
    }

    public int commitRouting(boolean isOverrideOrRecover) {
        if (!isOverrideOrRecover) {
            // Clear the Handler queue, only last commit_msg is relevant
            mHandler.removeMessages(MSG_COMMIT_ROUTING);
            mHandler.sendEmptyMessage(MSG_COMMIT_ROUTING);
            return STATUS_OK;
        }
        if (mCardEmulationManager.onRoutingChangeStarted()) {
            mHandler.sendEmptyMessage(MSG_COMMIT_ROUTING);
        } else {
            Log.d(TAG, "commitRouting: already in progress, ignoring...");
        }
        return STATUS_OK;
    }

    public boolean sendScreenMessageAfterNfcCharging() {
        if (DBG) Log.d(TAG, "sendScreenMessageAfterNfcCharging");

        if (mPendingPowerStateUpdate == true) {
            int screenState = mScreenStateHelper.checkScreenState(mCheckDisplayStateForScreenState);
            if (DBG) {
                Log.d(TAG, "sendScreenMessageAfterNfcCharging: applying postponed screen state "
                        + screenState);
            }
            sendMessage(NfcService.MSG_APPLY_SCREEN_STATE, screenState);
            mPendingPowerStateUpdate = false;
            return true;
        }
        return false;
    }

    /**
     * get default T4TNfcee power state supported
     */
    private int getT4tNfceePowerState() {
        int powerState = mDeviceHost.getT4TNfceePowerState();
        synchronized (NfcService.this) {
            if (mIsSecureNfcEnabled) {
                /* Secure nfc on,Setting power state screen on unlocked */
                powerState = POWER_STATE_SWITCH_ON;
            }
        }
        if (DBG) Log.d(TAG, "T4TNfceePowerState: " + powerState);
        return powerState;
    }

    /**
     * get info on NDEF-NFCEE feature from HAL config file
     */
    public boolean isNdefNfceefeatureEnabled() {
        return mDeviceHost.isNdefNfceefeatureEnabled();
    }

    public boolean sendData(byte[] data) {
        notifyOemLogEvent(new OemLogItems.Builder(OemLogItems.LOG_ACTION_HCE_DATA)
                .setApduResponse(data).build());
        return mDeviceHost.sendRawFrame(data);
    }

    public void onPreferredPaymentChanged(int reason) {
        mHandler.removeMessages(MSG_PREFERRED_PAYMENT_CHANGED);
        sendMessage(MSG_PREFERRED_PAYMENT_CHANGED, reason);
    }

    public void onPreferredSimChanged() {
        mHandler.sendEmptyMessage(MSG_PREFERRED_SIM_CHANGED);
    }

    public void clearRoutingTable(int clearFlags) {
        //Remove any previously sent messages not yet processed
        mHandler.removeMessages(MSG_COMMIT_ROUTING);
        mHandler.removeMessages(MSG_ROUTE_AID);
        mHandler.removeMessages(MSG_CLEAR_ROUTING_TABLE);
        mHandler.removeMessages(MSG_UNROUTE_AID);
        sendMessage(MSG_CLEAR_ROUTING_TABLE, clearFlags);
    }

    public void setIsoDepProtocolRoute(int route) {
        sendMessage(MSG_UPDATE_ISODEP_PROTOCOL_ROUTE, route);
    }

    /**
     * Set NFCC technology routing for ABF listening
     */
    public void setTechnologyABFRoute(int route, int felicaRoute) {
        Message msg = mHandler.obtainMessage();
        msg.what = MSG_UPDATE_TECHNOLOGY_ABF_ROUTE;
        msg.arg1 = route;
        msg.arg2 = felicaRoute;
        mHandler.sendMessage(msg);
    }

    public void setSystemCodeRoute(int route) {
        // Don't call function "nfcManager_updateSystemCodeRoute()" after NFC Deinitialization
        if(!isNfcDisabledOrDisabling()) {
            sendMessage(MSG_UPDATE_SYSTEM_CODE_ROUTE, route);
        }
    }

    void sendMessage(int what, Object obj) {
        Message msg = mHandler.obtainMessage();
        msg.what = what;
        msg.obj = obj;
        mHandler.sendMessage(msg);
    }

    void sendMessage(int what, int arg1, Object obj) {
        Message msg = mHandler.obtainMessage();
        msg.what = what;
        msg.arg1 = arg1;
        msg.obj = obj;
        mHandler.sendMessage(msg);
    }

    /**
     * Send require device unlock for NFC intent to system UI.
     */
    public void sendRequireUnlockIntent() {
        if (!mIsRequestUnlockShowed && mNfcInjector.isDeviceLocked()) {
            if (DBG) Log.d(TAG, "sendRequireUnlockIntent");
            mIsRequestUnlockShowed = true;
            mRequireUnlockWakeLock.acquire();
            Intent requireUnlockIntent =
                    new Intent(NfcAdapter.ACTION_REQUIRE_UNLOCK_FOR_NFC);
            requireUnlockIntent.setPackage(SYSTEM_UI);
            mContext.sendBroadcast(requireUnlockIntent);
            mRequireUnlockWakeLock.release();
        }
    }

    final class NfcServiceHandler extends Handler {
        public NfcServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ROUTE_AID: {
                    Log.d(TAG, "handleMessage: MSG_ROUTE_AID");
                    int route = msg.arg1;
                    int aidInfo = msg.arg2;
                    String aid = (String) msg.obj;

                    int power = 0x00;
                    Bundle bundle = msg.getData();
                    if (bundle != null) {
                        power = bundle.getInt(MSG_ROUTE_AID_PARAM_TAG);
                    }

                    mDeviceHost.routeAid(hexStringToBytes(aid), route, aidInfo, power);
                    // Restart polling config
                    break;
                }
                case MSG_UNROUTE_AID: {
                    Log.d(TAG, "handleMessage: MSG_UNROUTE_AID");
                    String aid = (String) msg.obj;
                    mDeviceHost.unrouteAid(hexStringToBytes(aid));
                    break;
                }
                case MSG_REGISTER_T3T_IDENTIFIER: {
                    Log.d(TAG, "handleMessage: MSG_REGISTER_T3T_IDENTIFIER");
                    mDeviceHost.disableDiscovery();

                    byte[] t3tIdentifier = (byte[]) msg.obj;
                    mDeviceHost.registerT3tIdentifier(t3tIdentifier);

                    NfcDiscoveryParameters params = computeDiscoveryParameters(mScreenState);
                    boolean shouldRestart = mCurrentDiscoveryParameters.shouldEnableDiscovery();
                    mDeviceHost.enableDiscovery(params, shouldRestart);
                    break;
                }
                case MSG_DEREGISTER_T3T_IDENTIFIER: {
                    Log.d(TAG, "handleMessage: MSG_DEREGISTER_T3T_IDENTIFIER");
                    mDeviceHost.disableDiscovery();

                    byte[] t3tIdentifier = (byte[]) msg.obj;
                    mDeviceHost.deregisterT3tIdentifier(t3tIdentifier);

                    NfcDiscoveryParameters params = computeDiscoveryParameters(mScreenState);
                    boolean shouldRestart = mCurrentDiscoveryParameters.shouldEnableDiscovery();
                    mDeviceHost.enableDiscovery(params, shouldRestart);
                    break;
                }
                case MSG_COMMIT_ROUTING: {
                    Log.d(TAG, "handleMessage: MSG_COMMIT_ROUTING");
                    synchronized (NfcService.this) {
                        if (isNfcDisabledOrDisabling()) {
                            Log.d(TAG, "handleMessage: Skip commit routing when NFCC is off "
                                    + "or turning off");
                            mCardEmulationManager.onRoutingChangeCompleted(STATUS_UNKNOWN_ERROR);
                            return;
                        }
                        if (mCurrentDiscoveryParameters.shouldEnableDiscovery()) {
                            if (mNfcOemExtensionCallback != null) {
                                // OemExtension will call the commit routing after some actions
                                if (receiveOemCallbackResult(ACTION_ON_ROUTING_CHANGED)) {
                                    Log.e(TAG, "handleMessage: Oem skip commitRouting");
                                    return;
                                }
                            }
                            mCardEmulationManager.onRoutingChangeCompleted(
                                    mDeviceHost.commitRouting());

                            if (mNfcOemExtensionCallback != null) {
                                try {
                                    mNfcOemExtensionCallback.onRoutingChangeCompleted();
                                } catch (RemoteException e) {
                                    Log.e(TAG, "onRoutingChangeCompleted failed e = " + e);
                                }
                            }
                        } else {
                            Log.d(TAG,
                                    "handleMessage: Not committing routing because "
                                            + "discovery is disabled");
                        }
                    }
                    break;
                }
                case MSG_MOCK_NDEF: {
                    Log.d(TAG, "handleMessage: MSG_MOCK_NDEF");
                    NdefMessage ndefMsg = (NdefMessage) msg.obj;
                    Bundle extras = new Bundle();
                    extras.putParcelable(Ndef.EXTRA_NDEF_MSG, ndefMsg);
                    extras.putInt(Ndef.EXTRA_NDEF_MAXLENGTH, 0);
                    extras.putInt(Ndef.EXTRA_NDEF_CARDSTATE, Ndef.NDEF_MODE_READ_ONLY);
                    extras.putInt(Ndef.EXTRA_NDEF_TYPE, Ndef.TYPE_OTHER);
                    /* Avoid setting mCookieUpToDate to negative values */
                    mCookieUpToDate = mCookieGenerator.nextLong() >>> 1;
                    Tag tag = Tag.createMockTag(new byte[]{0x00},
                            new int[]{TagTechnology.NDEF},
                            new Bundle[]{extras}, mCookieUpToDate);
                    Log.d(TAG, "handleMessage: mock NDEF tag, starting corresponding activity");
                    Log.d(TAG, tag.toString());
                    int dispatchStatus = mNfcDispatcher.dispatchTag(tag);
                    if (dispatchStatus == NfcDispatcher.DISPATCH_SUCCESS) {
                        playSound(SOUND_END);
                    } else if (dispatchStatus == NfcDispatcher.DISPATCH_FAIL) {
                        playSound(SOUND_ERROR);
                    }
                    break;
                }

                case MSG_NDEF_TAG:
                    if (!isNfcEnabled())
                        break;
                    if (DBG) Log.d(TAG, "Tag detected, notifying applications");

                    clearAppInactivityDetectionContext();

                    TagEndpoint tag = (TagEndpoint) msg.obj;
                    byte[] debounceTagUid;
                    int debounceTagMs;
                    ITagRemovedCallback debounceTagRemovedCallback;
                    synchronized (NfcService.this) {
                        debounceTagUid = mDebounceTagUid;
                        debounceTagMs = mDebounceTagDebounceMs;
                        debounceTagRemovedCallback = mDebounceTagRemovedCallback;
                    }
                    ReaderModeParams readerParams = null;
                    int presenceCheckDelay = DEFAULT_PRESENCE_CHECK_DELAY;
                    DeviceHost.TagDisconnectedCallback callback =
                            new DeviceHost.TagDisconnectedCallback() {
                                @Override
                                public void onTagDisconnected() {
                                    mCookieUpToDate = -1;
                                    clearAppInactivityDetectionContext();
                                    executeOemOnTagConnectedCallback(false);
                                    executeReaderModeOnTagLostCallback();
                                    applyRouting(false);
                                }
                            };
                    synchronized (NfcService.this) {
                        readerParams = mReaderModeParams;
                    }
                    executeOemOnTagConnectedCallback(true);
                    if (mNfcOemExtensionCallback != null
                            && receiveOemCallbackResult(ACTION_ON_READ_NDEF)) {
                        Log.d(TAG, "handleMessage: skip due to oem callback");
                        tag.startPresenceChecking(presenceCheckDelay, callback);
                        break;
                    }
                    if (readerParams != null) {
                        presenceCheckDelay = readerParams.presenceCheckDelay;
                        if ((readerParams.flags & NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK) != 0) {
                            if (DBG) {
                                Log.d(TAG, "handleMessage: Skipping NDEF detection in reader mode");
                            }
                            tag.startPresenceChecking(presenceCheckDelay, callback);
                            dispatchTagEndpoint(tag, readerParams);
                            break;
                        }

                        if (mIsDebugBuild && mSkipNdefRead) {
                            if (DBG) {
                                Log.d(TAG, "handleMessage: Only NDEF detection in reader mode");
                            }
                            tag.findNdef();
                            tag.startPresenceChecking(presenceCheckDelay, callback);
                            dispatchTagEndpoint(tag, readerParams);
                            break;
                        }
                    }

                    if (tag.getConnectedTechnology() == TagTechnology.NFC_BARCODE) {
                        // When these tags start containing NDEF, they will require
                        // the stack to deal with them in a different way, since
                        // they are activated only really shortly.
                        // For now, don't consider NDEF on these.
                        if (DBG) {
                            Log.d(TAG, "handleMessage: Skipping NDEF detection for NFC Barcode");
                        }
                        tag.startPresenceChecking(presenceCheckDelay, callback);
                        dispatchTagEndpoint(tag, readerParams);
                        if (readerParams == null) {
                            scheduleAppInactivityDetectionTask();
                        }
                        break;
                    }

                    if (mGestureExchangeEnabled
                            && readerParams == null
                            && tag.getConnectedTechnology() == TagTechnology.ISO_DEP) {
                        int[] retCode;
                        byte[] respData;

                        String gestureSecondaryAid = Settings.Secure.getString(
                                mContext.getContentResolver(),
                                GESTURE_EXCHAGE_SECONDARY_AID_SETTINGS_KEY);

                        // Validate the fetched string
                        if (gestureSecondaryAid != null && !gestureSecondaryAid.isEmpty()) {
                            byte[] gestureSecondaryAidCheckCmd =
                                    buildSelectAidCommand(gestureSecondaryAid);
                            Log.d(TAG, "Gesture exchange alternative AID selectCmd:"
                                    + Arrays.toString(gestureSecondaryAidCheckCmd));


                            retCode = new int[2];
                            respData = tag.transceive(gestureSecondaryAidCheckCmd, false, retCode);

                            if (respData != null && respData.length >= 2) {
                                if (respData[respData.length - 2] == (byte) 0x90
                                        && respData[respData.length - 1] == 0x00) {
                                    Log.d(TAG, "Gesture Exchange secondary AID exists, "
                                            + "skipping ndef read");
                                    if (SdkLevel.isAtLeastC()
                                            && mNfcGestureExchangeCallback != null) {
                                        try {
                                            mCookieUpToDate = mCookieGenerator.nextLong() >>> 1;
                                            String gestureComponent = Settings.Secure.getString(
                                                    mContext.getContentResolver(),
                                                    GESTURE_EXCHANGE_COMPONENT_SETTINGS_KEY);
                                            Tag gestureTag = buildGestureTag(tag,
                                                    gestureComponent, gestureSecondaryAid);
                                            registerTagObject(tag);
                                            registerTag(gestureTag);
                                            mNfcGestureExchangeCallback.onTagDiscovered(gestureTag);
                                        } catch (RemoteException e) {
                                            Log.e(TAG, "Failed to trigger gesture callback", e);
                                        }
                                    } else {
                                        ReaderModeParams gestureReaderModeParams =
                                                new ReaderModeParams();
                                        gestureReaderModeParams.flags =
                                                GESTURE_EXCHANGE_SECONDARY_FLAG;
                                        dispatchTagEndpoint(tag, gestureReaderModeParams);
                                    }

                                    tag.startPresenceChecking(presenceCheckDelay, callback);
                                    break;
                                }
                            }
                        }

                        byte[] gestureAidCheckCmd = buildSelectAidCommand(GESTURE_EXCHAGE_AID);
                        Log.d(TAG, "Gesture exchange primary AID selectCmd:"
                                + Arrays.toString(gestureAidCheckCmd));
                        retCode = new int[2];
                        respData = tag.transceive(gestureAidCheckCmd, false, retCode);

                        if (respData != null && respData.length >= 2) {
                            if (respData[respData.length - 2] == (byte) 0x90
                                    && respData[respData.length - 1] == 0x00) {
                                Log.d(TAG, "Gesture Exchange AID exists, skipping ndef read");
                                if (SdkLevel.isAtLeastC()
                                        && mNfcGestureExchangeCallback != null) {
                                    try {
                                        mCookieUpToDate = mCookieGenerator.nextLong() >>> 1;
                                        String gestureComponent = Settings.Secure.getString(
                                                mContext.getContentResolver(),
                                                GESTURE_EXCHANGE_COMPONENT_SETTINGS_KEY);
                                        Tag gestureTag = buildGestureTag(tag,
                                                gestureComponent, GESTURE_EXCHAGE_AID);
                                        registerTagObject(tag);
                                        registerTag(gestureTag);
                                        mNfcGestureExchangeCallback.onTagDiscovered(gestureTag);
                                    } catch (RemoteException e) {
                                        Log.e(TAG, "Failed to trigger gesture callback", e);
                                    }
                                } else {
                                    ReaderModeParams gestureReaderModeParams =
                                            new ReaderModeParams();
                                    gestureReaderModeParams.flags = GESTURE_EXCHANGE_FLAG;
                                    dispatchTagEndpoint(tag, gestureReaderModeParams);
                                }

                                tag.startPresenceChecking(presenceCheckDelay, callback);
                                break;
                            }
                        }
                    }

                    NdefMessage ndefMsg = tag.findAndReadNdef();

                    if (ndefMsg == null) {
                        // First try to see if this was a bad tag read
                        if (!tag.reconnect()) {
                            tag.disconnect();
                            if (DBG) Log.d(TAG, "handleMessage: Read NDEF error");
                            executeOemOnTagConnectedCallback(false);
                            executeReaderModeOnTagLostCallback();
                            if (mScreenState == ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED) {
                                if (mReadErrorCount < mReadErrorCountMax) {
                                    mReadErrorCount++;
                                } else {
                                    pollingDelay();
                                }
                                if (!sToast_debounce && mNotifyReadFailed) {
                                    Toast.makeText(mContext, R.string.tag_read_error,
                                                   Toast.LENGTH_SHORT).show();
                                    sToast_debounce = true;
                                    mHandler.sendEmptyMessageDelayed(MSG_TOAST_DEBOUNCE_EVENT,
                                                                     sToast_debounce_time_ms);
                                }
                            }
                            break;
                        }
                    }

                    if (debounceTagUid != null) {
                        // If we're debouncing and the UID or the NDEF message of the tag match,
                        // don't dispatch but drop it.
                        if (Arrays.equals(debounceTagUid, tag.getUid()) ||
                                (ndefMsg != null && ndefMsg.equals(mLastReadNdefMessage))) {
                            mHandler.removeMessages(MSG_TAG_DEBOUNCE);
                            mHandler.sendEmptyMessageDelayed(MSG_TAG_DEBOUNCE, debounceTagMs);
                            tag.disconnect();
                            return;
                        } else {
                            synchronized (NfcService.this) {
                                mDebounceTagUid = null;
                                mDebounceTagRemovedCallback = null;
                                mDebounceTagNativeHandle = INVALID_NATIVE_HANDLE;
                            }
                            if (debounceTagRemovedCallback != null) {
                                try {
                                    debounceTagRemovedCallback.onTagRemoved();
                                } catch (RemoteException e) {
                                    // Ignore
                                }
                            }
                        }
                    }

                    mLastReadNdefMessage = ndefMsg;

                    if (mIsWlcEnabled) {
                        if (DBG) Log.d(TAG, "handleMessage: Wlc enabled, check for WLC_CAP record");

                        if (!mNfcCharging.NfcChargingMode
                                && (mNfcCharging.checkWlcCapMsg(ndefMsg) == true)) {
                            if (DBG) Log.d(TAG, "handleMessage: checkWlcCapMsg returned true");
                            if (mNfcCharging.startNfcCharging(tag)) {
                                mNfcCharging.NfcChargingMode = true;
                                if (DBG) {
                                    Log.d(TAG,
                                            "handleMessage: "
                                                    + "Nfc charging mode started successfully");
                                }
                            } else {
                                if (DBG) {
                                    Log.d(TAG,
                                            "handleMessage: Nfc charging mode not detected");
                                }
                            }
                        } else if (mIsRWCapable) {
                            tag.startPresenceChecking(presenceCheckDelay, callback);
                            dispatchTagEndpoint(tag, readerParams);
                        } else {
                            tag.startPresenceChecking(presenceCheckDelay, callback);
                        }
                    } else if (mIsRWCapable) {
                        tag.startPresenceChecking(presenceCheckDelay, callback);
                        dispatchTagEndpoint(tag, readerParams);
                    } else {
                        tag.startPresenceChecking(presenceCheckDelay, callback);
                    }
                    if (readerParams == null) {
                        scheduleAppInactivityDetectionTask();
                    }
                    break;

                case MSG_RF_FIELD_ACTIVATED:
                    Log.d(TAG, "handleMessage: MSG_RF_FIELD_ACTIVATED");
                    notifyOemLogEvent(new OemLogItems
                            .Builder(OemLogItems.LOG_ACTION_RF_FIELD_STATE_CHANGED)
                            .setRfFieldOnTime(Instant.now()).build());
                    if (mCardEmulationManager != null) {
                        mCardEmulationManager.onFieldChangeDetected(true);
                    }
                    sendRfFieldOnOffDetectedBroadcast(ACTION_RF_FIELD_ON_DETECTED);
                    if (mIsSecureNfcEnabled) {
                        sendRequireUnlockIntent();
                    }
                    break;
                case MSG_RF_FIELD_DEACTIVATED:
                    Log.d(TAG, "handleMessage: MSG_RF_FIELD_DEACTIVATED");
                    notifyOemLogEvent(new OemLogItems
                            .Builder(OemLogItems.LOG_ACTION_RF_FIELD_STATE_CHANGED)
                            .setRfFieldOnTime(Instant.EPOCH).build());
                    if (mCardEmulationManager != null) {
                        mCardEmulationManager.onFieldChangeDetected(false);
                    }
                    sendRfFieldOnOffDetectedBroadcast(ACTION_RF_FIELD_OFF_DETECTED);
                    break;
                case MSG_RESUME_POLLING:
                    Log.d(TAG, "handleMessage: MSG_RESUME_POLLING");
                    mNfcAdapter.resumePolling();
                    break;
                case MSG_TAG_DEBOUNCE:
                    Log.d(TAG, "handleMessage: MSG_TAG_DEBOUNCE");
                    // Didn't see the tag again, tag is gone
                    ITagRemovedCallback tagRemovedCallback;
                    synchronized (NfcService.this) {
                        mDebounceTagUid = null;
                        tagRemovedCallback = mDebounceTagRemovedCallback;
                        mDebounceTagRemovedCallback = null;
                        mDebounceTagNativeHandle = INVALID_NATIVE_HANDLE;
                    }
                    if (tagRemovedCallback != null) {
                        try {
                            tagRemovedCallback.onTagRemoved();
                        } catch (RemoteException e) {
                            // Ignore
                        }
                    }
                    break;

                case MSG_APPLY_SCREEN_STATE:
                    mScreenState = (Integer)msg.obj;
                    Log.d(TAG, "handleMessage: MSG_APPLY_SCREEN_STATE "
                            + ScreenStateHelper.screenStateToString(mScreenState));

                    synchronized (NfcService.this) {
                        // Disable delay polling when screen state changed
                        mPollDelayed = false;
                        mHandler.removeMessages(MSG_DELAY_POLLING);
                        // If NFC is turning off, we shouldn't need any changes here
                        if (mState.get() == NfcAdapter.STATE_TURNING_OFF) {
                            return;
                        }
                    }

                    notifyOemLogEvent(
                            new OemLogItems.Builder(OemLogItems.LOG_ACTION_SCREEN_STATE_CHANGED)
                                    .build());

                    mRoutingWakeLock.acquire();
                    try {
                        int screen_state_mask = mScreenState;
                        if (mNfcUnlockManager.isLockscreenPollingEnabled()) {
                            screen_state_mask |= ScreenStateHelper.SCREEN_POLLING_TAG_MASK;
                        }
                        if (mScreenState == ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED) {
                            mIsRequestUnlockShowed = false;
                            if (android.app.Flags.deviceUnlockListener()
                                    && mIsKeyguardLocked) {
                                Log.d(TAG, "Don't start polling when KeyguardLocked");
                            } else {
                                screen_state_mask |= ScreenStateHelper.SCREEN_POLLING_TAG_MASK;
                            }
                        }
                        if (mNfcUnlockManager.isLockscreenPollingEnabled()
                                || mScreenState == ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED) {
                            applyRouting(false);
                        }
                        mDeviceHost.doSetScreenState(screen_state_mask, mIsWlcEnabled);
                    } finally {
                        if (mRoutingWakeLock.isHeld()) {
                            mRoutingWakeLock.release();
                        }
                    }
                    break;

                case MSG_TRANSACTION_EVENT:
                    Log.d(TAG, "handleMessage: MSG_TRANSACTION_EVENT");
                    if (mCardEmulationManager != null) {
                        mCardEmulationManager.onOffHostAidTransaction();
                    }
                    byte[][] data = (byte[][]) msg.obj;
                    if (mIsEuiccCapable){
                        byte [] reader = null;
                        String sReader = new String(data[2], StandardCharsets.UTF_8);

                        if (mCardEmulationManager != null
                                && sReader.contains(RoutingOptionManager.SE_PREFIX_SIM)) {
                            reader = mCardEmulationManager.getReaderByPreferredSim();
                        }
                        if (reader != null) {
                            data[2] = reader;
                        }
                    }
                    synchronized (NfcService.this) {
                        sendOffHostTransactionEvent(data[0], data[1], data[2]);
                    }
                    break;

                case MSG_SE_SELECTED_EVENT:
                    Log.d(TAG, "handleMessage: MSG_SE_SELECTED_EVENT");
                    int type = (int) msg.arg1;
                    if (mCardEmulationManager != null && type == SE_SELECTED_AID) {
                        Pair<byte[], String> aidAndEeName = (Pair<byte[], String>) msg.obj;
                        String aidString = Utils.aidBytesToString(aidAndEeName.first);
                        mCardEmulationManager.onOffHostAidSelected(aidString, aidAndEeName.second);
                    }
                    break;
                case MSG_PREFERRED_PAYMENT_CHANGED:
                    Log.d(TAG, "handleMessage: MSG_PREFERRED_PAYMENT_CHANGED");
                    Intent preferredPaymentChangedIntent =
                            new Intent(NfcAdapter.ACTION_PREFERRED_PAYMENT_CHANGED);
                    preferredPaymentChangedIntent.putExtra(
                            NfcAdapter.EXTRA_PREFERRED_PAYMENT_CHANGED_REASON, (int)msg.obj);
                    sendPreferredPaymentChangedEvent(preferredPaymentChangedIntent);
                    break;

                case MSG_TOAST_DEBOUNCE_EVENT:
                    Log.d(TAG, "handleMessage: MSG_TOAST_DEBOUNCE_EVENT");
                    sToast_debounce = false;
                    break;

                case MSG_DELAY_POLLING:
                    Log.d(TAG, "handleMessage: MSG_DELAY_POLLING");
                    synchronized (NfcService.this) {
                        if (!mPollDelayed) {
                            return;
                        }
                        mPollDelayed = false;
                        mDeviceHost.startStopPolling(true);
                    }
                    if (DBG) Log.d(TAG, "handleMessage: Polling is started");
                    break;
                case MSG_CLEAR_ROUTING_TABLE:
                    if (!isNfcEnabled()) break;
                    Log.d(TAG, "handleMessage: MSG_CLEAR_ROUTING_TABLE");
                    int clearFlags = (Integer)msg.obj;
                    mDeviceHost.clearRoutingEntry(clearFlags);
                    break;
                case MSG_UPDATE_ISODEP_PROTOCOL_ROUTE:
                    Log.d(TAG, "handleMessage: MSG_UPDATE_ISODEP_PROTOCOL_ROUTE");
                    if (isNfcEnabled()) {
                        mDeviceHost.setIsoDepProtocolRoute((Integer) msg.obj);
                    }
                    break;
                case MSG_UPDATE_TECHNOLOGY_ABF_ROUTE:
                    Log.d(TAG, "handleMessage: MSG_UPDATE_TECHNOLOGY_ABF_ROUTE");
                    int msgRoute = msg.arg1;
                    int felicaRoute = msg.arg2;
                    if (isNfcEnabled()) {
                        mDeviceHost.setTechnologyABFRoute(msgRoute, felicaRoute);
                    }
                    break;
                case MSG_WATCHDOG_PING:
                    NfcWatchdog watchdog = (NfcWatchdog) msg.obj;
                    watchdog.notifyHasReturned();
                    if (mLastFieldOnTimestamp + TIME_TO_MONITOR_AFTER_FIELD_ON_MS >
                            mNfcInjector.getWallClockMillis()) {
                        watchdog.stopMonitoring();
                    }
                    break;
                case MSG_UPDATE_SYSTEM_CODE_ROUTE:
                    Log.d(TAG, "handleMessage: MSG_UPDATE_SYSTEM_CODE_ROUTE");
                    mDeviceHost.setSystemCodeRoute((Integer) msg.obj);
                    break;

                case MSG_PREFERRED_SIM_CHANGED:
                    if (!isNfcEnabled()) break;
                    Log.d(TAG, "handleMessage: MSG_PREFERRED_SIM_CHANGED");
                    new EnableDisableTask().execute(TASK_DISABLE);
                    new EnableDisableTask().execute(TASK_ENABLE);
                    break;
                case MSG_RESTART_DISCOVERY:
                    Log.d(TAG, "handleMessage: MSG_RESTART_DISCOVERY");
                    mDeviceHost.restartRfDiscovery();
                    break;
                default:
                    Log.e(TAG, "handleMessage: Unknown message received");
                    break;
            }
        }

        private byte[] buildSelectAidCommand(String aidStr) {
            if (aidStr == null || aidStr.length() % 2 != 0) {
                Log.e(TAG, "Invalid or missing AID.");
                return null;
            }
            int aidLen = aidStr.length() / 2;
            byte[] selectCmd = new byte[5 + aidLen]; // 5 bytes for APDU header + AID payload

            // Set standard APDU Header
            selectCmd[0] = (byte) 0x00; // CLA
            selectCmd[1] = (byte) 0xA4; // INS
            selectCmd[2] = (byte) 0x04; // P1
            selectCmd[3] = (byte) 0x00; // P2
            selectCmd[4] = (byte) aidLen; // Lc (Length of AID)

            for (int i = 0; i < aidStr.length(); i += 2) {
                selectCmd[5 + (i / 2)] = (byte) ((Character.digit(aidStr.charAt(i), 16) << 4)
                        + Character.digit(aidStr.charAt(i + 1), 16));
            }

            return selectCmd;
        }

        private void sendOffHostTransactionEvent(byte[] aid, byte[] data, byte[] readerByteArray) {
            String reader = "";
            int uid = -1;
            int offhostCategory = NfcStatsLog.NFC_CARDEMULATION_OCCURRED__CATEGORY__OFFHOST;
            try {
                String aidString = Utils.aidBytesToString(aid);
                String aidCategory = mCardEmulationManager
                        .getRegisteredAidCategory(aidString.toString());
                if (DBG) {
                    Log.d(TAG, "sendOffHostTransactionEvent: aid category=" + aidCategory);
                }
                if (mStatsdUtils != null) {
                    mStatsdUtils.setCardEmulationEventCategory(aidCategory);
                } else {
                    switch (aidCategory) {
                        case CardEmulation.CATEGORY_PAYMENT:
                            offhostCategory = NfcStatsLog
                                  .NFC_CARDEMULATION_OCCURRED__CATEGORY__OFFHOST_PAYMENT;
                            break;
                        case CardEmulation.CATEGORY_OTHER:
                            offhostCategory = NfcStatsLog
                                    .NFC_CARDEMULATION_OCCURRED__CATEGORY__OFFHOST_OTHER;
                            break;
                        default:
                            offhostCategory = NfcStatsLog
                                .NFC_CARDEMULATION_OCCURRED__CATEGORY__OFFHOST;
                    };
                }

                reader = new String(readerByteArray, "UTF-8");

                if (!isSEServiceAvailable() || mNfcEventInstalledPackages.isEmpty()) {
                    return;
                }

                for (int userId : mNfcEventInstalledPackages.keySet()) {
                    List<String> packagesOfUser = mNfcEventInstalledPackages.get(userId);
                    String[] installedPackages = new String[packagesOfUser.size()];
                    boolean[] nfcAccess = mSEService.isNfcEventAllowed(reader, aid,
                            packagesOfUser.toArray(installedPackages), userId);
                    if (nfcAccess == null) {
                        continue;
                    }
                    Intent intent = new Intent(NfcAdapter.ACTION_TRANSACTION_DETECTED);
                    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.putExtra(NfcAdapter.EXTRA_AID, aid);
                    intent.putExtra(NfcAdapter.EXTRA_DATA, data);
                    intent.putExtra(NfcAdapter.EXTRA_SECURE_ELEMENT_NAME, reader);
                    String url =
                            new String("nfc://secure:0/" + reader + "/" + aidString.toString());
                    intent.setData(Uri.parse(url));

                    final BroadcastOptions options = BroadcastOptions.makeBasic();
                    options.setBackgroundActivityStartsAllowed(true);

                    Map<String, Integer> hasIntentPackages = mContext
                            .getPackageManager()
                            .queryBroadcastReceiversAsUser(intent, 0, UserHandle.of(userId))
                            .stream()
                            .collect(Collectors.toMap(
                                      activity -> activity.activityInfo.applicationInfo.packageName,
                                      activity -> activity.activityInfo.applicationInfo.uid,
                                      (packageName1, packageName2) -> {
                                        if (DBG)  {
                                            Log.d(TAG,
                                                    "sendOffHostTransactionEvent: "
                                                            + "queryBroadcastReceiversAsUser"
                                                            + " duplicate: " + packageName1 + ", "
                                                            + packageName2);
                                        }
                                          return packageName1;
                                      }));
                    if (DBG)  {
                        String[] packageNames = hasIntentPackages
                                .keySet().toArray(new String[hasIntentPackages.size()]);
                        Log.d(TAG, "sendOffHostTransactionEvent: queryBroadcastReceiversAsUser: "
                                + Arrays.toString(packageNames));
                    }

                    boolean foundFirstPackage = false;
                    for (int i = 0; i < nfcAccess.length; i++) {
                        if (nfcAccess[i]) {
                            String packageName = packagesOfUser.get(i);
                            if (DBG)  {
                                Log.d(TAG, "sendOffHostTransactionEvent: to " + packageName);
                            }
                            if (!foundFirstPackage && hasIntentPackages.containsKey(packageName)) {
                                uid = hasIntentPackages.get(packageName);
                                if (mStatsdUtils != null) {
                                    mStatsdUtils.setCardEmulationEventUid(uid);
                                }
                                foundFirstPackage = true;
                            }
                            intent.setPackage(packagesOfUser.get(i));
                            mContext.sendBroadcastAsUser(intent, UserHandle.of(userId), null,
                                    options.toBundle());
                        }
                    }
                }
            } catch (RemoteException e) {
                Log.e(TAG, "sendOffHostTransactionEvent: Error in isNfcEventAllowed() " + e);
            } catch (UnsupportedEncodingException e) {
                Log.e(TAG, "sendOffHostTransactionEvent: Incorrect format for Secure Element name"
                        + e);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "sendOffHostTransactionEvent: Error " + e);
            } finally {
                if (mStatsdUtils != null) {
                    mStatsdUtils.logCardEmulationOffhostEvent(reader);
                } else {
                    NfcStatsLog.write(NfcStatsLog.NFC_CARDEMULATION_OCCURRED,
                            offhostCategory,
                            reader,
                            uid);
                }
            }
        }

        private void sendRfFieldOnOffDetectedBroadcast(String action) {
            if (mNfcEventInstalledPackages.isEmpty()) {
                return;
            }
            Intent intent = new Intent(action);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            Bundle rfFieldBroadcastOptions = coalesceRfFieldOnOffBroadcasts()
                    ? RF_FIELD_ON_OFF_BROADCAST_OPTIONS : null;
            Runnable task = () -> {
                Map<Integer, List<String>> packagesCopy = new HashMap<>(mNfcEventInstalledPackages);
                Intent broadcastIntent = new Intent(intent);
                for (int userId : packagesCopy.keySet()) {
                    List<String> pkgList = new ArrayList<>(packagesCopy.get(userId));
                    for (String packageName : pkgList) {
                        broadcastIntent.setPackage(packageName);
                        mContext.sendBroadcastAsUser(broadcastIntent,
                                UserHandle.of(userId),
                                null /* receiverPermission */,
                                rfFieldBroadcastOptions);
                    }
                }
                Log.d(TAG, "sendRfFieldOnOffDetectedBroadcast: Background task sendBroadcast "
                        + intent.getAction());
            };

            mNfcBroadcastHandler.post(task);
        }

        /* Returns the list of packages request for nfc preferred payment service changed and
         * have access to NFC Events on any SE */
        private ArrayList<String> getNfcPreferredPaymentChangedSEAccessAllowedPackages(int userId) {
            synchronized (NfcService.this) {
                if (!isSEServiceAvailable() || isPowerSavingModeEnabled()
                        || mNfcPreferredPaymentChangedInstalledPackages.get(userId).isEmpty()) {
                    return null;
                }
                String[] readers = null;
                try {
                    readers = mSEService.getReaders();
                } catch (RemoteException e) {
                    Log.e(TAG,
                            "getNfcPreferredPaymentChangedSEAccessAllowedPackages: "
                                    + "Error in getReaders() "
                                    + e);
                    return null;
                }

                if (readers == null || readers.length == 0) {
                    return null;
                }
                boolean[] nfcAccessFinal = null;
                List<String> packagesOfUser =
                      mNfcPreferredPaymentChangedInstalledPackages.get(userId);
                String[] installedPackages = new String[packagesOfUser.size()];

                for (String reader : readers) {
                    try {
                        boolean[] accessList = mSEService.isNfcEventAllowed(reader, null,
                                packagesOfUser.toArray(installedPackages), userId
                                );
                        if (accessList == null) {
                            continue;
                        }
                        if (nfcAccessFinal == null) {
                            nfcAccessFinal = accessList;
                        }
                        for (int i = 0; i < accessList.length; i++) {
                            if (accessList[i]) {
                                nfcAccessFinal[i] = true;
                            }
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "getNfcPreferredPaymentChangedSEAccessAllowedPackages: "
                                + "Error in isNfcEventAllowed() " + e);
                    } catch (IllegalArgumentException e) {
                        Log.e(TAG,
                                "getNfcPreferredPaymentChangedSEAccessAllowedPackages: Error " + e);
                    }
                }
                if (nfcAccessFinal == null) {
                    return null;
                }
                ArrayList<String> packages = new ArrayList<String>();
                for (int i = 0; i < nfcAccessFinal.length; i++) {
                    if (nfcAccessFinal[i]) {
                        packages.add(packagesOfUser.get(i));
                    }
                }
                return packages;
            }
        }

        private boolean isSystemApp(ApplicationInfo applicationInfo) {
             return ((applicationInfo.flags & APP_INFO_FLAGS_SYSTEM_APP) != 0);
        }

        private void sendPreferredPaymentChangedEvent(Intent intent) {
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            // Resume app switches so the receivers can start activities without delay
            mNfcDispatcher.resumeAppSwitches();
            synchronized (this) {
                for (int userId : mNfcPreferredPaymentChangedInstalledPackages.keySet()) {
                    ArrayList<String> SEPackages =
                            getNfcPreferredPaymentChangedSEAccessAllowedPackages(userId);
                    UserHandle userHandle = UserHandle.of(userId);
                    if (SEPackages != null && !SEPackages.isEmpty()) {
                        for (String packageName : SEPackages) {
                            intent.setPackage(packageName);
                            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                            mContext.sendBroadcastAsUser(intent, userHandle);
                        }
                    }
                    PackageManager pm;
                    try {
                        pm = mContext.createContextAsUser(userHandle, /*flags=*/0)
                                .getPackageManager();
                    } catch (IllegalStateException e) {
                        Log.d(TAG,
                                "sendPreferredPaymentChangedEvent: "
                                        + "Fail to get PackageManager for user: "
                                        + userHandle);
                        continue;
                    }
                    for (String pkgName :
                            mNfcPreferredPaymentChangedInstalledPackages.get(userId)) {
                        try {
                            PackageInfo info = pm.getPackageInfo(pkgName, 0);
                            if (SEPackages != null && SEPackages.contains(pkgName)) {
                                continue;
                            }
                            if (info.applicationInfo != null
                                    && (isSystemApp(info.applicationInfo)
                                    || info.applicationInfo.isPrivilegedApp())) {
                                intent.setPackage(pkgName);
                                intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                                mContext.sendBroadcastAsUser(intent, userHandle);
                            }
                        } catch (Exception e) {
                            Log.e(TAG,
                                    "sendPreferredPaymentChangedEvent: Exception in getPackageInfo "
                                            + e);
                        }
                    }
                }
            }
        }

        private void pollingDelay() {
            if (isNfcDisabledOrDisabling()) {
                Log.d(TAG, "pollingDelay: Skip when NFCC is off or turning off");
                return;
            }
            if (mPollDelayTime <= NO_POLL_DELAY) return;
            synchronized (NfcService.this) {
                if (!mPollDelayed) {
                    mPollDelayed = true;
                    mDeviceHost.startStopPolling(false);
                    int delayTime = mPollDelayTime;
                    if (mPollDelayCount < mPollDelayCountMax) {
                        mPollDelayCount++;
                    } else {
                        delayTime = mPollDelayTimeLong;
                    }
                    if (DBG) Log.d(TAG, "pollingDelay: delay " + delayTime);
                    mHandler.sendMessageDelayed(
                            mHandler.obtainMessage(MSG_DELAY_POLLING), delayTime);
                } else {
                    if (DBG) Log.d(TAG, "pollingDelay: Keep waiting");
                }
            }
        }

        private Tag buildGestureTag(TagEndpoint endpoint, String component, String aid) {
            NdefRecord applicationRecord = NdefRecord.createApplicationRecord(component);
            NdefMessage applicationMsg = new NdefMessage(applicationRecord);

            Bundle extras = new Bundle();
            extras.putParcelable(Ndef.EXTRA_NDEF_MSG, applicationMsg);
            extras.putString(NfcAdapter.EXTRA_AID, aid);

            Bundle[] techExtras = new Bundle[] {endpoint.getTechExtras()[0], extras};

            int[] techList = new int[] { TagTechnology.ISO_DEP, TagTechnology.NDEF };

            return new Tag(endpoint.getUid(), techList, techExtras, endpoint.getHandle(),
                    mCookieUpToDate, mNfcTagService);
        }

        private void dispatchTagEndpoint(TagEndpoint tagEndpoint, ReaderModeParams readerParams) {
            if (mNfcOemExtensionCallback != null
                    && receiveOemCallbackResult(ACTION_ON_TAG_DISPATCH)) {
                Log.d(TAG, "dispatchTagEndpoint: skip due to oem callback");
                return;
            }
            try {
                boolean performFeedback = true;
                /* Avoid setting mCookieUpToDate to negative values */
                mCookieUpToDate = mCookieGenerator.nextLong() >>> 1;
                Tag tag;
                String gestureComponent = Settings.Secure.getString(mContext.getContentResolver(),
                        GESTURE_EXCHANGE_COMPONENT_SETTINGS_KEY);

                boolean isValidGesture = (readerParams != null && gestureComponent != null);
                boolean isPrimaryGesture =
                        isValidGesture && (readerParams.flags == GESTURE_EXCHANGE_FLAG);
                boolean isSecondaryGesture =
                        isValidGesture && (readerParams.flags == GESTURE_EXCHANGE_SECONDARY_FLAG);

                if (isPrimaryGesture) {
                    tag = buildGestureTag(tagEndpoint, gestureComponent, GESTURE_EXCHAGE_AID);
                    readerParams = null;
                    performFeedback = false;

                } else if (isSecondaryGesture) {
                    String gestureSecondaryAid =
                            Settings.Secure.getString(mContext.getContentResolver(),
                            GESTURE_EXCHAGE_SECONDARY_AID_SETTINGS_KEY);
                    tag = buildGestureTag(tagEndpoint, gestureComponent, gestureSecondaryAid);
                    readerParams = null;
                    performFeedback = false;
                } else {
                    // Default fallback
                    tag = new Tag(tagEndpoint.getUid(), tagEndpoint.getTechList(),
                            tagEndpoint.getTechExtras(), tagEndpoint.getHandle(),
                            mCookieUpToDate, mNfcTagService);
                }

                registerTagObject(tagEndpoint);
                registerTag(tag);
                if (readerParams != null) {
                    try {
                        if ((readerParams.flags & NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS) == 0) {
                            mVibrator.vibrate(mVibrationEffect,
                                    HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES);
                            playSound(SOUND_END);
                        }
                        if (readerParams.callback != null) {
                            if (mScreenState == ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED) {
                                mPowerManager.userActivity(SystemClock.uptimeMillis(),
                                        PowerManager.USER_ACTIVITY_EVENT_OTHER, 0);
                            }
                            readerParams.callback.onTagDiscovered(tag);
                            return;
                        } else {
                            // Follow normal dispatch below
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG,
                                "dispatchTagEndpoint: Reader mode remote has died, falling back ",
                                e);
                        // Intentional fall-through
                    } catch (Exception e) {
                        // Catch any other exception
                        Log.e(TAG, "dispatchTagEndpoint: App exception, not dispatching ", e);
                        return;
                    }
                }
                refreshTagDispatcherInProvisionMode();
                int dispatchResult = mNfcDispatcher.dispatchTag(tag);
                if (dispatchResult == NfcDispatcher.DISPATCH_FAIL
                        && !isEndPointRemovalDetectionSupported()) {
                    if (DBG) Log.d(TAG, "dispatchTagEndpoint: Tag dispatch failed");
                    executeOemOnTagConnectedCallback(false);
                    executeReaderModeOnTagLostCallback();
                    unregisterObject(tagEndpoint.getHandle());
                    unregisterTag(tag.getServiceHandle());
                    if (mPollDelayTime > NO_POLL_DELAY) {
                        pollingDelay();
                        tagEndpoint.stopPresenceChecking(false);
                    } else {
                        Log.d(TAG, "dispatchTagEndpoint: Keep presence checking");
                    }
                    if (mScreenState == ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED
                            && mNotifyDispatchFailed && !mInProvisionMode) {
                        if (!sToast_debounce) {
                            Toast.makeText(mContext, R.string.tag_dispatch_failed,
                                           Toast.LENGTH_SHORT).show();
                            sToast_debounce = true;
                            mHandler.sendEmptyMessageDelayed(MSG_TOAST_DEBOUNCE_EVENT,
                                                             sToast_debounce_time_ms);
                        }
                        if (performFeedback) {
                            playSound(SOUND_ERROR);
                        }
                    }
                    if (!mAntennaBlockedMessageShown && mDispatchFailedCount++ > mDispatchFailedMax) {
                        new NfcBlockedNotification(mContext).startNotification();
                        synchronized (NfcService.this) {
                            mPrefsEditor.putBoolean(PREF_ANTENNA_BLOCKED_MESSAGE_SHOWN, true);
                            mPrefsEditor.apply();
                        }
                        mBackupManager.dataChanged();
                        mAntennaBlockedMessageShown = true;
                        mDispatchFailedCount = 0;
                        if (DBG) {
                            Log.d(TAG,
                                    "dispatchTagEndpoint: Tag dispatch failed notification");
                        }
                    }
                } else if (dispatchResult == NfcDispatcher.DISPATCH_SUCCESS) {
                    synchronized (NfcService.this) {
                        mPollDelayCount = 0;
                        mReadErrorCount = 0;
                    }
                    if (mScreenState == ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED) {
                        mPowerManager.userActivity(SystemClock.uptimeMillis(),
                                PowerManager.USER_ACTIVITY_EVENT_OTHER, 0);
                    }
                    mDispatchFailedCount = 0;
                    if (performFeedback) {
                        mVibrator.vibrate(mVibrationEffect, HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES);
                        playSound(SOUND_END);
                    }
                    notifyOemLogEvent(new OemLogItems.Builder(OemLogItems.LOG_ACTION_TAG_DETECTED)
                            .setTag(tag).build());
                }
            } catch (Exception e) {
                Log.e(TAG, "dispatchTagEndpoint: Tag creation exception, not dispatching ", e);
                return;
            }
        }
    }

    private void executeOemOnTagConnectedCallback(boolean connected) {
        mTagConnected = connected;
        if (mNfcOemExtensionCallback != null) {
            try {
                mNfcOemExtensionCallback.onTagConnected(connected);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        }
    }

    private void executeReaderModeOnTagLostCallback() {
        if (mReaderModeParams != null && mReaderModeParams.callback != null) {
            try {
                Object[] objectValues;
                synchronized (mObjectMapLock) {
                    objectValues = mTagObjectMap.values().toArray();
                    mTagObjectMap.clear();
                }

                for (Object o : objectValues) {
                    if (o instanceof Tag) {
                        mReaderModeParams.callback.onTagLost((Tag) o);
                    }
                }
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        }
    }

    private NfcServiceHandler mHandler;

    class ApplyRoutingTask extends AsyncTask<Integer, Void, Void> {
        @Override
        protected Void doInBackground(Integer... params) {
            synchronized (NfcService.this) {
                if (params == null || params.length != 1) {
                    // force apply current routing
                    applyRouting(true);
                    return null;
                }
                mScreenState = params[0].intValue();

                mRoutingWakeLock.acquire();
                try {
                    applyRouting(false);
                } finally {
                    if (mRoutingWakeLock.isHeld()) {
                        mRoutingWakeLock.release();
                    }
                }
                return null;
            }
        }
    }

    private void handleScreenStateChanged() {
        // Perform applyRouting() in AsyncTask to serialize blocking calls
        if (mIsWlcCapable && mNfcCharging.NfcChargingOnGoing) {
            Log.d(TAG,
                    "handleScreenStateChanged: MSG_APPLY_SCREEN_STATE postponing due "
                            + "to a charging pier device");
            mPendingPowerStateUpdate = true;
            return;
        }
        int screenState = mScreenStateHelper.checkScreenState(mCheckDisplayStateForScreenState);
        if (screenState == SCREEN_STATE_ON_LOCKED || screenState == SCREEN_STATE_ON_UNLOCKED) {
            synchronized (NfcService.this) {
                mPollDelayCount = 0;
                mReadErrorCount = 0;
            }
        }
        applyScreenState(screenState);
    }

    boolean isEndPointRemovalDetectionSupported() {
        if (!(mIsRDCapable && mDeviceHost.isRemovalDetectionInPollModeSupported())) {
            Log.d(TAG, "isEndPointRemovalDetectionSupported: not supported");
            return false;
        }
        if (!(mAppInActivityDetectionTime >= MIN_RF_REMOVAL_DETECTION_TIMEOUT &&
                (mTagRemovalDetectionWaitTime >= MIN_RF_REMOVAL_DETECTION_TIMEOUT &&
                 mTagRemovalDetectionWaitTime <= MAX_RF_REMOVAL_DETECTION_TIMEOUT))) {
            Log.d(TAG, "isEndPointRemovalDetectionSupported: Unexpected wait time");
            return false;
        }
        return true;
    }

    void scheduleAppInactivityDetectionTask() {
        if (isEndPointRemovalDetectionSupported()) {
            clearAppInactivityDetectionContext();
            mAppInActivityDetectionTimer = new Timer();
            AppInActivityHandlerTask task = new AppInActivityHandlerTask();
            mAppInActivityDetectionTimer.schedule(task,mAppInActivityDetectionTime);
            Log.d(TAG, "scheduleAppInactivityDetectionTask: scheduled");
        }
    }

    boolean checkAndHandleRemovalDetectionMode(boolean isDisconnectNeeded) {
        if (mAppInActivityDetectionTimer != null) {
            if (isPresenceCheckStopped()) {
                Log.d(TAG, "checkAndHandleRemovalDetectionMode: Removal detection state");
                if (isDisconnectNeeded) {
                    Log.d(TAG, "checkAndHandleRemovalDetectionMode: Restarting discovery..");
                    maybeDisconnectTarget();
                    return true;
                }
            } else {
                Log.d(TAG, "checkAndHandleRemovalDetectionMode: Clearing Removal "
                        + "Detection Timer Context");
                clearAppInactivityDetectionContext();
            }
        }
        return false;
    }

    class AppInActivityHandlerTask extends TimerTask {
        public void run() {
            Log.d(TAG, "run: App Inactivity detected, Requesting to Start Removal "
                    + "Detection Procedure");
            if (isTagPresent()) {
                prepareForRemovalDetectionMode();
                Log.d(TAG, "run: No activity over reader mode, "
                        + "RF removal detection procedure started");
                /* Request JNI to start remove detection procedure */
                startRemovalDetection(mTagRemovalDetectionWaitTime);
            } else {
                clearAppInactivityDetectionContext();
            }
        }
    }

    void clearAppInactivityDetectionContext() {
        if (mAppInActivityDetectionTimer != null) {
            mAppInActivityDetectionTimer.cancel();
            mAppInActivityDetectionTimer = null;
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SCREEN_ON)
                    || action.equals(Intent.ACTION_SCREEN_OFF)
                    || action.equals(Intent.ACTION_USER_PRESENT)) {
                handleScreenStateChanged();
            } else if ((action.equals(ACTION_BOOT_COMPLETED)
                    || action.equals(ACTION_LOCKED_BOOT_COMPLETED))
                    && mIsHceCapable) {
                if (DBG) Log.d(TAG, action + " received");
                synchronized (NfcService.this) {
                    mCardEmulationManager.onBootCompleted();
                }
            } else if (action.equals(Intent.ACTION_USER_SWITCHED)) {
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                mUserId = userId;
                updatePackageCache();
                if (DBG) Log.d(TAG, action + "mReceiver.onReceive: UserId: " + userId);
                if (mIsHceCapable) {
                    mCardEmulationManager.onUserSwitched(getUserId());
                    loadSecureNfcSettings(userId);
                }

                mNfcInjector.onUserSwitched();
                applyScreenState(mScreenStateHelper.checkScreenState(mCheckDisplayStateForScreenState));

                if ((NFC_SNOOP_LOG_MODE.equals(NfcProperties.snoop_log_mode_values.FULL) ||
                        NFC_VENDOR_DEBUG_ENABLED) &&
                        mDeviceConfigFacade.getEnableDeveloperNotification()){
                    new NfcDeveloperOptionNotification(mContext.createContextAsUser(
                            UserHandle.of(ActivityManager.getCurrentUser()), /*flags=*/0))
                            .startNotification();
                }
                // Reload when another userId activated
                synchronized (NfcService.this) {
                    initTagAppPrefList();
                }
            } else if (action.equals(Intent.ACTION_USER_ADDED)) {
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                setPaymentForegroundPreference(userId);

                if ((NFC_SNOOP_LOG_MODE.equals(NfcProperties.snoop_log_mode_values.FULL) ||
                        NFC_VENDOR_DEBUG_ENABLED) &&
                        mDeviceConfigFacade.getEnableDeveloperNotification()) {
                    new NfcDeveloperOptionNotification(mContext.createContextAsUser(
                            UserHandle.of(ActivityManager.getCurrentUser()), /*flags=*/0))
                            .startNotification();
                }
            } else if (action.equals(Intent.ACTION_USER_UNLOCKED)) {
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                // If this is first unlock after upgrading to NFC stack that is direct boot aware,
                // migrate over the data from CE directory to DE directory for access before user
                // unlock in subsequent bootups.
                if (!mPrefs.getBoolean(PREF_MIGRATE_TO_DE_COMPLETE, false) && userId == 0) {
                    Log.i(TAG, "mReceiver.onReceive: Migrating shared prefs to DE directory "
                            + "from CE directory");
                    Context ceContext = mContext.createCredentialProtectedStorageContext();
                    SharedPreferences cePreferences =
                        ceContext.getSharedPreferences(PREF, Context.MODE_PRIVATE);
                    SharedPreferences ceTagPreferences =
                            ceContext.getSharedPreferences(PREF_TAG_APP_LIST, Context.MODE_PRIVATE);
                    Log.d(TAG, "mReceiver.onReceive: CE Shared Pref values: "
                            + cePreferences.getAll() + ", " + ceTagPreferences.getAll());
                    if (cePreferences.getAll().isEmpty()) {
                        Log.d(TAG,
                                "mReceiver.onReceive: No NFC Shared preferences to "
                                        + "migrate from CE data");
                    } else {
                        if (!mContext.moveSharedPreferencesFrom(ceContext, PREF)) {
                            Log.e(TAG,
                                    "mReceiver.onReceive: Failed to migrate NFC Shared preferences "
                                    + "to DE directory");
                            return;
                        }
                    }
                    if (ceTagPreferences.getAll().isEmpty()) {
                        Log.d(TAG,
                                "mReceiver.onReceive: No NFC Shared preferences for tag app "
                                        + "to migrate from CE data");
                    } else {
                        if (!mContext.moveSharedPreferencesFrom(ceContext, PREF_TAG_APP_LIST)) {
                            Log.e(TAG,
                                    "mReceiver.onReceive: Failed to migrate NFC Shared "
                                            + "preferences for tag app list to DE directory");
                            return;
                        }
                        initTagAppPrefList();
                    }
                    if (mIsHceCapable) {
                        mCardEmulationManager.migrateSettingsFilesFromCe(ceContext);
                    }
                    // If the move is completed, refresh our reference to the shared preferences.
                    mPrefs = mContext.getSharedPreferences(PREF, Context.MODE_PRIVATE);
                    mPrefsEditor = mPrefs.edit();
                    mPrefsEditor.putBoolean(PREF_MIGRATE_TO_DE_COMPLETE, true);
                    // Read the shared prefs again to determine if NFC should
                    // be enabled or disabled.
                    reinitializeAfterDeMigration();
                }
            }
        }

        private void reinitializeAfterDeMigration() {
            if (shouldEnableNfc()) {
                new EnableDisableTask().execute(TASK_ENABLE);
            } else {
                new EnableDisableTask().execute(TASK_DISABLE);
            }
            String secureNfcPreferenceKey = getSecureNfcPreferenceKeyForUser(mUserId);
            synchronized (NfcService.this) {
                if (mUserId == UserHandle.SYSTEM.getIdentifier()
                        && !mPrefs.contains(PREF_SECURE_NFC_ON + "_"
                                + UserHandle.SYSTEM.getIdentifier())) {
                    mIsSecureNfcEnabled = mPrefs.getBoolean(PREF_SECURE_NFC_ON,
                            mDeviceConfigFacade.getDefaultSecureNfcState())
                            && mIsSecureNfcCapable;
                } else {
                    mIsSecureNfcEnabled = mPrefs.getBoolean(secureNfcPreferenceKey,
                            mDeviceConfigFacade.getDefaultSecureNfcState())
                            && mIsSecureNfcCapable;
                }

                mPrefsEditor.putBoolean(secureNfcPreferenceKey, mIsSecureNfcEnabled);
                mPrefsEditor.apply();
                mBackupManager.dataChanged();

                mDeviceHost.setNfcSecure(mIsSecureNfcEnabled);

                if (mIsHceCapable) {
                    // update HCE/HCEF routing and commitRouting if Nfc is enabled
                    mCardEmulationManager.onTriggerRoutingTableUpdate();
                } else if (isNfcEnabled()) {
                    // commit only tech/protocol route without HCE support
                    mDeviceHost.commitRouting();
                }
            }
        }
    };

    private final BroadcastReceiver mManagedProfileReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UserHandle user = intent.getParcelableExtra(Intent.EXTRA_USER);

            // User should be filled for below intents, check the existence.
            if (user == null) {
                Log.d(TAG, intent.getAction()
                        + "mManagedProfileReceiver.onReceive: broadcast without EXTRA_USER");
                return;
            }

            if (mCardEmulationManager == null) {
                return;
            }
            if (action.equals(Intent.ACTION_MANAGED_PROFILE_ADDED) ||
                    action.equals(Intent.ACTION_MANAGED_PROFILE_AVAILABLE) ||
                    action.equals(Intent.ACTION_MANAGED_PROFILE_REMOVED) ||
                    action.equals(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)) {
                if (DBG) {
                    Log.d(TAG, action + "mManagedProfileReceiver.onReceive: UserId: "
                            + user.getIdentifier());
                }
                mCardEmulationManager.onManagedProfileChanged();
                setPaymentForegroundPreference(user.getIdentifier());
                synchronized (NfcService.this) {
                    initTagAppPrefList();
                }
            }
        }
    };

    private final BroadcastReceiver mOwnerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_PACKAGE_REMOVED)
                    || action.equals(Intent.ACTION_PACKAGE_ADDED)
                    || action.equals(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
                    || action.equals(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)) {
                updatePackageCache();
                renewTagAppPrefList(action);
            } else if (action.equals(Intent.ACTION_SHUTDOWN)) {
                if (DBG) {
                    Log.d(TAG, "mOwnerReceiver.onReceive: Shutdown received with UserId: "
                            + getSendingUser().getIdentifier());
                }
                if (!getSendingUser().equals(UserHandle.ALL)) {
                    return;
                }
                if (DBG) Log.d(TAG, "mOwnerReceiver.onReceive: Device is shutting down");
                if (mIsAlwaysOnSupported && mAlwaysOnState == NfcAdapter.STATE_ON) {
                    new EnableDisableTask().execute(TASK_DISABLE_ALWAYS_ON);
                }
                if (isNfcEnabled()) {
                    StopPresenceChecking(true);
                    mDeviceHost.shutdown();
                }
            }
        }
    };

    private void applyScreenState(int screenState) {
        if (mIsWatchType && !mCardEmulationManager.isRequiresScreenOnServiceExist()) {
            if (screenState == ScreenStateHelper.SCREEN_STATE_OFF_LOCKED) {
                screenState = SCREEN_STATE_ON_LOCKED;
            } else if (screenState == ScreenStateHelper.SCREEN_STATE_OFF_UNLOCKED) {
                screenState = ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED;
            }
        }
        if (DBG)  {
            Log.d(TAG, "applyScreenState: screenState = "
                    + ScreenStateHelper.screenStateToString(screenState));
        }
        if (mScreenState != screenState) {
            if (nci_version < NCI_VERSION_2_0) {
                new ApplyRoutingTask().execute(Integer.valueOf(screenState));
            }
            if (DBG) Log.d(TAG, "applyScreenState: screenState != mScreenState=" + mScreenState);
            sendMessage(MSG_APPLY_SCREEN_STATE, screenState);
        }
    }

    private void setPaymentForegroundPreference(int user) {
        Context uc;
        try {
            uc = mContext.createContextAsUser(UserHandle.of(user), 0);
        } catch (IllegalStateException e) {
            Log.d(TAG,
                    "setPaymentForegroundPreference: Fail to get user context for user: " + user);
            return;
        }
        try {
            // Check whether the Settings.Secure.NFC_PAYMENT_FOREGROUND exists or not.
            Settings.Secure.getInt(uc.getContentResolver(),
                    Constants.SETTINGS_SECURE_NFC_PAYMENT_FOREGROUND);
        } catch (SettingNotFoundException e) {
            boolean foregroundPreference =
                    mContext.getResources().getBoolean(R.bool.payment_foreground_preference);
            Settings.Secure.putInt(uc.getContentResolver(),
                    Constants.SETTINGS_SECURE_NFC_PAYMENT_FOREGROUND, foregroundPreference ? 1 : 0);
            Log.d(TAG, "setPaymentForegroundPreference: NFC_PAYMENT_FOREGROUND preference:"
                    + foregroundPreference);
        }
    }

    /**
     * for debugging only - no i18n
     */
    static String stateToString(int state) {
        switch (state) {
            case NfcAdapter.STATE_OFF:
                return "off";
            case NfcAdapter.STATE_TURNING_ON:
                return "turning on";
            case NfcAdapter.STATE_ON:
                return "on";
            case NfcAdapter.STATE_TURNING_OFF:
                return "turning off";
            default:
                return "<error>";
        }
    }

    static int stateToProtoEnum(int state) {
        switch (state) {
            case NfcAdapter.STATE_OFF:
                return NfcServiceDumpProto.STATE_OFF;
            case NfcAdapter.STATE_TURNING_ON:
                return NfcServiceDumpProto.STATE_TURNING_ON;
            case NfcAdapter.STATE_ON:
                return NfcServiceDumpProto.STATE_ON;
            case NfcAdapter.STATE_TURNING_OFF:
                return NfcServiceDumpProto.STATE_TURNING_OFF;
            default:
                return NfcServiceDumpProto.STATE_UNKNOWN;
        }
    }

    private void copyNativeCrashLogsIfAny(PrintWriter pw) {
        try {
            File file = new File(NATIVE_LOG_FILE_PATH, NATIVE_LOG_FILE_NAME);
            if (!file.exists()) {
                return;
            }
            pw.println("---BEGIN: NATIVE CRASH LOG----");
            Scanner sc = new Scanner(file);
            while (sc.hasNextLine()) {
                String s = sc.nextLine();
                pw.println(s);
            }
            pw.println("---END: NATIVE CRASH LOG----");
            sc.close();
        } catch (IOException e) {
            Log.e(TAG, "copyNativeCrashLogsIfAny: Exception " + e);
        }
    }

    public void storeNativeCrashLogs() {
        FileOutputStream fos = null;
        try {
            File file = new File(NATIVE_LOG_FILE_PATH, NATIVE_LOG_FILE_NAME);
            if (file.length() >= NATIVE_CRASH_FILE_SIZE) {
                file.createNewFile();
            }

            fos = new FileOutputStream(file, true);
            mDeviceHost.dump(new PrintWriter(new StringWriter()), fos.getFD());
            fos.flush();
        } catch (IOException e) {
            Log.e(TAG, "storeNativeCrashLogs: e=" + e);
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    Log.e(TAG, "storeNativeCrashLogs: file close " + e);
                }
            }
        }
    }

    private void dumpTagAppPreference(PrintWriter pw) {
        pw.println("mIsTagAppPrefSupported =" + mIsTagAppPrefSupported);
        if (!mIsTagAppPrefSupported) return;
        pw.println("TagAppPreference:");
        for (Integer userId : getEnabledUserIds()) {
            HashMap<String, Boolean> map;
            synchronized (NfcService.this) {
                map = mTagAppPrefList.getOrDefault(userId, new HashMap<>());
            }
            if (map.size() > 0) pw.println("userId=" + userId);
            for (Map.Entry<String, Boolean> entry : map.entrySet()) {
                pw.println("pkg: " + entry.getKey() + " : " + entry.getValue());
            }
        }
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump nfc from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " without permission " + android.Manifest.permission.DUMP);
            return;
        }

        for (String arg : args) {
            if ("--proto".equals(arg)) {
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(fd);
                    ProtoOutputStream proto = new ProtoOutputStream(fos);
                    synchronized (this) {
                        dumpDebug(proto);
                    }
                    proto.flush();
                } catch (Exception e) {
                    Log.e(TAG, "dump: exception=" + e);
                } finally {
                    if (fos != null) {
                        try {
                            fos.close();
                        } catch (IOException e) {
                            Log.e(TAG, "dump: Exception in storeNativeCrashLogs " + e);
                        }
                    }
                }
                return;
            }
        }
        pw.println("mState=" + stateToString(mState.get()));
        pw.println("mAlwaysOnState=" + stateToString(mAlwaysOnState));
        pw.println("mScreenState=" + ScreenStateHelper.screenStateToString(mScreenState));
        pw.println("mIsSecureNfcEnabled=" + mIsSecureNfcEnabled);
        pw.println("mIsReaderOptionEnabled=" + mIsReaderOptionEnabled);
        pw.println("mIsAlwaysOnSupported=" + mIsAlwaysOnSupported);
        if (mIsWlcCapable) {
            pw.println("WlcEnabled=" + mIsWlcEnabled);
        }
        pw.println("SnoopLogMode=" + NFC_SNOOP_LOG_MODE);
        pw.println("VendorDebugEnabled=" + NFC_VENDOR_DEBUG_ENABLED);
        pw.println("mPowerSavingState=" + mPowerSavingState);
        pw.println("mIsObserveModeSupported=" + mNfcAdapter.isObserveModeSupported());
        pw.println("mIsObserveModeEnabled=" + mNfcAdapter.isObserveModeEnabled());
        pw.println("listenTech=0x" + Integer.toHexString(getNfcListenTech()));
        pw.println("pollTech=0x" + Integer.toHexString(getNfcPollTech()));
        pw.println(mCurrentDiscoveryParameters);
        if (mIsHceCapable) {
            mCardEmulationManager.dump(fd, pw, args);
        }
        mNfcDispatcher.dump(fd, pw, args);
        if (mState.get() == NfcAdapter.STATE_ON) {
            mRoutingTableParser.dump(mDeviceHost, pw);
            mRfDiscoverCmdParser.dump(mDeviceHost, pw);
        }

        dumpTagAppPreference(pw);
        mNfcInjector.getNfcEventLog().dump(fd, pw, args);
        copyNativeCrashLogsIfAny(pw);
        pw.flush();
        mDeviceHost.dump(pw,fd);
    }

    /**
     * Dump debugging information as a NfcServiceDumpProto
     *
     * Note:
     * See proto definition in frameworks/base/core/proto/android/nfc/nfc_service.proto
     * When writing a nested message, must call {@link ProtoOutputStream#start(long)} before and
     * {@link ProtoOutputStream#end(long)} after.
     * Never reuse a proto field number. When removing a field, mark it as reserved.
     */
    private void dumpDebug(ProtoOutputStream proto) {
        proto.write(NfcServiceDumpProto.STATE, stateToProtoEnum(mState.get()));
        proto.write(NfcServiceDumpProto.IN_PROVISION_MODE, mInProvisionMode);
        proto.write(NfcServiceDumpProto.SCREEN_STATE,
                ScreenStateHelper.screenStateToProtoEnum(mScreenState));
        proto.write(NfcServiceDumpProto.SECURE_NFC_ENABLED, mIsSecureNfcEnabled);
        proto.write(NfcServiceDumpProto.POLLING_PAUSED, mPollingPaused);
        proto.write(NfcServiceDumpProto.HCE_CAPABLE, mIsHceCapable);
        proto.write(NfcServiceDumpProto.HCE_F_CAPABLE, mIsHceFCapable);
        proto.write(NfcServiceDumpProto.SECURE_NFC_CAPABLE, mIsSecureNfcCapable);
        proto.write(NfcServiceDumpProto.VR_MODE_ENABLED, false);

        long token = proto.start(NfcServiceDumpProto.DISCOVERY_PARAMS);
        mCurrentDiscoveryParameters.dumpDebug(proto);
        proto.end(token);

        if (mIsHceCapable) {
            token = proto.start(NfcServiceDumpProto.CARD_EMULATION_MANAGER);
            mCardEmulationManager.dumpDebug(proto);
            proto.end(token);
        }

        token = proto.start(NfcServiceDumpProto.NFC_DISPATCHER);
        mNfcDispatcher.dumpDebug(proto);
        proto.end(token);

        // Dump native crash logs if any
        File file = new File(NATIVE_LOG_FILE_PATH, NATIVE_LOG_FILE_NAME);
        if (!file.exists()) {
            return;
        }
        try {
            String logs = Files.lines(file.toPath()).collect(Collectors.joining("\n"));
            proto.write(NfcServiceDumpProto.NATIVE_CRASH_LOGS, logs);
        } catch (IOException e) {
            Log.e(TAG, "dumpDebug: IOException=" + e);
        }
    }

    private int runTaskOnSingleThreadExecutor(FutureTask<Integer> task, int timeoutMs)
            throws InterruptedException, TimeoutException, ExecutionException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(task);
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            executor.shutdownNow();
            throw e;
        }
    }

    void cancelRoutingWatchDog() {
        synchronized (mRoutingWatchDogLock) {
            if (mRoutingWatchDog != null) {
                mRoutingWatchDog.cancel();
                mRoutingWatchDog = null;
            }
        }
    }

    @VisibleForTesting
    public Handler getHandler() {
        return mHandler;
    }

    public void notifyOemLogEvent(OemLogItems item) {
        if (mNfcOemExtensionCallback != null) {
            try {
                mNfcOemExtensionCallback.onLogEventNotified(item);
            } catch (RemoteException e) {
                Log.e(TAG, "notifyOemLogEvent: failed e = " + e.toString());
            }

        }
    }

    public boolean isFirmwareExitFramesSupported() {
        return mDeviceHost.isFirmwareExitFramesSupported();
    }

    public int getNumberOfFirmwareExitFramesSupported() {
        return mDeviceHost.getNumberOfFirmwareExitFramesSupported();
    }

    public boolean setFirmwareExitFrameTable(List<ExitFrame> exitFrames, int timeoutMs) {
        // Check if NFC is enabled
        if (!isNfcEnabled()) {
            Log.w(TAG, "setFirmwareExitFrameTable: NFC is not enabled");
            return false;
        }

        if (mCardEmulationManager != null
                && mCardEmulationManager.isHostCardEmulationActivated()) {
            Log.w(TAG,"setFirmwareExitFrameTable: " +
                "Cannot set exit rame table in during a transaction.");
            return false;
        }

        byte[] timeoutBytes = new byte[2];
        if (timeoutMs > 0xFFFF) {
            Log.w(TAG,
                    "setFirmwareExitFrameTable: timeout is larger than 16 bits, "
                            + "timeout will be truncated");
            timeoutBytes = new byte[] {(byte) 0xFF, (byte) 0xFF};
        } else {
            // Convert to little endian, two byte array
            timeoutBytes[0] = (byte) timeoutMs;
            timeoutBytes[1] = (byte) (timeoutMs >> 8);
        }

        boolean result = mDeviceHost.setFirmwareExitFrameTable(exitFrames.toArray(ExitFrame[]::new),
                timeoutBytes);

        if (result && mStatsdUtils != null) {
            mStatsdUtils.logExitFrameTableChanged(exitFrames.size(), timeoutMs);
        }

        return result;
    }

    private final class GesturePollFrameObserver extends ContentObserver {
        GesturePollFrameObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            updateGesturePollFrame();
        }

        void updateGesturePollFrame() {
            String gesturePollFrameString =
                    Settings.Secure.getString(mContext.getContentResolver(),
                            HostEmulationManager.GESTURE_POLL_FRAME_SETTINGS_KEY);
            if (gesturePollFrameString == null) {
                mGestureExchangeEnabled = false;
                byte[] defaultFrame = new byte[] {(byte) 0x6a, 0x01, (byte) 0xcf, 0x00, 0x00};
                mDeviceHost.setDefaultFrame(defaultFrame);
            } else {
                mGestureExchangeEnabled = true;
                byte[] frame = HexFormat.of().parseHex(gesturePollFrameString);
                mDeviceHost.setDefaultFrame(frame);
            }
        }
    }
}
