package com.example.smartassist

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_settings")
data class UserSettings(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 1,
    val language: String = "en",
    val batteryThreshold: Int = 20,
    val powerSaverMode: Boolean = false,
    val disableGptOnLowBattery: Boolean = true,
    val reduceFrameRate: Boolean = true
)