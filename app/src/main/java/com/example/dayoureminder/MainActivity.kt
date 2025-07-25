package com.example.dayoureminder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.dayoureminder.ui.theme.DaYouReminderTheme

class MainActivity : ComponentActivity() {

    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var allRequiredPermissionsGranted = true
            var essentialPermissionsDenied = false

            val requiredPermissions = getRequiredPermissionsList() // Get current list of needed permissions

            permissions.entries.forEach { entry ->
                Log.d("Permissions", "${entry.key} = ${entry.value}")
                if (requiredPermissions.contains(entry.key) && !entry.value) {
                    allRequiredPermissionsGranted = false
                    if (entry.key == Manifest.permission.BLUETOOTH_CONNECT ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && entry.key == Manifest.permission.BLUETOOTH_SCAN) ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && entry.key == Manifest.permission.POST_NOTIFICATIONS)) {
                        essentialPermissionsDenied = true
                    }
                }
            }

            if (allRequiredPermissionsGranted) {
                Toast.makeText(this, "Permissions BLE accordées.", Toast.LENGTH_SHORT).show()
                startBluetoothMonitoringService()
            } else {
                if (essentialPermissionsDenied) {
                    BluetoothStatusHolder.updateStatus("Permissions BLE essentielles refusées.")
                    Toast.makeText(this, "Permissions BLE/Notification essentielles requises.", Toast.LENGTH_LONG).show()
                } else {
                    BluetoothStatusHolder.updateStatus("Certaines permissions BLE facultatives refusées.")
                    Toast.makeText(this, "Certaines permissions BLE/Notification facultatives refusées.", Toast.LENGTH_LONG).show()
                    // Decide if you can still start the service or not
                    // startBluetoothMonitoringService() // Or not, depending on which were denied
                }
            }
        }

    private fun getRequiredPermissionsList(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+ for new BLE permissions
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else { // Older versions
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION) // Often needed for BLE scan pre-Android 12
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+ for notification
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DaYouReminderTheme {
                val currentStatus by BluetoothStatusHolder.statusMessage.collectAsState()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BluetoothControlScreen(
                        modifier = Modifier.padding(innerPadding),
                        onStartService = {
                            checkAndRequestPermissionsAndStartService()
                        },
                        onStopService = {
                            stopBluetoothMonitoringService()
                        },
                        statusText = currentStatus
                    )
                }
            }
        }
    }

    private fun checkPermissions(): Boolean {
        return getRequiredPermissionsList().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissionsToRequest = getRequiredPermissionsList().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun checkAndRequestPermissionsAndStartService() {
        if (checkPermissions()) {
            startBluetoothMonitoringService()
        } else {
            requestPermissions()
        }
    }

    private fun startBluetoothMonitoringService() {
        if (!checkPermissions()){ // Double check
            BluetoothStatusHolder.updateStatus("Permissions manquantes pour démarrer (BLE).")
            Toast.makeText(this, "Impossible de démarrer: permissions BLE manquantes.", Toast.LENGTH_LONG).show()
            return
        }

        val serviceIntent = Intent(this, BluetoothStateMonitorService::class.java)
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, "Service de surveillance BLE démarré.", Toast.LENGTH_SHORT).show()
            BluetoothStatusHolder.updateStatus("Démarrage du service BLE...")
        } catch (e: SecurityException) {
            Log.e("MainActivity", "SecurityException starting BLE service: ${e.message}", e)
            BluetoothStatusHolder.updateStatus("Erreur démarrage BLE (Sec): ${e.localizedMessage}")
            Toast.makeText(this, "Erreur de sécurité au démarrage du service BLE.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Exception starting BLE service: ${e.message}", e)
            BluetoothStatusHolder.updateStatus("Erreur démarrage BLE: ${e.localizedMessage}")
            Toast.makeText(this, "Erreur au démarrage du service BLE.", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopBluetoothMonitoringService() {
        val serviceIntent = Intent(this, BluetoothStateMonitorService::class.java)
        stopService(serviceIntent)
        Toast.makeText(this, "Arrêt du service de surveillance BLE demandé.", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun BluetoothControlScreen(
    modifier: Modifier = Modifier,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    statusText: String
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        Button(onClick = onStartService) {
            Text("Démarrer Surveillance BLE")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onStopService) {
            Text("Arrêter Surveillance BLE")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun BluetoothControlScreenPreview() {
    DaYouReminderTheme {
        BluetoothControlScreen(
            onStartService = {},
            onStopService = {},
            statusText = "Aperçu: Statut BLE ici"
        )
    }
}