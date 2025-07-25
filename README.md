<p align="center">
  <h1 align="center">DaYouReminder_BLE</h1>
</p>

<p align="center">
  A Bluetooth Low Energy (BLE) based reminder system featuring an Android application and companion ESP32 firmware to help you not forget your important items.
</p>

<p align="center">
  <!-- TODO: Replace with actual badges from shields.io or similar -->
  <img src="https://img.shields.io/badge/status-in_development-yellow" alt="Project Status: In Development"/>
  <img src="https://img.shields.io/badge/license-MIT-blue" alt="License: MIT"/> <!-- Replace MIT with your chosen license -->
  <!-- <img src="https://img.shields.io/github/last-commit/YOUR_USERNAME/YOUR_REPONAME" alt="Last Commit"/> -->
  <!-- <img src="https://img.shields.io/github/stars/YOUR_USERNAME/YOUR_REPONAME?style=social" alt="GitHub Stars"/> -->
</p>

---

## 🎯 Overview

DaYouReminder_BLE is a project designed to prevent you from leaving important items behind. It consists of:

1.  An **Android application** that monitors a BLE connection.
2.  **Firmware for an ESP32 device** (intended to be attached to items like keys).

If the connection between your phone and the ESP32 device is lost (e.g., you walk out of range), both devices can provide alerts.

## ✨ Features

### 📱 Android Application

*   **BLE Connection Monitoring**: Actively monitors the connection to a configured ESP32 device.
*   **Background Service**: Utilizes a foreground service for persistent connection monitoring, even when the app is minimized.
*   **Status Notifications**: Provides real-time status updates via a persistent Android notification (e.g., "Connected to DaYouReminder," "Disconnected," "Searching...").
*   **Disconnection Alerts**: Audible notifications and/or vibrations upon disconnection.
*   **Automatic Reconnection**: Intelligently attempts to re-establish lost connections.
*   **Configurable Retry Limit**: Ceases automatic reconnection attempts after a predefined threshold to conserve battery, requiring user action or a Bluetooth toggle to resume.
*   **Secure Configuration**: Target ESP32 MAC address is loaded from a local, git-ignored `ble_config.properties` file.
*   **Permissions Handling**: Properly requests and verifies necessary Bluetooth permissions (and Location for older Android versions).
*   **Basic UI**: Displays current connection status and basic device information.

### 📟 ESP32 Device Firmware

*   **BLE Peripheral**: Functions as a BLE peripheral, advertising with the name "DaYouReminder".
*   **Visual & Audible Indicators**:
    *   **LED (GPIO8)**: Solid ON when connected, OFF when disconnected.
    *   **Buzzer (GPIO1)**: Silent when connected.
*   **Disconnection Alerts**:
    *   **LED (GPIO8)**: Turns OFF.
    *   **Buzzer (GPIO1)**: Emits periodic beeps (alternating ON/OFF every 250ms) when the BLE link is severed.
*   **BLE Security**: Implements basic Man-In-The-Middle (MITM) protection.
*   **Standard BLE Service/Characteristic**: Exposes a simple readable characteristic (`UUID: abcdef01-2345-6789-abcd-ef0123456789`) under a service (`UUID: 12345678-1234-5678-1234-56789abcdef0`).

### 🛠️ Building and Running

#### 1. ESP32 Device Firmware

1.  **Clone the Repository**
2.  **Open Sketch**: Launch the Arduino IDE and open the `/esp32_firmware/DaYouReminder_ESP32.ino` (or your sketch filename).
3.  **Configure Arduino IDE**:
    *   Select your ESP32 board model under `Tools > Board`.
    *   Select the correct COM port under `Tools > Port`.
4.  **Wire Components**:
    *   **LED**: Connect anode (longer leg) to GPIO8, cathode (shorter leg) through a resistor to GND.
    *   **Buzzer**: If active LOW, connect its positive terminal to VCC (3.3V) and negative terminal to GPIO1.
5.  **Upload Firmware**: Click the "Upload" button in the Arduino IDE.
6.  **Monitor Output**: Open the Serial Monitor (`Tools > Serial Monitor`) at **115200 baud**. Observe logs and note the ESP32's Bluetooth MAC Address (often shown on boot or findable with a BLE scanner app). The device will advertise as "DaYouReminder".

#### 2. Android Application

1.  **Open Project**: In Android Studio, select `File > Open...` and navigate to the cloned `DaYouReminder_BLE/app` directory.
2.  **Gradle Sync**: Allow Android Studio to sync Gradle dependencies.
3.  **Configure Target MAC Address (Crucial!)**:
    *   In the Android Studio Project view, navigate to `app/src/main/assets/`.
    *   Copy `ble_config.properties.template` and rename the copy to `ble_config.properties`.
    *   Open `ble_config.properties` and replace `YOUR_ESP32_MAC_ADDRESS_HERE` with the actual MAC address of **your** ESP32 device (noted in the previous step).
        
*   This `ble_config.properties` file is git-ignored and specific to your local setup.
4.  **Run App**:
    *   Connect your Android device (USB Debugging enabled) or start a BLE-capable Emulator.
    *   Click the "Run 'app'" button in Android Studio.
5.  **Grant Permissions**: When prompted, grant the necessary Bluetooth (and Location for older Android versions) permissions.
6.  The app will now attempt to connect to your configured ESP32.

## 🚦 Usage

1.  Ensure your ESP32 device is powered on, correctly wired, and within Bluetooth range.
2.  Launch the **DaYouReminder_BLE** app on your Android phone.
3.  Observe the connection status in the app's UI and notification.
    *   **On ESP32**: The LED (GPIO8) should illuminate, and the buzzer (GPIO1) should be silent.
4.  **Test Disconnection**:
    *   Move the phone out of BLE range.
    *   Turn off Bluetooth on your phone.
    *   Power off the ESP32.
5.  **Observe Alerts**:
    *   **Android**: Notification updates; *[Customize: sound/vibration if implemented]*.
    *   **ESP32**: LED (GPIO8) turns OFF; Buzzer (GPIO1) starts periodic beeping.
6.  **Test Reconnection**:
    *   Bring devices back into range / re-enable Bluetooth / power on ESP32.
    *   The Android app should attempt to reconnect.
    *   Upon success, ESP32's LED will turn ON, and the buzzer will silence.

## ⚙️ How It Works

### Android App

The app's core logic resides in `BluetoothStateMonitorService.kt`, a foreground `Service` that manages the BLE connection lifecycle using Android's `BluetoothAdapter` and `BluetoothGatt` APIs. It reads the target MAC from `ble_config.properties`. Connection state changes trigger UI updates, notifications, and reconnection strategies.

### ESP32 Firmware

The ESP32 sketch leverages the built-in ESP32 BLE libraries. It initializes a BLE server, defines a service with a simple characteristic, and handles advertising. `MyServerCallbacks` detect connection/disconnection events to control the LED and buzzer, and to restart advertising when a connection is lost.

## 🚧 Known Issues & TODO

*   GUI for Android app is currently minimalistic.
*   ESP32 buzzer alert is continuous upon disconnection. Consider:
    *   Implementing a firmware-side timeout for the buzzer.
    *   Adding a physical switch to the ESP32 to manually silence alerts/power off.
*   Enhance Android app with BLE scanning and device selection instead of relying solely on a config file for MAC.
*   Introduce user-configurable settings (alert types, durations, retry behavior) in the Android app.
*   Further optimize ESP32 battery consumption (e.g., deep sleep strategies).
*   Implement more robust error handling and user feedback across both platforms.
*   Consider BLE bonding/encryption for enhanced security if sensitive data were to be exchanged in the future.

## 🤝 Contributing

*   Contributions are welcome! Please feel free to submit a Pull Request. For major changes, please open an issue first to discuss what you would like to change.
*   Feel free to fork and adapt.


## 📜 License

This project is licensed under the MIT License. See the `LICENSE.md` file for details.

---

<p align="center">
  Made by Bauj
</p>



