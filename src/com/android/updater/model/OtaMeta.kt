package com.android.updater.model

import android.util.Base64
import android.util.Log
import com.android.updater.misc.Utils
import com.android.updaterimport.UpdatesCheckReceiver
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection

@Serializable
data class OtaMeta(
        val currentDownloadUrl: String,
        val changelogUrl: String,
        val originalFilename: String,
        val type: OtaType = OtaType.UNKNOWN,
        val sizeBytes: Long,
        val wipe: Boolean = false,
        val downgrade: Boolean = false
) {
    enum class OtaType(value: Int) {
        UNKNOWN(0),
        AB(1),
        BLOCK(2),
        BRICK(3)
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun toByteArray(): ByteArray {
        return ProtoBuf.encodeToByteArray(this)
    }
    @OptIn(ExperimentalSerializationApi::class)
    fun fromByteArray(bytes: ByteArray): ByteArray {
        return ProtoBuf.decodeFromByteArray(bytes)
    }
}
