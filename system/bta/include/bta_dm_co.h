/******************************************************************************
 *
 *  Copyright 2006-2012 Broadcom Corporation
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

/******************************************************************************
 *
 *  This is the interface file for device mananger callout functions.
 *
 ******************************************************************************/
#ifndef BTA_DM_CO_H
#define BTA_DM_CO_H

#include "bta/include/bta_api.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/bt_types.h"
#include "stack/include/btm_api_types.h"
#include "types/raw_address.h"

#ifndef BTA_SCO_OUT_PKT_SIZE
#define BTA_SCO_OUT_PKT_SIZE BTM_SCO_DATA_SIZE_MAX
#endif

/*****************************************************************************
 *  Function Declarations
 ****************************************************************************/

/*******************************************************************************
 *
 * Function         bta_dm_co_io_req
 *
 * Description      This callout function is executed by DM to get IO
 *                  capabilities of the local device for the Simple Pairing
 *                  process
 *
 * Parameters       bd_addr  - The peer device
 *                  *p_io_cap - The local Input/Output capabilities
 *                  *p_oob_data - true, if OOB data is available for the peer
 *                                device.
 *                  *p_auth_req - true, if MITM protection is required.
 *
 * Returns          void.
 *
 ******************************************************************************/
void bta_dm_co_io_req(const RawAddress& bd_addr, tBTM_IO_CAP* p_io_cap,
                      tBTM_OOB_DATA* p_oob_data, tBTM_AUTH_REQ* p_auth_req,
                      bool is_orig);

/*******************************************************************************
 *
 * Function         bta_dm_co_io_rsp
 *
 * Description      This callout function is executed by DM to report IO
 *                  capabilities of the peer device for the Simple Pairing
 *                  process
 *
 * Parameters       bd_addr  - The peer device
 *                  io_cap - The remote Input/Output capabilities
 *                  oob_data - true, if OOB data is available for the peer
 *                             device.
 *                  auth_req - true, if MITM protection is required.
 *
 * Returns          void.
 *
 ******************************************************************************/
void bta_dm_co_io_rsp(const RawAddress& bd_addr, tBTM_IO_CAP io_cap,
                      tBTM_OOB_DATA oob_data, tBTM_AUTH_REQ auth_req);

/*******************************************************************************
 *
 * Function         bta_dm_co_loc_oob
 *
 * Description      This callout function is executed by DM to report the OOB
 *                  data of the local device for the Simple Pairing process
 *
 * Parameters       valid - true, if the local OOB data is retrieved from LM
 *                  c     - Simple Pairing Hash C
 *                  r     - Simple Pairing Randomnizer R
 *
 * Returns          void.
 *
 ******************************************************************************/
void bta_dm_co_loc_oob(bool valid, const Octet16& c, const Octet16& r);

/*******************************************************************************
 *
 * Function         bta_dm_co_rmt_oob
 *
 * Description      This callout function is executed by DM to request the OOB
 *                  data for the remote device for the Simple Pairing process
 *
 * Parameters       bd_addr  - The peer device
 *
 * Returns          void.
 *
 ******************************************************************************/
void bta_dm_co_rmt_oob(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         bta_dm_sco_co_open
 *
 * Description      This function is executed when a SCO connection is open.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_dm_sco_co_open(uint16_t handle, uint8_t pkt_size, uint16_t event);

/*******************************************************************************
 *
 * Function         bta_dm_sco_co_close
 *
 * Description      This function is called when a SCO connection is closed
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_dm_sco_co_close(void);

/*******************************************************************************
 *
 * Function         bta_dm_sco_co_out_data
 *
 * Description      This function is called to send SCO data over HCI.
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_dm_sco_co_out_data(BT_HDR** p_buf);

/*******************************************************************************
 *
 * Function         bta_dm_sco_co_in_data
 *
 * Description      This function is called to send incoming SCO data to
 *                  application.
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_dm_sco_co_in_data(BT_HDR* p_buf, tBTM_SCO_DATA_FLAG status);

/*******************************************************************************
 *
 * Function         bta_dm_co_ble_io_req
 *
 * Description      This callout function is executed by DM to get BLE IO
 *                  capabilities before SMP pairing gets going.
 *
 * Parameters       bd_addr  - The peer device
 *                  *p_io_cap - The local Input/Output capabilities
 *                  *p_oob_data - true, if OOB data is available for the peer
 *                                device.
 *                  *p_auth_req -  Auth request setting (Bonding and MITM
 *                                                       required or not)
 *                  *p_max_key_size - max key size local device supported.
 *                  *p_init_key - initiator keys.
 *                  *p_resp_key - responder keys.
 *
 * Returns          void.
 *
 ******************************************************************************/
void bta_dm_co_ble_io_req(const RawAddress& bd_addr, tBTM_IO_CAP* p_io_cap,
                          tBTM_OOB_DATA* p_oob_data,
                          tBTM_LE_AUTH_REQ* p_auth_req, uint8_t* p_max_key_size,
                          tBTM_LE_KEY_TYPE* p_init_key,
                          tBTM_LE_KEY_TYPE* p_resp_key);

/*******************************************************************************
 *
 * Function         bta_dm_co_ble_local_key_reload
 *
 * Description      This callout function is to load the local BLE keys if
 *                  available on the device.
 *
 * Parameters       none
 *
 * Returns          void.
 *
 ******************************************************************************/
void bta_dm_co_ble_load_local_keys(tBTA_DM_BLE_LOCAL_KEY_MASK* p_key_mask,
                                   Octet16* p_er,
                                   tBTA_BLE_LOCAL_ID_KEYS* p_id_keys);

#endif /* BTA_DM_CO_H */
