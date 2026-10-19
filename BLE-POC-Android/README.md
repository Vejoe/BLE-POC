# BLE-POC-Android

A BLE proof-of-concept with **two roles in one app**:

| Tab | Role | What it does |
|-----|------|--------------|
| **Broadcast** | Peripheral | Advertises a GATT service, can receive writes and push notifications |
| **Central** | Central | Scans for the service, connects, writes and subscribes to notifications |

Both tabs have their **own editable Pairing Config** (Service UUID, Write/Notify Characteristic UUIDs, plus a Pair Code on the Broadcast tab).

Defaults (must match the iOS app / web helper):

```
Service UUID              8f6d7f58-9ed7-4a95-8e09-5b4b8b8d1f10
Write Characteristic     4a38a38b-b6d9-43cc-8c02-44d4605dd002
Notify Characteristic    4a38a38b-b6d9-43cc-8c02-44d4605dd002
Pair Code                123456
```

> When the write and notify UUIDs are identical, the app automatically uses a
> **single** read/write/notify characteristic (BLE forbids duplicate characteristic
> UUIDs inside one service).

## Requirements

- Android Studio (Ladybug or newer recommended)
- Android device with BLE (Android 8.0 / API 26+)
- Bluetooth permissions granted at first launch

## Open & run

1. Open **Android Studio**.
2. `File → Open…` and select the `BLE-POC-Android` folder.
3. Wait for Gradle sync. If Android Studio asks to create/repair the Gradle wrapper, accept it
   (the binary `gradle-wrapper.jar` is not included in this folder because it could not be downloaded here).
4. Connect an Android phone (Developer Options → USB debugging ON) and press **Run ▶**.

### If Gradle wrapper is missing

Either let Android Studio generate it, or install Gradle and run:

```bash
cd BLE-POC-Android
gradle wrapper --gradle-version 8.9
```

## How to test

**Two Android phones**

1. Phone A → **Broadcast** tab → turn on *Broadcast*.
2. Phone B → **Central** tab → turn on *Scan for Devices* → tap **Connect** next to Phone A.
3. Send messages from either side; both directions appear in the Log.

**Android ↔ iOS**

1. iOS app → Broadcast tab → advertise.
2. Android → Central tab → scan & connect. (Both use the same default UUIDs.)

**Android ↔ Web (Chrome)**

The browser can only act as a **central**. Keep the Android app broadcasting and connect to it
from `BLE-POC-Web.html` in Chrome/Edge.

## Notes

- BLE advertising stops if the app goes to the background on some OEM devices; keep it in the foreground.
- Android 12+ uses `BLUETOOTH_SCAN` / `BLUETOOTH_ADVERTISE` / `BLUETOOTH_CONNECT`.
- Android 11 and below additionally require `ACCESS_FINE_LOCATION` for scanning.
