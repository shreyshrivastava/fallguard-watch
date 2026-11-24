package com.example.activitycollector

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * EmergencyDispatcher
 * ===================
 * Dispatches emergency SOS alerts when a confirmed fall is not cancelled by the user:
 * 1. Obtains the most recent GPS location coordinates (if permitted).
 * 2. Broadcasts an emergency SMS with GPS Google Maps link to ALL registered contacts.
 * 3. Places an automated direct telephone call to the 1st (Primary) emergency contact.
 */
object EmergencyDispatcher {

    private const val TAG = "EmergencyDispatcher"

    data class DispatchResult(
        val totalContacts: Int,
        val smsSuccessCount: Int,
        val primaryCallAttempted: Boolean,
        val primaryContactName: String?,
        val locationFound: Boolean,
        val summary: String
    )

    fun dispatchEmergency(context: Context): DispatchResult {
        Log.w(TAG, "🚨 DISPATCHING EMERGENCY SOS PROTOCOL")
        val contactManager = EmergencyContactManager(context)
        val contacts = contactManager.getContacts()

        if (contacts.isEmpty()) {
            val msg = "No emergency contacts registered! Please add contacts in the app."
            Log.e(TAG, msg)
            return DispatchResult(
                totalContacts = 0,
                smsSuccessCount = 0,
                primaryCallAttempted = false,
                primaryContactName = null,
                locationFound = false,
                summary = msg
            )
        }

        // 1. Fresh GPS fix if one arrives in time, else the last known position.
        val fresh = getFreshLocation(context)
        val location = fresh ?: getLastKnownLocation(context)
        val locationStr = if (location != null) mapsLink(location, isFresh = fresh != null) else "Location unavailable"

        val timeStr = SimpleDateFormat("HH:mm:ss dd-MMM", Locale.getDefault()).format(Date())
        val smsBody = "EMERGENCY SOS: Fall detected by FallGuard! User did not respond to check-in. Time: $timeStr. Location: $locationStr. Please call or assist immediately!"

        // 2. Send SMS to ALL contacts in list
        var smsSuccessCount = 0
        val hasSmsPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasSmsPermission) {
            try {
                val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

                for (contact in contacts) {
                    try {
                        val parts = smsManager.divideMessage(smsBody)
                        if (parts.size > 1) {
                            smsManager.sendMultipartTextMessage(contact.phone, null, parts, null, null)
                        } else {
                            smsManager.sendTextMessage(contact.phone, null, smsBody, null, null)
                        }
                        smsSuccessCount++
                        Log.i(TAG, "SOS SMS sent to ${contact.name} (${contact.phone})")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed sending SMS to ${contact.name}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing SmsManager: ${e.message}")
            }
        } else {
            Log.w(TAG, "SEND_SMS permission not granted — skipping direct SMS transmission.")
        }

        // 3. Initiate phone call to 1st (Primary) contact
        val primary = contacts.first()
        var callPlaced = false
        val hasCallPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        try {
            if (hasCallPermission) {
                // TelecomManager.placeCall works from a background service; starting the
                // call activity directly is blocked by Android 10+ when the app isn't visible.
                context.getSystemService(TelecomManager::class.java)
                    .placeCall(Uri.parse("tel:${primary.phone}"), Bundle())
            } else {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${primary.phone}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
            callPlaced = true
            Log.i(TAG, "Emergency call initiated to primary contact: ${primary.name} (${primary.phone})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate call to primary contact: ${e.message}")
        }

        val summary = "SOS Dispatched: Call initiated to ${primary.name}. SMS sent to $smsSuccessCount of ${contacts.size} contacts."
        return DispatchResult(
            totalContacts = contacts.size,
            smsSuccessCount = smsSuccessCount,
            primaryCallAttempted = callPlaced,
            primaryContactName = primary.name,
            locationFound = location != null,
            summary = summary
        )
    }

    /** Texts every contact that the wearer is OK, after an SOS. Returns how many texts were sent. */
    fun sendSafeMessage(context: Context): Int {
        val contacts = EmergencyContactManager(context).getContacts()
        if (contacts.isEmpty() || ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) return 0
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION") SmsManager.getDefault()
        }
        val body = "FallGuard update: I'm OK. Please ignore the earlier emergency alert."
        var sent = 0
        for (c in contacts) {
            try { sms.sendTextMessage(c.phone, null, body, null, null); sent++ }
            catch (e: Exception) { Log.e(TAG, "Failed sending safe message to ${c.name}: ${e.message}") }
        }
        return sent
    }

    /** Google Maps link with accuracy and when the position was measured. */
    private fun mapsLink(loc: Location, isFresh: Boolean): String {
        val at = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(loc.time))
        val accuracy = if (loc.hasAccuracy()) " ±${loc.accuracy.toInt()} m" else ""
        val age = if (isFresh) "now" else "last known, $at"
        return "https://www.google.com/maps/search/?api=1&query=" +
            String.format(Locale.US, "%.6f,%.6f", loc.latitude, loc.longitude) + " ($age$accuracy)"
    }

    /**
     * Asks for a new GPS fix (falling back to network) and waits up to [FRESH_FIX_TIMEOUT_S].
     * Returns null on the main thread, where waiting would freeze the app.
     */
    private fun getFreshLocation(context: Context): Location? {
        if (Looper.myLooper() == Looper.getMainLooper() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!lm.isProviderEnabled(provider)) continue
            val latch = CountDownLatch(1)
            var result: Location? = null
            val cancel = CancellationSignal()
            try {
                lm.getCurrentLocation(provider, cancel, Executors.newSingleThreadExecutor()) { result = it; latch.countDown() }
                if (!latch.await(FRESH_FIX_TIMEOUT_S, TimeUnit.SECONDS)) cancel.cancel()
            } catch (e: SecurityException) {
                Log.w(TAG, "No location permission for $provider: ${e.message}")
            }
            if (result != null) return result
        }
        return null
    }

    private const val FRESH_FIX_TIMEOUT_S = 10L

    private fun getLastKnownLocation(context: Context): Location? {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null

        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val gpsLoc = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } else null

            val netLoc = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else null

            when {
                gpsLoc != null && netLoc != null -> if (gpsLoc.time > netLoc.time) gpsLoc else netLoc
                gpsLoc != null -> gpsLoc
                else -> netLoc
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire location: ${e.message}")
            null
        }
    }
}
