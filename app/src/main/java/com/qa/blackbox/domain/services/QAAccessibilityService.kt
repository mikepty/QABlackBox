package com.qa.blackbox.domain.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.qa.blackbox.domain.models.AccessibilityEventLog
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/**
 * Servicio de accesibilidad que intercepta eventos de UI
 * SECURITY: Redacta contraseñas automáticamente
 */
class QAAccessibilityService : AccessibilityService() {

    companion object {
        private const val EVENTS_FILENAME = "events.jsonl" // JSON Lines format
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var isRecording = false
    private var recordingStartTime: Long = 0L
    private var currentSessionId: String? = null
    private var eventsFile: File? = null
    private var fileWriter: FileWriter? = null
    
    private var currentForegroundPackage: String? = null
    private var isKeyboardVisible = false

    private val recordingStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val recording = intent?.getBooleanExtra("is_recording", false) ?: false
            val sessionId = intent?.getStringExtra("session_id")
            val startTime = intent?.getLongExtra("start_time", 0L) ?: 0L
            
            handleRecordingStatusChange(recording, sessionId, startTime)
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        // Registrar receiver para sincronizar con el servicio de grabación
        val filter = IntentFilter("com.qa.blackbox.RECORDING_STATUS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordingStatusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(recordingStatusReceiver, filter)
        }
        
        Timber.d("QAAccessibilityService created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        
        // Configurar el servicio para capturar todos los eventos necesarios
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            
            notificationTimeout = 100L
        }
        
        serviceInfo = info
        Timber.i("QAAccessibilityService connected and configured")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isRecording) return
        
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleWindowStateChanged(event)
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    handleViewClicked(event)
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    handleTextChanged(event)
                }
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    handleViewScrolled(event)
                }
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    handleViewFocused(event)
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    detectKeyboardState(event)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error processing accessibility event")
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        // Detectar cambio de app en primer plano
        event.packageName?.let { pkg ->
            val packageStr = pkg.toString()
            if (packageStr != "com.qa.blackbox" && packageStr != currentForegroundPackage) {
                currentForegroundPackage = packageStr
                
                logEvent(
                    AccessibilityEventLog(
                        timestamp = System.currentTimeMillis(),
                        relativeTime = calculateRelativeTime(),
                        eventType = "APP_SWITCH",
                        packageName = packageStr,
                        className = event.className?.toString(),
                        viewId = null,
                        coordinates = null,
                        text = "Switched to: ${event.className}",
                        additionalData = mapOf("window" to (event.className?.toString() ?: "unknown"))
                    )
                )
                
                Timber.d("Foreground app changed: $packageStr")
            }
        }
    }

    private fun handleViewClicked(event: AccessibilityEvent) {
        val source = event.source ?: return
        
        try {
            val bounds = Rect()
            source.getBoundsInScreen(bounds)
            val centerX = bounds.centerX()
            val centerY = bounds.centerY()
            
            val viewId = source.viewIdResourceName
            val className = source.className?.toString() ?: "Unknown"
            val text = extractText(source)
            
            logEvent(
                AccessibilityEventLog(
                    timestamp = System.currentTimeMillis(),
                    relativeTime = calculateRelativeTime(),
                    eventType = "CLICK",
                    packageName = event.packageName?.toString() ?: "unknown",
                    className = className,
                    viewId = viewId,
                    coordinates = Pair(centerX, centerY),
                    text = text,
                    additionalData = mapOf(
                        "bounds" to "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
                        "clickable" to source.isClickable.toString()
                    )
                )
            )
            
            Timber.d("Click: viewId=$viewId, coords=($centerX,$centerY)")
            
        } finally {
            source.recycle()
        }
    }

    private fun handleTextChanged(event: AccessibilityEvent) {
        val source = event.source ?: return
        
        try {
            // SECURITY CHECK: Redactar contraseñas
            val isPassword = source.isPassword
            val text = if (isPassword) {
                "[REDACTED]"
            } else {
                event.text?.joinToString(" ") ?: ""
            }
            
            logEvent(
                AccessibilityEventLog(
                    timestamp = System.currentTimeMillis(),
                    relativeTime = calculateRelativeTime(),
                    eventType = "TEXT_CHANGED",
                    packageName = event.packageName?.toString() ?: "unknown",
                    className = source.className?.toString(),
                    viewId = source.viewIdResourceName,
                    coordinates = null,
                    text = text,
                    isPassword = isPassword,
                    additionalData = mapOf(
                        "text_length" to (event.text?.firstOrNull()?.length?.toString() ?: "0"),
                        "before_text" to (event.beforeText?.toString() ?: "")
                    )
                )
            )
            
            Timber.d("Text changed: isPassword=$isPassword, length=${text.length}")
            
        } finally {
            source.recycle()
        }
    }

    private fun handleViewScrolled(event: AccessibilityEvent) {
        val source = event.source ?: return
        
        try {
            logEvent(
                AccessibilityEventLog(
                    timestamp = System.currentTimeMillis(),
                    relativeTime = calculateRelativeTime(),
                    eventType = "SCROLL",
                    packageName = event.packageName?.toString() ?: "unknown",
                    className = source.className?.toString(),
                    viewId = source.viewIdResourceName,
                    coordinates = null,
                    text = null,
                    additionalData = mapOf(
                        "scroll_x" to event.scrollX.toString(),
                        "scroll_y" to event.scrollY.toString(),
                        "max_scroll_x" to event.maxScrollX.toString(),
                        "max_scroll_y" to event.maxScrollY.toString()
                    )
                )
            )
        } finally {
            source.recycle()
        }
    }

    private fun handleViewFocused(event: AccessibilityEvent) {
        val source = event.source ?: return
        
        try {
            // Detectar si es un campo de entrada de texto
            if (source.isEditable) {
                logEvent(
                    AccessibilityEventLog(
                        timestamp = System.currentTimeMillis(),
                        relativeTime = calculateRelativeTime(),
                        eventType = "FOCUS",
                        packageName = event.packageName?.toString() ?: "unknown",
                        className = source.className?.toString(),
                        viewId = source.viewIdResourceName,
                        coordinates = null,
                        text = null,
                        isPassword = source.isPassword,
                        additionalData = mapOf(
                            "editable" to "true",
                            "hint" to (source.hintText?.toString() ?: "")
                        )
                    )
                )
            }
        } finally {
            source.recycle()
        }
    }

    private fun detectKeyboardState(event: AccessibilityEvent) {
        // Heurística para detectar teclado: buscar IME en la jerarquía de ventanas
        val windows = windows ?: return
        
        val keyboardNowVisible = windows.any { window ->
            window.type == AccessibilityNodeInfo.WINDOW_TYPE_INPUT_METHOD
        }
        
        if (keyboardNowVisible != isKeyboardVisible) {
            isKeyboardVisible = keyboardNowVisible
            
            logEvent(
                AccessibilityEventLog(
                    timestamp = System.currentTimeMillis(),
                    relativeTime = calculateRelativeTime(),
                    eventType = "KEYBOARD_STATE",
                    packageName = event.packageName?.toString() ?: "unknown",
                    className = null,
                    viewId = null,
                    coordinates = null,
                    text = if (isKeyboardVisible) "Keyboard shown" else "Keyboard hidden",
                    additionalData = mapOf("visible" to isKeyboardVisible.toString())
                )
            )
            
            Timber.d("Keyboard visibility changed: $isKeyboardVisible")
        }
    }

    private fun extractText(node: AccessibilityNodeInfo): String? {
        return when {
            !node.text.isNullOrEmpty() -> node.text.toString()
            !node.contentDescription.isNullOrEmpty() -> node.contentDescription.toString()
            else -> null
        }
    }

    private fun calculateRelativeTime(): Long {
        return if (recordingStartTime > 0) {
            System.currentTimeMillis() - recordingStartTime
        } else {
            0L
        }
    }

    private fun logEvent(event: AccessibilityEventLog) {
        serviceScope.launch {
            try {
                // Escribir en formato JSON Lines (un JSON por línea)
                val json = buildString {
                    append("{")
                    append("\"timestamp\":${event.timestamp},")
                    append("\"relative_time\":${event.relativeTime},")
                    append("\"event_type\":\"${event.eventType}\",")
                    append("\"package\":\"${event.packageName}\",")
                    append("\"class\":${event.className?.let { "\"$it\"" } ?: "null"},")
                    append("\"view_id\":${event.viewId?.let { "\"$it\"" } ?: "null"},")
                    append("\"coordinates\":${event.coordinates?.let { "[${it.first},${it.second}]" } ?: "null"},")
                    append("\"text\":${event.text?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"},")
                    append("\"is_password\":${event.isPassword},")
                    append("\"additional\":{")
                    append(event.additionalData.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" })
                    append("}")
                    append("}")
                }
                
                fileWriter?.apply {
                    appendLine(json)
                    flush()
                }
            } catch (e: IOException) {
                Timber.e(e, "Error writing event to file")
            }
        }
    }

    private fun handleRecordingStatusChange(recording: Boolean, sessionId: String?, startTime: Long) {
        if (recording && sessionId != null) {
            startEventLogging(sessionId, startTime)
        } else {
            stopEventLogging()
        }
    }

    private fun startEventLogging(sessionId: String, startTime: Long) {
        if (isRecording) {
            Timber.w("Already logging events")
            return
        }
        
        try {
            currentSessionId = sessionId
            recordingStartTime = startTime
            isRecording = true
            
            // Crear archivo de eventos
            val moviesDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_MOVIES
            )
            val sessionDir = File(moviesDir, "QA_BlackBox").apply { mkdirs() }
            eventsFile = File(sessionDir, "events_$sessionId.jsonl")
            
            fileWriter = FileWriter(eventsFile, false)
            
            // Escribir metadata inicial
            val metadata = buildString {
                append("{")
                append("\"type\":\"SESSION_START\",")
                append("\"session_id\":\"$sessionId\",")
                append("\"start_time\":$startTime,")
                append("\"device\":\"${Build.MODEL}\",")
                append("\"android_version\":${Build.VERSION.SDK_INT}")
                append("}")
            }
            fileWriter?.appendLine(metadata)
            fileWriter?.flush()
            
            Timber.i("Event logging started: $sessionId")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to start event logging")
            isRecording = false
        }
    }

    private fun stopEventLogging() {
        if (!isRecording) return
        
        try {
            // Metadata final
            val metadata = buildString {
                append("{")
                append("\"type\":\"SESSION_END\",")
                append("\"end_time\":${System.currentTimeMillis()},")
                append("\"total_duration\":${calculateRelativeTime()}")
                append("}")
            }
            fileWriter?.appendLine(metadata)
            fileWriter?.flush()
            fileWriter?.close()
            
            Timber.i("Event logging stopped. File: ${eventsFile?.absolutePath}")
            
        } catch (e: Exception) {
            Timber.e(e, "Error stopping event logging")
        } finally {
            fileWriter = null
            eventsFile = null
            isRecording = false
            recordingStartTime = 0L
            currentSessionId = null
        }
    }

    override fun onInterrupt() {
        Timber.w("QAAccessibilityService interrupted")
        stopEventLogging()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopEventLogging()
        try {
            unregisterReceiver(recordingStatusReceiver)
        } catch (e: Exception) {
            Timber.w(e, "Error unregistering receiver")
        }
        super.onDestroy()
        Timber.d("QAAccessibilityService destroyed")
    }
}
