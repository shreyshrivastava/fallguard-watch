package com.example.activitycollector.wear

import android.content.Context
import org.json.JSONArray

/** Small watch-side settings and the contact names synced from the phone. */
object WatchPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("watch_prefs", Context.MODE_PRIVATE)

    fun alarmSound(c: Context) = prefs(c).getBoolean("alarm_sound", true)
    fun setAlarmSound(c: Context, on: Boolean) = prefs(c).edit().putBoolean("alarm_sound", on).apply()

    data class Contact(val name: String, val primary: Boolean)

    fun contacts(c: Context): List<Contact> = runCatching {
        val a = JSONArray(prefs(c).getString("contacts_json", "[]"))
        List(a.length()) { i -> a.getJSONObject(i).let { Contact(it.getString("name"), it.optBoolean("primary")) } }
    }.getOrDefault(emptyList())

    fun saveContactsJson(c: Context, json: String) = prefs(c).edit().putString("contacts_json", json).apply()
}
