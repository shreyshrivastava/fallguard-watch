package com.example.activitycollector.wear

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.SwitchDefaults
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.example.activitycollector.AlertEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Scrolling screen with the clock on top and room for an edge button at the bottom. */
@Composable
fun ListScreen(edge: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null, content: ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = state) }) {
        Box(Modifier.fillMaxSize().background(FG.Bg)) {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(), state = state,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                content()
                if (edge != null) item { Spacer(Modifier.height(56.dp)) }
            }
            edge?.invoke(this)
        }
    }
}

/** Ticks once a second, for countdowns measured from SystemClock.elapsedRealtime(). */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(1000L) } }
    return now
}

// ── Guard ──────────────────────────────────────────────────────────────────────

@Composable
fun HomeScreen(
    guardOn: Boolean, primary: String?, onToggleGuard: () -> Unit, onSos: () -> Unit,
    onHistory: () -> Unit, onContacts: () -> Unit, onSensitivity: () -> Unit, onSettings: () -> Unit,
) {
    ListScreen(edge = {
        if (guardOn) EdgeButton("Hold for SOS", R.drawable.ic_alert, FG.Crimson, Color.White, onClick = onSos)
        else EdgeButton("Resume Guard", R.drawable.ic_shield, FG.Emerald, FG.OnEmerald, onClick = onToggleGuard)
    }) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val ring = if (guardOn) FG.Emerald else FG.Amber
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(if (guardOn) FG.EmeraldTint else FG.AmberTint).border(2.dp, ring, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(if (guardOn) R.drawable.ic_shield_check else R.drawable.ic_pause), null, tint = ring, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(if (guardOn) "Protected" else "Guard paused", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = if (guardOn) FG.Emerald else FG.Text)
                Text(if (guardOn) "Watching for falls" else "SOS still works", fontSize = 13.sp, color = FG.Muted)
            }
        }
        item {
            ListCard(R.drawable.ic_phone, primary ?: "No contacts yet", if (primary != null) "Primary · via phone" else "Add them on your phone", onClick = onContacts)
        }
        if (guardOn) item { ListCard(R.drawable.ic_pause, "Pause Guard", iconTint = FG.Amber, iconBg = FG.AmberTint, onClick = onToggleGuard) }
        else item { ListCard(R.drawable.ic_alert, "Hold for SOS", iconTint = FG.CrimsonSoft, iconBg = FG.CrimsonTint, onClick = onSos) }
        item { ListCard(R.drawable.ic_history, "History", onClick = onHistory) }
        item { ListCard(R.drawable.ic_sliders, "Sensitivity", onClick = onSensitivity) }
        item { ListCard(R.drawable.ic_settings, "Settings", iconTint = FG.Text, iconBg = FG.Gray, onClick = onSettings) }
    }
}

// ── Fall alert and SOS ─────────────────────────────────────────────────────────

@Composable
fun AreYouOkScreen(startedAtElapsedMs: Long, onOk: () -> Unit, onCallHelp: () -> Unit) {
    val now = rememberNow()
    val total = DetectionService.IMMOBILE_S
    val left = (total - (now - startedAtElapsedMs) / 1000).coerceIn(0, total.toLong())
    Box(Modifier.fillMaxSize().background(FG.AlertBg)) {
        CircularProgressIndicator(
            progress = left.toFloat() / total, modifier = Modifier.fillMaxSize().padding(3.dp),
            startAngle = 270f, indicatorColor = Color(0xFFFCA5A5), trackColor = Color(0xFF7F1D1D), strokeWidth = 5.dp,
        )
        Column(Modifier.fillMaxWidth().padding(top = 28.dp, start = 30.dp, end = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_alert), null, tint = Color(0xFFFCA5A5), modifier = Modifier.size(22.dp))
            Text("Are you OK?", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text("Possible fall. If you don't move, help is called in ${left} s", fontSize = 12.sp, color = Color(0xFFFEE2E2), textAlign = TextAlign.Center, lineHeight = 15.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).border(2.dp, Color(0xFFFCA5A5), RoundedCornerShape(22.dp))
                    .clickable(role = Role.Button, onClick = onCallHelp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_phone), null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Call help now", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        EdgeButton("I'm OK", R.drawable.ic_check, FG.Emerald, FG.OnEmerald, onClick = onOk)
    }
}

@Composable
fun SosStatusScreen(state: SosController.State, onImOk: () -> Unit, onDone: () -> Unit) {
    when (state) {
        is SosController.State.Contacting -> ListScreen(edge = { EdgeButton("I'm OK, tell them", null, FG.Card, FG.Text, border = Color(0xFF3F4A44), onClick = onImOk) }) {
            item { Text("SOS IN PROGRESS", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = FG.CrimsonSoft) }
            item { Text("Contacting help", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = FG.Text) }
            item { ListCard(R.drawable.ic_map_pin, "Texting your location", "From your phone", iconTint = FG.Amber, iconBg = FG.AmberTint, trailing = { Spinner() }) }
            item { ListCard(R.drawable.ic_phone, "Calling your contact", "From your phone", iconTint = FG.Amber, iconBg = FG.AmberTint, trailing = { Spinner() }) }
        }
        is SosController.State.Notified -> ListScreen(edge = { EdgeButton("Done", null, FG.Card, FG.Text, onClick = onDone) }) {
            item { IconBadge(R.drawable.ic_check, FG.Emerald, FG.EmeraldTint, 40.dp) }
            item { Text(if (state.callPlaced || state.textsSent > 0) "Help is notified" else "Couldn't alert anyone", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = FG.Text) }
            item {
                ListCard(R.drawable.ic_phone, if (state.callPlaced) "Called ${state.primaryName}" else "No call placed",
                    if (state.callPlaced) "From your phone" else "Check call permission on the phone",
                    iconTint = if (state.callPlaced) FG.Emerald else FG.Amber, iconBg = if (state.callPlaced) FG.EmeraldTint else FG.AmberTint)
            }
            item {
                ListCard(R.drawable.ic_map_pin, if (state.textsSent > 0) "Location texted" else "No texts sent",
                    if (state.textsSent > 0) state.textedNames.joinToString(", ") + if (!state.locationFound) " · location unknown" else ""
                    else if (state.contacts == 0) "No contacts on the phone" else "Check SMS permission on the phone",
                    iconTint = if (state.textsSent > 0) FG.Emerald else FG.Amber, iconBg = if (state.textsSent > 0) FG.EmeraldTint else FG.AmberTint)
            }
        }
        is SosController.State.Unreachable -> ListScreen(edge = { EdgeButton("I'm OK, stop", null, FG.Emerald, FG.OnEmerald, onClick = onImOk) }) {
            item { IconBadge(R.drawable.ic_phone_off, FG.Amber, FG.AmberTint, 40.dp) }
            item { Text("Can't reach your phone", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = FG.Text, textAlign = TextAlign.Center) }
            item { Text("This watch can't call or text by itself. Alarm is sounding so people nearby notice.", fontSize = 12.sp, color = FG.Muted, textAlign = TextAlign.Center) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_refresh), null, tint = FG.Amber, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Retrying every 10 s (try ${state.attempts})", fontSize = 12.sp, color = FG.Amber)
                }
            }
        }
        SosController.State.Idle -> Unit
    }
}

@Composable
private fun Spinner() = CircularProgressIndicator(modifier = Modifier.size(16.dp), indicatorColor = FG.Amber, trackColor = Color.Transparent, strokeWidth = 2.dp)

/** Press and hold for 3 s; letting go early cancels. Prevents pocket presses. */
@Composable
fun HoldSosScreen(onSend: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(FG.Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Press and hold for 3 s", fontSize = 13.sp, color = FG.Muted)
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.size(104.dp).clip(CircleShape).background(FG.Crimson)
                    .semantics { contentDescription = "Hold to send SOS" }
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            val job = scope.launch {
                                progress.animateTo(1f, tween(3000, easing = LinearEasing))
                                onSend()
                            }
                            tryAwaitRelease()
                            if (progress.value < 1f) { job.cancel(); scope.launch { progress.snapTo(0f) } }
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(progress = progress.value, modifier = Modifier.fillMaxSize(), startAngle = 270f, indicatorColor = Color(0xFFFECACA), trackColor = Color(0xFF991B1B), strokeWidth = 4.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.ic_alert), null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Text("SOS", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Texts your location to every contact and calls your primary contact", fontSize = 11.sp, color = FG.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 34.dp))
        }
    }
}

// ── History, contacts, sensitivity, settings ───────────────────────────────────

@Composable
fun HistoryScreen(events: List<AlertEvent>) {
    ListScreen {
        item { ScreenTitle(R.drawable.ic_history, "History") }
        if (events.isEmpty()) item { Text("No alerts yet", fontSize = 13.sp, color = FG.Muted) }
        items(events) { e ->
            val when_ = "${e.relativeTimeSpan}"
            when (e.outcome) {
                AlertEvent.Outcome.DISMISSED_SAFE -> ListCard(R.drawable.ic_check, "You were OK", "$when_ · ${e.summary}")
                AlertEvent.Outcome.CANCELLED_EARLY -> ListCard(R.drawable.ic_activity, "You kept moving", "$when_ · no call made", iconTint = FG.Amber, iconBg = FG.AmberTint)
                AlertEvent.Outcome.SOS_DISPATCHED -> ListCard(R.drawable.ic_alert, "SOS sent", "$when_ · ${e.summary}", iconTint = FG.CrimsonSoft, iconBg = FG.CrimsonTint)
            }
        }
    }
}

@Composable
fun ContactsScreen(contacts: List<WatchPrefs.Contact>) {
    ListScreen {
        item { ScreenTitle(R.drawable.ic_users, "Contacts") }
        if (contacts.isEmpty()) item { Text("No contacts yet. Add them in FallGuard Companion on your phone.", fontSize = 12.sp, color = FG.Muted, textAlign = TextAlign.Center) }
        items(contacts) { c ->
            ListCard(R.drawable.ic_phone, c.name, if (c.primary) "Called first · texted" else "Texted your location",
                trailing = if (c.primary) ({ Text("PRIMARY", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = FG.OnEmerald, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(FG.Emerald).padding(horizontal = 6.dp, vertical = 2.dp)) }) else null)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_smartphone), null, tint = FG.Muted, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text("Edit contacts on your phone", fontSize = 12.sp, color = FG.Muted)
            }
        }
    }
}

@Composable
fun SensitivityScreen() {
    ListScreen {
        item { ScreenTitle(R.drawable.ic_sliders, "Sensitivity") }
        item { ListCard(R.drawable.ic_shield_check, "Standard", "Tuned for the wrist", selected = true) }
        // shortcut: only the Standard thresholds are tuned and tested; enable these once tune_detector.py produces them.
        item { ListCard(R.drawable.ic_activity, "More sensitive", "Not tuned yet", enabled = false) }
        item { ListCard(R.drawable.ic_shield, "Fewer prompts", "Not tuned yet", enabled = false) }
    }
}

@Composable
fun SettingsScreen(phoneConnected: Boolean?, alarmSound: Boolean, onAlarmSound: (Boolean) -> Unit, onRecordData: () -> Unit) {
    ListScreen {
        item { ScreenTitle(null, "Settings") }
        item {
            ListCard(R.drawable.ic_smartphone, "Phone", "Sends texts and calls for help", trailing = {
                Text(when (phoneConnected) { true -> "Connected"; false -> "Not connected"; null -> "Checking…" },
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (phoneConnected == true) FG.Emerald else FG.Amber)
            })
        }
        item { ListCard(R.drawable.ic_timer, "Wait before calling", "If you don't move", iconTint = FG.Text, iconBg = FG.Gray, trailing = { Text("${DetectionService.IMMOBILE_S} s", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = FG.Text) }) }
        item {
            ListCard(R.drawable.ic_volume, "Alarm sound", "When the phone can't be reached", iconTint = FG.Text, iconBg = FG.Gray, onClick = { onAlarmSound(!alarmSound) }, trailing = {
                Switch(checked = alarmSound, colors = SwitchDefaults.colors(checkedThumbColor = FG.Emerald, checkedTrackColor = FG.EmeraldTint))
            })
        }
        item { ListCard(R.drawable.ic_record, "Record data", "Help improve detection", iconTint = FG.CrimsonSoft, iconBg = FG.CrimsonTint, onClick = onRecordData) }
    }
}

// ── Record data ────────────────────────────────────────────────────────────────

/**
 * What the wearer is about to do. One label per session: stop and start a new
 * session when the activity changes. The false-alarm activities come from the
 * research notes on what triggers wrist fall detectors.
 */
val RECORDING_LABELS = listOf(
    "Normal day", "Walking", "Lying down quickly", "Sitting down hard", "Stairs",
    "Clapping", "Typing", "Hand-washing", "Hitting a desk", "Brushing teeth", "Taking watch off",
    "Simulated fall onto mat",
)

@Composable
fun RecordDataScreen(stats: RecordingService.Stats?, selected: String?, onSelect: (String) -> Unit, onStart: () -> Unit, onStop: () -> Unit) {
    if (stats != null) {
        val now = rememberNow()
        val elapsedMs = (now - stats.startedAtElapsedMs).coerceAtLeast(0L)
        val s = elapsedMs / 1000
        ListScreen(edge = { EdgeButton("Stop and save", null, FG.Crimson, Color.White, onClick = onStop) }) {
            item { Text("● Recording", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = FG.CrimsonSoft) }
            item { Text(stats.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = FG.Text) }
            item { Text("%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = FG.Text) }
            item {
                val rate = if (elapsedMs >= 2000L) "%.1f Hz".format(stats.accelEvents * 1000.0 / elapsedMs) else "—"
                Text("${stats.accelEvents} samples · $rate · %.1f MB".format(stats.bytesWritten / 1_000_000.0), fontSize = 12.sp, color = FG.Muted)
            }
            item {
                val b0 = stats.batteryAtStartPct; val b1 = stats.batteryNowPct
                Text("Battery ${if (b0 != null && b1 != null) "$b0% → $b1%" else "—"}", fontSize = 12.sp, color = FG.Muted)
            }
        }
        return
    }
    ListScreen(edge = { EdgeButton(if (selected == null) "Pick a label" else "Start recording", R.drawable.ic_record, if (selected == null) FG.Card else FG.Crimson, Color.White, onClick = { if (selected != null) onStart() }) }) {
        item { ScreenTitle(null, "Record data") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_lock), null, tint = FG.Muted, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(4.dp))
                Text("Stays on your watch until you share it", fontSize = 11.sp, color = FG.Muted)
            }
        }
        item { Text("WHAT ARE YOU DOING?", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = FG.Muted) }
        items(RECORDING_LABELS) { label ->
            ListCard(R.drawable.ic_activity, label, selected = label == selected, onClick = { onSelect(label) }, trailing = {})
        }
    }
}
