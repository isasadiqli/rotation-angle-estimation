package com.example.cameraapp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.location.Location
import android.location.LocationListener
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.cameraapp.databinding.ActivityMainBinding
import com.google.android.gms.location.LocationServices
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority


class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var frameCounterText: TextView
    private var frameCounter = 0
    private var frameCounterHandler = Handler(Looper.getMainLooper())
    private var frameCounterRunnable: Runnable? = null
    private val estimatedFrameRate = 30 // Most devices record at 30fps by default

    private var magnetometerSensor: Sensor? = null
    private val magnetometerDataList = mutableListOf<SensorData>()
    private lateinit var locationManager: LocationManager
    private val locationDataList = mutableListOf<LocationData>()
    private var locationListener: LocationListener? = null

    private lateinit var locationStatusTextView: TextView

    data class LocationData(
        val relativeTimestamp: Long,
        val absoluteTimestamp: Long,
        val formattedDate: String,
        val estimatedFrame: Int,
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val accuracy: Float,
        val speed: Float,
        val bearing: Float
    )

    private val TAG = "VideoSensorRecorder"
    private val REQUIRED_PERMISSIONS = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        // Android 10+ (API 29+)
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    } else {
        // Android 9 and below
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
    private val REQUEST_CODE_PERMISSIONS = 10

    // UI elements
    private lateinit var viewFinder: PreviewView
    private lateinit var recordButton: Button
    private lateinit var gyroDataTextView: TextView
    private lateinit var accelDataTextView: TextView

    // Camera variables
    private var videoCapture: VideoCapture<Recorder>? = null
    private lateinit var cameraExecutor: ExecutorService
    private var recording: Recording? = null

    // Sensor variables
    private lateinit var sensorManager: SensorManager
    private var gyroscopeSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private var isRecording = false
    private val gyroscopeDataList = mutableListOf<SensorData>()
    private val accelerometerDataList = mutableListOf<SensorData>()
    private var recordingStartTime: Long = 0

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var locationUpdatesCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // In onCreate, add this simple test
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val testHandler = Handler(Looper.getMainLooper())
            testHandler.post(object : Runnable {
                override fun run() {
                    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                        if (location != null) {
                            Log.d(TAG, "Test location: ${location.latitude}, ${location.longitude}, bearing: ${location.bearing}")
                        } else {
                            Log.d(TAG, "Test location: null")
                        }
                    }
                    testHandler.postDelayed(this, 1000) // Check once per second
                }
            })
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            setupLocationUpdates()
        }

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize UI components using binding
        viewFinder = binding.viewFinder
        recordButton = binding.recordButton
        gyroDataTextView = binding.gyroDataText
        accelDataTextView = binding.accelDataText
        frameCounterText = binding.frameCounterText

        locationStatusTextView = TextView(this)
        locationStatusTextView.text = "GPS: Waiting..."
        locationStatusTextView.setTextColor(Color.WHITE)
        locationStatusTextView.setPadding(16, 16, 16, 16)
        locationStatusTextView.background = ColorDrawable(Color.parseColor("#80000000"))
        (binding.root as? ViewGroup)?.addView(locationStatusTextView)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Initialize sensors
        setupSensors()

        // Set up the record button listener
        recordButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Check permissions before starting camera
        if (allPermissionsGranted()) {
            startCamera()
            ensureGpsEnabled()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun setupLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 50) // 50ms = 20 updates/second
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(0) // Get updates as fast as possible
            .setMaxUpdateDelayMillis(100) // Don't delay updates more than 100ms
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationUpdatesCount++
                Log.d(TAG, "Location update received (#$locationUpdatesCount): ${locationResult.locations.size} locations")

                for (location in locationResult.locations) {
                    Log.d(TAG, "  Loc: ${location.latitude}, ${location.longitude}, accuracy: ${location.accuracy}m, bearing: ${location.bearing}")

                    if (isRecording) {
                        val relativeTimestamp = System.currentTimeMillis() - recordingStartTime
                        val absoluteTimestamp = System.currentTimeMillis()
                        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                        val formattedDate = dateFormat.format(Date(absoluteTimestamp))
                        val estimatedFrame = (relativeTimestamp * estimatedFrameRate / 1000).toInt()

                        val locationData = LocationData(
                            relativeTimestamp,
                            absoluteTimestamp,
                            formattedDate,
                            estimatedFrame,
                            location.latitude,
                            location.longitude,
                            location.altitude,
                            location.accuracy,
                            location.speed,
                            location.bearing
                        )

                        locationDataList.add(locationData)
                    }
                }
            }
        }

        // Request permission if needed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // Start requesting location updates
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            // Try to get last location
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    Log.d(TAG, "Initial location: ${location.latitude}, ${location.longitude}, bearing: ${location.bearing}")
                } else {
                    Log.d(TAG, "No initial location available")
                }
            }
        } else {
            Log.e(TAG, "Location permission not granted")
            // You could request permissions here if needed
        }
    }

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Get gyroscope sensor
        gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyroscopeSensor == null) {
            Toast.makeText(this, "Device has no gyroscope sensor", Toast.LENGTH_SHORT).show()
        }

        // Get accelerometer sensor
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometerSensor == null) {
            Toast.makeText(this, "Device has no accelerometer sensor", Toast.LENGTH_SHORT).show()
        }

        // Get magnetometer sensor
        magnetometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (magnetometerSensor == null) {
            Toast.makeText(this, "Device has no magnetometer sensor", Toast.LENGTH_SHORT).show()
        }

        // Set up location manager for GPS
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // Set up the preview use case
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(viewFinder.surfaceProvider)

                // Set up the recorder
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                // Select the back camera
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build()

                // Unbind all use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture
                )

            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
                Toast.makeText(this, "Failed to start camera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecording() {
        val videoCapture = this.videoCapture ?: run {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }

        // Clear previous sensor data
        gyroscopeDataList.clear()
        accelerometerDataList.clear()
        magnetometerDataList.clear()
        locationDataList.clear()

        // Record the start time
        recordingStartTime = System.currentTimeMillis()

        // Register sensor listeners
        gyroscopeSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        accelerometerSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        magnetometerSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        // Set up and register location listener
        // Set up and register location updates using Fused Location Provider
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 100)
                .setWaitForAccurateLocation(false)
                .setMinUpdateIntervalMillis(100)
                .build()

            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationUpdatesCount++
                    Log.d(TAG, "Location update received (#$locationUpdatesCount): ${locationResult.locations.size} locations")

                    for (location in locationResult.locations) {

                        Log.d(TAG, "  Loc: ${location.latitude}, ${location.longitude}, accuracy: ${location.accuracy}m, bearing: ${location.bearing}")

                        // Process your location data here
                        val relativeTimestamp = System.currentTimeMillis() - recordingStartTime
                        val absoluteTimestamp = System.currentTimeMillis()
                        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                        val formattedDate = dateFormat.format(Date(absoluteTimestamp))
                        val estimatedFrame = (relativeTimestamp * estimatedFrameRate / 1000).toInt()

                        val locationData = LocationData(
                            relativeTimestamp,
                            absoluteTimestamp,
                            formattedDate,
                            estimatedFrame,
                            location.latitude,
                            location.longitude,
                            location.altitude,
                            location.accuracy,
                            location.speed,
                            location.bearing
                        )

                        locationDataList.add(locationData)
                        Log.d(TAG, "Location update: ${location.latitude}, ${location.longitude}, bearing: ${location.bearing}")
                    }
                }
            }

            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }

        // Create video file metadata
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Video-$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        }

        // Create MediaStoreOutputOptions for VideoCapture
        val options = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        // Start recording video
        recording = videoCapture.output.prepareRecording(this, options)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { videoRecordEvent ->
                when (videoRecordEvent) {
                    is VideoRecordEvent.Start -> {
                        recordButton.text = "Stop Recording"
                        isRecording = true
                        startFrameCapture()
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (videoRecordEvent.hasError()) {
                            Log.e(TAG, "Video recording failed: ${videoRecordEvent.cause}")
                            Toast.makeText(this, "Video recording failed", Toast.LENGTH_SHORT).show()
                        } else {
                            val videoUri = videoRecordEvent.outputResults.outputUri.toString()
                            Toast.makeText(this, "Video saved: $videoUri", Toast.LENGTH_SHORT).show()
                            Log.d(TAG, "Video saved: $videoUri")
                        }
                        recordButton.text = "Start Recording"
                        isRecording = false
                    }
                    else -> {
                        // Handle other events if needed
                    }
                }
            }

        // ADD THE FRAME COUNTER CODE RIGHT HERE, after starting recording
        frameCounter = 0
        frameCounterText.text = "Frame: 0"
        frameCounterRunnable = object : Runnable {
            override fun run() {
                if (isRecording) {
                    // Estimate frame based on elapsed time and frame rate
                    val elapsedMs = System.currentTimeMillis() - recordingStartTime
                    frameCounter = (elapsedMs * estimatedFrameRate / 1000).toInt()
                    frameCounterText.text = "Frame: $frameCounter"
                    frameCounterHandler.postDelayed(this, 33) // Update roughly at frame rate
                }
            }
        }
        frameCounterHandler.post(frameCounterRunnable!!)
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null

        // ADD THIS LINE HERE to stop the frame counter
        frameCounterRunnable?.let { frameCounterHandler.removeCallbacks(it) }

        // Unregister sensor listeners
        sensorManager.unregisterListener(this)

        // Remove location updates
        locationListener?.let {
            locationManager.removeUpdates(it)
        }

        // Save sensor data to files
        saveSensorData()
        saveFrameData()
    }

    private fun saveSensorData() {
        try {
            // Create directory if it doesn't exist
            val sensorDataDir = File(getExternalFilesDir(null), "SensorData")
            if (!sensorDataDir.exists()) {
                sensorDataDir.mkdirs()
            }

            // Generate timestamp for filenames
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(recordingStartTime))

            // Save gyroscope data
            if (gyroscopeDataList.isNotEmpty()) {
                val gyroFile = File(sensorDataDir, "Gyroscope-$timestamp.csv")
                FileOutputStream(gyroFile).use { fos ->
                    fos.write("relative_timestamp,absolute_timestamp,formatted_date,estimated_frame,x,y,z\n".toByteArray())
                    for (data in gyroscopeDataList) {
                        val line = "${data.relativeTimestamp},${data.absoluteTimestamp},\"${data.formattedDate}\",${data.estimatedFrame},${data.x},${data.y},${data.z}\n"
                        fos.write(line.toByteArray())
                    }
                }
                Toast.makeText(this, "Gyroscope data saved to ${gyroFile.absolutePath}", Toast.LENGTH_SHORT).show()
            }

            // Save accelerometer data
            if (accelerometerDataList.isNotEmpty()) {
                val accelFile = File(sensorDataDir, "Accelerometer-$timestamp.csv")
                FileOutputStream(accelFile).use { fos ->
                    fos.write("relative_timestamp,absolute_timestamp,formatted_date,estimated_frame,x,y,z\n".toByteArray())
                    for (data in accelerometerDataList) {
                        val line = "${data.relativeTimestamp},${data.absoluteTimestamp},\"${data.formattedDate}\",${data.estimatedFrame},${data.x},${data.y},${data.z}\n"
                        fos.write(line.toByteArray())
                    }
                }
                Toast.makeText(this, "Accelerometer data saved to ${accelFile.absolutePath}", Toast.LENGTH_SHORT).show()
            }

            // Save magnetometer data
            if (magnetometerDataList.isNotEmpty()) {
                val magnetFile = File(sensorDataDir, "Magnetometer-$timestamp.csv")
                FileOutputStream(magnetFile).use { fos ->
                    fos.write("relative_timestamp,absolute_timestamp,formatted_date,estimated_frame,x,y,z\n".toByteArray())
                    for (data in magnetometerDataList) {
                        val line = "${data.relativeTimestamp},${data.absoluteTimestamp},\"${data.formattedDate}\",${data.estimatedFrame},${data.x},${data.y},${data.z}\n"
                        fos.write(line.toByteArray())
                    }
                }
                Toast.makeText(this, "Magnetometer data saved to ${magnetFile.absolutePath}", Toast.LENGTH_SHORT).show()
            }

            // Save GPS location data
            if (locationDataList.isNotEmpty()) {
                val locationFile = File(sensorDataDir, "Location-$timestamp.csv")
                FileOutputStream(locationFile).use { fos ->
                    fos.write("relative_timestamp,absolute_timestamp,formatted_date,estimated_frame,latitude,longitude,altitude,accuracy,speed,bearing\n".toByteArray())
                    for (data in locationDataList) {
                        val line = "${data.relativeTimestamp},${data.absoluteTimestamp},\"${data.formattedDate}\",${data.estimatedFrame},${data.latitude},${data.longitude},${data.altitude},${data.accuracy},${data.speed},${data.bearing}\n"
                        fos.write(line.toByteArray())
                    }
                }
                Toast.makeText(this, "Location data saved to ${locationFile.absolutePath}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving sensor data", e)
            Toast.makeText(this, "Failed to save sensor data: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSavedFileLocations() {
        val videoDir = File(getExternalFilesDir(null), "Videos")
        val sensorDir = File(getExternalFilesDir(null), "SensorData")

        val message = StringBuilder()
        message.append("Files saved to:\n\n")

        if (videoDir.exists() && videoDir.listFiles()?.isNotEmpty() == true) {
            message.append("Videos:\n")
            videoDir.listFiles()?.forEach {
                message.append("- ${it.name} (${it.length()} bytes)\n")
            }
        } else {
            message.append("No videos found\n")
        }

        message.append("\nSensor Data:\n")
        if (sensorDir.exists() && sensorDir.listFiles()?.isNotEmpty() == true) {
            sensorDir.listFiles()?.forEach {
                message.append("- ${sensorDir}/${it.name} (${it.length()} bytes)\n")
            }
        } else {
            message.append("No sensor data files found\n")
        }

        // Display the message in an alert dialog
        AlertDialog.Builder(this)
            .setTitle("Saved Files")
            .setMessage(message.toString())
            .setPositiveButton("OK", null)
            .show()
    }



    override fun onSensorChanged(event: SensorEvent) {
        if (!isRecording) return

        val relativeTimestamp = System.currentTimeMillis() - recordingStartTime
        val absoluteTimestamp = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val formattedDate = dateFormat.format(Date(absoluteTimestamp))

        // More precise frame number calculation
        val estimatedFrame = kotlin.math.floor(relativeTimestamp / (1000.0 / estimatedFrameRate)).toInt()

        val data = SensorData(
            relativeTimestamp,
            absoluteTimestamp,
            formattedDate,
            estimatedFrame,
            event.values[0],
            event.values[1],
            event.values[2]
        )

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                gyroscopeDataList.add(data)
                gyroDataTextView.text = String.format(
                    Locale.US, "Gyro: X=%.2f, Y=%.2f, Z=%.2f",
                    event.values[0], event.values[1], event.values[2]
                )
            }
            Sensor.TYPE_ACCELEROMETER -> {
                accelerometerDataList.add(data)
                accelDataTextView.text = String.format(
                    Locale.US, "Accel: X=%.2f, Y=%.2f, Z=%.2f",
                    event.values[0], event.values[1], event.values[2]
                )
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magnetometerDataList.add(data)
                // You can add a TextView to display magnetometer data if desired
            }
        }
    }

    // Updated data class
    data class SensorData(
        val relativeTimestamp: Long,
        val absoluteTimestamp: Long,
        val formattedDate: String,
        val estimatedFrame: Int,
        val x: Float,
        val y: Float,
        val z: Float
    )

    data class FrameData(
        val frameNumber: Int,
        val timestamp: Long,
        val absoluteTime: String,
        val gyroData: List<SensorData>,
        val accelData: List<SensorData>,
        val magnetData: List<SensorData>,
        val locationData: List<LocationData>
    )

    // List to store per-frame data
    private val frameDataList = mutableListOf<FrameData>()

    // Frame capture interval (33ms for ~30fps)
    private val frameInterval = 33L

    private fun startFrameCapture() {
        frameCounter = 0
        frameDataList.clear()

        frameCounterRunnable = object : Runnable {
            override fun run() {
                if (isRecording) {
                    val elapsedMs = System.currentTimeMillis() - recordingStartTime

                    // More precise frame number calculation
                    frameCounter = kotlin.math.floor(elapsedMs / (1000.0 / estimatedFrameRate)).toInt()

                    Log.d(TAG, "Elapsed time: $elapsedMs ms, Calculated frame: $frameCounter")

                    frameCounterText.text = "Frame: $frameCounter"

                    // Capture sensor data for this frame
                    captureFrameData(frameCounter, elapsedMs)

                    // Schedule next frame capture
                    frameCounterHandler.postDelayed(this, frameInterval)
                }
            }
        }
        frameCounterHandler.post(frameCounterRunnable!!)
    }

    private fun captureFrameData(frameNumber: Int, elapsedMs: Long) {
        // Only capture data if there are actually sensor readings
        if (gyroscopeDataList.isNotEmpty() || accelerometerDataList.isNotEmpty() ||
            magnetometerDataList.isNotEmpty() || locationDataList.isNotEmpty()) {

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            val absoluteTime = dateFormat.format(Date(recordingStartTime + elapsedMs))

            val frameData = FrameData(
                frameNumber = frameNumber,
                timestamp = elapsedMs,
                absoluteTime = absoluteTime,
                gyroData = gyroscopeDataList.toList(),
                accelData = accelerometerDataList.toList(),
                magnetData = magnetometerDataList.toList(),
                locationData = locationDataList.toList()
            )

            frameDataList.add(frameData)

            Log.d(TAG, "Captured frame $frameNumber: " +
                    "Gyro readings = ${frameData.gyroData.size}, " +
                    "Accel readings = ${frameData.accelData.size}, " +
                    "Magnet readings = ${frameData.magnetData.size}, " +
                    "Location readings = ${frameData.locationData.size}")
        }
    }

    private fun saveFrameData() {
        try {
            val sensorDataDir = File(getExternalFilesDir(null), "FrameData")
            if (!sensorDataDir.exists()) {
                sensorDataDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(recordingStartTime))
            val frameDataFile = File(sensorDataDir, "FrameData-$timestamp.csv")

            FileOutputStream(frameDataFile).use { fos ->
                fos.write("frame,timestamp_ms,absolute_time,gyro_x,gyro_y,gyro_z,accel_x,accel_y,accel_z,magnet_x,magnet_y,magnet_z,latitude,longitude,altitude\n".toByteArray())

                Log.d(TAG, "Saving ${frameDataList.size} frames to CSV")
                for (frameData in frameDataList) {
                    // Get average sensor values for this frame
                    val gyroX = if (frameData.gyroData.isNotEmpty()) frameData.gyroData.map { it.x }.average().toFloat() else 0f
                    val gyroY = if (frameData.gyroData.isNotEmpty()) frameData.gyroData.map { it.y }.average().toFloat() else 0f
                    val gyroZ = if (frameData.gyroData.isNotEmpty()) frameData.gyroData.map { it.z }.average().toFloat() else 0f

                    val accelX = if (frameData.accelData.isNotEmpty()) frameData.accelData.map { it.x }.average().toFloat() else 0f
                    val accelY = if (frameData.accelData.isNotEmpty()) frameData.accelData.map { it.y }.average().toFloat() else 0f
                    val accelZ = if (frameData.accelData.isNotEmpty()) frameData.accelData.map { it.z }.average().toFloat() else 0f

                    val magnetX = if (frameData.magnetData.isNotEmpty()) frameData.magnetData.map { it.x }.average().toFloat() else 0f
                    val magnetY = if (frameData.magnetData.isNotEmpty()) frameData.magnetData.map { it.y }.average().toFloat() else 0f
                    val magnetZ = if (frameData.magnetData.isNotEmpty()) frameData.magnetData.map { it.z }.average().toFloat() else 0f

                    // Get latest location data (if available)
                    val latitude = if (frameData.locationData.isNotEmpty()) frameData.locationData.last().latitude else 0.0
                    val longitude = if (frameData.locationData.isNotEmpty()) frameData.locationData.last().longitude else 0.0
                    val altitude = if (frameData.locationData.isNotEmpty()) frameData.locationData.last().altitude else 0.0

                    val line = "${frameData.frameNumber},${frameData.timestamp},\"${frameData.absoluteTime}\"," +
                            "$gyroX,$gyroY,$gyroZ,$accelX,$accelY,$accelZ,$magnetX,$magnetY,$magnetZ,$latitude,$longitude,$altitude\n"
                    fos.write(line.toByteArray())
                }
            }

            Toast.makeText(this, "Frame data saved to ${frameDataFile.absolutePath}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving frame data", e)
            Toast.makeText(this, "Failed to save frame data: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Not needed for this application
    }

    private fun allPermissionsGranted(): Boolean {
        val permissionsGranted = REQUIRED_PERMISSIONS.all {
            val isGranted = ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Permission $it: ${if (isGranted) "GRANTED" else "DENIED"}")
            isGranted
        }
        Log.d(TAG, "All permissions granted: $permissionsGranted")
        return permissionsGranted
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            // Log each permission result
            for (i in permissions.indices) {
                Log.d(TAG, "Permission result: ${permissions[i]} - ${if (grantResults[i] == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}")
            }

            if (allPermissionsGranted()) {
                Log.d(TAG, "All permissions granted, starting camera")
                startCamera()
            } else {
                // Check if we should show permission rationale for any denied permission
                var shouldShowRationale = false
                for (permission in REQUIRED_PERMISSIONS) {
                    if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                        Log.d(TAG, "Should show rationale for: $permission")
                        shouldShowRationale = true
                    }
                }

                if (shouldShowRationale) {
                    // User denied permission but didn't check "Don't ask again"
                    Toast.makeText(this, "Camera and storage permissions are required for this app", Toast.LENGTH_LONG).show()
                    // Request permissions again after showing rationale
                    Handler(Looper.getMainLooper()).postDelayed({
                        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
                    }, 3000)
                } else {
                    // User denied and checked "Don't ask again" or it's the first denial
                    Toast.makeText(this, "Permissions not granted. The app cannot function without these permissions.", Toast.LENGTH_LONG).show()
                    Log.e(TAG, "Permissions denied, app will close")
                    Handler(Looper.getMainLooper()).postDelayed({ finish() }, 3000)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun ensureGpsEnabled() {
        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (!gpsEnabled) {
            AlertDialog.Builder(this)
                .setTitle("GPS Required")
                .setMessage("This app needs GPS for accurate data collection. Would you like to enable it now?")
                .setPositiveButton("Yes") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("No", null)
                .show()
        }
    }

    // Helper data class to store sensor data
    // Replace the original SensorData class with this new one
//    data class SensorData(
//        val relativeTimestamp: Long,
//        val absoluteTimestamp: Long,
//        val formattedDate: String,
//        val x: Float,
//        val y: Float,
//        val z: Float
//    )
}