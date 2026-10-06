package com.example.smartassist

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UserSettingsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(settings: UserSettings)

    @Query("SELECT * FROM user_settings WHERE id = 1")
    suspend fun getSettings(): UserSettings?

    @Query("UPDATE user_settings SET language = :language WHERE id = 1")
    suspend fun updateLanguage(language: String)

    @Query("UPDATE user_settings SET batteryThreshold = :threshold WHERE id = 1")
    suspend fun updateBatteryThreshold(threshold: Int)

    @Query("UPDATE user_settings SET powerSaverMode = :enabled WHERE id = 1")
    suspend fun updatePowerSaverMode(enabled: Boolean)

    @Query("UPDATE user_settings SET disableGptOnLowBattery = :disabled WHERE id = 1")
    suspend fun updateDisableGptOnLowBattery(disabled: Boolean)

    @Query("UPDATE user_settings SET reduceFrameRate = :reduced WHERE id = 1")
    suspend fun updateReduceFrameRate(reduced: Boolean)
}