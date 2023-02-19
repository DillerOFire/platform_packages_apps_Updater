package com.android.updater.model

import kotlinx.serialization.Serializable

@Serializable
data class DeviceState(
        val device: String,
        val build: String,
        val buildIncremental: Long,
        val timestamp: Long,
        val sdkLevel: String,
        val securityPatchLevel: String,
        val partitionState: PartitionState? = null,
        val hwId: String = ""
)
