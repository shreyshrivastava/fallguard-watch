package com.example.activitycollector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Receives the watch's SOS and "I'm OK" messages and acts on them with the phone's
 * SIM: texts every emergency contact the location, calls the primary contact, and
 * tells the watch what happened. Android starts this service when a message arrives,
 * even if the app isn't open.
 */
class WatchRelayService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WatchProtocol.PATH_SOS -> handleSos(event.sourceNodeId, String(event.data, Charsets.UTF_8))
            WatchProtocol.PATH_SAFE -> {
                val sent = EmergencyDispatcher.sendSafeMessage(this)
                Log.i(TAG, "Watch says wearer is OK; sent $sent safe texts")
                notifyUser("You're marked as OK", "Texted $sent contact(s) that you're safe.")
            }
        }
    }

    private fun handleSos(watchNodeId: String, reason: String) {
        Log.w(TAG, "SOS from watch ($reason)")
        val result = EmergencyDispatcher.dispatchEmergency(this)
        val contacts = EmergencyContactManager(this).getContacts()
        val texted = if (result.smsSuccessCount > 0) contacts.map { it.name } else emptyList()

        AlertHistoryManager(this).recordAlert(
            AlertEvent(outcome = AlertEvent.Outcome.SOS_DISPATCHED, summary = "From your watch: ${result.summary}")
        )
        notifyUser("Fall detected by your watch", result.summary)

        val json = WatchProtocol.sosResultJson(
            contacts = result.totalContacts, textsSent = result.smsSuccessCount,
            callPlaced = result.primaryCallAttempted, primaryName = result.primaryContactName,
            textedNames = texted, locationFound = result.locationFound,
        )
        try {
            Tasks.await(
                Wearable.getMessageClient(this).sendMessage(watchNodeId, WatchProtocol.PATH_SOS_RESULT, json.toByteArray()),
                10, TimeUnit.SECONDS,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't report the SOS result to the watch: ${e.message}")
        }
    }

    private fun notifyUser(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Watch alerts", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val TAG = "WatchRelayService"
        private const val CHANNEL = "watch_alerts"
        private const val NOTIFICATION_ID = 7101

        /** Shares contact names (and which is primary) with the watch. Phone numbers stay on the phone. */
        fun pushContactsToWatch(context: Context) {
            val contacts = EmergencyContactManager(context).getContacts()
            val json = JSONArray(contacts.mapIndexed { i, c ->
                JSONObject().put("name", c.name).put("primary", i == 0)
            }).toString()
            val request = PutDataMapRequest.create(WatchProtocol.PATH_CONTACTS).apply {
                dataMap.putString(WatchProtocol.KEY_CONTACTS_JSON, json)
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request)
                .addOnFailureListener { Log.w(TAG, "Contacts not synced to watch: ${it.message}") }
        }
    }
}
