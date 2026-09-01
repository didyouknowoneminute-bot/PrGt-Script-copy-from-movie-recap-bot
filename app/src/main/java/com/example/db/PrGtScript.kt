package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "prgt_scripts")
data class PrGtScript(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileName: String,
    val fileSize: String,
    val durationSec: String,
    val scriptText: String,
    val timestamp: Long,
    val language: String,
    val modelId: String,
    val targetLength: String,
    val pov: String,
    val tone: String,
    val geminiFileUri: String? = null,
    val mimeType: String? = null,
    val partsCount: Int = 0,
    val scriptPartsJson: String = "",
    val totalApiCalls: Int = 0,
    val successApiCalls: Int = 0,
    val failedApiCalls: Int = 0,
    val previousVersionsJson: String = "[]"
)
