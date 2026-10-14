package com.miraglio.data.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.miraglio.domain.vision.DetectionBox
import com.miraglio.domain.vision.DetectionResult
import com.miraglio.domain.vision.ObjectDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Rilevatore YOLOv8 (ONNX Runtime) per targhe rettangolari e paglioni circolari.
 *
 * Se il file modello non e' presente in assets (es. prima dell'integrazione
 * di best.onnx), [detect] restituisce lista vuota come [NoOpObjectDetector]
 * e l'app continua a funzionare in modalita' TAP/MANUAL.
 */
@Singleton
class YoloObjectDetector @Inject constructor(
    @ApplicationContext private val context: Context
) : ObjectDetector {

    companion object {
        private const val TAG = "YoloObjectDetector"
        private const val MODEL_ASSET = "models/yolov8n_hybrid.onnx"
        private const val INPUT_SIZE = 640
        private const val CONF_THRESHOLD = 0.25f
        private const val IOU_THRESHOLD = 0.45f

        val CLASS_NAMES = listOf("rectangular_target", "circular_target")
        const val LABEL_RECTANGULAR = "rectangular_target"
        const val LABEL_CIRCULAR = "circular_target"
    }

    override val modelLabel: String
        get() = if (session != null) "YOLOv8n-hybrid (ONNX)" else "no-op (modello ONNX non caricato)"

    private val env: OrtEnvironment? =
        runCatching { OrtEnvironment.getEnvironment() }
            .onFailure { Log.w(TAG, "OrtEnvironment non disponibile", it) }
            .getOrNull()

    private val session: OrtSession? by lazy {
        val e = env
        if (e == null) {
            null
        } else {
            runCatching {
                val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
                e.createSession(bytes, OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                })
            }.onFailure {
                Log.w(TAG, "Modello $MODEL_ASSET non caricato, fallback no-op", it)
            }.getOrNull()
        }
    }

    override fun detect(bitmap: Bitmap): DetectionResult {
        val t0 = System.currentTimeMillis()
        val sess = session
        val ortEnv = env
        if (sess == null || ortEnv == null || bitmap.width <= 0 || bitmap.height <= 0) {
            return DetectionResult(emptyList(), 0)
        }
        return try {
            val input = preprocess(bitmap)
            val inputName = sess.inputNames.first()
            OnnxTensor.createTensor(
                ortEnv,
                input.buffer,
                longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            ).use { tensor ->
                sess.run(mapOf(inputName to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val output = (result[0].value as Array<Array<FloatArray>>)[0]
                    val boxes = postprocess(output, bitmap.width, bitmap.height, input)
                    DetectionResult(boxes, System.currentTimeMillis() - t0)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "detect fallita", t)
            DetectionResult(emptyList(), System.currentTimeMillis() - t0)
        }
    }

    override fun close() {
        runCatching { session?.close() }
    }

    private data class ModelInput(
        val buffer: FloatBuffer,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    private fun preprocess(bitmap: Bitmap): ModelInput {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val scale = min(INPUT_SIZE / srcW.toFloat(), INPUT_SIZE / srcH.toFloat())
        val newW = (srcW * scale).toInt()
        val newH = (srcH * scale).toInt()
        val padX = (INPUT_SIZE - newW) / 2f
        val padY = (INPUT_SIZE - newH) / 2f

        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        val pixels = IntArray(newW * newH)
        scaled.getPixels(pixels, 0, newW, 0, 0, newW, newH)
        if (scaled != bitmap) {
            scaled.recycle()
        }

        // CHW float32 RGB normalizzato [0,1], padding grigio 114/255
        val buffer = FloatBuffer.allocate(3 * INPUT_SIZE * INPUT_SIZE)
        val padValue = 114f / 255f
        val rPlane = FloatArray(INPUT_SIZE * INPUT_SIZE) { padValue }
        val gPlane = FloatArray(INPUT_SIZE * INPUT_SIZE) { padValue }
        val bPlane = FloatArray(INPUT_SIZE * INPUT_SIZE) { padValue }
        val offX = padX.toInt()
        val offY = padY.toInt()
        for (y in 0 until newH) {
            for (x in 0 until newW) {
                val pixel = pixels[y * newW + x]
                val idx = (y + offY) * INPUT_SIZE + (x + offX)
                rPlane[idx] = ((pixel shr 16) and 0xFF) / 255f
                gPlane[idx] = ((pixel shr 8) and 0xFF) / 255f
                bPlane[idx] = (pixel and 0xFF) / 255f
            }
        }
        buffer.put(rPlane)
        buffer.put(gPlane)
        buffer.put(bPlane)
        buffer.rewind()
        return ModelInput(buffer, scale, padX, padY)
    }

    private data class Candidate(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val classId: Int,
        val confidence: Float
    )

    private fun postprocess(
        output: Array<FloatArray>,
        srcW: Int,
        srcH: Int,
        input: ModelInput
    ): List<DetectionBox> {
        // Output YOLOv8: [6][8400] = cx, cy, w, h (letterbox px), score cls0, score cls1
        val numBoxes = output[0].size
        val candidates = ArrayList<Candidate>(numBoxes)
        for (i in 0 until numBoxes) {
            val s0 = output[4][i]
            val s1 = output[5][i]
            val classId = if (s0 >= s1) 0 else 1
            val conf = max(s0, s1)
            if (conf < CONF_THRESHOLD) {
                continue
            }
            val cx = (output[0][i] - input.padX) / input.scale
            val cy = (output[1][i] - input.padY) / input.scale
            val w = output[2][i] / input.scale
            val h = output[3][i] / input.scale
            val left = (cx - w / 2f).coerceIn(0f, srcW.toFloat()) / srcW
            val top = (cy - h / 2f).coerceIn(0f, srcH.toFloat()) / srcH
            val right = (cx + w / 2f).coerceIn(0f, srcW.toFloat()) / srcW
            val bottom = (cy + h / 2f).coerceIn(0f, srcH.toFloat()) / srcH
            if (right > left && bottom > top) {
                candidates.add(Candidate(left, top, right, bottom, classId, conf))
            }
        }

        // NMS per classe
        val kept = ArrayList<DetectionBox>()
        for (classId in CLASS_NAMES.indices) {
            val perClass = candidates
                .filter { it.classId == classId }
                .sortedByDescending { it.confidence }
                .toMutableList()
            while (perClass.isNotEmpty()) {
                val best = perClass.removeAt(0)
                kept.add(
                    DetectionBox(
                        label = CLASS_NAMES[classId],
                        confidence = best.confidence,
                        left = best.left,
                        top = best.top,
                        right = best.right,
                        bottom = best.bottom
                    )
                )
                val it = perClass.iterator()
                while (it.hasNext()) {
                    if (iou(best, it.next()) > IOU_THRESHOLD) {
                        it.remove()
                    }
                }
            }
        }
        return kept.sortedByDescending { it.confidence }
    }

    private fun iou(a: Candidate, b: Candidate): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interW = max(0f, interRight - interLeft)
        val interH = max(0f, interBottom - interTop)
        val inter = interW * interH
        if (inter <= 0f) {
            return 0f
        }
        val union = (a.right - a.left) * (a.bottom - a.top) +
            (b.right - b.left) * (b.bottom - b.top) - inter
        return if (union <= 0f) 0f else inter / union
    }
}
