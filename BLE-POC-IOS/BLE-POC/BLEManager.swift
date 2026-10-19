//
//  BLEManager.swift
//  BLE-POC
//
//  Created by Vejoe Wei on 2026/9/10.
//

import Foundation
import Combine
import CoreBluetooth

/// Default UUIDs (must match the peer's service / characteristic UUIDs)
enum BLEDefaults {
    static let serviceUUID = "8f6d7f58-9ed7-4a95-8e09-5b4b8b8d1f10"
    static let writeCharacteristicUUID = "4a38a38b-b6d9-43cc-8c02-44d4605dd002"
    static let notifyCharacteristicUUID = "4a38a38b-b6d9-43cc-8c02-44d4605dd002"
    static let pairCode = "123456"
}

@MainActor
final class BLEManager: NSObject, ObservableObject {

    struct LogEntry: Identifiable {
        let id = UUID()
        let text: String
        let isIncoming: Bool
        let date = Date()
    }

    @Published var logEntries: [LogEntry] = []
    @Published var discoveredPeripherals: [UUID: CBPeripheral] = [:]
    @Published var connectedPeripheral: CBPeripheral?
    @Published var isScanning = false
    @Published var isAdvertising = false
    @Published var hasSubscribedCentral = false

    // Broadcast (peripheral) config — resets to defaults on every launch
    @Published var broadcastServiceUUID = BLEDefaults.serviceUUID
    @Published var broadcastWriteUUID = BLEDefaults.writeCharacteristicUUID
    @Published var broadcastNotifyUUID = BLEDefaults.notifyCharacteristicUUID

    // Central (scan) config — its own separate copy, also resets to defaults on every launch
    @Published var centralServiceUUID = BLEDefaults.serviceUUID
    @Published var centralWriteUUID = BLEDefaults.writeCharacteristicUUID
    @Published var centralNotifyUUID = BLEDefaults.notifyCharacteristicUUID

    // Pair code (used in the broadcast name); persists across launches
    @Published var pairCode: String {
        didSet { UserDefaults.standard.set(pairCode, forKey: Keys.pairCode) }
    }

    private enum Keys {
        static let pairCode = "ble.pairCode"
    }

    private var centralManager: CBCentralManager!
    private var peripheralManager: CBPeripheralManager!

    private var transferService: CBMutableService?
    private var writeCharacteristic: CBMutableCharacteristic?
    private var notifyCharacteristic: CBMutableCharacteristic?

    private var subscribedCentral: CBCentral?
    private var remoteWriteCharacteristic: CBCharacteristic?
    private var remoteNotifyCharacteristic: CBCharacteristic?

    // Remote characteristic UUIDs locked at connect time (avoids mid-connection config changes)
    private var activeWriteUUID: CBUUID?
    private var activeNotifyUUID: CBUUID?

    override init() {
        pairCode = UserDefaults.standard.string(forKey: Keys.pairCode) ?? BLEDefaults.pairCode
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
    }

    // MARK: - UUID helpers

    /// Validate and convert to CBUUID; returns nil if invalid
    private func validCBUUID(_ string: String) -> CBUUID? {
        guard UUID(uuidString: string) != nil else { return nil }
        return CBUUID(string: string)
    }

    static func randomUUIDString() -> String {
        UUID().uuidString
    }

    // MARK: - Logging

    private func addLog(_ text: String, incoming: Bool = false) {
        logEntries.append(LogEntry(text: text, isIncoming: incoming))
        if logEntries.count > 200 {
            logEntries.removeFirst(logEntries.count - 200)
        }
    }

    private func stateName(_ state: CBManagerState) -> String {
        switch state {
        case .poweredOn: return "On"
        case .poweredOff: return "Off"
        case .resetting: return "Resetting"
        case .unauthorized: return "Unauthorized"
        case .unsupported: return "Unsupported"
        case .unknown: return "Unknown"
        @unknown default: return "Unknown (\(state.rawValue))"
        }
    }

    // MARK: - Peripheral (advertising)

    func startAdvertising() {
        guard peripheralManager.state == .poweredOn else {
            addLog("Bluetooth not ready, can't advertise (state: \(stateName(peripheralManager.state)))")
            return
        }

        guard let serviceUUID = validCBUUID(broadcastServiceUUID),
              let writeUUID = validCBUUID(broadcastWriteUUID),
              let notifyUUID = validCBUUID(broadcastNotifyUUID) else {
            addLog("Invalid UUID format, check the config")
            return
        }

        let service = CBMutableService(type: serviceUUID, primary: true)

        if writeUUID == notifyUUID {
            // Same UUID for write & notify: merge into one read/write/notify characteristic
            let char = CBMutableCharacteristic(
                type: writeUUID,
                properties: [.write, .writeWithoutResponse, .notify, .read],
                value: nil,
                permissions: [.writeable, .readable]
            )
            service.characteristics = [char]
            transferService = service
            writeCharacteristic = char
            notifyCharacteristic = char
        } else {
            let writeChar = CBMutableCharacteristic(
                type: writeUUID,
                properties: [.write, .writeWithoutResponse],
                value: nil,
                permissions: [.writeable]
            )

            let notifyChar = CBMutableCharacteristic(
                type: notifyUUID,
                properties: [.notify, .read],
                value: nil,
                permissions: [.readable]
            )

            service.characteristics = [writeChar, notifyChar]
            transferService = service
            writeCharacteristic = writeChar
            notifyCharacteristic = notifyChar
        }

        peripheralManager.add(service)
        peripheralManager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
            CBAdvertisementDataLocalNameKey: "BLE-POC-\(pairCode)"
        ])

        isAdvertising = true
        addLog("Started advertising \(broadcastServiceUUID)")
    }

    func stopAdvertising() {
        peripheralManager.stopAdvertising()
        if let transferService {
            peripheralManager.remove(transferService)
        }
        transferService = nil
        writeCharacteristic = nil
        notifyCharacteristic = nil
        subscribedCentral = nil
        hasSubscribedCentral = false
        isAdvertising = false
        addLog("Stopped advertising")
    }

    // MARK: - Central (scan / connect)

    func startScanning() {
        guard centralManager.state == .poweredOn else {
            addLog("Bluetooth not ready, can't scan (state: \(stateName(centralManager.state)))")
            return
        }
        guard let serviceUUID = validCBUUID(centralServiceUUID) else {
            addLog("Invalid UUID format, check the config")
            return
        }
        discoveredPeripherals.removeAll()
        centralManager.scanForPeripherals(
            withServices: [serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
        isScanning = true
        addLog("Started scanning…")
    }

    func stopScanning() {
        centralManager.stopScan()
        isScanning = false
        addLog("Stopped scanning")
    }

    func connect(to peripheral: CBPeripheral) {
        centralManager.stopScan()
        isScanning = false
        activeWriteUUID = validCBUUID(centralWriteUUID)
        activeNotifyUUID = validCBUUID(centralNotifyUUID)
        peripheral.delegate = self
        centralManager.connect(peripheral, options: nil)
        addLog("Connecting to \(peripheral.name ?? peripheral.identifier.uuidString)…")
    }

    func disconnect() {
        guard let connectedPeripheral else { return }
        centralManager.cancelPeripheralConnection(connectedPeripheral)
    }

    // MARK: - Send message

    func sendMessage(_ text: String) {
        guard !text.isEmpty else { return }

        // As central: write to the connected peripheral's writable characteristic
        if let connectedPeripheral, let remoteWriteCharacteristic {
            connectedPeripheral.writeValue(Data(text.utf8), for: remoteWriteCharacteristic, type: .withResponse)
            addLog("Me (central): \(text)")
            return
        }

        // As peripheral: push to the subscribed central via notify
        if isAdvertising, let notifyCharacteristic, hasSubscribedCentral {
            _ = peripheralManager.updateValue(Data(text.utf8), for: notifyCharacteristic, onSubscribedCentrals: nil)
            addLog("Me (peripheral): \(text)")
            return
        }

        addLog("No active connection to send")
    }
}

// MARK: - CBCentralManagerDelegate (central)

extension BLEManager: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        addLog("Central Bluetooth state: \(stateName(central.state))")
    }

    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any],
                        rssi RSSI: NSNumber) {
        discoveredPeripherals[peripheral.identifier] = peripheral
        addLog("Found device: \(peripheral.name ?? "Unknown")  RSSI:\(RSSI)")
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        connectedPeripheral = peripheral
        addLog("Connected to \(peripheral.name ?? peripheral.identifier.uuidString)")
        if let serviceUUID = validCBUUID(centralServiceUUID) {
            peripheral.discoverServices([serviceUUID])
        } else {
            peripheral.discoverServices(nil)
        }
    }

    func centralManager(_ central: CBCentralManager,
                        didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        connectedPeripheral = nil
        remoteWriteCharacteristic = nil
        remoteNotifyCharacteristic = nil
        activeWriteUUID = nil
        activeNotifyUUID = nil
        addLog("Disconnected: \(peripheral.name ?? peripheral.identifier.uuidString)")
    }

    func centralManager(_ central: CBCentralManager,
                        didFailToConnect peripheral: CBPeripheral,
                        error: Error?) {
        addLog("Connection failed: \(error?.localizedDescription ?? "unknown error")")
    }
}

// MARK: - CBPeripheralDelegate (central reading the remote peripheral)

extension BLEManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error {
            addLog("Failed to discover services: \(error.localizedDescription)")
            return
        }
        guard let services = peripheral.services else { return }
        for service in services {
            var uuids: [CBUUID] = []
            if let activeWriteUUID { uuids.append(activeWriteUUID) }
            if let activeNotifyUUID { uuids.append(activeNotifyUUID) }
            peripheral.discoverCharacteristics(uuids.isEmpty ? nil : uuids, for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        if let error {
            addLog("Failed to discover characteristics: \(error.localizedDescription)")
            return
        }
        for characteristic in service.characteristics ?? [] {
            if characteristic.uuid == activeWriteUUID {
                remoteWriteCharacteristic = characteristic
            }
            if characteristic.uuid == activeNotifyUUID {
                remoteNotifyCharacteristic = characteristic
                peripheral.setNotifyValue(true, for: characteristic)
                addLog("Subscribed to notifications")
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        if let data = characteristic.value, let text = String(data: data, encoding: .utf8) {
            addLog("Peer: \(text)", incoming: true)
        }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didWriteValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        if let error {
            addLog("Write failed: \(error.localizedDescription)")
        } else {
            addLog("Write succeeded")
        }
    }
}

// MARK: - CBPeripheralManagerDelegate (peripheral)

extension BLEManager: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        addLog("Peripheral Bluetooth state: \(stateName(peripheral.state))")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           didAdd service: CBService,
                           error: Error?) {
        if let error {
            addLog("Failed to add service: \(error.localizedDescription)")
            isAdvertising = false
        } else {
            addLog("Service added")
        }
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error {
            addLog("Advertising failed: \(error.localizedDescription)")
            isAdvertising = false
        } else {
            addLog("Advertising started")
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           central: CBCentral,
                           didSubscribeTo characteristic: CBCharacteristic) {
        subscribedCentral = central
        hasSubscribedCentral = true
        addLog("Central subscribed: \(central.identifier)")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           central: CBCentral,
                           didUnsubscribeFrom characteristic: CBCharacteristic) {
        subscribedCentral = nil
        hasSubscribedCentral = false
        addLog("Central unsubscribed")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            guard let value = request.value, let text = String(data: value, encoding: .utf8) else {
                peripheral.respond(to: request, withResult: .invalidAttributeValueLength)
                continue
            }
            addLog("Peer: \(text)", incoming: true)
            peripheral.respond(to: request, withResult: .success)
        }
    }
}
