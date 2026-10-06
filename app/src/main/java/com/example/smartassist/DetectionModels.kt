package com.example.smartassist

import android.graphics.RectF

/**
 * Minimal detection models that mimic the fields you already use in SceneActivity:
 * - detection.categories.maxByOrNull { it.score }?.label
 * - detection.boundingBox
 */
data class Category(
    val label: String,
    val score: Float
)

data class Detection(
    val boundingBox: RectF,
    val categories: List<Category>
)