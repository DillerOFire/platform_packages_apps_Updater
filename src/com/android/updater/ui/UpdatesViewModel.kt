package com.android.updater.ui

import androidx.lifecycle.ViewModel
import com.android.updater.misc.Utils
import com.android.updater.model.OtaMeta
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.protobuf.protobuf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi

private const val TAG = "UpdatesViewModel"
class UpdatesViewModel: ViewModel() {
    private val client = Utils.client

    private val _metadata: MutableStateFlow<OtaMeta?> = MutableStateFlow(null)
    val metadata: StateFlow<OtaMeta?> = _metadata

    // Uhhhoohhh cunny should be in repository class ahhhhh
    fun refresh(serverUrl: String) {
        CoroutineScope(Dispatchers.IO).launch {
            _metadata.emit(
                    client.get(serverUrl).body<OtaMeta>()
            )
        }
    }
}