/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

package com.android.bluetooth.le_audio;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothLeAudioCodecConfig;
import android.bluetooth.BluetoothLeAudioCodecStatus;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothLeAudioCallback;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.BluetoothProfileConnectionInfo;
import android.os.ParcelUuid;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.R;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.storage.DatabaseManager;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class LeAudioServiceTest {
    private static final int ASYNC_CALL_TIMEOUT_MILLIS = 250;
    private static final int TIMEOUT_MS = 1000;
    private static final int MAX_LE_AUDIO_CONNECTIONS = 5;
    private static final int LE_AUDIO_GROUP_ID_INVALID = -1;

    private BluetoothAdapter mAdapter;
    private Context mTargetContext;
    private LeAudioService mService;
    private BluetoothDevice mLeftDevice;
    private BluetoothDevice mRightDevice;
    private BluetoothDevice mSingleDevice;
    private HashSet<BluetoothDevice> mBondedDevices = new HashSet<>();
    private HashMap<BluetoothDevice, LinkedBlockingQueue<Intent>> mDeviceQueueMap;
    private LinkedBlockingQueue<Intent> mGroupIntentQueue = new LinkedBlockingQueue<>();
    private int testGroupId = 1;
    private boolean onGroupStatusCallbackCalled = false;
    private boolean onGroupCodecConfChangedCallbackCalled = false;
    private BluetoothLeAudioCodecStatus testCodecStatus = null;

    private BroadcastReceiver mLeAudioIntentReceiver;

    @Mock private AdapterService mAdapterService;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private LeAudioNativeInterface mNativeInterface;
    @Mock private AudioManager mAudioManager;
    @Mock private LeAudioTmapGattServer mTmapGattServer;
    @Spy private LeAudioObjectsFactory mObjectsFactory = LeAudioObjectsFactory.getInstance();


    @Rule public final ServiceTestRule mServiceRule = new ServiceTestRule();

    private static final BluetoothLeAudioCodecConfig LC3_16KHZ_CONFIG =
            new BluetoothLeAudioCodecConfig.Builder()
                .setCodecType(BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3)
                .setSampleRate(BluetoothLeAudioCodecConfig.SAMPLE_RATE_16000)
                .build();
    private static final BluetoothLeAudioCodecConfig LC3_48KHZ_CONFIG =
            new BluetoothLeAudioCodecConfig.Builder()
                .setCodecType(BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3)
                .setSampleRate(BluetoothLeAudioCodecConfig.SAMPLE_RATE_48000)
                .build();

    private static final BluetoothLeAudioCodecConfig LC3_48KHZ_16KHZ_CONFIG =
             new BluetoothLeAudioCodecConfig.Builder()
               .setCodecType(BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3)
               .setSampleRate(BluetoothLeAudioCodecConfig.SAMPLE_RATE_48000
                                | BluetoothLeAudioCodecConfig.SAMPLE_RATE_16000)
               .build();

    private static final List<BluetoothLeAudioCodecConfig> INPUT_CAPABILITIES_CONFIG =
            new ArrayList() {{
                    add(LC3_48KHZ_16KHZ_CONFIG);
            }};

    private static final List<BluetoothLeAudioCodecConfig> OUTPUT_CAPABILITIES_CONFIG =
            new ArrayList() {{
                    add(LC3_48KHZ_16KHZ_CONFIG);
            }};

    private static final List<BluetoothLeAudioCodecConfig> INPUT_SELECTABLE_CONFIG =
            new ArrayList() {{
                    add(LC3_16KHZ_CONFIG);
             }};

    private static final List<BluetoothLeAudioCodecConfig> OUTPUT_SELECTABLE_CONFIG =
            new ArrayList() {{
                    add(LC3_48KHZ_16KHZ_CONFIG);
            }};

    @Before
    public void setUp() throws Exception {
        mTargetContext = InstrumentationRegistry.getTargetContext();

        // Set up mocks and test assets
        MockitoAnnotations.initMocks(this);

        // Use spied objects factory
        doNothing().when(mTmapGattServer).start(anyInt());
        doNothing().when(mTmapGattServer).stop();
        LeAudioObjectsFactory.setInstanceForTesting(mObjectsFactory);
        doReturn(mTmapGattServer).when(mObjectsFactory).getTmapGattServer(any());

        TestUtils.setAdapterService(mAdapterService);
        doReturn(MAX_LE_AUDIO_CONNECTIONS).when(mAdapterService).getMaxConnectedAudioDevices();
        doReturn(new ParcelUuid[]{BluetoothUuid.LE_AUDIO}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        doReturn(true, false).when(mAdapterService).isStartedProfile(anyString());

        BluetoothManager manager = mTargetContext.getSystemService(BluetoothManager.class);
        assertThat(manager).isNotNull();
        mAdapter = manager.getAdapter();
        // Mock methods in AdapterService
        doAnswer(invocation -> mBondedDevices.toArray(new BluetoothDevice[]{})).when(
                mAdapterService).getBondedDevices();

        startService();
        mService.mLeAudioNativeInterface = mNativeInterface;
        mService.mAudioManager = mAudioManager;

        LeAudioStackEvent stackEvent =
        new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_NATIVE_INITIALIZED);
        mService.messageFromNative(stackEvent);
        assertThat(mService.mLeAudioNativeIsInitialized).isTrue();

        // Override the timeout value to speed up the test
        LeAudioStateMachine.sConnectTimeoutMs = TIMEOUT_MS;    // 1s

        mGroupIntentQueue = new LinkedBlockingQueue<>();

        // Set up the Connection State Changed receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED);
        mLeAudioIntentReceiver = new LeAudioIntentReceiver();
        mTargetContext.registerReceiver(mLeAudioIntentReceiver, filter);

        doAnswer(invocation -> mBondedDevices.toArray(new BluetoothDevice[]{})).when(
                mAdapterService).getBondedDevices();

        // Get a device for testing
        mLeftDevice = TestUtils.getTestDevice(mAdapter, 0);
        mRightDevice = TestUtils.getTestDevice(mAdapter, 1);
        mSingleDevice = TestUtils.getTestDevice(mAdapter, 2);
        mDeviceQueueMap = new HashMap<>();
        mDeviceQueueMap.put(mLeftDevice, new LinkedBlockingQueue<>());
        mDeviceQueueMap.put(mRightDevice, new LinkedBlockingQueue<>());
        mDeviceQueueMap.put(mSingleDevice, new LinkedBlockingQueue<>());
        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService)
                .getBondState(any(BluetoothDevice.class));
        doReturn(new ParcelUuid[]{BluetoothUuid.LE_AUDIO}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
    }

    @After
    public void tearDown() throws Exception {
        mBondedDevices.clear();
        mGroupIntentQueue.clear();
        stopService();
        mTargetContext.unregisterReceiver(mLeAudioIntentReceiver);
        mDeviceQueueMap.clear();
        TestUtils.clearAdapterService(mAdapterService);
    }

    private void startService() throws TimeoutException {
        TestUtils.startService(mServiceRule, LeAudioService.class);
        mService = LeAudioService.getLeAudioService();
        assertThat(mService).isNotNull();
    }

    private void stopService() throws TimeoutException {
        TestUtils.stopService(mServiceRule, LeAudioService.class);
        mService = LeAudioService.getLeAudioService();
        assertThat(mService).isNull();
    }

    private class LeAudioIntentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED
                    .equals(intent.getAction())) {
                try {
                    BluetoothDevice device = intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE);
                    assertThat(device).isNotNull();
                    LinkedBlockingQueue<Intent> queue = mDeviceQueueMap.get(device);
                    assertThat(queue).isNotNull();
                    queue.put(intent);
                } catch (InterruptedException e) {
                    assertWithMessage("Cannot add Intent to the Connection State queue: "
                            + e.getMessage()).fail();
                }
            }
            if (BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED.equals(intent.getAction())) {
                try {
                    BluetoothDevice device = intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE);
                    if (device != null) {
                        LinkedBlockingQueue<Intent> queue = mDeviceQueueMap.get(device);
                        assertThat(queue).isNotNull();
                        queue.put(intent);
                    }
                } catch (InterruptedException e) {
                    assertWithMessage("Cannot add Le Audio Intent to the Connection State queue: "
                            + e.getMessage()).fail();
                }
            }
        }
    }

    private void verifyConnectionStateIntent(int timeoutMs, BluetoothDevice device,
            int newState, int prevState) {
        Intent intent = TestUtils.waitForIntent(timeoutMs, mDeviceQueueMap.get(device));
        assertThat(intent).isNotNull();
        assertThat(intent.getAction())
                .isEqualTo(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED);
        assertThat(device).isEqualTo(intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
        assertThat(newState).isEqualTo(intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1));
        assertThat(prevState).isEqualTo(intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE,
                -1));
    }

    /**
     * Test getting LeAudio Service: getLeAudioService()
     */
    @Test
    public void testGetLeAudioService() {
        assertThat(mService).isEqualTo(LeAudioService.getLeAudioService());
    }

    /**
     * Test stop LeAudio Service
     */
    @Test
    public void testStopLeAudioService() {
        // Prepare: connect
        connectDevice(mLeftDevice);
        // LeAudio Service is already running: test stop(). Note: must be done on the main thread
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            public void run() {
                assertThat(mService.stop()).isTrue();
            }
        });
    }

    /**
     * Test if stop during init is ok.
     */
    @Test
    public void testStopStartStopService() throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            public void run() {
                assertThat(mService.stop()).isTrue();
                assertThat(mService.start()).isTrue();
                assertThat(mService.stop()).isTrue();
                assertThat(mService.start()).isTrue();
            }
        });
    }

    /**
     * Test get/set priority for BluetoothDevice
     */
    @Test
    public void testGetSetPriority() {
        when(mDatabaseManager.getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_UNKNOWN);
        assertWithMessage("Initial device priority")
                .that(BluetoothProfile.CONNECTION_POLICY_UNKNOWN)
                .isEqualTo(mService.getConnectionPolicy(mLeftDevice));

        when(mDatabaseManager.getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        assertWithMessage("Setting device priority to PRIORITY_OFF")
                .that(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN)
                .isEqualTo(mService.getConnectionPolicy(mLeftDevice));

        when(mDatabaseManager.getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        assertWithMessage("Setting device priority to PRIORITY_ON")
                .that(BluetoothProfile.CONNECTION_POLICY_ALLOWED)
                .isEqualTo(mService.getConnectionPolicy(mLeftDevice));
    }

    /**
     *  Helper function to test okToConnect() method
     *
     *  @param device test device
     *  @param bondState bond state value, could be invalid
     *  @param priority value, could be invalid, could be invalid
     *  @param expected expected result from okToConnect()
     */
    private void testOkToConnectCase(BluetoothDevice device, int bondState, int priority,
            boolean expected) {
        doReturn(bondState).when(mAdapterService).getBondState(device);
        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.LE_AUDIO))
                .thenReturn(priority);
        assertThat(expected).isEqualTo(mService.okToConnect(device));
    }

    /**
     *  Test okToConnect method using various test cases
     */
    @Test
    public void testOkToConnect() {
        int badPriorityValue = 1024;
        int badBondState = 42;
        testOkToConnectCase(mSingleDevice,
                BluetoothDevice.BOND_NONE, BluetoothProfile.CONNECTION_POLICY_UNKNOWN, false);
        testOkToConnectCase(mSingleDevice,
                BluetoothDevice.BOND_NONE, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(mSingleDevice,
                BluetoothDevice.BOND_NONE, BluetoothProfile.CONNECTION_POLICY_ALLOWED, false);
        testOkToConnectCase(mSingleDevice,
                BluetoothDevice.BOND_NONE, badPriorityValue, false);
        testOkToConnectCase(mSingleDevice,
                BluetoothDevice.BOND_BONDING, BluetoothProfile.CONNECTION_POLICY_UNKNOWN, false);
        testOkToConnectCase(mSingleDevice,
                BluetoothDevice.BOND_BONDING, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(mSingleDevice,
                BluetoothDevice.BOND_BONDING, BluetoothProfile.CONNECTION_POLICY_ALLOWED, false);
        testOkToConnectCase(mSingleDevice,
                BluetoothDevice.BOND_BONDING, badPriorityValue, false);
        testOkToConnectCase(mSingleDevice,
                BluetoothDevice.BOND_BONDED, BluetoothProfile.CONNECTION_POLICY_UNKNOWN, true);
        testOkToConnectCase(mSingleDevice,
                BluetoothDevice.BOND_BONDED, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(mSingleDevice,
                BluetoothDevice.BOND_BONDED, BluetoothProfile.CONNECTION_POLICY_ALLOWED, true);
        testOkToConnectCase(mSingleDevice,
                BluetoothDevice.BOND_BONDED, badPriorityValue, false);
        testOkToConnectCase(mSingleDevice,
                badBondState, BluetoothProfile.CONNECTION_POLICY_UNKNOWN, false);
        testOkToConnectCase(mSingleDevice,
                badBondState, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(mSingleDevice,
                badBondState, BluetoothProfile.CONNECTION_POLICY_ALLOWED, false);
        testOkToConnectCase(mSingleDevice,
                badBondState, badPriorityValue, false);
    }

    /**
     * Test that an outgoing connection to device that does not have Le Audio UUID is rejected
     */
    @Test
    public void testOutgoingConnectMissingLeAudioUuid() {
        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        when(mDatabaseManager
                .getProfileConnectionPolicy(mRightDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        when(mDatabaseManager
                .getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));

        // Return No UUID
        doReturn(new ParcelUuid[]{}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        // Send a connect request
        assertWithMessage("Connect expected to fail").that(mService.connect(mLeftDevice)).isFalse();
    }

    /**
     * Test that an outgoing connection to device with PRIORITY_OFF is rejected
     */
    @Test
    public void testOutgoingConnectPriorityOff() {
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));

        // Set the device priority to PRIORITY_OFF so connect() should fail
        when(mDatabaseManager
                .getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);

        // Send a connect request
        assertWithMessage("Connect expected to fail").that(mService.connect(mLeftDevice)).isFalse();
    }

    /**
     * Test that an outgoing connection times out
     */
    @Test
    public void testOutgoingConnectTimeout() {
        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager
                .getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        when(mDatabaseManager
                .getProfileConnectionPolicy(mRightDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        when(mDatabaseManager
                .getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));

        // Send a connect request
        assertWithMessage("Connect failed").that(mService.connect(mLeftDevice)).isTrue();

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(TIMEOUT_MS, mLeftDevice, BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTING);

        // Verify the connection state broadcast, and that we are in Disconnected state
        verifyConnectionStateIntent(LeAudioStateMachine.sConnectTimeoutMs * 2,
                mLeftDevice, BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTED);
    }

    private void injectAndVerifyDeviceConnected(BluetoothDevice device) {
        generateConnectionMessageFromNative(device,
                LeAudioStackEvent.CONNECTION_STATE_CONNECTED,
                LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED);
    }

    private void injectNoVerifyDeviceConnected(BluetoothDevice device) {
        generateUnexpectedConnectionMessageFromNative(device,
                LeAudioStackEvent.CONNECTION_STATE_CONNECTED,
                LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED);
    }

    private void injectAndVerifyDeviceDisconnected(BluetoothDevice device) {
        generateConnectionMessageFromNative(device,
                LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED,
                LeAudioStackEvent.CONNECTION_STATE_CONNECTED);
    }

    private void injectNoVerifyDeviceDisconnected(BluetoothDevice device) {
        generateUnexpectedConnectionMessageFromNative(device,
                LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED,
                LeAudioStackEvent.CONNECTION_STATE_CONNECTED);
    }
    /**
     * Test that the outgoing connect/disconnect and audio switch is successful.
     */
    @Test
    public void testAudioManagerConnectDisconnect() {
        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager
                .getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        when(mDatabaseManager
                .getProfileConnectionPolicy(mRightDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        when(mDatabaseManager
                .getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));

        // Send a connect request
        assertWithMessage("Connect failed").that(mService.connect(mLeftDevice)).isTrue();
        assertWithMessage("Connect failed").that(mService.connect(mRightDevice)).isTrue();

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(TIMEOUT_MS, mLeftDevice, BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTING);
        verifyConnectionStateIntent(TIMEOUT_MS, mRightDevice, BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mRightDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTING);

        LeAudioStackEvent connCompletedEvent;
        // Send a message to trigger connection completed
        connCompletedEvent = new LeAudioStackEvent(
                LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mLeftDevice;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(connCompletedEvent);

        // Verify the connection state broadcast, and that we are in Connected state
        verifyConnectionStateIntent(TIMEOUT_MS, mLeftDevice, BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTED);

        // Send a message to trigger connection completed for right side
        connCompletedEvent = new LeAudioStackEvent(
                LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mRightDevice;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(connCompletedEvent);

        // Verify the connection state broadcast, and that we are in Connected state for right side
        verifyConnectionStateIntent(TIMEOUT_MS, mRightDevice, BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        assertThat(mService.getConnectionState(mRightDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTED);

        // Verify the list of connected devices
        assertThat(mService.getConnectedDevices().contains(mLeftDevice)).isTrue();
        assertThat(mService.getConnectedDevices().contains(mRightDevice)).isTrue();

        // Send a disconnect request
        assertWithMessage("Disconnect failed").that(mService.disconnect(mLeftDevice)).isTrue();
        assertWithMessage("Disconnect failed").that(mService.disconnect(mRightDevice)).isTrue();

        // Verify the connection state broadcast, and that we are in Disconnecting state
        verifyConnectionStateIntent(TIMEOUT_MS, mLeftDevice, BluetoothProfile.STATE_DISCONNECTING,
                BluetoothProfile.STATE_CONNECTED);
        assertThat(BluetoothProfile.STATE_DISCONNECTING)
                .isEqualTo(mService.getConnectionState(mLeftDevice));
        verifyConnectionStateIntent(TIMEOUT_MS, mRightDevice, BluetoothProfile.STATE_DISCONNECTING,
                BluetoothProfile.STATE_CONNECTED);
        assertThat(BluetoothProfile.STATE_DISCONNECTING)
                .isEqualTo(mService.getConnectionState(mRightDevice));

        // Send a message to trigger disconnection completed
        connCompletedEvent = new LeAudioStackEvent(
                LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mLeftDevice;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED;
        mService.messageFromNative(connCompletedEvent);

        // Verify the connection state broadcast, and that we are in Disconnected state
        verifyConnectionStateIntent(TIMEOUT_MS, mLeftDevice, BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_DISCONNECTING);
        assertThat(BluetoothProfile.STATE_DISCONNECTED)
                .isEqualTo(mService.getConnectionState(mLeftDevice));

        // Send a message to trigger disconnection completed to the right device
        connCompletedEvent = new LeAudioStackEvent(
                LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mRightDevice;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED;
        mService.messageFromNative(connCompletedEvent);

        // Verify the connection state broadcast, and that we are in Disconnected state
        verifyConnectionStateIntent(TIMEOUT_MS, mRightDevice, BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_DISCONNECTING);
        assertThat(BluetoothProfile.STATE_DISCONNECTED)
                .isEqualTo(mService.getConnectionState(mRightDevice));

        // Verify the list of connected devices
        assertThat(mService.getConnectedDevices().contains(mLeftDevice)).isFalse();
        assertThat(mService.getConnectedDevices().contains(mRightDevice)).isFalse();
    }

    /**
     * Test that only CONNECTION_STATE_CONNECTED or CONNECTION_STATE_CONNECTING Le Audio stack
     * events will create a state machine.
     */
    @Test
    public void testCreateStateMachineStackEvents() {
        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager
                .getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        when(mDatabaseManager
                .getProfileConnectionPolicy(mRightDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        when(mDatabaseManager
                .getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));

        // Le Audio stack event: CONNECTION_STATE_CONNECTING - state machine should be created
        generateConnectionMessageFromNative(mLeftDevice, BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(BluetoothProfile.STATE_CONNECTING)
                .isEqualTo(mService.getConnectionState(mLeftDevice));
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_DISCONNECTED - state machine should be removed
        generateConnectionMessageFromNative(mLeftDevice, BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        assertThat(BluetoothProfile.STATE_DISCONNECTED)
                .isEqualTo(mService.getConnectionState(mLeftDevice));
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_NONE);
        assertThat(mService.getDevices().contains(mLeftDevice)).isFalse();

        // stack event: CONNECTION_STATE_CONNECTED - state machine should be created
        generateConnectionMessageFromNative(mLeftDevice, BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(BluetoothProfile.STATE_CONNECTED)
                .isEqualTo(mService.getConnectionState(mLeftDevice));
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // stack event: CONNECTION_STATE_DISCONNECTED - state machine should be removed
        generateConnectionMessageFromNative(mLeftDevice, BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTED);
        assertThat(BluetoothProfile.STATE_DISCONNECTED)
                .isEqualTo(mService.getConnectionState(mLeftDevice));
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_NONE);
        assertThat(mService.getDevices().contains(mLeftDevice)).isFalse();

        // stack event: CONNECTION_STATE_DISCONNECTING - state machine should not be created
        generateUnexpectedConnectionMessageFromNative(mLeftDevice,
                BluetoothProfile.STATE_DISCONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(BluetoothProfile.STATE_DISCONNECTED)
                .isEqualTo(mService.getConnectionState(mLeftDevice));
        assertThat(mService.getDevices().contains(mLeftDevice)).isFalse();

        // stack event: CONNECTION_STATE_DISCONNECTED - state machine should not be created
        generateUnexpectedConnectionMessageFromNative(mLeftDevice,
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(BluetoothProfile.STATE_DISCONNECTED)
                .isEqualTo(mService.getConnectionState(mLeftDevice));
        assertThat(mService.getDevices().contains(mLeftDevice)).isFalse();
    }

    /**
     * Test that a state machine in DISCONNECTED state is removed only after the device is unbond.
     */
    @Test
    public void testDeleteStateMachineUnbondEvents() {
        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager
                .getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        when(mDatabaseManager
                .getProfileConnectionPolicy(mRightDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        when(mDatabaseManager
                .getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));

        // LeAudio stack event: CONNECTION_STATE_CONNECTING - state machine should be created
        generateConnectionMessageFromNative(mLeftDevice, BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(BluetoothProfile.STATE_CONNECTING)
                .isEqualTo(mService.getConnectionState(mLeftDevice));
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();
        // Device unbond - state machine is not removed
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_NONE);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_CONNECTED - state machine is not removed
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_BONDED);
        generateConnectionMessageFromNative(mLeftDevice, BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        assertThat(BluetoothProfile.STATE_CONNECTED)
                .isEqualTo(mService.getConnectionState(mLeftDevice));
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();
        // Device unbond - state machine is not removed
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_NONE);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_DISCONNECTING - state machine is not removed
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_BONDED);
        generateConnectionMessageFromNative(mLeftDevice, BluetoothProfile.STATE_DISCONNECTING,
                BluetoothProfile.STATE_CONNECTED);
        assertThat(BluetoothProfile.STATE_DISCONNECTING)
                .isEqualTo(mService.getConnectionState(mLeftDevice));
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();
        // Device unbond - state machine is not removed
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_NONE);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_DISCONNECTED - state machine is not removed
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_BONDED);
        generateConnectionMessageFromNative(mLeftDevice, BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_DISCONNECTING);
        assertThat(BluetoothProfile.STATE_DISCONNECTED)
                .isEqualTo(mService.getConnectionState(mLeftDevice));
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();
        // Device unbond - state machine is removed
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_NONE);
        assertThat(mService.getDevices().contains(mLeftDevice)).isFalse();
    }

    /**
     * Test that a CONNECTION_STATE_DISCONNECTED Le Audio stack event will remove the state
     * machine only if the device is unbond.
     */
    @Test
    public void testDeleteStateMachineDisconnectEvents() {
        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager
                .getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        when(mDatabaseManager
                .getProfileConnectionPolicy(mRightDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        when(mDatabaseManager
                .getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));

        // LeAudio stack event: CONNECTION_STATE_CONNECTING - state machine should be created
        generateConnectionMessageFromNative(mLeftDevice, BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(BluetoothProfile.STATE_CONNECTING)
                .isEqualTo(mService.getConnectionState(mLeftDevice));
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_DISCONNECTED - state machine is not removed
        generateConnectionMessageFromNative(mLeftDevice, BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        assertThat(BluetoothProfile.STATE_DISCONNECTED)
                .isEqualTo(mService.getConnectionState(mLeftDevice));
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_CONNECTING - state machine remains
        generateConnectionMessageFromNative(mLeftDevice, BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(BluetoothProfile.STATE_CONNECTING)
                .isEqualTo(mService.getConnectionState(mLeftDevice));
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // Device bond state marked as unbond - state machine is not removed
        doReturn(BluetoothDevice.BOND_NONE).when(mAdapterService)
                .getBondState(any(BluetoothDevice.class));
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_DISCONNECTED - state machine is removed
        generateConnectionMessageFromNative(mLeftDevice, BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        assertThat(BluetoothProfile.STATE_DISCONNECTED)
                .isEqualTo(mService.getConnectionState(mLeftDevice));
        assertThat(mService.getDevices().contains(mLeftDevice)).isFalse();
    }

    private void connectDevice(BluetoothDevice device) {
        LeAudioStackEvent connCompletedEvent;

        List<BluetoothDevice> prevConnectedDevices = mService.getConnectedDevices();

        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectLeAudio(device);
        doReturn(true).when(mNativeInterface).disconnectLeAudio(device);

        // Send a connect request
        assertWithMessage("Connect failed").that(mService.connect(device)).isTrue();

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(TIMEOUT_MS, device, BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(BluetoothProfile.STATE_CONNECTING)
                .isEqualTo(mService.getConnectionState(device));

        // Send a message to trigger connection completed
        connCompletedEvent = new LeAudioStackEvent(
                LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = device;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(connCompletedEvent);

        // Verify the connection state broadcast, and that we are in Connected state
        verifyConnectionStateIntent(TIMEOUT_MS, device, BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        assertThat(BluetoothProfile.STATE_CONNECTED)
                .isEqualTo(mService.getConnectionState(device));

        // Verify that the device is in the list of connected devices
        assertThat(mService.getConnectedDevices().contains(device)).isTrue();
        // Verify the list of previously connected devices
        for (BluetoothDevice prevDevice : prevConnectedDevices) {
            assertThat(mService.getConnectedDevices().contains(prevDevice)).isTrue();
        }
    }

    private void generateConnectionMessageFromNative(BluetoothDevice device, int newConnectionState,
            int oldConnectionState) {
        LeAudioStackEvent stackEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt1 = newConnectionState;
        mService.messageFromNative(stackEvent);
        // Verify the connection state broadcast
        verifyConnectionStateIntent(TIMEOUT_MS, device, newConnectionState, oldConnectionState);
    }

    private void generateUnexpectedConnectionMessageFromNative(BluetoothDevice device,
            int newConnectionState, int oldConnectionState) {
        LeAudioStackEvent stackEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt1 = newConnectionState;
        mService.messageFromNative(stackEvent);
        // Verify the connection state broadcast
        verifyNoConnectionStateIntent(TIMEOUT_MS, device);
    }

    private void verifyNoConnectionStateIntent(int timeoutMs, BluetoothDevice device) {
        Intent intent = TestUtils.waitForNoIntent(timeoutMs, mDeviceQueueMap.get(device));
        assertThat(intent).isNull();
    }

    /**
     * Test setting connection policy
     */
    @Test
    public void testSetConnectionPolicy() {
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mDatabaseManager).setProfileConnectionPolicy(any(BluetoothDevice.class),
                anyInt(), anyInt());
        when(mDatabaseManager.getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_UNKNOWN);

        assertThat(mService.setConnectionPolicy(mSingleDevice,
                BluetoothProfile.CONNECTION_POLICY_ALLOWED)).isTrue();

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(TIMEOUT_MS, mSingleDevice, BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(BluetoothProfile.STATE_CONNECTING)
                .isEqualTo(mService.getConnectionState(mSingleDevice));

        LeAudioStackEvent connCompletedEvent;
        // Send a message to trigger connection completed
        connCompletedEvent = new LeAudioStackEvent(
                LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mSingleDevice;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(connCompletedEvent);

        // Verify the connection state broadcast, and that we are in Connected state
        verifyConnectionStateIntent(TIMEOUT_MS, mSingleDevice, BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        assertThat(BluetoothProfile.STATE_CONNECTED)
                .isEqualTo(mService.getConnectionState(mSingleDevice));

        // Set connection policy to forbidden
        assertThat(mService.setConnectionPolicy(mSingleDevice,
                BluetoothProfile.CONNECTION_POLICY_FORBIDDEN)).isTrue();

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(TIMEOUT_MS, mSingleDevice, BluetoothProfile.STATE_DISCONNECTING,
                BluetoothProfile.STATE_CONNECTED);
        assertThat(BluetoothProfile.STATE_DISCONNECTING)
                .isEqualTo(mService.getConnectionState(mSingleDevice));

        // Send a message to trigger disconnection completed
        connCompletedEvent = new LeAudioStackEvent(
                LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mSingleDevice;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED;
        mService.messageFromNative(connCompletedEvent);

        // Verify the connection state broadcast, and that we are in Disconnected state
        verifyConnectionStateIntent(TIMEOUT_MS, mSingleDevice, BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_DISCONNECTING);
        assertThat(BluetoothProfile.STATE_DISCONNECTED)
                .isEqualTo(mService.getConnectionState(mSingleDevice));
    }

    /**
     *  Helper function to connect Test device
     *
     *  @param device test device
     */
    private void connectTestDevice(BluetoothDevice device, int GroupId) {
        List<BluetoothDevice> prevConnectedDevices = mService.getConnectedDevices();

        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_UNKNOWN);
        // Send a connect request
        assertWithMessage("Connect failed").that(mService.connect(device)).isTrue();

        // Make device bonded
        mBondedDevices.add(device);

        // Wait ASYNC_CALL_TIMEOUT_MILLIS for state to settle, timing is also tested here and
        // 250ms for processing two messages should be way more than enough. Anything that breaks
        // this indicate some breakage in other part of Android OS

        verifyConnectionStateIntent(ASYNC_CALL_TIMEOUT_MILLIS, device,
                BluetoothProfile.STATE_CONNECTING, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(BluetoothProfile.STATE_CONNECTING)
                .isEqualTo(mService.getConnectionState(device));

        // Use connected event to indicate that device is connected
        LeAudioStackEvent connCompletedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = device;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(connCompletedEvent);

        verifyConnectionStateIntent(ASYNC_CALL_TIMEOUT_MILLIS, device,
                BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING);

        assertThat(BluetoothProfile.STATE_CONNECTED)
                .isEqualTo(mService.getConnectionState(device));

        LeAudioStackEvent nodeGroupAdded =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED);
        nodeGroupAdded.device = device;
        nodeGroupAdded.valueInt1 = GroupId;
        nodeGroupAdded.valueInt2 = LeAudioStackEvent.GROUP_NODE_ADDED;
        mService.messageFromNative(nodeGroupAdded);

        // Verify that the device is in the list of connected devices
        assertThat(mService.getConnectedDevices().contains(device)).isTrue();
        // Verify the list of previously connected devices
        for (BluetoothDevice prevDevice : prevConnectedDevices) {
                assertThat(mService.getConnectedDevices().contains(prevDevice)).isTrue();
        }
   }

    /**
     * Test matching connection state devices.
     */
    @Test
    public void testGetDevicesMatchingConnectionState() {
        // Update the device priority so okToConnect() returns true
        doReturn(new ParcelUuid[]{BluetoothUuid.LE_AUDIO}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        doReturn(new BluetoothDevice[]{mSingleDevice}).when(mAdapterService).getBondedDevices();
        when(mDatabaseManager
                .getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));

        connectTestDevice(mSingleDevice, testGroupId);
    }

    /**
     * Test adding node
     */
    @Test
    public void testGroupAddRemoveNode() {
        int groupId = 1;

        doReturn(true).when(mNativeInterface).groupAddNode(groupId, mSingleDevice);
        doReturn(true).when(mNativeInterface).groupRemoveNode(groupId, mSingleDevice);

        assertThat(mService.groupAddNode(groupId, mSingleDevice)).isTrue();
        assertThat(mService.groupRemoveNode(groupId, mSingleDevice)).isTrue();
    }

    /**
     * Test setting active device group
     */
    @Test
    public void testSetActiveDeviceGroup() {
        int groupId = 1;

        // Not connected device
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Connected device
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);
        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();

        // no active device
        assertThat(mService.setActiveDevice(null)).isTrue();
    }

    /**
     * Test getting active device
     */
    @Test
    public void testGetActiveDevices() {
        int groupId = 1;
        int direction = 1;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5;
        int nodeStatus = LeAudioStackEvent.GROUP_NODE_ADDED;
        int groupStatus = LeAudioStackEvent.GROUP_STATUS_ACTIVE;

        // Single active device
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        // Add device to group
        LeAudioStackEvent nodeStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED);
        nodeStatusChangedEvent.device = mSingleDevice;
        nodeStatusChangedEvent.valueInt1 = groupId;
        nodeStatusChangedEvent.valueInt2 = nodeStatus;
        mService.messageFromNative(nodeStatusChangedEvent);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();

        //Add location support
        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.device = mSingleDevice;
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        //Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.device = mSingleDevice;
        groupStatusChangedEvent.valueInt1 = groupId;
        groupStatusChangedEvent.valueInt2 = groupStatus;
        mService.messageFromNative(groupStatusChangedEvent);

        assertThat(mService.getActiveDevices().contains(mSingleDevice)).isTrue();
    }

    private void injectGroupStatusChange(int groupId, int groupStatus) {
        int eventType = LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED;
        LeAudioStackEvent groupStatusChangedEvent = new LeAudioStackEvent(eventType);
        groupStatusChangedEvent.valueInt1 = groupId;
        groupStatusChangedEvent.valueInt2 = groupStatus;
        mService.messageFromNative(groupStatusChangedEvent);
    }

    private void injectAudioConfChanged(int groupId, Integer availableContexts) {
        int direction = 1;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int eventType = LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED;

        // Add device to group
        LeAudioStackEvent audioConfChangedEvent = new LeAudioStackEvent(eventType);
        audioConfChangedEvent.device = mSingleDevice;
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);
    }
    /**
     * Test native interface audio configuration changed message handling
     */
    @Test
    public void testMessageFromNativeAudioConfChangedActiveGroup() {
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);
        injectAudioConfChanged(testGroupId, BluetoothLeAudio.CONTEXT_TYPE_MEDIA |
                         BluetoothLeAudio.CONTEXT_TYPE_COMMUNICATION);
        injectGroupStatusChange(testGroupId, BluetoothLeAudio.GROUP_STATUS_ACTIVE);

        String action = BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED;

        Intent intent = TestUtils.waitForIntent(TIMEOUT_MS, mDeviceQueueMap.get(mSingleDevice));
        assertThat(intent).isNotNull();
        assertThat(action).isEqualTo(intent.getAction());
        assertThat(mSingleDevice)
                .isEqualTo(intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
    }
    /**
     * Test native interface audio configuration changed message handling
     */
    @Test
    public void testMessageFromNativeAudioConfChangedInactiveGroup() {
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        String action = BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED;
        Integer contexts = BluetoothLeAudio.CONTEXT_TYPE_MEDIA |
        BluetoothLeAudio.CONTEXT_TYPE_COMMUNICATION;
        injectAudioConfChanged(testGroupId, contexts);

        Intent intent = TestUtils.waitForNoIntent(TIMEOUT_MS, mDeviceQueueMap.get(mSingleDevice));
        assertThat(intent).isNull();
    }
    /**
     * Test native interface audio configuration changed message handling
     */
    @Test
    public void testMessageFromNativeAudioConfChangedNoGroupChanged() {
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        String action = BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED;

        injectAudioConfChanged(testGroupId, 0);
        Intent intent = TestUtils.waitForNoIntent(TIMEOUT_MS, mDeviceQueueMap.get(mSingleDevice));
        assertThat(intent).isNull();
    }


    private void sendEventAndVerifyIntentForGroupStatusChanged(int groupId, int groupStatus) {

        onGroupStatusCallbackCalled = false;

        IBluetoothLeAudioCallback leAudioCallbacks =
        new IBluetoothLeAudioCallback.Stub() {
            @Override
            public void onCodecConfigChanged(int gid, BluetoothLeAudioCodecStatus status) {}
            @Override
            public void onGroupStatusChanged(int gid, int gStatus) {
                onGroupStatusCallbackCalled = true;
                assertThat(gid == groupId).isTrue();
                assertThat(gStatus == groupStatus).isTrue();
            }
            @Override
            public void onGroupNodeAdded(BluetoothDevice device, int gid) {}
            @Override
            public void onGroupNodeRemoved(BluetoothDevice device, int gid) {}
        };

        mService.mLeAudioCallbacks.register(leAudioCallbacks);

        injectGroupStatusChange(groupId, groupStatus);

        TestUtils.waitForLooperToFinishScheduledTask(mService.getMainLooper());
        assertThat(onGroupStatusCallbackCalled).isTrue();

        onGroupStatusCallbackCalled = false;
        mService.mLeAudioCallbacks.unregister(leAudioCallbacks);
    }

    /**
     * Test native interface group status message handling
     */
    @Test
    public void testMessageFromNativeGroupStatusChanged() {
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        injectAudioConfChanged(testGroupId, BluetoothLeAudio.CONTEXT_TYPE_MEDIA |
                                 BluetoothLeAudio.CONTEXT_TYPE_COMMUNICATION);

        sendEventAndVerifyIntentForGroupStatusChanged(testGroupId, LeAudioStackEvent.GROUP_STATUS_ACTIVE);
        sendEventAndVerifyIntentForGroupStatusChanged(testGroupId, LeAudioStackEvent.GROUP_STATUS_INACTIVE);
    }

    private void injectLocalCodecConfigCapaChanged(List<BluetoothLeAudioCodecConfig> inputCodecCapa,
                                                 List<BluetoothLeAudioCodecConfig> outputCodecCapa) {
        int eventType = LeAudioStackEvent.EVENT_TYPE_AUDIO_LOCAL_CODEC_CONFIG_CAPA_CHANGED;

        // Add device to group
        LeAudioStackEvent localCodecCapaEvent = new LeAudioStackEvent(eventType);
        localCodecCapaEvent.valueCodecList1 = inputCodecCapa;
        localCodecCapaEvent.valueCodecList2 =  outputCodecCapa;
        mService.messageFromNative(localCodecCapaEvent);
    }

    private void injectGroupCodecConfigChanged(int groupId, BluetoothLeAudioCodecConfig inputCodecConfig,
                                BluetoothLeAudioCodecConfig outputCodecConfig,
                                List<BluetoothLeAudioCodecConfig> inputSelectableCodecConfig,
                                List<BluetoothLeAudioCodecConfig> outputSelectableCodecConfig) {
        int eventType = LeAudioStackEvent.EVENT_TYPE_AUDIO_GROUP_CODEC_CONFIG_CHANGED;

        // Add device to group
        LeAudioStackEvent groupCodecConfigChangedEvent = new LeAudioStackEvent(eventType);
        groupCodecConfigChangedEvent.valueInt1 = groupId;
        groupCodecConfigChangedEvent.valueCodec1 = inputCodecConfig;
        groupCodecConfigChangedEvent.valueCodec2 = outputCodecConfig;
        groupCodecConfigChangedEvent.valueCodecList1 = inputSelectableCodecConfig;
        groupCodecConfigChangedEvent.valueCodecList2 =  outputSelectableCodecConfig;
        mService.messageFromNative(groupCodecConfigChangedEvent);
    }

    /**
     * Test native interface group status message handling
     */
    @Test
    public void testMessageFromNativeGroupCodecConfigChanged() {
        onGroupCodecConfChangedCallbackCalled = false;

        injectLocalCodecConfigCapaChanged(INPUT_CAPABILITIES_CONFIG, OUTPUT_CAPABILITIES_CONFIG);

        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        testCodecStatus = new BluetoothLeAudioCodecStatus(LC3_16KHZ_CONFIG,
                                LC3_48KHZ_CONFIG, INPUT_CAPABILITIES_CONFIG,
                                OUTPUT_CAPABILITIES_CONFIG, INPUT_SELECTABLE_CONFIG,
                                OUTPUT_SELECTABLE_CONFIG);

        IBluetoothLeAudioCallback leAudioCallbacks =
        new IBluetoothLeAudioCallback.Stub() {
            @Override
            public void onCodecConfigChanged(int gid, BluetoothLeAudioCodecStatus status) {
                onGroupCodecConfChangedCallbackCalled = true;
                assertThat(status.equals(testCodecStatus)).isTrue();
            }
            @Override
            public void onGroupStatusChanged(int gid, int gStatus) {}
            @Override
            public void onGroupNodeAdded(BluetoothDevice device, int gid) {}
            @Override
            public void onGroupNodeRemoved(BluetoothDevice device, int gid) {}
        };

        mService.mLeAudioCallbacks.register(leAudioCallbacks);

        injectGroupCodecConfigChanged(testGroupId, LC3_16KHZ_CONFIG, LC3_48KHZ_CONFIG,
                                        INPUT_SELECTABLE_CONFIG,
                                        OUTPUT_SELECTABLE_CONFIG);


        TestUtils.waitForLooperToFinishScheduledTask(mService.getMainLooper());
        assertThat(onGroupCodecConfChangedCallbackCalled).isTrue();

        onGroupCodecConfChangedCallbackCalled = false;
        mService.mLeAudioCallbacks.unregister(leAudioCallbacks);
    }

    private void verifyActiveDeviceStateIntent(int timeoutMs, BluetoothDevice device) {
        Intent intent = TestUtils.waitForIntent(timeoutMs, mDeviceQueueMap.get(device));
        assertThat(intent).isNotNull();
        assertThat(intent.getAction())
                .isEqualTo(BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED);
        assertThat(device).isEqualTo(intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
    }

    /**
     * Test native interface group status message handling
     */
    @Test
    public void testLeadGroupDeviceDisconnects() {
        int groupId = 1;
        int direction = 1;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5;
        int nodeStatus = LeAudioStackEvent.GROUP_NODE_ADDED;
        int groupStatus = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        BluetoothDevice leadDevice;
        BluetoothDevice memberDevice = mLeftDevice;

        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mLeftDevice, groupId);
        connectTestDevice(mRightDevice, groupId);

        leadDevice = mService.getConnectedGroupLeadDevice(groupId);
        if (leadDevice == mLeftDevice) {
                memberDevice = mRightDevice;
        }

        assertThat(mService.setActiveDevice(leadDevice)).isTrue();

        //Add location support
        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        //Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = groupId;
        groupStatusChangedEvent.valueInt2 = groupStatus;
        mService.messageFromNative(groupStatusChangedEvent);

        assertThat(mService.getActiveDevices().contains(leadDevice)).isTrue();
        verify(mAudioManager, times(1)).handleBluetoothActiveDeviceChanged(eq(leadDevice), any(),
                        any(BluetoothProfileConnectionInfo.class));

        verifyActiveDeviceStateIntent(TIMEOUT_MS, leadDevice);
        injectNoVerifyDeviceDisconnected(leadDevice);

        // We should not change the audio device
        assertThat(BluetoothProfile.STATE_CONNECTED)
                .isEqualTo(mService.getConnectionState(leadDevice));

        injectAndVerifyDeviceDisconnected(memberDevice);

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(TIMEOUT_MS, leadDevice, BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTED);

        verify(mAudioManager, times(1)).handleBluetoothActiveDeviceChanged(any(), eq(leadDevice),
                any(BluetoothProfileConnectionInfo.class));

    }

    /**
     * Test native interface group status message handling
     */
    @Test
    public void testLeadGroupDeviceReconnects() {
        int groupId = 1;
        int direction = 1;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5;
        int nodeStatus = LeAudioStackEvent.GROUP_NODE_ADDED;
        int groupStatus = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        BluetoothDevice leadDevice;
        BluetoothDevice memberDevice = mLeftDevice;

        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mLeftDevice, groupId);
        connectTestDevice(mRightDevice, groupId);

        leadDevice = mService.getConnectedGroupLeadDevice(groupId);
        if (leadDevice == mLeftDevice) {
                memberDevice = mRightDevice;
        }

        assertThat(mService.setActiveDevice(leadDevice)).isTrue();

        //Add location support
        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        //Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = groupId;
        groupStatusChangedEvent.valueInt2 = groupStatus;
        mService.messageFromNative(groupStatusChangedEvent);

        assertThat(mService.getActiveDevices().contains(leadDevice)).isTrue();
        verify(mAudioManager, times(1)).handleBluetoothActiveDeviceChanged(eq(leadDevice), any(),
                        any(BluetoothProfileConnectionInfo.class));

        verifyActiveDeviceStateIntent(TIMEOUT_MS, leadDevice);
        /* We don't want to distribute DISCONNECTION event, instead will try to reconnect
         * (in native)
         */
        injectNoVerifyDeviceDisconnected(leadDevice);
        assertThat(BluetoothProfile.STATE_CONNECTED)
                .isEqualTo(mService.getConnectionState(leadDevice));

        /* Reconnect device, there should be no intent about that, as device was pretending
         * connected
         */
        injectNoVerifyDeviceConnected(leadDevice);

        injectAndVerifyDeviceDisconnected(memberDevice);
        injectAndVerifyDeviceDisconnected(leadDevice);

        verify(mAudioManager, times(1)).handleBluetoothActiveDeviceChanged(eq(null), eq(leadDevice),
                any(BluetoothProfileConnectionInfo.class));
    }
}
