package com.example.smartassist

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.InputStream

/**
 * Urdu Translator for YOLO object classes
 * Converts English object names to Urdu for narration
 */
class UrduTranslator(private val context: Context) {

    companion object {
        private const val TAG = "UrduTranslator"
        private const val TRANSLATION_FILE = "urdu_translations.json"
    }

    private val translations: MutableMap<String, String> = mutableMapOf()
    private var isLoaded = false

    init {
        loadTranslations()
    }

    private fun loadTranslations() {
        try {
            val inputStream: InputStream = context.assets.open(TRANSLATION_FILE)
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)

            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                translations[key] = jsonObject.getString(key)
            }

            isLoaded = true
            Log.d(TAG, "Loaded ${translations.size} Urdu translations")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Urdu translations: ${e.message}")
            isLoaded = false
        }
    }

    fun translate(englishLabel: String): String {
        if (!isLoaded) return englishLabel
        val lowerKey = englishLabel.lowercase()
        val translation = translations[lowerKey]
        return if (translation != null) {
            translation
        } else {
            for ((key, value) in translations) {
                if (lowerKey.contains(key) || key.contains(lowerKey)) {
                    return value
                }
            }
            englishLabel
        }
    }

    fun createUrduNarration(detections: List<Detection>, imgW: Int, imgH: Int): String {
        if (detections.isEmpty()) return "اردگرد کو دیکھ رہا ہوں۔"
        val imgArea = imgW.toFloat() * imgH.toFloat()
        val picked = detections
            .mapNotNull { d ->
                val best = d.categories.maxByOrNull { it.score } ?: return@mapNotNull null
                val box = d.boundingBox
                val ratio = (box.width() * box.height()) / imgArea
                Triple(d, best, ratio)
            }
            .filter { (_, best, ratio) -> best.score >= 0.35f && ratio >= 0.01f }
            .sortedByDescending { (_, best, _) -> best.score }
            .distinctBy { (_, best, _) -> best.label.lowercase() }
            .take(3)
        if (picked.isEmpty()) return "اردگرد کو دیکھ رہا ہوں۔"
        val parts = mutableListOf<String>()
        for ((d, best, ratio) in picked) {
            val urduLabel = translate(best.label)
            val box = d.boundingBox
            val cx = (box.left + box.right) / 2f
            val pos = getUrduPosition(cx, imgW)
            val dist = getUrduDistance(ratio)
            parts.add("$urduLabel $pos، $dist")
        }
        return "میں دیکھ رہا ہوں " + parts.joinToString(". ") + "۔"
    }

    private fun getUrduPosition(cx: Float, imgW: Int): String {
        return when {
            cx < imgW * 0.35f -> "آپ کی بائیں طرف"
            cx > imgW * 0.65f -> "آپ کی دائیں طرف"
            else -> "سیدھے سامنے"
        }
    }

    private fun getUrduDistance(ratio: Float): String {
        return when {
            ratio >= 0.30f -> "بہت قریب، ایک قدم سے بھی کم"
            ratio >= 0.15f -> "تقریباً 1-2 قدم دور"
            ratio >= 0.08f -> "تقریباً 3 قدم دور"
            ratio >= 0.03f -> "تقریباً 4-5 قدم دور"
            ratio >= 0.015f -> "تقریباً 6 قدم دور"
            else -> "دور، 7+ قدم"
        }
    }

    fun isAvailable(): Boolean = isLoaded

    /**
     * Get danger alert in Urdu - handles both English and Urdu input
     */
    fun getUrduDangerAlert(dangerText: String): String {
        val lowerText = dangerText.lowercase()
        var objectName = extractObjectName(lowerText)
        val position = extractPosition(lowerText)
        val distance = extractDistance(lowerText)

        if (objectName.isNotEmpty()) {
            val urduObject = translate(objectName)
            val action = getSpecificDangerAction(objectName)
            return when {
                position.isNotEmpty() && distance.isNotEmpty() -> "خطرہ! $urduObject $position، $distance۔ $action"
                position.isNotEmpty() -> "خطرہ! $urduObject $position۔ $action"
                distance.isNotEmpty() -> "خطرہ! $urduObject $distance۔ $action"
                else -> "خطرہ! $urduObject۔ $action"
            }
        }
        return getGenericDangerMessage(lowerText)
    }

    private fun extractObjectName(text: String): String {
        val dangerObjects = listOf(
            "knife", "چھری", "چاقو",
            "gun", "pistol", "rifle", "shotgun", "firearm", "بندوق", "پستول",
            "scissors", "کینچی",
            "hammer", "ہتھوڑا",
            "wrench", "رینچ",
            "screwdriver", "پیچ کس",
            "drill", "ڈرل",
            "chainsaw", "چینسا",
            "axe", "کلہاڑی",
            "sword", "تلوار",
            "fire", "flame", "burning", "آگ", "شعلہ",
            "broken glass", "ٹوٹا شیشہ",
            "wire", "cable", "تار", "کیبل",
            "snake", "سانپ",
            "scorpion", "بچھو",
            "lion", "شیر", "tiger", "bear", "ریچھ", "shark", "شارک", "crocodile", "مگرمچھ"
        )
        for (obj in dangerObjects) {
            if (text.contains(obj)) return obj
        }
        return ""
    }

    private fun extractPosition(text: String): String {
        return when {
            text.contains("on your left") || text.contains("بائیں طرف") || text.contains("بائیں") -> "آپ کی بائیں طرف"
            text.contains("on your right") || text.contains("دائیں طرف") || text.contains("دائیں") -> "آپ کی دائیں طرف"
            text.contains("directly ahead") || text.contains("سیدھے سامنے") || text.contains("سامنے") -> "سیدھے سامنے"
            else -> ""
        }
    }

    private fun extractDistance(text: String): String {
        return when {
            text.contains("extremely close") || text.contains("less than 1 step") || text.contains("بالکل قریب") -> "بہت قریب، ایک قدم سے بھی کم"
            text.contains("very close") || text.contains("بہت قریب") -> "بہت قریب"
            text.contains("1 step") || text.contains("ایک قدم") -> "تقریباً 1 قدم دور"
            text.contains("2 steps") || text.contains("دو قدم") -> "تقریباً 2 قدم دور"
            text.contains("3 steps") || text.contains("3-4 steps") -> "تقریباً 3-4 قدم دور"
            text.contains("5 steps") || text.contains("5-6 steps") -> "تقریباً 5-6 قدم دور"
            else -> ""
        }
    }

    private fun getSpecificDangerAction(objectName: String): String {
        val lower = objectName.lowercase()
        return when {
            lower.contains("knife") || lower.contains("چھری") || lower.contains("چاقو") -> "فوری طور پر پیچھے ہٹیں اور تیز دھار سے بچیں۔"
            lower.contains("gun") || lower.contains("pistol") || lower.contains("rifle") ||
                    lower.contains("shotgun") || lower.contains("firearm") || lower.contains("بندوق") || lower.contains("پستول") -> "فوری طور پر بھاگ جائیں اور محفوظ جگہ چھپ جائیں۔"
            lower.contains("scissors") || lower.contains("کینچی") -> "کینچی کا تیز دھار خطرناک ہے۔ احتیاط سے پیچھے ہٹیں۔"
            lower.contains("hammer") || lower.contains("ہتھوڑا") -> "ہتھوڑا گرنے سے چوٹ لگ سکتی ہے۔ محفوظ فاصلہ بنائیں۔"
            lower.contains("wrench") || lower.contains("رینچ") -> "رینچ ایک بھاری اوزار ہے۔ دور رہیں اور محتاط رہیں۔"
            lower.contains("screwdriver") || lower.contains("پیچ کس") -> "پیچ کس کا نوک دار سرا خطرناک ہے۔ احتیاط برتیں۔"
            lower.contains("drill") || lower.contains("ڈرل") -> "ڈرل مشین خطرناک ہے۔ فوری طور پر دور ہو جائیں۔"
            lower.contains("chainsaw") || lower.contains("چینسا") -> "چینسا بہت خطرناک اوزار ہے۔ فوری طور پر علاقہ چھوڑیں۔"
            lower.contains("axe") || lower.contains("کلہاڑی") -> "کلہاڑی سے شدید چوٹ لگ سکتی ہے۔ دور رہیں۔"
            lower.contains("sword") || lower.contains("تلوار") -> "تلوار انتہائی خطرناک ہے۔ فوری طور پر محفوظ جگہ پر جائیں۔"
            lower.contains("fire") || lower.contains("flame") || lower.contains("burning") ||
                    lower.contains("آگ") || lower.contains("شعلہ") -> "آگ لگ گئی ہے! فوری طور پر علاقہ چھوڑیں اور باہر نکلیں۔"
            lower.contains("broken glass") || lower.contains("ٹوٹا شیشہ") -> "ٹوٹا ہوا شیشہ! حرکت نہ کریں۔ شیشے سے بچ کر جائیں۔"
            lower.contains("wire") || lower.contains("cable") || lower.contains("تار") || lower.contains("کیبل") -> "برقی تار! اس پر قدم نہ رکھیں۔ احتیاط سے گزریں۔"
            lower.contains("snake") || lower.contains("سانپ") -> "سانپ خطرناک ہے! حرکت نہ کریں اور آہستہ سے پیچھے ہٹیں۔"
            lower.contains("scorpion") || lower.contains("بچھو") -> "بچھو کا ڈنک خطرناک ہے! دور رہیں اور احتیاط برتیں۔"
            lower.contains("lion") || lower.contains("شیر") -> "شیر خطرناک جانور ہے! فوری طور پر بھاگ جائیں اور محفوظ جگہ تلاش کریں۔"
            lower.contains("tiger") -> "شیر خطرناک ہے! فوری طور پر بھاگ جائیں۔"
            lower.contains("bear") || lower.contains("ریچھ") -> "ریچھ خطرناک ہے! آہستہ سے پیچھے ہٹیں اور شور نہ مچائیں۔"
            lower.contains("shark") || lower.contains("شارک") -> "شارک خطرناک مچھلی ہے! فوری طور پر پانی سے باہر نکلیں۔"
            lower.contains("crocodile") || lower.contains("مگرمچھ") -> "مگرمچھ بہت خطرناک ہے! فوری طور پر دور بھاگ جائیں۔"
            else -> "فوری طور پر محفوظ جگہ پر جائیں۔"
        }
    }

    private fun getGenericDangerMessage(text: String): String {
        return when {
            text.contains("knife") || text.contains("چھری") -> "خطرہ! چھری کا خطرہ ہے! فوری طور پر پیچھے ہٹیں۔"
            text.contains("gun") || text.contains("بندوق") -> "خطرہ! بندوق کا خطرہ ہے! بھاگ جائیں اور چھپ جائیں۔"
            text.contains("fire") || text.contains("آگ") -> "خطرہ! آگ لگی ہے! فوری طور پر باہر نکلیں۔"
            text.contains("scissors") -> "خطرہ! کینچی کا خطرہ ہے! احتیاط سے پیچھے ہٹیں۔"
            text.contains("hammer") -> "خطرہ! ہتھوڑا خطرناک ہے! دور رہیں۔"
            text.contains("snake") -> "خطرہ! سانپ ہے! حرکت نہ کریں۔"
            text.contains("broken glass") -> "خطرہ! ٹوٹا ہوا شیشہ! حرکت نہ کریں۔"
            else -> "خطرہ! احتیاط برتیں۔ فوری طور پر محفوظ جگہ پر جائیں۔"
        }
    }
}