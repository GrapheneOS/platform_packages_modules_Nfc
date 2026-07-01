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

import static android.nfc.NfcAdapter.ACTION_PREFERRED_PAYMENT_CHANGED;

import static com.android.nfc.NfcService.INVALID_NATIVE_HANDLE;
import static com.android.nfc.NfcService.NCI_VERSION_1_0;
import static com.android.nfc.NfcService.NFC_LISTEN_A;
import static com.android.nfc.NfcService.NFC_LISTEN_B;
import static com.android.nfc.NfcService.NFC_LISTEN_F;
import static com.android.nfc.NfcService.NFC_POLL_V;
import static com.android.nfc.NfcService.PREF_NFC_ON;
import static com.android.nfc.NfcService.RF_FIELD_ON_OFF_BROADCAST_OPTIONS;
import static com.android.nfc.NfcService.SOUND_END;
import static com.android.nfc.NfcService.SOUND_ERROR;
import static com.android.nfc.module.nonexported.flags.Flags.coalesceRfFieldOnOffBroadcasts;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.Application;
import android.app.KeyguardManager;
import android.app.VrManager;
import android.app.backup.BackupManager;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.media.SoundPool;
import android.nfc.ErrorCodes;
import android.nfc.INfcAdapterExtras;
import android.nfc.INfcControllerAlwaysOnListener;
import android.nfc.INfcDta;
import android.nfc.INfcOemExtensionCallback;
import android.nfc.INfcUnlockHandler;
import android.nfc.INfcVendorNciCallback;
import android.nfc.INfcWlcStateListener;
import android.nfc.ITagRemovedCallback;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.NfcAntennaInfo;
import android.nfc.NfcOemExtension;
import android.nfc.NfcServiceManager;
import android.nfc.Tag;
import android.nfc.TransceiveResult;
import android.nfc.WlcListenerDeviceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.PollingFrame;
import android.nfc.tech.Ndef;
import android.nfc.tech.TagTechnology;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.flag.junit.SetFlagsRule;
import android.se.omapi.ISecureElementService;
import android.sysprop.NfcProperties;
import android.view.Display;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.cardemulation.CardEmulationManager;
import com.android.nfc.cardemulation.util.StatsdUtils;
import com.android.nfc.dhimpl.NativeNfcManager;
import com.android.nfc.flags.Flags;
import com.android.nfc.wlc.NfcCharging;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RunWith(AndroidJUnit4.class)
public final class NfcServiceTest {
    private static final String PKG_NAME = "com.test";
    private static final int[] ANTENNA_POS_X = { 5 };
    private static final int[] ANTENNA_POS_Y = { 6 };
    private static final int ANTENNA_DEVICE_WIDTH = 9;
    private static final int ANTENNA_DEVICE_HEIGHT = 10;
    private static final boolean ANTENNA_DEVICE_FOLDABLE = true;
    @Mock Application mApplication;
    @Mock NfcInjector mNfcInjector;
    @Mock DeviceHost mDeviceHost;
    @Mock NfcEventLog mNfcEventLog;
    @Mock NfcDispatcher mNfcDispatcher;
    @Mock NfcUnlockManager mNfcUnlockManager;
    @Mock SharedPreferences mPreferences;
    @Mock SharedPreferences.Editor mPreferencesEditor;
    @Mock PowerManager mPowerManager;
    @Mock PackageManager mPackageManager;
    @Mock ScreenStateHelper mScreenStateHelper;
    @Mock Resources mResources;
    @Mock KeyguardManager mKeyguardManager;
    @Mock UserManager mUserManager;
    @Mock ActivityManager mActivityManager;
    @Mock NfcServiceManager.ServiceRegisterer mNfcManagerRegisterer;
    @Mock NfcDiagnostics mNfcDiagnostics;
    @Mock DeviceConfigFacade mDeviceConfigFacade;
    @Mock ContentResolver mContentResolver;
    @Mock Bundle mUserRestrictions;
    @Mock BackupManager mBackupManager;
    @Mock AlarmManager mAlarmManager;
    @Mock SoundPool mSoundPool;
    @Mock DisplayManager mDisplayManager;
    @Mock CardEmulationManager mCardEmulationManager;
    @Mock StatsdUtils mStatsdUtils;
    @Mock NfcCharging mNfcCharging;
    @Mock VrManager mVrManager;
    @Mock RoleManager mRoleManager;
    @Mock AppOpsManager mAppOpsManager;
    @Mock NativeNfcManager mNativeNfcManager;
    @Captor ArgumentCaptor<DeviceHost.DeviceHostListener> mDeviceHostListener;
    @Captor ArgumentCaptor<BroadcastReceiver> mGlobalReceiver;
    @Captor ArgumentCaptor<BroadcastReceiver> mManagedProfileReceiver;
    @Captor ArgumentCaptor<IBinder> mIBinderArgumentCaptor;
    @Captor ArgumentCaptor<Integer> mSoundCaptor;
    @Captor ArgumentCaptor<Intent> mIntentArgumentCaptor;
    @Captor ArgumentCaptor<Bundle> mBundleArgumentCaptor;
    @Captor ArgumentCaptor<ContentObserver> mContentObserverArgumentCaptor;
    @Captor ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverArgumentCaptor;
    TestLooper mLooper;
    NfcService mNfcService;
    private MockitoSession mStaticMockSession;
    private ContentObserver mContentObserver;
    private TestClock mClock = new TestClock();

    class TestClock implements TestLooper.Clock {
        long mOffset = 0;
        public long uptimeMillis() {
            return SystemClock.uptimeMillis() + mOffset;
        }
    }

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() throws PackageManager.NameNotFoundException {
        mLooper = new TestLooper(mClock);
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .mockStatic(NfcProperties.class)
                .mockStatic(android.nfc.Flags.class)
                .mockStatic(Flags.class)
                .mockStatic(NfcStatsLog.class)
                .mockStatic(android.permission.flags.Flags.class)
                .mockStatic(NfcInjector.class)
                .mockStatic(NativeNfcManager.class)
                .mockStatic(android.provider.Settings.Secure.class)
                .mockStatic(com.android.nfc.module.flags.Flags.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        MockitoAnnotations.initMocks(this);
        AsyncTask.setDefaultExecutor(new HandlerExecutor(new Handler(mLooper.getLooper())));

        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_WATCH))
                .thenReturn(false);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_VR_MODE_HIGH_PERFORMANCE))
                .thenReturn(true);
        when(mNfcInjector.getMainLooper()).thenReturn(mLooper.getLooper());
        when(mNfcInjector.getNfcEventLog()).thenReturn(mNfcEventLog);
        when(mNfcInjector.makeDeviceHost(any())).thenReturn(mDeviceHost);
        when(mNfcInjector.getScreenStateHelper()).thenReturn(mScreenStateHelper);
        when(mNfcInjector.getNfcDiagnostics()).thenReturn(mNfcDiagnostics);
        when(mNfcInjector.getDeviceConfigFacade()).thenReturn(mDeviceConfigFacade);
        when(mNfcInjector.getNfcManagerRegisterer()).thenReturn(mNfcManagerRegisterer);
        when(mNfcInjector.getBackupManager()).thenReturn(mBackupManager);
        when(mNfcInjector.getNfcDispatcher()).thenReturn(mNfcDispatcher);
        when(mNfcInjector.getNfcUnlockManager()).thenReturn(mNfcUnlockManager);
        when(mNfcInjector.isSatelliteModeSensitive()).thenReturn(true);
        when(mNfcInjector.getCardEmulationManager()).thenReturn(mCardEmulationManager);
        when(mNfcInjector.getNfcCharging(mDeviceHost)).thenReturn(mNfcCharging);
        when(mNfcInjector.getNfcBroadcastLooper()).thenReturn(mLooper.getLooper());
        when(mApplication.getSharedPreferences(anyString(), anyInt())).thenReturn(mPreferences);
        when(mApplication.getSystemService(PowerManager.class)).thenReturn(mPowerManager);
        when(mApplication.getSystemService(UserManager.class)).thenReturn(mUserManager);
        when(mApplication.getSystemService(ActivityManager.class)).thenReturn(mActivityManager);
        when(mApplication.getSystemService(KeyguardManager.class)).thenReturn(mKeyguardManager);
        when(mApplication.getSystemService(AlarmManager.class)).thenReturn(mAlarmManager);
        when(mApplication.getPackageManager()).thenReturn(mPackageManager);
        when(mDeviceConfigFacade.getCheckDisplayStateForScreenState()).thenReturn(true);
        when(mApplication.getResources()).thenReturn(mResources);
        when(mApplication.createContextAsUser(any(), anyInt())).thenReturn(mApplication);
        when(mApplication.getContentResolver()).thenReturn(mContentResolver);
        when(mApplication.getSystemService(DisplayManager.class)).thenReturn(mDisplayManager);
        when(mApplication.getSystemService(VrManager.class)).thenReturn(mVrManager);
        when(mApplication.getSystemService(RoleManager.class)).thenReturn(mRoleManager);
        when(mApplication.getSystemService(AppOpsManager.class)).thenReturn(mAppOpsManager);
        when(mUserManager.getUserRestrictions()).thenReturn(mUserRestrictions);
        when(mResources.getStringArray(R.array.nfc_allow_list)).thenReturn(new String[0]);
        when(mResources.getBoolean(R.bool.tag_intent_app_pref_supported)).thenReturn(true);
        when(mDeviceConfigFacade.getNfccAlwaysOnAllowed()).thenReturn(true);
        when(mPreferences.edit()).thenReturn(mPreferencesEditor);
        when(mPowerManager.newWakeLock(anyInt(), anyString()))
                .thenReturn(mock(PowerManager.WakeLock.class));
        when(mResources.getIntArray(R.array.antenna_x)).thenReturn(new int[0]);
        when(mResources.getIntArray(R.array.antenna_y)).thenReturn(new int[0]);
        when(mResources.getStringArray(R.array.tag_intent_blocked_app_list))
                .thenReturn(new String[]{"com.android.test"});
        when(NfcProperties.info_antpos_X()).thenReturn(List.of());
        when(NfcProperties.info_antpos_Y()).thenReturn(List.of());
        when(NfcProperties.initialized()).thenReturn(Optional.of(Boolean.TRUE));
        when(NfcProperties.vendor_debug_enabled()).thenReturn(Optional.of(Boolean.TRUE));
        when(NativeNfcManager.getInstance()).thenReturn(mNativeNfcManager);
        when(mPackageManager.getPackageUid(PKG_NAME, 0)).thenReturn(Binder.getCallingUid());
        createNfcService();
    }

    @After
    public void tearDown() {
        mStaticMockSession.finishMocking();
    }

    private void createNfcService() {
        when(android.nfc.Flags.enableNfcCharging()).thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_NFC_CHARGING))
                .thenReturn(true);
        mNfcService = new NfcService(mApplication, mNfcInjector);
        mLooper.dispatchAll();
        verify(mContentResolver, atLeastOnce()).registerContentObserver(any(),
                anyBoolean(), mContentObserverArgumentCaptor.capture());
        mContentObserver = mContentObserverArgumentCaptor.getValue();
        Assert.assertNotNull(mContentObserver);
        verify(mNfcInjector).makeDeviceHost(mDeviceHostListener.capture());
        verify(mApplication).registerReceiverForAllUsers(
                mGlobalReceiver.capture(),
                argThat(intent -> intent.hasAction(Intent.ACTION_SCREEN_ON)), any(), any());
        verify(mApplication).registerReceiverForAllUsers(
                mManagedProfileReceiver.capture(),
                argThat(intent -> intent.hasAction(Intent.ACTION_MANAGED_PROFILE_ADDED)),
                isNull(),
                isNull());
        verify(mApplication).registerReceiver(mBroadcastReceiverArgumentCaptor.capture(),
                argThat(intent -> intent.hasAction(UserManager.ACTION_USER_RESTRICTIONS_CHANGED)));
        clearInvocations(mDeviceHost, mNfcInjector, mApplication);
    }

    private void createNfcServiceWithoutStatsdUtils() {
        when(mNfcInjector.getStatsdUtils()).thenReturn(mStatsdUtils);
        createNfcService();
    }

    private void enableAndVerify() throws Exception {
        when(mDeviceHost.initialize()).thenReturn(true);
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        mNfcService.mNfcAdapter.enable(PKG_NAME);
        verify(mPreferencesEditor).putBoolean(PREF_NFC_ON, true);
        mLooper.dispatchAll();
        verify(mDeviceHost).initialize();
        clearInvocations(mDeviceHost, mPreferencesEditor);
    }

    private void disableAndVerify() throws Exception {
        when(mDeviceHost.deinitialize()).thenReturn(true);
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(false);
        mNfcService.mNfcAdapter.disable(true, PKG_NAME);
        verify(mPreferencesEditor).putBoolean(PREF_NFC_ON, false);
        mLooper.dispatchAll();
        verify(mDeviceHost).deinitialize();
        verify(mNfcDispatcher).resetForegroundDispatch();
        clearInvocations(mDeviceHost, mPreferencesEditor, mNfcDispatcher);
    }


    @Test
    public void testEnable() throws Exception {
        enableAndVerify();
    }

    @Test
    public void testDisable() throws Exception {
        enableAndVerify();
        disableAndVerify();
    }

    @Test
    public void testEnable_clearsObjectMaps() throws Exception {
        // Add mock objects to object maps
        Object mockObject = new Object();
        mNfcService.mObjectMap.put(1, mockObject);
        mNfcService.mTagObjectMap.put(1, mockObject);

        // Enable NFC
        enableAndVerify();

        // Verify that object maps are cleared
        assertTrue(
          "mObjectMap should be cleared on enable", mNfcService.mObjectMap.isEmpty());
        assertTrue(
          "mTagObjectMap should be cleared on enable", mNfcService.mTagObjectMap.isEmpty());
    }

    @Test
    public void testDisable_disconnectsTagsAndClearsMaps() throws Exception {
        // Enable NFC first
        enableAndVerify();

        // Add a mock TagEndpoint to the object map
        DeviceHost.TagEndpoint mockTagEndpoint = mock(DeviceHost.TagEndpoint.class);
        mNfcService.mObjectMap.put(1, mockTagEndpoint);
        mNfcService.mTagObjectMap.put(1, new Object());

        // Disable NFC
        disableAndVerify();

        // Verify that disconnect was called on the tag endpoint
        verify(mockTagEndpoint).disconnect();

        // Verify that object maps are cleared
        assertTrue(
          "mObjectMap should be cleared on disable", mNfcService.mObjectMap.isEmpty());
        assertTrue(
          "mTagObjectMap should be cleared on disable", mNfcService.mTagObjectMap.isEmpty());
    }

    @Test
    public void testStopPresenceChecking_withReaderMode_callsOnTagLost() throws Exception {
        // Set up reader mode with a callback
        NfcService.ReaderModeParams readerParams = mNfcService.new ReaderModeParams();
        readerParams.callback = mock(android.nfc.IAppCallback.class);
        mNfcService.mReaderModeParams = readerParams;

        // Add a mock tag to the tag object map and a mock endpoint to object map
        Tag mockTag = mock(Tag.class);
        DeviceHost.TagEndpoint mockTagEndpoint = mock(DeviceHost.TagEndpoint.class);
        mNfcService.mTagObjectMap.put(1, mockTag);
        mNfcService.mObjectMap.put(1, mockTagEndpoint);

        // onRfDiscoveryEvent(false) calls StopPresenceChecking
        mDeviceHostListener.getValue().onRfDiscoveryEvent(false);
        mLooper.dispatchAll();

        // Verify that onTagLost was called on the reader mode callback
        verify(readerParams.callback).onTagLost(mockTag);
        // Verify that the tag object map is cleared
        assertTrue(mNfcService.mTagObjectMap.isEmpty());
    }

    @Test
    public void testEnable_noHceCapability_doesNotCrash() throws Exception {
        // Set up mocks to simulate a device without HCE capability.
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))
                .thenReturn(false);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF))
                .thenReturn(false);

        // Create a new NfcService instance with the updated mock configuration.
        // This will create an NfcService with mIsHceCapable = false, and
        // mCardEmulationManager will be null.
        createNfcService();

        // Mock dependencies required for the enable() flow.
        when(mDeviceHost.initialize()).thenReturn(true);
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);

        // Execute the enable operation.
        mNfcService.mNfcAdapter.enable(PKG_NAME);
        mLooper.dispatchAll();

        // Verify that the NFC stack initialization proceeds without crashing.
        // The primary goal of this test is to ensure no NullPointerException is thrown
        // when mCardEmulationManager is null.
        verify(mDeviceHost).initialize();
    }

    @Test
    public void testEnable_WheOemExtensionEnabledAndNotInitialized() throws Exception {
        when(mDeviceConfigFacade.getEnableOemExtension()).thenReturn(true);
        when(NfcProperties.initialized()).thenReturn(Optional.of(Boolean.FALSE));

        createNfcService();

        when(mDeviceHost.initialize()).thenReturn(true);
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        mNfcService.mNfcAdapter.enable(PKG_NAME);
        verify(mPreferencesEditor, never()).putBoolean(PREF_NFC_ON, true);
        mLooper.dispatchAll();
        verify(mDeviceHost, never()).initialize();
    }

    @Test
    public void testBootupWithNfcOn() throws Exception {
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        mNfcService = new NfcService(mApplication, mNfcInjector);
        mLooper.dispatchAll();
        verify(mNfcInjector).makeDeviceHost(mDeviceHostListener.capture());
        verify(mApplication).registerReceiverForAllUsers(
                mGlobalReceiver.capture(),
                argThat(intent -> intent.hasAction(Intent.ACTION_SCREEN_ON)), any(), any());
        verify(mDeviceHost).initialize();
    }

    @Test
    public void testBootupWithNfcOn_WhenOemExtensionEnabled() throws Exception {
        when(mDeviceConfigFacade.getEnableOemExtension()).thenReturn(true);
        createNfcService();

        verifyNoMoreInteractions(mDeviceHost);
    }

    @Test
    public void testBootupWithNfcOn_WhenOemExtensionEnabled_ThenAllowBoot() throws Exception {
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        when(mResources.getBoolean(R.bool.enable_oem_extension)).thenReturn(true);
        createNfcService();

        mNfcService.mNfcAdapter.triggerInitialization();
        mLooper.dispatchAll();
        verify(mDeviceHost).initialize();
    }

    @Test
    public void testSetObserveMode_nfcDisabled() throws Exception {
        mNfcService.mNfcAdapter.disable(true, PKG_NAME);

        Assert.assertFalse(mNfcService.mNfcAdapter.setObserveMode(true, PKG_NAME));
    }

    @Test
    public void testIsObserveModeEnabled_nfcDisabled() throws Exception {
        mNfcService.mNfcAdapter.disable(true, PKG_NAME);

        Assert.assertFalse(mNfcService.mNfcAdapter.isObserveModeEnabled());
    }

    @Test
    public void testIsObserveModeSupported_nfcDisabled() throws Exception {
        mNfcService.mNfcAdapter.disable(true, PKG_NAME);

        Assert.assertFalse(mNfcService.mNfcAdapter.isObserveModeSupported());
    }

    @Test
    public void testEnableNfc_changeStateRestricted() throws Exception {
        when(mUserRestrictions.getBoolean(
                UserManager.DISALLOW_CHANGE_NEAR_FIELD_COMMUNICATION_RADIO)).thenReturn(true);
        Exception exception = assertThrows(SecurityException.class, () -> {
            mNfcService.mNfcAdapter.enable(PKG_NAME);
        });
        assertEquals("Change nfc state by system app is not allowed!", exception.getMessage());
        assert (mNfcService.mState.get() == NfcAdapter.STATE_OFF);
    }

    @Test
    public void testDisableNfc_changeStateRestricted() throws Exception {
        enableAndVerify();
        when(mUserRestrictions.getBoolean(
                UserManager.DISALLOW_CHANGE_NEAR_FIELD_COMMUNICATION_RADIO)).thenReturn(true);
        Exception exception = assertThrows(SecurityException.class, () -> {
            mNfcService.mNfcAdapter.disable(true, PKG_NAME);
        });
        assertEquals("Change nfc state by system app is not allowed!", exception.getMessage());
        assert (mNfcService.mState.get() == NfcAdapter.STATE_ON);
    }

    @Test
    public void testHandlerResumePolling() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        handler.handleMessage(handler.obtainMessage(NfcService.MSG_RESUME_POLLING));
        verify(mNfcManagerRegisterer).register(mIBinderArgumentCaptor.capture());
        Assert.assertNotNull(mIBinderArgumentCaptor.getValue());
        Assert.assertFalse(handler.hasMessages(NfcService.MSG_RESUME_POLLING));
        Assert.assertEquals(mIBinderArgumentCaptor.getValue(), mNfcService.mNfcAdapter);
    }

    @Test
    public void testHandlerRoute_Aid() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_ROUTE_AID);
        msg.arg1 = 1;
        msg.arg2 = 2;
        msg.obj = "test";
        handler.handleMessage(msg);
        verify(mDeviceHost).routeAid(any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void testHandlerUnRoute_Aid() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_UNROUTE_AID);
        msg.obj = "test";
        handler.handleMessage(msg);
        verify(mDeviceHost).unrouteAid(any());
    }

    @Test
    public void testGetAntennaInfo_NoneSet() throws Exception {
        enableAndVerify();
        NfcAntennaInfo nfcAntennaInfo = mNfcService.mNfcAdapter.getNfcAntennaInfo();
        assertThat(nfcAntennaInfo).isNotNull();
        assertThat(nfcAntennaInfo.getDeviceWidth()).isEqualTo(0);
        assertThat(nfcAntennaInfo.getDeviceHeight()).isEqualTo(0);
        assertThat(nfcAntennaInfo.isDeviceFoldable()).isEqualTo(false);
        assertThat(nfcAntennaInfo.getAvailableNfcAntennas()).isEmpty();
    }

    @Test
    public void testGetAntennaInfo_ReadFromResources() throws Exception {
        enableAndVerify();
        when(mResources.getIntArray(R.array.antenna_x)).thenReturn(ANTENNA_POS_X);
        when(mResources.getIntArray(R.array.antenna_y)).thenReturn(ANTENNA_POS_Y);
        when(mResources.getInteger(R.integer.device_width)).thenReturn(ANTENNA_DEVICE_WIDTH);
        when(mResources.getInteger(R.integer.device_height)).thenReturn(ANTENNA_DEVICE_HEIGHT);
        when(mResources.getBoolean(R.bool.device_foldable)).thenReturn(ANTENNA_DEVICE_FOLDABLE);
        NfcAntennaInfo nfcAntennaInfo = mNfcService.mNfcAdapter.getNfcAntennaInfo();
        assertThat(nfcAntennaInfo).isNotNull();
        assertThat(nfcAntennaInfo.getDeviceWidth()).isEqualTo(ANTENNA_DEVICE_WIDTH);
        assertThat(nfcAntennaInfo.getDeviceHeight()).isEqualTo(ANTENNA_DEVICE_HEIGHT);
        assertThat(nfcAntennaInfo.isDeviceFoldable()).isEqualTo(ANTENNA_DEVICE_FOLDABLE);
        assertThat(nfcAntennaInfo.getAvailableNfcAntennas()).isNotEmpty();
        assertThat(nfcAntennaInfo.getAvailableNfcAntennas().get(0).getLocationX())
                .isEqualTo(ANTENNA_POS_X[0]);
        assertThat(nfcAntennaInfo.getAvailableNfcAntennas().get(0).getLocationY())
                .isEqualTo(ANTENNA_POS_Y[0]);
    }

    @Test
    public void testGetAntennaInfo_ReadFromSysProp() throws Exception {
        enableAndVerify();
        when(NfcProperties.info_antpos_X())
                .thenReturn(Arrays.stream(ANTENNA_POS_X).boxed().toList());
        when(NfcProperties.info_antpos_Y())
                .thenReturn(Arrays.stream(ANTENNA_POS_Y).boxed().toList());
        when(NfcProperties.info_antpos_device_width())
                .thenReturn(Optional.of(ANTENNA_DEVICE_WIDTH));
        when(NfcProperties.info_antpos_device_height())
                .thenReturn(Optional.of(ANTENNA_DEVICE_HEIGHT));
        when(NfcProperties.info_antpos_device_foldable())
                .thenReturn(Optional.of(ANTENNA_DEVICE_FOLDABLE));
        NfcAntennaInfo nfcAntennaInfo = mNfcService.mNfcAdapter.getNfcAntennaInfo();
        assertThat(nfcAntennaInfo).isNotNull();
        assertThat(nfcAntennaInfo.getDeviceWidth()).isEqualTo(ANTENNA_DEVICE_WIDTH);
        assertThat(nfcAntennaInfo.getDeviceHeight()).isEqualTo(ANTENNA_DEVICE_HEIGHT);
        assertThat(nfcAntennaInfo.isDeviceFoldable()).isEqualTo(ANTENNA_DEVICE_FOLDABLE);
        assertThat(nfcAntennaInfo.getAvailableNfcAntennas()).isNotEmpty();
        assertThat(nfcAntennaInfo.getAvailableNfcAntennas().get(0).getLocationX())
                .isEqualTo(ANTENNA_POS_X[0]);
        assertThat(nfcAntennaInfo.getAvailableNfcAntennas().get(0).getLocationY())
                .isEqualTo(ANTENNA_POS_Y[0]);
    }

    @Test
    public void testHandlerMsgRegisterT3tIdentifier() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_REGISTER_T3T_IDENTIFIER);
        msg.obj = "test".getBytes();
        handler.handleMessage(msg);
        verify(mDeviceHost).disableDiscovery();
        verify(mDeviceHost).registerT3tIdentifier(any());
        verify(mDeviceHost).enableDiscovery(any(), anyBoolean());
        Message msgDeregister = handler.obtainMessage(NfcService.MSG_DEREGISTER_T3T_IDENTIFIER);
        msgDeregister.obj = "test".getBytes();
        handler.handleMessage(msgDeregister);
        verify(mDeviceHost, times(2)).disableDiscovery();
        verify(mDeviceHost, times(2)).enableDiscovery(any(), anyBoolean());
    }

    @Test
    public void testHandlerMsgCommitRouting() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_COMMIT_ROUTING);
        mNfcService.mState.set(NfcAdapter.STATE_OFF);
        handler.handleMessage(msg);
        verify(mDeviceHost, never()).commitRouting();
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        NfcDiscoveryParameters nfcDiscoveryParameters = mock(NfcDiscoveryParameters.class);
        when(nfcDiscoveryParameters.shouldEnableDiscovery()).thenReturn(true);
        mNfcService.mCurrentDiscoveryParameters = nfcDiscoveryParameters;
        handler.handleMessage(msg);
        verify(mDeviceHost).commitRouting();
    }

    @Test
    public void testHandlerMsgMockNdef() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_MOCK_NDEF);
        NdefMessage ndefMessage = mock(NdefMessage.class);
        msg.obj = ndefMessage;
        handler.handleMessage(msg);
        verify(mNfcDispatcher).dispatchTag(any());
    }

    @Test
    public void testInitSoundPool_End() {
        mNfcService.playSound(SOUND_END);

        verify(mSoundPool, never()).play(mSoundCaptor.capture(),
                anyFloat(), anyFloat(), anyInt(), anyInt(), anyFloat());
        mNfcService.mSoundPool = mSoundPool;
        mNfcService.playSound(SOUND_END);
        verify(mSoundPool, atLeastOnce()).play(mSoundCaptor.capture(),
                anyFloat(), anyFloat(), anyInt(), anyInt(), anyFloat());
        Integer value = mSoundCaptor.getValue();
        Assert.assertEquals(mNfcService.mEndSound, (int) value);
    }

    @Test
    public void testInitSoundPool_Error() {
        mNfcService.playSound(SOUND_ERROR);

        verify(mSoundPool, never()).play(mSoundCaptor.capture(),
                anyFloat(), anyFloat(), anyInt(), anyInt(), anyFloat());
        mNfcService.mSoundPool = mSoundPool;
        mNfcService.playSound(SOUND_ERROR);
        verify(mSoundPool, atLeastOnce()).play(mSoundCaptor.capture(),
                anyFloat(), anyFloat(), anyInt(), anyInt(), anyFloat());
        Integer value = mSoundCaptor.getValue();
        Assert.assertEquals(mNfcService.mErrorSound, (int) value);
    }

    @Test
    public void testReleaseSoundPool() {
        mNfcService.mSoundPool = mSoundPool;
        mNfcService.releaseSoundPool();
        Assert.assertNull(mNfcService.mSoundPool);
    }

    @Test
    public void testMsg_Rf_Field_Activated() {
        Assume.assumeTrue(coalesceRfFieldOnOffBroadcasts());
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_RF_FIELD_ACTIVATED);
        List<String> userlist = new ArrayList<>();
        userlist.add("com.android.nfc");
        mNfcService.mNfcEventInstalledPackages.put(1, userlist);
        mNfcService.mIsSecureNfcEnabled = true;
        mNfcService.mIsRequestUnlockShowed = false;
        when(mNfcInjector.isDeviceLocked()).thenReturn(true);
        handler.handleMessage(msg);
        mLooper.dispatchAll();
        verify(mApplication).sendBroadcastAsUser(mIntentArgumentCaptor.capture(), any(),
                isNull(), mBundleArgumentCaptor.capture());
        Intent intent = mIntentArgumentCaptor.getValue();
        Assert.assertNotNull(intent);
        Assert.assertEquals(NfcService.ACTION_RF_FIELD_ON_DETECTED, intent.getAction());
        Bundle bundle = mBundleArgumentCaptor.getValue();
        Assert.assertNotNull(bundle);
        Assert.assertEquals(RF_FIELD_ON_OFF_BROADCAST_OPTIONS, bundle);
        verify(mApplication).sendBroadcast(mIntentArgumentCaptor.capture());
        intent = mIntentArgumentCaptor.getValue();
        Assert.assertEquals(NfcAdapter.ACTION_REQUIRE_UNLOCK_FOR_NFC, intent.getAction());
    }

    @Test
    public void testMsg_Rf_Field_Activated_withBroadcastCoalescingDisabled() {
        Assume.assumeFalse(coalesceRfFieldOnOffBroadcasts());
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_RF_FIELD_ACTIVATED);
        List<String> userlist = new ArrayList<>();
        userlist.add("com.android.nfc");
        mNfcService.mNfcEventInstalledPackages.put(1, userlist);
        mNfcService.mIsSecureNfcEnabled = true;
        mNfcService.mIsRequestUnlockShowed = false;
        when(mNfcInjector.isDeviceLocked()).thenReturn(true);
        handler.handleMessage(msg);
        mLooper.dispatchAll();
        verify(mApplication).sendBroadcastAsUser(mIntentArgumentCaptor.capture(), any(),
                isNull(), isNull());
        Intent intent = mIntentArgumentCaptor.getValue();
        Assert.assertNotNull(intent);
        Assert.assertEquals(NfcService.ACTION_RF_FIELD_ON_DETECTED, intent.getAction());
        verify(mApplication).sendBroadcast(mIntentArgumentCaptor.capture());
        intent = mIntentArgumentCaptor.getValue();
        Assert.assertEquals(NfcAdapter.ACTION_REQUIRE_UNLOCK_FOR_NFC, intent.getAction());
    }

    @Test
    public void testMsg_Rf_Field_Deactivated() {
        Assume.assumeTrue(coalesceRfFieldOnOffBroadcasts());
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_RF_FIELD_DEACTIVATED);
        List<String> userlist = new ArrayList<>();
        userlist.add("com.android.nfc");
        mNfcService.mNfcEventInstalledPackages.put(1, userlist);
        handler.handleMessage(msg);
        mLooper.dispatchAll();
        verify(mApplication).sendBroadcastAsUser(mIntentArgumentCaptor.capture(), any(),
                isNull(), mBundleArgumentCaptor.capture());
        Intent intent = mIntentArgumentCaptor.getValue();
        Assert.assertNotNull(intent);
        Assert.assertEquals(NfcService.ACTION_RF_FIELD_OFF_DETECTED, intent.getAction());
        Bundle bundle = mBundleArgumentCaptor.getValue();
        Assert.assertNotNull(bundle);
        Assert.assertEquals(RF_FIELD_ON_OFF_BROADCAST_OPTIONS, bundle);
    }

    @Test
    public void testMsg_Rf_Field_Deactivated_withBroadcastCoalescingDisabled() {
        Assume.assumeFalse(coalesceRfFieldOnOffBroadcasts());
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_RF_FIELD_DEACTIVATED);
        List<String> userlist = new ArrayList<>();
        userlist.add("com.android.nfc");
        mNfcService.mNfcEventInstalledPackages.put(1, userlist);
        handler.handleMessage(msg);
        mLooper.dispatchAll();
        verify(mApplication).sendBroadcastAsUser(mIntentArgumentCaptor.capture(), any(),
                isNull(), isNull());
        Intent intent = mIntentArgumentCaptor.getValue();
        Assert.assertNotNull(intent);
        Assert.assertEquals(NfcService.ACTION_RF_FIELD_OFF_DETECTED, intent.getAction());
    }

    @Test
    public void testMsg_Tag_Debounce() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_TAG_DEBOUNCE);
        handler.handleMessage(msg);
        Assert.assertEquals(INVALID_NATIVE_HANDLE, mNfcService.mDebounceTagNativeHandle);
    }

    @Test
    public void testMsg_Apply_Screen_State() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_APPLY_SCREEN_STATE);
        msg.obj = ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED;
        handler.handleMessage(msg);
        verify(mDeviceHost).doSetScreenState(anyInt(), anyBoolean());
    }

    @Test
    public void testMsg_Transaction_Event_Cardemulation_Occurred() {
        CardEmulationManager cardEmulationManager = mock(CardEmulationManager.class);
        when(cardEmulationManager.getRegisteredAidCategory(anyString())).
                thenReturn(CardEmulation.CATEGORY_PAYMENT);
        mNfcService.mCardEmulationManager = cardEmulationManager;
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_TRANSACTION_EVENT);
        byte[][] data = {NfcService.hexStringToBytes("F00102030405"),
                NfcService.hexStringToBytes("02FE00010002"),
                NfcService.hexStringToBytes("03000000")};
        msg.obj = data;
        handler.handleMessage(msg);
        ExtendedMockito.verify(() -> NfcStatsLog.write(NfcStatsLog.NFC_CARDEMULATION_OCCURRED,
                NfcStatsLog
                        .NFC_CARDEMULATION_OCCURRED__CATEGORY__OFFHOST_PAYMENT,
                new String(NfcService.hexStringToBytes("03000000"), "UTF-8"),
                -1));
    }

    @Test
    public void testMsg_Transaction_Event() throws RemoteException {
        CardEmulationManager cardEmulationManager = mock(CardEmulationManager.class);
        when(cardEmulationManager.getRegisteredAidCategory(anyString())).
                thenReturn(CardEmulation.CATEGORY_PAYMENT);
        mNfcService.mCardEmulationManager = cardEmulationManager;
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_TRANSACTION_EVENT);
        byte[][] data = {NfcService.hexStringToBytes("F00102030405"),
                NfcService.hexStringToBytes("02FE00010002"),
                NfcService.hexStringToBytes("03000000")};
        msg.obj = data;
        List<String> userlist = new ArrayList<>();
        userlist.add("com.android.nfc");
        mNfcService.mNfcEventInstalledPackages.put(1, userlist);
        ISecureElementService iSecureElementService = mock(ISecureElementService.class);
        IBinder iBinder = mock(IBinder.class);
        when(iSecureElementService.asBinder()).thenReturn(iBinder);
        boolean[] nfcAccess = {true};
        when(iSecureElementService.isNfcEventAllowed(anyString(), any(), any(), anyInt()))
                .thenReturn(nfcAccess);
        when(mNfcInjector.connectToSeService()).thenReturn(iSecureElementService);
        handler.handleMessage(msg);
        verify(mApplication).sendBroadcastAsUser(mIntentArgumentCaptor.capture(),
                any(), any(), any());
    }

    @Test
    public void testMsg_Preferred_Payment_Changed()
            throws RemoteException, PackageManager.NameNotFoundException {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_PREFERRED_PAYMENT_CHANGED);
        msg.obj = 1;
        List<String> packagesList = new ArrayList<>();
        packagesList.add("com.android.nfc");
        packagesList.add("com.sample.nfc");
        mNfcService.mNfcPreferredPaymentChangedInstalledPackages.put(1, packagesList);
        ISecureElementService iSecureElementService = mock(ISecureElementService.class);
        IBinder iBinder = mock(IBinder.class);
        when(iSecureElementService.asBinder()).thenReturn(iBinder);
        when(iSecureElementService.getReaders()).thenReturn(new String[]{"com.android.nfc"});
        when(iSecureElementService.isNfcEventAllowed(anyString(), isNull(), any(), anyInt()))
                .thenReturn(new boolean[]{true});
        boolean[] nfcAccess = {true};
        when(iSecureElementService.isNfcEventAllowed(anyString(), any(), any(), anyInt()))
                .thenReturn(nfcAccess);
        when(mNfcInjector.connectToSeService()).thenReturn(iSecureElementService);
        PackageInfo info = mock(PackageInfo.class);
        ApplicationInfo applicationInfo = mock(ApplicationInfo.class);
        applicationInfo.flags = 1;
        info.applicationInfo = applicationInfo;
        when(mPackageManager.getPackageInfo(anyString(), anyInt())).thenReturn(info);
        handler.handleMessage(msg);
        verify(mApplication, times(2))
                .sendBroadcastAsUser(mIntentArgumentCaptor.capture(), any());
        Intent intent = mIntentArgumentCaptor.getValue();
        Assert.assertEquals(ACTION_PREFERRED_PAYMENT_CHANGED, intent.getAction());
    }

    @Test
    public void testMSG_NDEF_TAG() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_NDEF_TAG);
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.getConnectedTechnology()).thenReturn(TagTechnology.NDEF);
        NdefMessage ndefMessage = mock(NdefMessage.class);
        when(tagEndpoint.findAndReadNdef()).thenReturn(ndefMessage);
        msg.obj = tagEndpoint;
        handler.handleMessage(msg);
        verify(tagEndpoint, atLeastOnce()).startPresenceChecking(anyInt(), any());
    }

    @Test
    public void testMsg_Ndef_Tag_Wlc_Enabled() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_NDEF_TAG);
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.getConnectedTechnology()).thenReturn(TagTechnology.NDEF);
        when(tagEndpoint.getUid()).thenReturn(NfcService
                .hexStringToBytes("0x040000010100000000000000"));
        when(tagEndpoint.getTechList()).thenReturn(new int[]{Ndef.NDEF});
        when(tagEndpoint.getTechExtras()).thenReturn(new Bundle[]{});
        when(tagEndpoint.getHandle()).thenReturn(1);
        NdefMessage ndefMessage = mock(NdefMessage.class);
        when(tagEndpoint.findAndReadNdef()).thenReturn(ndefMessage);
        msg.obj = tagEndpoint;
        mNfcService.mIsWlcEnabled = true;
        mNfcService.mIsRWCapable = true;
        handler.handleMessage(msg);
        verify(tagEndpoint, atLeastOnce()).startPresenceChecking(anyInt(), any());
        ArgumentCaptor<Tag> tagCaptor = ArgumentCaptor
                .forClass(Tag.class);
        verify(mNfcDispatcher).dispatchTag(tagCaptor.capture());
        Tag tag = tagCaptor.getValue();
        Assert.assertNotNull(tag);
        Assert.assertEquals("android.nfc.tech.Ndef", tag.getTechList()[0]);
    }

    @Test
    public void testMsg_Clear_Routing_Table() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_CLEAR_ROUTING_TABLE);
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        msg.obj = 1;
        handler.handleMessage(msg);
        ArgumentCaptor<Integer> flagCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mDeviceHost).clearRoutingEntry(flagCaptor.capture());
        int flag = flagCaptor.getValue();
        Assert.assertEquals(1, flag);
    }

    @Test
    public void testMsg_Update_Isodep_Protocol_Route() {
        Handler handler = mNfcService.getHandler();
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_UPDATE_ISODEP_PROTOCOL_ROUTE);
        msg.obj = 1;
        handler.handleMessage(msg);
        ArgumentCaptor<Integer> flagCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mDeviceHost).setIsoDepProtocolRoute(flagCaptor.capture());
        int flag = flagCaptor.getValue();
        Assert.assertEquals(1, flag);
    }

    @Test
    public void testMsg_Update_Technology_Abf_Route() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        Message msg = handler.obtainMessage(NfcService.MSG_UPDATE_TECHNOLOGY_ABF_ROUTE);
        msg.arg1 = 1;
        msg.arg2 = 2;
        handler.handleMessage(msg);
        ArgumentCaptor<Integer> flagCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> flagCaptor2 = ArgumentCaptor.forClass(Integer.class);
        verify(mDeviceHost).setTechnologyABFRoute(flagCaptor.capture(), flagCaptor2.capture());
        int flag = flagCaptor.getValue();
        Assert.assertEquals(1, flag);
        int flag2 = flagCaptor2.getValue();
        Assert.assertEquals(2, flag2);
    }

    @Test
    public void testDirectBootAware_migrationForUser0() throws Exception {
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        // Ensure migration is not marked as complete
        when(mPreferences.getBoolean(eq(NfcService.PREF_MIGRATE_TO_DE_COMPLETE), anyBoolean()))
                .thenReturn(false);

        mNfcService = new NfcService(mApplication, mNfcInjector);
        mLooper.dispatchAll();
        verify(mNfcInjector).makeDeviceHost(mDeviceHostListener.capture());
        verify(mApplication).registerReceiverForAllUsers(
                mGlobalReceiver.capture(),
                argThat(intent -> intent.hasAction(Intent.ACTION_USER_UNLOCKED)), any(), any());
        verify(mDeviceHost).initialize();

        clearInvocations(mApplication, mPreferences, mPreferencesEditor);
        Context ceContext = mock(Context.class);
        when(mApplication.createCredentialProtectedStorageContext()).thenReturn(ceContext);
        when(ceContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mPreferences);
        doAnswer((Answer<Map<String, ?>>) invocation -> {
            Map<String, Object> prefMap = Map.of(PREF_NFC_ON, true);
            return prefMap;
        }).when(mPreferences).getAll();
        when(mApplication.moveSharedPreferencesFrom(ceContext, NfcService.PREF)).thenReturn(true);
        when(mApplication.moveSharedPreferencesFrom(ceContext, NfcService.PREF_TAG_APP_LIST))
                .thenReturn(true);

        // Create an intent for the primary user (user 0)
        Intent intent = new Intent(Intent.ACTION_USER_UNLOCKED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, 0);
        mGlobalReceiver.getValue().onReceive(mApplication, intent);

        // Verify that migration logic was triggered
        verify(mApplication).moveSharedPreferencesFrom(ceContext, NfcService.PREF);
        verify(mApplication).getSharedPreferences(eq(NfcService.PREF), anyInt());
        verify(mPreferences).edit();
        verify(mPreferencesEditor).putBoolean(NfcService.PREF_MIGRATE_TO_DE_COMPLETE, true);
        verify(mPreferencesEditor).apply();
    }

    @Test
    public void testDirectBootAware_noMigrationForSecondaryUser() throws Exception {
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        // Ensure migration is not marked as complete
        when(mPreferences.getBoolean(eq(NfcService.PREF_MIGRATE_TO_DE_COMPLETE), anyBoolean()))
                .thenReturn(false);

        mNfcService = new NfcService(mApplication, mNfcInjector);
        mLooper.dispatchAll();
        verify(mNfcInjector).makeDeviceHost(mDeviceHostListener.capture());
        verify(mApplication).registerReceiverForAllUsers(
                mGlobalReceiver.capture(),
                argThat(intent -> intent.hasAction(Intent.ACTION_USER_UNLOCKED)), any(), any());
        verify(mDeviceHost).initialize();

        clearInvocations(mApplication, mPreferences, mPreferencesEditor);

        // Create an intent for a secondary user
        Intent intent = new Intent(Intent.ACTION_USER_UNLOCKED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, 10); // Non-primary user
        mGlobalReceiver.getValue().onReceive(mApplication, intent);

        // Verify migration logic is NOT triggered
        verify(mApplication, never()).moveSharedPreferencesFrom(any(), anyString());
        verify(mPreferencesEditor, never()).putBoolean(
                eq(NfcService.PREF_MIGRATE_TO_DE_COMPLETE), anyBoolean());
    }

    @Test
    public void testDirectBootAware_migrationSkippedIfComplete() throws Exception {
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        // Setup: migration is already complete
        when(mPreferences.getBoolean(eq(NfcService.PREF_MIGRATE_TO_DE_COMPLETE), anyBoolean()))
                .thenReturn(true);

        mNfcService = new NfcService(mApplication, mNfcInjector);
        mLooper.dispatchAll();
        verify(mNfcInjector).makeDeviceHost(mDeviceHostListener.capture());
        verify(mApplication).registerReceiverForAllUsers(
                mGlobalReceiver.capture(),
                argThat(intent -> intent.hasAction(Intent.ACTION_USER_UNLOCKED)), any(), any());
        verify(mDeviceHost).initialize();

        clearInvocations(mApplication, mPreferences, mPreferencesEditor);

        // Create an intent for the primary user
        Intent intent = new Intent(Intent.ACTION_USER_UNLOCKED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, 0);
        mGlobalReceiver.getValue().onReceive(mApplication, intent);

        // Verify migration logic is NOT triggered
        verify(mApplication, never()).moveSharedPreferencesFrom(any(), anyString());
        verify(mPreferencesEditor, never()).putBoolean(
                eq(NfcService.PREF_MIGRATE_TO_DE_COMPLETE), anyBoolean());
    }

    @Test
    public void testAllowOemOnTagDispatchCallback() throws Exception {
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        INfcOemExtensionCallback callback = mock(INfcOemExtensionCallback.class);
        when(callback.asBinder()).thenReturn(mock(IBinder.class));
        mNfcService.mNfcAdapter.registerOemExtensionCallback(callback);
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_NDEF_TAG);
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.getConnectedTechnology()).thenReturn(TagTechnology.NDEF);
        when(tagEndpoint.getUid()).thenReturn(NfcService
                .hexStringToBytes("0x040000010100000000000000"));
        when(tagEndpoint.getTechList()).thenReturn(new int[]{Ndef.NDEF});
        when(tagEndpoint.getTechExtras()).thenReturn(new Bundle[]{});
        when(tagEndpoint.getHandle()).thenReturn(1);
        NdefMessage ndefMessage = mock(NdefMessage.class);
        when(tagEndpoint.findAndReadNdef()).thenReturn(ndefMessage);
        msg.obj = tagEndpoint;
        mNfcService.mIsWlcEnabled = true;
        mNfcService.mIsRWCapable = true;
        handler.handleMessage(msg);
        verify(tagEndpoint, atLeastOnce()).startPresenceChecking(anyInt(), any());
        ArgumentCaptor<Tag> tagCaptor = ArgumentCaptor
                .forClass(Tag.class);
        verify(mNfcDispatcher).dispatchTag(tagCaptor.capture());
        Tag tag = tagCaptor.getValue();
        Assert.assertNotNull(tag);
        Assert.assertEquals("android.nfc.tech.Ndef", tag.getTechList()[0]);

        doAnswer(new Answer() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                ResultReceiver r = invocation.getArgument(0);
                r.send(1, null);
                return null;
            }
        }).when(callback).onTagDispatch(any(ResultReceiver.class));
        mContentObserver.onChange(true);
        ArgumentCaptor<ResultReceiver> receiverArgumentCaptor = ArgumentCaptor
                .forClass(ResultReceiver.class);
        verify(callback).onTagDispatch(receiverArgumentCaptor.capture());
        ResultReceiver resultReceiver = receiverArgumentCaptor.getValue();
        Assert.assertNotNull(resultReceiver);
    }

    @Test
    public void testAllowOemOnNdefReadCallback() throws Exception {
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        INfcOemExtensionCallback callback = mock(INfcOemExtensionCallback.class);
        when(callback.asBinder()).thenReturn(mock(IBinder.class));
        mNfcService.mNfcAdapter.registerOemExtensionCallback(callback);
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_NDEF_TAG);
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.getConnectedTechnology()).thenReturn(TagTechnology.NDEF);
        when(tagEndpoint.getUid()).thenReturn(NfcService
                .hexStringToBytes("0x040000010100000000000000"));
        when(tagEndpoint.getTechList()).thenReturn(new int[]{Ndef.NDEF});
        when(tagEndpoint.getTechExtras()).thenReturn(new Bundle[]{});
        when(tagEndpoint.getHandle()).thenReturn(1);
        NdefMessage ndefMessage = mock(NdefMessage.class);
        when(tagEndpoint.findAndReadNdef()).thenReturn(ndefMessage);
        msg.obj = tagEndpoint;
        mNfcService.mIsWlcEnabled = true;
        mNfcService.mIsRWCapable = true;
        handler.handleMessage(msg);
        verify(tagEndpoint, atLeastOnce()).startPresenceChecking(anyInt(), any());
        ArgumentCaptor<Tag> tagCaptor = ArgumentCaptor
                .forClass(Tag.class);
        verify(mNfcDispatcher).dispatchTag(tagCaptor.capture());
        Tag tag = tagCaptor.getValue();
        Assert.assertNotNull(tag);
        Assert.assertEquals("android.nfc.tech.Ndef", tag.getTechList()[0]);

        doAnswer(new Answer() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                ResultReceiver r = invocation.getArgument(0);
                r.send(1, null);
                return null;
            }
        }).when(callback).onNdefRead(any(ResultReceiver.class));
        mContentObserver.onChange(true);
        ArgumentCaptor<ResultReceiver> receiverArgumentCaptor = ArgumentCaptor
                .forClass(ResultReceiver.class);
        verify(callback).onNdefRead(receiverArgumentCaptor.capture());
        ResultReceiver resultReceiver = receiverArgumentCaptor.getValue();
        Assert.assertNotNull(resultReceiver);
    }

    @Test
    public void testAllowOemOnApplyRoutingCallback() throws Exception {
        INfcOemExtensionCallback callback = mock(INfcOemExtensionCallback.class);
        when(callback.asBinder()).thenReturn(mock(IBinder.class));
        mNfcService.mNfcAdapter.registerOemExtensionCallback(callback);
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        INfcUnlockHandler binder = mock(INfcUnlockHandler.class);
        mNfcService.mNfcAdapter.removeNfcUnlockHandler(binder);

        doAnswer(new Answer() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                ResultReceiver r = invocation.getArgument(0);
                r.send(1, null);
                return null;
            }
        }).when(callback).onApplyRouting(any(ResultReceiver.class));
        mContentObserver.onChange(true);
        ArgumentCaptor<ResultReceiver> receiverArgumentCaptor = ArgumentCaptor
                .forClass(ResultReceiver.class);
        verify(callback).onApplyRouting(receiverArgumentCaptor.capture());
        ResultReceiver resultReceiver = receiverArgumentCaptor.getValue();
        Assert.assertNotNull(resultReceiver);
    }

    @Test
    public void testThermalStatusChangeListener() {
        Assert.assertNotNull(mPowerManager);
        ArgumentCaptor<PowerManager.OnThermalStatusChangedListener> argumentCaptor =
                ArgumentCaptor.forClass(PowerManager.OnThermalStatusChangedListener.class);
        verify(mPowerManager).addThermalStatusListener(any(), argumentCaptor.capture());
        PowerManager.OnThermalStatusChangedListener changedListener =
                argumentCaptor.getValue();
        Assert.assertNotNull(changedListener);
        changedListener.onThermalStatusChanged(PowerManager.THERMAL_STATUS_MODERATE);
        changedListener.onThermalStatusChanged(PowerManager.THERMAL_STATUS_SEVERE);
        changedListener.onThermalStatusChanged(PowerManager.THERMAL_STATUS_CRITICAL);
        changedListener.onThermalStatusChanged(0);
    }

    @Test
    public void testClearRoutingTable() {
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        mNfcService.clearRoutingTable(1);
        mLooper.dispatchAll();
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(mDeviceHost).clearRoutingEntry(captor.capture());
        int flag = captor.getValue();
        Assert.assertEquals(1, flag);
    }

    @Test
    public void testDeregisterT3tIdentifier() {
        NfcDiscoveryParameters nfcDiscoveryParameters = mock(NfcDiscoveryParameters.class);
        when(nfcDiscoveryParameters.shouldEnableDiscovery()).thenReturn(true);
        mNfcService.mCurrentDiscoveryParameters = nfcDiscoveryParameters;
        mNfcService.deregisterT3tIdentifier("02FE", "02FEC1DE32456789", "F0010203");
        mLooper.dispatchAll();
        verify(mDeviceHost).disableDiscovery();
        ArgumentCaptor<byte[]> t3tIdentifierByteArray = ArgumentCaptor.forClass(byte[].class);
        verify(mDeviceHost).deregisterT3tIdentifier(t3tIdentifierByteArray.capture());
        byte[] data = t3tIdentifierByteArray.getValue();
        Assert.assertNotNull(data);
        String msg = new String(data, StandardCharsets.UTF_8);
        Assert.assertNotNull(msg);
        verify(mDeviceHost).enableDiscovery(any(), anyBoolean());
    }

    @Test
    public void testFindAndRemoveObject() {
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.getHandle()).thenReturn(1);
        mNfcService.registerTagObject(tagEndpoint);
        DeviceHost.TagEndpoint device = (DeviceHost.TagEndpoint) mNfcService.mObjectMap.get(1);
        Assert.assertNotNull(device);
        Assert.assertEquals(tagEndpoint, device);
        mNfcService.findAndRemoveObject(1);
        Object obj = mNfcService.mObjectMap.get(1);
        Assert.assertNull(obj);
    }

    @Test
    public void testDisplayManagerCallback() {
        ArgumentCaptor<DisplayManager.DisplayListener> displayListenerArgumentCaptor =
                ArgumentCaptor.forClass(DisplayManager.DisplayListener.class);
        ArgumentCaptor<NfcService.NfcServiceHandler> nfcServiceHandlerArgumentCaptor =
                ArgumentCaptor.forClass(NfcService.NfcServiceHandler.class);
        verify(mDisplayManager).registerDisplayListener(displayListenerArgumentCaptor.capture(),
                nfcServiceHandlerArgumentCaptor.capture());
        DisplayManager.DisplayListener displayListener = displayListenerArgumentCaptor.getValue();
        Assert.assertNotNull(displayListener);
        NfcService.NfcServiceHandler handler = nfcServiceHandlerArgumentCaptor.getValue();
        Assert.assertNotNull(handler);
        displayListener.onDisplayAdded(Display.DEFAULT_DISPLAY);
        displayListener.onDisplayRemoved(Display.DEFAULT_DISPLAY);
        mNfcService.mIsWlcCapable = false;
        when(mScreenStateHelper.checkScreenState(anyBoolean()))
                .thenReturn(ScreenStateHelper.SCREEN_STATE_ON_LOCKED);
        mNfcService.mScreenState = ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED;
        displayListener.onDisplayChanged(Display.DEFAULT_DISPLAY);
        mLooper.dispatchAll();
        Assert.assertFalse(handler.hasMessages(NfcService.MSG_DELAY_POLLING));
        Assert.assertFalse(mNfcService.mIsRequestUnlockShowed);
        verify(mDeviceHost).doSetScreenState(anyInt(), anyBoolean());
    }

    @Test
    public void testThermalStatusListener() {
        Assert.assertNotNull(mPowerManager);
        ArgumentCaptor<PowerManager.OnThermalStatusChangedListener> argumentCaptor =
                ArgumentCaptor.forClass(PowerManager.OnThermalStatusChangedListener.class);
        verify(mPowerManager).addThermalStatusListener(any(), argumentCaptor.capture());
        PowerManager.OnThermalStatusChangedListener thermalStatusChangedListener =
                argumentCaptor.getValue();
        Assert.assertNotNull(thermalStatusChangedListener);
        thermalStatusChangedListener.onThermalStatusChanged(PowerManager.THERMAL_STATUS_MODERATE);
        thermalStatusChangedListener.onThermalStatusChanged(PowerManager.THERMAL_STATUS_SEVERE);
        thermalStatusChangedListener.onThermalStatusChanged(PowerManager.THERMAL_STATUS_CRITICAL);
        thermalStatusChangedListener.onThermalStatusChanged(PowerManager.THERMAL_STATUS_SHUTDOWN);
    }

    @Test
    public void testGetAppName() throws RemoteException, PackageManager.NameNotFoundException {
        String[] packages = {"com.android.test1"};
        when(mResources.getStringArray(R.array.nfc_allow_list)).thenReturn(packages);
        mNfcService.mNfcAdapter.enable(PKG_NAME);
        ArgumentCaptor<String> stringArgumentCaptor = ArgumentCaptor.forClass(String.class);
        verify(mPackageManager).getApplicationInfoAsUser(stringArgumentCaptor.capture(), anyInt(),
                any());
        assertThat(PKG_NAME).isEqualTo(stringArgumentCaptor.getValue());
        verify(mPackageManager, atLeastOnce()).getApplicationLabel(any());
    }

    @Test
    public void testFindObject() {
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.getHandle()).thenReturn(1);
        mNfcService.registerTagObject(tagEndpoint);
        DeviceHost.TagEndpoint device = (DeviceHost.TagEndpoint) mNfcService.mObjectMap.get(1);
        Assert.assertNotNull(device);
        Assert.assertEquals(tagEndpoint, device);
        Object obj = mNfcService.findObject(1);
        Assert.assertNotNull(obj);
        Object object = mNfcService.mObjectMap.get(1);
        Assert.assertNotNull(object);
        assertThat(obj).isEqualTo(object);
    }

    @Test
    public void testGetEnabledUserIds() {
        when(mPreferences.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        Assert.assertTrue(mNfcService.getNfcOnSetting());
        when(mNfcInjector.isSatelliteModeOn()).thenReturn(false);
        when(mUserRestrictions.getBoolean(UserManager.DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO))
                .thenReturn(false);
        NfcService.sIsNfcRestore = true;
        UserHandle uh = mock(UserHandle.class);
        when(uh.getIdentifier()).thenReturn(1);
        List<UserHandle> luh = new ArrayList<>();
        luh.add(uh);
        when(mUserManager.getEnabledProfiles()).thenReturn(luh);
        mNfcService.enableNfc();
        verify(mPreferences).edit();
        verify(mPreferencesEditor).putBoolean(PREF_NFC_ON, true);
        verify(mPreferencesEditor, atLeastOnce()).apply();
        verify(mBackupManager).dataChanged();
        mLooper.dispatchAll();
        verify(mUserManager, atLeastOnce()).getEnabledProfiles();
    }

    @Test
    public void testGetLfT3tMax() {
        int lfT3t = mNfcService.getLfT3tMax();
        assertThat(lfT3t).isEqualTo(0);
        when(mDeviceHost.getLfT3tMax()).thenReturn(100);
        lfT3t = mNfcService.getLfT3tMax();
        assertThat(lfT3t).isEqualTo(100);
        verify(mDeviceHost, atLeastOnce()).getLfT3tMax();
    }

    @Test
    public void testGetNfcPollTech() {
        int pollTech = mNfcService.getNfcPollTech();
        assertThat(pollTech).isEqualTo(0);
        when(mPreferences.getInt(NfcService.PREF_POLL_TECH, NfcService.DEFAULT_POLL_TECH))
                .thenReturn(NfcService.DEFAULT_LISTEN_TECH);
        mNfcService.mIsReaderOptionEnabled = true;
        pollTech = mNfcService.getNfcPollTech();
        assertThat(pollTech).isEqualTo(0xf);
        verify(mPreferences, atLeastOnce()).getInt(anyString(), anyInt());
    }

    @Test
    public void testIsPackageInstalled() {
        when(mPreferences.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        String jsonString = "{}";
        when(mPreferences.getString(anyString(), anyString())).thenReturn(jsonString);
        Assert.assertTrue(mNfcService.getNfcOnSetting());
        when(mNfcInjector.isSatelliteModeOn()).thenReturn(false);
        when(mUserRestrictions.getBoolean(UserManager.DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO))
                .thenReturn(false);
        NfcService.sIsNfcRestore = true;
        UserHandle uh = mock(UserHandle.class);
        when(uh.getIdentifier()).thenReturn(1);
        List<UserHandle> luh = new ArrayList<>();
        luh.add(uh);
        when(mUserManager.getEnabledProfiles()).thenReturn(luh);
        mNfcService.enableNfc();
        verify(mPreferences).edit();
        verify(mPreferencesEditor).putBoolean(PREF_NFC_ON, true);
        verify(mPreferencesEditor, atLeastOnce()).apply();
        verify(mBackupManager).dataChanged();
        mLooper.dispatchAll();
        verify(mUserManager, atLeastOnce()).getEnabledProfiles();
        verify(mApplication, atLeastOnce()).createContextAsUser(any(), anyInt());
    }

    @Test
    public void testIsPackageInstalled_createContextFails_returnsFalse() throws Exception {
        // This test verifies that if creating a user context fails with an IllegalStateException,
        // isPackageInstalled correctly handles it and returns false.

        // Arrange
        UserHandle userHandle = UserHandle.of(10);
        // Mock getEnabledProfiles to return our test user, so initTagAppPrefList processes it.
        when(mUserManager.getEnabledProfiles()).thenReturn(Collections.singletonList(userHandle));

        // Mock createContextAsUser to throw IllegalStateException for our test user.
        // This simulates a failure to create the user's context (e.g., user is stopping).
        when(mApplication.createContextAsUser(eq(userHandle), anyInt()))
                .thenThrow(new IllegalStateException("Test Exception: User context not available"));

        // Get the receiver that handles profile changes.
        BroadcastReceiver receiver = mManagedProfileReceiver.getValue();
        Intent intent = new Intent(Intent.ACTION_MANAGED_PROFILE_ADDED);
        intent.putExtra(Intent.EXTRA_USER, userHandle);

        // Act
        // Trigger the receiver to call initTagAppPrefList, which in turn calls isPackageInstalled.
        receiver.onReceive(mApplication, intent);
        mLooper.dispatchAll();

        // Assert
        // The call to isPackageInstalled should have failed and returned false due to the
        // exception.
        // As a result, no packages from the blocklist should be added to the preferences for this
        // user.
        Map<String, Boolean> prefList = mNfcService.mTagAppPrefList.get(10);

        // The preference map for the user should exist but be empty.
        assertThat(prefList).isNotNull();
        assertThat(prefList).isEmpty();
    }

    @Test
    public void testIsSecureNfcEnabled() {
        mNfcService.mIsSecureNfcEnabled = true;
        boolean isSecureNfcEnabled = mNfcService.isSecureNfcEnabled();
        assertThat(isSecureNfcEnabled).isTrue();
        mNfcService.mIsSecureNfcEnabled = false;
        isSecureNfcEnabled = mNfcService.isSecureNfcEnabled();
        assertThat(isSecureNfcEnabled).isFalse();
    }

    @Test
    public void testIsTagPresent() throws RemoteException {
        boolean isTagPresent = mNfcService.mNfcAdapter.isTagPresent();
        assertThat(isTagPresent).isFalse();
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.isPresent()).thenReturn(true);
        mNfcService.mObjectMap.put(1, tagEndpoint);
        isTagPresent = mNfcService.mNfcAdapter.isTagPresent();
        assertThat(isTagPresent).isTrue();

    }

    @Test
    public void testOnObserveModeStateChanged() {
        mNfcService.onObserveModeStateChanged(true);
        mLooper.dispatchAll();
        verify(mCardEmulationManager, atLeastOnce()).onObserveModeStateChange(anyBoolean());
        mNfcService.onObserveModeStateChanged(false);
        mLooper.dispatchAll();
        verify(mCardEmulationManager, atLeastOnce()).onObserveModeStateChange(anyBoolean());
    }

    @Test
    public void testOnPollingLoopDetected() {
        PollingFrame pollingFrame = mock(PollingFrame.class);
        List<PollingFrame> frames = new ArrayList<>();
        frames.add(pollingFrame);
        mNfcService.onPollingLoopDetected(frames);
        mLooper.dispatchAll();
        ArgumentCaptor<List<PollingFrame>> listArgumentCaptor = ArgumentCaptor.forClass(List.class);
        verify(mCardEmulationManager).onPollingLoopDetected(listArgumentCaptor.capture());
        assertThat(frames).isEqualTo(listArgumentCaptor.getValue());
        mNfcService.onPollingLoopDetected(frames);
        mLooper.dispatchAll();
        verify(mCardEmulationManager, atLeastOnce()).onPollingLoopDetected(listArgumentCaptor.capture());
        assertThat(frames).isEqualTo(listArgumentCaptor.getValue());
    }

    @Test
    public void testOnVendorSpecificEvent() throws RemoteException {
        INfcVendorNciCallback callback = mock(INfcVendorNciCallback.class);
        when(callback.asBinder()).thenReturn(mock(IBinder.class));
        mNfcService.mNfcAdapter.registerVendorExtensionCallback(callback);
        verify(mDeviceHost).enableVendorNciNotifications(true);
        mNfcService.onVendorSpecificEvent(1, 2, "test".getBytes());
        mLooper.dispatchAll();
        verify(callback).onVendorNotificationReceived(anyInt(), anyInt(), any());
    }

    @Test
    public void testOnHostCardEmulationActivated() throws RemoteException {
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        INfcOemExtensionCallback callback = mock(INfcOemExtensionCallback.class);
        when(callback.asBinder()).thenReturn(mock(IBinder.class));
        mNfcService.mNfcAdapter.registerOemExtensionCallback(callback);
        verify(callback).onCardEmulationActivated(anyBoolean());
        when(android.nfc.Flags.nfcPersistLog()).thenReturn(true);
        mNfcService.onHostCardEmulationActivated(1);
        verify(mCardEmulationManager).onHostCardEmulationActivated(anyInt());
        verify(mNfcEventLog, times(2)).logEvent(any());
    }

    @Test
    public void testOnHostCardEmulationDeactivated()  throws RemoteException {
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        INfcOemExtensionCallback callback = mock(INfcOemExtensionCallback.class);
        when(callback.asBinder()).thenReturn(mock(IBinder.class));
        mNfcService.mNfcAdapter.registerOemExtensionCallback(callback);
        verify(callback).onCardEmulationActivated(false);
        when(android.nfc.Flags.nfcPersistLog()).thenReturn(true);
        mNfcService.onHostCardEmulationDeactivated(1);
        verify(mCardEmulationManager).onHostCardEmulationDeactivated(anyInt());
        verify(mNfcEventLog, times(2)).logEvent(any());
    }

    @Test
    public void testOnEeUpdated() {
        mNfcService.onEeUpdated();
        mLooper.dispatchAll();
        Assert.assertEquals(0, mNfcService.mScreenState);
    }

    @Test
    public void testOnHwErrorReported() {
        when(mPreferences.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        Assert.assertTrue(mNfcService.getNfcOnSetting());
        when(mNfcInjector.isSatelliteModeOn()).thenReturn(false);
        when(mUserRestrictions.getBoolean(UserManager.DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO))
                .thenReturn(false);
        NfcService.sIsNfcRestore = true;
        mNfcService.mState.set(NfcAdapter.STATE_OFF);
        mNfcService.onHwErrorReported();
        verify(mApplication).unregisterReceiver(any());
        assertThat(mNfcService.mIsRecovering).isTrue();
        mLooper.dispatchAll();
        verify(mUserManager, atLeastOnce()).getEnabledProfiles();
    }

    @Test
    public void testOnNfcTransactionEvent() throws RemoteException {
        ISecureElementService iSecureElementService = mock(ISecureElementService.class);
        IBinder iBinder = mock(IBinder.class);
        when(iSecureElementService.asBinder()).thenReturn(iBinder);
        boolean[] nfcAccess = {true};
        when(iSecureElementService.isNfcEventAllowed(anyString(), any(), any(), anyInt()))
                .thenReturn(nfcAccess);
        when(mNfcInjector.connectToSeService()).thenReturn(iSecureElementService);
        List<String> packages = new ArrayList<>();
        packages.add("com.android.test");
        mNfcService.mNfcEventInstalledPackages.put(1, packages);
        when(mCardEmulationManager.getRegisteredAidCategory(anyString()))
                .thenReturn(CardEmulation.CATEGORY_PAYMENT);
        byte[] aid = { 0x0A, 0x00, 0x00, 0x00 };
        byte[] data = { 0x12, 0x34, 0x56, 0x78, 0x78 };
        mNfcService.onNfcTransactionEvent(aid, data, "SecureElement1");
        mLooper.dispatchAll();
        verify(mCardEmulationManager).onOffHostAidTransaction();
        verify(mPackageManager).queryBroadcastReceiversAsUser(any(), anyInt(), any());
        verify(mApplication).sendBroadcastAsUser(any(), any(), isNull(), any());
    }

    @Test
    public void testOnRemoteEndpointDiscovered() {
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        NfcService.ReaderModeParams readerModeParams = mock(NfcService.ReaderModeParams.class);
        readerModeParams.presenceCheckDelay = 1;
        readerModeParams.flags = 129;
        mNfcService.mReaderModeParams = readerModeParams;
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        mNfcService.onRemoteEndpointDiscovered(tagEndpoint);
        mLooper.dispatchAll();
        verify(tagEndpoint).startPresenceChecking(anyInt(), any());
    }

    @EnableFlags(com.android.nfc.module.nonexported.flags.Flags.FLAG_COALESCE_RF_FIELD_ON_OFF_BROADCASTS)
    @Test
    public void testOnRemoteFieldActivated() throws RemoteException {
        createNfcServiceWithoutStatsdUtils();
        List<String> userlist = new ArrayList<>();
        userlist.add("com.android.nfc");
        mNfcService.mIsSecureNfcEnabled = true;
        mNfcService.mIsRequestUnlockShowed = false;
        when(mNfcInjector.isDeviceLocked()).thenReturn(true);
        mNfcService.mNfcEventInstalledPackages.put(1, userlist);
        INfcOemExtensionCallback callback = mock(INfcOemExtensionCallback.class);
        when(callback.asBinder()).thenReturn(mock(IBinder.class));
        mNfcService.mNfcAdapter.registerOemExtensionCallback(callback);
        when(android.nfc.Flags.nfcPersistLog()).thenReturn(true);
        mNfcService.onRemoteFieldActivated();
        verify(callback, atLeastOnce()).onRfFieldDetected(anyBoolean());
        mLooper.dispatchAll();
        verify(mCardEmulationManager).onFieldChangeDetected(anyBoolean());
        verify(mApplication).sendBroadcastAsUser(any(), any(), isNull(), any());
        verify(mApplication).sendBroadcast(any());
        verify(mStatsdUtils).logFieldChanged(anyBoolean(), anyInt());
        verify(mNfcEventLog, atLeast(2)).logEvent(any());
    }

    @Test
    public void testOnRemoteFieldActivated_withBroadcastCoalesciingDisabled()
            throws RemoteException {
        Assume.assumeFalse(coalesceRfFieldOnOffBroadcasts());
        createNfcServiceWithoutStatsdUtils();
        List<String> userlist = new ArrayList<>();
        userlist.add("com.android.nfc");
        mNfcService.mIsSecureNfcEnabled = true;
        mNfcService.mIsRequestUnlockShowed = false;
        when(mNfcInjector.isDeviceLocked()).thenReturn(true);
        mNfcService.mNfcEventInstalledPackages.put(1, userlist);
        INfcOemExtensionCallback callback = mock(INfcOemExtensionCallback.class);
        when(callback.asBinder()).thenReturn(mock(IBinder.class));
        mNfcService.mNfcAdapter.registerOemExtensionCallback(callback);
        when(android.nfc.Flags.nfcPersistLog()).thenReturn(true);
        mNfcService.onRemoteFieldActivated();
        verify(callback, atLeastOnce()).onRfFieldDetected(anyBoolean());
        mLooper.dispatchAll();
        verify(mCardEmulationManager).onFieldChangeDetected(anyBoolean());
        verify(mApplication).sendBroadcastAsUser(any(), any(), isNull(), isNull());
        verify(mApplication).sendBroadcast(any());
        verify(mStatsdUtils).logFieldChanged(anyBoolean(), anyInt());
        verify(mNfcEventLog, atLeast(2)).logEvent(any());
    }

    @Test
    public void testOnRemoteFieldDeactivated() throws RemoteException {
        Assume.assumeTrue(coalesceRfFieldOnOffBroadcasts());
        createNfcServiceWithoutStatsdUtils();
        List<String> userlist = new ArrayList<>();
        userlist.add("com.android.nfc");
        mNfcService.mIsSecureNfcEnabled = true;
        mNfcService.mIsRequestUnlockShowed = false;
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);
        mNfcService.mNfcEventInstalledPackages.put(1, userlist);
        INfcOemExtensionCallback callback = mock(INfcOemExtensionCallback.class);
        when(callback.asBinder()).thenReturn(mock(IBinder.class));
        mNfcService.mNfcAdapter.registerOemExtensionCallback(callback);
        when(android.nfc.Flags.nfcPersistLog()).thenReturn(true);
        mNfcService.onRemoteFieldDeactivated();
        verify(callback, atLeastOnce()).onRfFieldDetected(anyBoolean());
        mClock.mOffset += 60;
        mLooper.dispatchAll();
        verify(mCardEmulationManager).onFieldChangeDetected(anyBoolean());
        verify(mApplication).sendBroadcastAsUser(any(), any(), isNull(), any());
        verify(mStatsdUtils).logFieldChanged(anyBoolean(), anyInt());
        verify(mNfcEventLog, atLeast(2)).logEvent(any());
    }

    @Test
    public void testOnRemoteFieldDeactivated_withBroadcastCoalesciingDisabled()
            throws RemoteException {
        Assume.assumeFalse(coalesceRfFieldOnOffBroadcasts());
        createNfcServiceWithoutStatsdUtils();
        List<String> userlist = new ArrayList<>();
        userlist.add("com.android.nfc");
        mNfcService.mIsSecureNfcEnabled = true;
        mNfcService.mIsRequestUnlockShowed = false;
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);
        mNfcService.mNfcEventInstalledPackages.put(1, userlist);
        INfcOemExtensionCallback callback = mock(INfcOemExtensionCallback.class);
        when(callback.asBinder()).thenReturn(mock(IBinder.class));
        mNfcService.mNfcAdapter.registerOemExtensionCallback(callback);
        when(android.nfc.Flags.nfcPersistLog()).thenReturn(true);
        mNfcService.onRemoteFieldDeactivated();
        verify(callback, atLeastOnce()).onRfFieldDetected(anyBoolean());
        mClock.mOffset += 60;
        mLooper.dispatchAll();
        verify(mCardEmulationManager).onFieldChangeDetected(anyBoolean());
        verify(mApplication).sendBroadcastAsUser(any(), any(), isNull(), isNull());
        verify(mStatsdUtils).logFieldChanged(anyBoolean(), anyInt());
        verify(mNfcEventLog, atLeast(2)).logEvent(any());
    }

    @Test
    public void testOnRemoteFieldCoalessing() throws RemoteException {
        Assume.assumeTrue(Flags.coalesceRfEvents());
        createNfcServiceWithoutStatsdUtils();
        List<String> userlist = new ArrayList<>();
        userlist.add("com.android.nfc");
        mNfcService.mIsSecureNfcEnabled = true;
        mNfcService.mIsRequestUnlockShowed = false;
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);
        mNfcService.mNfcEventInstalledPackages.put(1, userlist);
        when(android.nfc.Flags.nfcPersistLog()).thenReturn(true);
        mNfcService.onRemoteFieldActivated();
        mNfcService.onRemoteFieldDeactivated();
        mNfcService.onRemoteFieldActivated();
        mNfcService.onRemoteFieldDeactivated();
        mClock.mOffset += 60;
        mLooper.dispatchAll();
        verify(mCardEmulationManager).onFieldChangeDetected(true);
        verify(mCardEmulationManager).onFieldChangeDetected(false);
        verify(mApplication, times(2)).sendBroadcastAsUser(any(), any());
        verify(mStatsdUtils, times(4)).logFieldChanged(anyBoolean(), anyInt());
        verify(mNfcEventLog, atLeast(2)).logEvent(any());
    }

    @Test
    public void testOnSeSelected() {
        byte[] aid = new byte[]{ 0x0A, 0x00, 0x00, 0x00 };
        mNfcService.onSeSelected(
                NfcService.SE_SELECTED_AID, aid, "eSE1");
        mLooper.dispatchAll();
        verify(mCardEmulationManager).onOffHostAidSelected(Utils.aidBytesToString(aid), "eSE1");
    }

    @Test
    public void testOnUidToBackground() throws RemoteException {
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        mNfcService.mNfcAdapter.disable(false, PKG_NAME);
        mLooper.dispatchAll();
        NfcService.ReaderModeParams readerModeParams = mock(NfcService.ReaderModeParams.class);
        mNfcService.mReaderModeParams = readerModeParams;
        readerModeParams.uid = 1;
        IBinder binder = mock(IBinder.class);
        readerModeParams.binder = binder;
        NfcService.DiscoveryTechParams discoveryTechParams =
                mock(NfcService.DiscoveryTechParams.class);
        discoveryTechParams.uid = 1;
        discoveryTechParams.binder = binder;
        mNfcService.mDiscoveryTechParams = discoveryTechParams;
        mNfcService.onUidToBackground(1);
        verify(binder, times(2)).unlinkToDeath(any(), anyInt());
        Assert.assertNull(mNfcService.mReaderModeParams);
        verify(binder, times(2)).unlinkToDeath(any(), anyInt());
        verify(mDeviceHost).resetDiscoveryTech();
        Assert.assertNull(mNfcService.mDiscoveryTechParams);
    }

    @Test
    public void testOnUidToBackground_unlinkThrows() throws RemoteException {
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        mLooper.dispatchAll();
        IBinder binder = mock(IBinder.class);
        doThrow(new java.util.NoSuchElementException()).when(binder).unlinkToDeath(any(), anyInt());

        NfcService.DiscoveryTechParams discoveryTechParams =
                mNfcService.new DiscoveryTechParams();
        discoveryTechParams.uid = 1;
        discoveryTechParams.binder = binder;
        mNfcService.mDiscoveryTechParams = discoveryTechParams;

        mNfcService.onUidToBackground(1);

        verify(binder).unlinkToDeath(any(), anyInt());
        verify(mDeviceHost).resetDiscoveryTech();
        Assert.assertNull(mNfcService.mDiscoveryTechParams);
    }

    @Test
    public void testUpdateDiscoveryTechnology_resetTech() throws RemoteException {
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        when(NfcInjector.isPrivileged(anyInt())).thenReturn(true);

        IBinder binder = mock(IBinder.class);
        NfcService.DiscoveryTechParams discoveryTechParams =
                mNfcService.new DiscoveryTechParams();
        discoveryTechParams.uid = 1;
        discoveryTechParams.binder = binder;
        mNfcService.mDiscoveryTechParams = discoveryTechParams;

        mNfcService.mNfcAdapter.updateDiscoveryTechnology(binder,
                NfcAdapter.FLAG_USE_ALL_TECH, NfcAdapter.FLAG_USE_ALL_TECH, PKG_NAME);

        verify(binder).unlinkToDeath(any(), anyInt());
        verify(mDeviceHost).resetDiscoveryTech();
        Assert.assertNull(mNfcService.mDiscoveryTechParams);
    }

    @Test
    public void testUpdateDiscoveryTechnology_resetTech_unlinkThrows() throws RemoteException {
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        when(NfcInjector.isPrivileged(anyInt())).thenReturn(true);

        IBinder binder = mock(IBinder.class);
        doThrow(new java.util.NoSuchElementException()).when(binder).unlinkToDeath(any(), anyInt());

        NfcService.DiscoveryTechParams discoveryTechParams =
                mNfcService.new DiscoveryTechParams();
        discoveryTechParams.uid = 1;
        discoveryTechParams.binder = binder;
        mNfcService.mDiscoveryTechParams = discoveryTechParams;

        mNfcService.mNfcAdapter.updateDiscoveryTechnology(binder,
                NfcAdapter.FLAG_USE_ALL_TECH, NfcAdapter.FLAG_USE_ALL_TECH, PKG_NAME);

        verify(binder).unlinkToDeath(any(), anyInt());
        verify(mDeviceHost).resetDiscoveryTech();
        Assert.assertNull(mNfcService.mDiscoveryTechParams);
    }

    @Test
    public void testUpdateDiscoveryTechnology_setTech() throws RemoteException {
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        when(NfcInjector.isPrivileged(anyInt())).thenReturn(true);

        IBinder binder = mock(IBinder.class);
        IBinder newBinder = mock(IBinder.class);
        NfcService.DiscoveryTechParams discoveryTechParams =
                mNfcService.new DiscoveryTechParams();
        discoveryTechParams.uid = 1;
        discoveryTechParams.binder = binder;
        mNfcService.mDiscoveryTechParams = discoveryTechParams;

        mNfcService.mNfcAdapter.updateDiscoveryTechnology(newBinder,
                0x01, 0x01, PKG_NAME);

        verify(binder).unlinkToDeath(any(), anyInt());
        verify(newBinder).linkToDeath(any(), anyInt());
        Assert.assertNotNull(mNfcService.mDiscoveryTechParams);
        Assert.assertEquals(newBinder, mNfcService.mDiscoveryTechParams.binder);
    }

    @Test
    public void testUpdateDiscoveryTechnology_setTech_unlinkThrows() throws RemoteException {
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        when(NfcInjector.isPrivileged(anyInt())).thenReturn(true);

        IBinder binder = mock(IBinder.class);
        doThrow(new java.util.NoSuchElementException()).when(binder).unlinkToDeath(any(), anyInt());
        IBinder newBinder = mock(IBinder.class);

        NfcService.DiscoveryTechParams discoveryTechParams =
                mNfcService.new DiscoveryTechParams();
        discoveryTechParams.uid = 1;
        discoveryTechParams.binder = binder;
        mNfcService.mDiscoveryTechParams = discoveryTechParams;

        mNfcService.mNfcAdapter.updateDiscoveryTechnology(newBinder,
                0x01, 0x01, PKG_NAME);

        verify(binder).unlinkToDeath(any(), anyInt());
        verify(newBinder).linkToDeath(any(), anyInt());
        Assert.assertNotNull(mNfcService.mDiscoveryTechParams);
        Assert.assertEquals(newBinder, mNfcService.mDiscoveryTechParams.binder);
    }

    @Test
    public void testOnWlcData() throws RemoteException {
        mNfcService.mIsWlcCapable = true;
        INfcWlcStateListener listener = mock(INfcWlcStateListener.class);
        mNfcService.mNfcAdapter.registerWlcStateListener(listener);
        Map<String, Integer> wlcDeviceInfo = new HashMap<>();
        wlcDeviceInfo.put(NfcCharging.VendorId, 1);
        wlcDeviceInfo.put(NfcCharging.TemperatureListener, 1);
        wlcDeviceInfo.put(NfcCharging.BatteryLevel, 1);
        wlcDeviceInfo.put(NfcCharging.State, 1);
        mNfcService.onWlcData(wlcDeviceInfo);
        ArgumentCaptor<WlcListenerDeviceInfo> argumentCaptor = ArgumentCaptor
                .forClass(WlcListenerDeviceInfo.class);
        verify(listener).onWlcStateChanged(argumentCaptor.capture());
        WlcListenerDeviceInfo deviceInfo = argumentCaptor.getValue();
        Assert.assertNotNull(deviceInfo);
        assertThat(deviceInfo.getBatteryLevel()).isEqualTo(1);
    }

    @Test
    public void testOnWlcStopped() {
        mNfcService.onWlcStopped(0x0);
        verify(mNfcCharging).onWlcStopped(anyInt());
    }

    @Test
    public void testRenewTagAppPrefList() throws PackageManager.NameNotFoundException {
        BroadcastReceiver receiver = mGlobalReceiver.getValue();
        Assert.assertNotNull(receiver);
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_USER_SWITCHED);
        UserHandle uh = mock(UserHandle.class);
        when(uh.getIdentifier()).thenReturn(5);
        List<UserHandle> luh = new ArrayList<>();
        luh.add(uh);
        when(mUserManager.getEnabledProfiles()).thenReturn(luh);
        String jsonString = "{}";
        when(mPreferences.getString(anyString(), anyString())).thenReturn(jsonString);
        PackageInfo info = mock(PackageInfo.class);
        when(mPackageManager.getPackageInfo(anyString(), anyInt())).thenReturn(info);
        when(mPreferencesEditor.remove(any())).thenReturn(mPreferencesEditor);
        when(mPreferencesEditor.putString(anyString(), anyString())).thenReturn(mPreferencesEditor);
        receiver.onReceive(mApplication, intent);
        verify(mUserManager, atLeastOnce()).getEnabledProfiles();
        verify(mPreferencesEditor).putString(anyString(), anyString());
    }

    @Test
    public void testSaveNfcPollTech() {
        mNfcService.saveNfcPollTech(NfcService.DEFAULT_POLL_TECH);
        verify(mPreferencesEditor, atLeastOnce()).putInt(anyString(), anyInt());
        verify(mPreferencesEditor, atLeastOnce()).apply();
        verify(mBackupManager).dataChanged();
    }

    @Test
    public void testSendData() {
        mNfcService.sendData("test".getBytes());
        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(mDeviceHost).sendRawFrame(captor.capture());
        assertThat(captor.getValue()).isNotNull();
        assertThat(captor.getValue()).isEqualTo("test".getBytes());
    }

    @Test
    public void testSendMockNdefTag() {
        NdefMessage msg = mock(NdefMessage.class);
        when(mNfcDispatcher.dispatchTag(any())).thenReturn(NfcDispatcher.DISPATCH_SUCCESS);
        when(mVrManager.isVrModeEnabled()).thenReturn(false);
        mNfcService.mSoundPool = mSoundPool;
        mNfcService.sendMockNdefTag(msg);
        mLooper.dispatchAll();
        ArgumentCaptor<Tag> captor = ArgumentCaptor.forClass(Tag.class);
        verify(mNfcDispatcher).dispatchTag(captor.capture());
        Tag tag = captor.getValue();
        assertThat(tag).isNotNull();
        verify(mSoundPool)
                .play(anyInt(), anyFloat(), anyFloat(), anyInt(), anyInt(), anyFloat());

        when(mNfcDispatcher.dispatchTag(any())).thenReturn(NfcDispatcher.DISPATCH_FAIL);
        mNfcService.sendMockNdefTag(msg);
        mLooper.dispatchAll();
        verify(mNfcDispatcher, atLeastOnce()).dispatchTag(captor.capture());
        tag = captor.getValue();
        assertThat(tag).isNotNull();
        verify(mSoundPool, times(2))
                .play(anyInt(), anyFloat(), anyFloat(), anyInt(), anyInt(), anyFloat());
    }

    @Test
    public void testSendScreenMessageAfterNfcCharging() {
        mNfcService.mPendingPowerStateUpdate = true;
        when(mScreenStateHelper.checkScreenState(anyBoolean()))
                .thenReturn(ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED);
        boolean result = mNfcService.sendScreenMessageAfterNfcCharging();
        mLooper.dispatchAll();
        assertThat(mNfcService.mIsRequestUnlockShowed).isFalse();
        assertThat(result).isTrue();
        assertThat(mNfcService.mPendingPowerStateUpdate).isFalse();
        verify(mDeviceHost).doSetScreenState(anyInt(), anyBoolean());
    }

    @Test
    public void testSetPowerSavingModeNciMessage() throws RemoteException {
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        byte[] payload = { 0x01, 0x01, 0x00, 0x00 };
        when(mDeviceHost.isPowerSavingModeSupported()).thenReturn(true);
        when(mDeviceHost.setPowerSavingMode(true)).thenReturn(true);
        int result = mNfcService.mNfcAdapter.sendVendorNciMessage(1,0x0f,0x0c, payload);
        mLooper.dispatchAll();
        assertThat(result).isEqualTo(0x00);
        verify(mDeviceHost).setPowerSavingMode(eq(true));
    }

    @Test
    public void testSetSystemCodeRoute() throws Exception {
        enableAndVerify();

        mNfcService.setSystemCodeRoute(1);
        mLooper.dispatchAll();
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(mDeviceHost).setSystemCodeRoute(captor.capture());
        assertThat(captor.getValue()).isEqualTo(1);
    }

    @Test
    public void testStateToProtoEnum() {
        int result = NfcService.stateToProtoEnum(NfcAdapter.STATE_OFF);
        assertThat(result).isEqualTo(NfcServiceDumpProto.STATE_OFF);
        result = NfcService.stateToProtoEnum(NfcAdapter.STATE_TURNING_ON);
        assertThat(result).isEqualTo(NfcServiceDumpProto.STATE_TURNING_ON);
        result = NfcService.stateToProtoEnum(NfcAdapter.STATE_ON);
        assertThat(result).isEqualTo(NfcServiceDumpProto.STATE_ON);
        result = NfcService.stateToProtoEnum(NfcAdapter.STATE_TURNING_OFF);
        assertThat(result).isEqualTo(NfcServiceDumpProto.STATE_TURNING_OFF);
        result = NfcService.stateToProtoEnum(0);
        assertThat(result).isEqualTo(NfcServiceDumpProto.STATE_UNKNOWN);
    }
    @Test
    public void testUnregisterObject() {
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.getHandle()).thenReturn(1);
        mNfcService.registerTagObject(tagEndpoint);
        mNfcService.unregisterObject(1);
        assertThat(mNfcService.mObjectMap.get(1)).isNull();
    }

    @Test
    public void testNfcServiceOnReceive() {
        BroadcastReceiver receiver = mBroadcastReceiverArgumentCaptor.getValue();
        Bundle bundle = new Bundle();
        bundle.putBoolean(UserManager.DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO, true);
        when(mUserManager.getUserRestrictions()).thenReturn(bundle);
        Assert.assertNotNull(receiver);
        mNfcService.mIsNfcUserRestricted = false;
        when(mPreferences.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        when(mNfcInjector.isSatelliteModeOn()).thenReturn(false);
        receiver.onReceive(mApplication, new Intent(UserManager.ACTION_USER_RESTRICTIONS_CHANGED));
        verify(mUserManager, atLeastOnce()).getUserRestrictions();

    }

    @Test
    public void testDiscoveryTechDeathRecipient_BinderDied() {
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        mNfcService.mDiscoveryTechParams = mock(NfcService.DiscoveryTechParams.class);
        NfcService.DiscoveryTechDeathRecipient discoveryTechDeathRecipient = mNfcService
                .new DiscoveryTechDeathRecipient();
        discoveryTechDeathRecipient.binderDied();
        verify(mDeviceHost).resetDiscoveryTech();
        assertThat(mNfcService.mDiscoveryTechParams).isNull();
    }

    @Test
    public void testDisableAlwaysOnInternal() throws RemoteException {
        mNfcService.mAlwaysOnState = NfcAdapter.STATE_OFF;
        mNfcService.mNfcAdapter.setControllerAlwaysOn(NfcOemExtension.DISABLE);
        mLooper.dispatchAll();

        mNfcService.mAlwaysOnState = NfcAdapter.STATE_TURNING_OFF;
        mNfcService.mAlwaysOnMode = NfcOemExtension.DISABLE;
        mNfcService.mNfcAdapter.setControllerAlwaysOn(NfcOemExtension.DISABLE);
        mLooper.dispatchAll();
        assertThat(mNfcService.mAlwaysOnMode).isEqualTo(NfcOemExtension.DISABLE);

        mNfcService.mState.set(NfcAdapter.STATE_ON);
        mNfcService.mAlwaysOnState = NfcAdapter.STATE_TURNING_ON;
        mNfcService.mNfcAdapter.setControllerAlwaysOn(NfcOemExtension.DISABLE);
        mLooper.dispatchAll();
        verify(mDeviceHost).setNfceePowerAndLinkCtrl(false);

        mNfcService.mState.set(NfcAdapter.STATE_OFF);
        mNfcService.mNfcAdapter.setControllerAlwaysOn(NfcOemExtension.DISABLE);
        mLooper.dispatchAll();
        verify(mDeviceHost).setNfceePowerAndLinkCtrl(false);

    }

    @Test
    public void testEnableAlwaysOnInternal() throws RemoteException {
        mNfcService.mAlwaysOnState = NfcAdapter.STATE_ON;
        mNfcService.mNfcAdapter.setControllerAlwaysOn(NfcOemExtension.ENABLE_EE);
        mLooper.dispatchAll();

        mNfcService.mAlwaysOnState = NfcAdapter.STATE_TURNING_OFF;
        mNfcService.mNfcAdapter.setControllerAlwaysOn(NfcOemExtension.ENABLE_EE);
        mLooper.dispatchAll();

        mNfcService.mState.set(NfcAdapter.STATE_ON);
        mNfcService.mAlwaysOnState = NfcAdapter.STATE_OFF;
        mNfcService.mNfcAdapter.setControllerAlwaysOn(NfcOemExtension.ENABLE_EE);
        mLooper.dispatchAll();
        verify(mDeviceHost).setNfceePowerAndLinkCtrl(true);

        mNfcService.mState.set(NfcAdapter.STATE_OFF);
        mNfcService.mAlwaysOnState = NfcAdapter.STATE_OFF;
        mNfcService.mNfcAdapter.setControllerAlwaysOn(NfcOemExtension.ENABLE_EE);
        mLooper.dispatchAll();
        verify(mDeviceHost, times(2)).setPartialInitMode(anyInt());
        verify(mDeviceHost).setNfceePowerAndLinkCtrl(true);
    }

    @Test
    public void testAddNfcUnlockHandler() {
        INfcUnlockHandler unlockHandler = mock(INfcUnlockHandler.class);
        mNfcService.mNfcAdapter.addNfcUnlockHandler(unlockHandler, new int[]{NfcService.NFC_POLL_A,
                NfcService.NFC_POLL_B, NFC_POLL_V});
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(mNfcUnlockManager).addUnlockHandler(any(), captor.capture());
        assertThat(captor.getValue()).isNotNull();
        assertThat(captor.getValue()).isEqualTo(3);
    }

    @Test
    public void testCheckFirmware() throws RemoteException {
        mNfcService.mNfcAdapter.checkFirmware();
        verify(mDeviceHost).checkFirmware();
    }

    @Test
    public void testClearPreference() throws RemoteException {
        when(android.nfc.Flags.nfcPersistLog()).thenReturn(true);
        mNfcService.mNfcAdapter.clearPreference();
        verify(mNfcEventLog, times(2)).logEvent(any());
    }

    @Test
    public void clearT3tIdentifiersCache_whenIdentifierRegistered_restartsDiscovery() {
        // Arrange
        when(mNativeNfcManager.isT3TIdentifierRegistered()).thenReturn(true);
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        mNfcService.mScreenState = ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED;
        // Set current discovery to be enabled to verify shouldRestart is true
        mNfcService.mCurrentDiscoveryParameters = NfcDiscoveryParameters.newBuilder()
                .setTechMask(NfcDiscoveryParameters.NFC_POLL_DEFAULT)
                .setEnableReaderMode(true)
                .build();

        // Act
        mNfcService.clearT3tIdentifiersCache();

        // Assert
        InOrder inOrder = inOrder(mDeviceHost);
        inOrder.verify(mDeviceHost).disableDiscovery();
        inOrder.verify(mDeviceHost).clearT3tIdentifiersCache();
        inOrder.verify(mDeviceHost).enableDiscovery(any(NfcDiscoveryParameters.class), eq(true));
    }

    @Test
    public void clearT3tIdentifiersCache_whenIdentifierNotRegistered_doesNothing() {
        // Arrange
        when(mNativeNfcManager.isT3TIdentifierRegistered()).thenReturn(false);

        // Act
        mNfcService.clearT3tIdentifiersCache();

        // Assert
        verify(mDeviceHost, never()).disableDiscovery();
        verify(mDeviceHost, never()).clearT3tIdentifiersCache();
        verify(mDeviceHost, never()).enableDiscovery(any(NfcDiscoveryParameters.class),
                anyBoolean());
    }

    @Test
    public void testEnableReaderOption() throws RemoteException {
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        INfcOemExtensionCallback callback = mock(INfcOemExtensionCallback.class);
        when(callback.asBinder()).thenReturn(mock(IBinder.class));
        mNfcService.mNfcAdapter.registerOemExtensionCallback(callback);
        mNfcService.mReaderOptionCapable = true;
        when(android.nfc.Flags.nfcPersistLog()).thenReturn(true);
        boolean result = mNfcService.mNfcAdapter
                .enableReaderOption(true, "com.android.test");
        assertThat(mNfcService.mIsReaderOptionEnabled).isTrue();
        verify(mPreferencesEditor, atLeastOnce()).apply();
        verify(mBackupManager).dataChanged();
        verify(callback).onReaderOptionChanged(true);
        verify(mNfcEventLog, times(2)).logEvent(any());
        assertThat(result).isTrue();
    }

    @Test
    public void testFetchActiveNfceeList() throws RemoteException {
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        Map<String, Integer> nfceeMap = new HashMap<>();
        nfceeMap.put("test1", Integer.valueOf(NFC_LISTEN_A));
        nfceeMap.put("test2", Integer.valueOf(NFC_LISTEN_B));
        nfceeMap.put("test3", Integer.valueOf(NFC_LISTEN_F));
        when(mDeviceHost.dofetchActiveNfceeList()).thenReturn(nfceeMap);
        Map<String, Integer> map = mNfcService.mNfcAdapter.fetchActiveNfceeList();
        verify(mDeviceHost).dofetchActiveNfceeList();
        Assert.assertEquals(map, nfceeMap);
    }

    @Test
    public void testGetNfcAdapterExtrasInterface() throws RemoteException {
        INfcAdapterExtras adpExtras = mNfcService.mNfcAdapter
                .getNfcAdapterExtrasInterface("com.android.test");
        assertThat(adpExtras).isNull();
    }

    @Test
    public void testGetNfcDtaInterface() throws RemoteException {
        mNfcService.mNfcDtaService = null;
        INfcDta nfcData = mNfcService.mNfcAdapter.getNfcDtaInterface("com.android.test");
        assertThat(nfcData).isNotNull();

        NfcService.NfcDtaService nfcDtaService = mock(NfcService.NfcDtaService.class);
        mNfcService.mNfcDtaService = nfcDtaService;
        INfcDta resultDtaService = mNfcService.mNfcAdapter
                .getNfcDtaInterface("com.android.test");
        Assert.assertNotNull(resultDtaService);
        assertThat(nfcDtaService).isEqualTo(resultDtaService);
    }

    @Test
    public void testGetSettingStatus() throws RemoteException {
        when(mDeviceConfigFacade.getNfcDefaultState()).thenReturn(true);
        when(mPreferences.getBoolean(PREF_NFC_ON, true)).thenReturn(true);
        boolean result = mNfcService.mNfcAdapter.getSettingStatus();
        assertThat(result).isTrue();
        verify(mPreferences, atLeastOnce()).getBoolean(anyString(), anyBoolean());
    }

    @Test
    public void testSetTagIntentAppPreferenceForUser()
            throws RemoteException, PackageManager.NameNotFoundException {
        PackageInfo info = mock(PackageInfo.class);
        ApplicationInfo applicationInfo = mock(ApplicationInfo.class);
        applicationInfo.flags = 1;
        info.applicationInfo = applicationInfo;
        when(mPackageManager.getPackageInfo(anyString(), anyInt())).thenReturn(info);
        int result = mNfcService.mNfcAdapter
                .setTagIntentAppPreferenceForUser(1, "com.android.test", true);
        assertThat(result).isEqualTo(NfcAdapter.TAG_INTENT_APP_PREF_RESULT_SUCCESS);
        when(mPackageManager.getPackageInfo(anyString(), anyInt())).thenReturn(null);
        result = mNfcService.mNfcAdapter
                .setTagIntentAppPreferenceForUser(1, "com.android.test", true);
        assertThat(result).isEqualTo(NfcAdapter.TAG_INTENT_APP_PREF_RESULT_PACKAGE_NOT_FOUND);
    }

    @Test
    public void testCanMakeReadOnly() throws RemoteException {
        NfcService.TagService tagService = mNfcService.new TagService();
        tagService.canMakeReadOnly(Ndef.TYPE_1);
        verify(mDeviceHost).canMakeReadOnly(anyInt());
    }

    @Test
    public void testConnect() throws RemoteException {
        NfcService.TagService tagService = mNfcService.new TagService();
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        mNfcService.mIsReaderOptionEnabled = true;
        mNfcService.mCookieUpToDate = 12345L;
        mNfcService.mDispatchedUid = Process.INVALID_UID;
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.isPresent()).thenReturn(true);
        when(tagEndpoint.connect(anyInt())).thenReturn(true);
        mNfcService.mObjectMap.put(1, tagEndpoint);
        int resultCode = tagService.connect(1, Ndef.TYPE_1);
       assertThat(resultCode).isEqualTo(ErrorCodes.SUCCESS);
    }

    @Test
    public void testReConnect() throws RemoteException {
        NfcService.TagService tagService = mNfcService.new TagService();
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        mNfcService.mIsReaderOptionEnabled = true;
        mNfcService.mCookieUpToDate = 12345L;
        mNfcService.mDispatchedUid = Process.INVALID_UID;
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.isPresent()).thenReturn(true);
        when(tagEndpoint.reconnect()).thenReturn(true);
        mNfcService.mObjectMap.put(1, tagEndpoint);
        int resultCode = tagService.reconnect(1);
        assertThat(resultCode).isEqualTo(ErrorCodes.SUCCESS);
    }

    @Test
    public void testFormatNdef() throws RemoteException {
        NfcService.TagService tagService = mNfcService.new TagService();
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        mNfcService.mIsReaderOptionEnabled = true;
        mNfcService.mCookieUpToDate = 12345L;
        mNfcService.mDispatchedUid = Process.INVALID_UID;
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.formatNdef(any())).thenReturn(true);
        mNfcService.mObjectMap.put(1, tagEndpoint);
        int resultCode = tagService.formatNdef(1, "test".getBytes());
        assertThat(resultCode).isEqualTo(ErrorCodes.SUCCESS);
    }

    @Test
    public void testRediscover() throws RemoteException {
        NfcService.TagService tagService = mNfcService.new TagService();
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        mNfcService.mIsReaderOptionEnabled = true;
        mNfcService.mCookieUpToDate = 12345L;
        mNfcService.mDispatchedUid = Process.INVALID_UID;
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.getUid()).thenReturn(new byte[]{4, 18, 52, 86});
        when(tagEndpoint.getTechList()).thenReturn(new int[]{Ndef.NDEF});
        when(tagEndpoint.getTechExtras()).thenReturn(new Bundle[]{});
        when(tagEndpoint.getHandle()).thenReturn(1);
        mNfcService.mObjectMap.put(1, tagEndpoint);
        Tag tag = tagService.rediscover(1);
        assertThat(tag).isNotNull();
    }

    @Test
    public void testGetExtendedLengthApdusSupported() throws RemoteException {
        NfcService.TagService tagService = mNfcService.new TagService();
        tagService.getExtendedLengthApdusSupported();
        verify(mDeviceHost).getExtendedLengthApdusSupported();
    }

    @Test
    public void testGetMaxTransceiveLength() throws RemoteException {
        NfcService.TagService tagService = mNfcService.new TagService();
        tagService.getMaxTransceiveLength(Ndef.NDEF);
        verify(mDeviceHost).getMaxTransceiveLength(Ndef.NDEF);
    }

    @Test
    public void testTechList() throws RemoteException {
        NfcService.TagService tagService = mNfcService.new TagService();
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        mNfcService.mIsReaderOptionEnabled = true;
        mNfcService.mCookieUpToDate = 12345L;
        mNfcService.mDispatchedUid = Process.INVALID_UID;
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.getTechList()).thenReturn(new int[]{Ndef.NDEF, Ndef.TYPE_OTHER});
        mNfcService.mObjectMap.put(1, tagEndpoint);
        int[] techList = tagService.getTechList(1);
        assertThat(techList).isNotNull();
        assertThat(techList).hasLength(2);
    }

    @Test
    public void testGetTimeOut() throws RemoteException {
        NfcService.TagService tagService = mNfcService.new TagService();
        tagService.getTimeout(Ndef.NDEF);
        verify(mDeviceHost).getTimeout(Ndef.NDEF);
    }

    @Test
    public void testIsNdef() throws RemoteException {
        NfcService.TagService tagService = mNfcService.new TagService();
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        mNfcService.mIsReaderOptionEnabled = true;
        mNfcService.mCookieUpToDate = 12345L;
        mNfcService.mDispatchedUid = Process.INVALID_UID;
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.checkNdef(any())).thenReturn(true);
        mNfcService.mObjectMap.put(1, tagEndpoint);
        boolean result = tagService.isNdef(1);
        assertThat(result).isTrue();
    }

    @Test
    public void testIsPresent() throws RemoteException {
        NfcService.TagService tagService = mNfcService.new TagService();
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        mNfcService.mIsReaderOptionEnabled = true;
        mNfcService.mCookieUpToDate = 12345L;
        mNfcService.mDispatchedUid = Process.INVALID_UID;
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.isPresent()).thenReturn(true);
        mNfcService.mObjectMap.put(1, tagEndpoint);
        boolean result = tagService.isPresent(1);
        assertThat(result).isTrue();
    }

    @Test
    public void testIsTagUpToDate() throws RemoteException {
        NfcService.TagService tagService = mNfcService.new TagService();
        mNfcService.mCookieUpToDate = 0;
        boolean result = tagService.isTagUpToDate(0);
        assertThat(result).isTrue();
    }

    @Test
    public void testNdefMakeReadOnly() throws RemoteException {
        NfcService.TagService tagService = mNfcService.new TagService();
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        mNfcService.mIsReaderOptionEnabled = true;
        mNfcService.mCookieUpToDate = 12345L;
        mNfcService.mDispatchedUid = Process.INVALID_UID;
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.makeReadOnly()).thenReturn(true);
        mNfcService.mObjectMap.put(1, tagEndpoint);
        int result = tagService.ndefMakeReadOnly(1);
        assertThat(result).isEqualTo(ErrorCodes.SUCCESS);
    }

    @Test
    public void testNdefRead() throws RemoteException {
        NfcService.TagService tagService = mNfcService.new TagService();
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        mNfcService.mIsReaderOptionEnabled = true;
        mNfcService.mCookieUpToDate = 12345L;
        mNfcService.mDispatchedUid = Process.INVALID_UID;
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        NdefRecord record = NdefRecord.createTextRecord("en", "ndef");
        NdefMessage ndefMessage = new NdefMessage(new NdefRecord[]{record});
        when(tagEndpoint.readNdef()).thenReturn(ndefMessage.toByteArray());
        mNfcService.mObjectMap.put(1, tagEndpoint);
        NdefMessage resultMessage = tagService.ndefRead(1);
        assertThat(resultMessage).isNotNull();
        assertThat(resultMessage.getRecords()).hasLength(1);
    }

    @Test
    public void testNdefWrite() throws RemoteException {
        NfcService.TagService tagService = mNfcService.new TagService();
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        mNfcService.mIsReaderOptionEnabled = true;
        mNfcService.mCookieUpToDate = 12345L;
        mNfcService.mDispatchedUid = Process.INVALID_UID;
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        NdefRecord record = NdefRecord.createTextRecord("en", "ndef");
        NdefMessage ndefMessage = new NdefMessage(new NdefRecord[]{record});
        when(tagEndpoint.writeNdef(any())).thenReturn(true);
        mNfcService.mObjectMap.put(1, tagEndpoint);
        int result = tagService.ndefWrite(1, ndefMessage);
        assertThat(result).isEqualTo(ErrorCodes.SUCCESS);
    }

    @Test
    public void testResetTimeouts() throws RemoteException {
        NfcService.TagService tagService = mNfcService.new TagService();
        tagService.resetTimeouts();
        verify(mDeviceHost).resetTimeouts();
    }

    @Test
    public void testSetTimeout() throws RemoteException {
        NfcService.TagService tagService = mNfcService.new TagService();
        when(mDeviceHost.setTimeout(Ndef.NDEF, 100)).thenReturn(true);
        int result = tagService.setTimeout(Ndef.NDEF, 100);
        verify(mDeviceHost).setTimeout(Ndef.NDEF, 100);
        assertThat(result).isEqualTo(ErrorCodes.SUCCESS);
    }

    @Test
    public void testTransceive() throws RemoteException {
        NfcService.TagService tagService = mNfcService.new TagService();
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        mNfcService.mIsReaderOptionEnabled = true;
        mNfcService.mCookieUpToDate = 12345L;
        mNfcService.mDispatchedUid = Process.INVALID_UID;
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        NdefRecord record = NdefRecord.createTextRecord("en", "ndef");
        NdefMessage ndefMessage = new NdefMessage(new NdefRecord[]{record});
        when(mDeviceHost.getMaxTransceiveLength(anyInt())).thenReturn(100);
        when(tagEndpoint.getConnectedTechnology()).thenReturn(Ndef.NDEF);
        when(tagEndpoint.transceive(any(), anyBoolean(), any()))
                .thenReturn(ndefMessage.toByteArray());
        mNfcService.mObjectMap.put(1, tagEndpoint);

        TransceiveResult result = tagService.transceive(1, ndefMessage
                .toByteArray(), true);
        assertThat(result).isNotNull();
    }

    @Test
    public void testTagService_unauthorizedCallingUid_blocksTagOperations() throws RemoteException {
        NfcService.TagService tagService = mNfcService.new TagService();
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        mNfcService.mIsReaderOptionEnabled = true;
        mNfcService.mCookieUpToDate = 12345L;
        // Ensure mDispatchedUid is guaranteed to be different from Binder.getCallingUid()
        mNfcService.mDispatchedUid = Binder.getCallingUid() + 100;

        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.isPresent()).thenReturn(true);
        when(tagEndpoint.connect(anyInt())).thenReturn(true);
        mNfcService.mObjectMap.put(1, tagEndpoint);

        // Current calling process UID (different from 10001) will be blocked
        assertThat(tagService.connect(1, Ndef.TYPE_1)).isEqualTo(ErrorCodes.ERROR_DISCONNECT);
        assertThat(tagService.reconnect(1)).isEqualTo(ErrorCodes.ERROR_DISCONNECT);
        assertThat(tagService.getTechList(1)).isNull();
        assertThat(tagService.isPresent(1)).isFalse();
        assertThat(tagService.isNdef(1)).isFalse();
        assertThat(tagService.transceive(1, new byte[]{0x00}, true)).isNull();
        assertThat(tagService.ndefRead(1)).isNull();

        NdefRecord record = NdefRecord.createTextRecord("en", "ndef");
        NdefMessage ndefMessage = new NdefMessage(new NdefRecord[]{record});
        assertThat(tagService.ndefWrite(1, ndefMessage)).isEqualTo(ErrorCodes.ERROR_IO);
        assertThat(tagService.ndefMakeReadOnly(1)).isEqualTo(ErrorCodes.ERROR_IO);
        assertThat(tagService.formatNdef(1, "test".getBytes())).isEqualTo(ErrorCodes.ERROR_IO);
        assertThat(tagService.rediscover(1)).isNull();
    }

    @Test
    public void testGetWlcListenerDeviceInfo() {
        Map<String, Integer> wlcDeviceInfo = new HashMap<>();
        wlcDeviceInfo.put(NfcCharging.VendorId, 1);
        wlcDeviceInfo.put(NfcCharging.TemperatureListener, 1);
        wlcDeviceInfo.put(NfcCharging.BatteryLevel, 1);
        wlcDeviceInfo.put(NfcCharging.State, 1);
        mNfcService.onWlcData(wlcDeviceInfo);
        NfcService.NfcAdapterService adapterService = mNfcService.new NfcAdapterService();
        mNfcService.mIsWlcCapable = true;
        WlcListenerDeviceInfo wlcListenerDeviceInfo = adapterService.getWlcListenerDeviceInfo();
        assertThat(wlcListenerDeviceInfo).isNotNull();
        assertThat(wlcListenerDeviceInfo.getBatteryLevel()).isEqualTo(1);
    }

    @Test
    public void testHandleShellCommand() {
        ParcelFileDescriptor in = mock(ParcelFileDescriptor.class);
        when(in.getFileDescriptor()).thenReturn(mock(FileDescriptor.class));
        ParcelFileDescriptor out = mock(ParcelFileDescriptor.class);
        when(out.getFileDescriptor()).thenReturn(mock(FileDescriptor.class));
        ParcelFileDescriptor err = mock(ParcelFileDescriptor.class);
        when(err.getFileDescriptor()).thenReturn(mock(FileDescriptor.class));
        NfcService.NfcAdapterService adapterService = mNfcService.new NfcAdapterService();
        int result = adapterService.handleShellCommand(in, out, err, new String[]{"test"});
        assertThat(result).isEqualTo(-1);
    }

    @Test
    public void testIgnore() throws RemoteException {
        mNfcService.mDebounceTagNativeHandle = 0;
        ITagRemovedCallback callback = mock(ITagRemovedCallback.class);
        NfcService.NfcAdapterService adapterService = mNfcService.new NfcAdapterService();
        boolean result = adapterService.ignore(0,0, callback);
        mLooper.dispatchAll();
        assertThat(result).isTrue();

        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.getUid()).thenReturn(new byte[]{4, 18 ,52, 86});
        mNfcService.mObjectMap.put(1, tagEndpoint);
        result = adapterService.ignore(1,1, callback);
        verify(tagEndpoint).disconnect();
        mLooper.dispatchAll();
        assertThat(result).isTrue();
        // Verify that the tag object is removed from the map
        Assert.assertNull(mNfcService.mObjectMap.get(1));
    }

    @Test
    public void testIsNfcSecureEnabled() throws RemoteException {
        NfcService.NfcAdapterService adapterService = mNfcService.new NfcAdapterService();
        mNfcService.mIsSecureNfcEnabled = true;
        boolean result = adapterService.isNfcSecureEnabled();
        assertThat(result).isTrue();
    }

    @Test
    public void testIsNfcSecureEnabled_UserChanged() throws RemoteException {
        NfcService.NfcAdapterService adapterService = mNfcService.new NfcAdapterService();
        int currentUser = ActivityManager.getCurrentUser();

        // Simulate user switch to change mUserId to a different user
        BroadcastReceiver receiver = mGlobalReceiver.getValue();
        Intent intent = new Intent(Intent.ACTION_USER_SWITCHED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, currentUser + 1);
        receiver.onReceive(mApplication, intent);

        mNfcService.mIsSecureNfcCapable = true;
        when(mDeviceConfigFacade.getDefaultSecureNfcState()).thenReturn(false);
        when(mPreferences.getBoolean(eq("secure_nfc_on_" + currentUser), anyBoolean()))
                .thenReturn(true);
        clearInvocations(mPreferences, mDeviceHost);

        boolean result = adapterService.isNfcSecureEnabled();

        assertThat(result).isTrue();
        verify(mPreferences).getBoolean(eq("secure_nfc_on_" + currentUser), anyBoolean());
        verify(mDeviceHost).setNfcSecure(true);
    }

    @Test
    public void testIsReaderOptionSupported() {
        NfcService.NfcAdapterService adapterService = mNfcService.new NfcAdapterService();
        mNfcService.mReaderOptionCapable = true;
        boolean result = adapterService.isReaderOptionSupported();
        assertThat(result).isTrue();
    }

    @Test
    public void testGetTagIntentAppPreferenceForUser() throws RemoteException {
        NfcService.NfcAdapterService adapterService = mNfcService.new NfcAdapterService();
        mNfcService.mTagAppPrefList.put(1, new HashMap<>());
        Map result = adapterService.getTagIntentAppPreferenceForUser(1);
        assertThat(result).isNotNull();
    }

    @Test
    public void testGetWalletRoleHolder() {
        NfcService.NfcAdapterService adapterService = mNfcService.new NfcAdapterService();
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        when(NfcInjector.isPrivileged(anyInt())).thenReturn(false);
        when(mCardEmulationManager.isPreferredServicePackageNameForUser(anyString(), anyInt()))
                .thenReturn(true);
        when(android.permission.flags.Flags.walletRoleEnabled()).thenReturn(true);
        List<String> list = new ArrayList<>();
        list.add("com.android.test");
        when(mRoleManager.getRoleHolders(anyString())).thenReturn(list);
        boolean result = adapterService.setObserveMode(true, "com.android.test");
        assertThat(result).isFalse();
        verify(mDeviceHost).setObserveMode(anyBoolean());
    }

    @Test
    public void testIsWlcEnabled() throws RemoteException {
        mNfcService.mIsWlcCapable = true;
        mNfcService.mIsWlcEnabled = true;
        NfcService.NfcAdapterService adapterService = mNfcService.new NfcAdapterService();
        boolean result = adapterService.isWlcEnabled();
        assertThat(result).isTrue();
    }

    @Test
    public void testNotifyTestHceData() {
        NfcService.NfcAdapterService adapterService = mNfcService.new NfcAdapterService();
        doNothing().when(mCardEmulationManager).onHostCardEmulationData(anyInt(), any());
        when(android.nfc.Flags.nfcPersistLog()).thenReturn(true);
        adapterService.notifyTestHceData(Ndef.NDEF, "test".getBytes());
        verify(mCardEmulationManager).onHostCardEmulationData(anyInt(), any());
        verify(mNfcEventLog, atLeastOnce()).logEvent(any());
    }

    @Test
    public void testPausePolling() {
        mNfcService.onRfDiscoveryEvent(true);
        NfcService.NfcAdapterService adapterService = mNfcService.new NfcAdapterService();
        adapterService.pausePolling(100);
        verify(mDeviceHost).disableDiscovery();
        mLooper.dispatchAll();
    }

    @Test
    public void testRegisterControllerAlwaysOnListener() throws RemoteException {
        INfcControllerAlwaysOnListener iNfcControllerAlwaysOnListener
                = mock(INfcControllerAlwaysOnListener.class);
        NfcService.NfcAdapterService adapterService = mNfcService.new NfcAdapterService();
        adapterService.registerControllerAlwaysOnListener(iNfcControllerAlwaysOnListener);

        mNfcService.mAlwaysOnState = NfcAdapter.STATE_TURNING_ON;
        mNfcService.mState.set(NfcAdapter.STATE_OFF);
        mNfcService.mNfcAdapter.setControllerAlwaysOn(NfcOemExtension.ENABLE_DEFAULT);
        mLooper.dispatchAll();

        verify(iNfcControllerAlwaysOnListener).onControllerAlwaysOnChanged(anyBoolean());
    }

    @Test
    public void testSetNfcSecure() {
        NfcService.NfcAdapterService adapterService = mNfcService.new NfcAdapterService();
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(false);
        mNfcService.mIsSecureNfcEnabled = false;
        when(android.nfc.Flags.nfcPersistLog()).thenReturn(true);
        mNfcService.mIsHceCapable = true;
        adapterService.setNfcSecure(true);
        verify(mPreferencesEditor).putBoolean(anyString(), anyBoolean());
        verify(mPreferencesEditor, atLeastOnce()).apply();
        verify(mBackupManager).dataChanged();
        verify(mDeviceHost).setNfcSecure(true);
        verify(mNfcEventLog, times(2)).logEvent(any());
        verify(mCardEmulationManager).onTriggerRoutingTableUpdate();
    }

    @Test
    public void testSetScreenState() throws RemoteException {
        NfcService.NfcAdapterService adapterService = mNfcService.new NfcAdapterService();
        when(mScreenStateHelper.checkScreenState(anyBoolean()))
                .thenReturn(ScreenStateHelper.SCREEN_STATE_OFF_LOCKED);
        mNfcService.mScreenState = ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED;
        when(mDeviceHost.getNciVersion()).thenReturn(NCI_VERSION_1_0);
        mNfcService.mIsWatchType = true;
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        when(mCardEmulationManager.isRequiresScreenOnServiceExist()).thenReturn(false);
        adapterService.setScreenState();
        mLooper.dispatchAll();
        verify(mDeviceHost).doSetScreenState(anyInt(), anyBoolean());
    }

    @Test
    public void testSetWlcEnabled() {
        NfcService.NfcAdapterService adapterService = mNfcService.new NfcAdapterService();
        mNfcService.mIsWlcCapable = true;
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        when(android.nfc.Flags.nfcPersistLog()).thenReturn(true);
        adapterService.setWlcEnabled(true);
        verify(mPreferencesEditor).putBoolean(anyString(), anyBoolean());
        verify(mPreferencesEditor, atLeastOnce()).apply();
        verify(mBackupManager).dataChanged();
        verify(mBackupManager).dataChanged();
    }

    @Test
    public void testUnregisterControllerAlwaysOnListener() throws RemoteException {
        INfcControllerAlwaysOnListener iNfcControllerAlwaysOnListener
                = mock(INfcControllerAlwaysOnListener.class);
        NfcService.NfcAdapterService adapterService = mNfcService.new NfcAdapterService();
        adapterService.unregisterControllerAlwaysOnListener(iNfcControllerAlwaysOnListener);

        mNfcService.mAlwaysOnState = NfcAdapter.STATE_TURNING_ON;
        mNfcService.mState.set(NfcAdapter.STATE_OFF);
        mNfcService.mNfcAdapter.setControllerAlwaysOn(NfcOemExtension.ENABLE_DEFAULT);
        mLooper.dispatchAll();

        verify(iNfcControllerAlwaysOnListener, never()).onControllerAlwaysOnChanged(anyBoolean());
    }

    @Test
    public void testUnregisterOemExtensionCallback() throws RemoteException {
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        INfcOemExtensionCallback callback = mock(INfcOemExtensionCallback.class);
        IBinder binder = mock(IBinder.class);
        when(callback.asBinder()).thenReturn(binder);
        mNfcService.mNfcAdapter.registerOemExtensionCallback(callback);
        verify(binder).linkToDeath(any(), anyInt());
        ArgumentCaptor<INfcOemExtensionCallback> captor = ArgumentCaptor
                .forClass(INfcOemExtensionCallback.class);
        verify(mCardEmulationManager).setOemExtension(captor.capture());
        assertThat(captor.getValue()).isEqualTo(callback);

        mNfcService.mNfcAdapter.unregisterOemExtensionCallback(callback);
        verify(binder).unlinkToDeath(any(), anyInt());
        mNfcService.onHostCardEmulationActivated(Ndef.NDEF);
        verify(callback, times(1)).onCardEmulationActivated(anyBoolean());
    }

    @Test
    public void testUnregisterWlcStateListener() throws RemoteException {
        mNfcService.mIsWlcCapable = true;
        INfcWlcStateListener listener = mock(INfcWlcStateListener.class);
        mNfcService.mNfcAdapter.registerWlcStateListener(listener);
        Map<String, Integer> wlcDeviceInfo = new HashMap<>();
        wlcDeviceInfo.put(NfcCharging.VendorId, 1);
        wlcDeviceInfo.put(NfcCharging.TemperatureListener, 1);
        wlcDeviceInfo.put(NfcCharging.BatteryLevel, 1);
        wlcDeviceInfo.put(NfcCharging.State, 1);
        mNfcService.onWlcData(wlcDeviceInfo);
        verify(listener).onWlcStateChanged(any());

        mNfcService.mNfcAdapter.unregisterWlcStateListener(listener);
        mNfcService.onWlcData(wlcDeviceInfo);
        verify(listener, times(1)).onWlcStateChanged(any());
    }

    @Test
    public void testIsControllerAlwaysOn() throws RemoteException {
        mNfcService.mAlwaysOnState = NfcAdapter.STATE_ON;
        boolean result = mNfcService.mNfcAdapter.isControllerAlwaysOn();
        assertThat(result).isTrue();
    }


    @Test
    public void testDisableDta() throws RemoteException {
        mNfcService.mNfcAdapter.getNfcDtaInterface("com.android.test");
        NfcService.sIsDtaMode = true;
        mNfcService.mNfcDtaService.disableDta();
        verify(mDeviceHost).disableDtaMode();
        assertThat(NfcService.sIsDtaMode).isFalse();
    }

    @Test
    public void testEnableClient() throws RemoteException {
        mNfcService.mNfcAdapter.getNfcDtaInterface("com.android.test");
        boolean result = mNfcService.mNfcDtaService.enableClient("com.android.test",
                0, 0, 0);
        assertThat(result).isFalse();

    }

    @Test
    public void testEnableDta() throws RemoteException {
        mNfcService.mNfcAdapter.getNfcDtaInterface("com.android.test");
        NfcService.sIsDtaMode = false;
        mNfcService.mNfcDtaService.enableDta();
        verify(mDeviceHost).enableDtaMode();
        assertThat(NfcService.sIsDtaMode).isTrue();
    }

    @Test
    public void testEnableServer() throws RemoteException {
        mNfcService.mNfcAdapter.getNfcDtaInterface("com.android.test");
        boolean result = mNfcService.mNfcDtaService.enableServer("com.android.test",
                0, 0, 0, 0);
        assertThat(result).isFalse();
    }

    @Test
    public void testRegisterMessageService() throws RemoteException {
        mNfcService.mNfcAdapter.getNfcDtaInterface("com.android.test");
        boolean result = mNfcService.mNfcDtaService
                .registerMessageService("com.android.test");
        assertThat(result).isTrue();
    }

    @Test
    public void testPollingDelay() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_NDEF_TAG);
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.getConnectedTechnology()).thenReturn(TagTechnology.NDEF);
        when(tagEndpoint.reconnect()).thenReturn(false);
        mNfcService.mScreenState = ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED;
        msg.obj = tagEndpoint;
        handler.handleMessage(msg);
        verify(tagEndpoint).disconnect();
        verify(mDeviceHost).startStopPolling(false);
    }

    @Test
    public void testOnTagDisconnected() throws RemoteException {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_NDEF_TAG);
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.getConnectedTechnology()).thenReturn(TagTechnology.NDEF);
        NdefMessage ndefMessage = mock(NdefMessage.class);
        when(tagEndpoint.findAndReadNdef()).thenReturn(ndefMessage);
        msg.obj = tagEndpoint;
        handler.handleMessage(msg);
        ArgumentCaptor<DeviceHost.TagDisconnectedCallback> callbackArgumentCaptor
                = ArgumentCaptor.forClass(DeviceHost.TagDisconnectedCallback.class);
        verify(tagEndpoint, atLeastOnce()).startPresenceChecking(anyInt(),
                callbackArgumentCaptor.capture());

        DeviceHost.TagDisconnectedCallback callback = callbackArgumentCaptor.getValue();
        Assert.assertNotNull(callback);
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        INfcOemExtensionCallback oemExtensionCallback = mock(INfcOemExtensionCallback.class);
        when(oemExtensionCallback.asBinder()).thenReturn(mock(IBinder.class));
        mNfcService.mNfcAdapter.registerOemExtensionCallback(oemExtensionCallback);
        callback.onTagDisconnected();
        assertThat(mNfcService.mCookieUpToDate).isLessThan(0);
        verify(oemExtensionCallback).onTagConnected(false);
    }

    @Test
    public void testSetFirmwareExitFrameTable() throws Exception{
        createNfcServiceWithoutStatsdUtils();
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        ArgumentCaptor<ExitFrame[]> frameCaptor = ArgumentCaptor.forClass(ExitFrame[].class);
        ArgumentCaptor<byte[]> timeoutCaptor = ArgumentCaptor.forClass(byte[].class);
        when(mDeviceHost.setFirmwareExitFrameTable(any(), any())).thenReturn(true);

        boolean result = mNfcService.setFirmwareExitFrameTable(
            Collections.singletonList(new ExitFrame("1234")), 5000);

        assertTrue("setFirmwareExitFrameTable should return true", result);
        verify(mDeviceHost).setFirmwareExitFrameTable(frameCaptor.capture(),
                timeoutCaptor.capture());
        ExitFrame[] frames = frameCaptor.getValue();
        assertThat(frames).hasLength(1);
        assertThat(frames[0].getData()).isEqualTo(HexFormat.of().parseHex("1234"));
        byte[] timeoutBytes = timeoutCaptor.getValue();
        // 5000 in little-endian bytes
        assertArrayEquals(new byte[] {(byte) 0x88, 0x13}, timeoutBytes);
        verify(mStatsdUtils).logExitFrameTableChanged(1, 5000);
    }

    @Test
    public void testSetFirmwareExitFrameTable_largeTimeout() throws Exception{
        createNfcServiceWithoutStatsdUtils();
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        ArgumentCaptor<byte[]> timeoutCaptor = ArgumentCaptor.forClass(byte[].class);
        when(mDeviceHost.setFirmwareExitFrameTable(any(), any())).thenReturn(true);

        boolean result = mNfcService.setFirmwareExitFrameTable(
            Collections.singletonList(new ExitFrame("1234")), 500000);

        assertTrue("setFirmwareExitFrameTable should return true", result);
        verify(mDeviceHost).setFirmwareExitFrameTable(any(), timeoutCaptor.capture());
        byte[] timeoutBytes = timeoutCaptor.getValue();
        assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xFF}, timeoutBytes);
        verify(mStatsdUtils).logExitFrameTableChanged(1, 500000);
    }

    @Test
    public void testSetFirmwareExitFrameTable_nfcDisabled() throws Exception {
        createNfcServiceWithoutStatsdUtils();
        mNfcService.mState.set(NfcAdapter.STATE_OFF);

        boolean result = mNfcService.setFirmwareExitFrameTable(
            Collections.singletonList(new ExitFrame("1234")), 5000);

        assertFalse("setFirmwareExitFrameTable should return false", result);
        verify(mDeviceHost, never()).setFirmwareExitFrameTable(any(), any());
    }

    @Test
    public void testSetFirmwareExitFrameTable_hceActive() throws Exception {
        createNfcServiceWithoutStatsdUtils();
        mNfcService.mState.set(NfcAdapter.STATE_ON);
        when(mCardEmulationManager.isHostCardEmulationActivated())
            .thenReturn(true);

        boolean result = mNfcService.setFirmwareExitFrameTable(
            Collections.singletonList(new ExitFrame("1234")), 5000);

        assertFalse("setFirmwareExitFrameTable should return false", result);
        verify(mDeviceHost, never()).setFirmwareExitFrameTable(any(), any());
    }

    @Test
    public void testApplyRouting_whenNfcDisabled_doesNothing() {
        // Set NFC state to OFF
        mNfcService.mState.set(NfcAdapter.STATE_OFF);

        // applyRouting is package-private, can be called directly from test
        mNfcService.applyRouting(true);

        // Verify that discovery methods on DeviceHost are not called, as applyRouting should return
        // early
        verify(mDeviceHost, never()).enableDiscovery(any(), anyBoolean());
        verify(mDeviceHost, never()).disableDiscovery();
        verify(mDeviceHost, never()).commitRouting();
    }

    @Test
    public void testApplyRouting_whenNfcTurningOn_doesNothing() {
        // Set NFC state to TURNING_ON
        mNfcService.mState.set(NfcAdapter.STATE_TURNING_ON);

        // applyRouting is package-private, can be called directly from test
        mNfcService.applyRouting(true);

        // Verify that discovery methods on DeviceHost are not called,
        // as applyRouting should return early
        verify(mDeviceHost, never()).enableDiscovery(any(), anyBoolean());
        verify(mDeviceHost, never()).disableDiscovery();
        verify(mDeviceHost, never()).commitRouting();
    }

    @Test
    public void testApplyRouting_whenNfcTurningOff_doesNothing() {
        // Set NFC state to TURNING_OFF
        mNfcService.mState.set(NfcAdapter.STATE_TURNING_OFF);

        // applyRouting is package-private, can be called directly from test
        mNfcService.applyRouting(true);

        // Verify that discovery methods on DeviceHost are not called,
        // as applyRouting should return early
        verify(mDeviceHost, never()).enableDiscovery(any(), anyBoolean());
        verify(mDeviceHost, never()).disableDiscovery();
        verify(mDeviceHost, never()).commitRouting();
    }

    @Test
    public void testDeviceSupportsNfcSecure_HceAndSecureNfcCapable_ReturnsTrue() {
        // Arrange: HCE is capable and secure NFC is configured as capable
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))
                .thenReturn(true);
        when(mDeviceConfigFacade.isSecureNfcCapable()).thenReturn(true);

        // Act: Create a new NfcService instance to apply the new configuration
        createNfcService();

        // Assert: deviceSupportsNfcSecure should be true
        assertTrue(mNfcService.mNfcAdapter.deviceSupportsNfcSecure());
    }

    @Test
    public void testDeviceSupportsNfcSecure_HceCapableAndNotSecureNfcCapable_ReturnsFalse() {
        // Arrange: HCE is capable but secure NFC is not configured as capable
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))
                .thenReturn(true);
        when(mDeviceConfigFacade.isSecureNfcCapable()).thenReturn(false);

        // Act: Create a new NfcService instance to apply the new configuration
        createNfcService();

        // Assert: deviceSupportsNfcSecure should be false
        assertFalse(mNfcService.mNfcAdapter.deviceSupportsNfcSecure());
    }

    @Test
    public void testDeviceSupportsNfcSecure_NotHceCapableAndSecureNfcCapable_ReturnsFalse() {
        // Arrange: HCE is not capable but secure NFC is configured as capable
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))
                .thenReturn(false);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF))
                .thenReturn(false);
        when(mDeviceConfigFacade.isSecureNfcCapable()).thenReturn(true);

        // Act: Create a new NfcService instance to apply the new configuration
        createNfcService();

        // Assert: deviceSupportsNfcSecure should be false
        assertFalse(mNfcService.mNfcAdapter.deviceSupportsNfcSecure());
    }

    @Test
    public void testDeviceSupportsNfcSecure_NotHceCapableAndNotSecureNfcCapable_ReturnsFalse() {
        // Arrange: HCE is not capable and secure NFC is not configured as capable
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))
                .thenReturn(false);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF))
                .thenReturn(false);
        when(mDeviceConfigFacade.isSecureNfcCapable()).thenReturn(false);

        // Act: Create a new NfcService instance to apply the new configuration
        createNfcService();

        // Assert: deviceSupportsNfcSecure should be false
        assertFalse(mNfcService.mNfcAdapter.deviceSupportsNfcSecure());
    }

    private DeviceHost.TagEndpoint setupMockTagEndpoint() {
        DeviceHost.TagEndpoint mockTagEndpoint = mock(DeviceHost.TagEndpoint.class);
        mNfcService.mObjectMap.put(1, mockTagEndpoint);
        return mockTagEndpoint;
    }

    @Test
    public void onRfDiscoveryEvent_discoveryStopped_stopsPresenceChecking() {
        // Arrange
        DeviceHost.TagEndpoint mockTagEndpoint = setupMockTagEndpoint();
        DeviceHost.DeviceHostListener listener = mDeviceHostListener.getValue();

        // Act
        listener.onRfDiscoveryEvent(false);
        mLooper.dispatchAll();

        // Assert
        verify(mockTagEndpoint).stopPresenceChecking(false);
    }

    @Test
    public void onRfDiscoveryEvent_discoveryStarted_doesNotStopPresenceChecking() {
        // Arrange
        DeviceHost.TagEndpoint mockTagEndpoint = setupMockTagEndpoint();
        DeviceHost.DeviceHostListener listener = mDeviceHostListener.getValue();

        // Act
        listener.onRfDiscoveryEvent(true);

        // Assert
        verify(mockTagEndpoint, never()).stopPresenceChecking(anyBoolean());
    }

    @Test
    public void onTagRfDiscovered_tagNotDiscovered_stopsPresenceChecking() {
        // Arrange
        DeviceHost.TagEndpoint mockTagEndpoint = setupMockTagEndpoint();
        DeviceHost.DeviceHostListener listener = mDeviceHostListener.getValue();

        // Act
        listener.onTagRfDiscovered(false);
        mLooper.dispatchAll();

        // Assert
        verify(mockTagEndpoint).stopPresenceChecking(false);
    }

    @Test
    public void onTagRfDiscovered_tagDiscovered_doesNotStopPresenceChecking() {
        // Arrange
        DeviceHost.TagEndpoint mockTagEndpoint = setupMockTagEndpoint();
        DeviceHost.DeviceHostListener listener = mDeviceHostListener.getValue();

        // Act
        listener.onTagRfDiscovered(true);

        // Assert
        verify(mockTagEndpoint, never()).stopPresenceChecking(anyBoolean());
    }

    @Test
    public void testMsg_Ndef_Tag_GestureExchange() throws RemoteException {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_NDEF_TAG);
        mNfcService.mState.set(NfcAdapter.STATE_ON);

        android.nfc.IReaderCallback gestureCallback = mock(android.nfc.IReaderCallback.class);
        mNfcService.mNfcAdapter.registerGestureExchangeCallback(gestureCallback);

        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.getConnectedTechnology()).thenReturn(TagTechnology.ISO_DEP);
        when(tagEndpoint.getUid()).thenReturn(new byte[]{0x01, 0x02});
        when(tagEndpoint.getTechList()).thenReturn(new int[]{TagTechnology.ISO_DEP});
        when(tagEndpoint.getTechExtras()).thenReturn(new Bundle[]{new Bundle()});
        when(tagEndpoint.getHandle()).thenReturn(1);

        byte[] respData = new byte[]{(byte) 0x90, 0x00};
        when(tagEndpoint.transceive(any(), eq(false), any())).thenReturn(respData);

        msg.obj = tagEndpoint;

        mNfcService.mGestureExchangeEnabled = true;
        when(android.provider.Settings.Secure.getString(
                any(), eq(NfcService.GESTURE_EXCHANGE_COMPONENT_SETTINGS_KEY)))
                .thenReturn("some_component");
        mNfcService.mCookieUpToDate = -1;

        handler.handleMessage(msg);

        Assert.assertNotEquals(-1, mNfcService.mCookieUpToDate);
        verify(tagEndpoint, atLeastOnce()).startPresenceChecking(anyInt(), any());
    }

    @Test
    public void testMsg_Ndef_Tag_GestureExchange_CookieAlreadySet() throws RemoteException {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_NDEF_TAG);
        mNfcService.mState.set(NfcAdapter.STATE_ON);

        android.nfc.IReaderCallback gestureCallback = mock(android.nfc.IReaderCallback.class);
        mNfcService.mNfcAdapter.registerGestureExchangeCallback(gestureCallback);

        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.getConnectedTechnology()).thenReturn(TagTechnology.ISO_DEP);
        when(tagEndpoint.getUid()).thenReturn(new byte[]{0x01, 0x02});
        when(tagEndpoint.getTechList()).thenReturn(new int[]{TagTechnology.ISO_DEP});
        when(tagEndpoint.getTechExtras()).thenReturn(new Bundle[]{new Bundle()});
        when(tagEndpoint.getHandle()).thenReturn(1);

        byte[] respData = new byte[]{(byte) 0x90, 0x00};
        when(tagEndpoint.transceive(any(), eq(false), any())).thenReturn(respData);

        msg.obj = tagEndpoint;

        mNfcService.mGestureExchangeEnabled = true;
        when(android.provider.Settings.Secure.getString(
                any(), eq(NfcService.GESTURE_EXCHANGE_COMPONENT_SETTINGS_KEY)))
                .thenReturn("some_component");
        mNfcService.mCookieUpToDate = 12345L;

        handler.handleMessage(msg);

        Assert.assertNotEquals(12345L, mNfcService.mCookieUpToDate);
        Assert.assertTrue(mNfcService.mCookieUpToDate >= 0);
        verify(tagEndpoint, atLeastOnce()).startPresenceChecking(anyInt(), any());
    }

    @Test
    public void testGetT4tNfceeAid() {
        byte[] aidBytes = {(byte) 0xD2, 0x76, 0x00, 0x00, (byte) 0x85, 0x01, 0x01};
        when(mDeviceHost.getT4tNfceeAid()).thenReturn(aidBytes);
        String aid = mNfcService.getT4tNfceeAid();
        assertThat(aid).isEqualTo("D2760000850101");

        when(mDeviceHost.getT4tNfceeAid()).thenReturn(null);
        aid = mNfcService.getT4tNfceeAid();
        assertThat(aid).isNull();
    }
}
