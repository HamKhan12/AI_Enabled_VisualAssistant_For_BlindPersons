package com.example.smartassist

import android.content.Context
import android.graphics.*
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class ObjectDetectorHelper(
    val context: Context,
    val listener: DetectorListener,
    private var threshold: Float = 0.10f,
    private val maxResults: Int = 35,
    private val numThreads: Int = 4,
    private var iouThreshold: Float = 0.45f,
    private var inputSize: Int = 320
) {

    companion object {
        private const val TAG = "YOLOv8Helper"
        private const val MODEL_FILE = "yolov8x-oiv7_float16.tflite"
        private const val LABELS_FILE = "classes.txt"
    }

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()

    // Track detection stats for hybrid decision
    var lastDetectionCount = 0
    var lastAverageConfidence = 0f

    init {
        setup()
    }

    /**
     * Get current input size (always 320)
     */
    fun getCurrentInputSize(): Int = inputSize

    fun close() {
        try { interpreter?.close() } catch (_: Exception) {}
        interpreter = null
    }

    private fun setup() {
        try {
            labels = FileUtil.loadLabels(context, LABELS_FILE)
            Log.d(TAG, "Loaded labels lines=${labels.size}")

            val modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
            val options = Interpreter.Options().setNumThreads(numThreads)
            interpreter = Interpreter(modelBuffer, options)

            val inTensor = interpreter!!.getInputTensor(0)
            val outTensor = interpreter!!.getOutputTensor(0)

            Log.d(TAG, "Model loaded: $MODEL_FILE")
            Log.d(TAG, "Input shape=${inTensor.shape().contentToString()} type=${inTensor.dataType()}")
            Log.d(TAG, "Output shape=${outTensor.shape().contentToString()} type=${outTensor.dataType()}")
            Log.d(TAG, "Using inputSize=$inputSize, threshold=$threshold, iou=$iouThreshold")

        } catch (e: Exception) {
            listener.onError("Failed to initialize YOLO detector: ${e.message}")
            Log.e(TAG, "Setup error", e)
        }
    }

    fun detect(bitmap: Bitmap, rotationDegrees: Int) {
        if (interpreter == null) setup()
        val itp = interpreter ?: run {
            listener.onError("Interpreter null")
            return
        }

        try {
            val src = if (rotationDegrees != 0) rotateBitmap(bitmap, rotationDegrees) else bitmap
            val origW = src.width
            val origH = src.height

            val prep = letterboxToInput(src, inputSize)

            val outTensor = itp.getOutputTensor(0)
            val outShape = outTensor.shape()
            val outType = outTensor.dataType()

            if (outType != DataType.FLOAT32) {
                throw IllegalStateException("Unexpected output type=$outType. Expected FLOAT32.")
            }

            val outBuf = ByteBuffer.allocateDirect(outTensor.numBytes()).order(ByteOrder.nativeOrder())
            itp.run(prep.input, outBuf)
            outBuf.rewind()

            val outElements = outShape.reduce { a, b -> a * b }
            val fb = outBuf.asFloatBuffer()
            val output = FloatArray(outElements)
            fb.get(output)

            val detections = decode(
                output = output,
                outShape = outShape,
                origW = origW,
                origH = origH,
                scale = prep.scale,
                padX = prep.padX,
                padY = prep.padY
            )

            Log.d(TAG, "Detections after NMS: ${detections.size}")
            listener.onResults(detections.toMutableList(), origH, origW)

        } catch (e: Exception) {
            Log.e(TAG, "Detect error", e)
            listener.onError("YOLO detect failed: ${e.message}")
        }
    }

    // ----------------------------
    // Preprocess (letterbox)
    // ----------------------------

    private data class PreprocessResult(
        val input: ByteBuffer,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    private fun letterboxToInput(src: Bitmap, dst: Int): PreprocessResult {
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()

        val scale = min(dst / sw, dst / sh)
        val nw = (sw * scale).toInt()
        val nh = (sh * scale).toInt()
        val padX = (dst - nw) / 2f
        val padY = (dst - nh) / 2f

        val resized = Bitmap.createScaledBitmap(src, nw, nh, true)
        val canvasBmp = Bitmap.createBitmap(dst, dst, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(resized, padX, padY, null)

        val input = ByteBuffer.allocateDirect(1 * dst * dst * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(dst * dst)
        canvasBmp.getPixels(pixels, 0, dst, 0, 0, dst, dst)

        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            input.putFloat(r); input.putFloat(g); input.putFloat(b)
        }

        input.rewind()
        resized.recycle()
        canvasBmp.recycle()

        return PreprocessResult(input, scale, padX, padY)
    }

    private fun rotateBitmap(src: Bitmap, rotationDegrees: Int): Bitmap {
        val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    // ----------------------------
    // Decode + NMS
    // ----------------------------

    private fun sigmoid(x: Float): Float = (1f / (1f + exp(-x)))

    private fun decode(
        output: FloatArray,
        outShape: IntArray,
        origW: Int,
        origH: Int,
        scale: Float,
        padX: Float,
        padY: Float
    ): List<Detection> {

        if (outShape.size != 3) return emptyList()

        val a = outShape[1]
        val b = outShape[2]

        val channels: Int
        val numPred: Int
        val layoutCHW: Boolean

        if (a in 80..2000) {
            channels = a
            numPred = b
            layoutCHW = true
        } else {
            channels = b
            numPred = a
            layoutCHW = false
        }

        val ncNoObj = channels - 4
        val ncWithObj = channels - 5

        val preferNoObj = abs(ncNoObj - labels.size) <= abs(ncWithObj - labels.size)
        val hasObj = !preferNoObj
        val nc = if (hasObj) ncWithObj else ncNoObj

        val objIndex = 4
        val classStart = if (hasObj) 5 else 4

        if (nc <= 0 || classStart + nc > channels) {
            Log.e(TAG, "Decode config invalid: channels=$channels classStart=$classStart nc=$nc labels=${labels.size}")
            return emptyList()
        }

        fun getVal(pred: Int, ch: Int): Float {
            return if (layoutCHW) output[ch * numPred + pred] else output[pred * channels + ch]
        }

        val candidates = ArrayList<Detection>(min(numPred, 5000))

        for (i in 0 until numPred) {
            val x = getVal(i, 0)
            val y = getVal(i, 1)
            val w = getVal(i, 2)
            val h = getVal(i, 3)

            val objRaw = if (hasObj) getVal(i, objIndex) else 1f
            val obj = if (hasObj && (objRaw < 0f || objRaw > 1f)) sigmoid(objRaw) else objRaw

            var bestClass = -1
            var bestProb = 0f
            for (c in 0 until nc) {
                val raw = getVal(i, classStart + c)
                val p = if (raw < 0f || raw > 1f) sigmoid(raw) else raw
                if (p > bestProb) {
                    bestProb = p
                    bestClass = c
                }
            }

            if (bestClass < 0) continue
            val conf = if (hasObj) obj * bestProb else bestProb

            if (conf < threshold) continue

            val px = if (x <= 1.5f && y <= 1.5f) x * inputSize else x
            val py = if (x <= 1.5f && y <= 1.5f) y * inputSize else y
            val pw = if (w <= 1.5f && h <= 1.5f) w * inputSize else w
            val ph = if (w <= 1.5f && h <= 1.5f) h * inputSize else h

            var left = px - pw / 2f
            var top = py - ph / 2f
            var right = px + pw / 2f
            var bottom = py + ph / 2f

            left = (left - padX) / scale
            right = (right - padX) / scale
            top = (top - padY) / scale
            bottom = (bottom - padY) / scale

            left = left.coerceIn(0f, origW.toFloat())
            right = right.coerceIn(0f, origW.toFloat())
            top = top.coerceIn(0f, origH.toFloat())
            bottom = bottom.coerceIn(0f, origH.toFloat())

            if (right <= left || bottom <= top) continue

            val label = if (bestClass in labels.indices) labels[bestClass] else "class_$bestClass"

            candidates.add(
                Detection(
                    boundingBox = RectF(left, top, right, bottom),
                    categories = listOf(Category(label, conf))
                )
            )
        }

        // Calculate stats for hybrid decision
        lastDetectionCount = candidates.size
        if (candidates.isNotEmpty()) {
            var totalConf = 0f
            for (det in candidates) {
                totalConf += det.categories[0].score
            }
            lastAverageConfidence = totalConf / candidates.size
            Log.d(TAG, "Detection stats - Count: $lastDetectionCount, Avg Confidence: ${"%.2f".format(lastAverageConfidence)}")
        } else {
            lastAverageConfidence = 0f
        }

        return nonMaxSuppression(candidates, iouThreshold, maxResults)
    }

    private fun nonMaxSuppression(
        detections: List<Detection>,
        iouThresh: Float,
        limit: Int
    ): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.categories[0].score }
        val selected = ArrayList<Detection>(min(limit, sorted.size))

        for (det in sorted) {
            if (selected.size >= limit) break
            var keep = true
            for (sel in selected) {
                if (iou(det.boundingBox, sel.boundingBox) > iouThresh) {
                    keep = false
                    break
                }
            }
            if (keep) selected.add(det)
        }
        return selected
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(b.bottom, a.bottom)

        val iw = max(0f, interRight - interLeft)
        val ih = max(0f, interBottom - interTop)
        val inter = iw * ih

        val areaA = max(0f, a.width()) * max(0f, a.height())
        val areaB = max(0f, b.width()) * max(0f, b.height())
        val union = areaA + areaB - inter

        return if (union <= 0f) 0f else inter / union
    }

    interface DetectorListener {
        fun onError(msg: String)
        fun onResults(results: MutableList<Detection>?, imageHeight: Int, imageWidth: Int)
    }
}