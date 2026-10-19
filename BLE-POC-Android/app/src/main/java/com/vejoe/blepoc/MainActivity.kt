package com.vejoe.blepoc

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BlePocTheme {
                BlePocApp()
            }
        }
    }
}

@Composable
fun BlePocTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF2F6BFF),
            secondary = Color(0xFF6A5BFF)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlePocApp() {
    val context = LocalContext.current
    val ble = remember { BleManager(context.applicationContext) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(requiredBlePermissions())
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("BLE POC") }) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Broadcast") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Central") }
                )
            }

            when (selectedTab) {
                0 -> BroadcastScreen(ble)
                else -> CentralScreen(ble)
            }
        }
    }
}

private fun requiredBlePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

// ------------------------------------------------------------- Broadcast tab

@Composable
fun BroadcastScreen(ble: BleManager) {
    val logs by ble.logs.collectAsState()
    val isAdvertising by ble.isAdvertising.collectAsState()
    val hasSubscribedCentral by ble.hasSubscribedCentral.collectAsState()

    var serviceUuid by rememberSaveable { mutableStateOf(BleDefaults.SERVICE_UUID) }
    var writeUuid by rememberSaveable { mutableStateOf(BleDefaults.WRITE_UUID) }
    var notifyUuid by rememberSaveable { mutableStateOf(BleDefaults.NOTIFY_UUID) }
    var pairCode by rememberSaveable { mutableStateOf(BleDefaults.PAIR_CODE) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Broadcast", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (isAdvertising) "Advertising" else "Idle",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isAdvertising,
                        onCheckedChange = { on ->
                            if (on) {
                                ble.startAdvertising(serviceUuid, writeUuid, notifyUuid, pairCode)
                            } else {
                                ble.stopAdvertising()
                            }
                        }
                    )
                }
                Spacer(Modifier.height(10.dp))
                StatusRow(
                    active = hasSubscribedCentral,
                    activeText = "A central is connected",
                    inactiveText = "Waiting for a central…"
                )
            }
        }

        item {
            CollapsibleCard(title = "Pairing Config") {
                ConfigField(
                    label = "Service UUID",
                    value = serviceUuid,
                    onValueChange = { serviceUuid = it },
                    onRandom = { serviceUuid = UUID.randomUUID().toString() }
                )
                ConfigField(
                    label = "Write Characteristic (send)",
                    value = writeUuid,
                    onValueChange = { writeUuid = it },
                    onRandom = { writeUuid = UUID.randomUUID().toString() }
                )
                ConfigField(
                    label = "Notify Characteristic (receive)",
                    value = notifyUuid,
                    onValueChange = { notifyUuid = it },
                    onRandom = { notifyUuid = UUID.randomUUID().toString() }
                )
                ConfigField(
                    label = "Pair Code",
                    value = pairCode,
                    onValueChange = { pairCode = it }
                )
                Text(
                    text = "Broadcast uses these UUIDs. Changes take effect after restarting the broadcast.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item { MessageRow(ble) }

        item { Text("Log", style = MaterialTheme.typography.titleSmall) }
        logItems(logs)
    }
}

// --------------------------------------------------------------- Central tab

@Composable
fun CentralScreen(ble: BleManager) {
    val logs by ble.logs.collectAsState()
    val isScanning by ble.isScanning.collectAsState()
    val devices by ble.devices.collectAsState()
    val connectedName by ble.connectedName.collectAsState()

    var serviceUuid by rememberSaveable { mutableStateOf(BleDefaults.SERVICE_UUID) }
    var writeUuid by rememberSaveable { mutableStateOf(BleDefaults.WRITE_UUID) }
    var notifyUuid by rememberSaveable { mutableStateOf(BleDefaults.NOTIFY_UUID) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Central", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (isScanning) "Scanning…" else "Idle",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isScanning,
                        onCheckedChange = { on ->
                            if (on) ble.startScanning(serviceUuid) else ble.stopScanning()
                        }
                    )
                }

                connectedName?.let { name ->
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusRow(
                            active = true,
                            activeText = "Connected: $name",
                            inactiveText = ""
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { ble.disconnect() }) { Text("Disconnect") }
                    }
                }
            }
        }

        item {
            CollapsibleCard(title = "Pairing Config") {
                ConfigField(
                    label = "Service UUID",
                    value = serviceUuid,
                    onValueChange = { serviceUuid = it },
                    onRandom = { serviceUuid = UUID.randomUUID().toString() }
                )
                ConfigField(
                    label = "Write Characteristic (send)",
                    value = writeUuid,
                    onValueChange = { writeUuid = it },
                    onRandom = { writeUuid = UUID.randomUUID().toString() }
                )
                ConfigField(
                    label = "Notify Characteristic (receive)",
                    value = notifyUuid,
                    onValueChange = { notifyUuid = it },
                    onRandom = { notifyUuid = UUID.randomUUID().toString() }
                )
                Text(
                    text = "Central has its own UUIDs, separate from Broadcast. Changes take effect after restarting the scan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item { Text("Devices", style = MaterialTheme.typography.titleSmall) }

        if (devices.isEmpty()) {
            item {
                Text(
                    text = "No devices found",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(devices) { d ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = d.name ?: "Unknown Device",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = buildString {
                                    append(d.device.address)
                                    if (!d.pairCode.isNullOrEmpty()) append("  ·  code ${d.pairCode}")
                                    append("  ·  RSSI ${d.rssi}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = {
                                ble.connect(d.device, serviceUuid, writeUuid, notifyUuid)
                            }
                        ) {
                            Text("Connect")
                        }
                    }
                }
            }
        }

        item { MessageRow(ble) }

        item { Text("Log", style = MaterialTheme.typography.titleSmall) }
        logItems(logs)
    }
}

// -------------------------------------------------------------- shared UI

@Composable
fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }
}

@Composable
fun CollapsibleCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
fun ConfigField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onRandom: (() -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters
                ),
                modifier = Modifier.weight(1f)
            )
            if (onRandom != null) {
                IconButton(onClick = onRandom) {
                    Icon(Icons.Default.Refresh, contentDescription = "Random")
                }
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(value)) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
            }
        }
    }
}

@Composable
fun MessageRow(ble: BleManager) {
    var message by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            placeholder = { Text("Type a message…") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                ble.sendMessage(message)
                message = ""
            },
            enabled = message.isNotEmpty()
        ) {
            Text("Send")
        }
    }
}

@Composable
fun StatusRow(active: Boolean, activeText: String, inactiveText: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (active) Color(0xFF22C55E) else Color(0xFF9CA3AF),
                    shape = CircleShape
                )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (active) activeText else inactiveText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun LogRow(entry: LogEntry) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .background(
                    color = if (entry.incoming) Color(0xFF2F6BFF) else Color(0xFFF59E0B),
                    shape = CircleShape
                )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = entry.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.incoming) Color(0xFF2F6BFF)
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

fun LazyListScope.logItems(logs: List<LogEntry>) {
    if (logs.isEmpty()) {
        item {
            Text(
                text = "No logs yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        items(logs) { entry -> LogRow(entry) }
    }
}
