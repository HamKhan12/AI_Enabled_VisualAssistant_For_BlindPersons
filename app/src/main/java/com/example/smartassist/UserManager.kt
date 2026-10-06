package com.example.smartassist

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class UserManager(private val context: Context) {

    private var repository: AppRepository? = null

    fun initRepository(database: AppDatabase) {
        repository = AppRepository(database.userSettingsDao(), database.sceneHistoryDao())
    }

    fun saveLanguage(language: String) {
        // Use runBlocking to ensure save completes immediately
        runBlocking(Dispatchers.IO) {
            repository?.saveLanguage(language)
        }
    }

    fun getLanguage(): String {
        // Use runBlocking to get value immediately
        return runBlocking(Dispatchers.IO) {
            repository?.getLanguage() ?: "en"
        }
    }

    fun isLanguageSelected(): Boolean {
        return runBlocking(Dispatchers.IO) {
            repository?.isLanguageSelected() ?: false
        }
    }
}