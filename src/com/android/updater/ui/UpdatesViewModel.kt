package com.android.updater.ui

import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import com.android.updater.misc.Utils
import com.android.updater.model.OtaMeta
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

private const val TAG = "UpdatesViewModel"
class UpdatesViewModel: ViewModel() {
    private val client = Utils.client
    private var lastHtmlContent: String = ""

    private var prefs: SharedPreferences? = null
    private var prefsEditor: SharedPreferences.Editor? = null

    private val _metadata = MutableSharedFlow<OtaMeta?>(replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val metadata: SharedFlow<OtaMeta?> = _metadata

    // Uhhhoohhh cunny should be in repository class ahhhhh
    fun refresh(serverUrl: String, timestamp: Long) {
        Log.d(TAG, "refresh: ACCESSED")
        CoroutineScope(Dispatchers.IO).launch {

            client.get(serverUrl)
            {
                url {
                    parameters.append("timestamp", timestamp.toString())
                }
            }.let { response ->
                if (response.status.isSuccess())
                    _metadata.emit(
                        response.body<OtaMeta>()
                    )
                else {
                    _metadata.emit(null)
                }
            }
        }
    }

    fun htmlContentEqual(html: String): Boolean {
        return if (html != lastHtmlContent) {
            lastHtmlContent = html
            true
        } else {
            false
        }
    }
}