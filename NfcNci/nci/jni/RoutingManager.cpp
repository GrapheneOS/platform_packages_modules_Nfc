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

/*
 *  Manage the listen-mode routing table.
 */

#include "RoutingManager.h"
// Redefined by android-base headers.
#undef ATTRIBUTE_UNUSED

#include <android-base/logging.h>
#include <android-base/stringprintf.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>

#include "JavaClassConstants.h"
#include "nfa_ce_api.h"
#include "nfa_ee_api.h"
#include "nfc_config.h"

using android::base::StringPrintf;

extern bool gActivated;
extern bool sIsRecovering;
extern SyncEvent gDeactivatedEvent;

const JNINativeMethod RoutingManager::sMethods[] = {
    {"doGetDefaultRouteDestination", "()I",
     (void*)RoutingManager::
         com_android_nfc_cardemulation_doGetDefaultRouteDestination},
    {"doGetDefaultOffHostRouteDestination", "()I",
     (void*)RoutingManager::
         com_android_nfc_cardemulation_doGetDefaultOffHostRouteDestination},
    {"doGetDefaultFelicaRouteDestination", "()I",
     (void*)RoutingManager::
         com_android_nfc_cardemulation_doGetDefaultFelicaRouteDestination},
    {"doGetOffHostUiccDestination", "()[B",
     (void*)RoutingManager::
         com_android_nfc_cardemulation_doGetOffHostUiccDestination},
    {"doGetOffHostEseDestination", "()[B",
     (void*)RoutingManager::
         com_android_nfc_cardemulation_doGetOffHostEseDestination},
    {"doGetAidMatchingMode", "()I",
     (void*)RoutingManager::com_android_nfc_cardemulation_doGetAidMatchingMode},
    {"doGetDefaultIsoDepRouteDestination", "()I",
     (void*)RoutingManager::
         com_android_nfc_cardemulation_doGetDefaultIsoDepRouteDestination},
    {"doGetDefaultScRouteDestination", "()I",
     (void*)RoutingManager::
         com_android_nfc_cardemulation_doGetDefaultScRouteDestination},
    {"doGetEuiccMepMode", "()I",
     (void*)RoutingManager::com_android_nfc_cardemulation_doGetEuiccMepMode}};

// SCBR from host works only when App is in foreground
static const uint8_t SYS_CODE_PWR_STATE_HOST = 0x01;
static const uint16_t DEFAULT_SYS_CODE = 0xFEFE;

static const uint8_t AID_ROUTE_QUAL_PREFIX = 0x10;

static bool gFirstRun = true;
static Mutex sEeInfoMutex;
static Mutex sEeInfoChangedMutex;

/*******************************************************************************
**
** Function:        RoutingManager
**
** Description:     Constructor
**
** Returns:         None
**
*******************************************************************************/
RoutingManager::RoutingManager()
    : mSecureNfcEnabled(false),
      mNativeData(NULL),
      mAidRoutingConfigured(false) {
  static const char fn[] = "RoutingManager::RoutingManager()";

  mDefaultOffHostRoute =
      NfcConfig::getUnsigned(NAME_DEFAULT_OFFHOST_ROUTE, 0x00);

  if (NfcConfig::hasKey(NAME_OFFHOST_ROUTE_UICC)) {
    mOffHostRouteUicc = NfcConfig::getBytes(NAME_OFFHOST_ROUTE_UICC);
  }

  if (NfcConfig::hasKey(NAME_OFFHOST_ROUTE_ESE)) {
    mOffHostRouteEse = NfcConfig::getBytes(NAME_OFFHOST_ROUTE_ESE);
  }

  mDefaultFelicaRoute = NfcConfig::getUnsigned(NAME_DEFAULT_NFCF_ROUTE, 0x00);
  LOG(DEBUG) << StringPrintf("%s: Active SE for Nfc-F is 0x%02X", fn,
                             mDefaultFelicaRoute);

  mDefaultEe = NfcConfig::getUnsigned(NAME_DEFAULT_ROUTE, 0x00);
  LOG(DEBUG) << StringPrintf("%s: default route is 0x%02X", fn, mDefaultEe);

  mAidMatchingMode =
      NfcConfig::getUnsigned(NAME_AID_MATCHING_MODE, AID_MATCHING_EXACT_ONLY);

  mDefaultSysCodeRoute =
      NfcConfig::getUnsigned(NAME_DEFAULT_SYS_CODE_ROUTE, 0xC0);

  mDefaultSysCodePowerstate =
      NfcConfig::getUnsigned(NAME_DEFAULT_SYS_CODE_PWR_STATE, 0x19);

  mDefaultSysCode = DEFAULT_SYS_CODE;
  if (NfcConfig::hasKey(NAME_DEFAULT_SYS_CODE)) {
    std::vector<uint8_t> pSysCode = NfcConfig::getBytes(NAME_DEFAULT_SYS_CODE);
    if (pSysCode.size() == 0x02) {
      mDefaultSysCode = ((pSysCode[0] << 8) | ((int)pSysCode[1] << 0));
      LOG(DEBUG) << StringPrintf("%s: DEFAULT_SYS_CODE=0x%02X", __func__,
                                 mDefaultSysCode);
    }
  }

  mOffHostAidRoutingPowerState =
      NfcConfig::getUnsigned(NAME_OFFHOST_AID_ROUTE_PWR_STATE, 0x01);

  mDefaultIsoDepRoute = NfcConfig::getUnsigned(NAME_DEFAULT_ISODEP_ROUTE, 0x0);

  mHostListenTechMask =
      NfcConfig::getUnsigned(NAME_HOST_LISTEN_TECH_MASK,
                             NFA_TECHNOLOGY_MASK_A | NFA_TECHNOLOGY_MASK_F);

  mOffHostListenTechMask = NfcConfig::getUnsigned(
      NAME_OFFHOST_LISTEN_TECH_MASK,
      NFA_TECHNOLOGY_MASK_A | NFA_TECHNOLOGY_MASK_B | NFA_TECHNOLOGY_MASK_F);

  mEuiccMepMode= NfcConfig::getUnsigned(NAME_EUICC_MEP_MODE, 0x0);

  if (NfcConfig::hasKey(NAME_NFCEE_EVENT_RF_DISCOVERY_OPTION)) {
    mIsRFDiscoveryOptimized =
        (NfcConfig::getUnsigned(NAME_NFCEE_EVENT_RF_DISCOVERY_OPTION) == 0x01
             ? true
             : false);
    LOG(VERBOSE) << StringPrintf(
        "%s: NAME_NFCEE_EVENT_RF_DISCOVERY_OPTION found=%d", fn,
        mIsRFDiscoveryOptimized);
  } else {
    mIsRFDiscoveryOptimized = false;
    LOG(VERBOSE) << StringPrintf(
        "%s: NAME_NFCEE_EVENT_RF_DISCOVERY_OPTION not found=%d", fn,
        mIsRFDiscoveryOptimized);
  }

  if (NfcConfig::hasKey(NAME_OPTIMIZE_ROUTING_TABLE_UPDATE)) {
    mIsRTUpdateOptimized =
        (NfcConfig::getUnsigned(NAME_OPTIMIZE_ROUTING_TABLE_UPDATE) == 0x01
             ? true
             : false);
    LOG(VERBOSE) << StringPrintf(
        "%s: NAME_OPTIMIZE_ROUTING_TABLE_UPDATE found=%d", fn,
        mIsRTUpdateOptimized);
  } else {
    mIsRTUpdateOptimized = false;
    LOG(VERBOSE) << StringPrintf(
        "%s: NAME_OPTIMIZE_ROUTING_TABLE_UPDATE not found=%d", fn,
        mIsRTUpdateOptimized);
  }

  memset(&mEeInfo, 0, sizeof(mEeInfo));
  mReceivedEeInfo = false;
  mSeTechMask = 0x00;
  mIsScbrSupported = false;

  mNfcFOnDhHandle = NFA_HANDLE_INVALID;

  mDeinitializing = false;
}

/*******************************************************************************
**
** Function:        RoutingManager
**
** Description:     Destructor
**
** Returns:         None
**
*******************************************************************************/
RoutingManager::~RoutingManager() {}

/*******************************************************************************
**
** Function:        initialize
**
** Description:     Initialize the object
**
** Returns:         true if OK
**                  false if failed
**
*******************************************************************************/
bool RoutingManager::initialize(nfc_jni_native_data* native) {
  static const char fn[] = "RoutingManager::initialize()";
  mNativeData = native;
  mRxDataBuffer.clear();

  {
    SyncEventGuard guard(mEeRegisterEvent);
    LOG(DEBUG) << fn << ": try ee register";
    tNFA_STATUS nfaStat = NFA_EeRegister(nfaEeCallback);
    if (nfaStat != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: fail ee register; error=0x%X", fn,
                                 nfaStat);
      return false;
    }
    mEeRegisterEvent.wait();
  }

  if ((mDefaultOffHostRoute != 0) || (mDefaultFelicaRoute != 0)) {
    // Wait for EE info if needed
    SyncEventGuard guard(mEeInfoEvent);
    if (!mReceivedEeInfo) {
      LOG(INFO) << fn << ": Waiting for EE info";
      mEeInfoEvent.wait();
    }
  }

  // Set the host-routing Tech
  tNFA_STATUS nfaStat = NFA_CeSetIsoDepListenTech(
      mHostListenTechMask & (NFA_TECHNOLOGY_MASK_A | NFA_TECHNOLOGY_MASK_B));

  if (nfaStat != NFA_STATUS_OK)
    LOG(ERROR) << StringPrintf("%s: Failed to configure CE IsoDep technologies",
                               fn);

  // Register a wild-card for AIDs routed to the host
  nfaStat = NFA_CeRegisterAidOnDH(NULL, 0, stackCallback);
  if (nfaStat != NFA_STATUS_OK)
    LOG(ERROR) << fn << ": Failed to register wildcard AID for DH";

  // Trigger RT update
  gFirstRun = true;
  mNfceeListenConfig.nb_config = 0;
  setEeInfoChangedFlag();
  mDefaultAidRouteAdded = false;

  return true;
}

/*******************************************************************************
**
** Function:        getInstance
**
** Description:     Get an instance of RoutingManager object
**
** Returns:         handle to object
**
*******************************************************************************/
RoutingManager& RoutingManager::getInstance() {
  static RoutingManager manager;
  return manager;
}

/*******************************************************************************
 **
 ** Function:        isTypeATypeBTechSupportedInEe
 **
 ** Description:     receive eeHandle
 **
 ** Returns:         true  : if EE support protocol type A/B
 **                  false : if EE doesn't protocol type A/B
 **
 *******************************************************************************/
bool RoutingManager::isTypeATypeBTechSupportedInEe(tNFA_HANDLE eeHandle) {
  static const char fn[] = "RoutingManager::isTypeATypeBTechSupportedInEe";
  uint8_t actualNbEe = NFA_EE_MAX_EE_SUPPORTED;
  tNFA_EE_INFO eeInfo[actualNbEe];

  memset(&eeInfo, 0, actualNbEe * sizeof(tNFA_EE_INFO));
  tNFA_STATUS nfaStat = NFA_EeGetInfo(&actualNbEe, eeInfo);
  if (nfaStat != NFA_STATUS_OK) {
    return false;
  }
  for (auto i = 0; i < actualNbEe; i++) {
    if (eeHandle == eeInfo[i].ee_handle) {
      if (eeInfo[i].la_protocol || eeInfo[i].lb_protocol) {
        return true;
      }
    }
  }

  if (mEuiccMepMode) {
    actualNbEe = NFA_EE_MAX_EE_SUPPORTED;
    memset(&eeInfo, 0, actualNbEe * sizeof(tNFA_EE_INFO));
    nfaStat = NFA_EeGetMepInfo(&actualNbEe, eeInfo);
    if (nfaStat != NFA_STATUS_OK) {
      return false;
    }
    for (auto i = 0; i < actualNbEe; i++) {
      if (eeHandle == eeInfo[i].ee_handle) {
        if (eeInfo[i].la_protocol || eeInfo[i].lb_protocol) {
          return true;
        }
      }
    }
  }

  LOG(WARNING) << StringPrintf(
      "%s:  Route %02X does not support A/B, using DH as default", fn,
      eeHandle);
  return false;
}

/*******************************************************************************
**
** Function:        addAidRouting
**
** Description:     Add an AID to be programmed in routing table.
**
** Returns:         true if procedure OK
**                  false if procedure failed
**
*******************************************************************************/
bool RoutingManager::addAidRouting(const uint8_t* aid, uint8_t aidLen,
                                   int route, int aidInfo, int power) {
  static const char fn[] = "RoutingManager::addAidRouting";
  uint8_t powerState = 0x01;
  int defaultAidRoute = mDefaultEe;

  if (route != NFC_DH_ID &&
      !isTypeATypeBTechSupportedInEe(route | NFA_HANDLE_GROUP_EE)) {
    // If default AID route is DH no need to add aid explicitly
    // as all AIDs will be routed to DH
    if (defaultAidRoute == NFC_DH_ID) {
      LOG(DEBUG) << StringPrintf(
          "%s:  defaultAidRoute=%02x, Skip fallback to DH", fn,
          defaultAidRoute);
      return true;
    }
    route = NFC_DH_ID;
    power = 0x11;
  }

  if (!mSecureNfcEnabled) {
    if (power == 0x00) {
      powerState = (route != 0x00) ? mOffHostAidRoutingPowerState : 0x11;
    } else {
      powerState =
          (route != 0x00) ? mOffHostAidRoutingPowerState & power : power;
    }
  }

  if (aidLen == 0) {
    LOG(DEBUG) << StringPrintf(
        "%s:  default AID on route=%02x, aidInfo=%02x, power=%02x", fn, route,
        aidInfo, power);
    mDefaultAidRouteAdded = true;
  } else {
    LOG(DEBUG) << StringPrintf(
        "%s:  aidLen =%02X, route=%02x, aidInfo=%02x, power=%02x", fn, aidLen,
        route, aidInfo, power);
  }

  SyncEventGuard guard(mAidAddRemoveEvent);
  mAidRoutingConfigured = false;
  tNFA_STATUS nfaStat =
      NFA_EeAddAidRouting(route, aidLen, (uint8_t*)aid, powerState, aidInfo);
  if (!sIsRecovering && nfaStat == NFA_STATUS_OK) {
    LOG(DEBUG) << StringPrintf("%s: wait for mAidAddRemoveEvent completion",
                               fn);
    mAidAddRemoveEvent.wait();
  }
  if (mAidRoutingConfigured) {
    return true;
  } else {
    LOG(ERROR) << fn << ": failed to route AID";
    return false;
  }
}

/*******************************************************************************
**
** Function:        removeAidRouting
**
** Description:     Removes an AID from the routing table
**
** Returns:         true if procedure OK
**                  false if procedure failed
**
*******************************************************************************/
bool RoutingManager::removeAidRouting(const uint8_t* aid, uint8_t aidLen) {
  static const char fn[] = "RoutingManager::removeAidRouting";

  if (aidLen != 0) {
    LOG(DEBUG) << StringPrintf("%s: len=%d, 0x%x 0x%x 0x%x 0x%x 0x%x", __func__,
                               aidLen, *(aid), *(aid + 1), *(aid + 2),
                               *(aid + 3), *(aid + 4));
  } else {
    LOG(DEBUG) << fn << ": Remove Empty aid";
  }

  SyncEventGuard guard(mAidAddRemoveEvent);
  mAidRoutingConfigured = false;
  tNFA_STATUS nfaStat = NFA_EeRemoveAidRouting(aidLen, (uint8_t*)aid);
  if (!sIsRecovering) {
    if (nfaStat == NFA_STATUS_OK) {
      LOG(DEBUG) << StringPrintf("%s: wait for mAidAddRemoveEvent completion",
                                fn);
      mAidAddRemoveEvent.wait();
    }
  }
  if (mAidRoutingConfigured) {
    return true;
  } else {
    LOG(WARNING) << fn << ": failed to remove AID";
    return false;
  }
}

/*******************************************************************************
**
** Function:        commitRouting
**
** Description:     Ask for routing table update
**
** Returns:         true if procedure OK
**                  false if procedure failed
**
*******************************************************************************/
tNFA_STATUS RoutingManager::commitRouting() {
  static const char fn[] = "RoutingManager::commitRouting";
  tNFA_STATUS nfaStat = 0;
  sEeInfoChangedMutex.lock();
  bool eeChanged = mEeInfoChanged;
  mEeInfoChanged = false;
  sEeInfoChangedMutex.unlock();
  if (eeChanged) {
    clearRoutingEntry(CLEAR_PROTOCOL_ENTRIES | CLEAR_TECHNOLOGY_ENTRIES);
    updateRoutingTable();
  }
  if (mAidRoutingConfigured || eeChanged) {
    LOG(DEBUG) << StringPrintf("%s: RT update needed", fn);
    {
      SyncEventGuard guard(mEeUpdateEvent);
      nfaStat = NFA_EeUpdateNow();
      if (!sIsRecovering) {
        if (nfaStat == NFA_STATUS_OK) {
          mEeUpdateEvent.wait();  // wait for NFA_EE_UPDATED_EVT
        }
      }
    }
  }
  return nfaStat;
}

/*******************************************************************************
**
** Function:        onNfccShutdown
**
** Description:     performs tasks for NFC shutdown
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::onNfccShutdown() {
  static const char fn[] = "RoutingManager:onNfccShutdown";
  if (mDefaultOffHostRoute == 0x00 && mDefaultFelicaRoute == 0x00) return;

  tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
  uint8_t actualNumEe = NFA_EE_MAX_EE_SUPPORTED;
  tNFA_EE_INFO eeInfo[actualNumEe];
  mDeinitializing = true;

  memset(&eeInfo, 0, sizeof(eeInfo));
  if ((nfaStat = NFA_EeGetInfo(&actualNumEe, eeInfo)) != NFA_STATUS_OK) {
    LOG(ERROR) << StringPrintf("%s: fail get info; error=0x%X", fn, nfaStat);
    return;
  }
  if (actualNumEe != 0) {
    for (uint8_t xx = 0; xx < actualNumEe; xx++) {
      bool bIsOffHostEEPresent =
          (NFC_GetNCIVersion() < NCI_VERSION_2_0)
              ? (eeInfo[xx].num_interface != 0)
              : (eeInfo[xx].ee_interface[0] !=
                 NCI_NFCEE_INTERFACE_HCI_ACCESS) &&
                    (eeInfo[xx].ee_status == NFA_EE_STATUS_ACTIVE);
      if (bIsOffHostEEPresent) {
        LOG(DEBUG) << StringPrintf(
            "%s: Handle=0x%04x Change Status Active to Inactive", fn,
            eeInfo[xx].ee_handle);
        SyncEventGuard guard(mEeSetModeEvent);
        if ((nfaStat = NFA_EeModeSet(eeInfo[xx].ee_handle,
                                     NFA_EE_MD_DEACTIVATE)) == NFA_STATUS_OK) {
          mEeSetModeEvent.wait();  // wait for NFA_EE_MODE_SET_EVT
        } else {
          LOG(ERROR) << fn << ": Failed to set EE inactive";
        }
      }
    }
  } else {
    LOG(DEBUG) << fn << ": No active EEs found";
  }
  //release waits
  {
    SyncEventGuard guard(mEeRegisterEvent);
    mEeRegisterEvent.notifyOne();
  }
  {
    SyncEventGuard guard(mRoutingEvent);
    mRoutingEvent.notifyOne();
  }
  {
    SyncEventGuard guard(mEeSetModeEvent);
    mEeSetModeEvent.notifyOne();
  }
  {
    SyncEventGuard guard(mEePwrAndLinkCtrlEvent);
    mEePwrAndLinkCtrlEvent.notifyOne();
  }
  {
    SyncEventGuard guard(mAidAddRemoveEvent);
    mAidAddRemoveEvent.notifyOne();
    LOG(DEBUG) << StringPrintf("%s: mAidAddRemoveEvent notified", fn);
  }
}

/*******************************************************************************
**
** Function:        notifyActivated
**
** Description:     Notify upper layers of CE activation
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::notifyActivated(uint8_t technology) {
  JNIEnv* e = NULL;
  ScopedAttach attach(mNativeData->vm, &e);
  if (e == NULL) {
    LOG(ERROR) << __func__ << ": jni env is null";
    return;
  }

  e->CallVoidMethod(mNativeData->manager,
                    android::gCachedNfcManagerNotifyHostEmuActivated,
                    (int)technology);
  if (e->ExceptionCheck()) {
    e->ExceptionClear();
    LOG(ERROR) << __func__ << ": fail notify";
  }
}

/*******************************************************************************
**
** Function:        getNameOfEe
**
** Description:     Translates NFCEE Id into string name, if it exists
**
** Returns:         true if handle was found
**                  false if not
**
*******************************************************************************/
bool RoutingManager::getNameOfEe(tNFA_HANDLE ee_handle, std::string& eeName) {
  if (mOffHostRouteEse.size() == 0) {
    return false;
  }
  ee_handle &= ~NFA_HANDLE_GROUP_EE;

  for (uint8_t i = 0; i < mOffHostRouteEse.size(); i++) {
    if (ee_handle == mOffHostRouteEse[i]) {
      eeName = "eSE" + std::to_string(i + 1);
      return true;
    }
  }
  for (uint8_t i = 0; i < mOffHostRouteUicc.size(); i++) {
    if (ee_handle == mOffHostRouteUicc[i]) {
      eeName = "SIM" + std::to_string(i + 1);
      return true;
    }
  }
  if (NfcConfig::hasKey(NAME_T4T_NFCEE_ENABLE)) {
    if (NfcConfig::getUnsigned(NAME_T4T_NFCEE_ENABLE)) {
      uint8_t defaultNdefNfceeRoute =
          NfcConfig::getUnsigned(NAME_DEFAULT_NDEF_NFCEE_ROUTE, 0x10);
      if (ee_handle == defaultNdefNfceeRoute) {
        eeName = "NDEF-NFCEE";
        return true;
      }
    }
  }

  LOG(WARNING) << __func__ << ": Incorrect EE Id";
  return false;
}

/*******************************************************************************
**
** Function:        notifyEeAidSelected
**
** Description:     Notify upper layers of RF_NFCEE_ACTION_NTF with trigger
**                  AID
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::notifyEeAidSelected(tNFC_AID& nfcaid,
                                         tNFA_HANDLE ee_handle) {
  std::vector<uint8_t> aid(nfcaid.aid, nfcaid.aid + nfcaid.len_aid);
  if (aid.empty()) {
    return;
  }

  JNIEnv* e = NULL;
  ScopedAttach attach(mNativeData->vm, &e);
  CHECK(e);

  ScopedLocalRef<jobject> aidJavaArray(e, e->NewByteArray(aid.size()));
  CHECK(aidJavaArray.get());
  e->SetByteArrayRegion((jbyteArray)aidJavaArray.get(), 0, aid.size(),
                        (jbyte*)&aid[0]);
  CHECK(!e->ExceptionCheck());

  std::string eeName;
  if (!getNameOfEe(ee_handle, eeName)) {
    return;
  }

  ScopedLocalRef<jobject> eeNameJavaString(e, e->NewStringUTF(eeName.c_str()));
  CHECK(eeNameJavaString.get());
  e->CallVoidMethod(mNativeData->manager,
                    android::gCachedNfcManagerNotifyEeAidSelected,
                    aidJavaArray.get(), eeNameJavaString.get());
}

/*******************************************************************************
**
** Function:        notifyEeProtocolSelected
**
** Description:     Notify upper layers of RF_NFCEE_ACTION_NTF with trigger
**                  protocol
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::notifyEeProtocolSelected(uint8_t protocol,
                                              tNFA_HANDLE ee_handle) {
  JNIEnv* e = NULL;
  ScopedAttach attach(mNativeData->vm, &e);
  CHECK(e);

  std::string eeName;
  if (!getNameOfEe(ee_handle, eeName)) {
    return;
  }

  ScopedLocalRef<jobject> eeNameJavaString(e, e->NewStringUTF(eeName.c_str()));
  CHECK(eeNameJavaString.get());
  e->CallVoidMethod(mNativeData->manager,
                    android::gCachedNfcManagerNotifyEeProtocolSelected,
                    protocol, eeNameJavaString.get());
}

/*******************************************************************************
**
** Function:        notifyEeTechSelected
**
** Description:     Notify upper layers of RF_NFCEE_ACTION_NTF with trigger
**                  technology
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::notifyEeTechSelected(uint8_t tech, tNFA_HANDLE ee_handle) {
  JNIEnv* e = NULL;
  ScopedAttach attach(mNativeData->vm, &e);
  CHECK(e);

  std::string eeName;
  if (!getNameOfEe(ee_handle, eeName)) {
    return;
  }

  ScopedLocalRef<jobject> eeNameJavaString(e, e->NewStringUTF(eeName.c_str()));
  CHECK(eeNameJavaString.get());
  e->CallVoidMethod(mNativeData->manager,
                    android::gCachedNfcManagerNotifyEeTechSelected, tech,
                    eeNameJavaString.get());
}

/*******************************************************************************
**
** Function:        notifyDeactivated
**
** Description:     Notify upper layers for CE deactivation
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::notifyDeactivated(uint8_t technology) {
  mRxDataBuffer.clear();
  JNIEnv* e = NULL;
  ScopedAttach attach(mNativeData->vm, &e);
  if (e == NULL) {
    LOG(ERROR) << __func__ << ": jni env is null";
    return;
  }

  e->CallVoidMethod(mNativeData->manager,
                    android::gCachedNfcManagerNotifyEeListenActivated,
                    JNI_FALSE);
  if (e->ExceptionCheck()) {
    e->ExceptionClear();
    LOG(ERROR) << StringPrintf("%s: Fail to notify Ee listen active status",
                               __func__);
  }

  e->CallVoidMethod(mNativeData->manager,
                    android::gCachedNfcManagerNotifyHostEmuDeactivated,
                    (int)technology);
  if (e->ExceptionCheck()) {
    e->ExceptionClear();
    LOG(ERROR) << StringPrintf("%s: fail notify", __func__);
  }
}

/*******************************************************************************
**
** Function:        handleData
**
** Description:     Notify upper layers of received HCE data
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::handleData(uint8_t technology, const uint8_t* data,
                                uint32_t dataLen, tNFA_STATUS status) {
  if (status == NFC_STATUS_CONTINUE) {
    if (dataLen > 0) {
      mRxDataBuffer.insert(mRxDataBuffer.end(), &data[0],
                           &data[dataLen]);  // append data; more to come
    }
    return;  // expect another NFA_CE_DATA_EVT to come
  } else if (status == NFA_STATUS_OK) {
    if (dataLen > 0) {
      mRxDataBuffer.insert(mRxDataBuffer.end(), &data[0],
                           &data[dataLen]);  // append data
    }
    // entire data packet has been received; no more NFA_CE_DATA_EVT
  } else if (status == NFA_STATUS_FAILED) {
    LOG(ERROR) << __func__ << ": read data fail";
    goto TheEnd;
  }

  {
    JNIEnv* e = NULL;
    ScopedAttach attach(mNativeData->vm, &e);
    if (e == NULL) {
      LOG(ERROR) << __func__ << ": jni env is null";
      goto TheEnd;
    }

    ScopedLocalRef<jobject> dataJavaArray(
        e, e->NewByteArray(mRxDataBuffer.size()));
    if (dataJavaArray.get() == NULL) {
      LOG(ERROR) << __func__ << ": fail allocate array";
      goto TheEnd;
    }

    e->SetByteArrayRegion((jbyteArray)dataJavaArray.get(), 0,
                          mRxDataBuffer.size(), (jbyte*)(&mRxDataBuffer[0]));
    if (e->ExceptionCheck()) {
      e->ExceptionClear();
      LOG(ERROR) << __func__ << ": fail fill array";
      goto TheEnd;
    }

    e->CallVoidMethod(mNativeData->manager,
                      android::gCachedNfcManagerNotifyHostEmuData,
                      (int)technology, dataJavaArray.get());
    if (e->ExceptionCheck()) {
      e->ExceptionClear();
      LOG(ERROR) << __func__ << ": fail notify";
    }
  }
TheEnd:
  mRxDataBuffer.clear();
}

/*******************************************************************************
**
** Function:        notifyEeUpdated
**
** Description:     notify upper layers of NFCEE RF capabilities update
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::notifyEeUpdated() {
  JNIEnv* e = NULL;
  ScopedAttach attach(mNativeData->vm, &e);
  if (e == NULL) {
    LOG(ERROR) << __func__ << ": jni env is null";
    return;
  }

  e->CallVoidMethod(mNativeData->manager,
                    android::gCachedNfcManagerNotifyEeUpdated);
  if (e->ExceptionCheck()) {
    e->ExceptionClear();
    LOG(ERROR) << __func__ << ": fail notify";
  }
}

/*******************************************************************************
**
** Function:        stackCallback
**
** Description:     Handles callback for completion of calls to NFA APIs
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::stackCallback(uint8_t event,
                                   tNFA_CONN_EVT_DATA* eventData) {
  static const char fn[] = "RoutingManager::stackCallback";
  RoutingManager& routingManager = RoutingManager::getInstance();

  switch (event) {
    case NFA_CE_REGISTERED_EVT: {
      tNFA_CE_REGISTERED& ce_registered = eventData->ce_registered;
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_CE_REGISTERED_EVT; status=0x%X; h=0x%X", fn,
          ce_registered.status, ce_registered.handle);
    } break;

    case NFA_CE_DEREGISTERED_EVT: {
      tNFA_CE_DEREGISTERED& ce_deregistered = eventData->ce_deregistered;
      LOG(DEBUG) << StringPrintf("%s: NFA_CE_DEREGISTERED_EVT; h=0x%X", fn,
                                 ce_deregistered.handle);
    } break;

    case NFA_CE_ACTIVATED_EVT: {
      LOG(DEBUG) << StringPrintf("%s: NFA_CE_ACTIVATED_EVT;", fn);
      routingManager.notifyActivated(NFA_TECHNOLOGY_MASK_A);
    } break;

    case NFA_DEACTIVATED_EVT:
    case NFA_CE_DEACTIVATED_EVT: {
      if (event == NFA_DEACTIVATED_EVT) {
        LOG(DEBUG) << StringPrintf("%s: NFA_DEACTIVATED_EVT", fn);
      } else {
        LOG(DEBUG) << StringPrintf("%s: NFA_CE_DEACTIVATED_EVT", fn);
      }
      routingManager.notifyDeactivated(NFA_TECHNOLOGY_MASK_A);
      SyncEventGuard g(gDeactivatedEvent);
      gActivated = false;  // guard this variable from multi-threaded access
      gDeactivatedEvent.notifyOne();
    } break;

    case NFA_CE_DATA_EVT: {
      tNFA_CE_DATA& ce_data = eventData->ce_data;
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_CE_DATA_EVT; stat=0x%X; h=0x%X; data len=%u", fn,
          ce_data.status, ce_data.handle, ce_data.len);
      getInstance().handleData(NFA_TECHNOLOGY_MASK_A, ce_data.p_data,
                               ce_data.len, ce_data.status);
    } break;
  }
}

/*******************************************************************************
**
** Function:        updateRoutingTable
**
** Description:     Receive execution environment-related events from stack.
**                  event: Event code.
**                  eventData: Event data.
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::updateRoutingTable() {
  static const char fn[] = "RoutingManager::updateRoutingTable";
  LOG(DEBUG) << fn << ":(enter)";
  mSeTechMask = updateEeTechRouteSetting();
  updateDefaultRoute();
  updateDefaultProtocolRoute();
  LOG(DEBUG) << fn << ":(exit)";
}

/*******************************************************************************
**
** Function:        updateIsoDepProtocolRoute
**
** Description:     Updates the route for ISO-DEP protocol
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::updateIsoDepProtocolRoute(int route) {
  static const char fn[] = "RoutingManager::updateIsoDepProtocolRoute";
  LOG(DEBUG) << StringPrintf("%s:  New default ISO-DEP route=0x%x", fn, route);
  setEeInfoChangedFlag();
  mDefaultIsoDepRoute = route;
}

/*******************************************************************************
**
** Function:        updateSystemCodeRoute
**
** Description:     Updates the route for System Code
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::updateSystemCodeRoute(int route) {
  static const char fn[] = "RoutingManager::updateSystemCodeRoute";
  LOG(DEBUG) << StringPrintf("%s:  New default SC route=0x%x", fn, route);
  setEeInfoChangedFlag();
  mDefaultSysCodeRoute = route;
}

/*******************************************************************************
**
** Function:        updateDefaultProtocolRoute
**
** Description:     Updates the default protocol routes
**
** Returns:
**
*******************************************************************************/
void RoutingManager::updateDefaultProtocolRoute() {
  static const char fn[] = "RoutingManager::updateDefaultProtocolRoute";

  LOG(DEBUG) << StringPrintf("%s:  Default ISO-DEP route=0x%x", fn,
                             mDefaultIsoDepRoute);
  // Default Routing for ISO-DEP
  tNFA_PROTOCOL_MASK protoMask = NFA_PROTOCOL_MASK_ISO_DEP;
  tNFA_STATUS nfaStat;
  if (mDefaultIsoDepRoute != NFC_DH_ID &&
      isTypeATypeBTechSupportedInEe(mDefaultIsoDepRoute |
                                    NFA_HANDLE_GROUP_EE)) {
    nfaStat = NFA_EeSetDefaultProtoRouting(
        mDefaultIsoDepRoute, protoMask, mSecureNfcEnabled ? 0 : protoMask, 0,
        mSecureNfcEnabled ? 0 : protoMask, mSecureNfcEnabled ? 0 : protoMask,
        mSecureNfcEnabled ? 0 : protoMask);
  } else {
    nfaStat = NFA_EeSetDefaultProtoRouting(
        NFC_DH_ID, protoMask, 0, 0, mSecureNfcEnabled ? 0 : protoMask, 0, 0);
    mDefaultIsoDepRoute = NFC_DH_ID;
  }
  if (nfaStat != NFA_STATUS_OK)
    LOG(ERROR) << fn << ": failed to register default ISO-DEP route";

  // Default routing for T3T protocol
  if (!mIsScbrSupported) {
    SyncEventGuard guard(mRoutingEvent);
    tNFA_PROTOCOL_MASK protoMask = NFA_PROTOCOL_MASK_T3T;
    if (mDefaultEe == NFC_DH_ID) {
      if ((mHostListenTechMask & NFA_TECHNOLOGY_MASK_F) != 0) {
        nfaStat =
          NFA_EeSetDefaultProtoRouting(NFC_DH_ID, protoMask, 0, 0, 0, 0, 0);
      } else {
        return;
      }
    } else {
      nfaStat = NFA_EeSetDefaultProtoRouting(
          mDefaultEe, protoMask, 0, 0, mSecureNfcEnabled ? 0 : protoMask,
          mSecureNfcEnabled ? 0 : protoMask, mSecureNfcEnabled ? 0 : protoMask);
    }
    if (sIsRecovering) return;
    if (nfaStat == NFA_STATUS_OK)
      mRoutingEvent.wait();
    else
      LOG(ERROR) << fn << ": Fail to set default proto routing for T3T";
  }
}

/*******************************************************************************
**
** Function:        updateDefaultRoute
**
** Description:     Updating default AID and SC (T3T) routes
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::updateDefaultRoute() {
  static const char fn[] = "RoutingManager::updateDefaultRoute";
  int defaultAidRoute = mDefaultEe;

  if (NFC_GetNCIVersion() < NCI_VERSION_2_0) return;

  LOG(DEBUG) << StringPrintf("%s:  Default SC route=0x%x", fn,
                             mDefaultSysCodeRoute);

  // remove SC routing
  if (!gFirstRun) {
    SyncEventGuard guard(mRoutingEvent);
    tNFA_STATUS stat = NFA_EeRemoveSystemCodeRouting(mDefaultSysCode);
    if (sIsRecovering) return;
    if (stat == NFA_STATUS_OK) {
      mRoutingEvent.wait();
    } else {
      LOG(ERROR) << fn << ": Fail to remove system code";
    }
  }

  // Register System Code for routing
  SyncEventGuard guard(mRoutingEvent);
  tNFA_STATUS nfaStat = NFA_EeAddSystemCodeRouting(
      mDefaultSysCode, mDefaultSysCodeRoute,
      mSecureNfcEnabled ? (mDefaultSysCodePowerstate & 0x01)
                        : mDefaultSysCodePowerstate);
  if (sIsRecovering) return;
  if (nfaStat == NFA_STATUS_NOT_SUPPORTED) {
    mIsScbrSupported = false;
    LOG(ERROR) << fn << ": SCBR not supported";
  } else if (nfaStat == NFA_STATUS_OK) {
    mIsScbrSupported = true;
    mRoutingEvent.wait();
  } else {
    LOG(ERROR) << fn << ": Fail to register system code";
    // still support SCBR routing for other NFCEEs
    mIsScbrSupported = true;
  }

  // Check if default AID was already added or not
  if (!mDefaultAidRouteAdded) {
    LOG(DEBUG) << StringPrintf("%s:  Default AID route=0x%x", fn,
                               defaultAidRoute);

    // Register zero lengthy Aid for default Aid Routing
    if ((defaultAidRoute != NFC_DH_ID) &&
        (!isTypeATypeBTechSupportedInEe(defaultAidRoute |
                                        NFA_HANDLE_GROUP_EE))) {
      defaultAidRoute = NFC_DH_ID;
    }
    if (!gFirstRun) {
      removeAidRouting(nullptr, 0);
    }
    uint8_t powerState = 0x01;
    if (!mSecureNfcEnabled) {
      powerState =
          (defaultAidRoute != 0x00) ? mOffHostAidRoutingPowerState : 0x11;
    }
    nfaStat = NFA_EeAddAidRouting(defaultAidRoute, 0, NULL, powerState,
                                  AID_ROUTE_QUAL_PREFIX);
    if (nfaStat != NFA_STATUS_OK) {
      LOG(ERROR) << fn << ": failed to register zero length AID";
    } else {
      mDefaultAidRouteAdded = true;
    }
  }
  gFirstRun = false;
}

/*******************************************************************************
**
** Function:        updateTechnologyABFRoute
**
** Description:     Updating default A/B/F routes
**
** Returns:         bitmask of routed technologies
**
*******************************************************************************/
tNFA_TECHNOLOGY_MASK RoutingManager::updateTechnologyABFRoute(int route,
                                                              int felicaRoute) {
  static const char fn[] = "RoutingManager::updateTechnologyABFRoute";
  LOG(DEBUG) << StringPrintf("%s:  New default A/B route=0x%x", fn, route);
  LOG(DEBUG) << StringPrintf("%s:  New default F route=0x%x", fn, felicaRoute);
  setEeTechRouteUpdateRequired();
  mDefaultFelicaRoute = felicaRoute;
  mDefaultOffHostRoute = route;
  return mSeTechMask;
}

/*******************************************************************************
**
** Function:        checkUiccListenConfigNeeded
**
** Description:     Check and update UICC listen configuration
**
** Returns:         None
**
*******************************************************************************/
bool RoutingManager::checkUiccListenConfigNeeded(
    tNFA_HANDLE eeHandle, tNFA_TECHNOLOGY_MASK seTechMask) {
  static const char fn[] = "RoutingManager::checkUiccListenConfigNeeded";
  LOG(DEBUG) << StringPrintf("%s: ee_handle=0x%04x, seTechMask=0x%02x", fn,
                             eeHandle, seTechMask);

  bool found = false, config = false;
  for (int j = 0; j < mNfceeListenConfig.nb_config; j++) {
    if (mNfceeListenConfig.config[j].nfcee_id == eeHandle) {
      found = true;
      if (mNfceeListenConfig.config[j].tech_mask != seTechMask) {
        mNfceeListenConfig.config[j].tech_mask = seTechMask;
        config = true;
        break;
      }
    }
  }
  if (!found) {
    if (mNfceeListenConfig.nb_config >= NFA_EE_MAX_EE_SUPPORTED) {
      LOG(ERROR) << StringPrintf("%s: Exceeded max EE supported count (%d)", fn,
                                 NFA_EE_MAX_EE_SUPPORTED);
      return false;
    }
    mNfceeListenConfig.config[mNfceeListenConfig.nb_config].nfcee_id = eeHandle;
    mNfceeListenConfig.config[mNfceeListenConfig.nb_config].tech_mask =
        seTechMask;
    mNfceeListenConfig.nb_config++;
    config = true;
  }
  return config;
}

/*******************************************************************************
**
** Function:        updateEeTechRouteSetting
**
** Description:     Update the route of listen A/B/F technologies
**
** Returns:         None
**
*******************************************************************************/
tNFA_TECHNOLOGY_MASK RoutingManager::updateEeTechRouteSetting() {
  static const char fn[] = "RoutingManager::updateEeTechRouteSetting";
  tNFA_TECHNOLOGY_MASK allSeTechMask = 0x00, hostTechMask = 0x00;

  // Get content of mEeInfo as it can change if a NTF is received during update
  // of RT
  sEeInfoMutex.lock();
  tNFA_EE_DISCOVER_REQ localEeInfo;
  memcpy(&localEeInfo, &mEeInfo, sizeof(mEeInfo));
  sEeInfoMutex.unlock();
  LOG(DEBUG) << StringPrintf("%s: Default route A/B: 0x%x", fn,
                             mDefaultOffHostRoute);
  LOG(DEBUG) << StringPrintf("%s:  Default route F=0x%x", fn,
                             mDefaultFelicaRoute);

  LOG(DEBUG) << StringPrintf("%s:  Nb NFCEE=%d", fn, mEeInfo.num_ee);

  tNFA_STATUS nfaStat;

  for (uint8_t i = 0; i < localEeInfo.num_ee; i++) {
    tNFA_HANDLE eeHandle = localEeInfo.ee_disc_info[i].ee_handle;
    tNFA_TECHNOLOGY_MASK seTechMask = 0;

    LOG(DEBUG) << StringPrintf(
        "%s:   EE[%u] Handle=0x%04x  techA=0x%02x  techB="
        "0x%02x  techF=0x%02x  techBprime=0x%02x",
        fn, i, eeHandle, localEeInfo.ee_disc_info[i].la_protocol,
        localEeInfo.ee_disc_info[i].lb_protocol,
        localEeInfo.ee_disc_info[i].lf_protocol,
        localEeInfo.ee_disc_info[i].lbp_protocol);

    if ((mDefaultOffHostRoute != NFC_DH_ID) &&
        (eeHandle == (mDefaultOffHostRoute | NFA_HANDLE_GROUP_EE))) {
      if (localEeInfo.ee_disc_info[i].la_protocol != 0) {
        seTechMask |= NFA_TECHNOLOGY_MASK_A;
      }
      if (localEeInfo.ee_disc_info[i].lb_protocol != 0) {
        seTechMask |= NFA_TECHNOLOGY_MASK_B;
      }
    }

    if ((mDefaultFelicaRoute != NFC_DH_ID) &&
        (eeHandle == (mDefaultFelicaRoute | NFA_HANDLE_GROUP_EE))) {
      if (localEeInfo.ee_disc_info[i].lf_protocol != 0) {
        seTechMask |= NFA_TECHNOLOGY_MASK_F;
      }
    }

    // If OFFHOST_LISTEN_TECH_MASK exists,
    // filter out the unspecified technologies
    seTechMask &= mOffHostListenTechMask;

    LOG(DEBUG) << StringPrintf("%s: seTechMask[%u]=0x%02x", fn, i, seTechMask);
    if (seTechMask != 0x00) {
      LOG(DEBUG) << StringPrintf(
          "%s: Configuring tech mask 0x%02x on EE 0x%04x", fn, seTechMask,
          eeHandle);

      if (checkUiccListenConfigNeeded(eeHandle, seTechMask)) {
        nfaStat = NFA_CeConfigureUiccListenTech(eeHandle, seTechMask);
        if (nfaStat != NFA_STATUS_OK)
          LOG(ERROR) << fn << ": Failed to configure UICC listen technologies.";
      }

      nfaStat = NFA_EeSetDefaultTechRouting(
          eeHandle, seTechMask, mSecureNfcEnabled ? 0 : seTechMask, 0,
          mSecureNfcEnabled ? 0 : seTechMask,
          mSecureNfcEnabled ? 0 : seTechMask,
          mSecureNfcEnabled ? 0 : seTechMask);
      if (nfaStat != NFA_STATUS_OK)
        LOG(ERROR) << StringPrintf(
            "%s: Failed to configure 0x%x technology routing", fn, eeHandle);

      allSeTechMask |= seTechMask;
    }
  }

  // Check if some tech should be routed to DH
  if (!(allSeTechMask & NFA_TECHNOLOGY_MASK_A) &&
      (mHostListenTechMask & NFA_TECHNOLOGY_MASK_A)) {
    hostTechMask |= NFA_TECHNOLOGY_MASK_A;
  }
  // Check if some tech should be routed to DH
  if (!(allSeTechMask & NFA_TECHNOLOGY_MASK_B) &&
      (mHostListenTechMask & NFA_TECHNOLOGY_MASK_B)) {
    hostTechMask |= NFA_TECHNOLOGY_MASK_B;
  }
  // Check if some tech should be routed to DH
  if (!(allSeTechMask & NFA_TECHNOLOGY_MASK_F) &&
      (mHostListenTechMask & NFA_TECHNOLOGY_MASK_F)) {
    hostTechMask |= NFA_TECHNOLOGY_MASK_F;
  }

  if (hostTechMask) {
    nfaStat = NFA_EeSetDefaultTechRouting(NFC_DH_ID, hostTechMask, 0, 0,
                                          mSecureNfcEnabled ? 0 : hostTechMask,
                                          mSecureNfcEnabled ? 0 : hostTechMask,
                                          mSecureNfcEnabled ? 0 : hostTechMask);
    if (nfaStat != NFA_STATUS_OK)
      LOG(ERROR) << fn << ": Failed to configure DH technology routing.";
  }

  return allSeTechMask;
}

/*******************************************************************************
**
** Function:        nfaEeCallback
**
** Description:     Receive execution environment-related events from stack.
**                  event: Event code.
**                  eventData: Event data.
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::nfaEeCallback(tNFA_EE_EVT event,
                                   tNFA_EE_CBACK_DATA* eventData) {
  static const char fn[] = "RoutingManager::nfaEeCallback";

  RoutingManager& routingManager = RoutingManager::getInstance();
  if (!eventData) {
    LOG(ERROR) << fn << ": eventData is null";
    return;
  }
  routingManager.mCbEventData = *eventData;
  switch (event) {
    case NFA_EE_REGISTER_EVT: {
      SyncEventGuard guard(routingManager.mEeRegisterEvent);
      LOG(DEBUG) << StringPrintf("%s: NFA_EE_REGISTER_EVT; status=%u", fn,
                                 eventData->ee_register);
      routingManager.mEeRegisterEvent.notifyOne();
    } break;

    case NFA_EE_DEREGISTER_EVT: {
      LOG(DEBUG) << StringPrintf("%s: NFA_EE_DEREGISTER_EVT; status=0x%X", fn,
                                 eventData->status);
      routingManager.mReceivedEeInfo = false;
      routingManager.mDeinitializing = false;
    } break;

    case NFA_EE_MODE_SET_EVT: {
      SyncEventGuard guard(routingManager.mEeSetModeEvent);
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_EE_MODE_SET_EVT; status=0x%04X  handle=0x%04X  ", fn,
          eventData->mode_set.status, eventData->mode_set.ee_handle);
      routingManager.mEeSetModeEvent.notifyOne();
    } break;

    case NFA_EE_SET_TECH_CFG_EVT: {
      LOG(DEBUG) << StringPrintf("%s: NFA_EE_SET_TECH_CFG_EVT; status=0x%X", fn,
                                 eventData->status);
    } break;

    case NFA_EE_CLEAR_TECH_CFG_EVT: {
      LOG(DEBUG) << StringPrintf("%s: NFA_EE_CLEAR_TECH_CFG_EVT; status=0x%X",
                                 fn, eventData->status);
    } break;

    case NFA_EE_SET_PROTO_CFG_EVT: {
      LOG(DEBUG) << StringPrintf("%s: NFA_EE_SET_PROTO_CFG_EVT; status=0x%X",
                                 fn, eventData->status);
      if (!routingManager.mIsScbrSupported) {
        SyncEventGuard guard(routingManager.mRoutingEvent);
        routingManager.mRoutingEvent.notifyOne();
      }
    } break;

    case NFA_EE_CLEAR_PROTO_CFG_EVT: {
      LOG(DEBUG) << StringPrintf("%s: NFA_EE_CLEAR_PROTO_CFG_EVT; status=0x%X",
                                 fn, eventData->status);
    } break;

    case NFA_EE_ACTION_EVT: {
      tNFA_EE_ACTION& action = eventData->action;
      if (action.trigger == NFC_EE_TRIG_SELECT) {
        tNFC_AID& aid = action.param.aid;
        LOG(DEBUG) << StringPrintf(
            "%s: NFA_EE_ACTION_EVT; h=0x%X; trigger=select (0x%X)", fn,
            action.ee_handle, action.trigger);
        routingManager.notifyEeAidSelected(aid, action.ee_handle);
      } else if (action.trigger == NFC_EE_TRIG_APP_INIT) {
        tNFC_APP_INIT& app_init = action.param.app_init;
        LOG(DEBUG) << StringPrintf(
            "%s: NFA_EE_ACTION_EVT; h=0x%X; trigger=app-init "
            "(0x%X); aid len=%u; data len=%u",
            fn, action.ee_handle, action.trigger, app_init.len_aid,
            app_init.len_data);
      } else if (action.trigger == NFC_EE_TRIG_RF_PROTOCOL) {
        LOG(DEBUG) << StringPrintf(
            "%s: NFA_EE_ACTION_EVT; h=0x%X; trigger=rf protocol (0x%X)", fn,
            action.ee_handle, action.trigger);
        routingManager.notifyEeProtocolSelected(action.param.protocol,
                                                  action.ee_handle);
      } else if (action.trigger == NFC_EE_TRIG_RF_TECHNOLOGY) {
        LOG(DEBUG) << StringPrintf(
            "%s: NFA_EE_ACTION_EVT; h=0x%X; trigger=rf tech (0x%X)", fn,
            action.ee_handle, action.trigger);
        routingManager.notifyEeTechSelected(action.param.technology,
                                              action.ee_handle);
      } else
        LOG(DEBUG) << StringPrintf(
            "%s: NFA_EE_ACTION_EVT; h=0x%X; unknown trigger (0x%X)", fn,
            action.ee_handle, action.trigger);
    } break;

    case NFA_EE_DISCOVER_REQ_EVT: {
      SyncEventGuard guard(routingManager.mEeInfoEvent);
      sEeInfoMutex.lock();
      memcpy(&routingManager.mEeInfo, &eventData->discover_req,
             sizeof(routingManager.mEeInfo));
      for (int i = 0; i < eventData->discover_req.num_ee; i++) {
        LOG(DEBUG) << StringPrintf(
            "%s: NFA_EE_DISCOVER_REQ_EVT; nfceeId=0x%X; la_proto=0x%X, "
            "lb_proto=0x%x, lf_proto=0x%x",
            fn, eventData->discover_req.ee_disc_info[i].ee_handle,
            eventData->discover_req.ee_disc_info[i].la_protocol,
            eventData->discover_req.ee_disc_info[i].lb_protocol,
            eventData->discover_req.ee_disc_info[i].lf_protocol);
      }
      sEeInfoMutex.unlock();
      if (!routingManager.mIsRFDiscoveryOptimized) {
        if (routingManager.mReceivedEeInfo && !routingManager.mDeinitializing) {
          routingManager.setEeInfoChangedFlag();
          routingManager.notifyEeUpdated();
        }
      }
      routingManager.mReceivedEeInfo = true;
      routingManager.mEeInfoEvent.notifyOne();
    } break;

    case NFA_EE_ENABLED_EVT: {
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_EE_ENABLED_EVT; status=0x%X; num ee=%u", __func__,
          eventData->discover_req.status, eventData->discover_req.num_ee);
      if (routingManager.mIsRFDiscoveryOptimized) {
        if (routingManager.mReceivedEeInfo && !routingManager.mDeinitializing) {
          routingManager.setEeInfoChangedFlag();
          routingManager.notifyEeUpdated();
        }
      }
    } break;

    case NFA_EE_NO_CB_ERR_EVT:
      LOG(DEBUG) << StringPrintf("%s: NFA_EE_NO_CB_ERR_EVT  status=%u", fn,
                                 eventData->status);
      break;

    case NFA_EE_ADD_AID_EVT: {
      LOG(DEBUG) << StringPrintf("%s: NFA_EE_ADD_AID_EVT  status=%u", fn,
                                 eventData->status);
      SyncEventGuard guard(routingManager.mAidAddRemoveEvent);
      routingManager.mAidRoutingConfigured =
          (eventData->status == NFA_STATUS_OK);
      routingManager.mAidAddRemoveEvent.notifyOne();
      LOG(DEBUG) << StringPrintf("%s: NFA_EE_ADD_AID_EVT notified", fn);
    } break;

    case NFA_EE_ADD_SYSCODE_EVT: {
      SyncEventGuard guard(routingManager.mRoutingEvent);
      routingManager.mRoutingEvent.notifyOne();
      LOG(DEBUG) << StringPrintf("%s: NFA_EE_ADD_SYSCODE_EVT  status=%u", fn,
                                 eventData->status);
    } break;

    case NFA_EE_REMOVE_SYSCODE_EVT: {
      SyncEventGuard guard(routingManager.mRoutingEvent);
      routingManager.mRoutingEvent.notifyOne();
      LOG(DEBUG) << StringPrintf("%s: NFA_EE_REMOVE_SYSCODE_EVT  status=%u", fn,
                                 eventData->status);
    } break;

    case NFA_EE_REMOVE_AID_EVT: {
      LOG(DEBUG) << StringPrintf("%s: NFA_EE_REMOVE_AID_EVT  status=%u", fn,
                                 eventData->status);
      SyncEventGuard guard(routingManager.mAidAddRemoveEvent);
      routingManager.mAidRoutingConfigured =
          (eventData->status == NFA_STATUS_OK);
      routingManager.mAidAddRemoveEvent.notifyOne();
      LOG(DEBUG) << StringPrintf("%s: NFA_EE_REMOVE_AID_EVT notified", fn);
    } break;

    case NFA_EE_NEW_EE_EVT: {
      LOG(DEBUG) << StringPrintf("%s: NFA_EE_NEW_EE_EVT  h=0x%X; status=%u", fn,
                                 eventData->new_ee.ee_handle,
                                 eventData->new_ee.ee_status);
    } break;

    case NFA_EE_UPDATED_EVT: {
      LOG(DEBUG) << StringPrintf("%s: NFA_EE_UPDATED_EVT", fn);
      routingManager.mAidRoutingConfigured = false;
      SyncEventGuard guard(routingManager.mEeUpdateEvent);
      routingManager.mEeUpdateEvent.notifyOne();
    } break;

    case NFA_EE_PWR_AND_LINK_CTRL_EVT: {
      LOG(DEBUG) << StringPrintf("%s: NFA_EE_PWR_AND_LINK_CTRL_EVT", fn);
      SyncEventGuard guard(routingManager.mEePwrAndLinkCtrlEvent);
      routingManager.mEePwrAndLinkCtrlEvent.notifyOne();
    } break;

    default:
      LOG(DEBUG) << StringPrintf("%s: unknown event=%u ????", fn, event);
      break;
  }
}

/*******************************************************************************
**
** Function:        registerT3tIdentifier
**
** Description:     register a t3T identifier for HCE-F purposes
**
** Returns:         None
**
*******************************************************************************/
int RoutingManager::registerT3tIdentifier(uint8_t* t3tId, uint8_t t3tIdLen) {
  static const char fn[] = "RoutingManager::registerT3tIdentifier";

  LOG(DEBUG) << fn << ": Start to register NFC-F system on DH";

  if (t3tIdLen != (2 + NCI_RF_F_UID_LEN + NCI_T3T_PMM_LEN)) {
    LOG(ERROR) << fn << ": Invalid length of T3T Identifier";
    return NFA_HANDLE_INVALID;
  }

  mNfcFOnDhHandle = NFA_HANDLE_INVALID;

  uint16_t systemCode;
  uint8_t nfcid2[NCI_RF_F_UID_LEN];
  uint8_t t3tPmm[NCI_T3T_PMM_LEN];

  systemCode = (((int)t3tId[0] << 8) | ((int)t3tId[1] << 0));
  memcpy(nfcid2, t3tId + 2, NCI_RF_F_UID_LEN);
  memcpy(t3tPmm, t3tId + 10, NCI_T3T_PMM_LEN);
  {
    SyncEventGuard guard(mRoutingEvent);
    tNFA_STATUS nfaStat = NFA_CeRegisterFelicaSystemCodeOnDH(
        systemCode, nfcid2, t3tPmm, nfcFCeCallback);
    if (nfaStat == NFA_STATUS_OK) {
      mRoutingEvent.wait();
    } else {
      LOG(ERROR) << fn << ": Fail to register NFC-F system on DH";
      return NFA_HANDLE_INVALID;
    }
  }
  LOG(DEBUG) << fn << ": Succeed to register NFC-F system on DH";

  // Register System Code for routing
  if (mIsScbrSupported) {
    SyncEventGuard guard(mRoutingEvent);
    tNFA_STATUS nfaStat = NFA_EeAddSystemCodeRouting(systemCode, NCI_DH_ID,
                                                     SYS_CODE_PWR_STATE_HOST);
    if (nfaStat == NFA_STATUS_OK) {
      mRoutingEvent.wait();
    }
    setEeTechRouteUpdateRequired();
    if ((nfaStat != NFA_STATUS_OK) || (mCbEventData.status != NFA_STATUS_OK)) {
      LOG(ERROR) << StringPrintf("%s: Fail to register system code on DH", fn);
      return NFA_HANDLE_INVALID;
    }
    LOG(DEBUG) << StringPrintf("%s: Succeed to register system code on DH", fn);
    setEeInfoChangedFlag();
    // add handle and system code pair to the map
    mMapScbrHandle.emplace(mNfcFOnDhHandle, systemCode);
  } else {
    LOG(ERROR) << StringPrintf("%s: SCBR Not supported", fn);
  }

  return mNfcFOnDhHandle;
}

/*******************************************************************************
**
** Function:        deregisterT3tIdentifier
**
** Description:     Deregisters the T3T identifier used for HCE-F purposes
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::deregisterT3tIdentifier(int handle) {
  static const char fn[] = "RoutingManager::deregisterT3tIdentifier";

  LOG(DEBUG) << StringPrintf("%s: Start to deregister NFC-F system on DH", fn);
  {
    SyncEventGuard guard(mRoutingEvent);
    tNFA_STATUS nfaStat = NFA_CeDeregisterFelicaSystemCodeOnDH(handle);
    if (nfaStat == NFA_STATUS_OK) {
      mRoutingEvent.wait();
      LOG(DEBUG) << StringPrintf(
          "%s: Succeeded in deregistering NFC-F system on DH", fn);
    } else {
      LOG(ERROR) << StringPrintf("%s: Fail to deregister NFC-F system on DH",
                                 fn);
    }
  }
  if (mIsScbrSupported) {
    map<int, uint16_t>::iterator it = mMapScbrHandle.find(handle);
    // find system code for given handle
    if (it != mMapScbrHandle.end()) {
      uint16_t systemCode = it->second;
      mMapScbrHandle.erase(handle);
      if (systemCode != 0) {
        SyncEventGuard guard(mRoutingEvent);
        tNFA_STATUS nfaStat = NFA_EeRemoveSystemCodeRouting(systemCode);
        if (nfaStat == NFA_STATUS_OK) {
          mRoutingEvent.wait();
          setEeInfoChangedFlag();
          LOG(DEBUG) << StringPrintf(
              "%s: Succeeded in deregistering system Code on DH", fn);
        } else {
          LOG(ERROR) << StringPrintf("%s: Fail to deregister system Code on DH",
                                     fn);
        }
        setEeTechRouteUpdateRequired();
      }
    }
  }
}

/*******************************************************************************
**
** Function:        nfcFCeCallback
**
** Description:     Receive execution environment-related events from stack.
**                  event: Event code.
**                  eventData: Event data.
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::nfcFCeCallback(uint8_t event,
                                    tNFA_CONN_EVT_DATA* eventData) {
  static const char fn[] = "RoutingManager::nfcFCeCallback";
  RoutingManager& routingManager = RoutingManager::getInstance();

  switch (event) {
    case NFA_CE_REGISTERED_EVT: {
      LOG(DEBUG) << StringPrintf("%s: NFA_CE_REGISTERED_EVT", fn);
      routingManager.mNfcFOnDhHandle = eventData->ce_registered.handle;
      SyncEventGuard guard(routingManager.mRoutingEvent);
      routingManager.mRoutingEvent.notifyOne();
    } break;
    case NFA_CE_DEREGISTERED_EVT: {
      LOG(DEBUG) << StringPrintf("%s: NFA_CE_DEREGISTERED_EVT", fn);
      SyncEventGuard guard(routingManager.mRoutingEvent);
      routingManager.mRoutingEvent.notifyOne();
    } break;
    case NFA_CE_ACTIVATED_EVT: {
      LOG(DEBUG) << StringPrintf("%s: NFA_CE_ACTIVATED_EVT", fn);
      routingManager.notifyActivated(NFA_TECHNOLOGY_MASK_F);
    } break;
    case NFA_CE_DEACTIVATED_EVT: {
      LOG(DEBUG) << StringPrintf("%s: NFA_CE_DEACTIVATED_EVT", fn);
      routingManager.notifyDeactivated(NFA_TECHNOLOGY_MASK_F);
    } break;
    case NFA_CE_DATA_EVT: {
      LOG(DEBUG) << StringPrintf("%s: NFA_CE_DATA_EVT", fn);
      tNFA_CE_DATA& ce_data = eventData->ce_data;
      routingManager.handleData(NFA_TECHNOLOGY_MASK_F, ce_data.p_data,
                                ce_data.len, ce_data.status);
    } break;
    default: {
      LOG(DEBUG) << StringPrintf("%s: unknown event=%u ????", fn, event);
    } break;
  }
}

/*******************************************************************************
**
** Function:        setNfcSecure
**
** Description:     set the NFC secure status
**
** Returns:         true
**
*******************************************************************************/
bool RoutingManager::setNfcSecure(bool enable) {
  mSecureNfcEnabled = enable;
  LOG(INFO) << __func__ << ": enable= " << enable;
  NFA_SetNfcSecure(enable);
  return true;
}

/*******************************************************************************
**
** Function:        eeSetPwrAndLinkCtrl
**
** Description:     Programs the NCI command NFCEE_POWER_AND_LINK_CTRL_CMD
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::eeSetPwrAndLinkCtrl(uint8_t config) {
  static const char fn[] = "RoutingManager::eeSetPwrAndLinkCtrl";
  tNFA_STATUS status = NFA_STATUS_OK;

  if (mOffHostRouteEse.size() > 0) {
    LOG(DEBUG) << StringPrintf("%s: nfceeId=0x%02X, config=0x%02X", fn,
                               mOffHostRouteEse[0], config);

    SyncEventGuard guard(mEePwrAndLinkCtrlEvent);
    status =
        NFA_EePowerAndLinkCtrl(
            ((uint8_t)mOffHostRouteEse[0] | NFA_HANDLE_GROUP_EE), config);
    if (status != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: fail NFA_EePowerAndLinkCtrl; error=0x%X",
                                 fn, status);
      return;
    } else {
      mEePwrAndLinkCtrlEvent.wait();
    }
  } else {
    LOG(ERROR) << StringPrintf("%s: No ESE specified", fn);
  }
}

/*******************************************************************************
**
** Function:        clearRoutingEntry
**
** Description:     Receive execution environment-related events from stack.
**                  event: Event code.
**                  eventData: Event data.
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::clearRoutingEntry(int clearFlags) {
  static const char fn[] = "RoutingManager::clearRoutingEntry";

  LOG(DEBUG) << StringPrintf("%s:   clearFlags = %x", fn, clearFlags);
  bool clear_tech = false, clear_proto = false, clear_sc = false;

  if (clearFlags & CLEAR_AID_ENTRIES) {
    LOG(DEBUG) << StringPrintf("%s:  clear all of aid based routing", fn);
    RoutingManager::getInstance().removeAidRouting((uint8_t*)NFA_REMOVE_ALL_AID,
                                                   NFA_REMOVE_ALL_AID_LEN);
    mDefaultAidRouteAdded = false;
    setEeTechRouteUpdateRequired();
  }

  if (clearFlags & CLEAR_PROTOCOL_ENTRIES) {
    clear_proto = true;
  }

  if (clearFlags & CLEAR_TECHNOLOGY_ENTRIES) {
    clear_tech = true;
  }

  if (clearFlags & CLEAR_SC_ENTRIES) {
    clear_sc = true;
  }

  if (clearFlags > CLEAR_AID_ENTRIES) {
    NFA_EeClearRoutingTable(clear_tech, clear_proto, clear_sc);
  }
}

/*******************************************************************************
**
** Function:        setEeTechRouteUpdateRequired
**
** Description:     Set flag EeInfoChanged so that tech route will be updated
**                  when applying route table.
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::setEeTechRouteUpdateRequired() {
  static const char fn[] = "RoutingManager::setEeTechRouteUpdateRequired";

  LOG(DEBUG) << StringPrintf("%s", fn);

  // Setting flag for Ee info changed so that
  // routing table can be updated
  setEeInfoChangedFlag();
}

/*******************************************************************************
**
** Function:        deinitialize
**
** Description:     Called for NFC disable
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::deinitialize() {
  onNfccShutdown();
  NFA_EeDeregister(nfaEeCallback);
}

/*******************************************************************************
**
** Function:        setEeInfoChangedFlag
**
** Description:     .
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::setEeInfoChangedFlag() {
  static const char fn[] = "RoutingManager::setEeInfoChangedFlag";
  LOG(DEBUG) << StringPrintf("%s", fn);
  sEeInfoChangedMutex.lock();
  mEeInfoChanged = true;
  sEeInfoChangedMutex.unlock();
}

/*******************************************************************************
**
** Function:        isRTUpdateOptimized
**
** Description:     Checking if routing table update optimized or not.
**
** Returns:         True/False
**
*******************************************************************************/
bool RoutingManager::isRTUpdateOptimized() {
  return mIsRTUpdateOptimized;
}

/*******************************************************************************
**
** Function:        registerJniFunctions
**
** Description:     called at object creation to register JNI function
**
** Returns:         None
**
*******************************************************************************/
int RoutingManager::registerJniFunctions(JNIEnv* e) {
  static const char fn[] = "RoutingManager::registerJniFunctions";
  LOG(DEBUG) << StringPrintf("%s", fn);
  return jniRegisterNativeMethods(
      e, "com/android/nfc/cardemulation/RoutingOptionManager", sMethods,
      NELEM(sMethods));
}

/*******************************************************************************
**
** Function:        com_android_nfc_cardemulation_doGetDefaultRouteDestination
**
** Description:     Retrieves the default NFCEE route
**
** Returns:         default NFCEE route
**
*******************************************************************************/
int RoutingManager::com_android_nfc_cardemulation_doGetDefaultRouteDestination(
    JNIEnv*) {
  return getInstance().mDefaultEe;
}

/*******************************************************************************
**
** Function: com_android_nfc_cardemulation_doGetDefaultOffHostRouteDestination
**
** Description:     retrieves the default off host route
**
** Returns:         off host route
**
*******************************************************************************/
int RoutingManager::
    com_android_nfc_cardemulation_doGetDefaultOffHostRouteDestination(JNIEnv*) {
  return NfcConfig::getUnsigned(NAME_DEFAULT_OFFHOST_ROUTE, 0x00);
}

/*******************************************************************************
**
** Function:        com_android_nfc_cardemulation_doGetDefaultFelicaRouteDestination
**
** Description:     retrieves the default Felica route
**
** Returns:         felica route
**
*******************************************************************************/
int RoutingManager::
    com_android_nfc_cardemulation_doGetDefaultFelicaRouteDestination(JNIEnv*) {
  return NfcConfig::getUnsigned(NAME_DEFAULT_NFCF_ROUTE, 0x00);
}

/*******************************************************************************
**
** Function:        com_android_nfc_cardemulation_doGetEuiccMepMode
**
** Description:     retrieves mep mode of euicc
**
** Returns:         mep mode
**
*******************************************************************************/

int RoutingManager::com_android_nfc_cardemulation_doGetEuiccMepMode(JNIEnv*) {
  return getInstance().mEuiccMepMode;
}

/*******************************************************************************
**
** Function:        com_android_nfc_cardemulation_doGetOffHostUiccDestination
**
** Description:     retrieves the default UICC NFCEE-ID
**
** Returns:         areay of NFCEE Id for UICC
**
*******************************************************************************/
jbyteArray
RoutingManager::com_android_nfc_cardemulation_doGetOffHostUiccDestination(
    JNIEnv* e) {
  std::vector<uint8_t> uicc = getInstance().mOffHostRouteUicc;
  if (uicc.size() == 0) {
    return NULL;
  }
  CHECK(e);
  jbyteArray uiccJavaArray = e->NewByteArray(uicc.size());
  CHECK(uiccJavaArray);
  e->SetByteArrayRegion(uiccJavaArray, 0, uicc.size(), (jbyte*)&uicc[0]);
  return uiccJavaArray;
}

/*******************************************************************************
**
** Function: com_android_nfc_cardemulation_doGetOffHostEseDestination
**
** Description:     Retrieves the NFCEE id for eSE
**
** Returns:         array of NFCEE Ids

**
*******************************************************************************/
jbyteArray
RoutingManager::com_android_nfc_cardemulation_doGetOffHostEseDestination(
    JNIEnv * e) {
  std::vector<uint8_t> ese = getInstance().mOffHostRouteEse;
  if (ese.size() == 0) {
    return NULL;
  }
  CHECK(e);
  jbyteArray eseJavaArray = e->NewByteArray(ese.size());
  CHECK(eseJavaArray);
  e->SetByteArrayRegion(eseJavaArray, 0, ese.size(), (jbyte*)&ese[0]);
  return eseJavaArray;
}

/*******************************************************************************
**
** Function: com_android_nfc_cardemulation_doGetAidMatchingMode
**
** Description:     Retrieves the AID matching mode
**
** Returns:         matching mode
**
*******************************************************************************/
int RoutingManager::com_android_nfc_cardemulation_doGetAidMatchingMode(
    JNIEnv*) {
  return getInstance().mAidMatchingMode;
}

/*******************************************************************************
**
** Function: com_android_nfc_cardemulation_doGetDefaultIsoDepRouteDestination
**
** Description:     Retrieves the route for ISO-DEP
**
** Returns:         ISO-DEP route

**
*******************************************************************************/
int RoutingManager::
    com_android_nfc_cardemulation_doGetDefaultIsoDepRouteDestination(JNIEnv*) {
  return NfcConfig::getUnsigned(NAME_DEFAULT_ISODEP_ROUTE, 0x0);
}

/*******************************************************************************
**
** Function:        com_android_nfc_cardemulation_doGetDefaultScRouteDestination
**
** Description:     Retrieves the default NFCEE route
**
** Returns:         default NFCEE route
**
*******************************************************************************/
int RoutingManager::com_android_nfc_cardemulation_doGetDefaultScRouteDestination(
    JNIEnv*) {
  return NfcConfig::getUnsigned(NAME_DEFAULT_SYS_CODE_ROUTE, 0xC0);
}
