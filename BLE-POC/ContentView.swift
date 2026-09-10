//
//  ContentView.swift
//  BLE-POC
//
//  Created by Vejoe Wei on 2026/9/10.
//

import SwiftUI
import CoreBluetooth
#if os(macOS)
import AppKit
#else
import UIKit
#endif

func copyToClipboard(_ text: String) {
    #if os(macOS)
    NSPasteboard.general.clearContents()
    NSPasteboard.general.setString(text, forType: .string)
    #else
    UIPasteboard.general.string = text
    #endif
}

func configRow(title: String, text: Binding<String>, onRandom: @escaping () -> Void) -> some View {
    VStack(alignment: .leading, spacing: 6) {
        Text(title)
            .font(.caption)
            .foregroundStyle(.secondary)
        HStack {
            TextField(title, text: text)
                .font(.system(.caption, design: .monospaced))
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
            Button {
                onRandom()
            } label: {
                Image(systemName: "arrow.clockwise")
            }
            .buttonStyle(.borderless)
            Button {
                copyToClipboard(text.wrappedValue)
            } label: {
                Image(systemName: "doc.on.doc")
            }
            .buttonStyle(.borderless)
        }
    }
}

struct ContentView: View {
    @StateObject private var ble = BLEManager()

    var body: some View {
        TabView {
            BroadcastView(ble: ble)
                .tabItem { Label("Broadcast", systemImage: "dot.radiowaves.left.and.right") }
            CentralView(ble: ble)
                .tabItem { Label("Central", systemImage: "magnifyingglass") }
        }
    }
}

// MARK: - Broadcast (Peripheral)

struct BroadcastView: View {
    @ObservedObject var ble: BLEManager
    @State private var showPairingConfig = false

    var body: some View {
        NavigationStack {
            List {
                broadcastSection
                pairingConfigSection
                ChatPanel(ble: ble)
            }
            .navigationTitle("Broadcast")
        }
    }

    private var pairingConfigSection: some View {
        Section {
            DisclosureGroup(isExpanded: $showPairingConfig) {
                configRow(
                    title: "Service UUID",
                    text: $ble.broadcastServiceUUID,
                    onRandom: { ble.broadcastServiceUUID = BLEManager.randomUUIDString() }
                )
                configRow(
                    title: "Write Characteristic (send)",
                    text: $ble.broadcastWriteUUID,
                    onRandom: { ble.broadcastWriteUUID = BLEManager.randomUUIDString() }
                )
                configRow(
                    title: "Notify Characteristic (receive)",
                    text: $ble.broadcastNotifyUUID,
                    onRandom: { ble.broadcastNotifyUUID = BLEManager.randomUUIDString() }
                )

                VStack(alignment: .leading, spacing: 6) {
                    Text("Pair Code")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    HStack {
                        TextField("Pair Code", text: $ble.pairCode)
                            .font(.system(.body, design: .monospaced))
                            .autocorrectionDisabled()
                        Button {
                            copyToClipboard(ble.pairCode)
                        } label: {
                            Image(systemName: "doc.on.doc")
                        }
                        .buttonStyle(.borderless)
                    }
                }

                Text("Broadcast uses these UUIDs. Changes take effect after restarting the broadcast.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.top, 4)
            } label: {
                Label("Pairing Config", systemImage: "slider.horizontal.3")
            }
        }
    }

    private var broadcastSection: some View {
        Section("Broadcast") {
            Toggle(isOn: Binding(
                get: { ble.isAdvertising },
                set: { on in on ? ble.startAdvertising() : ble.stopAdvertising() }
            )) {
                Label("Advertise Service", systemImage: "dot.radiowaves.left.and.right")
            }

            HStack(spacing: 6) {
                Image(systemName: ble.hasSubscribedCentral ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(ble.hasSubscribedCentral ? Color.green : Color.secondary)
                Text(ble.hasSubscribedCentral ? "A central is connected" : "Waiting for a central…")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - Central (Scan / Connect)

struct CentralView: View {
    @ObservedObject var ble: BLEManager
    @State private var showPairingConfig = false

    var body: some View {
        NavigationStack {
            List {
                scanSection
                pairingConfigSection
                ChatPanel(ble: ble)
            }
            .navigationTitle("Central")
        }
    }

    private var pairingConfigSection: some View {
        Section {
            DisclosureGroup(isExpanded: $showPairingConfig) {
                configRow(
                    title: "Service UUID",
                    text: $ble.centralServiceUUID,
                    onRandom: { ble.centralServiceUUID = BLEManager.randomUUIDString() }
                )
                configRow(
                    title: "Write Characteristic (send)",
                    text: $ble.centralWriteUUID,
                    onRandom: { ble.centralWriteUUID = BLEManager.randomUUIDString() }
                )
                configRow(
                    title: "Notify Characteristic (receive)",
                    text: $ble.centralNotifyUUID,
                    onRandom: { ble.centralNotifyUUID = BLEManager.randomUUIDString() }
                )

                Text("Central has its own UUIDs, separate from Broadcast. Changes take effect after restarting the scan.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.top, 4)
            } label: {
                Label("Pairing Config", systemImage: "slider.horizontal.3")
            }
        }
    }

    private var scanSection: some View {
        Section("Central") {
            Toggle(isOn: Binding(
                get: { ble.isScanning },
                set: { on in on ? ble.startScanning() : ble.stopScanning() }
            )) {
                Label("Scan for Devices", systemImage: "magnifyingglass")
            }

            if ble.discoveredPeripherals.isEmpty {
                Text("No devices found")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(Array(ble.discoveredPeripherals.values), id: \.identifier) { peripheral in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(peripheral.name ?? "Unknown Device")
                            Text(peripheral.identifier.uuidString)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Connect") {
                            ble.connect(to: peripheral)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.small)
                    }
                }
            }

            if let connected = ble.connectedPeripheral {
                HStack {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                    Text("Connected: \(connected.name ?? connected.identifier.uuidString)")
                        .font(.footnote)
                    Spacer()
                    Button("Disconnect") {
                        ble.disconnect()
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
            }
        }
    }
}

// MARK: - Chat (message + log), shared by both tabs

struct ChatPanel: View {
    @ObservedObject var ble: BLEManager
    @State private var message = ""

    var body: some View {
        Group {
            Section("Message") {
                HStack {
                    TextField("Type a message…", text: $message)
                    Button("Send") {
                        ble.sendMessage(message)
                        message = ""
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(message.isEmpty)
                }
            }

            Section("Log") {
                if ble.logEntries.isEmpty {
                    Text("No logs yet")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(ble.logEntries) { entry in
                        HStack(alignment: .top, spacing: 8) {
                            Circle()
                                .fill(entry.isIncoming ? Color.blue : Color.orange)
                                .frame(width: 8, height: 8)
                                .padding(.top, 5)
                            Text(entry.text)
                                .font(.footnote)
                                .foregroundStyle(entry.isIncoming ? Color.blue : Color.primary)
                        }
                    }
                }
            }
        }
    }
}

#Preview {
    ContentView()
}
