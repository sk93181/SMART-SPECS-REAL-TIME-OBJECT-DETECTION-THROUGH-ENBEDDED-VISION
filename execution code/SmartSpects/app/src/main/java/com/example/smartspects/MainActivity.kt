package com.example.smartspects

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.*
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.*

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    companion object {
        private const val MODEL_NAME = "yolov10.tflite"
        private const val LABELS_FILENAME = "labelmap.txt"
        private const val INPUT_SIZE = 300
        private const val NUM_DETECTIONS = 10
        private const val CONF_THRESHOLD = 0.5f
        private const val UTTERANCE_ID = "DETECTION_RESULT"
        private const val ESP32_IMAGE_URL = "http://192.168.172.241/capture" // Update to your ESP32 IP
    }

    private lateinit var tts: TextToSpeech
    private lateinit var tflite: Interpreter
    private lateinit var labels: List<String>
    private lateinit var overlayBitmap: Bitmap
    private var pendingAnnouncement: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tts = TextToSpeech(this, this)
        labels = assets.open(LABELS_FILENAME).bufferedReader().useLines { it.toList() }
        tflite = Interpreter(loadModelFile(MODEL_NAME))

        val imageView = findViewById<ImageView>(R.id.imagePreview)
        val btnDetect = findViewById<Button>(R.id.btnDetect)
        val progressBar = findViewById<ProgressBar>(R.id.progress)

        btnDetect.setOnClickListener {
            progressBar.visibility = ProgressBar.VISIBLE
            val freshUrl = "$ESP32_IMAGE_URL?time=${System.currentTimeMillis()}"

            Glide.with(this)
                .asBitmap()
                .load(freshUrl)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        val scaled = Bitmap.createScaledBitmap(resource, INPUT_SIZE, INPUT_SIZE, true)
                        overlayBitmap = scaled.copy(Bitmap.Config.ARGB_8888, true)
                        imageView.setImageBitmap(overlayBitmap)
                        runObjectDetection(scaled)
                        progressBar.visibility = ProgressBar.GONE
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        progressBar.visibility = ProgressBar.GONE
                    }
                })
        }
    }

    private fun runObjectDetection(bitmap: Bitmap) {
        val inputBuf = toByteBuffer(bitmap)
        val locs = Array(1) { Array(NUM_DETECTIONS) { FloatArray(4) } }
        val classes = Array(1) { FloatArray(NUM_DETECTIONS) }
        val scores = Array(1) { FloatArray(NUM_DETECTIONS) }
        val countArr = FloatArray(1)

        val outputs = mapOf(0 to locs, 1 to classes, 2 to scores, 3 to countArr)
        tflite.runForMultipleInputsOutputs(arrayOf(inputBuf), outputs)

        val canvas = Canvas(overlayBitmap)
        val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.RED
        }
        val textPaint = Paint().apply {
            style = Paint.Style.FILL
            textSize = 32f
            color = Color.WHITE
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        var bestLabel = "No object"
        var bestScore = 0f
        val detCount = countArr[0].toInt()

        for (i in 0 until detCount) {
            val score = scores[0][i]
            if (score < CONF_THRESHOLD) continue
            val clsIdx = classes[0][i].toInt().coerceIn(labels.indices)
            val name = labels[clsIdx]
            val box = locs[0][i]
            val left = box[1] * INPUT_SIZE
            val top = box[0] * INPUT_SIZE
            val right = box[3] * INPUT_SIZE
            val bottom = box[2] * INPUT_SIZE

            canvas.drawRect(left, top, right, bottom, boxPaint)
            canvas.drawText(name, left, top - 10, textPaint)

            if (score > bestScore) {
                bestScore = score
                bestLabel = name
            }
        }

        findViewById<TextView>(R.id.objectLabel).text = bestLabel
        findViewById<ImageView>(R.id.imagePreview).invalidate()
        announce(bestLabel)
    }

    private fun toByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3)
        buf.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in pixels) {
            buf.put((p shr 16 and 0xFF).toByte())
            buf.put((p shr 8 and 0xFF).toByte())
            buf.put((p and 0xFF).toByte())
        }
        return buf
    }

    private fun loadModelFile(filename: String): ByteBuffer {
        val fd = assets.openFd(filename)
        return FileInputStream(fd.fileDescriptor).use { fis ->
            fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    private fun announce(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, UTTERANCE_ID)
        } else {
            @Suppress("DEPRECATION")
            tts.speak(text, TextToSpeech.QUEUE_ADD, null)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            pendingAnnouncement?.let {
                announce(it)
                pendingAnnouncement = null
            }
        }
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
