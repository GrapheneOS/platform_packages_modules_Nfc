/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include <android-base/logging.h>
#include <android-base/stringprintf.h>
#include <cutils/properties.h>
#include <errno.h>
#include <nativehelper/JNIPlatformHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include <semaphore.h>

#include "HciEventManager.h"
#include "JavaClassConstants.h"
#include "NativeWlcManager.h"
#include "NfcAdaptation.h"
#ifdef DTA_ENABLED
#include "NfcDta.h"
#endif /* DTA_ENABLED */
#include "NativeT4tNfcee.h"
#include "NfcJniUtil.h"
#include "NfcTag.h"
#include "NfceeManager.h"
#include "RoutingManager.h"
#include "SyncEvent.h"
#include "android_nfc.h"
#include "ce_api.h"
#include "com_android_nfc_module_flags.h"
#include "debug_lmrt.h"
#include "debug_rf_discover.h"
#include "nfa_api.h"
#include "nfa_ee_api.h"
#include "nfa_nfcee_int.h"
#include "nfc_brcm_defs.h"
#include "nfc_config.h"
#include "rw_api.h"

using android::base::StringPrintf;

extern tNFA_DM_DISC_FREQ_CFG* p_nfa_dm_rf_disc_freq_cfg;  // defined in stack
namespace android {
extern bool gIsTagDeactivating;
extern bool gIsSelectingRfInterface;
extern bool gTagJustActivated;
extern void nativeNfcTag_doTransceiveStatus(tNFA_STATUS status, uint8_t* buf,
                                            uint32_t buflen);
extern void nativeNfcTag_notifyRfTimeout();
extern void nativeNfcTag_doConnectStatus(jboolean is_connect_ok);
extern void nativeNfcTag_doDeactivateStatus(int status);
extern void nativeNfcTag_doWriteStatus(jboolean is_write_ok);
extern jboolean nativeNfcTag_doDisconnect(JNIEnv*, jobject);
extern void nativeNfcTag_doCheckNdefResult(tNFA_STATUS status,
                                           uint32_t max_size,
                                           uint32_t current_size,
                                           uint8_t flags);
extern void nativeNfcTag_doMakeReadonlyResult(tNFA_STATUS status);
extern void nativeNfcTag_doPresenceCheckResult(tNFA_STATUS status);
extern void nativeNfcTag_formatStatus(bool is_ok);
extern void nativeNfcTag_resetPresenceCheck();
extern void nativeNfcTag_doReadCompleted(tNFA_STATUS status);
extern void nativeNfcTag_setRfInterface(tNFA_INTF_TYPE rfInterface);
extern void nativeNfcTag_setActivatedRfProtocol(tNFA_INTF_TYPE rfProtocol);
extern void nativeNfcTag_setActivatedRfMode(uint8_t rfMode);
extern void nativeNfcTag_abortWaits();
extern void nativeNfcTag_registerNdefTypeHandler();
extern void nativeNfcTag_acquireRfInterfaceMutexLock();
extern void nativeNfcTag_releaseRfInterfaceMutexLock();
extern void updateNfcID0Param(uint8_t* nfcID0);
}  // namespace android

/*****************************************************************************
**
** public variables and functions
**
*****************************************************************************/
bool gActivated = false;
bool sIsRecovering = false;
SyncEvent gDeactivatedEvent;
SyncEvent sNfaSetPowerSubState;
int recovery_option = 0;
int always_on_nfcee_power_and_link_conf = 0;
int disable_always_on_nfcee_power_and_link_conf = 0;

namespace android {
jmethodID gCachedNfcManagerNotifyNdefMessageListeners;
jmethodID gCachedNfcManagerNotifyTransactionListeners;
jmethodID gCachedNfcManagerNotifyHostEmuActivated;
jmethodID gCachedNfcManagerNotifyHostEmuData;
jmethodID gCachedNfcManagerNotifyHostEmuDeactivated;
jmethodID gCachedNfcManagerNotifyRfFieldActivated;
jmethodID gCachedNfcManagerNotifyRfFieldDeactivated;
jmethodID gCachedNfcManagerNotifyEeUpdated;
jmethodID gCachedNfcManagerNotifyTagDiscovered;
jmethodID gCachedNfcManagerNotifyHwErrorReported;
jmethodID gCachedNfcManagerNotifyPollingLoopFrame;
jmethodID gCachedNfcManagerNotifyWlcStopped;
jmethodID gCachedNfcManagerNotifyVendorSpecificEvent;
jmethodID gCachedNfcManagerNotifyCommandTimeout;
jmethodID gCachedNfcManagerNotifyObserveModeChanged;
jmethodID gCachedNfcManagerNotifyRfDiscoveryEvent;
jmethodID gCachedNfcManagerNotifyEeAidSelected;
jmethodID gCachedNfcManagerNotifyEeProtocolSelected;
jmethodID gCachedNfcManagerNotifyEeTechSelected;
jmethodID gCachedNfcManagerNotifyEeListenActivated;
jmethodID gCachedNfcManagerOnRestartRfDiscovery;
jmethodID gCachedNfcManagerOnObserveModeDisabledInFirmware;
jmethodID gCachedNfcManagerOnObserveModeEnabledInFirmware;
jmethodID gCachedNfcManagerNotifyEndpointRemoved;

const char* gNativeNfcTagClassName = "com/android/nfc/dhimpl/NativeNfcTag";
const char* gNativeNfcManagerClassName =
    "com/android/nfc/dhimpl/NativeNfcManager";
const char* gNfcVendorNciResponseClassName =
    "com/android/nfc/NfcVendorNciResponse";
const char* gNativeT4tNfceeClassName =
    "com/android/nfc/dhimpl/NativeT4tNfceeManager";
void doStartupConfig();
void startStopPolling(bool isStartPolling);
void startRfDiscovery(bool isStart);
bool isDiscoveryStarted();
}  // namespace android

/*****************************************************************************
**
** private variables and functions
**
*****************************************************************************/
namespace android {
static SyncEvent sNfaEnableEvent;                // event for NFA_Enable()
static SyncEvent sNfaDisableEvent;               // event for NFA_Disable()
static SyncEvent sNfaEnableDisablePollingEvent;  // event for
                                                 // NFA_EnablePolling(),
                                                 // NFA_DisablePolling()
SyncEvent gNfaSetConfigEvent;                    // event for Set_Config....
SyncEvent gNfaGetConfigEvent;                    // event for Get_Config....
SyncEvent gNfaVsCommand;                         // event for VS commands
SyncEvent gSendRawVsCmdEvent;  // event for NFA_SendRawVsCommand()
SyncEvent gNfaRemoveEpEvent;   // event for StartRemoval....
static bool sIsEpDetectStarted = false;
static bool sIsNfaEnabled = false;
static bool sPollingEnabled = false;    // is polling for tag?
bool sIsDisabling = false;
static bool sRfEnabled = false;   // whether RF discovery is enabled
static bool sSeRfActive = false;  // whether RF with SE is likely active
static bool sReaderModeEnabled = false;  // whether we're only reading tags, not allowing card emu
static bool sAbortConnlessWait = false;
static jint sLfT3tMax = 0;
static bool sRoutingInitialized = false;
static bool sIsAlwaysPolling = false;
static std::vector<uint8_t> sRawVendorCmdResponse;
static bool sEnableVendorNciNotifications = false;
static bool sIsShuttingDown = false;

#define CONFIG_UPDATE_TECH_MASK (1 << 1)
#define DEFAULT_TECH_MASK                                                  \
  (NFA_TECHNOLOGY_MASK_A | NFA_TECHNOLOGY_MASK_B | NFA_TECHNOLOGY_MASK_F | \
   NFA_TECHNOLOGY_MASK_V | NFA_TECHNOLOGY_MASK_B_PRIME |                   \
   NFA_TECHNOLOGY_MASK_A_ACTIVE | NFA_TECHNOLOGY_MASK_F_ACTIVE |           \
   NFA_TECHNOLOGY_MASK_KOVIO)
#define DEFAULT_DISCOVERY_DURATION 500
#define READER_MODE_DISCOVERY_DURATION 200
#define FLAG_SET_DEFAULT_TECH 0x40000000
#define DEFAULT_MAX_POLLING_WAIT_TIME 5000

static void nfaConnectionCallback(uint8_t event, tNFA_CONN_EVT_DATA* eventData);
static void nfaDeviceManagementCallback(uint8_t event,
                                        tNFA_DM_CBACK_DATA* eventData);
static bool isListenMode(tNFA_ACTIVATED& activated);
static tNFA_STATUS stopPolling_rfDiscoveryDisabled();
static tNFA_STATUS startPolling_rfDiscoveryDisabled(
    tNFA_TECHNOLOGY_MASK tech_mask);
static void nfcManager_doSetScreenState(JNIEnv* e, jobject o,
                                        jint screen_state_mask,
                                        jboolean alwaysPoll);
static jboolean nfcManager_doSetPowerSavingMode(JNIEnv* e, jobject o,
                                                bool flag);
static void sendRawVsCmdCallback(uint8_t event, uint16_t param_len,
                                 uint8_t* p_param);
static jbyteArray nfcManager_getProprietaryCaps(JNIEnv* e, jobject o);
static jboolean nfcManager_setFirmwareExitFrameTable(JNIEnv* env, jobject o,
                                                     jobjectArray exit_frames,
                                                     jbyteArray timeout_ms);
static void nfcManager_restartRfDiscovery(JNIEnv* e, jobject o);
tNFA_STATUS gVSCmdStatus = NFA_STATUS_OK;
uint16_t gCurrentConfigLen;
uint8_t gConfig[256];
std::vector<uint8_t> gCaps(0);
std::vector<uint8_t> defaultFrame;

// sPrevScreenStateMask contains screen state + polling enable/disable mask
//  Possible screen states:
//  NFA_SCREEN_STATE_UNKNOWN  0x00
//  NFA_SCREEN_STATE_OFF_UNLOCKED  0x01
//  NFA_SCREEN_STATE_OFF_LOCKED    0x02
//  NFA_SCREEN_STATE_ON_LOCKED     0x04
//  NFA_SCREEN_STATE_ON_UNLOCKED   0x08
//  Polling:
//  NFA_SCREEN_POLLING_TAG_MASK    0x10
//  0x10 polling enable / 0x00 polling disable
static int sPrevScreenStateMask = NFA_SCREEN_STATE_UNKNOWN;
bool gIsDtaEnabled = false;
static bool gObserveModeEnabled = false;
static int gPartialInitMode = ENABLE_MODE_DEFAULT;
Mutex gMutexConfig;
/////////////////////////////////////////////////////////////
/////////////////////////////////////////////////////////////

namespace {
void initializeGlobalDebugEnabledFlag() {
  bool nfc_debug_enabled =
      (NfcConfig::getUnsigned(NAME_NFC_DEBUG_ENABLED, 1) != 0) ||
      property_get_bool("persist.nfc.debug_enabled", true);

  android::base::SetMinimumLogSeverity(nfc_debug_enabled ? android::base::DEBUG
                                                         : android::base::INFO);
}

void initializeRecoveryOption() {
  recovery_option = NfcConfig::getUnsigned(NAME_RECOVERY_OPTION, 1);

  LOG(DEBUG) << __func__ << ": recovery option=" << recovery_option;
}

void initializeNfceePowerAndLinkConf() {
  always_on_nfcee_power_and_link_conf =
      NfcConfig::getUnsigned(NAME_ALWAYS_ON_SET_EE_POWER_AND_LINK_CONF, 0);

  LOG(DEBUG) << __func__ << ": Always on set NFCEE_POWER_AND_LINK_CONF="
             << always_on_nfcee_power_and_link_conf;
}

void initializeDisableAlwaysOnNfceePowerAndLinkConf() {
  disable_always_on_nfcee_power_and_link_conf = NfcConfig::getUnsigned(
      NAME_DISABLE_ALWAYS_ON_SET_EE_POWER_AND_LINK_CONF, 0);

  LOG(DEBUG) << __func__ << ": Always on set NFCEE_POWER_AND_LINK_CONF="
             << disable_always_on_nfcee_power_and_link_conf;
}

}  // namespace

/*******************************************************************************
**
** Function:        getNative
**
** Description:     Get native data
**
** Returns:         Native data structure.
**
*******************************************************************************/
nfc_jni_native_data* getNative(JNIEnv* e, jobject o) {
  static struct nfc_jni_native_data* sCachedNat = NULL;
  if (e) {
    sCachedNat = nfc_jni_get_nat(e, o);
  }
  return sCachedNat;
}

/*******************************************************************************
**
** Function:        handleRfDiscoveryEvent
**
** Description:     Handle RF-discovery events from the stack.
**                  discoveredDevice: Discovered device.
**
** Returns:         None
**
*******************************************************************************/
static void handleRfDiscoveryEvent(tNFC_RESULT_DEVT* discoveredDevice) {
  NfcTag& natTag = NfcTag::getInstance();

  LOG(DEBUG) << StringPrintf("%s: ", __func__);

  if (gIsSelectingRfInterface && !natTag.getMultiProtocolTagSupport()) {
    if (discoveredDevice->more == NCI_DISCOVER_NTF_MORE) return;
    LOG(WARNING) << StringPrintf(
        "%s: reselecting, mismatch in nb of detected interfaces, "
        "restarting discovery",
        __func__);
    nativeNfcTag_doConnectStatus(false);
    natTag.setReselect(false);
    NFA_Deactivate(FALSE);
  }
  if (discoveredDevice->protocol != NFA_PROTOCOL_NFC_DEP) {
    natTag.setNumDiscNtf(natTag.getNumDiscNtf() + 1);
  }
  if (discoveredDevice->more == NCI_DISCOVER_NTF_MORE) {
    // there is more discovery notification coming
    return;
  }

  if (natTag.getNumDiscNtf() > 1) {
    natTag.setMultiProtocolTagSupport(true);
  }

  natTag.setNumDiscNtf(natTag.getNumDiscNtf() - 1);
  // select the first of multiple tags that is discovered
  natTag.selectFirstTag();
}

/*******************************************************************************
**
** Function:        nfaConnectionCallback
**
** Description:     Receive connection-related events from stack.
**                  connEvent: Event code.
**                  eventData: Event data.
**
** Returns:         None
**
*******************************************************************************/
static void nfaConnectionCallback(uint8_t connEvent,
                                  tNFA_CONN_EVT_DATA* eventData) {
  tNFA_STATUS status = NFA_STATUS_FAILED;

  switch (connEvent) {
    case NFA_LISTEN_ENABLED_EVT:  // whether listening successfully started
    {
      LOG(DEBUG) << StringPrintf("%s: NFA_LISTEN_ENABLED_EVT:status= %u",
                                 __func__, eventData->status);

      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      sNfaEnableDisablePollingEvent.notifyOne();
    } break;

    case NFA_LISTEN_DISABLED_EVT:
      LOG(DEBUG) << StringPrintf("%s: NFA_LISTEN_DISABLED_EVT:status= %u",
                                 __func__, eventData->status);
      break;

    case NFA_POLL_ENABLED_EVT:  // whether polling successfully started
    {
      LOG(DEBUG) << StringPrintf("%s: NFA_POLL_ENABLED_EVT: status = %u",
                                 __func__, eventData->status);

      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      sNfaEnableDisablePollingEvent.notifyOne();
    } break;

    case NFA_POLL_DISABLED_EVT:  // Listening/Polling stopped
    {
      LOG(DEBUG) << StringPrintf("%s: NFA_POLL_DISABLED_EVT: status = %u",
                                 __func__, eventData->status);

      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      sNfaEnableDisablePollingEvent.notifyOne();
    } break;

    case NFA_RF_DISCOVERY_STARTED_EVT:  // RF Discovery started
    {
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_RF_DISCOVERY_STARTED_EVT: status = %u", __func__,
          eventData->status);
      {
        SyncEventGuard guard(sNfaEnableDisablePollingEvent);
        sNfaEnableDisablePollingEvent.notifyOne();
      }
      struct nfc_jni_native_data* nat = getNative(NULL, NULL);
      if (!nat) {
        LOG(ERROR) << StringPrintf("%s: cached nat is null", __func__);
        return;
      }
      JNIEnv* e = NULL;
      ScopedAttach attach(nat->vm, &e);
      if (e == NULL) {
        LOG(ERROR) << StringPrintf("%s: jni env is null", __func__);
        return;
      }
      e->CallVoidMethod(nat->manager,
                        android::gCachedNfcManagerNotifyRfDiscoveryEvent,
                        JNI_TRUE);
    } break;

    case NFA_RF_DISCOVERY_STOPPED_EVT:  // RF Discovery stopped event
    {
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_RF_DISCOVERY_STOPPED_EVT: status = %u", __func__,
          eventData->status);

      gActivated = false;
      {
        SyncEventGuard guard(sNfaEnableDisablePollingEvent);
        sNfaEnableDisablePollingEvent.notifyOne();
      }
      struct nfc_jni_native_data* nat = getNative(NULL, NULL);
      if (!nat) {
        LOG(ERROR) << StringPrintf("%s: cached nat is null", __func__);
        return;
      }
      JNIEnv* e = NULL;
      ScopedAttach attach(nat->vm, &e);
      if (e == NULL) {
        LOG(ERROR) << StringPrintf("%s: jni env is null", __func__);
        return;
      }
      e->CallVoidMethod(nat->manager,
                        android::gCachedNfcManagerNotifyRfDiscoveryEvent,
                        JNI_FALSE);
    } break;

    case NFA_DISC_RESULT_EVT:  // NFC link/protocol discovery notificaiton
      status = eventData->disc_result.status;
      LOG(DEBUG) << StringPrintf("%s: NFA_DISC_RESULT_EVT: status = %d",
                                 __func__, status);
      if (status != NFA_STATUS_OK) {
        NfcTag::getInstance().setNumDiscNtf(0);
        LOG(ERROR) << StringPrintf("%s: NFA_DISC_RESULT_EVT error: status = %d",
                                   __func__, status);
      } else {
        NfcTag::getInstance().connectionEventHandler(connEvent, eventData);
        handleRfDiscoveryEvent(&eventData->disc_result.discovery_ntf);
      }
      break;

    case NFA_SELECT_RESULT_EVT:  // NFC link/protocol discovery select response
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_SELECT_RESULT_EVT: status = %d, gIsSelectingRfInterface = "
          "%d, "
          "sIsDisabling=%d",
          __func__, eventData->status, gIsSelectingRfInterface, sIsDisabling);

      if (sIsDisabling) break;

      if (eventData->status != NFA_STATUS_OK) {
        if (gIsSelectingRfInterface) {
          nativeNfcTag_doConnectStatus(false);
          NfcTag::getInstance().selectCompleteStatus(false);
        }

        LOG(ERROR) << StringPrintf(
            "%s: NFA_SELECT_RESULT_EVT error: status = %d", __func__,
            eventData->status);
        if (NfcTag::getInstance().retrySelect() == NFA_STATUS_OK) {
          break;
        }
        NFA_Deactivate(FALSE);
      }
      break;

    case NFA_DEACTIVATE_FAIL_EVT:
      LOG(DEBUG) << StringPrintf("%s: NFA_DEACTIVATE_FAIL_EVT: status = %d",
                                 __func__, eventData->status);
      {
        nativeNfcTag_doDeactivateStatus(eventData->status);
        SyncEventGuard g(gDeactivatedEvent);
        gDeactivatedEvent.notifyOne();
      }
      break;

    case NFA_ACTIVATED_EVT:  // NFC link/protocol activated
    {
      // Check whether this is a proprietary rf interface to be ignored.
      // If so shouldn't be processed by libnfc as prop module handles
      // the processing. Otherwise continue normal processing.
      if (NfcConfig::hasKey(NAME_PROP_RF_IFACE_LIST)) {
        std::vector<uint8_t> ignorePropIntfs =
            NfcConfig::getBytes(NAME_PROP_RF_IFACE_LIST);
        if (std::find(ignorePropIntfs.begin(), ignorePropIntfs.end(),
                      eventData->activated.activate_ntf.intf_param.type) !=
            ignorePropIntfs.end()) {
          LOG(INFO) << StringPrintf(
              "%s: NFA_ACTIVATED_EVT: Prop Rf iface %02X found in ignore list, "
              "returning",
              __func__, eventData->activated.activate_ntf.intf_param.type);
          break;
        }
      }
      bool notListen = !isListenMode(eventData->activated);
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_ACTIVATED_EVT: gIsSelectingRfInterface=%d, sIsDisabling=%d",
          __func__, gIsSelectingRfInterface, sIsDisabling);
      uint8_t activatedProtocol =
          (tNFA_INTF_TYPE)eventData->activated.activate_ntf.protocol;
      uint8_t activatedMode =
          eventData->activated.activate_ntf.rf_tech_param.mode;
      gTagJustActivated = true;
      NfcTag::getInstance().selectCompleteStatus(true);
      if (NFC_PROTOCOL_T5T == activatedProtocol &&
          NfcTag::getInstance().getNumDiscNtf()) {
        /* T5T doesn't support multiproto detection logic */
        NfcTag::getInstance().setNumDiscNtf(0);
      }
      NfcTag::getInstance().clearSelectRetryCount();
      if ((eventData->activated.activate_ntf.protocol !=
           NFA_PROTOCOL_NFC_DEP) &&
          (!isListenMode(eventData->activated))) {
        nativeNfcTag_setRfInterface(
            (tNFA_INTF_TYPE)eventData->activated.activate_ntf.intf_param.type);
        nativeNfcTag_setActivatedRfProtocol(activatedProtocol);
        nativeNfcTag_setActivatedRfMode(activatedMode);
      }
      NfcTag::getInstance().setActive(notListen);
      if (sIsDisabling || !sIsNfaEnabled) break;
      gActivated = true;

      if (notListen) {
        updateNfcID0Param(
            eventData->activated.activate_ntf.rf_tech_param.param.pb.nfcid0);
        NfcTag::getInstance().setActivationState();
        if (gIsSelectingRfInterface) {
          nativeNfcTag_doConnectStatus(true);
          break;
        }

        nativeNfcTag_resetPresenceCheck();
        int prevScreenState = sPrevScreenStateMask & NFA_SCREEN_STATE_MASK;
        if (!isListenMode(eventData->activated) &&
            (prevScreenState == NFA_SCREEN_STATE_OFF_LOCKED ||
             prevScreenState == NFA_SCREEN_STATE_OFF_UNLOCKED)) {
          if (!sIsAlwaysPolling) {
            NFA_Deactivate(FALSE);
          }
        }

        NfcTag::getInstance().connectionEventHandler(connEvent, eventData);
        if (NfcTag::getInstance().getNumDiscNtf()) {
          /*If its multiprotocol tag, deactivate tag with current selected
          protocol to sleep . Select tag with next supported protocol after
          deactivation event is received*/
          if (((tNFA_INTF_TYPE)eventData->activated.activate_ntf.intf_param
                   .type == NFA_INTERFACE_FRAME)) {
            uint8_t RW_TAG_SLP_REQ[] = {0x50, 0x00};
            SyncEvent waitSome;
            SyncEventGuard g(waitSome);
            NFA_SendRawFrame(RW_TAG_SLP_REQ, sizeof(RW_TAG_SLP_REQ), 0);
            waitSome.wait(4);
          }
          NFA_Deactivate(true);
        }

      } else {
        // If it activated in
        // listen mode then it is likely for an SE transaction.
        // Send the RF Event.
        sSeRfActive = true;
        struct nfc_jni_native_data* nat = getNative(NULL, NULL);
        if (!nat) {
          LOG(ERROR) << StringPrintf("%s: cached nat is null", __func__);
          return;
        }
        JNIEnv* e = NULL;
        ScopedAttach attach(nat->vm, &e);
        if (e == NULL) {
          LOG(ERROR) << __func__ << ": jni env is null";
          return;
        }
        e->CallVoidMethod(nat->manager,
                          android::gCachedNfcManagerNotifyEeListenActivated,
                          JNI_TRUE);
      }
    } break;
    case NFA_DEACTIVATED_EVT:  // NFC link/protocol deactivated
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_DEACTIVATED_EVT   Type=%u, gIsTagDeactivating=%d, "
          "gIsSelectingRfInterface=%d",
          __func__, eventData->deactivated.type, gIsTagDeactivating,
          gIsSelectingRfInterface);
      NfcTag::getInstance().setDeactivationState(eventData->deactivated);

      if (eventData->deactivated.type != NFA_DEACTIVATE_TYPE_SLEEP) {
        {
          SyncEventGuard g(gDeactivatedEvent);
          gActivated = false;  // guard this variable from multi-threaded access
          gDeactivatedEvent.notifyOne();
        }
        nativeNfcTag_resetPresenceCheck();
        NfcTag::getInstance().connectionEventHandler(connEvent, eventData);
        nativeNfcTag_abortWaits();
        NfcTag::getInstance().abort();
      } else if (gIsTagDeactivating) {
        NfcTag::getInstance().setActive(false);
        nativeNfcTag_doDeactivateStatus(0);
      } else if (!sIsDisabling) {
        NfcTag::getInstance().selectNextTagIfExists();
      }

      // If RF is activated for what we think is a Secure Element transaction
      // and it is deactivated to either IDLE or DISCOVERY mode, notify w/event.
      if ((eventData->deactivated.type == NFA_DEACTIVATE_TYPE_IDLE) ||
          (eventData->deactivated.type == NFA_DEACTIVATE_TYPE_DISCOVERY)) {
        if (sSeRfActive) {
          sSeRfActive = false;
          struct nfc_jni_native_data* nat = getNative(NULL, NULL);
          if (!nat) {
            LOG(ERROR) << StringPrintf("%s: cached nat is null", __func__);
            return;
          }
          JNIEnv* e = NULL;
          ScopedAttach attach(nat->vm, &e);
          if (e == NULL) {
            LOG(ERROR) << __func__ << ": jni env is null";
            return;
          }
          e->CallVoidMethod(nat->manager,
                            android::gCachedNfcManagerNotifyEeListenActivated,
                            JNI_FALSE);
        }
      }

      break;

    case NFA_DETECT_REMOVAL_STARTED_EVT: {  // whether EP Removal Detection
                                            // successfully started
      LOG(DEBUG) << StringPrintf(
          "%s:  NFA_DETECT_REMOVAL_STARTED_EVT: status = %d", __func__, status);

      sIsEpDetectStarted = eventData->status == NFA_STATUS_OK;
      SyncEventGuard guard(gNfaRemoveEpEvent);
      gNfaRemoveEpEvent.notifyOne();
    } break;

    case NFA_DETECT_REMOVAL_RESULT_EVT: {  // Removal Detection complete
      LOG(DEBUG) << StringPrintf(
          "%s:  NFA_DETECT_REMOVAL_RESULT_EVT: status = %d, reason = %d",
          __func__, status, eventData->removal_detect.reason);

      /* Return REMOVAL_DETECTION deactivation reason to service */
      {
        struct nfc_jni_native_data* nat = getNative(NULL, NULL);
        if (!nat) {
          LOG(ERROR) << StringPrintf("%s: cached nat is null", __func__);
          return;
        }
        JNIEnv* e = NULL;
        ScopedAttach attach(nat->vm, &e);
        if (e == NULL) {
          LOG(ERROR) << StringPrintf("%s:  jni env is null", __func__);
          return;
        }
        e->CallVoidMethod(nat->manager,
                          android::gCachedNfcManagerNotifyEndpointRemoved,
                          (int)eventData->removal_detect.reason);
        if (e->ExceptionCheck()) {
          e->ExceptionClear();
          LOG(ERROR) << StringPrintf("%s: fail notify", __func__);
        }
      }
    } break;

    case NFA_TLV_DETECT_EVT:  // TLV Detection complete
      status = eventData->tlv_detect.status;
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_TLV_DETECT_EVT: status = %d, protocol = %d, num_tlvs = %d, "
          "num_bytes = %d",
          __func__, status, eventData->tlv_detect.protocol,
          eventData->tlv_detect.num_tlvs, eventData->tlv_detect.num_bytes);
      if (status != NFA_STATUS_OK) {
        LOG(ERROR) << StringPrintf("%s: NFA_TLV_DETECT_EVT error: status = %d",
                                   __func__, status);
      }
      break;

    case NFA_NDEF_DETECT_EVT:  // NDEF Detection complete;
      // if status is failure, it means the tag does not contain any or valid
      // NDEF data;  pass the failure status to the NFC Service;
      status = eventData->ndef_detect.status;
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_NDEF_DETECT_EVT: status = 0x%X, protocol = %u, "
          "max_size = %u, cur_size = %u, flags = 0x%X",
          __func__, status, eventData->ndef_detect.protocol,
          eventData->ndef_detect.max_size, eventData->ndef_detect.cur_size,
          eventData->ndef_detect.flags);
      NfcTag::getInstance().connectionEventHandler(connEvent, eventData);
      nativeNfcTag_doCheckNdefResult(status, eventData->ndef_detect.max_size,
                                     eventData->ndef_detect.cur_size,
                                     eventData->ndef_detect.flags);
      break;

    case NFA_DATA_EVT:  // Data message received (for non-NDEF reads)
      LOG(DEBUG) << StringPrintf("%s: NFA_DATA_EVT: status = 0x%X, len = %d",
                                 __func__, eventData->status,
                                 eventData->data.len);
      nativeNfcTag_doTransceiveStatus(eventData->status, eventData->data.p_data,
                                      eventData->data.len);
      break;
    case NFA_RW_INTF_ERROR_EVT:
      LOG(DEBUG) << StringPrintf("%s: NFC_RW_INTF_ERROR_EVT", __func__);
      nativeNfcTag_notifyRfTimeout();
      nativeNfcTag_doReadCompleted(NFA_STATUS_TIMEOUT);
      break;
    case NFA_SELECT_CPLT_EVT:  // Select completed
      status = eventData->status;
      LOG(DEBUG) << StringPrintf("%s: NFA_SELECT_CPLT_EVT: status = %d",
                                 __func__, status);
      if (status != NFA_STATUS_OK) {
        LOG(ERROR) << StringPrintf("%s: NFA_SELECT_CPLT_EVT error: status = %d",
                                   __func__, status);
      }
      break;

    case NFA_READ_CPLT_EVT:  // NDEF-read or tag-specific-read completed
      LOG(DEBUG) << StringPrintf("%s: NFA_READ_CPLT_EVT: status = 0x%X",
                                 __func__, eventData->status);
      nativeNfcTag_doReadCompleted(eventData->status);
      NfcTag::getInstance().connectionEventHandler(connEvent, eventData);
      break;

    case NFA_WRITE_CPLT_EVT:  // Write completed
      LOG(DEBUG) << StringPrintf("%s: NFA_WRITE_CPLT_EVT: status = %d",
                                 __func__, eventData->status);
      nativeNfcTag_doWriteStatus(eventData->status == NFA_STATUS_OK);
      break;

    case NFA_SET_TAG_RO_EVT:  // Tag set as Read only
      LOG(DEBUG) << StringPrintf("%s: NFA_SET_TAG_RO_EVT: status = %d",
                                 __func__, eventData->status);
      nativeNfcTag_doMakeReadonlyResult(eventData->status);
      break;

    case NFA_CE_NDEF_WRITE_START_EVT:  // NDEF write started
      LOG(DEBUG) << StringPrintf("%s: NFA_CE_NDEF_WRITE_START_EVT: status=%d",
                                 __func__, eventData->status);

      if (eventData->status != NFA_STATUS_OK)
        LOG(ERROR) << StringPrintf(
            "%s: NFA_CE_NDEF_WRITE_START_EVT error: status = %d", __func__,
            eventData->status);
      break;

    case NFA_CE_NDEF_WRITE_CPLT_EVT:  // NDEF write completed
      LOG(DEBUG) << StringPrintf("%s: FA_CE_NDEF_WRITE_CPLT_EVT: len = %u",
                                 __func__, eventData->ndef_write_cplt.len);
      break;

    case NFA_PRESENCE_CHECK_EVT:
      LOG(DEBUG) << StringPrintf("%s: NFA_PRESENCE_CHECK_EVT", __func__);
      nativeNfcTag_doPresenceCheckResult(eventData->status);
      break;
    case NFA_FORMAT_CPLT_EVT:
      LOG(DEBUG) << StringPrintf("%s: NFA_FORMAT_CPLT_EVT: status=0x%X",
                                 __func__, eventData->status);
      nativeNfcTag_formatStatus(eventData->status == NFA_STATUS_OK);
      break;

    case NFA_I93_CMD_CPLT_EVT:
      LOG(DEBUG) << StringPrintf("%s: NFA_I93_CMD_CPLT_EVT: status=0x%X",
                                 __func__, eventData->status);
      break;

    case NFA_CE_UICC_LISTEN_CONFIGURED_EVT:
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_CE_UICC_LISTEN_CONFIGURED_EVT : status=0x%X", __func__,
          eventData->status);
      break;
    case NFA_T4TNFCEE_EVT:
    case NFA_T4TNFCEE_READ_CPLT_EVT:
    case NFA_T4TNFCEE_WRITE_CPLT_EVT:
    case NFA_T4TNFCEE_CLEAR_CPLT_EVT:
    case NFA_T4TNFCEE_READ_CC_DATA_CPLT_EVT:
      NativeT4tNfcee::getInstance().eventHandler(connEvent, eventData);
      break;

    default:
      LOG(DEBUG) << StringPrintf("%s: unknown event (%d) ????", __func__,
                                 connEvent);
      break;
  }
}

/*******************************************************************************
**
** Function:        nfcManager_initNativeStruc
**
** Description:     Initialize variables.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean nfcManager_initNativeStruc(JNIEnv* e, jobject o) {
  if (sIsShuttingDown) return false;
  initializeGlobalDebugEnabledFlag();
  initializeRecoveryOption();
  initializeNfceePowerAndLinkConf();
  initializeDisableAlwaysOnNfceePowerAndLinkConf();
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);

  nfc_jni_native_data* nat =
      (nfc_jni_native_data*)malloc(sizeof(struct nfc_jni_native_data));
  if (nat == NULL) {
    LOG(ERROR) << StringPrintf("%s: fail allocate native data", __func__);
    return JNI_FALSE;
  }

  memset(nat, 0, sizeof(*nat));
  e->GetJavaVM(&(nat->vm));
  nat->env_version = e->GetVersion();
  nat->manager = e->NewGlobalRef(o);

  ScopedLocalRef<jclass> cls(e, e->GetObjectClass(o));
  jfieldID f = e->GetFieldID(cls.get(), "mNative", "J");
  e->SetLongField(o, f, (jlong)nat);

  /* Initialize native cached references */
  gCachedNfcManagerNotifyNdefMessageListeners =
      e->GetMethodID(cls.get(), "notifyNdefMessageListeners",
                     "(Lcom/android/nfc/dhimpl/NativeNfcTag;)V");

  gCachedNfcManagerNotifyHostEmuActivated =
      e->GetMethodID(cls.get(), "notifyHostEmuActivated", "(I)V");

  gCachedNfcManagerNotifyHostEmuData =
      e->GetMethodID(cls.get(), "notifyHostEmuData", "(I[B)V");

  gCachedNfcManagerNotifyHostEmuDeactivated =
      e->GetMethodID(cls.get(), "notifyHostEmuDeactivated", "(I)V");

  gCachedNfcManagerNotifyRfFieldActivated =
      e->GetMethodID(cls.get(), "notifyRfFieldActivated", "()V");
  gCachedNfcManagerNotifyRfFieldDeactivated =
      e->GetMethodID(cls.get(), "notifyRfFieldDeactivated", "()V");

  gCachedNfcManagerNotifyTransactionListeners = e->GetMethodID(
      cls.get(), "notifyTransactionListeners", "([B[BLjava/lang/String;)V");

  gCachedNfcManagerNotifyEeUpdated =
      e->GetMethodID(cls.get(), "notifyEeUpdated", "()V");

  gCachedNfcManagerNotifyHwErrorReported =
      e->GetMethodID(cls.get(), "notifyHwErrorReported", "()V");

  gCachedNfcManagerNotifyPollingLoopFrame =
      e->GetMethodID(cls.get(), "notifyPollingLoopFrame", "(I[B)V");

  gCachedNfcManagerNotifyVendorSpecificEvent =
      e->GetMethodID(cls.get(), "notifyVendorSpecificEvent", "(II[B)V");

  gCachedNfcManagerNotifyWlcStopped =
      e->GetMethodID(cls.get(), "notifyWlcStopped", "(I)V");

  gCachedNfcManagerNotifyTagDiscovered =
      e->GetMethodID(cls.get(), "notifyTagDiscovered", "(Z)V");

  gCachedNfcManagerNotifyCommandTimeout =
      e->GetMethodID(cls.get(), "notifyCommandTimeout", "()V");

  gCachedNfcManagerNotifyObserveModeChanged =
      e->GetMethodID(cls.get(), "notifyObserveModeChanged", "(Z)V");

  gCachedNfcManagerNotifyRfDiscoveryEvent =
      e->GetMethodID(cls.get(), "notifyRFDiscoveryEvent", "(Z)V");

  gCachedNfcManagerNotifyEeListenActivated =
      e->GetMethodID(cls.get(), "notifyEeListenActivated", "(Z)V");

  gCachedNfcManagerNotifyEeAidSelected = e->GetMethodID(
      cls.get(), "notifyEeAidSelected", "([BLjava/lang/String;)V");

  gCachedNfcManagerNotifyEeProtocolSelected = e->GetMethodID(
      cls.get(), "notifyEeProtocolSelected", "(ILjava/lang/String;)V");

  gCachedNfcManagerNotifyEeTechSelected = e->GetMethodID(
      cls.get(), "notifyEeTechSelected", "(ILjava/lang/String;)V");

  gCachedNfcManagerNotifyEndpointRemoved =
      e->GetMethodID(cls.get(), "notifyEndpointRemoved", "(I)V");

  gCachedNfcManagerOnRestartRfDiscovery =
      e->GetMethodID(cls.get(), "onRestartRfDiscovery", "()V");

  gCachedNfcManagerOnObserveModeDisabledInFirmware =
      e->GetMethodID(cls.get(), "onObserveModeDisabledInFirmware", "(I[B)V");

  gCachedNfcManagerOnObserveModeEnabledInFirmware =
      e->GetMethodID(cls.get(), "onObserveModeEnabledInFirmware", "()V");

  if (nfc_jni_cache_object(e, gNativeNfcTagClassName, &(nat->cached_NfcTag)) ==
      -1) {
    LOG(ERROR) << StringPrintf("%s: fail cache NativeNfcTag", __func__);
    return JNI_FALSE;
  }

  // Cache the reference to the manager
  (void)getNative(e,o);

  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
  return JNI_TRUE;
}

/*******************************************************************************
**
** Function:        nfaDeviceManagementCallback
**
** Description:     Receive device management events from stack.
**                  dmEvent: Device-management event ID.
**                  eventData: Data associated with event ID.
**
** Returns:         None
**
*******************************************************************************/
void nfaDeviceManagementCallback(uint8_t dmEvent,
                                 tNFA_DM_CBACK_DATA* eventData) {
  LOG(DEBUG) << StringPrintf("%s: enter; event=0x%X", __func__, dmEvent);

  switch (dmEvent) {
    case NFA_DM_ENABLE_EVT: /* Result of NFA_Enable */
    {
      SyncEventGuard guard(sNfaEnableEvent);
      LOG(DEBUG) << StringPrintf("%s: NFA_DM_ENABLE_EVT; status=0x%X", __func__,
                                 eventData->status);
      sIsNfaEnabled = eventData->status == NFA_STATUS_OK;
      sIsDisabling = false;
      sNfaEnableEvent.notifyOne();
    } break;

    case NFA_DM_DISABLE_EVT: /* Result of NFA_Disable */
    {
      SyncEventGuard guard(sNfaDisableEvent);
      LOG(DEBUG) << StringPrintf("%s: NFA_DM_DISABLE_EVT", __func__);
      sIsNfaEnabled = false;
      sIsDisabling = false;
      sNfaDisableEvent.notifyOne();
    } break;

    case NFA_DM_SET_CONFIG_EVT:  // result of NFA_SetConfig
      LOG(DEBUG) << StringPrintf("%s: NFA_DM_SET_CONFIG_EVT", __func__);
      {
        SyncEventGuard guard(gNfaSetConfigEvent);
        gNfaSetConfigEvent.notifyOne();
      }
      break;

    case NFA_DM_GET_CONFIG_EVT: /* Result of NFA_GetConfig */
      LOG(DEBUG) << StringPrintf("%s: NFA_DM_GET_CONFIG_EVT", __func__);
      {
        SyncEventGuard guard(gNfaGetConfigEvent);
        if (eventData->status == NFA_STATUS_OK &&
            eventData->get_config.tlv_size <= sizeof(gConfig)) {
          gCurrentConfigLen = eventData->get_config.tlv_size;
          memcpy(gConfig, eventData->get_config.param_tlvs,
                 eventData->get_config.tlv_size);
        } else {
          LOG(ERROR) << StringPrintf("%s: NFA_DM_GET_CONFIG failed", __func__);
          gCurrentConfigLen = 0;
        }
        gNfaGetConfigEvent.notifyOne();
      }
      break;

    case NFA_DM_RF_FIELD_EVT:
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_DM_RF_FIELD_EVT; status=0x%X; field status=%u", __func__,
          eventData->rf_field.status, eventData->rf_field.rf_field_status);
      if (eventData->rf_field.status == NFA_STATUS_OK) {
        struct nfc_jni_native_data* nat = getNative(NULL, NULL);
        if (!nat) {
          LOG(ERROR) << StringPrintf("%s: cached nat is null", __func__);
          return;
        }
        JNIEnv* e = NULL;
        ScopedAttach attach(nat->vm, &e);
        if (e == NULL) {
          LOG(ERROR) << StringPrintf("%s: jni env is null", __func__);
          return;
        }
        if (eventData->rf_field.rf_field_status == NFA_DM_RF_FIELD_ON)
          e->CallVoidMethod(nat->manager,
                            android::gCachedNfcManagerNotifyRfFieldActivated);
        else
          e->CallVoidMethod(nat->manager,
                            android::gCachedNfcManagerNotifyRfFieldDeactivated);
      }
      break;

    case NFA_DM_NFCC_TRANSPORT_ERR_EVT:
    case NFA_DM_NFCC_TIMEOUT_EVT: {
      if (dmEvent == NFA_DM_NFCC_TIMEOUT_EVT)
        LOG(ERROR) << StringPrintf("%s: NFA_DM_NFCC_TIMEOUT_EVT; abort",
                                   __func__);
      else if (dmEvent == NFA_DM_NFCC_TRANSPORT_ERR_EVT)
        LOG(ERROR) << StringPrintf("%s: NFA_DM_NFCC_TRANSPORT_ERR_EVT; abort",
                                   __func__);

      struct nfc_jni_native_data* nat = getNative(NULL, NULL);
      if (recovery_option && nat != NULL) {
        JNIEnv* e = NULL;
        ScopedAttach attach(nat->vm, &e);
        if (e == NULL) {
          LOG(ERROR) << StringPrintf("%s: jni env is null", __func__);
          return;
        }
        LOG(ERROR) << StringPrintf("%s: toggle NFC state to recovery nfc",
                                   __func__);
        sIsRecovering = true;
        {
          LOG(DEBUG) << StringPrintf(
              "%s: aborting  sNfaEnableDisablePollingEvent", __func__);
          SyncEventGuard guard(sNfaEnableDisablePollingEvent);
          sNfaEnableDisablePollingEvent.notifyOne();
        }
        {
          LOG(DEBUG) << StringPrintf("%s: aborting  sNfaEnableEvent", __func__);
          SyncEventGuard guard(sNfaEnableEvent);
          sNfaEnableEvent.notifyOne();
        }
        {
          LOG(DEBUG) << StringPrintf("%s: aborting  sNfaDisableEvent",
                                     __func__);
          SyncEventGuard guard(sNfaDisableEvent);
          sNfaDisableEvent.notifyOne();
        }
        {
          LOG(DEBUG) << StringPrintf("%s: aborting  sNfaSetPowerSubState",
                                     __func__);
          SyncEventGuard guard(sNfaSetPowerSubState);
          sNfaSetPowerSubState.notifyOne();
        }
        {
          LOG(DEBUG) << StringPrintf("%s: aborting gNfaSetConfigEvent",
                                     __func__);
          SyncEventGuard guard(gNfaSetConfigEvent);
          gNfaSetConfigEvent.notifyOne();
        }
        {
          LOG(DEBUG) << StringPrintf("%s: aborting gNfaGetConfigEvent",
                                     __func__);
          SyncEventGuard guard(gNfaGetConfigEvent);
          gNfaGetConfigEvent.notifyOne();
        }
        {
          LOG(DEBUG) << StringPrintf(
              "%s: aborting RoutingManager::getInstance().mEeUpdateEvent",
              __func__);
          SyncEventGuard guard(RoutingManager::getInstance().mEeUpdateEvent);
          RoutingManager::getInstance().mEeUpdateEvent.notifyOne();
        }
        {
          LOG(DEBUG) << StringPrintf(
              "%s: aborting RoutingManager::getInstance().mRoutingEvent",
              __func__);
          SyncEventGuard guard(RoutingManager::getInstance().mRoutingEvent);
          RoutingManager::getInstance().mRoutingEvent.notifyOne();
        }
        e->CallVoidMethod(nat->manager,
                          android::gCachedNfcManagerNotifyHwErrorReported);
      } else {
        nativeNfcTag_abortWaits();
        NfcTag::getInstance().abort();
        sAbortConnlessWait = true;
        {
          LOG(DEBUG) << StringPrintf(
              "%s: aborting  sNfaEnableDisablePollingEvent", __func__);
          SyncEventGuard guard(sNfaEnableDisablePollingEvent);
          sNfaEnableDisablePollingEvent.notifyOne();
        }
        {
          LOG(DEBUG) << StringPrintf("%s: aborting  sNfaEnableEvent", __func__);
          SyncEventGuard guard(sNfaEnableEvent);
          sNfaEnableEvent.notifyOne();
        }
        {
          LOG(DEBUG) << StringPrintf("%s: aborting  sNfaDisableEvent",
                                     __func__);
          SyncEventGuard guard(sNfaDisableEvent);
          sNfaDisableEvent.notifyOne();
        }
        {
          LOG(DEBUG) << StringPrintf("%s: aborting  gSendRawVsCmdEvent",
                                     __func__);
          SyncEventGuard guard(gSendRawVsCmdEvent);
          gSendRawVsCmdEvent.notifyOne();
        }
        sPollingEnabled = false;

        if (!sIsDisabling && sIsNfaEnabled) {
          if (gIsDtaEnabled == true) {
            LOG(DEBUG) << StringPrintf("%s: DTA; unset dta flag in core stack",
                                       __func__);
            NFA_DisableDtamode();
          }

          NFA_Disable(FALSE);
          sIsDisabling = true;
        } else {
          sIsNfaEnabled = false;
          sIsDisabling = false;
        }
        LOG(ERROR) << StringPrintf("%s: crash NFC service", __func__);
        if (nat != NULL) {
          JNIEnv* e = NULL;
          ScopedAttach attach(nat->vm, &e);
          if (e != NULL) {
            e->CallVoidMethod(nat->manager,
                              android::gCachedNfcManagerNotifyCommandTimeout);
          }
        }
        //////////////////////////////////////////////
        // crash the NFC service process so it can restart automatically
        abort();
        //////////////////////////////////////////////
      }
    } break;

    case NFA_DM_SET_POWER_SUB_STATE_EVT: {
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_DM_SET_POWER_SUB_STATE_EVT; status=0x%X", __FUNCTION__,
          eventData->power_sub_state.status);
      SyncEventGuard guard(sNfaSetPowerSubState);
      sNfaSetPowerSubState.notifyOne();
    } break;
    default:
      LOG(DEBUG) << StringPrintf("%s: unhandled event", __func__);
      break;
  }
}

/*******************************************************************************
**
** Function:        nfcManager_sendRawFrame
**
** Description:     Send a raw frame.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean nfcManager_sendRawFrame(JNIEnv* e, jobject, jbyteArray data) {
  if (sIsShuttingDown) return false;
  ScopedByteArrayRO bytes(e, data);
  uint8_t* buf =
      const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
  size_t bufLen = bytes.size();
  tNFA_STATUS status = NFA_SendRawFrame(buf, bufLen, 0);

  return (status == NFA_STATUS_OK);
}

/*******************************************************************************
**
** Function:        nfcManager_routeAid
**
** Description:     Route an AID to an EE
**                  e: JVM environment.
**                  aid: aid to be added to routing table.
**                  route: aid route location. i.e. DH/eSE/UICC
**                  aidInfo: prefix or suffix aid.
**
** Returns:         True if aid is accpted by NFA Layer.
**
*******************************************************************************/
static jboolean nfcManager_routeAid(JNIEnv* e, jobject, jbyteArray aid,
                                    jint route, jint aidInfo, jint power) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return false;
  uint8_t* buf;
  size_t bufLen;
  if (sIsDisabling || !sIsNfaEnabled) {
    return false;
  }

  if (aid == NULL) {
    buf = NULL;
    bufLen = 0;
    return RoutingManager::getInstance().addAidRouting(buf, bufLen, route,
                                                       aidInfo, power);
  }
  ScopedByteArrayRO bytes(e, aid);
  buf = const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
  bufLen = bytes.size();
  if (NfcConfig::hasKey(NAME_DEFAULT_NDEF_NFCEE_ROUTE)) {
    if (route == (int)NfcConfig::getUnsigned(NAME_DEFAULT_NDEF_NFCEE_ROUTE)) {
      NativeT4tNfcee::getInstance().checkAndUpdateT4TAid(buf,
                                                         (uint8_t*)&bufLen);
    }
  }
  return RoutingManager::getInstance().addAidRouting(buf, bufLen, route,
                                                     aidInfo, power);
}

/*******************************************************************************
**
** Function:        nfcManager_unrouteAid
**
** Description:     Remove a AID routing
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean nfcManager_unrouteAid(JNIEnv* e, jobject, jbyteArray aid) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return false;
  uint8_t* buf;
  size_t bufLen;
  if (sIsDisabling || !sIsNfaEnabled) {
    return false;
  }

  if (aid == NULL) {
    buf = NULL;
    bufLen = 0;
    return RoutingManager::getInstance().removeAidRouting(buf, bufLen);
  }
  ScopedByteArrayRO bytes(e, aid);
  buf = const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
  bufLen = bytes.size();
  return RoutingManager::getInstance().removeAidRouting(buf, bufLen);
}

/*******************************************************************************
**
** Function:        nfcManager_commitRouting
**
** Description:     Sends the AID routing table to the controller
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         NFA_STATUS_OK if successfully initiated
**                  NFA_STATUS_SEMANTIC_ERROR is update is currently in progress
**                  NFA_STATUS_FAILED otherwise
**
*******************************************************************************/
static jint nfcManager_commitRouting(JNIEnv* e, jobject) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return -1;
  if (sRfEnabled) {
    /*Update routing table only in Idle state.*/
    startRfDiscovery(false);
  }
  RoutingManager::getInstance().setEeTechRouteUpdateRequired();
  jint commitStatus = RoutingManager::getInstance().commitRouting();
  startRfDiscovery(true);
  return commitStatus;
}

void static nfaVSCallback(uint8_t event, uint16_t param_len, uint8_t* p_param) {
  switch (event & NCI_OID_MASK) {
    case NCI_MSG_PROP_ANDROID: {
      uint8_t android_sub_opcode = p_param[3];
      switch (android_sub_opcode) {
        case NCI_QUERY_ANDROID_PASSIVE_OBSERVE: {
          gObserveModeEnabled = p_param[5];
          LOG(INFO) << StringPrintf("%s: Query Observe mode state is %s",
                                    __func__,
                                    gObserveModeEnabled ? "TRUE" : "FALSE");
        }
          FALLTHROUGH_INTENDED;
        case NCI_ANDROID_SET_PASSIVE_OBSERVER_TECH:
        case NCI_ANDROID_PASSIVE_OBSERVE:
        case NCI_ANDROID_SET_TECH_A_POLLING_LOOP_ANNOTATION:
        case NCI_ANDROID_SET_PASSIVE_OBSERVER_EXIT_FRAME: {
          gVSCmdStatus = p_param[4];
          LOG(INFO) << StringPrintf(
              "%s: RSP status: %x to Android proprietary cmd %x", __func__,
              gVSCmdStatus, android_sub_opcode);
          SyncEventGuard guard(gNfaVsCommand);
          gNfaVsCommand.notifyOne();
        } break;
        case NCI_ANDROID_GET_CAPS: {
          gVSCmdStatus = p_param[4];
          SyncEventGuard guard(gNfaVsCommand);
          if (param_len > 8) {
            gCaps.assign(p_param + 8, p_param + param_len);
          }
          gNfaVsCommand.notifyOne();
        } break;
        case NCI_ANDROID_POLLING_FRAME_NTF: {
          struct nfc_jni_native_data* nat = getNative(NULL, NULL);
          if (!nat) {
            LOG(ERROR) << StringPrintf("%s: cached nat is null", __func__);
            return;
          }
          JNIEnv* e = NULL;
          ScopedAttach attach(nat->vm, &e);
          if (e == NULL) {
            LOG(ERROR) << StringPrintf("%s: jni env is null", __func__);
            return;
          }
          ScopedLocalRef<jobject> dataJavaArray(e, e->NewByteArray(param_len));
          if (dataJavaArray.get() == NULL) {
            LOG(ERROR) << __func__ << ": fail allocate array";
            return;
          }
          e->SetByteArrayRegion((jbyteArray)dataJavaArray.get(), 0, param_len,
                                (jbyte*)(p_param));
          if (e->ExceptionCheck()) {
            e->ExceptionClear();
            LOG(ERROR) << __func__ << ": failed to fill array";
            return;
          }
          e->CallVoidMethod(nat->manager,
                            android::gCachedNfcManagerNotifyPollingLoopFrame,
                            (jint)param_len, dataJavaArray.get());
        } break;
        case NCI_ANDROID_PASSIVE_OBSERVER_SUSPENDED_NTF: {
          LOG(INFO) << StringPrintf("%s: Observe mode suspended NTF received",
                                    __func__);
          gObserveModeEnabled = false;
          struct nfc_jni_native_data* nat = getNative(NULL, NULL);
          if (!nat) {
            LOG(ERROR) << StringPrintf("%s: cached nat is null", __func__);
            return;
          }
          JNIEnv* e = NULL;
          ScopedAttach attach(nat->vm, &e);
          if (e == NULL) {
            LOG(ERROR) << StringPrintf("%s: jni env is null", __func__);
            return;
          }
          if (param_len <= 2) {
            LOG(ERROR) << StringPrintf(
                "%s: Cannot parse exit frame from "
                "NCI_ANDROID_PASSIVE_OBSERVER_SUSPENDED_NTF",
                __func__);
            return;
          }
          jint exit_frame_type = (jint) p_param[4];
          uint16_t exit_frame_len = p_param[5];
          ScopedLocalRef<jobject> dataJavaArray(e, e->NewByteArray(exit_frame_len));
          if (dataJavaArray.get() == NULL) {
            LOG(ERROR) << StringPrintf("%s: fail allocate array", __func__);
            return;
          }
          if (exit_frame_len > 0) {
              e->SetByteArrayRegion((jbyteArray)dataJavaArray.get(), 0, exit_frame_len,
                                    (jbyte*)(p_param + 6));
              if (e->ExceptionCheck()) {
                  e->ExceptionClear();
                  LOG(ERROR)
                      << StringPrintf("%s: failed to fill array", __func__);
                  return;
              }
          }
          e->CallVoidMethod(nat->manager,
                            android::gCachedNfcManagerOnObserveModeDisabledInFirmware,
                            exit_frame_type, dataJavaArray.get());
          return;
        } break;
        case NCI_ANDROID_PASSIVE_OBSERVER_RESUMED_NTF: {
          LOG(INFO) << StringPrintf("%s: Observe mode resumed NTF received",
                                    __func__);
          gObserveModeEnabled = true;
          struct nfc_jni_native_data *nat = getNative(NULL, NULL);
          if (!nat) {
            LOG(ERROR) << StringPrintf("%s: cached nat is null", __func__);
            return;
          }
          JNIEnv *e = NULL;
          ScopedAttach attach(nat->vm, &e);
          if (e == NULL) {
            LOG(ERROR) << StringPrintf("%s: jni env is null", __func__);
            return;
          }
          e->CallVoidMethod(nat->manager,
                            android::gCachedNfcManagerOnObserveModeEnabledInFirmware);
          return;
        } break;
        case NCI_ANDROID_RESTART_RF_DISCOVERY_REQUEST_NTF: {
                struct nfc_jni_native_data* nat = getNative(NULL, NULL);
                if (!nat) {
                  LOG(ERROR)
                      << StringPrintf("%s: cached nat is null", __func__);
                  return;
                }
                JNIEnv* e = NULL;
                ScopedAttach attach(nat->vm, &e);
                if (e == NULL) {
                  LOG(ERROR) << StringPrintf("%s: jni env is null", __func__);
                  return;
                }
                e->CallVoidMethod(nat->manager,
                                  android::gCachedNfcManagerOnRestartRfDiscovery);
        } break;

        default:
          LOG(DEBUG) << StringPrintf("%s: Unknown Android sub opcode %x",
                                     __func__, android_sub_opcode);
      }
    } break;
    default: {
      if (sEnableVendorNciNotifications) {
        struct nfc_jni_native_data* nat = getNative(NULL, NULL);
        if (!nat) {
          LOG(ERROR) << StringPrintf("%s: cached nat is null", __FUNCTION__);
          return;
        }
        JNIEnv* e = NULL;
        ScopedAttach attach(nat->vm, &e);
        if (e == NULL) {
          LOG(ERROR) << StringPrintf("%s: jni env is null", __FUNCTION__);
          return;
        }
        ScopedLocalRef<jobject> dataJavaArray(e, e->NewByteArray(param_len));
        if (dataJavaArray.get() == NULL) {
          LOG(ERROR) << StringPrintf("%s: fail allocate array", __FUNCTION__);
          return;
        }
        e->SetByteArrayRegion((jbyteArray)dataJavaArray.get(), 0, param_len,
                              (jbyte*)(p_param));
        if (e->ExceptionCheck()) {
          e->ExceptionClear();
          LOG(ERROR) << StringPrintf("%s failed to fill array", __FUNCTION__);
          return;
        }
        e->CallVoidMethod(nat->manager,
                          android::gCachedNfcManagerNotifyVendorSpecificEvent,
                          (jint)event, (jint)param_len, dataJavaArray.get());
      }
    } break;
  }
}

static void nfcManager_injectNtf(JNIEnv* e, jobject, jbyteArray data) {
  if (sIsShuttingDown) return;
  ScopedByteArrayRO bytes(e, data);
  size_t bufLen = bytes.size();
  tNFC_HAL_EVT_MSG* p_msg;
  p_msg = (tNFC_HAL_EVT_MSG*)GKI_getbuf(sizeof(tNFC_HAL_EVT_MSG) + bufLen + 1);
  if (p_msg != NULL) {
    p_msg->hdr.len = bufLen + 3;
    p_msg->hdr.event = BT_EVT_TO_NFC_NCI;
    p_msg->hdr.offset = sizeof(tNFC_HAL_EVT_MSG) - 7;
    p_msg->hdr.layer_specific = 0;
    memcpy(((uint8_t*)p_msg) + sizeof(tNFC_HAL_EVT_MSG) + 1, bytes.get(),
           bufLen);
    GKI_send_msg(NFC_TASK, NFC_MBOX_ID, p_msg);
  }
}

static jboolean isObserveModeSupported(JNIEnv* e, jobject o) {
  if (sIsShuttingDown) return false;
  ScopedLocalRef<jclass> cls(e, e->GetObjectClass(o));
  jmethodID isSupported =
      e->GetMethodID(cls.get(), "isObserveModeSupported", "()Z");
  return e->CallBooleanMethod(o, isSupported);
}

static jboolean nfcManager_isObserveModeEnabled(JNIEnv* e, jobject o) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return false;
  if (isObserveModeSupported(e, o) == JNI_FALSE) {
    LOG(DEBUG) << StringPrintf(
        "%s: Observe mode not supported, returning false", __func__);
    return false;
  }

  uint8_t cmd[] = {NCI_QUERY_ANDROID_PASSIVE_OBSERVE};
  SyncEventGuard guard(gNfaVsCommand);
  tNFA_STATUS status =
      NFA_SendVsCommand(NCI_MSG_PROP_ANDROID, sizeof(cmd), cmd, nfaVSCallback);

  if (status == NFA_STATUS_OK) {
    if (!gNfaVsCommand.wait(1000)) {
      LOG(ERROR) << StringPrintf(
          "%s: Timed out waiting for a response to get observe mode ",
          __FUNCTION__);
      gVSCmdStatus = NFA_STATUS_FAILED;
    }
  } else {
    LOG(DEBUG) << StringPrintf("%s: Failed to get observe mode ", __FUNCTION__);
  }
  LOG(DEBUG) << StringPrintf(
      "%s: returning %s", __FUNCTION__,
      (gObserveModeEnabled != JNI_FALSE ? "TRUE" : "FALSE"));
  return gObserveModeEnabled;
}

static void nfaSendRawVsCmdCallback(uint8_t event, uint16_t param_len,
                                    uint8_t* p_param) {
  if (param_len == 5) {
    gVSCmdStatus = p_param[4];
  } else {
    gVSCmdStatus = NFA_STATUS_FAILED;
  }
  SyncEventGuard guard(gNfaVsCommand);
  gNfaVsCommand.notifyOne();
}

bool isObserveModeSupportedWithoutRfDeactivation(JNIEnv* e, jobject o) {
  ScopedLocalRef<jclass> cls(e, e->GetObjectClass(o));
  jmethodID isSupported = e->GetMethodID(
      cls.get(), "isObserveModeSupportedWithoutRfDeactivation", "()Z");
  return e->CallBooleanMethod(o, isSupported);
}

static jboolean nfcManager_setObserveMode(JNIEnv* e, jobject o,
                                          jboolean enable) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return false;
  if (isObserveModeSupported(e, o) == JNI_FALSE) {
    LOG(DEBUG) << StringPrintf(
        "%s: Observe mode not supported, returning false", __func__);
    return false;
  }

  if (isObserveModeSupportedWithoutRfDeactivation(e, o) == JNI_FALSE) {
    LOG(DEBUG) << StringPrintf(
        "%s: Observe mode requires RF off/on, returning false", __func__);
    return false;
  }

  if ((gObserveModeEnabled == enable) &&
      ((enable != JNI_FALSE) ==
       (nfcManager_isObserveModeEnabled(e, o) != JNI_FALSE))) {
    LOG(DEBUG) << StringPrintf(
        "%s: called with %s but it is already %s, returning early",
        __FUNCTION__, (enable != JNI_FALSE ? "TRUE" : "FALSE"),
        (gObserveModeEnabled != JNI_FALSE ? "TRUE" : "FALSE"));
    return true;
  }
  uint8_t cmd[] = {
      static_cast<uint8_t>(NCI_ANDROID_SET_PASSIVE_OBSERVER_TECH),
      static_cast<uint8_t>(
          enable != JNI_FALSE
              ? (NCI_ANDROID_PASSIVE_OBSERVE_PARAM_ENABLE_A |
                           NCI_ANDROID_PASSIVE_OBSERVE_PARAM_ENABLE_B)
              : NCI_ANDROID_PASSIVE_OBSERVE_PARAM_DISABLE)};
  {
    SyncEventGuard guard(gNfaVsCommand);
    tNFA_STATUS status = NFA_SendVsCommand(NCI_MSG_PROP_ANDROID, sizeof(cmd),
                                           cmd, nfaVSCallback);

    if (status == NFA_STATUS_OK) {
      if (!gNfaVsCommand.wait(1000)) {
        LOG(ERROR) << StringPrintf(
            "%s: Timed out waiting for a response to set observe mode ",
            __FUNCTION__);
        gVSCmdStatus = NFA_STATUS_FAILED;
      }
    } else {
      LOG(DEBUG) << StringPrintf("%s: Failed to set observe mode ",
                                 __FUNCTION__);
      gVSCmdStatus = NFA_STATUS_FAILED;
    }
  }

  if (gVSCmdStatus == NFA_STATUS_OK) {
    gObserveModeEnabled = enable;
  } else {
    gObserveModeEnabled = nfcManager_isObserveModeEnabled(e, o);
  }

  LOG(DEBUG) << StringPrintf(
      "%s: Set observe mode to %s with result %x, observe mode is now %s.",
      __FUNCTION__, (enable != JNI_FALSE ? "TRUE" : "FALSE"), gVSCmdStatus,
      (gObserveModeEnabled ? "enabled" : "disabled"));
  if (gObserveModeEnabled == enable) {
    e->CallVoidMethod(o, android::gCachedNfcManagerNotifyObserveModeChanged,
                      enable);
    return true;
  } else {
    return false;
  }
}

/*******************************************************************************
**
** Function:        nfcManager_doRegisterT3tIdentifier
**
** Description:     Registers LF_T3T_IDENTIFIER for NFC-F.
**                  e: JVM environment.
**                  o: Java object.
**                  t3tIdentifier: LF_T3T_IDENTIFIER value (10 or 18 bytes)
**
** Returns:         Handle retrieve from RoutingManager.
**
*******************************************************************************/
static jint nfcManager_doRegisterT3tIdentifier(JNIEnv* e, jobject,
                                               jbyteArray t3tIdentifier) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return -1;
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);

  ScopedByteArrayRO bytes(e, t3tIdentifier);
  uint8_t* buf =
      const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
  size_t bufLen = bytes.size();
  int handle = RoutingManager::getInstance().registerT3tIdentifier(buf, bufLen);

  LOG(DEBUG) << StringPrintf("%s: exit, handle=%d", __func__, handle);
  return handle;
}

/*******************************************************************************
**
** Function:        nfcManager_doDeregisterT3tIdentifier
**
** Description:     Deregisters LF_T3T_IDENTIFIER for NFC-F.
**                  e: JVM environment.
**                  o: Java object.
**                  handle: Handle retrieve from libnfc-nci.
**
** Returns:         None
**
*******************************************************************************/
static void nfcManager_doDeregisterT3tIdentifier(JNIEnv*, jobject,
                                                 jint handle) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return;
  LOG(DEBUG) << StringPrintf("%s: enter; handle=%d", __func__, handle);

  RoutingManager::getInstance().deregisterT3tIdentifier(handle);
  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
}

/*******************************************************************************
**
** Function:        nfcManager_getLfT3tMax
**
** Description:     Returns LF_T3T_MAX value.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         LF_T3T_MAX value.
**
*******************************************************************************/
static jint nfcManager_getLfT3tMax(JNIEnv*, jobject) {
  if (sIsShuttingDown) return -1;
  LOG(DEBUG) << StringPrintf("%s: LF_T3T_MAX=%d", __func__, sLfT3tMax);
  return sLfT3tMax;
}

/*******************************************************************************
**
** Function:        doPartialInit
**
** Description:     Partial Nfc initialization based on mode set
**	            ENABLE_MODE_TRANSPARENT : Minimum initialization to allow
**                                 NFCC transport
**	            ENABLE_MODE_EE : Minimum Initialization to allow card
**                                 emulation operation
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean doPartialInit() {
  if (sIsShuttingDown) return false;
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);
  tNFA_STATUS stat = NFA_STATUS_OK;

  NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
  theInstance.Initialize();  // start GKI, NCI task, NFC task

  {
    SyncEventGuard guard(sNfaEnableEvent);
    tHAL_NFC_ENTRY* halFuncEntries = theInstance.GetHalEntryFuncs();
    NFA_Partial_Init(halFuncEntries, gPartialInitMode);
    LOG(DEBUG) << StringPrintf("%s: register VS callbacks", __func__);
    NFA_RegVSCback(true, &nfaVSCallback);

    LOG(DEBUG) << StringPrintf("%s: calling enable", __func__);
    stat = NFA_Enable(nfaDeviceManagementCallback, nfaConnectionCallback);
    if (stat == NFA_STATUS_OK) {
      sNfaEnableEvent.wait();  // wait for NFA command to finish
    }
    NFA_SetNfccMode(ENABLE_MODE_DEFAULT);
  }
  if (stat == NFA_STATUS_OK) {
    // sIsNfaEnabled indicates whether stack started successfully
    if (sIsNfaEnabled) {
      NativeT4tNfcee::getInstance().initialize();
    }
  } else {
    LOG(ERROR) << StringPrintf("%s: fail enable; error=0x%X", __func__, stat);
    return JNI_FALSE;
  }
  // sIsNfaEnabled indicates whether stack started successfully
  if (!sIsNfaEnabled) {
    NFA_Disable(false /* ungraceful */);
    theInstance.Finalize();
    return JNI_FALSE;
  }
  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
  return JNI_TRUE;
}

/*******************************************************************************
**
** Function:        nfcManager_doInitialize
**
** Description:     Turn on NFC.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean nfcManager_doInitialize(JNIEnv* e, jobject o) {
  if (sIsShuttingDown) return false;
  initializeGlobalDebugEnabledFlag();
  tNFA_STATUS stat = NFA_STATUS_OK;
  sIsRecovering = false;
  uint8_t mHostListenTechMask;

  struct nfc_jni_native_data* nat = getNative(e, o);

  if (sIsNfaEnabled) {
    LOG(DEBUG) << StringPrintf("%s: already enabled", __func__);
    goto TheEnd;
  }
  if (gPartialInitMode != ENABLE_MODE_DEFAULT) {
    return doPartialInit();
  }

  {

    NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
    theInstance.Initialize();  // start GKI, NCI task, NFC task

    {
      SyncEventGuard guard(sNfaEnableEvent);
      tHAL_NFC_ENTRY* halFuncEntries = theInstance.GetHalEntryFuncs();

      NFA_Init(halFuncEntries);

      LOG(DEBUG) << StringPrintf("%s: register VS callbacks", __func__);
      NFA_RegVSCback(true, &nfaVSCallback);

      if (gIsDtaEnabled == true) {
        // Allows to set appl_dta_mode_flag
        LOG(DEBUG) << StringPrintf("%s: DTA; set dta flag in core stack",
                                   __func__);
        NFA_EnableDtamode((tNFA_eDtaModes)NFA_DTA_APPL_MODE);
      }

      stat = NFA_Enable(nfaDeviceManagementCallback, nfaConnectionCallback);
      if (stat == NFA_STATUS_OK) {
        sNfaEnableEvent.wait();  // wait for NFA command to finish
      }
    }

    if (stat == NFA_STATUS_OK) {
      // sIsNfaEnabled indicates whether stack started successfully
      if (sIsNfaEnabled) {
        sRoutingInitialized =
            RoutingManager::getInstance().initialize(getNative(e, o));
        nativeNfcTag_registerNdefTypeHandler();
        NfcTag::getInstance().initialize(getNative(e, o));
        HciEventManager::getInstance().initialize(getNative(e, o));
        NativeWlcManager::getInstance().initialize(getNative(e, o));
        NativeT4tNfcee::getInstance().initialize();

        /////////////////////////////////////////////////////////////////////////////////
        // Add extra configuration here (work-arounds, etc.)

        if (nat) {
          nat->tech_mask =
              NfcConfig::getUnsigned(NAME_POLLING_TECH_MASK, DEFAULT_TECH_MASK);
          LOG(DEBUG) << StringPrintf("%s: tag polling tech mask=0x%X", __func__,
                                     nat->tech_mask);

          // if this value exists, set polling interval.
          nat->discovery_duration = NfcConfig::getUnsigned(
              NAME_NFA_DM_DISC_DURATION_POLL, DEFAULT_DISCOVERY_DURATION);
          NFA_SetRfDiscoveryDuration(nat->discovery_duration);
        } else {
          LOG(ERROR) << StringPrintf("nat is null");
        }

        // get LF_T3T_MAX
        mHostListenTechMask = NfcConfig::getUnsigned(NAME_HOST_LISTEN_TECH_MASK,
                              NFA_TECHNOLOGY_MASK_A | NFA_TECHNOLOGY_MASK_F);
        if ((mHostListenTechMask & NFA_TECHNOLOGY_MASK_F) != 0)
        {
          SyncEventGuard guard(gNfaGetConfigEvent);
          tNFA_PMID configParam[1] = {NCI_PARAM_ID_LF_T3T_MAX};
          stat = NFA_GetConfig(1, configParam);
          if (stat == NFA_STATUS_OK) {
            gNfaGetConfigEvent.wait();
            if (gCurrentConfigLen >= 4 ||
                gConfig[1] == NCI_PARAM_ID_LF_T3T_MAX) {
              LOG(DEBUG) << StringPrintf("%s: lfT3tMax=%d", __func__,
                                         gConfig[3]);
              sLfT3tMax = gConfig[3];
            }
          }
        }

        sPrevScreenStateMask = NFA_SCREEN_STATE_UNKNOWN;

        // Do custom NFCA startup configuration.
        doStartupConfig();
#ifdef DTA_ENABLED
        NfcDta::getInstance().setNfccConfigParams();
#endif /* DTA_ENABLED */
        goto TheEnd;
      }
    }

    if (gIsDtaEnabled == true) {
      LOG(DEBUG) << StringPrintf("%s: DTA; unset dta flag in core stack",
                                 __func__);
      NFA_DisableDtamode();
    }

    LOG(ERROR) << StringPrintf("%s: fail nfa enable; error=0x%X", __func__,
                               stat);

    if (sIsNfaEnabled) {
      stat = NFA_Disable(FALSE /* ungraceful */);
    }

    theInstance.Finalize();
  }

TheEnd:
  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
  return sIsNfaEnabled ? JNI_TRUE : JNI_FALSE;
}

static void nfcManager_doSetPartialInitMode(JNIEnv*, jobject, jint mode) {
  if (sIsShuttingDown) return;
  gPartialInitMode = mode;
}

static void nfcManager_doEnableDtaMode(JNIEnv*, jobject) {
  if (sIsShuttingDown) return;
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);
  gIsDtaEnabled = true;
}

static void nfcManager_doDisableDtaMode(JNIEnv*, jobject) {
  if (sIsShuttingDown) return;
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);
  gIsDtaEnabled = false;
}

static void nfcManager_doFactoryReset(JNIEnv*, jobject) {
  if (sIsShuttingDown) return;
  NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
  theInstance.FactoryReset();
}

static void nfcManager_doShutdown(JNIEnv*, jobject) {
  if (sIsShuttingDown) return;
  NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
  NativeT4tNfcee::getInstance().onNfccShutdown();
  theInstance.DeviceShutdown();
  sIsShuttingDown = true;
}

static void nfcManager_configNfccConfigControl(bool flag) {
    // configure NFCC_CONFIG_CONTROL- NFCC allowed to manage RF configuration.
    if (NFC_GetNCIVersion() != NCI_VERSION_1_0) {
        uint8_t nfa_set_config[] = { 0x00 };

        nfa_set_config[0] = (flag == true ? 1 : 0);
        gMutexConfig.lock();
        tNFA_STATUS status = NFA_SetConfig(NCI_PARAM_ID_NFCC_CONFIG_CONTROL,
                                           sizeof(nfa_set_config),
                                           &nfa_set_config[0]);
        if (status != NFA_STATUS_OK) {
            LOG(ERROR) << __func__
            << ": Failed to configure NFCC_CONFIG_CONTROL";
        }
        gMutexConfig.unlock();
    }
}

static bool isReaderModeAnnotationSupported(JNIEnv* e, jobject o) {
  ScopedLocalRef<jclass> cls(e, e->GetObjectClass(o));
  jmethodID isSupported =
      e->GetMethodID(cls.get(), "isReaderModeAnnotationSupportedCaps", "()Z");
  return e->CallBooleanMethod(o, isSupported);
}

static tNFA_STATUS setTechAPollingLoopAnnotation(JNIEnv* env, jobject o,
                                                 const uint8_t* annotation_data,
                                                 size_t annotation_size,
                                                 const uint8_t* extra_annotation_data,
                                                 size_t extra_annotation_size) {
  std::vector<uint8_t> command;
  command.push_back(NCI_ANDROID_SET_TECH_A_POLLING_LOOP_ANNOTATION);
  if (annotation_data == NULL || annotation_size == 0) {
    // Annotation is null or size is 0, setting 0 annotations
    command.push_back(0x00);
  } else {
    command.push_back(0x01);                 // Number of frame entries.
    command.push_back(0x20);                 // Position and type.
    command.push_back(annotation_size + 1);  // Length
    command.push_back(0x0a);                 // Waiting time
    command.insert(command.end(), annotation_data,
                   annotation_data + annotation_size);
    // vendor specific extra bytes vendor should check if data is more than
    // annotation bytes
    if (extra_annotation_size > 0 && extra_annotation_data != NULL) {
      command.push_back(extra_annotation_size);
      command.insert(command.end(), extra_annotation_data,
                     extra_annotation_data + extra_annotation_size);
    }
  }
  SyncEventGuard guard(gNfaVsCommand);
  tNFA_STATUS status = NFA_SendVsCommand(NCI_MSG_PROP_ANDROID, command.size(),
                                         command.data(), nfaVSCallback);
  if (status == NFA_STATUS_OK) {
    if (!gNfaVsCommand.wait(1000)) {
      LOG(ERROR) << StringPrintf(
          "%s: Timed out waiting for a response to setting a polling loop "
          "annotation ",
          __FUNCTION__);
      gVSCmdStatus = NFA_STATUS_FAILED;
    }
  } else {
    gVSCmdStatus = status;
  }
  return gVSCmdStatus;
}

/*******************************************************************************
**
** Function:        nfcManager_enableDiscovery
**
** Description:     Start polling and listening for devices.
**                  e: JVM environment.
**                  o: Java object.
**                  technologies_mask: the bitmask of technologies for which to
*enable discovery
**                  enable_lptd: whether to enable low power polling (default:
*false)
**
** Returns:         None
**
*******************************************************************************/
static void nfcManager_enableDiscovery(
    JNIEnv* e, jobject o, jint technologies_mask, jboolean enable_lptd,
    jboolean is_reader_mode_request, jboolean enable_host_routing,
    jbyteArray tech_a_polling_loop_annotation,
    jbyteArray extra_vendor_annotation, jboolean restart) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return;
  tNFA_TECHNOLOGY_MASK tech_mask = DEFAULT_TECH_MASK;
  struct nfc_jni_native_data* nat = getNative(e, o);

  if (technologies_mask == -1 && nat)
    tech_mask = (tNFA_TECHNOLOGY_MASK)nat->tech_mask;
  else if (technologies_mask != -1)
    tech_mask = (tNFA_TECHNOLOGY_MASK)technologies_mask;
  LOG(DEBUG) << StringPrintf("%s: enter; tech_mask = %02x", __func__,
                             tech_mask);


  if (sRfEnabled) {
    // Stop RF discovery to reconfigure
    startRfDiscovery(false);
  }

  // Check polling configuration
  if (tech_mask != 0) {
    stopPolling_rfDiscoveryDisabled();
    if (isReaderModeAnnotationSupported(e, o)) {
      if (is_reader_mode_request && tech_a_polling_loop_annotation != NULL) {
        ScopedByteArrayRO annotationBytes(e, tech_a_polling_loop_annotation);
        if (extra_vendor_annotation == NULL) {
          setTechAPollingLoopAnnotation(e, o,
                                        (const uint8_t*)annotationBytes.get(),
                                        annotationBytes.size(), NULL, 0);
        } else {
          ScopedByteArrayRO extra_annotationBytes(e, extra_vendor_annotation);
          setTechAPollingLoopAnnotation(
              e, o, (const uint8_t*)annotationBytes.get(),
              annotationBytes.size(),
              (const uint8_t*)extra_annotationBytes.get(),
              extra_annotationBytes.size());
        }
      } else {
        LOG(DEBUG) << StringPrintf("%s: applying defaultFrame, size: %zu",
                                   __func__, defaultFrame.size());
        setTechAPollingLoopAnnotation(e, o, defaultFrame.data(),
                                      defaultFrame.size(), NULL, 0);
      }
    }
    startPolling_rfDiscoveryDisabled(tech_mask);

    if (sPollingEnabled) {
      if (is_reader_mode_request && !sReaderModeEnabled) {
        sReaderModeEnabled = true;
        NFA_DisableListening();

        // configure NFCC_CONFIG_CONTROL- NFCC not allowed to manage RF configuration.
        nfcManager_configNfccConfigControl(false);

        NFA_SetRfDiscoveryDuration(READER_MODE_DISCOVERY_DURATION);
      } else if (!is_reader_mode_request && sReaderModeEnabled) {
        struct nfc_jni_native_data* nat = getNative(e, o);
        sReaderModeEnabled = false;
        SyncEventGuard guard(sNfaEnableDisablePollingEvent);
        tNFA_STATUS status = NFA_EnableListening();

        if (status == NFA_STATUS_OK) {
          sNfaEnableDisablePollingEvent.wait();
        }

        // configure NFCC_CONFIG_CONTROL- NFCC allowed to manage RF configuration.
        nfcManager_configNfccConfigControl(true);

        if (nat) {
          NFA_SetRfDiscoveryDuration(nat->discovery_duration);
        } else {
          LOG(ERROR) << StringPrintf("%s: nat is null", __func__);
        }
      }
    }
  } else {
    if (is_reader_mode_request) {
      setTechAPollingLoopAnnotation(e, o, NULL, 0, NULL, 0);
    }
    if (!is_reader_mode_request && sReaderModeEnabled) {
      LOG(DEBUG) << StringPrintf(
          "%s: if reader mode disable, enable listen again", __func__);
      struct nfc_jni_native_data* nat = getNative(e, o);
      sReaderModeEnabled = false;
      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      tNFA_STATUS status = NFA_EnableListening();

      if (status == NFA_STATUS_OK) {
        sNfaEnableDisablePollingEvent.wait();
      }

      // configure NFCC_CONFIG_CONTROL- NFCC allowed to manage RF configuration.
      nfcManager_configNfccConfigControl(true);

      if (nat) {
        NFA_SetRfDiscoveryDuration(nat->discovery_duration);
      } else {
        LOG(ERROR) << StringPrintf("%s: nat is null", __func__);
      }
    }
    // No technologies configured, stop polling
    stopPolling_rfDiscoveryDisabled();
  }

  // Checking if RT should be updated
  if (!RoutingManager::getInstance().isRTUpdateOptimized()) {
    RoutingManager::getInstance().setEeTechRouteUpdateRequired();
    RoutingManager::getInstance().commitRouting();
  }

  // Actually start discovery.
  startRfDiscovery(true);

  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
}

/*******************************************************************************
**
** Function:        nfcManager_disableDiscovery
**
** Description:     Stop polling and listening for devices.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         None
**
*******************************************************************************/
void nfcManager_disableDiscovery(JNIEnv* e, jobject o) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return;
  tNFA_STATUS status = NFA_STATUS_OK;
  LOG(DEBUG) << StringPrintf("%s: enter;", __func__);

  if (sRfEnabled) {
    // Stop RF Discovery.
    startRfDiscovery(false);
  }
  if (sPollingEnabled) status = stopPolling_rfDiscoveryDisabled();

  LOG(DEBUG) << StringPrintf("%s: exit: Status = 0x%X", __func__, status);
}

/*******************************************************************************
**
** Function:        doPartialDeinit
**
** Description:     Partial DeInit for mode TRANSPARENT, CE ..
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean doPartialDeinit() {
  if (sIsShuttingDown) return false;
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);
  tNFA_STATUS stat = NFA_STATUS_OK;
  sIsDisabling = true;
  NativeT4tNfcee::getInstance().onNfccShutdown();
  if (sIsNfaEnabled) {
    SyncEventGuard guard(sNfaDisableEvent);
    stat = NFA_Disable(TRUE /* graceful */);
    if (stat == NFA_STATUS_OK) {
      LOG(DEBUG) << StringPrintf("%s: wait for completion", __func__);
      sNfaDisableEvent.wait();  // wait for NFA command to finish
    } else {
      LOG(ERROR) << StringPrintf("%s: fail disable; error=0x%X", __func__,
                                 stat);
    }
  }
  sIsDisabling = false;

  LOG(DEBUG) << StringPrintf("%s: deregister VS callbacks", __func__);
  NFA_RegVSCback(false, &nfaVSCallback);

  NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
  theInstance.Finalize();

  return stat == NFA_STATUS_OK ? JNI_TRUE : JNI_FALSE;
}

/*******************************************************************************
**
** Function:        nfcManager_doDeinitialize
**
** Description:     Turn off NFC.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean nfcManager_doDeinitialize(JNIEnv*, jobject) {
  if (sIsShuttingDown) return false;
  LOG(DEBUG) << StringPrintf("%s: enter, sIsRecovering=%d", __func__,
                             sIsRecovering);
  if (gPartialInitMode != ENABLE_MODE_DEFAULT) {
    return doPartialDeinit();
  }
  sIsDisabling = true;

  NativeT4tNfcee::getInstance().onNfccShutdown();
  if (!recovery_option || !sIsRecovering) {
    RoutingManager::getInstance().onNfccShutdown();
  }
  HciEventManager::getInstance().finalize();

  if (sIsNfaEnabled) {
    SyncEventGuard guard(sNfaDisableEvent);

    if (gIsDtaEnabled == true) {
      LOG(DEBUG) << StringPrintf("%s: DTA; unset dta flag in core stack",
                                 __func__);
      NFA_DisableDtamode();
    }

    tNFA_STATUS stat = NFA_Disable(!sIsRecovering);
    if (stat == NFA_STATUS_OK) {
      int16_t max_polling_wait_time = NfcConfig::getUnsigned(
          NAME_MAX_POLLING_WAIT_TIME, DEFAULT_MAX_POLLING_WAIT_TIME);
      LOG(DEBUG) << StringPrintf("%s: wait for completion", __func__);
      if (!sNfaDisableEvent.wait(max_polling_wait_time)) {
        LOG(ERROR) << StringPrintf(
            "%s: NFA_Disable() timeout, keep disabling anyway", __func__);
      }
    } else {
      LOG(ERROR) << StringPrintf("%s: fail disable; error=0x%X", __func__,
                                 stat);
    }
  }
  nativeNfcTag_abortWaits();
  NfcTag::getInstance().abort();
  sAbortConnlessWait = true;
  sIsNfaEnabled = false;
  sRoutingInitialized = false;
  sPollingEnabled = false;
  sIsDisabling = false;
  sReaderModeEnabled = false;
  gActivated = false;
  sRfEnabled = false;
  sLfT3tMax = 0;

  {
    // unblock NFA_EnablePolling() and NFA_DisablePolling()
    SyncEventGuard guard(sNfaEnableDisablePollingEvent);
    sNfaEnableDisablePollingEvent.notifyOne();
  }

  LOG(DEBUG) << StringPrintf("%s: deregister VS callbacks", __func__);
  NFA_RegVSCback(false, &nfaVSCallback);
  // abort any active waits
  {
    SyncEventGuard guard(sNfaSetPowerSubState);
    sNfaSetPowerSubState.notifyOne();
  }
  {
    SyncEventGuard guard(sNfaEnableDisablePollingEvent);
    sNfaEnableDisablePollingEvent.notifyOne();
  }
  {
    SyncEventGuard guard(gNfaSetConfigEvent);
    gNfaSetConfigEvent.notifyOne();
  }
  {
    SyncEventGuard guard(gNfaGetConfigEvent);
    gNfaGetConfigEvent.notifyOne();
  }
  {
    SyncEventGuard guard(gNfaVsCommand);
    gNfaVsCommand.notifyOne();
  }
  {
    SyncEventGuard guard(gSendRawVsCmdEvent);
    gSendRawVsCmdEvent.notifyOne();
  }
  {
    SyncEventGuard guard(gNfaRemoveEpEvent);
    gNfaRemoveEpEvent.notifyOne();
  }

  NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
  theInstance.Finalize();

  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
  return JNI_TRUE;
}

/*******************************************************************************
**
** Function:        isListenMode
**
** Description:     Indicates whether the activation data indicates it is
**                  listen mode.
**
** Returns:         True if this listen mode.
**
*******************************************************************************/
static bool isListenMode(tNFA_ACTIVATED& activated) {
  return (
      (NFC_DISCOVERY_TYPE_LISTEN_A ==
       activated.activate_ntf.rf_tech_param.mode) ||
      (NFC_DISCOVERY_TYPE_LISTEN_B ==
       activated.activate_ntf.rf_tech_param.mode) ||
      (NFC_DISCOVERY_TYPE_LISTEN_F ==
       activated.activate_ntf.rf_tech_param.mode) ||
      (NFC_DISCOVERY_TYPE_LISTEN_ISO15693 ==
       activated.activate_ntf.rf_tech_param.mode) ||
      (NFC_DISCOVERY_TYPE_LISTEN_B_PRIME ==
       activated.activate_ntf.rf_tech_param.mode) ||
      (NFC_INTERFACE_EE_DIRECT_RF == activated.activate_ntf.intf_param.type));
}

/*******************************************************************************
**
** Function:        nfcManager_doAbort
**
** Description:     Not used.
**
** Returns:         None
**
*******************************************************************************/
static void nfcManager_doAbort(JNIEnv* e, jobject, jstring msg) {
  ScopedUtfChars message = {e, msg};
  e->FatalError(message.c_str());
  abort();  // <-- Unreachable
}

/*******************************************************************************
**
** Function:        nfcManager_doDownload
**
** Description:     Download firmware patch files.  Do not turn on NFC.
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean nfcManager_doDownload(JNIEnv*, jobject) {
  if (sIsShuttingDown) return false;
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);
  NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
  bool result = JNI_FALSE;
  theInstance.Initialize();  // start GKI, NCI task, NFC task
  tHAL_NFC_ENTRY* halFuncEntries = theInstance.GetHalEntryFuncs();
  NFA_Partial_Init(halFuncEntries, ENABLE_MODE_TRANSPARENT);
  NFA_RegVSCback(true, &nfaVSCallback);
  result = theInstance.DownloadFirmware();
  NFA_RegVSCback(false, &nfaVSCallback);
  theInstance.Finalize();
  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
  return result;
}

/*******************************************************************************
**
** Function:        nfcManager_doResetTimeouts
**
** Description:     Not used.
**
** Returns:         None
**
*******************************************************************************/
static void nfcManager_doResetTimeouts(JNIEnv*, jobject) {
  if (sIsShuttingDown) return;
  LOG(DEBUG) << StringPrintf("%s", __func__);
  NfcTag::getInstance().resetAllTransceiveTimeouts(true);
}

/*******************************************************************************
**
** Function:        nfcManager_doSetTimeout
**
** Description:     Set timeout value.
**                  e: JVM environment.
**                  o: Java object.
**                  tech: technology ID.
**                  timeout: Timeout value.
**
** Returns:         True if ok.
**
*******************************************************************************/
static bool nfcManager_doSetTimeout(JNIEnv*, jobject, jint tech, jint timeout) {
  if (sIsShuttingDown) return false;
  if (timeout <= 0) {
    LOG(ERROR) << StringPrintf("%s: Timeout must be positive.", __func__);
    return false;
  }
  LOG(DEBUG) << StringPrintf("%s: tech=%d, timeout=%d", __func__, tech,
                             timeout);
  NfcTag::getInstance().setTransceiveTimeout(tech, timeout);
  return true;
}

/*******************************************************************************
**
** Function:        nfcManager_doGetTimeout
**
** Description:     Get timeout value.
**                  e: JVM environment.
**                  o: Java object.
**                  tech: technology ID.
**
** Returns:         Timeout value.
**
*******************************************************************************/
static jint nfcManager_doGetTimeout(JNIEnv*, jobject, jint tech) {
  if (sIsShuttingDown) return -1;
  int timeout = NfcTag::getInstance().getTransceiveTimeout(tech);
  LOG(DEBUG) << StringPrintf("%s: tech=%d, timeout=%d", __func__, tech,
                             timeout);
  return timeout;
}

/*******************************************************************************
**
** Function:        nfcManager_doDump
**
** Description:     Get libnfc-nci dump
**                  e: JVM environment.
**                  obj: Java object.
**                  fdobj: File descriptor to be used
**
** Returns:         Void
**
*******************************************************************************/
static void nfcManager_doDump(JNIEnv* e, jobject obj, jobject fdobj) {
  if (sIsShuttingDown) return;
  int fd = jniGetFDFromFileDescriptor(e, fdobj);
  if (fd < 0) return;

  NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
  theInstance.Dump(fd);
}

static jint nfcManager_doGetNciVersion(JNIEnv*, jobject) {
  if (sIsShuttingDown) return -1;
  return NFC_GetNCIVersion();
}

static void nfcManager_doSetScreenState(JNIEnv* e, jobject o,
                                        jint screen_state_mask,
                                        jboolean alwaysPoll) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return;
  tNFA_STATUS status = NFA_STATUS_OK;
  uint8_t prevScreenState = sPrevScreenStateMask & NFA_SCREEN_STATE_MASK;
  uint8_t state = (screen_state_mask & NFA_SCREEN_STATE_MASK);
  // Always enable listen on DH 0x00
  uint8_t discovry_param = NCI_LISTEN_DH_NFCEE_ENABLE_MASK;
  sIsAlwaysPolling = alwaysPoll;

  LOG(DEBUG) << StringPrintf(
      "%s: state = %d sPrevScreenStateMask= %d, screen_state_mask= %d",
      __FUNCTION__, state, sPrevScreenStateMask, screen_state_mask);

  if (gPartialInitMode != ENABLE_MODE_DEFAULT) {
    LOG(ERROR) << StringPrintf(
        "%s: PartialInit mode Screen state change not required", __FUNCTION__);
    return;
  }

  // Compares screen state + polling mask here.
  if (sPrevScreenStateMask == screen_state_mask) {
    LOG(DEBUG) << StringPrintf(
        "%s: New screen state is same as previous state. No action taken",
        __func__);
    return;
  }

  if (sIsDisabling || !sIsNfaEnabled ||
      (NFC_GetNCIVersion() < NCI_VERSION_2_0)) {
    sPrevScreenStateMask = screen_state_mask;
    return;
  }

  // skip remaining SetScreenState tasks when trying to silent recover NFCC
  if (recovery_option && sIsRecovering) {
    sPrevScreenStateMask = screen_state_mask;
    return;
  }

  if (prevScreenState == NFA_SCREEN_STATE_OFF_LOCKED ||
      prevScreenState == NFA_SCREEN_STATE_OFF_UNLOCKED ||
      prevScreenState == NFA_SCREEN_STATE_ON_LOCKED ||
      prevScreenState == NFA_SCREEN_STATE_UNKNOWN) {
    SyncEventGuard guard(sNfaSetPowerSubState);
    status = NFA_SetPowerSubStateForScreenState(state);
    if (status != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: fail enable SetScreenState; error=0x%X",
                                 __FUNCTION__, status);
      return;
    } else {
      int16_t max_polling_wait_time = NfcConfig::getUnsigned(
          NAME_MAX_POLLING_WAIT_TIME, DEFAULT_MAX_POLLING_WAIT_TIME);
      if (!sNfaSetPowerSubState.wait(max_polling_wait_time)) {
        LOG(ERROR) << StringPrintf(
            "%s: Wait for NFA_SetPowerSubStateForScreenState timeout",
            __func__);
        status = NFA_STATUS_TIMEOUT;
      }
    }
  }

  if (status == NFA_STATUS_TIMEOUT) {
    nfaDeviceManagementCallback(NFA_DM_NFCC_TIMEOUT_EVT, nullptr);
    return;
  }

  // skip remaining SetScreenState tasks when trying to silent recover NFCC
  if (recovery_option && sIsRecovering) {
    sPrevScreenStateMask = screen_state_mask;
    return;
  }

  if (state == NFA_SCREEN_STATE_OFF_LOCKED ||
      state == NFA_SCREEN_STATE_OFF_UNLOCKED) {
    // disable poll and enable listen on DH 0x00
    discovry_param |= NCI_POLLING_DH_DISABLE_MASK;
  } else if (state == NFA_SCREEN_STATE_ON_LOCKED ||
             state == NFA_SCREEN_STATE_ON_UNLOCKED) {
    // enable/disable poll based on NFA_SCREEN_POLLING_TAG_MASK
    discovry_param |= (screen_state_mask & NFA_SCREEN_POLLING_TAG_MASK)
                          ? NCI_POLLING_DH_ENABLE_MASK
                          : NCI_POLLING_DH_DISABLE_MASK;
  }
  LOG(DEBUG) << StringPrintf("%s: discovry_param = 0x%02x", __FUNCTION__,
                             discovry_param);

  if (!sIsAlwaysPolling) {
    gMutexConfig.lock();
    SyncEventGuard guard(gNfaSetConfigEvent);
    status = NFA_SetConfig(NCI_PARAM_ID_CON_DISCOVERY_PARAM,
                           NCI_PARAM_LEN_CON_DISCOVERY_PARAM, &discovry_param);
    if (status == NFA_STATUS_OK) {
      LOG(DEBUG) << StringPrintf("%s: wait for gNfaSetConfigEvent", __func__);
      if (!gNfaSetConfigEvent.wait(5000)) {
        LOG(ERROR) << StringPrintf("%s: wait for gNfaSetConfigEvent timeout",
                                 __func__);
      }
      gMutexConfig.unlock();
    } else {
      LOG(ERROR) << StringPrintf("%s: Failed to update CON_DISCOVER_PARAM",
                                 __FUNCTION__);
      gMutexConfig.unlock();
      return;
    }
  }
  // skip remaining SetScreenState tasks when trying to silent recover NFCC
  if (recovery_option && sIsRecovering) {
    sPrevScreenStateMask = screen_state_mask;
    return;
  }

  if (prevScreenState == NFA_SCREEN_STATE_ON_UNLOCKED) {
    SyncEventGuard guard(sNfaSetPowerSubState);
    status = NFA_SetPowerSubStateForScreenState(state);
    if (status != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: fail enable SetScreenState; error=0x%X",
                                 __FUNCTION__, status);
    } else {
      int16_t max_polling_wait_time = NfcConfig::getUnsigned(
          NAME_MAX_POLLING_WAIT_TIME, DEFAULT_MAX_POLLING_WAIT_TIME);
      if (!sNfaSetPowerSubState.wait(max_polling_wait_time)) {
        LOG(ERROR) << StringPrintf(
            "%s: Wait for NFA_SetPowerSubStateForScreenState timeout",
            __func__);

        status = NFA_STATUS_TIMEOUT;
      }
    }
  }

  if (status == NFA_STATUS_TIMEOUT) {
    nfaDeviceManagementCallback(NFA_DM_NFCC_TIMEOUT_EVT, nullptr);
    return;
  }

  // skip remaining SetScreenState tasks when trying to silent recover NFCC
  if (recovery_option && sIsRecovering) {
    sPrevScreenStateMask = screen_state_mask;
    return;
  }

  if ((state == NFA_SCREEN_STATE_OFF_LOCKED ||
       state == NFA_SCREEN_STATE_OFF_UNLOCKED) &&
      (prevScreenState == NFA_SCREEN_STATE_ON_UNLOCKED ||
       prevScreenState == NFA_SCREEN_STATE_ON_LOCKED) &&
      (!sSeRfActive)) {
    // screen turns off, disconnect tag if connected
    nativeNfcTag_doDisconnect(NULL, NULL);
  }

  sPrevScreenStateMask = screen_state_mask;
}

/*******************************************************************************
**
** Function:        nfcManager_getIsoDepMaxTransceiveLength
**
** Description:     Get maximum ISO DEP Transceive Length supported by the NFC
**                  chip. Returns default 261 bytes if the property is not set.
**
** Returns:         max value.
**
*******************************************************************************/
static jint nfcManager_getIsoDepMaxTransceiveLength(JNIEnv*, jobject) {
  if (sIsShuttingDown) return -1;
  /* Check if extended APDU is supported by the chip.
   * If not, default value is returned.
   * The maximum length of a default IsoDep frame consists of:
   * CLA, INS, P1, P2, LC, LE + 255 payload bytes = 261 bytes
   */
  return NfcConfig::getUnsigned(NAME_ISO_DEP_MAX_TRANSCEIVE, 261);
}

/*******************************************************************************
 **
 ** Function:        nfcManager_getAidTableSize
 ** Description:     Get the maximum supported size for AID routing table.
 **
 **                  e: JVM environment.
 **                  o: Java object.
 **
 *******************************************************************************/
static jint nfcManager_getAidTableSize(JNIEnv*, jobject) {
  if (sIsShuttingDown) return -1;
  return NFA_GetAidTableSize();
}

/*******************************************************************************
**
** Function:        nfcManager_IsMultiTag
**
** Description:     Check if it a multi tag case.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         None.
**
*******************************************************************************/
static bool nfcManager_isMultiTag() {
  if (sIsShuttingDown) return false;
  LOG(DEBUG) << StringPrintf("%s: enter mNumRfDiscId = %d", __func__,
                             NfcTag::getInstance().mNumRfDiscId);
  bool status = false;
  if (NfcTag::getInstance().mNumRfDiscId > 1) status = true;
  LOG(DEBUG) << StringPrintf("isMultiTag = %d", status);
  return status;
}

/*******************************************************************************
**
** Function:        nfcManager_doStartStopPolling
**
** Description:     Start or stop NFC RF polling
**                  e: JVM environment.
**                  o: Java object.
**                  start: start or stop RF polling
**
** Returns:         None
**
*******************************************************************************/
static void nfcManager_doStartStopPolling(JNIEnv* e, jobject o,
                                          jboolean start) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return;
  startStopPolling(start);
}

/*******************************************************************************
**
** Function:        nfcManager_doSetNfcSecure
**
** Description:     Set NfcSecure enable/disable.
**                  e: JVM environment.
**                  o: Java object.
**                  enable: Sets true/false to enable/disable NfcSecure
**                  It only updates the routing table cache without commit to
**                  NFCC.
**
** Returns:         True always
**
*******************************************************************************/
static jboolean nfcManager_doSetNfcSecure(JNIEnv* e, jobject o,
                                          jboolean enable) {
  if (sIsShuttingDown) return false;
  RoutingManager& routingManager = RoutingManager::getInstance();
  routingManager.setNfcSecure(enable);
  if (sRoutingInitialized) {
    routingManager.setEeTechRouteUpdateRequired();
  }
  return true;
}

static void nfcManager_doSetNfceePowerAndLinkCtrl(JNIEnv* e, jobject o,
                                                  jboolean enable) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return;
  RoutingManager& routingManager = RoutingManager::getInstance();
  if (enable) {
    routingManager.eeSetPwrAndLinkCtrl(
        (uint8_t)always_on_nfcee_power_and_link_conf);
  } else {
    routingManager.eeSetPwrAndLinkCtrl(
        (uint8_t)disable_always_on_nfcee_power_and_link_conf);
  }
}

/*******************************************************************************
**
** Function:        nfcManager_doGetMaxRoutingTableSize
**
** Description:     Retrieve the max routing table size from cache
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         Max Routing Table size
**
*******************************************************************************/
static jint nfcManager_doGetMaxRoutingTableSize(JNIEnv* e, jobject o) {
  if (sIsShuttingDown) return -1;
  return lmrt_get_max_size();
}

/*******************************************************************************
**
** Function:        nfcManager_doGetRoutingTable
**
** Description:     Retrieve the committed listen mode routing configuration
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         Committed listen mode routing configuration
**
*******************************************************************************/
static jbyteArray nfcManager_doGetRoutingTable(JNIEnv* e, jobject o) {
  if (sIsShuttingDown) return nullptr;
  std::vector<uint8_t>* routingTable = lmrt_get_tlvs();

  CHECK(e);
  jbyteArray rtJavaArray = e->NewByteArray((*routingTable).size());
  CHECK(rtJavaArray);
  e->SetByteArrayRegion(rtJavaArray, 0, (*routingTable).size(),
                        (jbyte*)&(*routingTable)[0]);

  return rtJavaArray;
}

static void nfcManager_clearRoutingEntry(JNIEnv* e, jobject o,
                                         jint clearFlags) {
  if (sIsShuttingDown) return;
  LOG(DEBUG) << StringPrintf("%s: clearFlags=0x%X", __func__, clearFlags);
  RoutingManager::getInstance().clearRoutingEntry(clearFlags);
}

/*******************************************************************************
**
** Function:        nfcManager_doDetectEpRemoval
**
** Description:     Start detection of WLC Listener removal
**                  e: JVM environment.
**                  waiting_time_int:

**
** Returns:         True if WLCL remove started properly
**
*******************************************************************************/
static jboolean nfcManager_doDetectEpRemoval(JNIEnv* e, jobject o,
                                             jint waiting_time_int) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return false;
  tNFA_STATUS stat = NFA_STATUS_FAILED;

  LOG(DEBUG) << StringPrintf("%s: enter waiting_time = %04X", __func__,
                             waiting_time_int);

  nativeNfcTag_acquireRfInterfaceMutexLock();
  stat = NFA_StartRemovalDetection((uint8_t)(waiting_time_int & 0xFF));
  if (stat == NFA_STATUS_OK) {
    LOG(DEBUG) << StringPrintf(
        "%s: start detect EP Listener removal, wait for success confirmation",
        __func__);
    SyncEventGuard g(gNfaRemoveEpEvent);
    gNfaRemoveEpEvent.wait();
    stat = sIsEpDetectStarted ? JNI_TRUE : JNI_FALSE;
  } else {
    LOG(ERROR) << StringPrintf("%s: fail detect EP removal; error=0x%X",
                               __func__, stat);
    stat = false;
  }
  nativeNfcTag_releaseRfInterfaceMutexLock();
  return stat;
}

/*******************************************************************************
**
** Function:        nfcManager_isRemovalDetectionSupported
**
** Description:     Check if the Removal Detection in Poll mode is supported.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         True if supports 'Removal Detection Mode'
**
*******************************************************************************/
static jboolean nfcManager_isRemovalDetectionSupported(JNIEnv* e, jobject o) {
  if (sIsShuttingDown) return false;
  return NFA_IsRfRemovalDetectionSupported();
}

static void nfcManager_updateIsoDepProtocolRoute(JNIEnv* e, jobject o,
                                                 jint route) {
  if (sIsShuttingDown) return;
  LOG(DEBUG) << StringPrintf("%s: route=0x%X", __func__, route);
  RoutingManager::getInstance().updateIsoDepProtocolRoute(route);
}

static void nfcManager_updateTechnologyABFRoute(JNIEnv* e, jobject o,
                                                jint route, jint felicaRoute) {
  if (sIsShuttingDown) return;
  LOG(DEBUG) << StringPrintf("%s: route=0x%X, felicaRoute=0x%X", __func__,
                             route, felicaRoute);
  RoutingManager::getInstance().updateTechnologyABFRoute(route, felicaRoute);
}

static void nfcManager_updateSystemCodeRoute(JNIEnv* e, jobject o,
                                                jint route) {
  if (sIsShuttingDown) return;
  LOG(DEBUG) << StringPrintf("%s: route=0x%X", __func__, route);
  RoutingManager::getInstance().updateSystemCodeRoute(route);
}

/*******************************************************************************
**
** Function:        nfcManager_setDiscoveryTech
**
** Description:     Temporarily changes the RF parameter
**                  pollTech: RF tech parameters for poll mode
**                  listenTech: RF tech parameters for listen mode
**
** Returns:         None.
**
*******************************************************************************/
static void nfcManager_setDiscoveryTech(JNIEnv* e, jobject o, jint pollTech,
                                        jint listenTech) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return;
  tNFA_STATUS nfaStat;
  bool isRevertPoll = false;
  bool isRevertListen = false;
  bool changeDefaultTech = false;
  LOG(DEBUG) << StringPrintf("%s  pollTech = 0x%x, listenTech = 0x%x", __func__,
                             pollTech, listenTech);

  if (pollTech < 0) isRevertPoll = true;
  if (listenTech < 0) isRevertListen = true;
  if (pollTech & FLAG_SET_DEFAULT_TECH || listenTech & FLAG_SET_DEFAULT_TECH)
    changeDefaultTech = true;

  // Need listen tech routing update in routing table
  // for addition of blocking bit
  RoutingManager::getInstance().setEeTechRouteUpdateRequired();

  nativeNfcTag_acquireRfInterfaceMutexLock();
  SyncEventGuard guard(sNfaEnableDisablePollingEvent);

  nfaStat = NFA_ChangeDiscoveryTech(pollTech, listenTech, isRevertPoll,
                                    isRevertListen, changeDefaultTech);

  if (nfaStat == NFA_STATUS_OK) {
    // wait for NFA_LISTEN_DISABLED_EVT
    int16_t max_polling_wait_time = NfcConfig::getUnsigned(
        NAME_MAX_POLLING_WAIT_TIME, DEFAULT_MAX_POLLING_WAIT_TIME);
    LOG(DEBUG) << StringPrintf("%s: wait for completion", __func__);
    if (!sNfaEnableDisablePollingEvent.wait(max_polling_wait_time)) {
      LOG(ERROR) << StringPrintf("%s: wait for NFA_LISTEN_DISABLED_EVT timeout",
                                 __func__);
    }
  } else {
    LOG(ERROR) << StringPrintf("%s: fail disable polling; error=0x%X", __func__,
                               nfaStat);
  }
  nativeNfcTag_releaseRfInterfaceMutexLock();
}

/*******************************************************************************
**
** Function:        nfcManager_resetDiscoveryTech
**
** Description:     Restores the RF tech to the state before
**                  nfcManager_setDiscoveryTech was called
**
** Returns:         None.
**
*******************************************************************************/
static void nfcManager_resetDiscoveryTech(JNIEnv* e, jobject o) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return;
  tNFA_STATUS nfaStat;
  LOG(DEBUG) << StringPrintf("%s : enter", __func__);

  // Need listen tech routing update in routing table
  // for addition of blocking bit
  RoutingManager::getInstance().setEeTechRouteUpdateRequired();

  nativeNfcTag_acquireRfInterfaceMutexLock();
  SyncEventGuard guard(sNfaEnableDisablePollingEvent);

  nfaStat = NFA_ChangeDiscoveryTech(0xFF, 0xFF, true, true, false);

  if (nfaStat == NFA_STATUS_OK) {
    // wait for NFA_LISTEN_DISABLED_EVT
    sNfaEnableDisablePollingEvent.wait();
  } else {
    LOG(ERROR) << StringPrintf("%s: fail disable polling; error=0x%X", __func__,
                               nfaStat);
  }
  nativeNfcTag_releaseRfInterfaceMutexLock();
}

static void ncfManager_nativeEnableVendorNciNotifications(JNIEnv* env,
                                                          jobject o,
                                                          jboolean enable) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return;
  sEnableVendorNciNotifications = (enable == JNI_TRUE);
}

static jobject nfcManager_dofetchActiveNfceeList(JNIEnv* e, jobject o) {
  (void)o;
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return nullptr;
  return NfceeManager::getInstance().getActiveNfceeList(e);
}

static jobject nfcManager_nativeSendRawVendorCmd(JNIEnv* env, jobject o,
                                                 jint mt, jint gid, jint oid,
                                                 jbyteArray payload) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return nullptr;
  LOG(DEBUG) << StringPrintf("%s : enter", __func__);
  ScopedByteArrayRO payloaBytes(env, payload);
  ScopedLocalRef<jclass> cls(env,
                             env->FindClass(gNfcVendorNciResponseClassName));
  jmethodID responseConstructor =
      env->GetMethodID(cls.get(), "<init>", "(BII[B)V");

  jbyte mStatus = NFA_STATUS_FAILED;
  jint resGid = 0;
  jint resOid = 0;
  jbyteArray resPayload = nullptr;

  sRawVendorCmdResponse.clear();

  resGid = gid;
  resOid = oid;
  if (payloaBytes.size() > 255) {
    LOG(ERROR) << StringPrintf("%s: payload size too large: %zu", __func__,
                               payloaBytes.size());
    return env->NewObject(cls.get(), responseConstructor, mStatus, resGid,
                          resOid, resPayload);
  }
  std::vector<uint8_t> command;
  command.push_back((uint8_t)((mt << NCI_MT_SHIFT) | gid));
  command.push_back((uint8_t)oid);
  command.push_back((uint8_t)payloaBytes.size());
  if (payloaBytes.size() > 0) {
    const jbyte* data = payloaBytes.get();  // get the pointer to the data
    command.insert(command.end(),
                   reinterpret_cast<const uint8_t*>(data),
                   reinterpret_cast<const uint8_t*>(data + payloaBytes.size()));
  }

  SyncEventGuard guard(gSendRawVsCmdEvent);
  mStatus = NFA_SendRawVsCommand(command.size(), command.data(),
                                 sendRawVsCmdCallback);
  if (mStatus == NFA_STATUS_OK) {
    if (gSendRawVsCmdEvent.wait(2000) == false) {
      mStatus = NFA_STATUS_FAILED;
      LOG(ERROR) << StringPrintf("%s: timeout ", __func__);
    }

    if (mStatus == NFA_STATUS_OK && sRawVendorCmdResponse.size() > 2) {
      resGid = sRawVendorCmdResponse[0] & NCI_GID_MASK;
      resOid = sRawVendorCmdResponse[1];
      const jsize len = static_cast<jsize>(sRawVendorCmdResponse[2]);
      if (sRawVendorCmdResponse.size() >= (sRawVendorCmdResponse[2] + 3)) {
        resPayload = env->NewByteArray(len);
        std::vector<uint8_t> payloadVec(sRawVendorCmdResponse.begin() + 3,
                                        sRawVendorCmdResponse.end());
        env->SetByteArrayRegion(
            resPayload, 0, len,
            reinterpret_cast<const jbyte*>(payloadVec.data()));
      } else {
        mStatus = NFA_STATUS_FAILED;
        LOG(ERROR) << StringPrintf("%s: invalid payload data", __func__);
      }
    } else {
      mStatus = NFA_STATUS_FAILED;
    }
  }

  LOG(DEBUG) << StringPrintf("%s : exit", __func__);
  return env->NewObject(cls.get(), responseConstructor, mStatus, resGid, resOid,
                        resPayload);
}

static void sendRawVsCmdCallback(uint8_t event, uint16_t param_len,
                                 uint8_t* p_param) {
  sRawVendorCmdResponse = std::vector<uint8_t>(p_param, p_param + param_len);

  SyncEventGuard guard(gSendRawVsCmdEvent);
  gSendRawVsCmdEvent.notifyOne();
}

/*******************************************************************************
 **
 ** Function:        nfcManager_setNciConfig
 **
 ** Description:     Set a NCI parameter through the NFA_SetConfig API.
 **                  param_id : The param id
 **                  param : value of the param
 **                  length : length of the parameter payload
 **
 ** Returns:         void
 **
 *******************************************************************************/
static void nfcManager_setNciConfig(JNIEnv* e, jobject o, jint param_id,
                                    jbyteArray param, jint length,
                                    jboolean custom) {
  LOG(INFO) << StringPrintf("%s; enter", __func__);
  tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
  uint8_t* buf;

  ScopedByteArrayRO bytes(e, param);
  buf = const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));

  NFA_SetCustomConfig(custom);

  {
    SyncEventGuard guard(gNfaSetConfigEvent);
    android::gMutexConfig.lock();
    nfaStat = NFA_SetConfig(param_id, length, buf);
    if (nfaStat == NFA_STATUS_OK) {
      gNfaSetConfigEvent.wait();  // wait for NFA_DM_SET_CONFIG_EVT
    } else {
      LOG(ERROR) << StringPrintf("%s; NFA_SetConfig() failed; error=0x%X",
                                 __func__, nfaStat);
    }
    android::gMutexConfig.unlock();
  }
}

/*******************************************************************************
**
** Function:        nfcManager_doGetRfDiscoverConfig
**
** Description:     Retrieve current Rf discover configuration
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         Current RF discover configuration
**
*******************************************************************************/
static jbyteArray nfcManager_doGetRfDiscoverConfig(JNIEnv* e, jobject o) {
  if (sIsShuttingDown) return nullptr;
  std::vector<uint8_t>* rfDiscoverConfig = rf_discover_get_configs();

  CHECK(e);
  jbyteArray rtJavaArray = e->NewByteArray((*rfDiscoverConfig).size());
  CHECK(rtJavaArray);
  e->SetByteArrayRegion(rtJavaArray, 0, (*rfDiscoverConfig).size(),
                        (jbyte*)&(*rfDiscoverConfig)[0]);

  return rtJavaArray;
}

/*******************************************************************************
**
** Function:        nfcManager_doSetDefaultFrame
**
** Description:     Set default frame content
**                  e: JVM environment.
**                  o: Java object.
**                  frame: default frame content.
**
** Returns:         None
**
*******************************************************************************/
static void nfcManager_doSetDefaultFrame(JNIEnv* e, jobject o,
                                         jbyteArray frame) {
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);
  if (frame != NULL) {
    ScopedByteArrayRO bytes(e, frame);
    defaultFrame.assign((const uint8_t*)bytes.get(),
                        (const uint8_t*)bytes.get() + bytes.size());
  } else {
    defaultFrame.clear();
  }
}

/*****************************************************************************
**
** JNI functions for android-4.0.1_r1
**
*****************************************************************************/
static const JNINativeMethod gMethods[] = {
    {"doDownload", "()Z", (void*)nfcManager_doDownload},

    {"initializeNativeStructure", "()Z", (void*)nfcManager_initNativeStruc},

    {"doInitialize", "()Z", (void*)nfcManager_doInitialize},

    {"doSetPartialInitMode", "(I)V", (void*)nfcManager_doSetPartialInitMode},

    {"doDeinitialize", "()Z", (void*)nfcManager_doDeinitialize},

    {"sendRawFrame", "([B)Z", (void*)nfcManager_sendRawFrame},

    {"routeAid", "([BIII)Z", (void*)nfcManager_routeAid},

    {"unrouteAid", "([B)Z", (void*)nfcManager_unrouteAid},

    {"commitRouting", "()I", (void*)nfcManager_commitRouting},

    {"doRegisterT3tIdentifier", "([B)I",
     (void*)nfcManager_doRegisterT3tIdentifier},

    {"doDeregisterT3tIdentifier", "(I)V",
     (void*)nfcManager_doDeregisterT3tIdentifier},

    {"getLfT3tMax", "()I", (void*)nfcManager_getLfT3tMax},

    {"doEnableDiscovery", "(IZZZ[B[BZ)V", (void*)nfcManager_enableDiscovery},

    {"doStartStopPolling", "(Z)V", (void*)nfcManager_doStartStopPolling},

    {"disableDiscovery", "()V", (void*)nfcManager_disableDiscovery},

    {"doSetTimeout", "(II)Z", (void*)nfcManager_doSetTimeout},

    {"doGetTimeout", "(I)I", (void*)nfcManager_doGetTimeout},

    {"doResetTimeouts", "()V", (void*)nfcManager_doResetTimeouts},

    {"doAbort", "(Ljava/lang/String;)V", (void*)nfcManager_doAbort},

    {"doSetScreenState", "(IZ)V", (void*)nfcManager_doSetScreenState},

    {"doDump", "(Ljava/io/FileDescriptor;)V", (void*)nfcManager_doDump},

    {"getNciVersion", "()I", (void*)nfcManager_doGetNciVersion},
    {"doEnableDtaMode", "()V", (void*)nfcManager_doEnableDtaMode},
    {"doDisableDtaMode", "()V", (void*)nfcManager_doDisableDtaMode},
    {"doFactoryReset", "()V", (void*)nfcManager_doFactoryReset},
    {"doShutdown", "()V", (void*)nfcManager_doShutdown},

    {"getIsoDepMaxTransceiveLength", "()I",
     (void*)nfcManager_getIsoDepMaxTransceiveLength},

    {"getAidTableSize", "()I", (void*)nfcManager_getAidTableSize},

    {"doSetNfcSecure", "(Z)Z", (void*)nfcManager_doSetNfcSecure},

    {"doSetNfceePowerAndLinkCtrl", "(Z)V",
     (void*)nfcManager_doSetNfceePowerAndLinkCtrl},

    {"doSetPowerSavingMode", "(Z)Z", (void*)nfcManager_doSetPowerSavingMode},

    {"getRoutingTable", "()[B", (void*)nfcManager_doGetRoutingTable},

    {"getMaxRoutingTableSize", "()I",
     (void*)nfcManager_doGetMaxRoutingTableSize},

    {"setObserveMode", "(Z)Z", (void*)nfcManager_setObserveMode},

    {"isObserveModeEnabled", "()Z", (void*)nfcManager_isObserveModeEnabled},

    {"isMultiTag", "()Z", (void*)nfcManager_isMultiTag},

    {"clearRoutingEntry", "(I)V", (void*)nfcManager_clearRoutingEntry},

    {"setIsoDepProtocolRoute", "(I)V",
     (void*)nfcManager_updateIsoDepProtocolRoute},

    {"setTechnologyABFRoute", "(II)V",
     (void*)nfcManager_updateTechnologyABFRoute},

    {"setSystemCodeRoute", "(I)V", (void*)nfcManager_updateSystemCodeRoute},

    {"setDiscoveryTech", "(II)V", (void*)nfcManager_setDiscoveryTech},

    {"resetDiscoveryTech", "()V", (void*)nfcManager_resetDiscoveryTech},
    {"nativeSendRawVendorCmd", "(III[B)Lcom/android/nfc/NfcVendorNciResponse;",
     (void*)nfcManager_nativeSendRawVendorCmd},

    {"dofetchActiveNfceeList", "()Ljava/util/Map;",
     (void*)nfcManager_dofetchActiveNfceeList},

    {"getProprietaryCaps", "()[B", (void*)nfcManager_getProprietaryCaps},
    {"enableVendorNciNotifications", "(Z)V",
     (void*)ncfManager_nativeEnableVendorNciNotifications},
    {"injectNtf", "([B)V", (void*)nfcManager_injectNtf},
    {"doDetectEpRemoval", "(I)Z", (void*)nfcManager_doDetectEpRemoval},
    {"isRemovalDetectionInPollModeSupported", "()Z",
     (void*)nfcManager_isRemovalDetectionSupported},
    {"setFirmwareExitFrameTable", "([Lcom/android/nfc/ExitFrame;[B)Z",
     (void*)nfcManager_setFirmwareExitFrameTable},
    {"doRestartRfDiscovery", "()V", (void*)nfcManager_restartRfDiscovery},
    {"setNciConfig", "(I[BIZ)V", (void*)nfcManager_setNciConfig},
    {"getRfDiscoverConfig", "()[B", (void*)nfcManager_doGetRfDiscoverConfig},
    {"doSetDefaultFrame", "([B)V", (void*)nfcManager_doSetDefaultFrame},
};

/*******************************************************************************
**
** Function:        register_com_android_nfc_NativeNfcManager
**
** Description:     Regisgter JNI functions with Java Virtual Machine.
**                  e: Environment of JVM.
**
** Returns:         Status of registration.
**
*******************************************************************************/
int register_com_android_nfc_NativeNfcManager(JNIEnv* e) {
  return jniRegisterNativeMethods(e, gNativeNfcManagerClassName, gMethods,
                                  NELEM(gMethods));
}

/*******************************************************************************
**
** Function:        startRfDiscovery
**
** Description:     Ask stack to start polling and listening for devices.
**                  isStart: Whether to start.
**
** Returns:         None
**
*******************************************************************************/
void startRfDiscovery(bool isStart) {
  tNFA_STATUS status = NFA_STATUS_FAILED;

  LOG(DEBUG) << StringPrintf("%s: is start=%d", __func__, isStart);
  nativeNfcTag_acquireRfInterfaceMutexLock();
  {
    SyncEventGuard guard(sNfaEnableDisablePollingEvent);
    status = isStart ? NFA_StartRfDiscovery() : NFA_StopRfDiscovery();
    if (!sIsRecovering && status == NFA_STATUS_OK) {
      int16_t max_polling_wait_time = NfcConfig::getUnsigned(
          NAME_MAX_POLLING_WAIT_TIME, DEFAULT_MAX_POLLING_WAIT_TIME);
      LOG(DEBUG) << StringPrintf("%s: Wait for completion timeout", __func__);
      if (!sNfaEnableDisablePollingEvent.wait(max_polling_wait_time)) {
        LOG(ERROR) << StringPrintf(
            "%s: Wait for NFA_RF_DISCOVERY_xxxx_EVT timeout. Restart NFC "
            "service...",
            __func__);
        status = NFA_STATUS_TIMEOUT;
      } else {
        sRfEnabled = isStart;
      }
    } else if (!sIsRecovering) {
      LOG(ERROR) << StringPrintf(
          "%s: Failed to start/stop RF discovery; error=0x%X", __func__,
          status);
    }
  }
  nativeNfcTag_releaseRfInterfaceMutexLock();
  if (status == NFA_STATUS_TIMEOUT) {
    nfaDeviceManagementCallback(NFA_DM_NFCC_TIMEOUT_EVT, nullptr);
  }
}

/*******************************************************************************
**
** Function:        isDiscoveryStarted
**
** Description:     Indicates whether the discovery is started.
**
** Returns:         True if discovery is started
**
*******************************************************************************/
bool isDiscoveryStarted() { return sRfEnabled; }

/*******************************************************************************
**
** Function:        doStartupConfig
**
** Description:     Configure the NFC controller.
**
** Returns:         None
**
*******************************************************************************/
void doStartupConfig() {
  // configure RF polling frequency for each technology
  static tNFA_DM_DISC_FREQ_CFG nfa_dm_disc_freq_cfg;
  // values in the polling_frequency[] map to members of nfa_dm_disc_freq_cfg
  std::vector<uint8_t> polling_frequency;
  if (NfcConfig::hasKey(NAME_POLL_FREQUENCY))
    polling_frequency = NfcConfig::getBytes(NAME_POLL_FREQUENCY);
  if (polling_frequency.size() == 8) {
    LOG(DEBUG) << StringPrintf("%s: polling frequency", __func__);
    memset(&nfa_dm_disc_freq_cfg, 0, sizeof(nfa_dm_disc_freq_cfg));
    nfa_dm_disc_freq_cfg.pa = polling_frequency[0];
    nfa_dm_disc_freq_cfg.pb = polling_frequency[1];
    nfa_dm_disc_freq_cfg.pf = polling_frequency[2];
    nfa_dm_disc_freq_cfg.pi93 = polling_frequency[3];
    nfa_dm_disc_freq_cfg.pbp = polling_frequency[4];
    nfa_dm_disc_freq_cfg.pk = polling_frequency[5];
    nfa_dm_disc_freq_cfg.paa = polling_frequency[6];
    nfa_dm_disc_freq_cfg.pfa = polling_frequency[7];
    p_nfa_dm_rf_disc_freq_cfg = &nfa_dm_disc_freq_cfg;
  }

  // configure NFCC_CONFIG_CONTROL- NFCC allowed to manage RF configuration.
  nfcManager_configNfccConfigControl(true);

}

/*******************************************************************************
**
** Function:        nfcManager_isNfcActive
**
** Description:     Used externaly to determine if NFC is active or not.
**
** Returns:         'true' if the NFC stack is running, else 'false'.
**
*******************************************************************************/
bool nfcManager_isNfcActive() { return sIsNfaEnabled; }

/*******************************************************************************
**
** Function:        startStopPolling
**
** Description:     Start or stop polling.
**                  isStartPolling: true to start polling; false to stop
*polling.
**
** Returns:         None.
**
*******************************************************************************/
void startStopPolling(bool isStartPolling) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  uint8_t discovry_param = 0;
  LOG(DEBUG) << StringPrintf("%s: enter; isStart=%u", __func__, isStartPolling);

  if (NFC_GetNCIVersion() >= NCI_VERSION_2_0) {
    gMutexConfig.lock();
    SyncEventGuard guard(gNfaSetConfigEvent);
    if (isStartPolling) {
      discovry_param =
          NCI_LISTEN_DH_NFCEE_ENABLE_MASK | NCI_POLLING_DH_ENABLE_MASK;
    } else {
      discovry_param =
          NCI_LISTEN_DH_NFCEE_ENABLE_MASK | NCI_POLLING_DH_DISABLE_MASK;
    }
    status = NFA_SetConfig(NCI_PARAM_ID_CON_DISCOVERY_PARAM,
                           NCI_PARAM_LEN_CON_DISCOVERY_PARAM, &discovry_param);
    if (status == NFA_STATUS_OK) {
      gNfaSetConfigEvent.wait();
    } else {
      LOG(ERROR) << StringPrintf("%s: Failed to update CON_DISCOVER_PARAM",
                                 __FUNCTION__);
    }
    gMutexConfig.unlock();
  } else {
    startRfDiscovery(false);
    if (isStartPolling)
      startPolling_rfDiscoveryDisabled(0);
    else
      stopPolling_rfDiscoveryDisabled();
    startRfDiscovery(true);
  }
  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
}

static tNFA_STATUS startPolling_rfDiscoveryDisabled(
    tNFA_TECHNOLOGY_MASK tech_mask) {
  tNFA_STATUS stat = NFA_STATUS_FAILED;

  if (tech_mask == 0)
    tech_mask =
        NfcConfig::getUnsigned(NAME_POLLING_TECH_MASK, DEFAULT_TECH_MASK);

  nativeNfcTag_acquireRfInterfaceMutexLock();
  SyncEventGuard guard(sNfaEnableDisablePollingEvent);
  LOG(DEBUG) << StringPrintf("%s: enable polling", __func__);
  stat = NFA_EnablePolling(tech_mask);
  if (stat == NFA_STATUS_OK) {
    LOG(DEBUG) << StringPrintf("%s: wait for enable event", __func__);
    sPollingEnabled = true;
    sNfaEnableDisablePollingEvent.wait();  // wait for NFA_POLL_ENABLED_EVT
  } else {
    LOG(ERROR) << StringPrintf("%s: fail enable polling; error=0x%X", __func__,
                               stat);
  }
  nativeNfcTag_releaseRfInterfaceMutexLock();

  return stat;
}

static tNFA_STATUS stopPolling_rfDiscoveryDisabled() {
  tNFA_STATUS stat = NFA_STATUS_FAILED;

  nativeNfcTag_acquireRfInterfaceMutexLock();
  SyncEventGuard guard(sNfaEnableDisablePollingEvent);
  LOG(DEBUG) << StringPrintf("%s: disable polling", __func__);
  stat = NFA_DisablePolling();
  if (!sIsRecovering) {
    if (stat == NFA_STATUS_OK) {
      sPollingEnabled = false;
      sNfaEnableDisablePollingEvent.wait();  // wait for NFA_POLL_DISABLED_EVT
    } else {
      LOG(ERROR) << StringPrintf("%s: fail disable polling; error=0x%X",
                                 __func__, stat);
    }
  }
  nativeNfcTag_releaseRfInterfaceMutexLock();

  return stat;
}

static jboolean nfcManager_doSetPowerSavingMode(JNIEnv* e, jobject o,
                                                bool flag) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return false;
  LOG(DEBUG) << StringPrintf("%s: enter; ", __func__);
  uint8_t cmd[] = {(NCI_MT_CMD << NCI_MT_SHIFT) | NCI_GID_PROP,
                   NCI_MSG_PROP_ANDROID, NCI_ANDROID_POWER_SAVING_PARAM_SIZE,
                   NCI_ANDROID_POWER_SAVING,
                   NCI_ANDROID_POWER_SAVING_PARAM_DISABLE};
  cmd[4] = flag ? NCI_ANDROID_POWER_SAVING_PARAM_ENABLE
                : NCI_ANDROID_POWER_SAVING_PARAM_DISABLE;

  SyncEventGuard guard(gNfaVsCommand);
  tNFA_STATUS status =
      NFA_SendRawVsCommand(sizeof(cmd), cmd, nfaSendRawVsCmdCallback);
  if (status == NFA_STATUS_OK) {
    gNfaVsCommand.wait();
  } else {
    LOG(ERROR) << StringPrintf("%s: Failed to set power-saving mode", __func__);
    gVSCmdStatus = NFA_STATUS_FAILED;
  }
  return gVSCmdStatus == NFA_STATUS_OK;
}

static jbyteArray nfcManager_getProprietaryCaps(JNIEnv* e, jobject o) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return nullptr;
  LOG(DEBUG) << StringPrintf("%s: enter; ", __func__);
  uint8_t cmd[] = {(NCI_MT_CMD << NCI_MT_SHIFT) | NCI_GID_PROP,
                   NCI_MSG_PROP_ANDROID, NCI_ANDROID_GET_CAPS_PARAM_SIZE,
                   NCI_ANDROID_GET_CAPS};
  SyncEventGuard guard(gNfaVsCommand);

  tNFA_STATUS status = NFA_SendRawVsCommand(sizeof(cmd), cmd, nfaVSCallback);
  if (status == NFA_STATUS_OK) {
    if (!gNfaVsCommand.wait(1000)) {
      LOG(ERROR) << StringPrintf(
          "%s: Timed out waiting for a response to get caps ",
          __FUNCTION__);
      gVSCmdStatus = NFA_STATUS_FAILED;
    }
  } else {
    LOG(ERROR) << StringPrintf("%s: Failed to get caps", __func__);
    gVSCmdStatus = NFA_STATUS_FAILED;
  }
  CHECK(e);
  jbyteArray rtJavaArray = e->NewByteArray(gCaps.size());
  CHECK(rtJavaArray);
  e->SetByteArrayRegion(rtJavaArray, 0, gCaps.size(), (jbyte*)gCaps.data());
  return rtJavaArray;
}

static jboolean nfcManager_setFirmwareExitFrameTable(JNIEnv* env, jobject o,
                                                     jobjectArray exit_frames,
                                                     jbyteArray timeout) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return false;
  LOG(DEBUG) << __func__ << ": Setting firmware exit frame table";
  std::vector<uint8_t> command;
  command.push_back(NCI_ANDROID_SET_PASSIVE_OBSERVER_EXIT_FRAME);

  // TODO(b/380455428)
  // Support more than 5 exit frames if firmware allows it. If we do so, might need to send second
  // NCI command if one is too large.
  uint8_t more = 0x00;
  command.push_back(more);

  uint8_t timeout_len = env->GetArrayLength(timeout);
  auto* timeout_arr = (uint8_t*)env->GetByteArrayElements(timeout, nullptr);

  for (int i = 0; i < timeout_len; ++i) {
    command.push_back(timeout_arr[i]);
  }
  env->ReleaseByteArrayElements(timeout, (jbyte*)timeout_arr, JNI_ABORT);

  uint8_t num_exit_frames = env->GetArrayLength(exit_frames);
  if (num_exit_frames > 5) {
      LOG(INFO)
        << "Truncating exit frame table to 5 frames so it fits in a single NCI command. "
        << "Original size was " << num_exit_frames;
      num_exit_frames = 5;
  }
  command.push_back(num_exit_frames);

  if (num_exit_frames > 0) {
    jobject exit_frame = env->GetObjectArrayElement(exit_frames, 0);
    jclass clazz = env->GetObjectClass(exit_frame);
    jmethodID is_prefix_allowed =
        env->GetMethodID(clazz, "isPrefixMatchingAllowed", "()Z");
    jmethodID get_data = env->GetMethodID(clazz, "getData", "()[B");
    jmethodID get_data_mask = env->GetMethodID(clazz, "getDataMask", "()[B");
    jmethodID get_tech = env->GetMethodID(clazz, "getNfcTech", "()I");
    jmethodID get_power_state = env->GetMethodID(clazz, "getPowerState", "()I");

    for (int i = 0; i < num_exit_frames; ++i) {
      jobject frame = env->GetObjectArrayElement(exit_frames, i);

      uint8_t qualifier_type = 0x00;
      if (env->CallBooleanMethod(frame, is_prefix_allowed)) {
        qualifier_type |= 0b00010000;
      }
      qualifier_type |= env->CallIntMethod(frame, get_tech);
      command.push_back(qualifier_type);

      uint8_t power_state = env->CallIntMethod(frame, get_power_state);

      jbyteArray data = (jbyteArray)env->CallObjectMethod(frame, get_data);
      uint8_t data_len = env->GetArrayLength(data);
      auto* data_arr = (uint8_t*)env->GetByteArrayElements(data, nullptr);

      jbyteArray data_mask =
          (jbyteArray)env->CallObjectMethod(frame, get_data_mask);
      uint8_t data_mask_len = env->GetArrayLength(data_mask);
      auto* data_mask_arr =
          (uint8_t*)env->GetByteArrayElements(data_mask, nullptr);

      uint8_t value_len = 1 + data_len + data_mask_len;

      command.push_back(value_len);
      command.push_back(power_state);

      for (int j = 0; j < data_len; ++j) {
        command.push_back(data_arr[j]);
      }
      for (int j = 0; j < data_mask_len; ++j) {
        command.push_back(data_mask_arr[j]);
      }
      env->ReleaseByteArrayElements(data, (jbyte*)data_arr, JNI_ABORT);
      env->ReleaseByteArrayElements(data_mask, (jbyte*)data_mask_arr,
                                    JNI_ABORT);
    }
  }

    bool reenableDiscovery = false;
    if (sRfEnabled) {
      startRfDiscovery(false);
      reenableDiscovery = true;
    }

    // TODO make helper to send single command and wait on response
    {
        SyncEventGuard guard(gNfaVsCommand);
        tNFA_STATUS status = NFA_SendVsCommand(NCI_MSG_PROP_ANDROID, command.size(),
                                               command.data(), nfaVSCallback);

        if (status == NFA_STATUS_OK) {
            if (!gNfaVsCommand.wait(1000)) {
                LOG(ERROR) << StringPrintf(
                        "%s: Timed out waiting for a response to set exit frame table ",
                        __FUNCTION__);
                gVSCmdStatus = NFA_STATUS_FAILED;
            }
        } else {
            LOG(DEBUG) << StringPrintf("%s: Failed to set exit frame table",
                                       __FUNCTION__);
            gVSCmdStatus = NFA_STATUS_FAILED;
        }
    }

    if (reenableDiscovery) {
        startRfDiscovery(true);
    }

    return gVSCmdStatus == NFA_STATUS_OK;
}

/*******************************************************************************
**
** Function:        nfcManager_restartRfDiscovery
** Description:     Restarts RF discovery
**
**                  e: JVM environment.
**                  o: Java object.
**
*******************************************************************************/
static void nfcManager_restartRfDiscovery(JNIEnv*, jobject) {
  if (sIsShuttingDown || sIsRecovering || sIsDisabling || !sIsNfaEnabled)
    return;
  if (sRfEnabled) {
    android::startRfDiscovery(false);
  }
  android::startRfDiscovery(true);
}

} /* namespace android */
