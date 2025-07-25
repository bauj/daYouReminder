package com.example.dayoureminder

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton object to hold and expose the Bluetooth connection status.
 * The Service updates this, and the Activity/UI observes it.
 */
object BluetoothStatusHolder {
    // Private MutableStateFlow that can be updated internally by the service
    private val _statusMessage = MutableStateFlow("Service non démarré")

    // Publicly exposed StateFlow that is read-only for UI consumption
    val statusMessage = _statusMessage.asStateFlow()

    /**
     * Updates the current Bluetooth status message.
     * This will notify any collectors of the statusMessage StateFlow.
     * @param newStatus The new status message to display.
     */
    fun updateStatus(newStatus: String) {
        _statusMessage.value = newStatus
    }
}
