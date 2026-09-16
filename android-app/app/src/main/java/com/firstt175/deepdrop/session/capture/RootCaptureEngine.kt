package com.firstt175.deepdrop.session.capture

import com.firstt175.deepdrop.session.LsfgLog
import com.firstt175.deepdrop.session.NativeBridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.firstt175.deepdrop.shizuku.IShizukuCaptureService
import com.firstt175.deepdrop.shizuku.IShizukuFrameCallback
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService

/**
 * Capture is intentionally dumb: bind the root service, forward every frame
 * it hands back to NativeBridge.pushFrame(). Scheduling, pacing, dropping and
 * latency policy all belong to the native render loop's own queue — see
 * CaptureMetrics for the (engine-independent) FPS/frame-graph telemetry.
 */
class RootCaptureEngine(private val ctx: Context) {

    fun interface ErrorListener {
        fun onError(message: String)
    }

    @Volatile private var service: IShizukuCaptureService? = null
    @Volatile private var pendingStart: StartArgs? = null
    @Volatile private var errorListener: ErrorListener? = null
    @Volatile private var everConnected: Boolean = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            everConnected = true
            service = binder?.takeIf { it.pingBinder() }
                ?.let { IShizukuCaptureService.Stub.asInterface(it) }
            pendingStart?.let { start ->
                startCaptureInternal(start.targetPackage, start.width, start.height, start.maxFps)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            val wasConnected = everConnected
            service = null
            if (!wasConnected) {
                errorListener?.onError("Root access denied or su not available")
            }
        }
    }

    fun setErrorListener(listener: ErrorListener?) { errorListener = listener }

    fun isReady(): Boolean = Shell.isAppGrantedRoot() == true

    fun startCapture(targetPackage: String, width: Int, height: Int, maxFps: Int) {
        startCaptureInternal(targetPackage, width, height, maxFps)
    }

    private fun startCaptureInternal(targetPackage: String, width: Int, height: Int, maxFps: Int) {
        val targetUid = runCatching {
            ctx.packageManager.getApplicationInfo(targetPackage, 0).uid
        }.getOrElse {
            errorListener?.onError("Target package not found: $targetPackage")
            return
        }

        pendingStart = StartArgs(targetPackage, width, height, maxFps)
        val svc = service
        if (svc == null || !svc.asBinder().pingBinder()) {
            bind()
            return
        }
        runCatching {
            svc.startCapture(targetUid, width, height, maxFps, frameCallback)
            LsfgLog.i(TAG, "Root capture started pkg=$targetPackage uid=$targetUid ${width}x${height}")
        }.onFailure {
            LsfgLog.w(TAG, "Root startCapture failed", it)
            errorListener?.onError("Root capture start failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    fun stop() {
        pendingStart = null
        runCatching { service?.stopCapture() }
        runCatching { RootService.unbind(connection) }
        service = null
        everConnected = false
    }

    fun pauseCapture() {
        pendingStart = null
        runCatching { service?.stopCapture() }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun bind() {
        // libsu's RootService.bind enforces main-thread invocation. start() may
        // be called from the foreground service's worker thread (e.g. on a
        // surface-geometry change), so hop to the main looper if we're not
        // already there. Without this the bind throws IllegalStateException and
        // the root capture path silently never arms.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { bind() }
            return
        }
        everConnected = false
        runCatching {
            val intent = Intent(ctx, RootCaptureService::class.java)
            RootService.bind(intent, connection)
        }.onFailure {
            LsfgLog.w(TAG, "RootService.bind failed", it)
            errorListener?.onError("Root service bind failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private val frameCallback = object : IShizukuFrameCallback.Stub() {
        override fun onFrame(buffer: HardwareBuffer, timestampNs: Long, frameId: Long) {
            try {
                NativeBridge.pushFrame(buffer, timestampNs, frameId)
            } catch (t: Throwable) {
                LsfgLog.w(TAG, "pushFrame from root failed", t)
            } finally {
                runCatching { buffer.close() }
            }
        }

        override fun onError(message: String?) {
            errorListener?.onError(message ?: "Unknown root capture error")
        }

        // Kept only because the shared AIDL callback still exposes this method.
        // Root capture itself does not calculate or report timing/latency metrics.
        override fun onFrameMetrics(timestampNs: Long, frameTimeNs: Long, pacingJitterNs: Long) {
            // Intentionally empty: RenderLoop owns frame scheduling and latency policy.
        }
    }

    private data class StartArgs(
        val targetPackage: String,
        val width: Int,
        val height: Int,
        val maxFps: Int,
    )

    companion object {
        private const val TAG = "RootCaptureEngine"
    }
}
