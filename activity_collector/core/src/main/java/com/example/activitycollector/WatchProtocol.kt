package com.example.activitycollector

/**
 * Wearable Data Layer paths shared by the phone app and the watch app.
 * Both apps must have the same application ID and signing key for these to arrive.
 */
object WatchProtocol {
    /** Watch -> phone: send the SOS (text every contact, call the primary). Payload: reason as UTF-8. */
    const val PATH_SOS = "/fallguard/sos"
    /** Watch -> phone: the wearer is OK; text every contact that they're safe. */
    const val PATH_SAFE = "/fallguard/safe"
    /** Phone -> watch: result of an SOS. Payload: JSON from [sosResultJson]. */
    const val PATH_SOS_RESULT = "/fallguard/sos_result"
    /** Phone -> watch data item: contact names only (no numbers leave the phone). */
    const val PATH_CONTACTS = "/fallguard/contacts"
    const val KEY_CONTACTS_JSON = "contacts_json"

    fun sosResultJson(
        contacts: Int, textsSent: Int, callPlaced: Boolean, primaryName: String?,
        textedNames: List<String>, locationFound: Boolean,
    ): String = org.json.JSONObject().apply {
        put("contacts", contacts)
        put("texts_sent", textsSent)
        put("call_placed", callPlaced)
        put("primary_name", primaryName ?: "")
        put("texted_names", org.json.JSONArray(textedNames))
        put("location_found", locationFound)
    }.toString()
}
