package com.example.activitycollector.wear

import com.example.activitycollector.WatchProtocol
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Receives the phone's SOS result and the synced contact names. */
class WearMessageService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == WatchProtocol.PATH_SOS_RESULT) {
            SosController.onPhoneResult(applicationContext, String(event.data, Charsets.UTF_8))
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { e ->
            if (e.dataItem.uri.path == WatchProtocol.PATH_CONTACTS) {
                DataMapItem.fromDataItem(e.dataItem).dataMap.getString(WatchProtocol.KEY_CONTACTS_JSON)
                    ?.let { WatchPrefs.saveContactsJson(applicationContext, it) }
            }
        }
    }
}
