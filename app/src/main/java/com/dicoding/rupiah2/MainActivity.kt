package com.dicoding.rupiah2

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.classifier.ImageClassifier
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var imageClassifier: ImageClassifier
    private lateinit var textView: TextView
    private lateinit var labels: List<String>
    private lateinit var tts: TextToSpeech
    private var isTTSReady = false

    private var lastLabel: String? = null
    private var lastConfidence: Int = 0
    private var lastDetectedTime: Long = 0L
    private var lastSpeakCooldown: Long = 0L
    private var lastAnalysisTime: Long = 0L
    private var isCapturing = false
    private var isCaptureCooldown = false
    private var isFocusLocked = false
    private var isAutoDetectMode = false

    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private val handler = Handler(Looper.getMainLooper())
    private val DETECTION_THRESHOLD = 80
    private val ANALYSIS_INTERVAL = 500L
    private val CAPTURE_TIMEOUT = 2000L
    private val SPEAK_COOLDOWN = 2000L
    private val SHARPNESS_THRESHOLD = 50.0
    private val BRIGHTNESS_THRESHOLD = 30.0
    private val STABILITY_DURATION = 1000L
    private val STABILITY_FRAME_COUNT = (STABILITY_DURATION / ANALYSIS_INTERVAL).toInt()
    private val BAD_FRAME_TOLERANCE = 2

    private lateinit var camera: Camera
    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(this, "Izin penyimpanan diperlukan untuk debugging", Toast.LENGTH_SHORT).show()
        }
    }

    private val detectionHistory = mutableListOf<Pair<String, Int>>()
    private var detectionStartTime: Long = 0L
    private var lastStableLabel: String? = null
    private var lastStableConfidence: Int = 0
    private var badFrameCount: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textView = findViewById(R.id.textView)

        val switchCameraButton = findViewById<Button>(R.id.switchCameraButton)
        switchCameraButton.setOnClickListener {
            toggleCamera()
        }

        val captureButton = findViewById<Button>(R.id.captureButton)
        captureButton.setOnClickListener {
            captureImage()
        }

        val toggleAutoDetectButton = findViewById<ToggleButton>(R.id.toggleAutoDetectButton)
        toggleAutoDetectButton.setOnCheckedChangeListener { _, isChecked ->
            isAutoDetectMode = isChecked
            if (isAutoDetectMode) {
                speak("Mode Deteksi Otomatis diaktifkan. Aplikasi akan mendeteksi uang secara otomatis.")
            } else {
                speak("Mode Deteksi Otomatis dinonaktifkan. Silakan gunakan tombol jepret untuk mendeteksi.")
            }
        }

        val previewView = findViewById<PreviewView>(R.id.viewFinder)
        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                triggerManualFocus(event.x, event.y)
                true
            } else {
                false
            }
        }

        labels = loadLabels()

        val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) startCamera() else {
                Toast.makeText(this, "Izin kamera diperlukan", Toast.LENGTH_SHORT).show()
                speak("Izin kamera diperlukan untuk menjalankan aplikasi ini.")
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        try {
            imageClassifier = ImageClassifier.createFromFile(this, "model.tflite")
            Log.d("ModelLoad", "Model TFLite berhasil dimuat. Jumlah label dari labels.txt: ${labels.size}")
            if (labels.size != 8) {
                Log.e("ModelLoad", "Jumlah label (${labels.size}) tidak sesuai dengan ekspektasi (8 kelas)")
                Toast.makeText(this, "Jumlah label tidak sesuai", Toast.LENGTH_LONG).show()
                speak("Jumlah label tidak sesuai. Aplikasi mungkin tidak berfungsi dengan benar.")
                finish()
            }
        } catch (e: Exception) {
            Log.e("ModelLoad", "Gagal memuat model: ${e.message}")
            Toast.makeText(this, "Gagal memuat model: ${e.message}", Toast.LENGTH_LONG).show()
            speak("Gagal memuat model. Aplikasi tidak dapat digunakan.")
            finish()
        }

        tts = TextToSpeech(this, this)
        cameraExecutor = Executors.newFixedThreadPool(2)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("id", "ID")
            isTTSReady = true
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    handler.post {
                        Snackbar.make(findViewById(R.id.viewFinder), "Berhasil memutar suara", Snackbar.LENGTH_SHORT)
                            .setBackgroundTint(ContextCompat.getColor(this@MainActivity, R.color.green))
                            .setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
                            .show()
                    }
                }
                override fun onError(utteranceId: String?) {
                    handler.post {
                        Snackbar.make(findViewById(R.id.viewFinder), "Gagal memutar suara", Snackbar.LENGTH_SHORT)
                            .setBackgroundTint(ContextCompat.getColor(this@MainActivity, R.color.red))
                            .setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
                            .show()
                    }
                }
            })
            speak("Aplikasi siap. Kamera sedang diaktifkan. Ketuk layar untuk mengunci fokus pada uang, lalu tekan tombol jepret untuk mendeteksi.")
        } else {
            Toast.makeText(this, "TTS tidak tersedia", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleCamera() {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
            CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        isFocusLocked = false
        startCamera()
        speak("Kamera dialihkan. Ketuk layar untuk mengunci fokus jika perlu.")
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.viewFinder).surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy -> processImageProxy(imageProxy) }
                }

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Toast.makeText(this, "Gagal memulai kamera: ${exc.message}", Toast.LENGTH_LONG).show()
                speak("Gagal memulai kamera.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun triggerManualFocus(x: Float, y: Float) {
        val previewView = findViewById<PreviewView>(R.id.viewFinder)
        val meteringPoint = previewView.meteringPointFactory.createPoint(x, y)
        try {
            if (isFocusLocked) {
                isFocusLocked = false
                camera.cameraControl.cancelFocusAndMetering() // Perbaikan: Ganti circleFocusAndMetering menjadi cancelFocusAndMetering
                speak("Fokus dibuka")
            } else {
                isFocusLocked = true
                camera.cameraControl.startFocusAndMetering(
                    FocusMeteringAction.Builder(meteringPoint, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                        .disableAutoCancel()
                        .build()
                )
                speak("Fokus terkunci")
            }
        } catch (e: Exception) {
            Log.e("Focus", "Gagal menyesuaikan fokus: ${e.message}")
            speak("Gagal menyesuaikan fokus. Coba ketuk lagi.")
        }
    }

    private fun captureImage() {
        if (isCapturing || isCaptureCooldown) return
        if (lastStableLabel == null || lastStableConfidence < DETECTION_THRESHOLD || lastStableLabel == "Tidak Ada Objek") {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastSpeakCooldown >= SPEAK_COOLDOWN) {
                lastSpeakCooldown = currentTime
                speak("Deteksi belum stabil. Coba sesuaikan posisi uang atau ketuk layar untuk mengunci fokus, lalu jepret lagi.")
            }
            return
        }

        isCapturing = true
        isCaptureCooldown = true
        val captureButton = findViewById<Button>(R.id.captureButton)
        captureButton.isEnabled = false
        captureButton.text = "Sedang Memproses..."

        val currentTime = System.currentTimeMillis()
        textView.text = "$lastStableLabel - $lastStableConfidence%"
        textView.setBackgroundColor(ContextCompat.getColor(this, R.color.green))
        if (lastStableLabel != lastLabel || lastStableConfidence != lastConfidence || currentTime - lastDetectedTime >= 4000) {
            lastLabel = lastStableLabel
            lastConfidence = lastStableConfidence
            lastDetectedTime = currentTime
            if (currentTime - lastSpeakCooldown >= SPEAK_COOLDOWN) {
                lastSpeakCooldown = currentTime
                val spokenLabel = lastStableLabel!!.replace(".", "")
                speak("$spokenLabel")
            }
        }

        handler.postDelayed({
            isCapturing = false
            isCaptureCooldown = false
            captureButton.isEnabled = true
            captureButton.text = "Jepret"
        }, CAPTURE_TIMEOUT)
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()

        if (isCapturing || currentTime - lastAnalysisTime < ANALYSIS_INTERVAL) {
            imageProxy.close()
            return
        }

        lastAnalysisTime = currentTime

        try {
            val bitmap = imageProxy.toBitmap()

            saveBitmapForDebugging(bitmap, "preview")

            val brightness = calculateBrightness(bitmap)
            val sharpness = calculateSharpness(bitmap)
            Log.d("ImageAnalysis", "Brightness: $brightness, Sharpness: $sharpness")

            if (brightness < BRIGHTNESS_THRESHOLD || sharpness < SHARPNESS_THRESHOLD) {
                badFrameCount++
                if (badFrameCount >= BAD_FRAME_TOLERANCE) {
                    runOnUiThread {
                        textView.text = "Gambar terlalu gelap atau buram"
                        textView.setBackgroundColor(ContextCompat.getColor(this, R.color.red))
                    }
                    detectionHistory.clear()
                    lastStableLabel = null
                    lastStableConfidence = 0
                    badFrameCount = 0
                }
                return
            }

            badFrameCount = 0

            Log.d("ImageAnalysis", "Bitmap dimensions: ${bitmap.width}x${bitmap.height}, byte count: ${bitmap.byteCount}")

            val tensorImage = TensorImage.fromBitmap(bitmap)
            val results = imageClassifier.classify(tensorImage)

            runOnUiThread {
                if (results.isNotEmpty()) {
                    val allScores = results[0].categories.joinToString(", ") { category ->
                        val label = labels.getOrNull(category.index) ?: "Tidak dikenali"
                        val confidence = (category.score * 100).toInt()
                        "$label: $confidence%"
                    }
                    Log.d("Classification", "All scores: [$allScores]")

                    val topCategory = results[0].categories.first()
                    val label = labels.getOrNull(topCategory.index) ?: "Tidak dikenali"
                    val confidence = (topCategory.score * 100).toInt()

                    detectionHistory.add(Pair(label, confidence))
                    if (detectionHistory.size == 1) {
                        detectionStartTime = currentTime
                    }

                    if (currentTime - detectionStartTime >= STABILITY_DURATION && detectionHistory.size >= STABILITY_FRAME_COUNT) {
                        val stableLabel = detectionHistory.groupingBy { it.first }.eachCount()
                            .maxByOrNull { it.value }?.key
                        val stableConfidenceList = detectionHistory.filter { it.first == stableLabel }.map { it.second }
                        val stableConfidence = if (stableConfidenceList.isNotEmpty()) {
                            stableConfidenceList.average().toInt()
                        } else {
                            0
                        }

                        Log.d("Stability", "Stable Label: $stableLabel, Stable Confidence: $stableConfidence%, History: $detectionHistory")

                        if (stableLabel != null && stableConfidence >= DETECTION_THRESHOLD && stableLabel != "Tidak Ada Objek") {
                            lastStableLabel = stableLabel
                            lastStableConfidence = stableConfidence
                            textView.text = "$lastStableLabel - $lastStableConfidence%"
                            textView.setBackgroundColor(ContextCompat.getColor(this, R.color.green))

                            if (isAutoDetectMode && (lastStableLabel != lastLabel || lastStableConfidence != lastConfidence || currentTime - lastDetectedTime >= 4000)) {
                                lastLabel = lastStableLabel
                                lastConfidence = lastStableConfidence
                                lastDetectedTime = currentTime
                                if (currentTime - lastSpeakCooldown >= SPEAK_COOLDOWN) {
                                    lastSpeakCooldown = currentTime
                                    val spokenLabel = lastStableLabel!!.replace(".", "")
                                    speak("$spokenLabel")
                                }
                            }
                        } else {
                            lastStableLabel = null
                            lastStableConfidence = 0
                            textView.text = "Nominal tidak terdeteksi"
                            textView.setBackgroundColor(ContextCompat.getColor(this, R.color.red))
                        }
                        detectionHistory.clear()
                    }
                } else {
                    badFrameCount++
                    if (badFrameCount >= BAD_FRAME_TOLERANCE) {
                        textView.text = "Tidak ada objek terdeteksi"
                        textView.setBackgroundColor(ContextCompat.getColor(this, R.color.red))
                        detectionHistory.clear()
                        lastStableLabel = null
                        lastStableConfidence = 0
                        badFrameCount = 0
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ImageAnalysis", "Error processing image: ${e.message}", e)
            badFrameCount++
            if (badFrameCount >= BAD_FRAME_TOLERANCE) {
                detectionHistory.clear()
                lastStableLabel = null
                lastStableConfidence = 0
                badFrameCount = 0
            }
        } finally {
            imageProxy.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun ImageProxy.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 95, out)
        return android.graphics.BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    }

    private fun calculateBrightness(bitmap: Bitmap): Double {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 100, 100, true)
        val pixels = IntArray(scaledBitmap.width * scaledBitmap.height)
        scaledBitmap.getPixels(pixels, 0, scaledBitmap.width, 0, 0, scaledBitmap.width, scaledBitmap.height)
        return pixels.map {
            val r = (it shr 16) and 0xFF
            val g = (it shr 8) and 0xFF
            val b = it and 0xFF
            0.299 * r + 0.587 * g + 0.114 * b
        }.average()
    }

    private fun calculateSharpness(bitmap: Bitmap): Double {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 100, 100, true)
        val width = scaledBitmap.width
        val height = scaledBitmap.height
        val pixels = IntArray(width * height)
        scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = DoubleArray(width * height)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            gray[i] = 0.299 * r + 0.587 * g + 0.114 * b
        }
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val laplacian = -4 * gray[index] +
                        gray[index - 1] + gray[index + 1] +
                        gray[index - width] + gray[index + width]
                sum += laplacian
                sumSq += laplacian * laplacian
                count++
            }
        }
        val mean = sum / count
        return (sumSq / count) - (mean * mean)
    }

    private fun saveBitmapForDebugging(bitmap: Bitmap, prefix: String) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            val file = java.io.File(getExternalFilesDir(null), "${prefix}_${System.currentTimeMillis()}.jpg")
            try {
                val fos = java.io.FileOutputStream(file)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                fos.close()
                Log.d("DebugImage", "Gambar disimpan di: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e("DebugImage", "Gagal menyimpan gambar: ${e.message}")
            }
        }
    }

    private fun loadLabels(): List<String> {
        return try {
            val loadedLabels = assets.open("labels.txt").bufferedReader().readLines()
            val expectedLabels = listOf(
                "1.000", "2.000", "5.000", "10.000",
                "20.000", "50.000", "100.000", "Tidak Ada Objek"
            )
            if (loadedLabels != expectedLabels) {
                Log.e("Labels", "Label tidak sesuai dengan dataset: $loadedLabels")
                Toast.makeText(this, "Label tidak sesuai dengan dataset", Toast.LENGTH_LONG).show()
                speak("Label tidak sesuai dengan dataset. Aplikasi mungkin tidak berfungsi dengan benar.")
            }
            loadedLabels
        } catch (e: Exception) {
            Log.e("Labels", "Gagal membaca labels.txt: ${e.message}")
            Toast.makeText(this, "Gagal membaca labels.txt", Toast.LENGTH_SHORT).show()
            listOf("Tidak dikenali")
        }
    }

    private fun speak(message: String) {
        if (isTTSReady) {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }
}