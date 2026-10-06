package com.example.smartassist

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow

class AppRepository(
    private val userSettingsDao: UserSettingsDao,
    private val sceneHistoryDao: SceneHistoryDao
) {

    // ========== User Settings ==========

    suspend fun getSettings(): UserSettings {
        return userSettingsDao.getSettings() ?: UserSettings().also {
            userSettingsDao.insert(it)
        }
    }

    suspend fun saveLanguage(language: String) {
        userSettingsDao.updateLanguage(language)
    }

    suspend fun getLanguage(): String {
        return getSettings().language
    }

    suspend fun isLanguageSelected(): Boolean {
        return getSettings().language != "en"
    }

    suspend fun saveBatteryThreshold(threshold: Int) {
        userSettingsDao.updateBatteryThreshold(threshold)
    }

    suspend fun savePowerSaverMode(enabled: Boolean) {
        userSettingsDao.updatePowerSaverMode(enabled)
    }

    suspend fun saveDisableGptOnLowBattery(disabled: Boolean) {
        userSettingsDao.updateDisableGptOnLowBattery(disabled)
    }

    suspend fun saveReduceFrameRate(reduced: Boolean) {
        userSettingsDao.updateReduceFrameRate(reduced)
    }

    // ========== Scene History (Cache) ==========

    fun generateImageFingerprint(bitmap: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(bitmap, 16, 16, false)
        val pixels = IntArray(256)
        small.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        small.recycle()

        val fingerprint = IntArray(64)
        for (i in 0 until 64) {
            val x = (i % 8) * 2
            val y = (i / 8) * 2
            var sum = 0
            for (dy in 0..1) {
                for (dx in 0..1) {
                    val idx = (y + dy) * 16 + (x + dx)
                    val r = (pixels[idx] shr 16) and 0xFF
                    val g = (pixels[idx] shr 8) and 0xFF
                    val b = pixels[idx] and 0xFF
                    sum += (r + g + b) / 3
                }
            }
            fingerprint[i] = sum / 4
        }
        return fingerprint
    }

    fun generateImageHash(fingerprint: IntArray): String {
        return fingerprint.joinToString(",") { it.toString() }.hashCode().toString()
    }

    fun calculateSimilarity(fp1: IntArray, fp2: IntArray): Float {
        if (fp1.size != fp2.size) return 0f
        var diff = 0
        for (i in fp1.indices) {
            diff += kotlin.math.abs(fp1[i] - fp2[i])
        }
        val maxDiff = 255 * fp1.size
        return 1.0f - (diff.toFloat() / maxDiff)
    }

    suspend fun findSimilarScene(fingerprint: IntArray, language: String, threshold: Float = 0.97f): String? {
        val recentScenes = sceneHistoryDao.getRecentScenes(System.currentTimeMillis() - (24 * 60 * 60 * 1000L))

        var bestMatch: SceneHistory? = null
        var bestSimilarity = 0f

        for (scene in recentScenes) {
            val sceneFingerprint = if (scene.fingerprintString.isNotEmpty()) {
                scene.fingerprintString.split(",").map { it.toInt() }.toIntArray()
            } else continue

            val similarity = calculateSimilarity(fingerprint, sceneFingerprint)
            if (similarity > bestSimilarity && similarity >= threshold) {
                bestSimilarity = similarity
                bestMatch = scene
            }
        }

        return if (bestMatch != null) {
            if (language == "ur") bestMatch.descriptionUr else bestMatch.descriptionEn
        } else null
    }

    suspend fun saveScene(
        fingerprint: IntArray,
        description: String,
        language: String,
        wasDanger: Boolean = false
    ) {
        val imageHash = generateImageHash(fingerprint)
        val existing = sceneHistoryDao.findByHash(imageHash)

        if (existing != null) {
            val updated = if (language == "ur") {
                existing.copy(descriptionUr = description, timestamp = System.currentTimeMillis())
            } else {
                existing.copy(descriptionEn = description, timestamp = System.currentTimeMillis())
            }
            sceneHistoryDao.insert(updated)
        } else {
            val newScene = SceneHistory(
                imageHash = imageHash,
                fingerprintString = fingerprint.joinToString(","),
                descriptionEn = if (language == "en") description else "",
                descriptionUr = if (language == "ur") description else "",
                language = language,
                timestamp = System.currentTimeMillis(),
                wasDanger = wasDanger
            )
            sceneHistoryDao.insert(newScene)
        }
        sceneHistoryDao.deleteOldScenes()
    }

    fun getAllScenes(): Flow<List<SceneHistory>> = sceneHistoryDao.getAllScenes()

    suspend fun getSceneCount(): Int = sceneHistoryDao.getCount()

    suspend fun getDangerCount(): Int = sceneHistoryDao.getDangerCount()

    suspend fun clearAllScenes() {
        sceneHistoryDao.deleteAll()
    }
}