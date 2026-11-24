package com.example.activitycollector.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material.MaterialTheme
import com.example.activitycollector.AlertHistoryManager
import com.example.activitycollector.WatchProtocol
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable

/** Navigation for the FallGuard Watch UI design. Alerts and SOS screens take over when active. */
class MainActivity : ComponentActivity() {

    private enum class Screen { Home, HoldSos, History, Contacts, Sensitivity, Settings, RecordData }

    private val contacts = mutableStateOf<List<WatchPrefs.Contact>>(emptyList())
    private val phoneConnected = mutableStateOf<Boolean?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The "Are you OK?" prompt opens this activity over the lock screen.
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf(Screen.Home) }
                var label by remember { mutableStateOf<String?>(null) }
                var alarmSound by remember { mutableStateOf(WatchPrefs.alarmSound(this)) }
                val detection by DetectionService.state.collectAsState()
                val sos by SosController.state.collectAsState()
                val recording by RecordingService.stats.collectAsState()
                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
                LaunchedEffect(screen) { if (screen == Screen.Settings) refreshPhoneStatus() }

                when {
                    sos !is SosController.State.Idle -> SosStatusScreen(
                        sos, onImOk = { SosController.imOk(this) }, onDone = { SosController.done(); screen = Screen.Home })
                    detection.alertActive -> AreYouOkScreen(
                        detection.alertStartedAtElapsedMs,
                        onOk = { DetectionService.imOk(this) },
                        onCallHelp = { DetectionService.callHelpNow(this) })
                    else -> when (screen) {
                        Screen.Home -> HomeScreen(
                            guardOn = detection.running,
                            primary = contacts.value.firstOrNull { it.primary }?.name,
                            onToggleGuard = {
                                if (detection.running) DetectionService.stop(this)
                                else { ensureNotificationPermission(); DetectionService.start(this) }
                            },
                            onSos = { screen = Screen.HoldSos },
                            onHistory = { screen = Screen.History },
                            onContacts = { screen = Screen.Contacts },
                            onSensitivity = { screen = Screen.Sensitivity },
                            onSettings = { screen = Screen.Settings },
                        )
                        Screen.HoldSos -> HoldSosScreen(onSend = { SosController.start(this, "manual"); screen = Screen.Home })
                        Screen.History -> HistoryScreen(AlertHistoryManager(this).getAlerts())
                        Screen.Contacts -> ContactsScreen(contacts.value)
                        Screen.Sensitivity -> SensitivityScreen()
                        Screen.Settings -> SettingsScreen(
                            phoneConnected = phoneConnected.value, alarmSound = alarmSound,
                            onAlarmSound = { alarmSound = it; WatchPrefs.setAlarmSound(this, it) },
                            onRecordData = { screen = Screen.RecordData },
                        )
                        Screen.RecordData -> RecordDataScreen(
                            stats = recording, selected = label, onSelect = { label = it },
                            onStart = { label?.let { ensureNotificationPermission(); RecordingService.start(this, it) } },
                            onStop = { RecordingService.stop(this) },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        contacts.value = WatchPrefs.contacts(this)
        // Catch up on contacts synced while the app wasn't running.
        Wearable.getDataClient(this).dataItems.addOnSuccessListener { items ->
            items.forEach { item ->
                if (item.uri.path == WatchProtocol.PATH_CONTACTS) {
                    DataMapItem.fromDataItem(item).dataMap.getString(WatchProtocol.KEY_CONTACTS_JSON)?.let {
                        WatchPrefs.saveContactsJson(this, it)
                        contacts.value = WatchPrefs.contacts(this)
                    }
                }
            }
            items.release()
        }
    }

    private fun refreshPhoneStatus() {
        phoneConnected.value = null
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { phoneConnected.value = it.isNotEmpty() }
            .addOnFailureListener { phoneConnected.value = false }
    }

    /** Notifications show that detection or recording is running and carry the fall prompt. */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
