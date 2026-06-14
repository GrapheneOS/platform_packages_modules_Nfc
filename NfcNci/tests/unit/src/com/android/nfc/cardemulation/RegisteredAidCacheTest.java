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

package com.android.nfc.cardemulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.nfc.ComponentNameAndUser;
import android.nfc.Flags;
import android.nfc.INfcOemExtensionCallback;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.proto.ProtoOutputStream;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.NfcService;
import com.android.nfc.R;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RunWith(AndroidJUnit4.class)
public class RegisteredAidCacheTest {

    private static final String PREFIX_AID = "ASDASD*";
    private static final String SUBSET_AID = "ASDASD#";
    private static final String EXACT_AID = "TASDASD";
    private static final String PAYMENT_AID_1 = "A000000004101012";
    private static final String PAYMENT_AID_2 = "A000000004101018";
    private static final String NON_PAYMENT_AID_1 = "F053414950454D";
    private static final String PREFIX_PAYMENT_AID = "A000000004*";
    private static final String NFC_FOREGROUND_PACKAGE_NAME = "com.android.test.foregroundnfc";
    private static final String NON_PAYMENT_NFC_PACKAGE_NAME = "com.android.test.nonpaymentnfc";
    private static final String WALLET_HOLDER_PACKAGE_NAME = "com.android.test.walletroleholder";
    private static final String WALLET_HOLDER_2_PACKAGE_NAME = "com.android.test.walletroleholder2";

    private static final ComponentName WALLET_PAYMENT_SERVICE =
            new ComponentName(
                    WALLET_HOLDER_PACKAGE_NAME,
                    "com.android.test.walletroleholder.WalletRoleHolderApduService");

    private static final ComponentName WALLET_PAYMENT_SERVICE_2 =
            new ComponentName(
                    WALLET_HOLDER_PACKAGE_NAME,
                    "com.android.test.walletroleholder.XWalletRoleHolderApduService");
    private static final ComponentName FOREGROUND_SERVICE =
            new ComponentName(
                    NFC_FOREGROUND_PACKAGE_NAME,
                    "com.android.test.foregroundnfc.ForegroundApduService");
    private static final ComponentName FOREGROUND_SERVICE_2 =
            new ComponentName(
                    NFC_FOREGROUND_PACKAGE_NAME,
                    "com.android.test.foregroundnfc.ForegroundApduService2");
    private static final ComponentName NON_PAYMENT_SERVICE =
            new ComponentName(
                    NON_PAYMENT_NFC_PACKAGE_NAME,
                    "com.android.test.nonpaymentnfc.NonPaymentApduService");

    private static final ComponentName PAYMENT_SERVICE =
            new ComponentName(
                    WALLET_HOLDER_2_PACKAGE_NAME,
                    "com.android.test.walletroleholder.WalletRoleHolderXApduService");

    private static final int USER_ID = 0;
    private static final UserHandle USER_HANDLE = UserHandle.of(USER_ID);
    RegisteredAidCache mRegisteredAidCache;
    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private WalletRoleObserver mWalletRoleObserver;
    @Mock
    private AidRoutingManager mAidRoutingManager;
    @Mock
    private UserManager mUserManager;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private NfcService mNfcService;
    @Captor
    private ArgumentCaptor<HashMap<String, AidRoutingManager.AidEntry>> mRoutingEntryMapCaptor;
    private MockitoSession mStaticMockSession;

    private static ApduServiceInfo createServiceInfoForAidRouting(
            ComponentName componentName,
            boolean onHost,
            List<String> aids,
            List<String> categories,
            boolean requiresUnlock,
            boolean requiresScreenOn,
            int uid,
            boolean isCategoryOtherServiceEnabled) {
        return createServiceInfoForAidRouting(componentName,
                onHost,
                aids,
                categories,
                requiresUnlock,
                requiresScreenOn,
                uid,
                isCategoryOtherServiceEnabled,
                false);
    }

    private static ApduServiceInfo createServiceInfoForAidRouting(
            ComponentName componentName,
            boolean onHost,
            List<String> aids,
            List<String> categories,
            boolean requiresUnlock,
            boolean requiresScreenOn,
            int uid,
            boolean isCategoryOtherServiceEnabled,
            boolean wantsRoleHolderPriority) {
        ApduServiceInfo apduServiceInfo = Mockito.mock(ApduServiceInfo.class);
        when(apduServiceInfo.isOnHost()).thenReturn(onHost);
        when(apduServiceInfo.getAids()).thenReturn(aids);
        when(apduServiceInfo.getUid()).thenReturn(uid);
        when(apduServiceInfo.requiresUnlock()).thenReturn(requiresUnlock);
        when(apduServiceInfo.requiresScreenOn()).thenReturn(requiresScreenOn);
        when(apduServiceInfo.isCategoryOtherServiceEnabled())
                .thenReturn(isCategoryOtherServiceEnabled);
        when(apduServiceInfo.getComponent()).thenReturn(componentName);
        when(apduServiceInfo.wantsRoleHolderPriority()).thenReturn(wantsRoleHolderPriority);
        for (int i = 0; i < aids.size(); i++) {
            String aid = aids.get(i);
            String category = categories.get(i);
            when(apduServiceInfo.getCategoryForAid(eq(aid))).thenReturn(category);
        }
        return apduServiceInfo;
    }

    @Before
    public void setUp() {
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(ActivityManager.class)
                        .mockStatic(com.android.nfc.module.nonexported.flags.Flags.class)
                        .mockStatic(NfcService.class)
                        .mockStatic(Flags.class)
                        .strictness(Strictness.LENIENT)
                        .initMocks(this)
                        .startMocking();
        when(ActivityManager.getCurrentUser()).thenReturn(USER_ID);
        when(NfcService.getInstance()).thenReturn(mNfcService);
        when(mNfcService.getNciVersion()).thenReturn(NfcService.NCI_VERSION_1_0);
        when(mUserManager.getProfileParent(eq(USER_HANDLE))).thenReturn(USER_HANDLE);
        when(mContext.createContextAsUser(any(), anyInt())).thenReturn(mContext);
        when(mContext.getSystemService(eq(UserManager.class))).thenReturn(mUserManager);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getBoolean(R.bool.telephony_subscription_routing_enabled)).thenReturn(true);
    }

    @After
    public void tearDown() {
        if (mStaticMockSession != null) {
            mStaticMockSession.finishMocking();
        }
    }

    @Test
    public void testConstructor_supportsPrefixAndSubset() {
        supportPrefixAndSubset(true);
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);

        verify(mAidRoutingManager).supportsAidPrefixRouting();
        verify(mAidRoutingManager).supportsAidSubsetRouting();
        assertTrue(mRegisteredAidCache.supportsAidPrefixRegistration());
        assertTrue(mRegisteredAidCache.supportsAidSubsetRegistration());
    }

    @Test
    public void testConstructor_doesNotSupportsPrefixAndSubset() {
        supportPrefixAndSubset(false);
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);

        verify(mAidRoutingManager).supportsAidPrefixRouting();
        verify(mAidRoutingManager).supportsAidSubsetRouting();
        assertFalse(mRegisteredAidCache.supportsAidPrefixRegistration());
        assertFalse(mRegisteredAidCache.supportsAidSubsetRegistration());
    }

    @Test
    public void testAidStaticMethods() {
        assertTrue(RegisteredAidCache.isPrefix(PREFIX_AID));
        assertTrue(RegisteredAidCache.isSubset(SUBSET_AID));
        assertTrue(RegisteredAidCache.isExact(EXACT_AID));

        assertFalse(RegisteredAidCache.isPrefix(EXACT_AID));
        assertFalse(RegisteredAidCache.isSubset(EXACT_AID));
        assertFalse(RegisteredAidCache.isExact(PREFIX_AID));
        assertFalse(RegisteredAidCache.isExact(SUBSET_AID));

        assertFalse(RegisteredAidCache.isPrefix(null));
        assertFalse(RegisteredAidCache.isSubset(null));
        assertFalse(RegisteredAidCache.isExact(null));
    }

    @Test
    public void testAidConflictResolution_walletRoleEnabledNfcDisabled_foregroundWins() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);
        mRegisteredAidCache.mNfcEnabled = false;

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        WALLET_PAYMENT_SERVICE,
                        true,
                        List.of(PAYMENT_AID_1, NON_PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_PAYMENT, CardEmulation.CATEGORY_OTHER),
                        false,
                        false,
                        USER_ID,
                        true));
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        FOREGROUND_SERVICE,
                        true,
                        List.of(PAYMENT_AID_1, NON_PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_PAYMENT, CardEmulation.CATEGORY_OTHER),
                        false,
                        false,
                        USER_ID,
                        true));
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        NON_PAYMENT_SERVICE,
                        true,
                        List.of(NON_PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_OTHER),
                        false,
                        false,
                        USER_ID,
                        true));

        mRegisteredAidCache.generateUserApduServiceInfoLocked(USER_ID, apduServiceInfos);
        mRegisteredAidCache.generateServiceMapLocked(apduServiceInfos);
        mRegisteredAidCache.onPreferredForegroundServiceChanged(
                new ComponentNameAndUser(USER_ID, FOREGROUND_SERVICE));
        RegisteredAidCache.AidResolveInfo resolveInfo =
                mRegisteredAidCache.resolveAid(PAYMENT_AID_1);

        verify(mAidRoutingManager).supportsAidPrefixRouting();
        verify(mAidRoutingManager).supportsAidSubsetRouting();
        assertEquals(FOREGROUND_SERVICE, resolveInfo.defaultService.getComponent());
        assertEquals(
                new ComponentNameAndUser(USER_ID, FOREGROUND_SERVICE),
                mRegisteredAidCache.getPreferredService());
        assertEquals(1, resolveInfo.services.size());
        assertEquals(CardEmulation.CATEGORY_PAYMENT, resolveInfo.category);
        verifyNoMoreInteractions(mAidRoutingManager);
    }

    @Test
    public void testAidConflictResolution_walletRoleEnabledNfcEnabled_walletWins() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);
        mRegisteredAidCache.mNfcEnabled = true;

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        WALLET_PAYMENT_SERVICE,
                        true,
                        List.of(PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_PAYMENT),
                        false,
                        true,
                        USER_ID,
                        true));
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        PAYMENT_SERVICE,
                        true,
                        List.of(PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_PAYMENT),
                        false,
                        true,
                        USER_ID,
                        true));
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        NON_PAYMENT_SERVICE,
                        true,
                        List.of(NON_PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_OTHER),
                        false,
                        false,
                        USER_ID,
                        true));

        mRegisteredAidCache.generateUserApduServiceInfoLocked(USER_ID, apduServiceInfos);
        mRegisteredAidCache.generateServiceMapLocked(apduServiceInfos);
        mRegisteredAidCache.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);
        RegisteredAidCache.AidResolveInfo paymentResolveInfo =
                mRegisteredAidCache.resolveAid(PAYMENT_AID_1);
        RegisteredAidCache.AidResolveInfo nonPaymentResolveInfo =
                mRegisteredAidCache.resolveAid(NON_PAYMENT_AID_1);

        assertEquals(WALLET_PAYMENT_SERVICE, paymentResolveInfo.defaultService.getComponent());
        assertEquals(1, paymentResolveInfo.services.size());
        assertEquals(CardEmulation.CATEGORY_PAYMENT, paymentResolveInfo.category);
        assertEquals(NON_PAYMENT_SERVICE, nonPaymentResolveInfo.defaultService.getComponent());
        assertEquals(1, nonPaymentResolveInfo.services.size());
        assertEquals(CardEmulation.CATEGORY_OTHER, nonPaymentResolveInfo.category);
        verify(mAidRoutingManager).configureRouting(mRoutingEntryMapCaptor.capture(), eq(false),
                eq(false));
        HashMap<String, AidRoutingManager.AidEntry> routingEntries =
                mRoutingEntryMapCaptor.getValue();
        assertTrue(routingEntries.containsKey(PAYMENT_AID_1));
        assertTrue(routingEntries.containsKey(NON_PAYMENT_AID_1));
        assertTrue(routingEntries.get(PAYMENT_AID_1).isOnHost);
        assertTrue(routingEntries.get(NON_PAYMENT_AID_1).isOnHost);
        assertNull(routingEntries.get(PAYMENT_AID_1).offHostSE);
        assertNull(routingEntries.get(NON_PAYMENT_AID_1).offHostSE);
        assertTrue(mRegisteredAidCache.isRequiresScreenOnServiceExist());
    }

    @Test
    public void testAidConflictResolution_walletRoleEnabledNfcEnabled_associatedRoleServices()
            throws PackageManager.NameNotFoundException {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        when(Flags.nfcAssociatedRoleServices()).thenReturn(true);
        when(mPackageManager.getProperty(
                eq(CardEmulation.PROPERTY_ALLOW_SHARED_ROLE_PRIORITY),
                eq(WALLET_HOLDER_PACKAGE_NAME)))
                .thenReturn(new PackageManager.Property(
                        CardEmulation.PROPERTY_ALLOW_SHARED_ROLE_PRIORITY,
                        true, WALLET_HOLDER_PACKAGE_NAME, null));

        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        mRegisteredAidCache.mNfcEnabled = true;

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE,
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false,
                true,
                USER_ID,
                true,
                true));
        apduServiceInfos.add(createServiceInfoForAidRouting(
                PAYMENT_SERVICE,
                true,
                List.of(PAYMENT_AID_2),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false,
                true,
                USER_ID,
                true,
                true));

        mRegisteredAidCache.generateUserApduServiceInfoLocked(USER_ID, apduServiceInfos);
        mRegisteredAidCache.generateServiceMapLocked(apduServiceInfos);
        mRegisteredAidCache.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);

        mRegisteredAidCache.mAssociatedRoleServices = new HashSet<>(apduServiceInfos);
        mRegisteredAidCache.generateAidCacheLocked();

        RegisteredAidCache.AidResolveInfo paymentResolveInfo
                = mRegisteredAidCache.resolveAid(PAYMENT_AID_2);

        assertNotNull(paymentResolveInfo.defaultService);
        assertEquals(PAYMENT_SERVICE, paymentResolveInfo.defaultService.getComponent());
        assertEquals(CardEmulation.CATEGORY_PAYMENT, paymentResolveInfo.category);
        assertEquals(1, paymentResolveInfo.services.size());

        assertTrue(mRegisteredAidCache.isPreferredServicePackageNameForUser(
                WALLET_HOLDER_PACKAGE_NAME, USER_ID));
        assertTrue(mRegisteredAidCache.isPreferredServicePackageNameForUser(
                WALLET_HOLDER_2_PACKAGE_NAME, USER_ID));
    }

    @Test
    public void testAidConflictResolution_walletRoleEnabledNfcEnabledPreFixAid_walletWins() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(true);
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);
        mRegisteredAidCache.mNfcEnabled = true;

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        WALLET_PAYMENT_SERVICE,
                        true,
                        List.of(PREFIX_PAYMENT_AID),
                        List.of(CardEmulation.CATEGORY_PAYMENT),
                        false,
                        true,
                        USER_ID,
                        true));
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        PAYMENT_SERVICE,
                        true,
                        List.of(PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_PAYMENT),
                        false,
                        true,
                        USER_ID,
                        true));
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        NON_PAYMENT_SERVICE,
                        true,
                        List.of(NON_PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_OTHER),
                        false,
                        false,
                        USER_ID,
                        true));

        mRegisteredAidCache.generateUserApduServiceInfoLocked(USER_ID, apduServiceInfos);
        mRegisteredAidCache.generateServiceMapLocked(apduServiceInfos);
        mRegisteredAidCache.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);
        RegisteredAidCache.AidResolveInfo paymentResolveInfo =
                mRegisteredAidCache.resolveAid(PAYMENT_AID_1);
        RegisteredAidCache.AidResolveInfo nonPaymentResolveInfo =
                mRegisteredAidCache.resolveAid(NON_PAYMENT_AID_1);

        assertEquals(WALLET_PAYMENT_SERVICE, paymentResolveInfo.defaultService.getComponent());
        assertEquals(1, paymentResolveInfo.services.size());
        assertEquals(CardEmulation.CATEGORY_PAYMENT, paymentResolveInfo.category);
        assertEquals(NON_PAYMENT_SERVICE, nonPaymentResolveInfo.defaultService.getComponent());
        assertEquals(1, nonPaymentResolveInfo.services.size());
        assertEquals(CardEmulation.CATEGORY_OTHER, nonPaymentResolveInfo.category);
    }

    @Test
    public void testAidConflictResolution_walletRoleEnabled_twoServicesOnWallet_firstServiceWins() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        WALLET_PAYMENT_SERVICE,
                        true,
                        List.of(PAYMENT_AID_1, NON_PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_PAYMENT, CardEmulation.CATEGORY_OTHER),
                        false,
                        false,
                        USER_ID,
                        true));
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        WALLET_PAYMENT_SERVICE_2,
                        true,
                        List.of(PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_PAYMENT),
                        false,
                        false,
                        USER_ID,
                        true));
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        PAYMENT_SERVICE,
                        true,
                        List.of(PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_PAYMENT),
                        false,
                        false,
                        USER_ID,
                        true));

        mRegisteredAidCache.generateUserApduServiceInfoLocked(USER_ID, apduServiceInfos);
        mRegisteredAidCache.generateServiceMapLocked(apduServiceInfos);
        mRegisteredAidCache.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);
        RegisteredAidCache.AidResolveInfo resolveInfo =
                mRegisteredAidCache.resolveAid(PAYMENT_AID_1);
        assertEquals(WALLET_PAYMENT_SERVICE, resolveInfo.defaultService.getComponent());
        assertEquals(2, resolveInfo.services.size());
        assertEquals(CardEmulation.CATEGORY_PAYMENT, resolveInfo.category);
    }

    @Test
    public void testAidConflictResolution_walletOtherServiceDisabled_nonDefaultServiceWins() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        WALLET_PAYMENT_SERVICE,
                        true,
                        List.of(PAYMENT_AID_1, NON_PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_PAYMENT, CardEmulation.CATEGORY_OTHER),
                        false,
                        false,
                        USER_ID,
                        false));
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        PAYMENT_SERVICE,
                        true,
                        List.of(PAYMENT_AID_1, NON_PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_PAYMENT, CardEmulation.CATEGORY_OTHER),
                        false,
                        false,
                        USER_ID,
                        true));

        mRegisteredAidCache.generateUserApduServiceInfoLocked(USER_ID, apduServiceInfos);
        mRegisteredAidCache.generateServiceMapLocked(apduServiceInfos);
        mRegisteredAidCache.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);
        RegisteredAidCache.AidResolveInfo resolveInfo =
                mRegisteredAidCache.resolveAid(NON_PAYMENT_AID_1);
        assertEquals(PAYMENT_SERVICE, resolveInfo.defaultService.getComponent());
        assertEquals(1, resolveInfo.services.size());
    }

    @Test
    public void testAidConflictResolution_walletOtherServiceDisabled_emptyServices() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        WALLET_PAYMENT_SERVICE,
                        true,
                        List.of(PAYMENT_AID_1, NON_PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_PAYMENT, CardEmulation.CATEGORY_OTHER),
                        false,
                        false,
                        USER_ID,
                        false));

        mRegisteredAidCache.generateUserApduServiceInfoLocked(USER_ID, apduServiceInfos);
        mRegisteredAidCache.generateServiceMapLocked(apduServiceInfos);
        mRegisteredAidCache.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);
        RegisteredAidCache.AidResolveInfo resolveInfo =
                mRegisteredAidCache.resolveAid(NON_PAYMENT_AID_1);
        assertNull(resolveInfo.defaultService);
        assertTrue(resolveInfo.services.isEmpty());
    }

    @Test
    public void testOnServicesUpdated_walletRoleEnabled() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);
        mRegisteredAidCache.mNfcEnabled = true;

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        WALLET_PAYMENT_SERVICE,
                        true,
                        List.of(PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_PAYMENT),
                        false,
                        true,
                        USER_ID,
                        true));
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        PAYMENT_SERVICE,
                        true,
                        List.of(PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_PAYMENT),
                        false,
                        true,
                        USER_ID,
                        true));
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        NON_PAYMENT_SERVICE,
                        true,
                        List.of(NON_PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_OTHER),
                        false,
                        true,
                        USER_ID,
                        true));

        mRegisteredAidCache.onServicesUpdated(USER_ID, apduServiceInfos);

        verify(mAidRoutingManager).supportsAidPrefixRouting();
        verify(mAidRoutingManager).supportsAidSubsetRouting();
        assertTrue(mRegisteredAidCache.mAidServices.containsKey(PAYMENT_AID_1));
        assertTrue(mRegisteredAidCache.mAidServices.containsKey(NON_PAYMENT_AID_1));
        assertEquals(2, mRegisteredAidCache.mAidServices.get(PAYMENT_AID_1).size());
        assertEquals(1, mRegisteredAidCache.mAidServices.get(NON_PAYMENT_AID_1).size());
        assertEquals(
                WALLET_PAYMENT_SERVICE,
                mRegisteredAidCache.mAidServices.get(PAYMENT_AID_1).get(0).service.getComponent());
        assertEquals(
                PAYMENT_SERVICE,
                mRegisteredAidCache.mAidServices.get(PAYMENT_AID_1).get(1).service.getComponent());
        verify(mAidRoutingManager).configureRouting(mRoutingEntryMapCaptor.capture(), eq(false),
                eq(false));
        HashMap<String, AidRoutingManager.AidEntry> routingEntries =
                mRoutingEntryMapCaptor.getValue();
        assertTrue(routingEntries.containsKey(NON_PAYMENT_AID_1));
        assertTrue(routingEntries.get(NON_PAYMENT_AID_1).isOnHost);
        assertNull(routingEntries.get(NON_PAYMENT_AID_1).offHostSE);
        assertTrue(mRegisteredAidCache.isRequiresScreenOnServiceExist());
    }

    @Test
    public void testOnNfcEnabled() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        WALLET_PAYMENT_SERVICE,
                        true,
                        List.of(PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_PAYMENT),
                        false,
                        false,
                        USER_ID,
                        true));
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        PAYMENT_SERVICE,
                        true,
                        List.of(PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_PAYMENT),
                        false,
                        false,
                        USER_ID,
                        true));
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        NON_PAYMENT_SERVICE,
                        true,
                        List.of(NON_PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_OTHER),
                        false,
                        false,
                        USER_ID,
                        true));

        mRegisteredAidCache.generateUserApduServiceInfoLocked(USER_ID, apduServiceInfos);
        mRegisteredAidCache.generateServiceMapLocked(apduServiceInfos);
        mRegisteredAidCache.onNfcEnabled();

        verify(mAidRoutingManager).supportsAidPrefixRouting();
        verify(mAidRoutingManager).supportsAidSubsetRouting();
        verify(mAidRoutingManager).configureRouting(mRoutingEntryMapCaptor.capture(), eq(true),
                eq(false));
        assertFalse(mRegisteredAidCache.isRequiresScreenOnServiceExist());
    }

    @Test
    public void testOnNfcDisabled() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);
        mRegisteredAidCache.onNfcDisabled();

        verify(mAidRoutingManager).supportsAidPrefixRouting();
        verify(mAidRoutingManager).supportsAidSubsetRouting();
        verify(mAidRoutingManager).onNfccRoutingTableCleared();
    }

    @Test
    public void testPollingLoopFilterToForeground_walletRoleEnabled_walletSet() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);
        mRegisteredAidCache.mNfcEnabled = true;

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        WALLET_PAYMENT_SERVICE,
                        true,
                        List.of(PAYMENT_AID_1, NON_PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_PAYMENT, CardEmulation.CATEGORY_OTHER),
                        false,
                        false,
                        USER_ID,
                        true));
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        FOREGROUND_SERVICE,
                        true,
                        List.of(PAYMENT_AID_1, NON_PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_PAYMENT, CardEmulation.CATEGORY_OTHER),
                        false,
                        false,
                        USER_ID,
                        true));
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        NON_PAYMENT_SERVICE,
                        true,
                        List.of(NON_PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_OTHER),
                        false,
                        false,
                        USER_ID,
                        true));

        mRegisteredAidCache.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);
        mRegisteredAidCache.onPreferredForegroundServiceChanged(
                new ComponentNameAndUser(USER_ID, FOREGROUND_SERVICE));

        ApduServiceInfo resolvedApdu =
                mRegisteredAidCache.resolvePollingLoopFilterConflict(apduServiceInfos);

        assertEquals(resolvedApdu, apduServiceInfos.get(1));
    }

    @Test
    public void testPollingLoopFilterToWallet_walletRoleEnabled_walletSet() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);
        mRegisteredAidCache.mNfcEnabled = true;

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        WALLET_PAYMENT_SERVICE,
                        true,
                        List.of(PAYMENT_AID_1, NON_PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_PAYMENT, CardEmulation.CATEGORY_OTHER),
                        false,
                        false,
                        USER_ID,
                        true));
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        FOREGROUND_SERVICE,
                        true,
                        List.of(PAYMENT_AID_1, NON_PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_PAYMENT, CardEmulation.CATEGORY_OTHER),
                        false,
                        false,
                        USER_ID,
                        true));
        apduServiceInfos.add(
                createServiceInfoForAidRouting(
                        NON_PAYMENT_SERVICE,
                        true,
                        List.of(NON_PAYMENT_AID_1),
                        List.of(CardEmulation.CATEGORY_OTHER),
                        false,
                        false,
                        USER_ID,
                        true));

        mRegisteredAidCache.mDefaultWalletHolderPackageName = WALLET_HOLDER_PACKAGE_NAME;

        ApduServiceInfo resolvedApdu =
                mRegisteredAidCache.resolvePollingLoopFilterConflict(apduServiceInfos);

        assertEquals(resolvedApdu, apduServiceInfos.get(0));
    }

    private void setWalletRoleFlag(boolean flag) {
        when(mWalletRoleObserver.isWalletRoleFeatureEnabled()).thenReturn(flag);
    }

    private void supportPrefixAndSubset(boolean support) {
        when(mAidRoutingManager.supportsAidPrefixRouting()).thenReturn(support);
        when(mAidRoutingManager.supportsAidSubsetRouting()).thenReturn(support);
    }

    @Test
    public void testGetPreferredService() {

        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);
        ComponentNameAndUser servicePair = mRegisteredAidCache.getPreferredService();
        Assert.assertNull(servicePair.getComponentName());
        mRegisteredAidCache.onPreferredForegroundServiceChanged(
                new ComponentNameAndUser(USER_ID, FOREGROUND_SERVICE));
        servicePair = mRegisteredAidCache.getPreferredService();
        Assert.assertNotNull(servicePair.getComponentName());
        assertEquals(new ComponentNameAndUser(USER_ID, FOREGROUND_SERVICE), servicePair);
    }

    @Test
    public void testIsDefaultServiceForAidWithDefaultService()
            throws NoSuchFieldException, IllegalAccessException {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        List<ApduServiceInfo> services = new ArrayList<>();
        ApduServiceInfo apduServiceInfo = mock(ApduServiceInfo.class);
        services.add(apduServiceInfo);
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mock(
                RegisteredAidCache.AidResolveInfo.class);
        TreeMap<String, RegisteredAidCache.AidResolveInfo> mAidCache = new TreeMap<>();
        mAidCache.put(PAYMENT_AID_1, aidResolveInfo);
        when(mAidRoutingManager.supportsAidPrefixRouting()).thenReturn(false);
        when(mAidRoutingManager.supportsAidSubsetRouting()).thenReturn(false);
        Field field = RegisteredAidCache.class.getDeclaredField("mAidCache");
        field.setAccessible(true);
        field.set(mRegisteredAidCache, mAidCache);
        aidResolveInfo.services = services;
        aidResolveInfo.defaultService = apduServiceInfo;
        when(apduServiceInfo.getComponent()).thenReturn(PAYMENT_SERVICE);

        assertTrue(
                mRegisteredAidCache.isDefaultServiceForAid(1, PAYMENT_SERVICE, PAYMENT_AID_1));
        verify(apduServiceInfo).getComponent();
    }

    @Test
    public void testIsDefaultServiceForAidWithSingleService()
            throws NoSuchFieldException, IllegalAccessException {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        List<ApduServiceInfo> services = new ArrayList<>();
        ApduServiceInfo apduServiceInfo = mock(ApduServiceInfo.class);
        services.add(apduServiceInfo);
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mock(
                RegisteredAidCache.AidResolveInfo.class);
        TreeMap<String, RegisteredAidCache.AidResolveInfo> mAidCache = new TreeMap<>();
        mAidCache.put(PAYMENT_AID_1, aidResolveInfo);
        when(mAidRoutingManager.supportsAidPrefixRouting()).thenReturn(false);
        when(mAidRoutingManager.supportsAidSubsetRouting()).thenReturn(false);
        Field field = RegisteredAidCache.class.getDeclaredField("mAidCache");
        field.setAccessible(true);
        field.set(mRegisteredAidCache, mAidCache);
        aidResolveInfo.services = services;
        aidResolveInfo.defaultService = null;
        when(apduServiceInfo.getComponent()).thenReturn(PAYMENT_SERVICE);

        assertTrue(
                mRegisteredAidCache.isDefaultServiceForAid(1, PAYMENT_SERVICE, PAYMENT_AID_1));
        verify(apduServiceInfo).getComponent();
    }

    @Test
    public void testIsDefaultServiceForAidWithMultipleService()
            throws NoSuchFieldException, IllegalAccessException {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        List<ApduServiceInfo> services = new ArrayList<>();
        ApduServiceInfo apduServiceInfo = mock(ApduServiceInfo.class);
        ApduServiceInfo secondApduServiceInfo = mock(ApduServiceInfo.class);
        services.add(apduServiceInfo);
        services.add(secondApduServiceInfo);
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mock(
                RegisteredAidCache.AidResolveInfo.class);
        TreeMap<String, RegisteredAidCache.AidResolveInfo> mAidCache = new TreeMap<>();
        mAidCache.put(PAYMENT_AID_1, aidResolveInfo);
        when(mAidRoutingManager.supportsAidPrefixRouting()).thenReturn(false);
        when(mAidRoutingManager.supportsAidSubsetRouting()).thenReturn(false);
        Field field = RegisteredAidCache.class.getDeclaredField("mAidCache");
        field.setAccessible(true);
        field.set(mRegisteredAidCache, mAidCache);
        aidResolveInfo.services = services;
        aidResolveInfo.defaultService = null;
        when(apduServiceInfo.getComponent()).thenReturn(PAYMENT_SERVICE);

        assertFalse(
                mRegisteredAidCache.isDefaultServiceForAid(1, PAYMENT_SERVICE, PAYMENT_AID_1));
        verify(apduServiceInfo, never()).getComponent();
    }

    @Test
    public void testIsDefaultServiceForAid() throws NoSuchFieldException, IllegalAccessException {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        when(mAidRoutingManager.supportsAidPrefixRouting()).thenReturn(false);
        when(mAidRoutingManager.supportsAidSubsetRouting()).thenReturn(false);
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mock(
                RegisteredAidCache.AidResolveInfo.class);
        TreeMap<String, RegisteredAidCache.AidResolveInfo> mAidCache = new TreeMap<>();
        mAidCache.put("aidResolveInfo", aidResolveInfo);
        Field field = RegisteredAidCache.class.getDeclaredField("mAidCache");
        field.setAccessible(true);
        field.set(mRegisteredAidCache, mAidCache);
        aidResolveInfo.services = null;

        assertFalse(mRegisteredAidCache.isDefaultServiceForAid(1, PAYMENT_SERVICE, "AID"));
    }

    @Test
    public void testDumpEntry() {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mock(
                RegisteredAidCache.AidResolveInfo.class);
        ApduServiceInfo defaultServiceInfo = mock(ApduServiceInfo.class);
        Map.Entry<String, RegisteredAidCache.AidResolveInfo> map = new AbstractMap.SimpleEntry<>(
                PAYMENT_AID_1, aidResolveInfo);
        List<ApduServiceInfo> services = new ArrayList<>();
        services.add(defaultServiceInfo);
        aidResolveInfo.category = "PAYMENT";
        aidResolveInfo.defaultService = defaultServiceInfo;
        aidResolveInfo.services = services;
        when(defaultServiceInfo.getComponent()).thenReturn(PAYMENT_SERVICE);
        when(defaultServiceInfo.getDescription()).thenReturn("PAYMENT");
        String sb = "    \"" + PAYMENT_AID_1 + "\" (category: " + "PAYMENT" + ")\n"
                + "        "
                + "*DEFAULT* "
                + defaultServiceInfo + " (Description: " + "PAYMENT" + ")\n";
        assertEquals(sb, mRegisteredAidCache.dumpEntry(map));
    }

    @Test
    public void testDump() throws NoSuchFieldException, IllegalAccessException {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        FileDescriptor fd = mock(FileDescriptor.class);
        PrintWriter pw = mock(PrintWriter.class);
        String[] args = new String[]{};
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mock(
                RegisteredAidCache.AidResolveInfo.class);
        TreeMap<String, RegisteredAidCache.AidResolveInfo> mAidCache = new TreeMap<>();
        mAidCache.put("PAYMENT_AID_1", aidResolveInfo);
        ApduServiceInfo defaultServiceInfo = mock(ApduServiceInfo.class);
        Map.Entry<String, RegisteredAidCache.AidResolveInfo> map = new AbstractMap.SimpleEntry<>(
                PAYMENT_AID_1, aidResolveInfo);
        List<ApduServiceInfo> services = new ArrayList<>();
        services.add(defaultServiceInfo);
        aidResolveInfo.category = "PAYMENT";
        aidResolveInfo.defaultService = defaultServiceInfo;
        aidResolveInfo.services = services;
        when(defaultServiceInfo.getComponent()).thenReturn(PAYMENT_SERVICE);
        when(defaultServiceInfo.getDescription()).thenReturn("PAYMENT");
        when(Flags.nfcAssociatedRoleServices()).thenReturn(true);
        Field field = RegisteredAidCache.class.getDeclaredField("mAidCache");
        field.setAccessible(true);
        field.set(mRegisteredAidCache, mAidCache);

        mRegisteredAidCache.dump(fd, pw, args);
        verify(mAidRoutingManager).dump(fd, pw, args);
    }

    @Test
    public void testDumpDebug() throws NoSuchFieldException, IllegalAccessException {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mock(
                RegisteredAidCache.AidResolveInfo.class);
        ProtoOutputStream proto = mock(ProtoOutputStream.class);
        ApduServiceInfo defaultServiceInfo = mock(ApduServiceInfo.class);
        ComponentName mPreferredPaymentService = mock(ComponentName.class);
        ComponentName mPreferredForegroundService = mock(ComponentName.class);
        aidResolveInfo.category = "PAYMENT";
        aidResolveInfo.defaultService = defaultServiceInfo;
        List<ApduServiceInfo> services = new ArrayList<>();
        services.add(defaultServiceInfo);
        aidResolveInfo.services = services;
        when(defaultServiceInfo.getComponent()).thenReturn(PAYMENT_SERVICE);
        TreeMap<String, RegisteredAidCache.AidResolveInfo> mAidCache = new TreeMap<>();
        mAidCache.put(PAYMENT_AID_1, aidResolveInfo);
        Field fieldAidCache = RegisteredAidCache.class.getDeclaredField("mAidCache");
        fieldAidCache.setAccessible(true);
        fieldAidCache.set(mRegisteredAidCache, mAidCache);
        Field fieldPayScheme = RegisteredAidCache.class.getDeclaredField(
                "mPreferredPaymentService");
        fieldPayScheme.setAccessible(true);
        fieldPayScheme.set(mRegisteredAidCache, mPreferredPaymentService);
        Field fieldService = RegisteredAidCache.class.getDeclaredField(
                "mPreferredForegroundService");
        fieldService.setAccessible(true);
        fieldService.set(mRegisteredAidCache, mPreferredForegroundService);

        mRegisteredAidCache.dumpDebug(proto);
        verify(mAidRoutingManager).dumpDebug(proto);
    }

    @Test
    public void testFindPrefixConflictForSubsetAidNoPrefixMatch() {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        String subsetAid = "A000000001#";
        List<ApduServiceInfo> prefixServices = Collections.emptyList();

        RegisteredAidCache.ResolvedPrefixConflictAid result =
                mRegisteredAidCache.findPrefixConflictForSubsetAid(
                        subsetAid, prefixServices, false);
        assertNull(result.prefixAid);
        assertFalse(result.matchingSubset);
    }

    @Test
    public void testFindPrefixConflictForSubsetAidWithMatchingPrefix() {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        String subsetAid = "A000000001#";
        ApduServiceInfo mockService = mock(ApduServiceInfo.class);
        when(mockService.getPrefixAids()).thenReturn(List.of("A0000000#"));
        List<ApduServiceInfo> prefixServices = Collections.singletonList(mockService);

        RegisteredAidCache.ResolvedPrefixConflictAid result =
                mRegisteredAidCache.findPrefixConflictForSubsetAid(
                        subsetAid, prefixServices, false);
        assertNotNull(result.prefixAid);
        assertEquals("A0000000#", result.prefixAid);
        assertFalse(result.matchingSubset);
    }

    @Test
    public void testFindPrefixConflictForSubsetAidMultiplePrefixes() {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        String subsetAid = "A000000001#";
        ApduServiceInfo mockService = mock(ApduServiceInfo.class);
        when(mockService.getPrefixAids()).thenReturn(Arrays.asList("A0000000#", "A000#"));
        List<ApduServiceInfo> prefixServices = Collections.singletonList(mockService);

        RegisteredAidCache.ResolvedPrefixConflictAid result =
                mRegisteredAidCache.findPrefixConflictForSubsetAid(
                        subsetAid, prefixServices, false);
        assertNotNull(result.prefixAid);
        assertEquals("A000#", result.prefixAid); // The smallest prefix should be chosen
    }

    @Test
    public void testFindPrefixConflictForSubsetAidMatchingSubset() {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        String subsetAid = "A000000001#";
        ApduServiceInfo mockService = mock(ApduServiceInfo.class);
        when(mockService.getPrefixAids()).thenReturn(List.of("A000000001#"));
        List<ApduServiceInfo> prefixServices = Collections.singletonList(mockService);

        RegisteredAidCache.ResolvedPrefixConflictAid result =
                mRegisteredAidCache.findPrefixConflictForSubsetAid(
                        subsetAid, prefixServices, false);
        assertNotNull(result.prefixAid);
        assertEquals("A000000001#", result.prefixAid);
        assertTrue(result.matchingSubset);
    }

    @Test
    public void testFindPrefixConflictForSubsetAidPriorityRootAid() {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        String subsetAid = "A000000001#";
        ApduServiceInfo mockService = mock(ApduServiceInfo.class);
        when(mockService.getPrefixAids()).thenReturn(Arrays.asList("A0000000#", "A000#"));
        when(mockService.getCategoryForAid(anyString())).thenReturn(CardEmulation.CATEGORY_PAYMENT);
        when(mockService.getUid()).thenReturn(1000);
        List<ApduServiceInfo> prefixServices = Collections.singletonList(mockService);

        RegisteredAidCache.ResolvedPrefixConflictAid result =
                mRegisteredAidCache.findPrefixConflictForSubsetAid(
                        subsetAid, prefixServices, true);
        assertNotNull(result.prefixAid);
        assertEquals("A000#", result.prefixAid); // Smallest prefix should be chosen
    }

    @Test
    public void testOnRoutingOverridedOrRecovered()
            throws NoSuchFieldException, IllegalAccessException, RemoteException {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        INfcOemExtensionCallback mNfcOemExtensionCallback = mock(INfcOemExtensionCallback.class);
        Field fieldNfcEnable = RegisteredAidCache.class.getDeclaredField("mNfcEnabled");
        fieldNfcEnable.setAccessible(true);
        fieldNfcEnable.set(mRegisteredAidCache, true);
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mock(
                RegisteredAidCache.AidResolveInfo.class);
        aidResolveInfo.services = new ArrayList<>();
        List<String> unCheckedOffHostSecureElement = new ArrayList<>();
        unCheckedOffHostSecureElement.add("SampleElement");
        aidResolveInfo.unCheckedOffHostSecureElement = unCheckedOffHostSecureElement;
        TreeMap<String, RegisteredAidCache.AidResolveInfo> mAidCache = new TreeMap<>();
        mAidCache.put(PAYMENT_AID_1, aidResolveInfo);
        Field fieldAidCache = RegisteredAidCache.class.getDeclaredField("mAidCache");
        fieldAidCache.setAccessible(true);
        fieldAidCache.set(mRegisteredAidCache, mAidCache);
        when(mAidRoutingManager.configureRouting(any(HashMap.class), anyBoolean(),
                anyBoolean())).thenReturn(AidRoutingManager.CONFIGURE_ROUTING_FAILURE_TABLE_FULL);
        mRegisteredAidCache.setOemExtension(mNfcOemExtensionCallback);

        assertEquals(AidRoutingManager.CONFIGURE_ROUTING_FAILURE_TABLE_FULL,
                mRegisteredAidCache.onRoutingOverridedOrRecovered());
        verify(mNfcOemExtensionCallback).onRoutingTableFull();
    }

    @Test
    public void testUpdateRoutingLockedWithNfcDisabled() {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);

        assertEquals(AidRoutingManager.CONFIGURE_ROUTING_FAILURE_UNKNOWN,
                mRegisteredAidCache.updateRoutingLocked(true, true));
    }

    @Test
    public void testUpdateRoutingLockedWithDefaultService()
            throws NoSuchFieldException, IllegalAccessException, RemoteException {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        INfcOemExtensionCallback mNfcOemExtensionCallback = mock(INfcOemExtensionCallback.class);
        Field fieldNfcEnable = RegisteredAidCache.class.getDeclaredField("mNfcEnabled");
        fieldNfcEnable.setAccessible(true);
        fieldNfcEnable.set(mRegisteredAidCache, true);
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mock(
                RegisteredAidCache.AidResolveInfo.class);
        ApduServiceInfo defaultServiceInfo = mock(ApduServiceInfo.class);
        when(defaultServiceInfo.isOnHost()).thenReturn(false);
        when(defaultServiceInfo.requiresUnlock()).thenReturn(true);
        when(defaultServiceInfo.requiresScreenOn()).thenReturn(true);
        when(defaultServiceInfo.getOffHostSecureElement()).thenReturn("sampleElement");
        List<ApduServiceInfo> services = new ArrayList<>();
        services.add(defaultServiceInfo);
        aidResolveInfo.services = services;
        aidResolveInfo.defaultService = defaultServiceInfo;
        List<String> unCheckedOffHostSecureElement = new ArrayList<>();
        unCheckedOffHostSecureElement.add("SampleElement");
        aidResolveInfo.unCheckedOffHostSecureElement = unCheckedOffHostSecureElement;
        TreeMap<String, RegisteredAidCache.AidResolveInfo> mAidCache = new TreeMap<>();
        mAidCache.put(SUBSET_AID, aidResolveInfo);
        Field fieldAidCache = RegisteredAidCache.class.getDeclaredField("mAidCache");
        fieldAidCache.setAccessible(true);
        fieldAidCache.set(mRegisteredAidCache, mAidCache);
        NfcService nfcService = mock(NfcService.class);
        when(NfcService.getInstance()).thenReturn(nfcService);
        when(nfcService.getNciVersion()).thenReturn(NfcService.NCI_VERSION_1_0);
        when(mAidRoutingManager.configureRouting(any(HashMap.class), anyBoolean(),
                anyBoolean())).thenReturn(AidRoutingManager.CONFIGURE_ROUTING_SUCCESS);
        mRegisteredAidCache.setOemExtension(mNfcOemExtensionCallback);

        assertEquals(AidRoutingManager.CONFIGURE_ROUTING_SUCCESS,
                mRegisteredAidCache.updateRoutingLocked(true, true));
        verify(mNfcOemExtensionCallback, never()).onRoutingTableFull();
        verify(nfcService).getNciVersion();
    }

    @Test
    public void testUpdateRoutingLockedWithSingleServiceAsPayment()
            throws NoSuchFieldException, IllegalAccessException, RemoteException {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        INfcOemExtensionCallback mNfcOemExtensionCallback = mock(INfcOemExtensionCallback.class);
        Field fieldNfcEnable = RegisteredAidCache.class.getDeclaredField("mNfcEnabled");
        fieldNfcEnable.setAccessible(true);
        fieldNfcEnable.set(mRegisteredAidCache, true);
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mock(
                RegisteredAidCache.AidResolveInfo.class);
        ApduServiceInfo defaultServiceInfo = mock(ApduServiceInfo.class);
        when(defaultServiceInfo.requiresUnlock()).thenReturn(true);
        when(defaultServiceInfo.requiresScreenOn()).thenReturn(true);
        List<ApduServiceInfo> services = new ArrayList<>();
        services.add(defaultServiceInfo);
        aidResolveInfo.services = services;
        aidResolveInfo.defaultService = null;
        aidResolveInfo.category = CardEmulation.CATEGORY_PAYMENT;
        List<String> unCheckedOffHostSecureElement = new ArrayList<>();
        unCheckedOffHostSecureElement.add("SampleElement");
        aidResolveInfo.unCheckedOffHostSecureElement = unCheckedOffHostSecureElement;
        TreeMap<String, RegisteredAidCache.AidResolveInfo> mAidCache = new TreeMap<>();
        mAidCache.put(PREFIX_AID, aidResolveInfo);
        Field fieldAidCache = RegisteredAidCache.class.getDeclaredField("mAidCache");
        fieldAidCache.setAccessible(true);
        fieldAidCache.set(mRegisteredAidCache, mAidCache);
        NfcService nfcService = mock(NfcService.class);
        when(NfcService.getInstance()).thenReturn(nfcService);
        when(nfcService.getNciVersion()).thenReturn(NfcService.NCI_VERSION_1_0);
        when(mAidRoutingManager.configureRouting(any(HashMap.class), anyBoolean(),
                anyBoolean())).thenReturn(AidRoutingManager.CONFIGURE_ROUTING_SUCCESS);
        mRegisteredAidCache.setOemExtension(mNfcOemExtensionCallback);

        assertEquals(AidRoutingManager.CONFIGURE_ROUTING_SUCCESS,
                mRegisteredAidCache.updateRoutingLocked(true, true));
        verify(mNfcOemExtensionCallback, never()).onRoutingTableFull();
        verify(nfcService).getNciVersion();
    }

    @Test
    public void testUpdateRoutingLockedWithSingleService()
            throws NoSuchFieldException, IllegalAccessException, RemoteException {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        INfcOemExtensionCallback mNfcOemExtensionCallback = mock(INfcOemExtensionCallback.class);
        Field fieldNfcEnable = RegisteredAidCache.class.getDeclaredField("mNfcEnabled");
        fieldNfcEnable.setAccessible(true);
        fieldNfcEnable.set(mRegisteredAidCache, true);
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mock(
                RegisteredAidCache.AidResolveInfo.class);
        ApduServiceInfo defaultServiceInfo = mock(ApduServiceInfo.class);
        when(defaultServiceInfo.isOnHost()).thenReturn(false);
        when(defaultServiceInfo.requiresUnlock()).thenReturn(true);
        when(defaultServiceInfo.requiresScreenOn()).thenReturn(true);
        when(defaultServiceInfo.getOffHostSecureElement()).thenReturn("sampleElement");
        List<ApduServiceInfo> services = new ArrayList<>();
        services.add(defaultServiceInfo);
        aidResolveInfo.services = services;
        aidResolveInfo.defaultService = null;
        aidResolveInfo.category = CardEmulation.CATEGORY_PAYMENT;
        List<String> unCheckedOffHostSecureElement = new ArrayList<>();
        unCheckedOffHostSecureElement.add("SampleElement");
        aidResolveInfo.unCheckedOffHostSecureElement = unCheckedOffHostSecureElement;
        TreeMap<String, RegisteredAidCache.AidResolveInfo> mAidCache = new TreeMap<>();
        mAidCache.put(PREFIX_AID, aidResolveInfo);
        Field fieldAidCache = RegisteredAidCache.class.getDeclaredField("mAidCache");
        fieldAidCache.setAccessible(true);
        fieldAidCache.set(mRegisteredAidCache, mAidCache);
        NfcService nfcService = mock(NfcService.class);
        when(NfcService.getInstance()).thenReturn(nfcService);
        when(nfcService.getNciVersion()).thenReturn(NfcService.NCI_VERSION_1_0);
        when(mAidRoutingManager.configureRouting(any(HashMap.class), anyBoolean(),
                anyBoolean())).thenReturn(AidRoutingManager.CONFIGURE_ROUTING_SUCCESS);
        mRegisteredAidCache.setOemExtension(mNfcOemExtensionCallback);

        assertEquals(AidRoutingManager.CONFIGURE_ROUTING_SUCCESS,
                mRegisteredAidCache.updateRoutingLocked(true, true));
        verify(mNfcOemExtensionCallback, never()).onRoutingTableFull();
        verify(nfcService).getNciVersion();
    }

    @Test
    public void testUpdateRoutingLockedWithMultipleService()
            throws NoSuchFieldException, IllegalAccessException, RemoteException {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        INfcOemExtensionCallback mNfcOemExtensionCallback = mock(INfcOemExtensionCallback.class);
        Field fieldNfcEnable = RegisteredAidCache.class.getDeclaredField("mNfcEnabled");
        fieldNfcEnable.setAccessible(true);
        fieldNfcEnable.set(mRegisteredAidCache, true);
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mock(
                RegisteredAidCache.AidResolveInfo.class);
        ApduServiceInfo defaultServiceInfo = mock(ApduServiceInfo.class);
        ApduServiceInfo secondServiceInfo = mock(ApduServiceInfo.class);
        when(defaultServiceInfo.isOnHost()).thenReturn(false);
        when(defaultServiceInfo.requiresUnlock()).thenReturn(true);
        when(defaultServiceInfo.requiresScreenOn()).thenReturn(true);
        when(defaultServiceInfo.getOffHostSecureElement()).thenReturn("sampleElement");
        when(secondServiceInfo.getOffHostSecureElement()).thenReturn("sampleElement");
        List<ApduServiceInfo> services = new ArrayList<>();
        services.add(defaultServiceInfo);
        services.add(secondServiceInfo);
        aidResolveInfo.services = services;
        aidResolveInfo.defaultService = null;
        aidResolveInfo.category = CardEmulation.CATEGORY_PAYMENT;
        List<String> unCheckedOffHostSecureElement = new ArrayList<>();
        unCheckedOffHostSecureElement.add("SampleElement");
        aidResolveInfo.unCheckedOffHostSecureElement = unCheckedOffHostSecureElement;
        TreeMap<String, RegisteredAidCache.AidResolveInfo> mAidCache = new TreeMap<>();
        mAidCache.put(PREFIX_AID, aidResolveInfo);
        Field fieldAidCache = RegisteredAidCache.class.getDeclaredField("mAidCache");
        fieldAidCache.setAccessible(true);
        fieldAidCache.set(mRegisteredAidCache, mAidCache);
        NfcService nfcService = mock(NfcService.class);
        when(NfcService.getInstance()).thenReturn(nfcService);
        when(nfcService.getNciVersion()).thenReturn(NfcService.NCI_VERSION_1_0);
        when(mAidRoutingManager.configureRouting(any(HashMap.class), anyBoolean(),
                anyBoolean())).thenReturn(AidRoutingManager.CONFIGURE_ROUTING_SUCCESS);
        mRegisteredAidCache.setOemExtension(mNfcOemExtensionCallback);

        assertEquals(AidRoutingManager.CONFIGURE_ROUTING_SUCCESS,
                mRegisteredAidCache.updateRoutingLocked(true, true));
        verify(mNfcOemExtensionCallback, never()).onRoutingTableFull();
        verify(nfcService).getNciVersion();
    }

    @Test
    public void testisPreferredServicePackageNameForUser()
            throws NoSuchFieldException, IllegalAccessException {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        String packageName = FOREGROUND_SERVICE.getPackageName();
        ComponentName mPreferredForegroundService = mock(ComponentName.class);
        Field fieldNfcEnable = RegisteredAidCache.class.getDeclaredField(
                "mPreferredForegroundService");
        fieldNfcEnable.setAccessible(true);
        fieldNfcEnable.set(mRegisteredAidCache, mPreferredForegroundService);
        Field fieldService = RegisteredAidCache.class.getDeclaredField(
                "mUserIdPreferredForegroundService");
        fieldService.setAccessible(true);
        fieldService.set(mRegisteredAidCache, USER_ID);
        when(mPreferredForegroundService.getPackageName()).thenReturn(packageName);

        assertTrue(mRegisteredAidCache.isPreferredServicePackageNameForUser(packageName, USER_ID));
    }

    @Test
    public void testisPreferredServicePackageNameForUserWithDifferentService()
            throws NoSuchFieldException, IllegalAccessException {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        String packageName = WALLET_PAYMENT_SERVICE.getPackageName();
        ComponentName mPreferredForegroundService = mock(ComponentName.class);
        Field fieldNfcEnable = RegisteredAidCache.class.getDeclaredField(
                "mPreferredForegroundService");
        fieldNfcEnable.setAccessible(true);
        fieldNfcEnable.set(mRegisteredAidCache, mPreferredForegroundService);
        Field fieldService = RegisteredAidCache.class.getDeclaredField(
                "mUserIdPreferredForegroundService");
        fieldService.setAccessible(true);
        fieldService.set(mRegisteredAidCache, USER_ID);
        when(mPreferredForegroundService.getPackageName()).thenReturn(packageName);

        assertFalse(mRegisteredAidCache.isPreferredServicePackageNameForUser(
                FOREGROUND_SERVICE.getPackageName(), USER_ID));
    }

    @Test
    public void testisPreferredServicePackageNameForUserWithWallet()
            throws NoSuchFieldException, IllegalAccessException {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        String packageName = WALLET_PAYMENT_SERVICE.getPackageName();
        Field fieldNfcEnable = RegisteredAidCache.class.getDeclaredField(
                "mPreferredForegroundService");
        fieldNfcEnable.setAccessible(true);
        fieldNfcEnable.set(mRegisteredAidCache, null);
        Field fieldWalletHolder = RegisteredAidCache.class.getDeclaredField(
                "mUserIdDefaultWalletHolder");
        fieldWalletHolder.setAccessible(true);
        fieldWalletHolder.set(mRegisteredAidCache, USER_ID);
        Field fieldWalletHolderPackage = RegisteredAidCache.class.getDeclaredField(
                "mDefaultWalletHolderPackageName");
        fieldWalletHolderPackage.setAccessible(true);
        fieldWalletHolderPackage.set(mRegisteredAidCache, packageName);
        when(mWalletRoleObserver.isWalletRoleFeatureEnabled()).thenReturn(true);


        assertTrue(mRegisteredAidCache.isPreferredServicePackageNameForUser(packageName, USER_ID));
    }

    @Test
    public void testisPreferredServicePackageNameForUserWithDifferentWalletService()
            throws NoSuchFieldException, IllegalAccessException {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        String packageName = WALLET_PAYMENT_SERVICE.getPackageName();
        Field fieldNfcEnable = RegisteredAidCache.class.getDeclaredField(
                "mPreferredForegroundService");
        fieldNfcEnable.setAccessible(true);
        fieldNfcEnable.set(mRegisteredAidCache, null);
        Field fieldWalletHolder = RegisteredAidCache.class.getDeclaredField(
                "mUserIdDefaultWalletHolder");
        fieldWalletHolder.setAccessible(true);
        fieldWalletHolder.set(mRegisteredAidCache, 1);
        when(mWalletRoleObserver.isWalletRoleFeatureEnabled()).thenReturn(true);


        assertFalse(mRegisteredAidCache.isPreferredServicePackageNameForUser(packageName, USER_ID));
    }

    @Test
    public void testisPreferredServicePackageNameForUserWithPreferredPaymentService()
            throws NoSuchFieldException, IllegalAccessException {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        String packageName = PAYMENT_SERVICE.getPackageName();
        Field fieldNfcEnable = RegisteredAidCache.class.getDeclaredField(
                "mPreferredForegroundService");
        fieldNfcEnable.setAccessible(true);
        fieldNfcEnable.set(mRegisteredAidCache, null);
        Field fieldService = RegisteredAidCache.class.getDeclaredField(
                "mPreferredPaymentService");
        fieldService.setAccessible(true);
        fieldService.set(mRegisteredAidCache, PAYMENT_SERVICE);
        Field fieldWalletHolder = RegisteredAidCache.class.getDeclaredField(
                "mUserIdPreferredPaymentService");
        fieldWalletHolder.setAccessible(true);
        fieldWalletHolder.set(mRegisteredAidCache, USER_ID);
        when(mWalletRoleObserver.isWalletRoleFeatureEnabled()).thenReturn(false);

        assertTrue(mRegisteredAidCache.isPreferredServicePackageNameForUser(packageName, USER_ID));
    }

    @Test
    public void testisPreferredServicePackageNameForUserWithNonDefaultService()
            throws NoSuchFieldException, IllegalAccessException {
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        String packageName = PAYMENT_SERVICE.getPackageName();
        Field fieldNfcEnable = RegisteredAidCache.class.getDeclaredField(
                "mPreferredForegroundService");
        fieldNfcEnable.setAccessible(true);
        fieldNfcEnable.set(mRegisteredAidCache, null);
        Field fieldService = RegisteredAidCache.class.getDeclaredField(
                "mPreferredPaymentService");
        fieldService.setAccessible(true);
        fieldService.set(mRegisteredAidCache, null);
        when(mWalletRoleObserver.isWalletRoleFeatureEnabled()).thenReturn(false);

        assertFalse(mRegisteredAidCache.isPreferredServicePackageNameForUser(packageName, USER_ID));
    }

    @Test
    public void testIsForegroundPreferred_flagEnabled_matchesPackageName() {
        // Setup: Flag for package name matching is enabled.
        ExtendedMockito
                .when(com.android.nfc.module.nonexported.flags.Flags
                        .foregroundAppPackageNameMatching())
                .thenReturn(true);
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);

        // Set the preferred foreground service to FOREGROUND_SERVICE.
        mRegisteredAidCache.onPreferredForegroundServiceChanged(
                new ComponentNameAndUser(USER_ID, FOREGROUND_SERVICE));

        // Create a service with a different component name but the same package name.
        ApduServiceInfo serviceWithSamePackage = createServiceInfoForAidRouting(
                FOREGROUND_SERVICE_2, // Different component, same package
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false, false, USER_ID, true);

        List<ApduServiceInfo> conflictingServices = new ArrayList<>();
        conflictingServices.add(serviceWithSamePackage);
        conflictingServices.add(createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE, // A different service
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false, false, USER_ID, true));

        // Action: Resolve conflict.
        ApduServiceInfo resolvedService =
                mRegisteredAidCache.resolvePollingLoopFilterConflict(conflictingServices);

        // Assert: The service with the matching package name is selected.
        assertNotNull(resolvedService);
        assertEquals(FOREGROUND_SERVICE_2, resolvedService.getComponent());
    }

    @Test
    public void testIsForegroundPreferred_flagDisabled_matchesSameComponent() {
        // Setup: Flag for package name matching is disabled.
        ExtendedMockito
                .when(com.android.nfc.module.nonexported.flags.Flags
                        .foregroundAppPackageNameMatching())
                .thenReturn(false);
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);

        // Set the preferred foreground service to FOREGROUND_SERVICE.
        mRegisteredAidCache.onPreferredForegroundServiceChanged(
                new ComponentNameAndUser(USER_ID, FOREGROUND_SERVICE));

        // Create a service with the exact same component name.
        ApduServiceInfo serviceWithSameComponent = createServiceInfoForAidRouting(
                FOREGROUND_SERVICE, // Exact same component
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false, false, USER_ID, true);

        List<ApduServiceInfo> conflictingServices = new ArrayList<>();
        conflictingServices.add(serviceWithSameComponent);
        conflictingServices.add(createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE, // A different service
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false, false, USER_ID, true));

        // Action: Resolve conflict.
        ApduServiceInfo resolvedService =
                mRegisteredAidCache.resolvePollingLoopFilterConflict(conflictingServices);

        // Assert: The service with the matching component name is selected.
        assertNotNull(resolvedService);
        assertEquals(FOREGROUND_SERVICE, resolvedService.getComponent());
    }

    @Test
    public void testIsForegroundPreferred_flagDisabled_doesNotMatchDifferentComponent() {
        // Setup: Flag for package name matching is disabled.
        ExtendedMockito
                .when(com.android.nfc.module.nonexported.flags.Flags
                        .foregroundAppPackageNameMatching())
                .thenReturn(false);
        setWalletRoleFlag(true); // Enable wallet role to have a fallback winner
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);

        // Set the preferred foreground service to FOREGROUND_SERVICE.
        mRegisteredAidCache.onPreferredForegroundServiceChanged(
                new ComponentNameAndUser(USER_ID, FOREGROUND_SERVICE));
        // Set wallet role holder as a fallback.
        mRegisteredAidCache.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);

        // Create a service with a different component name but the same package name.
        ApduServiceInfo serviceWithSamePackage = createServiceInfoForAidRouting(
                FOREGROUND_SERVICE_2, // Different component, same package
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false, false, USER_ID, true);
        ApduServiceInfo walletService = createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE, // The wallet service
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false, false, USER_ID, true);

        List<ApduServiceInfo> conflictingServices = new ArrayList<>();
        conflictingServices.add(serviceWithSamePackage);
        conflictingServices.add(walletService);

        // Action: Resolve conflict.
        ApduServiceInfo resolvedService =
                mRegisteredAidCache.resolvePollingLoopFilterConflict(conflictingServices);

        // Assert: The service with the different component name is NOT selected,
        // and the wallet service is chosen instead.
        assertNotNull(resolvedService);
        assertEquals(WALLET_PAYMENT_SERVICE, resolvedService.getComponent());
    }

    @Test
    public void testIsForegroundPreferred_userIdMismatch() {
        // Setup: Flag doesn't matter here, can be either.
        ExtendedMockito
                .when(com.android.nfc.module.nonexported.flags.Flags
                        .foregroundAppPackageNameMatching())
                .thenReturn(true);
        setWalletRoleFlag(true); // Enable wallet role to have a fallback winner
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);

        // Set the preferred foreground service with USER_ID.
        mRegisteredAidCache.onPreferredForegroundServiceChanged(
                new ComponentNameAndUser(USER_ID, FOREGROUND_SERVICE));
        // Set wallet role holder as a fallback.
        mRegisteredAidCache.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);

        // Create a service with a different user ID (UID 100000 corresponds to user 1).
        final int otherUserUid = 100000;
        ApduServiceInfo serviceWithDifferentUser = createServiceInfoForAidRouting(
                FOREGROUND_SERVICE,
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false, false, otherUserUid, true);
        ApduServiceInfo walletService = createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE, // The wallet service
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false, false, USER_ID, true);

        List<ApduServiceInfo> conflictingServices = new ArrayList<>();
        conflictingServices.add(serviceWithDifferentUser);
        conflictingServices.add(walletService);

        // Action: Resolve conflict.
        ApduServiceInfo resolvedService =
                mRegisteredAidCache.resolvePollingLoopFilterConflict(conflictingServices);

        // Assert: The service with the different user ID is NOT selected,
        // and the wallet service is chosen instead.
        assertNotNull(resolvedService);
        assertEquals(WALLET_PAYMENT_SERVICE, resolvedService.getComponent());
    }

    @Test
    public void testIsForegroundPreferred_nullPreferredService() {
        // Setup: No preferred foreground service is set.
        setWalletRoleFlag(true); // Enable wallet role to have a fallback winner
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);

        // Set wallet role holder as a fallback.
        mRegisteredAidCache.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);

        ApduServiceInfo foregroundService = createServiceInfoForAidRouting(
                FOREGROUND_SERVICE,
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false, false, USER_ID, true);
        ApduServiceInfo walletService = createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE, // The wallet service
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false, false, USER_ID, true);

        List<ApduServiceInfo> conflictingServices = new ArrayList<>();
        conflictingServices.add(foregroundService);
        conflictingServices.add(walletService);

        // Action: Resolve conflict.
        ApduServiceInfo resolvedService =
                mRegisteredAidCache.resolvePollingLoopFilterConflict(conflictingServices);

        // Assert: Since there's no foreground preference, the wallet service is chosen.
        assertNotNull(resolvedService);
        assertEquals(WALLET_PAYMENT_SERVICE, resolvedService.getComponent());
    }

    @Test
    public void testGetPreferredServiceInfo_noPreferredService() {
        // Setup: No preferred service is set.
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);

        // Action: Get preferred service info.
        ApduServiceInfo result = mRegisteredAidCache.getPreferredServiceInfo();

        // Assert: The result should be null as no preferred service is set.
        assertNull(result);
    }

    @Test
    public void testGetPreferredServiceInfo_serviceFound() {
        // Setup: A preferred foreground service is set and its info is available.
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);
        mRegisteredAidCache.onPreferredForegroundServiceChanged(
                new ComponentNameAndUser(USER_ID, FOREGROUND_SERVICE));

        ApduServiceInfo expectedServiceInfo = createServiceInfoForAidRouting(
                FOREGROUND_SERVICE,
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false, false, USER_ID, true);
        List<ApduServiceInfo> userServices = new ArrayList<>();
        userServices.add(expectedServiceInfo);
        userServices.add(createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE, // Another service for the same user
                true,
                List.of(PAYMENT_AID_2),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false, false, USER_ID, true));

        mRegisteredAidCache.generateUserApduServiceInfoLocked(USER_ID, userServices);

        // Action: Get preferred service info.
        ApduServiceInfo result = mRegisteredAidCache.getPreferredServiceInfo();

        // Assert: The correct service info is returned.
        assertNotNull(result);
        assertEquals(expectedServiceInfo, result);
    }

    @Test
    public void testGetPreferredServiceInfo_serviceNotFoundInList() {
        // Setup: A preferred foreground service is set, but its info is not in the user's list.
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);
        mRegisteredAidCache.onPreferredForegroundServiceChanged(
                new ComponentNameAndUser(USER_ID, FOREGROUND_SERVICE));

        // Create a list of services for the user that does NOT contain the preferred service.
        List<ApduServiceInfo> userServices = new ArrayList<>();
        userServices.add(createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE, // A different service
                true,
                List.of(PAYMENT_AID_2),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false, false, USER_ID, true));

        mRegisteredAidCache.generateUserApduServiceInfoLocked(USER_ID, userServices);

        // Action: Get preferred service info.
        ApduServiceInfo result = mRegisteredAidCache.getPreferredServiceInfo();

        // Assert: The result is null because the specific service info was not found.
        assertNull(result);
    }

    @Test
    public void testGetPreferredServiceInfo_userNotFound() {
        // Setup: A preferred foreground service is set, but there's no service list for that user.
        // This tests the fix for the NullPointerException.
        mRegisteredAidCache =
                new RegisteredAidCache(mContext, mWalletRoleObserver, mAidRoutingManager);
        mRegisteredAidCache.onPreferredForegroundServiceChanged(
                new ComponentNameAndUser(USER_ID, FOREGROUND_SERVICE));

        // mUserApduServiceInfo is empty, so get(USER_ID) will return null.

        // Action: Get preferred service info.
        ApduServiceInfo result = mRegisteredAidCache.getPreferredServiceInfo();

        // Assert: The result is null and no NullPointerException was thrown.
        assertNull(result);
    }

    @Test
    public void
            testAidConflictResolution_associatedRolePrefixAidDoesNotShadowWalletExactAid()
            throws PackageManager.NameNotFoundException {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(true);
        when(Flags.nfcAssociatedRoleServices()).thenReturn(true);
        when(mPackageManager.getProperty(
                eq(CardEmulation.PROPERTY_ALLOW_SHARED_ROLE_PRIORITY),
                eq(WALLET_HOLDER_PACKAGE_NAME)))
                .thenReturn(new PackageManager.Property(
                        CardEmulation.PROPERTY_ALLOW_SHARED_ROLE_PRIORITY,
                        WALLET_HOLDER_2_PACKAGE_NAME, WALLET_HOLDER_PACKAGE_NAME, null));

        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        mRegisteredAidCache.mNfcEnabled = true;

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        // Default wallet service with exact payment AID
        apduServiceInfos.add(createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE,
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false,
                true,
                USER_ID,
                true,
                true));
        // Associated role service with prefix AID and wantsRoleHolderPriority=true
        apduServiceInfos.add(createServiceInfoForAidRouting(
                PAYMENT_SERVICE,
                true,
                List.of(PREFIX_PAYMENT_AID),
                List.of(CardEmulation.CATEGORY_OTHER),
                false,
                true,
                USER_ID,
                true,
                true));

        mRegisteredAidCache.generateUserApduServiceInfoLocked(USER_ID, apduServiceInfos);
        mRegisteredAidCache.generateServiceMapLocked(apduServiceInfos);
        mRegisteredAidCache.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);

        mRegisteredAidCache.mAssociatedRoleServices = new HashSet<>();
        mRegisteredAidCache.mAssociatedRoleServices.add(apduServiceInfos.get(1));
        mRegisteredAidCache.generateAidCacheLocked();

        RegisteredAidCache.AidResolveInfo paymentResolveInfo =
                mRegisteredAidCache.resolveAid(PAYMENT_AID_1);

        assertNotNull(paymentResolveInfo.defaultService);
        assertEquals(WALLET_PAYMENT_SERVICE, paymentResolveInfo.defaultService.getComponent());
    }

    @Test
    public void testAidConflictResolution_walletHolderPrioritizedOverAssociatedService()
            throws PackageManager.NameNotFoundException {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        when(Flags.nfcAssociatedRoleServices()).thenReturn(true);
        when(mPackageManager.getProperty(
                eq(CardEmulation.PROPERTY_ALLOW_SHARED_ROLE_PRIORITY),
                eq(WALLET_HOLDER_PACKAGE_NAME)))
                .thenReturn(new PackageManager.Property(
                        CardEmulation.PROPERTY_ALLOW_SHARED_ROLE_PRIORITY,
                        WALLET_HOLDER_2_PACKAGE_NAME, WALLET_HOLDER_PACKAGE_NAME, null));

        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        mRegisteredAidCache.mNfcEnabled = true;

        ComponentName associatedServiceWithAlphabeticallyEarlierName = new ComponentName(
                WALLET_HOLDER_2_PACKAGE_NAME,
                "com.android.test.walletroleholder2.AAAApduService");

        ApduServiceInfo walletServiceInfo = createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE,
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false,
                true,
                USER_ID,
                true,
                true);
        ApduServiceInfo associatedServiceInfo = createServiceInfoForAidRouting(
                associatedServiceWithAlphabeticallyEarlierName,
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false,
                true,
                USER_ID,
                true,
                true);

        List<ApduServiceInfo> apduServiceInfos = List.of(walletServiceInfo, associatedServiceInfo);

        mRegisteredAidCache.generateUserApduServiceInfoLocked(USER_ID, apduServiceInfos);
        mRegisteredAidCache.generateServiceMapLocked(apduServiceInfos);
        mRegisteredAidCache.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);

        mRegisteredAidCache.mAssociatedRoleServices = new HashSet<>();
        mRegisteredAidCache.mAssociatedRoleServices.add(associatedServiceInfo);
        mRegisteredAidCache.generateAidCacheLocked();

        RegisteredAidCache.AidResolveInfo paymentResolveInfo =
                mRegisteredAidCache.resolveAid(PAYMENT_AID_1);

        assertNotNull(paymentResolveInfo.defaultService);
        assertEquals(WALLET_PAYMENT_SERVICE, paymentResolveInfo.defaultService.getComponent());
    }

    @Test
    public void testAidConflictResolution_walletRolePrefixChildAidDoesNotShadowWalletPrefixRootAid()
            throws PackageManager.NameNotFoundException {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(true);
        when(Flags.nfcAssociatedRoleServices()).thenReturn(true);
        when(mPackageManager.getProperty(
                eq(CardEmulation.PROPERTY_ALLOW_SHARED_ROLE_PRIORITY),
                eq(WALLET_HOLDER_PACKAGE_NAME)))
                .thenReturn(new PackageManager.Property(
                        CardEmulation.PROPERTY_ALLOW_SHARED_ROLE_PRIORITY,
                        WALLET_HOLDER_2_PACKAGE_NAME, WALLET_HOLDER_PACKAGE_NAME, null));

        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        mRegisteredAidCache.mNfcEnabled = true;

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        // Preferred wallet service with root prefix payment AID
        apduServiceInfos.add(createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE,
                true,
                List.of(PREFIX_PAYMENT_AID),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false,
                true,
                USER_ID,
                true,
                true));
        // Another service in the same wallet holder package with child prefix AID
        apduServiceInfos.add(createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE_2,
                true,
                List.of("A0000000041010*"),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false,
                true,
                USER_ID,
                true,
                true));

        mRegisteredAidCache.generateUserApduServiceInfoLocked(USER_ID, apduServiceInfos);
        mRegisteredAidCache.generateServiceMapLocked(apduServiceInfos);
        mRegisteredAidCache.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);

        mRegisteredAidCache.generateAidCacheLocked();

        RegisteredAidCache.AidResolveInfo paymentResolveInfo =
                mRegisteredAidCache.resolveAid("A0000000041010");

        assertNotNull(paymentResolveInfo.defaultService);
        assertEquals(WALLET_PAYMENT_SERVICE, paymentResolveInfo.defaultService.getComponent());
    }
}
