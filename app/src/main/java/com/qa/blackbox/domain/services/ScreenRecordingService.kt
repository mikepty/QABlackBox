package com.qa.blackbox.domain.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.qa.blackbox.R
import com.qa.blackbox.domain.models.RecorderState
import com.qa.blackbox.domain.models.RecordingConfig
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/**
 * Servicio foreground robusto para MediaProjection con manejo de estados
 * CRÍTICO: Android 14+ requiere foregroundServiceType="mediaProjection"
 */
class ScreenRecordingService : Service() {

    companion object {
        const val ACTION_START = "com.qa.blackbox.START_RECORDING"
        const val ACTION_STOP = "com.qa.blackbox.STOP_RECORDING"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_CONFIG = "config"
        
        const val NOTIFICATION_CHANNEL_ID = "screen_recording_channel"
        const val NOTIFICATION_ID = 1001
        
        private const val VIRTUAL_DISPLAY_NAME = "QA_BlackBox_Display"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // MediaProjection components
    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    
    // State management
    private var recorderState: RecorderState = RecorderState.IDLE
    private var sessionStartTime: Long = 0L
    private var currentSessionId: String? = null
    
    // Configuration
    private lateinit var config: RecordingConfig
    private lateinit var outputFile: File
    
    // Display metrics
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializeDisplayMetrics()
        Timber.d("ScreenRecordingService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
                config = intent.getParcelableExtra(EXTRA_CONFIG) 
                    ?: RecordingConfig()
                
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startRecording(resultCode, data)
                } else {
                    Timber.e("Invalid MediaProjection permission data")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopRecording()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        if (recorderState != RecorderState.IDLE) {
            Timber.w("Already recording or in invalid state: $recorderState")
            return
        }

        try {
            // 1. Iniciar foreground service INMEDIATAMENTE (Android 14 requirement)
            startForeground(NOTIFICATION_ID, createNotification("Iniciando..."))
            
            // 2. Generar ID de sesión y archivo de salida
            currentSessionId = generateSessionId()
            outputFile = createOutputFile(currentSessionId!!)
            sessionStartTime = System.currentTimeMillis()
            
            // 3. Inicializar MediaProjection
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            
            // 4. Registrar callback para detectar revocación de permiso
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Timber.w("MediaProjection stopped by system")
                    serviceScope.launch {
                        handleProjectionStopped()
                    }
                }
            }, Handler(Looper.getMainLooper()))
            
            // 5. Configurar MediaRecorder con máquina de estados correcta
            setupMediaRecorder()
            
            // 6. Crear VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface,
                null,
                null
            )
            
            // 7. Iniciar grabación
            mediaRecorder?.start()
            recorderState = RecorderState.RECORDING
            
            updateNotification("Grabando: ${currentSessionId}")
            broadcastRecordingStatus(true, currentSessionId)
            
            Timber.i("Recording started successfully: $currentSessionId")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to start recording")
            recorderState = RecorderState.ERROR
            cleanupResources()
            stopSelf()
        }
    }

    private fun setupMediaRecorder() {
        // Android 12+ usa nuevo API
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            // CRITICAL: Orden exacto de la máquina de estados
            
            // 1. Audio source (si está habilitado)
            if (config.captureAudio) {
                setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            
            // 2. Output format
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            
            // 3. Encoders
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (config.captureAudio) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }
            
            // 4. Video configuration
            setVideoSize(screenWidth, screenHeight)
            setVideoFrameRate(config.fps)
            setVideoEncodingBitRate(config.bitrate)
            
            // 5. Output file
            setOutputFile(outputFile.absolutePath)
            
            // 6. Error listener
            setOnErrorListener { mr, what, extra ->
                Timber.e("MediaRecorder error: what=$what, extra=$extra")
                recorderState = RecorderState.ERROR
                serviceScope.launch {
                    stopRecording()
                }
            }
            
            // 7. Info listener
            setOnInfoListener { mr, what, extra ->
                Timber.d("MediaRecorder info: what=$what, extra=$extra")
            }
            
            try {
                // 8. Prepare (IDLE -> PREPARED)
                prepare()
                recorderState = RecorderState.PREPARED
                Timber.d("MediaRecorder prepared successfully")
            } catch (e: IOException) {
                recorderState = RecorderState.ERROR
                throw e
            }
        }
    }

    private fun stopRecording() {
        if (recorderState != RecorderState.RECORDING) {
            Timber.w("Not recording, current state: $recorderState")
            cleanupResources()
            stopSelf()
            return
        }

        try {
            updateNotification("Finalizando...")
            recorderState = RecorderState.STOPPED
            
            // CRITICAL: Stop MediaRecorder de forma segura
            mediaRecorder?.apply {
                try {
                    stop()
                    Timber.d("MediaRecorder stopped")
                } catch (e: RuntimeException) {
                    // Puede lanzar IllegalStateException si ya está stopped
                    Timber.w(e, "Error stopping MediaRecorder")
                }
            }
            
            val duration = System.currentTimeMillis() - sessionStartTime
            Timber.i("Recording completed: $currentSessionId, duration: ${duration}ms")
            
            broadcastRecordingStatus(false, currentSessionId, duration)
            
        } finally {
            cleanupResources()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun handleProjectionStopped() {
        Timber.w("Handling projection stopped by system")
        if (recorderState == RecorderState.RECORDING) {
            stopRecording()
        }
    }

    private fun cleanupResources() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            
            mediaRecorder?.apply {
                if (recorderState == RecorderState.RECORDING) {
                    try {
                        stop()
                    } catch (e: Exception) {
                        Timber.w(e, "Error stopping recorder during cleanup")
                    }
                }
                reset()
                release()
            }
            mediaRecorder = null
            
            mediaProjection?.stop()
            mediaProjection = null
            
            recorderState = RecorderState.RELEASED
            
            Timber.d("Resources cleaned up")
        } catch (e: Exception) {
            Timber.e(e, "Error during cleanup")
        }
    }

    private fun initializeDisplayMetrics() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        
        screenDensity = resources.displayMetrics.densityDpi
        Timber.d("Display: ${screenWidth}x${screenHeight} @ ${screenDensity}dpi")
    }

    private fun createOutputFile(sessionId: String): File {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val appDir = File(moviesDir, "QA_BlackBox").apply { mkdirs() }
        return File(appDir, "recording_$sessionId.mp4")
    }

    private fun generateSessionId(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return dateFormat.format(Date())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Grabación de Pantalla",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificación de grabación activa"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val stopIntent = Intent(this, ScreenRecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("QA Black Box")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_record)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, "Detener", stopPendingIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun broadcastRecordingStatus(
        isRecording: Boolean, 
        sessionId: String?, 
        duration: Long = 0L
    ) {
        val intent = Intent("com.qa.blackbox.RECORDING_STATUS").apply {
            putExtra("is_recording", isRecording)
            putExtra("session_id", sessionId)
            putExtra("duration", duration)
            putExtra("start_time", sessionStartTime)
        }
        sendBroadcast(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        cleanupResources()
        super.onDestroy()
        Timber.d("ScreenRecordingService destroyed")
    }
}
