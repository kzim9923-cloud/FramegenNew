package com.firstt175.deepdrop.session.capture

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.firstt175.deepdrop.session.LsfgLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Hardware H.264 screen recorder fed directly by the shared final-frame render tee. */
class GameScreenRecorder(private val ctx: Context) {
    @Volatile var isRecording = false
        private set
    private var recorder: MediaRecorder? = null
    private var pendingUri: android.net.Uri? = null
    private var outputPfd: android.os.ParcelFileDescriptor? = null
    private var inputSurface: android.view.Surface? = null
    private var startedAtMs = 0L

    @Synchronized
    fun start(width: Int, height: Int, mic: Boolean): Boolean {
        if (isRecording || Build.VERSION.SDK_INT < 29 || width <= 0 || height <= 0) return false
        val resolver = ctx.contentResolver
        val name = "Deepdrop_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Deepdrop")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }) ?: return false
        try {
            val pfd = resolver.openFileDescriptor(uri, "w") ?: error("open output failed")
            val r = MediaRecorder()
            if (mic) r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (mic) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(96_000)
                r.setAudioSamplingRate(44_100)
                r.setAudioChannels(1)
            }
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoEncodingBitRate(bitrate(width, height))
            r.setVideoFrameRate(60)
            r.setVideoSize(width, height)
            r.setOutputFile(pfd.fileDescriptor)
            r.prepare()
            // The recorder is fed by the render pipeline itself. Native Vulkan tees
            // each frame that was actually presented into this MediaRecorder surface.
            // There is deliberately NO second MediaProjection/VirtualDisplay here:
            // normal session and recording session therefore share the exact same
            // capture/present path; recording only adds H.264/AAC encoding.
            r.start()
            inputSurface = r.surface
            recorder = r
            pendingUri = uri
            outputPfd = pfd
            startedAtMs = android.os.SystemClock.elapsedRealtime()
            isRecording = true
            LsfgLog.i(TAG, "record start ${width}x${height} mic=$mic uri=$uri")
            return true
        } catch (t: Throwable) {
            LsfgLog.w(TAG, "record start failed", t)
            runCatching { resolver.delete(uri, null, null) }
            runCatching { outputPfd?.close() }
            return false
        }
    }

    @Synchronized
    fun stop(): android.net.Uri? {
        if (!isRecording) return null
        val uri = pendingUri
        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAtMs
        isRecording = false
        runCatching { recorder?.stop() }
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { outputPfd?.close() }
        outputPfd = null
        inputSurface = null
        if (uri != null) {
            if (elapsed < 500L) runCatching { ctx.contentResolver.delete(uri, null, null) }
            else runCatching { ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) }
        }
        pendingUri = null
        LsfgLog.i(TAG, "record stop ${elapsed}ms uri=$uri")
        return if (elapsed >= 500L) uri else null
    }

    @Synchronized fun abort() {
        if (!isRecording && pendingUri == null) return
        isRecording = false
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { outputPfd?.close() }
        outputPfd = null
        inputSurface = null
        pendingUri?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
        pendingUri = null
    }

    fun recordingSurface(): android.view.Surface? = inputSurface

    fun elapsedMs(): Long = if (!isRecording) 0L else android.os.SystemClock.elapsedRealtime() - startedAtMs

    private fun bitrate(w: Int, h: Int): Int = when {
        w.toLong() * h >= 8_000_000L -> 16_000_000
        w.toLong() * h >= 4_000_000L -> 12_000_000
        w.toLong() * h >= 2_000_000L -> 8_000_000
        else -> 5_000_000
    }
    companion object { private const val TAG = "DeepdropRecorder" }
}
