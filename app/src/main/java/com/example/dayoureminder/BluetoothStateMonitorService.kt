package com.example.dayoureminder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Properties
import kotlin.text.isNullOrBlank

// Removed: import kotlin.io.path.name // This import was unused and confirmed unnecessary.

@SuppressLint("MissingPermission") // Permissions are checked explicitly before BLE operations.
class BluetoothStateMonitorService : Service() {

    //region Constants and Properties
    private val TAG = "BluetoothMonitorSvcBLE" // Logging Tag

    private var TARGET_DEVICE_ADDRESS: String? = null

    // Notification constants
    private val NOTIFICATION_CHANNEL_ID = "BluetoothMonitorChannelBLE"
    private val NOTIFICATION_ID = 3 // Unique ID for the foreground service notification

    // Bluetooth components
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null

    // Coroutine scope for background tasks like connection attempts and delays
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionAttemptJob: Job? = null // Job for managing connection timeout
    private var isAttemptingConnection = false  // Flag to prevent multiple concurrent connection attempts

    // BroadcastReceiver for Bluetooth adapter state changes
    private var adapterStateReceiver: BroadcastReceiver? = null

    // Retry mechanism constants and counter
    private val MAX_RECONNECT_ATTEMPTS = 150
    private var currentReconnectAttempts = 0
    private var hasGivenUpRetrying = false // Flag to indicate if we've stopped automatic retries
    //endregion

    //region BluetoothGattCallback
    /**
     * Callback for GATT events (connection state changes, services discovered, etc.).
     */
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            val deviceAddress = gatt?.device?.address
            // Use try-catch for device name as it can throw SecurityException if BLUETOOTH_CONNECT is missing
            val deviceName = try { gatt?.device?.name } catch (se: SecurityException) { null } ?: "DaYouReminder_BLE"

            Log.d(TAG, "onConnectionStateChange - ENTERED. Device: $deviceName($deviceAddress), Status: $status, NewState: $newState")

            // Ensure this callback is for our target device if a specific address is set
            if (TARGET_DEVICE_ADDRESS != null && deviceAddress != TARGET_DEVICE_ADDRESS) {
                Log.w(TAG, "onConnectionStateChange for unexpected device: $deviceAddress. Target is $TARGET_DEVICE_ADDRESS")
                return
            }

            isAttemptingConnection = false // Connection attempt has concluded (succeeded or failed)
            connectionAttemptJob?.cancel() // Cancel any pending connection timeout job

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "Successfully connected to $deviceName ($deviceAddress)")
                        publishStatusUpdate("Connecté à $deviceName (BLE)")
                        // Optional: Discover services if you need to interact with characteristics
                        // E.g., if (hasRequiredPermissions()) gatt?.discoverServices()

                        // Reset retry counter and 'give up' state on successful connection
                        currentReconnectAttempts = 0
                        hasGivenUpRetrying = false
                    } else {
                        // This case can happen if connection is established but immediately fails with an error status
                        Log.e(TAG, "Connected to $deviceName ($deviceAddress) but with error status: $status")
                        publishStatusUpdate("Erreur connexion $deviceName: $status")
                        closeGattInternal() // Clean up this problematic connection
                        handleFailedConnectionAttempt()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        // This typically means a graceful disconnect initiated by either side
                        Log.i(TAG, "Disconnected gracefully from $deviceName ($deviceAddress)")
                        publishStatusUpdate("Déconnecté de $deviceName (BLE).")
                    } else {
                        // Disconnection due to an error (e.g., link loss, timeout, device out of range)
                        Log.e(TAG, "Disconnected from $deviceName ($deviceAddress) with error. Status: $status")
                        publishStatusUpdate("Erreur/Déconnexion $deviceName (BLE): $status.")
                    }
                    closeGattInternal() // Clean up current GATT resources
                    handleFailedConnectionAttempt() // Attempt to reconnect or handle max retries
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            val deviceName = try { gatt?.device?.name } catch (se: SecurityException) { null } ?: "DaYouReminder_BLE"
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered for $deviceName (${gatt?.device?.address})")
                // TODO: Interact with characteristics if needed (e.g., read, write, subscribe)
            } else {
                Log.w(TAG, "Service discovery failed for $deviceName (${gatt?.device?.address}) with status: $status")
            }
        }
    }

    /**
     * Handles the logic after a connection attempt fails or a device disconnects.
     * Manages retry counts and the "give up" state.
     */
    private fun handleFailedConnectionAttempt() {
        if (hasGivenUpRetrying) {
            Log.w(TAG, "Already in 'given up' state. Not attempting further automatic reconnections.")
            // Status should already reflect that we've given up.
            return
        }

        currentReconnectAttempts++
        Log.d(TAG, "Reconnect attempt #$currentReconnectAttempts of $MAX_RECONNECT_ATTEMPTS")

        if (currentReconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached. Giving up automatic retries.")
            publishStatusUpdate("Échec reconnexion auto après $MAX_RECONNECT_ATTEMPTS essais. Surveillance passive.")
            hasGivenUpRetrying = true
            // Optional: Consider stopping the service here if desired: stopSelf()
            // For now, it remains running but passive. Manual restart (UI) or BT toggle needed to retry.
        } else {
            attemptReconnectAfterDelay() // Schedule the next attempt
        }
    }

    /**
     * Initiates a reconnection attempt after a delay, if not in "given up" state.
     */
    private fun attemptReconnectAfterDelay() {
        if (hasGivenUpRetrying) {
            Log.d(TAG, "In 'given up' state, skipping reconnect delay.")
            return
        }
        if (TARGET_DEVICE_ADDRESS == null) {
            Log.w(TAG, "attemptReconnectAfterDelay: No target device address set.")
            return
        }

        serviceScope.launch {
            publishStatusUpdate("Tentative de reconnexion ($currentReconnectAttempts/$MAX_RECONNECT_ATTEMPTS) dans 10s...")
            Log.d(TAG, "Will attempt to reconnect (#$currentReconnectAttempts) in 10 seconds to $TARGET_DEVICE_ADDRESS...")
            delay(10000L) // 10-second delay

            // Check conditions again after delay, before actually trying to connect
            if (!serviceScope.isActive) {
                Log.w(TAG, "Reconnect attempt aborted: Service scope is no longer active.")
            } else if (hasGivenUpRetrying) {
                Log.w(TAG, "Reconnect attempt aborted: Max retries reached or 'give up' state entered during delay.")
            } else if (bluetoothAdapter?.isEnabled == false) {
                Log.w(TAG, "Reconnect attempt aborted: Bluetooth is disabled.")
                publishStatusUpdate("Reconnexion annulée: Bluetooth désactivé.")
            } else {
                Log.i(TAG, "Re-attempting to connect to $TARGET_DEVICE_ADDRESS...")
                connectToTargetDevice()
            }
        }
    }

    /**
     * Loads the target BLE MAC address from the 'ble_config.properties' file in assets.
     */
    private fun getTargetDeviceAddressFromConfig(context: Context): String? {
        val properties = Properties()
        try {
            context.assets.open("ble_config.properties").use { inputStream ->
                properties.load(inputStream)
                val address = properties.getProperty("TARGET_BLE_MAC_ADDRESS")
                if (address == "YOUR_ESP32_MAC_ADDRESS_HERE" || address.isNullOrBlank()) {
                    Log.w(TAG, "Config file found, but TARGET_BLE_MAC_ADDRESS is placeholder or empty.")
                    return null
                }
                return address
            }
        } catch (e: IOException) {
            Log.e(TAG, "'ble_config.properties' not found in assets. Please create it from the template.", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error loading/parsing 'ble_config.properties'", e)
            return null
        }
    }
    //endregion

    //region Service Lifecycle Methods
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate - START")

        // Load target device address from config
        TARGET_DEVICE_ADDRESS = getTargetDeviceAddressFromConfig(this)

        if (TARGET_DEVICE_ADDRESS == null) {
            Log.e(TAG, "TARGET_DEVICE_ADDRESS could not be loaded from config. Service may not function correctly.")
            // Decide how to handle this:
            // - publishStatusUpdate("Erreur: Adresse MAC cible non configurée.")
            // - stopSelf() if it's critical
        } else {
            Log.i(TAG, "Target device address loaded from config: $TARGET_DEVICE_ADDRESS")
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported on this device. Stopping service.")
            publishStatusUpdate("Bluetooth non supporté")
            stopSelf()
            return
        }

        createNotificationChannel()
        registerAdapterStateReceiver() // Set up listener for Bluetooth ON/OFF events

        Log.d(TAG, "Service onCreate - END")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand - START. Intent Action: ${intent?.action}, Flags: $flags, StartId: $startId")

        // If service is (re)started explicitly and was in a 'given up' state, reset it.
        if (hasGivenUpRetrying) {
            Log.i(TAG, "Service (re)started, resetting 'give up' state and retry count.")
            hasGivenUpRetrying = false
            currentReconnectAttempts = 0
        }

        val initialStatus = "Initialisation surveillance BLE..."
        startForeground(NOTIFICATION_ID, createNotification(initialStatus))
        publishStatusUpdate(initialStatus)

        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Required BLE permissions missing. Stopping service.")
            publishStatusUpdate("Permissions Bluetooth manquantes.")
            stopSelf()
            return START_NOT_STICKY // Don't restart if permissions are missing.
        }

        if (bluetoothAdapter?.isEnabled == false) {
            Log.w(TAG, "Bluetooth is disabled. Waiting for it to be enabled (via BroadcastReceiver).")
            publishStatusUpdate("Bluetooth désactivé.")
            // START_STICKY will ensure service restarts if killed, receiver will handle BT enabling.
            return START_STICKY
        }

        // If we were in a 'given up' state, the flags are reset above,
        // so a new connection attempt will now proceed.
        if (hasGivenUpRetrying) { // This condition should ideally be false here due to reset above
            Log.w(TAG, "Service started, was in 'given up' state but should have been reset. Allowing new connection attempts.")
        }

        connectToTargetDevice() // Attempt initial connection
        return START_STICKY // If killed by system, restart the service.
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy - START")

        unregisterAdapterStateReceiver()
        publishStatusUpdate("Service BLE arrêté.") // Final status update

        serviceScope.cancel() // Cancel all coroutines to prevent leaks
        closeGattInternal()   // Ensure GATT is closed and resources are released

        // Remove the foreground service notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") // stopForeground(true) is deprecated but needed for older APIs
            stopForeground(true)
        }
        Log.d(TAG, "Service onDestroy - END")
    }

    override fun onBind(intent: Intent?): IBinder? = null // This is not a bound service
    //endregion

    //region Bluetooth Logic
    /**
     * Checks if the app has the required Bluetooth permissions based on Android SDK version.
     */
    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12 (API 31) and above
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            // Add Manifest.permission.BLUETOOTH_SCAN if your app performs BLE scanning (not just connecting)
            // && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else { // Pre-Android 12
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                    // ACCESS_FINE_LOCATION is often needed for discovering BLE devices on older versions
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Initiates connection to the target BLE device if not in "given up" state.
     */
    private fun connectToTargetDevice() {
        if (hasGivenUpRetrying) {
            Log.i(TAG, "ConnectToTargetDevice: In 'given up' state. Not initiating new connection.")
            // The status should already reflect the "given up" state.
            return
        }

        if (TARGET_DEVICE_ADDRESS == null || !BluetoothAdapter.checkBluetoothAddress(TARGET_DEVICE_ADDRESS)) {
            Log.e(TAG, "Invalid or null TARGET_DEVICE_ADDRESS: '$TARGET_DEVICE_ADDRESS'. Cannot connect.")
            publishStatusUpdate("Adresse MAC BLE cible invalide/manquante.")
            // Not treating this as a retryable device failure, as it's a configuration issue.
            return
        }
        if (bluetoothAdapter?.isEnabled == false) {
            Log.w(TAG, "connectToTargetDevice called but Bluetooth is disabled.")
            publishStatusUpdate("Tentative connexion annulée: Bluetooth désactivé.")
            // The adapterStateReceiver will handle re-attempting when BT is enabled.
            return
        }

        Log.i(TAG, "Target device address set to: $TARGET_DEVICE_ADDRESS. Attempting to get remote device...")
        val device: BluetoothDevice?
        try {
            device = bluetoothAdapter?.getRemoteDevice(TARGET_DEVICE_ADDRESS)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Failed to get BluetoothDevice for address $TARGET_DEVICE_ADDRESS due to invalid MAC format.", e)
            publishStatusUpdate("Adresse MAC BLE $TARGET_DEVICE_ADDRESS invalide.")
            // Not a retryable device issue.
            return
        }


        if (device == null) {
            Log.e(TAG, "Failed to get BluetoothDevice for address $TARGET_DEVICE_ADDRESS (adapter.getRemoteDevice returned null).")
            publishStatusUpdate("Impossible d'obtenir l'appareil BLE pour $TARGET_DEVICE_ADDRESS.")
            handleFailedConnectionAttempt() // Treat as a failed attempt towards the device
            return
        }

        val deviceName = try { device.name } catch (e: SecurityException) { null } ?: "DaYouReminder_BLE"
        Log.i(TAG, "Attempting to connect to $deviceName ($TARGET_DEVICE_ADDRESS)")
        initiateGattConnection(device)
    }

    /**
     * Handles the actual GATT connection attempt to the specified BluetoothDevice.
     * Includes timeout logic and retry management.
     */
    private fun initiateGattConnection(device: BluetoothDevice) {
        if (hasGivenUpRetrying) {
            Log.i(TAG, "InitiateGattConnection: In 'given up' state. Aborting connection to ${device.address}.")
            return
        }
        if (isAttemptingConnection) {
            Log.d(TAG, "Connection attempt already in progress for ${device.address}. Ignoring new request.")
            return
        }

        val deviceName = try { device.name } catch (e: SecurityException) { null } ?: "DaYouReminder_BLE"
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?

        // Check if the system already considers the device connected at the GATT profile level.
        val systemConnectedGattDevices = bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)
        val isDeviceSystemConnected = systemConnectedGattDevices?.any { it.address == device.address } == true

        // If system reports connected AND we have a valid GATT object for this device, assume connected.
        // This can prevent unnecessary new connection attempts if our state is slightly out of sync.
        if (isDeviceSystemConnected && bluetoothGatt != null && bluetoothGatt?.device?.address == device.address) {
            Log.i(TAG, "Already connected to $deviceName (${device.address}) via existing GATT object (system check).")
            publishStatusUpdate("Connecté à $deviceName (BLE)")
            currentReconnectAttempts = 0 // Reset attempts as we are connected
            hasGivenUpRetrying = false
            return
        }

        // Close any existing/stale GATT connection before starting a new one for this device or another
        if (bluetoothGatt != null) {
            Log.d(TAG, "Closing existing GATT connection (for ${bluetoothGatt?.device?.address ?: "unknown"}) before new attempt to ${device.address}.")
            closeGattInternal() // Ensure previous GATT is fully closed before new one
        }

        Log.i(TAG, "Attempting to connect GATT to $deviceName (${device.address})")
        isAttemptingConnection = true
        publishStatusUpdate("Connexion à $deviceName...")

        // Initiate GATT connection
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION") // connectGatt(context, autoConnect, callback) is for pre-M
            device.connectGatt(this, false, gattCallback)
        }

        if (bluetoothGatt == null) {
            Log.e(TAG, "device.connectGatt returned null for $deviceName (${device.address}). Connection failed to initiate.")
            publishStatusUpdate("Échec initiation connexion GATT à $deviceName")
            isAttemptingConnection = false // Reset flag
            handleFailedConnectionAttempt() // Manage retry count
        } else {
            Log.d(TAG, "connectGatt call succeeded for $deviceName (${device.address}). Waiting for callback or timeout.")
            connectionAttemptJob?.cancel() // Cancel previous timeout job, if any
            connectionAttemptJob = serviceScope.launch {
                delay(35000L) // 35-second timeout for connection attempt
                // Check if still attempting connection *for this specific device* and not yet connected
                if (isAttemptingConnection && bluetoothGatt?.device?.address == device.address) {
                    // Double check actual connection state with BluetoothManager
                    val currentConnectionState = bluetoothManager?.getConnectionState(device, BluetoothProfile.GATT)
                    if (currentConnectionState != BluetoothProfile.STATE_CONNECTED) {
                        Log.w(TAG, "GATT connection attempt timed out for $deviceName (${device.address}) after 35s.")
                        publishStatusUpdate("Timeout connexion à $deviceName")
                        closeGattInternal() // Clean up on timeout
                        handleFailedConnectionAttempt() // Manage retry count
                    } else {
                        // Already connected, timeout job was late / redundant, clear flag and reset counters
                        Log.d(TAG, "Timeout check: Device $deviceName already connected. No action from timeout job.")
                        isAttemptingConnection = false
                        currentReconnectAttempts = 0
                        hasGivenUpRetrying = false
                    }
                } else if (bluetoothGatt?.device?.address != device.address && isAttemptingConnection) {
                    Log.w(TAG, "Timeout job for ${device.address} fired, but current attempt is for ${bluetoothGatt?.device?.address}. Ignoring timeout for old device.")
                }
            }
        }
    }

    /**
     * Closes the current GATT connection. Currently not used externally but good for API design.
     */
    @Suppress("unused") // Method might be useful for external control later
    private fun closeGatt() {
        Log.d(TAG, "closeGatt() called (potentially externally).")
        closeGattInternal()
    }

    /**
     * Internal method to disconnect and close the BluetoothGatt object.
     * Ensures proper cleanup of resources.
     */
    private fun closeGattInternal() {
        Log.d(TAG, "closeGattInternal() executing. Current GATT object: ${if (bluetoothGatt != null) "exists for " + (bluetoothGatt?.device?.address ?: "unknown device") else "is null"}")
        connectionAttemptJob?.cancel() // Cancel any pending connection timeout related to the GATT about to be closed
        isAttemptingConnection = false   // No longer attempting to connect with this GATT instance

        val gattToClose = bluetoothGatt // Hold a reference to the current GATT object
        bluetoothGatt = null // Set member variable to null *before* actual close to prevent race conditions or re-entry

        if (gattToClose == null) {
            Log.d(TAG, "closeGattInternal: bluetoothGatt was already null. No action needed.")
            return
        }

        val deviceAddressToClose = gattToClose.device?.address ?: "unknown address"
        Log.d(TAG, "Closing GATT for device: $deviceAddressToClose")

        // BLUETOOTH_CONNECT permission is required for disconnect and close from Android 12+
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            try {
                gattToClose.disconnect() // Disconnect first
                gattToClose.close()      // Then close to release resources
                Log.d(TAG, "GATT disconnected and closed for $deviceAddressToClose.")
            } catch (e: Exception) { // Catch potential exceptions during close, e.g., if adapter is off or other issues
                Log.e(TAG, "Exception during GATT disconnect/close for $deviceAddressToClose", e)
            }
        } else {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT permission to disconnect/close GATT for $deviceAddressToClose.")
            // The connection might remain open from the system's perspective if not properly closed.
        }
        Log.d(TAG, "GATT resources for $deviceAddressToClose intended to be released.")
    }
    //endregion

    //region Status Publishing and Notifications
    /**
     * Updates the connection status both in a shared holder (for UI) and in the foreground notification.
     */
    private fun publishStatusUpdate(message: String) {
        Log.d(TAG, "publishStatusUpdate: Message='$message'")
        BluetoothStatusHolder.updateStatus(message) // Update status for UI observers (if any)
        updateNotification(message) // Update the foreground service notification
    }

    /**
     * Creates the notification channel required for foreground services on Android Oreo (API 26) and above.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "Bluetooth Monitor Service Channel BLE"
            val importance = NotificationManager.IMPORTANCE_LOW // Low importance for ongoing status notification
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, importance).apply {
                description = "Channel for DaYouReminder BLE monitoring service status"
                // setSound(null, null) // No sound for ongoing status updates
                // enableVibration(false) // No vibration for ongoing status updates
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel '$channelName' created.")
        }
    }

    /**
     * Creates the notification object for the foreground service.
     */
    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        notificationIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP // Standard flags

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Statut DaYouReminder (BLE)")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.outline_bigtop_updates_24)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Subsequent updates don't re-alert (sound/vibrate)
            .setSilent(true) // For Android N and above, this helps make updates less intrusive
            .build()
    }

    /**
     * Updates the content of the existing foreground service notification.
     */
    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, createNotification(contentText))
    }
    //endregion

    //region Bluetooth Adapter State Receiver
    /**
     * Registers a BroadcastReceiver to listen for Bluetooth adapter state changes (ON/OFF).
     */
    private fun registerAdapterStateReceiver() {
        if (adapterStateReceiver == null) {
            adapterStateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        when (state) {
                            BluetoothAdapter.STATE_OFF -> {
                                Log.w(TAG, "ACTION_STATE_CHANGED: Bluetooth Adapter turned OFF.")
                                publishStatusUpdate("Bluetooth désactivé.")
                                closeGattInternal() // Close any active GATT connection
                                // We don't modify 'hasGivenUpRetrying' or 'currentReconnectAttempts' here.
                                // The service waits for BT to be turned on again.
                            }
                            BluetoothAdapter.STATE_TURNING_OFF -> {
                                Log.d(TAG, "ACTION_STATE_CHANGED: Bluetooth Adapter turning OFF.")
                                publishStatusUpdate("Désactivation Bluetooth...")
                            }
                            BluetoothAdapter.STATE_ON -> {
                                Log.i(TAG, "ACTION_STATE_CHANGED: Bluetooth Adapter turned ON.")
                                // If BT is turned on and we had given up, reset the state to allow new attempts.
                                if (hasGivenUpRetrying) {
                                    Log.i(TAG, "Bluetooth turned ON, resetting 'give up' state and retry count.")
                                    hasGivenUpRetrying = false
                                    currentReconnectAttempts = 0
                                }
                                publishStatusUpdate("Bluetooth activé. Tentative de connexion...")
                                if (bluetoothAdapter?.isEnabled == true) { // Double check adapter is ready
                                    connectToTargetDevice()
                                } else {
                                    Log.w(TAG, "Bluetooth reported ON by broadcast, but adapter.isEnabled is false. Will wait for connectToTargetDevice check or next event.")
                                }
                            }
                            BluetoothAdapter.STATE_TURNING_ON -> {
                                Log.d(TAG, "ACTION_STATE_CHANGED: Bluetooth Adapter turning ON.")
                                publishStatusUpdate("Activation Bluetooth...")
                            }
                            BluetoothAdapter.ERROR -> { // Unlikely, but good to log
                                Log.e(TAG, "ACTION_STATE_CHANGED: Bluetooth Adapter reported an error state.")
                                publishStatusUpdate("Erreur adaptateur Bluetooth.")
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            try {
                // For Android 13 (TIRAMISU, API 33) and above, specify receiver export status.
                // Using RECEIVER_NOT_EXPORTED as this receiver is for internal app use.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(adapterStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(adapterStateReceiver, filter)
                }
                Log.d(TAG, "Adapter state receiver registered successfully.")
            } catch (e: Exception) { // Catch any exception during registration (e.g., SecurityException if permissions change)
                Log.e(TAG, "Error registering adapter state receiver", e)
                publishStatusUpdate("Erreur interne: Récepteur BT.") // Inform user if critical
            }
        }
    }

    /**
     * Unregisters the Bluetooth adapter state BroadcastReceiver to prevent leaks.
     */
    private fun unregisterAdapterStateReceiver() {
        adapterStateReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "Adapter state receiver unregistered successfully.")
            } catch (e: IllegalArgumentException) {
                // This can happen if the receiver was not registered or already unregistered (e.g., service killed abruptly)
                Log.w(TAG, "Adapter state receiver was not registered or already unregistered.", e)
            } catch (e: Exception) { // Catch any other unexpected exception during unregistration
                Log.e(TAG, "Error unregistering adapter state receiver", e)
            }
            adapterStateReceiver = null // Clear the reference
        }
    }
    //endregion
}
