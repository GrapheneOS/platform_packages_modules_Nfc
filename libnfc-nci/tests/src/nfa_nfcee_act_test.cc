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

#include <gtest/gtest.h>
#include <cstring>
#include "../../src/nfa/ndefnfcee/t4t/nfa_nfcee_act.cc"

class NfaNfceeActTest : public ::testing::Test {
protected:
    void SetUp() override {
        memset(&nfa_t4tnfcee_cb, 0, sizeof(nfa_t4tnfcee_cb));
    }
    void TearDown() override {
        if (nfa_t4tnfcee_cb.p_dataBuf) {
            free(nfa_t4tnfcee_cb.p_dataBuf);
            nfa_t4tnfcee_cb.p_dataBuf = nullptr;
        }
    }
};

TEST_F(NfaNfceeActTest, StoreRxBuf_NormalCopy) {
    uint32_t buf_len = 20;
    nfa_t4tnfcee_cb.p_dataBuf = (uint8_t*)malloc(buf_len);
    ASSERT_NE(nfa_t4tnfcee_cb.p_dataBuf, nullptr);
    memset(nfa_t4tnfcee_cb.p_dataBuf, 0xFF, buf_len);
    nfa_t4tnfcee_cb.p_dataBuf_len = 10;
    nfa_t4tnfcee_cb.rd_offset = 0;
    nfa_t4tnfcee_cb.status = NFA_STATUS_OK;

    uint16_t data_len = 8;
    uint16_t offset = 0;
    uint32_t msg_len = sizeof(NFC_HDR) + offset + data_len;
    NFC_HDR* p_msg = (NFC_HDR*)malloc(msg_len);
    ASSERT_NE(p_msg, nullptr);
    p_msg->len = data_len;
    p_msg->offset = offset;
    uint8_t* p_data = (uint8_t*)(p_msg + 1) + offset;
    memset(p_data, 0xAA, data_len);

    nfa_t4tnfcee_store_rx_buf(p_msg);

    for (int i = 0; i < data_len; i++) {
        EXPECT_EQ(nfa_t4tnfcee_cb.p_dataBuf[i], 0xAA);
    }
    for (int i = data_len; i < buf_len; i++) {
        EXPECT_EQ(nfa_t4tnfcee_cb.p_dataBuf[i], 0xFF);
    }
    EXPECT_EQ(nfa_t4tnfcee_cb.rd_offset, data_len);
    EXPECT_EQ(nfa_t4tnfcee_cb.status, NFA_STATUS_OK);

    free(p_msg);
}

TEST_F(NfaNfceeActTest, StoreRxBuf_OverflowClamped) {
    uint32_t buf_len = 20;
    nfa_t4tnfcee_cb.p_dataBuf = (uint8_t*)malloc(buf_len);
    ASSERT_NE(nfa_t4tnfcee_cb.p_dataBuf, nullptr);
    memset(nfa_t4tnfcee_cb.p_dataBuf, 0xFF, buf_len);
    nfa_t4tnfcee_cb.p_dataBuf_len = 10;
    nfa_t4tnfcee_cb.rd_offset = 4;
    nfa_t4tnfcee_cb.status = NFA_STATUS_OK;

    uint16_t data_len = 8;
    uint16_t offset = 0;
    uint32_t msg_len = sizeof(NFC_HDR) + offset + data_len;
    NFC_HDR* p_msg = (NFC_HDR*)malloc(msg_len);
    ASSERT_NE(p_msg, nullptr);
    p_msg->len = data_len;
    p_msg->offset = offset;
    uint8_t* p_data = (uint8_t*)(p_msg + 1) + offset;
    memset(p_data, 0xAA, data_len);

    nfa_t4tnfcee_store_rx_buf(p_msg);

    for (int i = 0; i < 4; i++) {
        EXPECT_EQ(nfa_t4tnfcee_cb.p_dataBuf[i], 0xFF);
    }
    for (int i = 4; i < 10; i++) {
        EXPECT_EQ(nfa_t4tnfcee_cb.p_dataBuf[i], 0xAA);
    }
    for (int i = 10; i < buf_len; i++) {
        EXPECT_EQ(nfa_t4tnfcee_cb.p_dataBuf[i], 0xFF);
    }
    EXPECT_EQ(nfa_t4tnfcee_cb.rd_offset, 10);
    EXPECT_EQ(nfa_t4tnfcee_cb.status, NFA_STATUS_FAILED);

    free(p_msg);
}
