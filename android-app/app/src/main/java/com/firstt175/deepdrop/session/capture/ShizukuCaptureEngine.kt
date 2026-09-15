package com.firstt175.deepdrop.session.capture

import com.firstt175.deepdrop.session.LsfgLog
import com.firstt175.deepdrop.session.NativeBridge

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.HardwareBuffer
import android.os.IBinder
import android.os.RemoteException
import com.firstt175.deepdrop.BuildConfig
import com.firstt175.deepdrop.shizuku.IShizukuCaptureService
import com.firstt175.deepdrop.shizuku.IShizukuFrameCallback
import rikka.shizuku.Shizuku

/**
 * Capture is intentionally dumb: bind the Shizuku user service, forward every
 * frame it hands back to NativeBridge.pushFrame(). Scheduling, pacing,
 * dropping and latency policy all belong to the native render loop's own
 * queue — see CaptureMetrics for the (engine-independent) FPS/frame-graph
 * telemetry.
 */
class ShizukuCaptureEngine(
    private val ctx: Context,
) {
    fun interface ErrorListener {
        fun onError(message: String)
    }

    @Volatile
    private var service: IShizukuCaptureService? = null
    @Volatile
    private var pendingStart: StartArgs? = null
    @Volatile
    private var errorListener: ErrorListener? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            service = binder?.takeIf { it.pingBinder() }?.let { IShizukuCaptureService.Stub.asInterface(it) }
            val start = pendingStart
            if (start != null) {
                startCapture(start.targetPackage, start.width, start.height, start.maxFps)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    fun setErrorListener(listener: ErrorListener?) {
        errorListener = listener
    }

    fun isReady(): Boolean {
        return runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun startCapture(targetPackage: String, width: Int, height: Int, maxFps: Int) {
        startCaptureInternal(targetPackage, width, height, maxFps)
    }

    /** Compatibility entry point; capture is always a frame source now. */
    fun startMetricsOnly(targetPackage: String, width: Int, height: Int, maxFps: Int) {
        startCaptureInternal(targetPackage, width, height, maxFps)
    }

    private fun startCaptureInternal(targetPackage: String, width: Int, height: Int, maxFps: Int) {
        val targetUid = runCatching {
            ctx.packageManager.getApplicationInfo(targetPackage, 0).uid
        }.getOrElse {
            errorListener?.onError("Target package not found: $targetPackage")
            return
        }

        val args = StartArgs(targetPackage, width, height, maxFps)
        pendingStart = args
        val svc = service
        if (svc == null || !svc.asBinder().pingBinder()) {
            bind()
            return
        }
        runCatching {
            svc.startCapture(targetUid, width, height, maxFps, frameCallback)
            LsfgLog.i(TAG, "Shizuku capture started package=$targetPackage uid=$targetUid ${width}x${height}")
        }.onFailure {
            LsfgLog.w(TAG, "Shizuku startCapture failed", it)
            errorListener?.onError("Shizuku start failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    fun stop() {
        pendingStart = null
        runCatching { service?.stopCapture() }
        runCatching { Shizuku.unbindUserService(userServiceArgs(), connection, true) }
        service = null
    }

    fun pauseCapture() {
        pendingStart = null
        runCatching { service?.stopCapture() }
    }

    private fun bind() {
        if (!isReady()) {
            errorListener?.onError("Shizuku is not running or permission is missing")
            return
        }
        runCatching {
            Shizuku.bindUserService(userServiceArgs(), connection)
        }.onFailure {
            LsfgLog.w(TAG, "bindUserService failed", it)
            errorListener?.onError("Shizuku bind failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, ShizukuCaptureUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("capture")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            .tag("lsfg_capture")

    private val frameCallback = object : IShizukuFrameCallback.Stub() {
        override fun onFrame(buffer: HardwareBuffer, timestampNs: Long, frameId: Long) {
            try {
                NativeBridge.pushFrame(buffer, timestampNs, frameId)
            } catch (t: Throwable) {
                LsfgLog.w(TAG, "pushFrame from Shizuku failed", t)
            } finally {
                runCatching { buffer.close() }
            }
        }

        override fun onError(message: String?) {
            errorListener?.onError(message ?: "Unknown Shizuku capture error")
        }

        override fun onFrameMetrics(timestampNs: Long, frameTimeNs: Long, pacingJitterNs: Long) {
            // Kept for AIDL compatibility. Capture must not perform timing, pacing,
            // latency or frame-drop decisions; RenderLoop owns those policies.
        }
    }

    private data class StartArgs(
        val targetPackage: String,
        val width: Int,
        val height: Int,
        val maxFps: Int,
    )

    companion object {
        private const val TAG = "ShizukuCapture"

        fun requestPermission(requestCode: Int) {
            if (Shizuku.isPreV11()) throw RemoteException("Shizuku pre-v11 is not supported")
            Shizuku.requestPermission(requestCode)
        }
    }
}
