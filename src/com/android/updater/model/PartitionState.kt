package com.android.updater.model

import kotlinx.serialization.Serializable

@Serializable
data class PartitionState(
        val partitionName: String,
        val device: String,
        val build: String,
        val version: String
)
