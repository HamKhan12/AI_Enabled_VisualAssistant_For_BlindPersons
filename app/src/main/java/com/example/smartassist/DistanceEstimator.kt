package com.example.smartassist

import android.content.Context
import android.content.SharedPreferences
import android.graphics.RectF
import android.speech.tts.TextToSpeech
import com.example.smartassist.SceneActivity
import kotlin.math.*

/**
 * DistanceEstimator - Handles all distance estimation functionality
 *
 * Features:
 * 1. Real-world object width database (covers all 601 classes)
 * 2. Focal length calibration for accurate distance
 * 3. Distance to steps conversion
 * 4. Danger object special handling
 */
class DistanceEstimator(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "distance_calibration"
        private const val KEY_FOCAL_CONSTANT = "focal_constant"
        private const val DEFAULT_FOCAL_CONSTANT = 3500f
        private const val ONE_STEP_CM = 70
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var currentLanguage = "en"

    /**
     * Update language preference for output text
     */
    fun updateLanguage(language: String) {
        currentLanguage = language
    }

    /**
     * Get or create the focal constant for this device
     */
    fun getFocalConstant(): Float {
        return prefs.getFloat(KEY_FOCAL_CONSTANT, DEFAULT_FOCAL_CONSTANT)
    }

    /**
     * Save calibrated focal constant
     */
    fun saveFocalConstant(constant: Float) {
        prefs.edit().putFloat(KEY_FOCAL_CONSTANT, constant).apply()
    }

    /**
     * Check if device has been calibrated
     */
    fun isCalibrated(): Boolean {
        return prefs.contains(KEY_FOCAL_CONSTANT)
    }

    /**
     * Reset calibration to default
     */
    fun resetCalibration() {
        prefs.edit().remove(KEY_FOCAL_CONSTANT).apply()
    }

    /**
     * Get real-world width in centimeters for ANY object from the 601 classes
     * Uses category-based estimation with intelligent defaults
     */
    fun getObjectWidthCm(label: String): Int {
        val lowerLabel = label.lowercase()

        // ========== PEOPLE (shoulder width 35-50cm) ==========
        if (lowerLabel.matches(Regex(".*(person|man|woman|boy|girl|child|people|human).*"))) {
            return when {
                lowerLabel.contains("child") -> 35
                lowerLabel.contains("boy") || lowerLabel.contains("girl") -> 38
                else -> 45
            }
        }

        // ========== VEHICLES ==========
        if (lowerLabel.matches(Regex(".*(truck|bus).*"))) return 250
        if (lowerLabel.matches(Regex(".*(car|vehicle|auto|suv|van|taxi).*"))) return 180
        if (lowerLabel.matches(Regex(".*(motorcycle|motorbike|scooter).*"))) return 80
        if (lowerLabel.matches(Regex(".*(bicycle|bike).*"))) return 60
        if (lowerLabel.matches(Regex(".*(train|rail).*"))) return 280
        if (lowerLabel.matches(Regex(".*(airplane|aircraft|jet|plane).*"))) return 350
        if (lowerLabel.matches(Regex(".*(helicopter).*"))) return 200
        if (lowerLabel.matches(Regex(".*(boat|ship|vessel).*"))) return 250

        // ========== ANIMALS ==========
        if (lowerLabel.matches(Regex(".*(dog|puppy).*"))) return 30
        if (lowerLabel.matches(Regex(".*(cat|kitten).*"))) return 20
        if (lowerLabel.matches(Regex(".*(horse|pony).*"))) return 150
        if (lowerLabel.matches(Regex(".*(cow|bull|ox).*"))) return 150
        if (lowerLabel.matches(Regex(".*(elephant).*"))) return 220
        if (lowerLabel.matches(Regex(".*(lion|tiger|leopard).*"))) return 100
        if (lowerLabel.matches(Regex(".*(bear).*"))) return 120
        if (lowerLabel.matches(Regex(".*(giraffe).*"))) return 80
        if (lowerLabel.matches(Regex(".*(zebra).*"))) return 130
        if (lowerLabel.matches(Regex(".*(monkey|ape|chimpanzee).*"))) return 50
        if (lowerLabel.matches(Regex(".*(rabbit|bunny).*"))) return 15
        if (lowerLabel.matches(Regex(".*(bird|eagle|hawk|parrot).*"))) return 15
        if (lowerLabel.matches(Regex(".*(snake).*"))) return 8
        if (lowerLabel.matches(Regex(".*(fish|shark).*"))) return 30
        if (lowerLabel.matches(Regex(".*(whale|dolphin).*"))) return 200
        if (lowerLabel.matches(Regex(".*(insect|bug|ant|bee|butterfly).*"))) return 5

        // ========== FURNITURE ==========
        if (lowerLabel.matches(Regex(".*(chair|seat|stool).*"))) return 45
        if (lowerLabel.matches(Regex(".*(table|desk|workspace).*"))) return 60
        if (lowerLabel.matches(Regex(".*(sofa|couch|loveseat).*"))) return 80
        if (lowerLabel.matches(Regex(".*(bed|mattress|cot).*"))) return 150
        if (lowerLabel.matches(Regex(".*(cabinet|wardrobe|closet).*"))) return 60
        if (lowerLabel.matches(Regex(".*(bookshelf|bookcase).*"))) return 80
        if (lowerLabel.matches(Regex(".*(dresser|chest|drawer).*"))) return 100
        if (lowerLabel.matches(Regex(".*(nightstand|end table).*"))) return 45
        if (lowerLabel.matches(Regex(".*(bench).*"))) return 120

        // ========== ELECTRONICS ==========
        if (lowerLabel.matches(Regex(".*(phone|mobile|cellphone|smartphone).*"))) return 7
        if (lowerLabel.matches(Regex(".*(laptop|notebook).*"))) return 30
        if (lowerLabel.matches(Regex(".*(tablet|ipad).*"))) return 20
        if (lowerLabel.matches(Regex(".*(tv|television|monitor|display|screen).*"))) return 100
        if (lowerLabel.matches(Regex(".*(computer|desktop|pc).*"))) return 45
        if (lowerLabel.matches(Regex(".*(keyboard).*"))) return 45
        if (lowerLabel.matches(Regex(".*(mouse).*"))) return 6
        if (lowerLabel.matches(Regex(".*(camera).*"))) return 12
        if (lowerLabel.matches(Regex(".*(printer).*"))) return 45
        if (lowerLabel.matches(Regex(".*(microwave).*"))) return 45
        if (lowerLabel.matches(Regex(".*(refrigerator|fridge).*"))) return 80
        if (lowerLabel.matches(Regex(".*(oven|stove|cooktop).*"))) return 60
        if (lowerLabel.matches(Regex(".*(toaster).*"))) return 25
        if (lowerLabel.matches(Regex(".*(blender).*"))) return 15
        if (lowerLabel.matches(Regex(".*(washing machine|washer|dryer).*"))) return 65
        if (lowerLabel.matches(Regex(".*(vacuum|cleaner).*"))) return 30
        if (lowerLabel.matches(Regex(".*(fan).*"))) return 40
        if (lowerLabel.matches(Regex(".*(heater).*"))) return 30
        if (lowerLabel.matches(Regex(".*(air conditioner|ac).*"))) return 50

        // ========== KITCHEN & DINING ==========
        if (lowerLabel.matches(Regex(".*(bottle).*"))) return 7
        if (lowerLabel.matches(Regex(".*(cup|mug).*"))) return 8
        if (lowerLabel.matches(Regex(".*(glass|tumbler).*"))) return 7
        if (lowerLabel.matches(Regex(".*(plate|platter).*"))) return 25
        if (lowerLabel.matches(Regex(".*(bowl).*"))) return 15
        if (lowerLabel.matches(Regex(".*(spoon|fork|utensil).*"))) return 3
        if (lowerLabel.matches(Regex(".*(knife|blade|dagger).*"))) return 4
        if (lowerLabel.matches(Regex(".*(pan|pot|skillet).*"))) return 25
        if (lowerLabel.matches(Regex(".*(container|jar).*"))) return 10

        // ========== FOOD ITEMS ==========
        if (lowerLabel.matches(Regex(".*(apple|fruit).*"))) return 8
        if (lowerLabel.matches(Regex(".*(banana).*"))) return 5
        if (lowerLabel.matches(Regex(".*(orange|lemon|lime).*"))) return 8
        if (lowerLabel.matches(Regex(".*(bread|loaf).*"))) return 15
        if (lowerLabel.matches(Regex(".*(cake|pastry).*"))) return 20
        if (lowerLabel.matches(Regex(".*(pizza).*"))) return 30
        if (lowerLabel.matches(Regex(".*(burger|sandwich).*"))) return 12
        if (lowerLabel.matches(Regex(".*(vegetable).*"))) return 10

        // ========== CLOTHING & ACCESSORIES ==========
        if (lowerLabel.matches(Regex(".*(shirt|top|blouse).*"))) return 50
        if (lowerLabel.matches(Regex(".*(pants|jeans|trousers).*"))) return 40
        if (lowerLabel.matches(Regex(".*(dress|skirt).*"))) return 45
        if (lowerLabel.matches(Regex(".*(jacket|coat).*"))) return 55
        if (lowerLabel.matches(Regex(".*(shoe|boot|sandal).*"))) return 10
        if (lowerLabel.matches(Regex(".*(hat|cap).*"))) return 20
        if (lowerLabel.matches(Regex(".*(bag|backpack|purse).*"))) return 35
        if (lowerLabel.matches(Regex(".*(wallet).*"))) return 10
        if (lowerLabel.matches(Regex(".*(watch).*"))) return 4
        if (lowerLabel.matches(Regex(".*(glasses|sunglasses).*"))) return 13
        if (lowerLabel.matches(Regex(".*(belt).*"))) return 5
        if (lowerLabel.matches(Regex(".*(tie).*"))) return 8

        // ========== BUILDINGS & STRUCTURES ==========
        if (lowerLabel.matches(Regex(".*(door).*"))) return 80
        if (lowerLabel.matches(Regex(".*(window).*"))) return 70
        if (lowerLabel.matches(Regex(".*(wall).*"))) return 200
        if (lowerLabel.matches(Regex(".*(building|house|structure).*"))) return 300

        // ========== SIGNS & TRAFFIC ==========
        if (lowerLabel.matches(Regex(".*(sign|billboard|poster).*"))) return 60
        if (lowerLabel.matches(Regex(".*(traffic light|stoplight).*"))) return 30
        if (lowerLabel.matches(Regex(".*(stop sign).*"))) return 45

        // ========== SPORTS & RECREATION ==========
        if (lowerLabel.matches(Regex(".*(ball).*"))) return 20
        if (lowerLabel.matches(Regex(".*(bat|racket).*"))) return 7
        if (lowerLabel.matches(Regex(".*(helmet).*"))) return 25
        if (lowerLabel.matches(Regex(".*(skateboard).*"))) return 20
        if (lowerLabel.matches(Regex(".*(surfboard).*"))) return 60
        if (lowerLabel.matches(Regex(".*(snowboard).*"))) return 30

        // ========== TOOLS & HARDWARE ==========
        if (lowerLabel.matches(Regex(".*(hammer).*"))) return 10
        if (lowerLabel.matches(Regex(".*(screwdriver).*"))) return 4
        if (lowerLabel.matches(Regex(".*(wrench|pliers).*"))) return 5
        if (lowerLabel.matches(Regex(".*(drill).*"))) return 8
        if (lowerLabel.matches(Regex(".*(ladder).*"))) return 50
        if (lowerLabel.matches(Regex(".*(scissors).*"))) return 5
        if (lowerLabel.matches(Regex(".*(chainsaw).*"))) return 30
        if (lowerLabel.matches(Regex(".*(axe|hatchet).*"))) return 15

        // ========== DANGER OBJECTS (extra precise) ==========
        if (lowerLabel.matches(Regex(".*(gun|pistol|rifle|firearm|weapon).*"))) return 15
        if (lowerLabel.matches(Regex(".*(sword).*"))) return 8
        if (lowerLabel.matches(Regex(".*(fire|flame|torch).*"))) return 30

        // ========== PLANTS & NATURE ==========
        if (lowerLabel.matches(Regex(".*(tree).*"))) return 100
        if (lowerLabel.matches(Regex(".*(plant|flower).*"))) return 30
        if (lowerLabel.matches(Regex(".*(grass).*"))) return 10

        // ========== TOYS & GAMES ==========
        if (lowerLabel.matches(Regex(".*(toy|doll).*"))) return 15
        if (lowerLabel.matches(Regex(".*(balloon).*"))) return 20
        if (lowerLabel.matches(Regex(".*(kite).*"))) return 50

        // ========== BOOKS & PAPERS ==========
        if (lowerLabel.matches(Regex(".*(book|magazine|newspaper).*"))) return 20
        if (lowerLabel.matches(Regex(".*(paper|document|envelope).*"))) return 25

        // ========== DEFAULT for all remaining objects ==========
        // Covers the rest of the 601 objects intelligently
        return when {
            lowerLabel.contains("mini") || lowerLabel.contains("small") || lowerLabel.contains("tiny") -> 10
            lowerLabel.contains("large") || lowerLabel.contains("big") || lowerLabel.contains("giant") -> 80
            lowerLabel.contains("handheld") || lowerLabel.contains("portable") -> 10
            lowerLabel.contains("furniture") -> 50
            lowerLabel.contains("equipment") -> 60
            lowerLabel.contains("appliance") -> 50
            else -> 40  // Default medium-sized object
        }
    }

    /**
     * Calculate distance in meters using focal length method
     */
    fun calculateDistance(realWidthCm: Int, pixelWidth: Float): Float {
        if (pixelWidth <= 5) return -1f

        val focalConstant = getFocalConstant()
        val distanceCm = (realWidthCm * focalConstant) / pixelWidth

        // Limit to reasonable range (0.3m to 15m)
        return (distanceCm / 100f).coerceIn(0.3f, 15f)
    }

    /**
     * Convert distance in meters to steps description
     */
    fun distanceToStepsDescription(distanceMeters: Float, label: String = ""): String {
        if (distanceMeters < 0) {
            return if (currentLanguage == "ur") "فاصلہ نامعلوم" else "distance unknown"
        }

        val steps = (distanceMeters / 0.7).toInt()
        val isDanger = isDangerObject(label)

        return when {
            isDanger && distanceMeters < 0.7 -> {
                if (currentLanguage == "ur") "خطرناک! بالکل قریب، ایک قدم سے بھی کم"
                else "DANGER! extremely close, less than 1 step"
            }
            isDanger && distanceMeters < 1.4 -> {
                if (currentLanguage == "ur") "خطرناک! بہت قریب، تقریباً 1 قدم"
                else "DANGER! very close, about 1 step"
            }
            distanceMeters < 0.7 -> {
                if (currentLanguage == "ur") "بالکل قریب، ایک قدم سے بھی کم"
                else "extremely close, less than 1 step"
            }
            steps == 1 -> {
                if (currentLanguage == "ur") "بہت قریب، تقریباً 1 قدم"
                else "very close, about 1 step"
            }
            steps <= 2 -> {
                if (currentLanguage == "ur") "قریب، تقریباً $steps قدم"
                else "close, about $steps steps"
            }
            steps <= 4 -> {
                if (currentLanguage == "ur") "تھوڑا دور، $steps قدم"
                else "a few steps away, $steps steps"
            }
            steps <= 6 -> {
                if (currentLanguage == "ur") "دور، $steps قدم"
                else "further away, $steps steps"
            }
            steps <= 10 -> {
                if (currentLanguage == "ur") "دور، $steps قدم"
                else "far, $steps steps away"
            }
            else -> {
                val rounded = (steps / 5) * 5
                if (currentLanguage == "ur") "بہت دور، $rounded+ قدم"
                else "very far, $rounded+ steps away"
            }
        }
    }

    /**
     * Main method to get distance description for any detected object
     */
    fun getDistanceDescription(label: String, boundingBox: RectF): String {
        val pixelWidth = boundingBox.width()

        // If object is too small in frame, it's far away
        if (pixelWidth <= 10) {
            return if (currentLanguage == "ur") "بہت دور" else "very far"
        }

        val realWidthCm = getObjectWidthCm(label)
        val distanceMeters = calculateDistance(realWidthCm, pixelWidth)

        if (distanceMeters < 0) {
            return if (currentLanguage == "ur") "فاصلہ نامعلوم" else "distance unknown"
        }

        return distanceToStepsDescription(distanceMeters, label)
    }

    /**
     * Check if an object is dangerous (for enhanced warnings)
     */
    private fun isDangerObject(label: String): Boolean {
        val lowerLabel = label.lowercase()
        val dangerKeywords = listOf(
            "gun", "pistol", "rifle", "firearm", "weapon",
            "knife", "blade", "dagger", "sword",
            "fire", "flame", "torch", "chainsaw",
            "scissors", "hammer", "axe", "saw"
        )
        return dangerKeywords.any { lowerLabel.contains(it) }
    }

    /**
     * Get just the distance text (without object name)
     */
    fun getDistanceOnlyText(distanceMeters: Float): String {
        val steps = (distanceMeters / 0.7).toInt()
        return when {
            distanceMeters < 0.7 -> if (currentLanguage == "ur") "بالکل قریب" else "extremely close"
            steps == 1 -> if (currentLanguage == "ur") "تقریباً 1 قدم" else "about 1 step"
            steps <= 2 -> if (currentLanguage == "ur") "تقریباً $steps قدم" else "about $steps steps"
            steps <= 4 -> if (currentLanguage == "ur") "$steps قدم دور" else "$steps steps away"
            else -> if (currentLanguage == "ur") "دور" else "far away"
        }
    }
}