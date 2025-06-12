/******************************************************************************
 *
 *  Copyright (C) 2024 The Android Open Source Project.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#include <android-base/logging.h>
#include <android-base/stringprintf.h>
#include <nativehelper/ScopedPrimitiveArray.h>

#include "JavaClassConstants.h"
#include "NativeT4tNfcee.h"
#include "NfcJniUtil.h"
#include "nfc_config.h"
using android::base::StringPrintf;

namespace android {
/*******************************************************************************
**
** Function:        t4tNfceeManager_doClearNdefData
**
** Description:     This API will set all the NFCEE NDEF data to zero.
**                  This API can be called regardless of NDEF file lock state.
**
** Returns:         boolean : Return the Success or fail of the operation.
**                  Return "True" when operation is successful. else "False"
**
*******************************************************************************/
jint t4tNfceeManager_doClearNdefData(JNIEnv* e, jobject o) {
  LOG(DEBUG) << StringPrintf("%s", __func__);

  return NativeT4tNfcee::getInstance().t4tClearData(e, o);
}
/*******************************************************************************
**
** Function:        t4tNfceeManager_isNdefOperationOngoing
**
** Description:     This API will get NDEF NFCEE status.
**
** Returns:         boolean : Indicates whether NDEF NFCEE Read or write
**                            operation is under process
**                  Return "True" when operation is in progress. else "False"
**
*******************************************************************************/
jboolean t4tNfceeManager_isNdefOperationOngoing(JNIEnv* e, jobject o) {
  LOG(DEBUG) << StringPrintf("%s", __func__);

  return NativeT4tNfcee::getInstance().getT4tStatus(e, o);
}
/*******************************************************************************
**
** Function:        t4tNfceeManager_isNdefNfceeEmulationSupported
**
** Description:     This API will tell whether NDEF NFCEE emulation is supported
**                  or not.
**
** Returns:         boolean : Indicates whether NDEF NFCEE emulation is
**                            supported or not
**                  Return "True" when feature supported. else "False"
**
*******************************************************************************/
jboolean t4tNfceeManager_isNdefNfceeEmulationSupported(JNIEnv* e, jobject o) {
  LOG(DEBUG) << StringPrintf("%s", __func__);

  return NativeT4tNfcee::getInstance().isT4tNdefNfceeEmulationSupported(e, o);
}
/*******************************************************************************
 **
 ** Function:        nfcManager_doWriteData
 **
 ** Description:     Write the data into the NDEF NFCEE file of the specific
 **                  file ID
 **
 ** Returns:         Return the size of data written
 **                  Return negative number of error code
 **
 *******************************************************************************/
jint t4tNfceeManager_doWriteData(JNIEnv* e, jobject o, jbyteArray fileId,
                                 jbyteArray data) {
  LOG(DEBUG) << StringPrintf("%s", __func__);

  return NativeT4tNfcee::getInstance().t4tWriteData(e, o, fileId, data);
}
/*******************************************************************************
**
** Function:        nfcManager_doReadData
**
** Description:     Read the data from the NDEF NFCEE file of the specific file
**                  ID.
**
** Returns:         byte[] : all the data previously written to the specific
**                  file ID.
**                  Return one byte '0xFF' if the data was never written to the
**                  specific file ID,
**                  Return null if reading fails.
**
*******************************************************************************/
jbyteArray t4tNfceeManager_doReadData(JNIEnv* e, jobject o, jbyteArray fileId) {
  LOG(DEBUG) << StringPrintf("%s", __func__);
  return NativeT4tNfcee::getInstance().t4tReadData(e, o, fileId);
}
/*******************************************************************************
**
** Function:        t4tNfceeManager_getNdefNfceeRouteId
**
** Description:     Get the NDEF NFCEE Route ID.
**
** Returns:         NDEF NFCEE route ID if available
**
*******************************************************************************/
jint t4tNfceeManager_getNdefNfceeRouteId() {
  return NfcConfig::getUnsigned(NAME_DEFAULT_NDEF_NFCEE_ROUTE, 0x10);
}
/*******************************************************************************
**
** Function:        t4tNfceeManager_getT4TNfceePowerState
**
** Description:     Get the T4T Nfcee power state supported.
**                  e: JVM environment.
**                  o: Java object.
**                  mode: Not used.
**
** Returns:         None
**
*******************************************************************************/
jint t4tNfceeManager_getT4TNfceePowerState(JNIEnv* e, jobject o) {
  return NfcConfig::getUnsigned(NAME_DEFAULT_T4TNFCEE_AID_POWER_STATE, 0x01);
}

/*******************************************************************************
**
** Function:        t4tNfceeManager_isNdefNfceefeatureEnabled
**
** Description:     Check if T4T_NFCEE_ENABLE is declared in HAL configuration
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         true is set to 1, false otherwise
**
*******************************************************************************/
jboolean t4tNfceeManager_isNdefNfceefeatureEnabled(JNIEnv* e, jobject o) {
  if (NfcConfig::hasKey(NAME_T4T_NFCEE_ENABLE)) {
    if (NfcConfig::getUnsigned(NAME_T4T_NFCEE_ENABLE)) {
      return true;
    }
  }
  return false;
}

/*****************************************************************************
 **
 ** Description:     JNI functions
 **
 *****************************************************************************/
static const JNINativeMethod gMethods[] = {
    {"doWriteData", "([B[B)I", (void*)t4tNfceeManager_doWriteData},
    {"doReadData", "([B)[B", (void*)t4tNfceeManager_doReadData},
    {"doClearNdefData", "()Z", (void*)t4tNfceeManager_doClearNdefData},
    {"isNdefOperationOngoing", "()Z",
     (void*)t4tNfceeManager_isNdefOperationOngoing},
    {"isNdefNfceeEmulationSupported", "()Z",
     (void*)t4tNfceeManager_isNdefNfceeEmulationSupported},
    {"getT4TNfceePowerState", "()I",
     (void*)t4tNfceeManager_getT4TNfceePowerState},
    {"getNdefNfceeRouteId", "()I", (void*)t4tNfceeManager_getNdefNfceeRouteId},
    {"isNdefNfceefeatureEnabled", "()Z",
     (void*)t4tNfceeManager_isNdefNfceefeatureEnabled},
};

/*******************************************************************************
 **
 ** Function:        register_com_android_nfc_NativeT4tNfcee
 **
 ** Description:     Regisgter JNI functions with Java Virtual Machine.
 **                  e: Environment of JVM.
 **
 ** Returns:         Status of registration.
 **
 *******************************************************************************/
int register_com_android_nfc_NativeT4tNfcee(JNIEnv* e) {
  return jniRegisterNativeMethods(e, gNativeT4tNfceeClassName, gMethods,
                                  NELEM(gMethods));
}
}  // namespace android
