/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.bluetooth.gatt;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.PeriodicAdvertisingParameters;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.SparseArray;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * ScanStats class helps keep track of information about scans
 * on a per application basis.
 * @hide
 */
/*package*/ class AppAdvertiseStats {
    private static final String TAG = AppAdvertiseStats.class.getSimpleName();

    static final DateFormat DATE_FORMAT = new SimpleDateFormat("MM-dd HH:mm:ss");

    static final String[] PHY_LE_STRINGS = {"LE_1M", "LE_2M", "LE_CODED"};

    // ContextMap here is needed to grab Apps and Connections
    ContextMap mContextMap;

    // GattService is needed to add scan event protos to be dumped later
    GattService mGattService;

    class AppAdvertiserData {
        public boolean includeDeviceName = false;
        public boolean includeTxPowerLevel = false;
        public SparseArray<byte[]> manufacturerData;
        public Map<ParcelUuid, byte[]> serviceData;
        public List<ParcelUuid> serviceUuids;
        AppAdvertiserData(boolean includeDeviceName, boolean includeTxPowerLevel,
                SparseArray<byte[]> manufacturerData, Map<ParcelUuid, byte[]> serviceData,
                List<ParcelUuid> serviceUuids) {
            this.includeDeviceName = includeDeviceName;
            this.includeTxPowerLevel = includeTxPowerLevel;
            this.manufacturerData = manufacturerData;
            this.serviceData = serviceData;
            this.serviceUuids = serviceUuids;
        }
    }

    class AppAdvertiserRecord {
        public long startTime = 0;
        public long stopTime = 0;
        public int duration = 0;
        public int maxExtendedAdvertisingEvents = 0;
        AppAdvertiserRecord(long startTime) {
            this.startTime = startTime;
        }
    }

    private int mAppUid;
    private String mAppName;
    private int mId;
    private boolean mAdvertisingEnabled = false;
    private boolean mPeriodicAdvertisingEnabled = false;
    private int mPrimaryPhy = BluetoothDevice.PHY_LE_1M;
    private int mSecondaryPhy = BluetoothDevice.PHY_LE_1M;
    private int mInterval = 0;
    private int mTxPowerLevel = 0;
    private boolean mLegacy = false;
    private boolean mAnonymous = false;
    private boolean mConnectable = false;
    private boolean mScannable = false;
    private AppAdvertiserData mAdvertisingData = null;
    private AppAdvertiserData mScanResponseData = null;
    private AppAdvertiserData mPeriodicAdvertisingData = null;
    private boolean mPeriodicIncludeTxPower = false;
    private int mPeriodicInterval = 0;
    public LinkedList<AppAdvertiserRecord> mAdvertiserRecords =
            new LinkedList<AppAdvertiserRecord>();

    AppAdvertiseStats(int appUid, int id, String name, ContextMap map, GattService service) {
        this.mAppUid = appUid;
        this.mId = id;
        this.mAppName = name;
        this.mContextMap = map;
        this.mGattService = service;
    }

    void recordAdvertiseStart(AdvertisingSetParameters parameters,
            AdvertiseData advertiseData, AdvertiseData scanResponse,
            PeriodicAdvertisingParameters periodicParameters, AdvertiseData periodicData,
            int duration, int maxExtAdvEvents) {
        mAdvertisingEnabled = true;
        AppAdvertiserRecord record = new AppAdvertiserRecord(SystemClock.elapsedRealtime());
        record.duration = duration;
        record.maxExtendedAdvertisingEvents = maxExtAdvEvents;
        mAdvertiserRecords.add(record);
        if (parameters != null) {
            mPrimaryPhy = parameters.getPrimaryPhy();
            mSecondaryPhy = parameters.getSecondaryPhy();
            mInterval = parameters.getInterval();
            mTxPowerLevel = parameters.getTxPowerLevel();
            mLegacy = parameters.isLegacy();
            mAnonymous = parameters.isAnonymous();
            mConnectable = parameters.isConnectable();
            mScannable = parameters.isScannable();
        }

        if (advertiseData != null) {
            mAdvertisingData = new AppAdvertiserData(advertiseData.getIncludeDeviceName(),
                    advertiseData.getIncludeTxPowerLevel(),
                    advertiseData.getManufacturerSpecificData(),
                    advertiseData.getServiceData(),
                    advertiseData.getServiceUuids());
        }

        if (scanResponse != null) {
            mScanResponseData = new AppAdvertiserData(scanResponse.getIncludeDeviceName(),
                    scanResponse.getIncludeTxPowerLevel(),
                    scanResponse.getManufacturerSpecificData(),
                    scanResponse.getServiceData(),
                    scanResponse.getServiceUuids());
        }

        if (periodicData != null) {
            mPeriodicAdvertisingData = new AppAdvertiserData(
                    periodicData.getIncludeDeviceName(),
                    periodicData.getIncludeTxPowerLevel(),
                    periodicData.getManufacturerSpecificData(),
                    periodicData.getServiceData(),
                    periodicData.getServiceUuids());
        }

        if (periodicParameters != null) {
            mPeriodicAdvertisingEnabled = true;
            mPeriodicIncludeTxPower = periodicParameters.getIncludeTxPower();
            mPeriodicInterval = periodicParameters.getInterval();
        }
    }

    void recordAdvertiseStop() {
        mAdvertisingEnabled = false;
        mPeriodicAdvertisingEnabled = false;
        AppAdvertiserRecord record = mAdvertiserRecords.getLast();
        record.stopTime = SystemClock.elapsedRealtime();
    }

    void enableAdvertisingSet(boolean enable, int duration, int maxExtAdvEvents) {
        AppAdvertiserRecord lastRecord = mAdvertiserRecords.getLast();
        if (mAdvertisingEnabled) {
            //if the advertisingSet have not been disabled, skip enabling.
            if (lastRecord.stopTime != 0) {
                AppAdvertiserRecord record = new AppAdvertiserRecord(SystemClock.elapsedRealtime());
                record.duration = duration;
                record.maxExtendedAdvertisingEvents = maxExtAdvEvents;
                mAdvertiserRecords.add(record);
                if (mAdvertiserRecords.size() > 5) {
                    mAdvertiserRecords.removeFirst();
                }
            }
        } else {
            if (lastRecord.stopTime == 0) {
                lastRecord.stopTime = SystemClock.elapsedRealtime();
            }
        }
    }

    void setAdvertisingData(AdvertiseData data) {
        if (mAdvertisingData == null) {
            mAdvertisingData = new AppAdvertiserData(data.getIncludeDeviceName(),
                    data.getIncludeTxPowerLevel(),
                    data.getManufacturerSpecificData(),
                    data.getServiceData(),
                    data.getServiceUuids());
        } else if (data != null) {
            mAdvertisingData.includeDeviceName = data.getIncludeDeviceName();
            mAdvertisingData.includeTxPowerLevel = data.getIncludeTxPowerLevel();
            mAdvertisingData.manufacturerData = data.getManufacturerSpecificData();
            mAdvertisingData.serviceData = data.getServiceData();
            mAdvertisingData.serviceUuids = data.getServiceUuids();
        }
    }

    void setScanResponseData(AdvertiseData data) {
        if (mScanResponseData == null) {
            mScanResponseData = new AppAdvertiserData(data.getIncludeDeviceName(),
                    data.getIncludeTxPowerLevel(),
                    data.getManufacturerSpecificData(),
                    data.getServiceData(),
                    data.getServiceUuids());
        } else if (data != null) {
            mScanResponseData.includeDeviceName = data.getIncludeDeviceName();
            mScanResponseData.includeTxPowerLevel = data.getIncludeTxPowerLevel();
            mScanResponseData.manufacturerData = data.getManufacturerSpecificData();
            mScanResponseData.serviceData = data.getServiceData();
            mScanResponseData.serviceUuids = data.getServiceUuids();
        }
    }

    void setAdvertisingParameters(AdvertisingSetParameters parameters) {
        if (parameters != null) {
            mPrimaryPhy = parameters.getPrimaryPhy();
            mSecondaryPhy = parameters.getSecondaryPhy();
            mInterval = parameters.getInterval();
            mTxPowerLevel = parameters.getTxPowerLevel();
            mLegacy = parameters.isLegacy();
            mAnonymous = parameters.isAnonymous();
            mConnectable = parameters.isConnectable();
            mScannable = parameters.isScannable();
        }
    }

    void setPeriodicAdvertisingParameters(PeriodicAdvertisingParameters parameters) {
        if (parameters != null) {
            mPeriodicIncludeTxPower = parameters.getIncludeTxPower();
            mPeriodicInterval = parameters.getInterval();
        }
    }

    void setPeriodicAdvertisingData(AdvertiseData data) {
        if (mPeriodicAdvertisingData == null) {
            mPeriodicAdvertisingData = new AppAdvertiserData(data.getIncludeDeviceName(),
                    data.getIncludeTxPowerLevel(),
                    data.getManufacturerSpecificData(),
                    data.getServiceData(),
                    data.getServiceUuids());
        } else if (data != null) {
            mPeriodicAdvertisingData.includeDeviceName = data.getIncludeDeviceName();
            mPeriodicAdvertisingData.includeTxPowerLevel = data.getIncludeTxPowerLevel();
            mPeriodicAdvertisingData.manufacturerData = data.getManufacturerSpecificData();
            mPeriodicAdvertisingData.serviceData = data.getServiceData();
            mPeriodicAdvertisingData.serviceUuids = data.getServiceUuids();
        }
    }

    void onPeriodicAdvertiseEnabled(boolean enable) {
        mPeriodicAdvertisingEnabled = enable;
    }

    void setId(int id) {
        this.mId = id;
    }

    private static String printByteArrayInHex(byte[] data) {
        final StringBuilder hex = new StringBuilder();
        for (byte b : data) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static void dumpAppAdvertiserData(StringBuilder sb, AppAdvertiserData advData) {
        sb.append("\n          └Include Device Name                          : "
                + advData.includeDeviceName);
        sb.append("\n          └Include Tx Power Level                       : "
                + advData.includeTxPowerLevel);

        if (advData.manufacturerData.size() > 0) {
            sb.append("\n          └Manufacturer Data                            : ");
            for (int i = 0; i < advData.manufacturerData.size(); i++) {
                sb.append("\n            [" + Integer.toHexString(advData.manufacturerData.keyAt(i))
                        + ", " + printByteArrayInHex(advData.manufacturerData.valueAt(i)) + "]");
            }
        }

        if (!advData.serviceData.isEmpty()) {
            sb.append("\n          └Service Data(UUID, length of data)           : ");
            for (ParcelUuid uuid : advData.serviceData.keySet()) {
                sb.append("\n            [" + uuid + ", "
                        + advData.serviceData.get(uuid).length + "]");
            }
        }

        if (!advData.serviceUuids.isEmpty()) {
            sb.append("\n          └Service Uuids                                : \n            "
                    + advData.serviceUuids.toString());
        }
    }

    private static String dumpPhyString(int phy) {
        if (phy > PHY_LE_STRINGS.length) {
            return Integer.toString(phy);
        } else {
            return PHY_LE_STRINGS[phy - 1];
        }
    }

    private static void dumpAppAdvertiseStats(StringBuilder sb, AppAdvertiseStats stats) {
        sb.append("\n      └Advertising:");
        sb.append("\n        └Interval(0.625ms)                              : "
                + stats.mInterval);
        sb.append("\n        └TX POWER(dbm)                                  : "
                + stats.mTxPowerLevel);
        sb.append("\n        └Primary Phy                                    : "
                + dumpPhyString(stats.mPrimaryPhy));
        sb.append("\n        └Secondary Phy                                  : "
                + dumpPhyString(stats.mSecondaryPhy));
        sb.append("\n        └Legacy                                         : "
                + stats.mLegacy);
        sb.append("\n        └Anonymous                                      : "
                + stats.mAnonymous);
        sb.append("\n        └Connectable                                    : "
                + stats.mConnectable);
        sb.append("\n        └Scannable                                      : "
                + stats.mScannable);

        if (stats.mAdvertisingData != null) {
            sb.append("\n        └Advertise Data:");
            dumpAppAdvertiserData(sb, stats.mAdvertisingData);
        }

        if (stats.mScanResponseData != null) {
            sb.append("\n        └Scan Response:");
            dumpAppAdvertiserData(sb, stats.mScanResponseData);
        }

        if (stats.mPeriodicInterval > 0) {
            sb.append("\n      └Periodic Advertising Enabled                     : "
                    + stats.mPeriodicAdvertisingEnabled);
            sb.append("\n        └Periodic Include TxPower                       : "
                    + stats.mPeriodicIncludeTxPower);
            sb.append("\n        └Periodic Interval(1.25ms)                      : "
                    + stats.mPeriodicInterval);
        }

        if (stats.mPeriodicAdvertisingData != null) {
            sb.append("\n        └Periodic Advertise Data:");
            dumpAppAdvertiserData(sb, stats.mPeriodicAdvertisingData);
        }

        sb.append("\n");
    }

    static void dumpToString(StringBuilder sb, AppAdvertiseStats stats) {
        long currentTime = System.currentTimeMillis();
        long currentRealTime = SystemClock.elapsedRealtime();

        sb.append("\n    " + stats.mAppName);
        sb.append("\n     Advertising ID                                     : "
                + stats.mId);
        for (int i = 0; i < stats.mAdvertiserRecords.size(); i++) {
            AppAdvertiserRecord record = stats.mAdvertiserRecords.get(i);
            Date timestamp = new Date(currentTime - currentRealTime
                    + record.startTime);

            sb.append("\n      " + (i + 1) + ":");
            sb.append("\n        └Start time                                     : "
                    + DATE_FORMAT.format(timestamp));
            if (record.stopTime == 0) {
                sb.append("\n        └Elapsed time                                   : "
                        + (currentRealTime - record.startTime) + "ms");
            } else {
                Date stopTimestamp = new Date(currentTime - currentRealTime
                        + record.stopTime);
                sb.append("\n        └Stop time                                      : "
                        + DATE_FORMAT.format(stopTimestamp));
            }
            sb.append("\n        └Duration(10ms unit)                            : "
                    + record.duration);
            sb.append("\n        └Maximum number of extended advertising events  : "
                    + record.maxExtendedAdvertisingEvents);
        }

        dumpAppAdvertiseStats(sb, stats);
    }
}
