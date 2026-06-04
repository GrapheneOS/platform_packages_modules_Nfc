//
// Copyright (C) 2026 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "gki.h"
#include "nfc_api.h"
#include "nfc_int.h"
#include "rw_api.h"
#include "rw_int.h"

// Define states that are internal to rw_ci.cc
#define RW_CI_STATE_IDLE 0x01
#define RW_CI_STATE_ATTRIB 0x02
#define RW_CI_STATE_UID 0x03

extern tNFC_CB nfc_cb;
extern tRW_CB rw_cb;

// Declare internal functions we need from gki
extern void gki_buffer_init(void);

class MockRwCallback {
 public:
  MOCK_METHOD(void, handler, (tRW_EVENT event, tRW_DATA* p_data));
};

MockRwCallback* g_mock_cb = nullptr;

void test_rw_cback(tRW_EVENT event, tRW_DATA* p_data) {
  if (g_mock_cb) {
    g_mock_cb->handler(event, p_data);
  }
}

class RwCiTest : public ::testing::Test {
 protected:
  void SetUp() override {
    gki_buffer_init();
    g_mock_cb = &mock_cb_;
    memset(&rw_cb, 0, sizeof(tRW_CB));
    rw_cb.p_cback = test_rw_cback;
    memset(&nfc_cb, 0, sizeof(tNFC_CB));
  }

  void TearDown() override { g_mock_cb = nullptr; }

  MockRwCallback mock_cb_;
};

TEST_F(RwCiTest, AttribResponseTooShort) {
  tRW_CI_CB* p_ci = &rw_cb.tcb.ci;

  rw_ci_select();
  tNFC_CONN_CBACK* p_cback = nfc_cb.conn_cb[NFC_RF_CONN_ID].p_cback;
  ASSERT_NE(p_cback, nullptr);

  p_ci->state = RW_CI_STATE_ATTRIB;

  uint16_t buf_size = sizeof(NFC_HDR) + 10;
  NFC_HDR* p_r_apdu = (NFC_HDR*)GKI_getbuf(buf_size);
  ASSERT_NE(p_r_apdu, nullptr);

  p_r_apdu->len = 1;  // Too short (attrib_res is 2 bytes)
  p_r_apdu->offset = 0;

  EXPECT_CALL(mock_cb_, handler(RW_CI_INTF_ERROR_EVT, ::testing::_));

  tNFC_CONN conn_data;
  conn_data.data.p_data = p_r_apdu;
  conn_data.data.status = NFC_STATUS_OK;

  p_cback(NFC_RF_CONN_ID, NFC_DATA_CEVT, &conn_data);

  EXPECT_EQ(p_ci->state, RW_CI_STATE_IDLE);
}

TEST_F(RwCiTest, UidResponseTooShort) {
  tRW_CI_CB* p_ci = &rw_cb.tcb.ci;

  rw_ci_select();
  tNFC_CONN_CBACK* p_cback = nfc_cb.conn_cb[NFC_RF_CONN_ID].p_cback;
  ASSERT_NE(p_cback, nullptr);

  p_ci->state = RW_CI_STATE_UID;

  uint16_t buf_size = sizeof(NFC_HDR) + 10;
  NFC_HDR* p_r_apdu = (NFC_HDR*)GKI_getbuf(buf_size);
  ASSERT_NE(p_r_apdu, nullptr);

  p_r_apdu->len = 2;  // Too short (expects 1 + T4T_RSP_STATUS_WORDS_SIZE = 3)
  p_r_apdu->offset = 0;

  // Expect event 0 (NFC_STATUS_OK) with status NFC_STATUS_BAD_RESP
  EXPECT_CALL(mock_cb_, handler(0, ::testing::_))
      .WillOnce(::testing::Invoke([](tRW_EVENT event, tRW_DATA* p_data) {
        (void)event;
        EXPECT_EQ(p_data->status, NFC_STATUS_BAD_RESP);
      }));

  tNFC_CONN conn_data;
  conn_data.data.p_data = p_r_apdu;
  conn_data.data.status = NFC_STATUS_OK;

  p_cback(NFC_RF_CONN_ID, NFC_DATA_CEVT, &conn_data);

  EXPECT_EQ(p_ci->state, RW_CI_STATE_IDLE);
}
