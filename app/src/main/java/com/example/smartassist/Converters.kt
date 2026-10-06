package com.example.smartassist

import androidx.room.TypeConverter

class Converters {

    @TypeConverter
    fun fromIntArray(value: IntArray): String {
        return value.joinToString(",")
    }

    @TypeConverter
    fun toIntArray(value: String): IntArray {
        if (value.isEmpty()) return IntArray(0)
        return value.split(",").map { it.toInt() }.toIntArray()
    }
}