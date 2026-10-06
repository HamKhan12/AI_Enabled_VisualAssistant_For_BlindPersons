package com.example.smartassist

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

// ═══════════════════════════════════════════════════════════════════════════
//  GestureController - Handles tap and double-tap gestures for mute/unmute
//
//  Features:
//  - Single tap: Mute non-essential narration (safety alerts still work)
//  - Double tap: Unmute full narration
//  - Safety alerts ALWAYS work regardless of mute state
//  - NOW WORKS ON FULL SCREEN (not just camera preview)
// ═══════════════════════════════════════════════════════════════════════════

class GestureController(
    private val view: View,
    private val onMuteStateChanged: (isMuted: Boolean) -> Unit
) {

    companion object {
        private const val TAG = "GestureController"
    }

    private var isMuted = false
    private val gestureDetector: GestureDetector
    private val handler = Handler(Looper.getMainLooper())
    private var pendingSingleTap = false
    private val DOUBLE_TAP_TIMEOUT = 300L

    init {
        gestureDetector = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                Log.d(TAG, "✅ Single tap confirmed - Muting narration")
                handleSingleTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                Log.d(TAG, "✅ Double tap detected - Unmuting narration")
                handleDoubleTap()
                return true
            }

            override fun onDown(e: MotionEvent): Boolean {
                return true
            }
        })

        // CRITICAL CHANGE: Set touch listener on the provided view (now the main container)
        // This makes gestures work on the ENTIRE screen
        view.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        Log.d(TAG, "GestureController initialized on FULL SCREEN. Single tap = mute, Double tap = unmute")
    }

    private fun handleSingleTap() {
        if (!isMuted) {
            isMuted = true
            Log.w(TAG, "🔇 NARRATION MUTED - User will only hear safety alerts")
            onMuteStateChanged(true)
            showMuteFeedback()
        } else {
            Log.d(TAG, "Already muted, ignoring single tap")
        }
    }

    private fun handleDoubleTap() {
        if (isMuted) {
            isMuted = false
            Log.w(TAG, "🔊 NARRATION UNMUTED - Full description enabled")
            onMuteStateChanged(false)
            showUnmuteFeedback()
        } else {
            Log.d(TAG, "Already unmuted, ignoring double tap")
        }
    }

    private fun showMuteFeedback() {
        Log.d(TAG, "🔇 MUTE FEEDBACK - Show mute icon on screen")
    }

    private fun showUnmuteFeedback() {
        Log.d(TAG, "🔊 UNMUTE FEEDBACK - Show unmute icon on screen")
    }

    fun isMuted(): Boolean = isMuted

    fun getMuteState(): String = if (isMuted) "MUTED" else "UNMUTED"

    fun reset() {
        handler.removeCallbacksAndMessages(null)
        pendingSingleTap = false
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        view.setOnTouchListener(null)
    }
}