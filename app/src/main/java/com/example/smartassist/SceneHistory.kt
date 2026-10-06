package com.example.smartassist

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scene_history")
data class SceneHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val imageHash: String,
    val fingerprintString: String,
    val descriptionEn: String,
    val descriptionUr: String,
    val language: String,
    val timestamp: Long = System.currentTimeMillis(),
    val wasDanger: Boolean = false
)