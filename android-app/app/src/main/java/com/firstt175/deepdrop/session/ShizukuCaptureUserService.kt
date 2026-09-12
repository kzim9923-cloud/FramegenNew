package com.firstt175.deepdrop.session

import android.util.Log
import com.firstt175.deepdrop.shizuku.IShizukuCaptureService
import com.firstt175.deepdrop.shizuku.IShizukuFrameCallback
import java.util.concurrent.atomic.AtomicBoolean

class ShizukuCaptureUserService : IShizukuCaptureService.Stub() {

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun startCapture(
        targetUid: Int,
        width: Int,
        height: Int,
        maxFps: Int,
        callback: IShizukuFrameCallback,
    ) {
        stopCapture()
        running.set(true)
        worker = Thread({
            runCaptureLoop(targetUid, width, height, callback)
        }, "lsfg-shizuku-capture").also { it.start() }
    }

    override fun stopCapture() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }

    override fun describeBackend(): String {
        return "uid=${android.os.Process.myUid()} sdk=${android.os.Build.VERSION.SDK_INT}"
    }

    override fun destroy() {
        stopCapture()
        System.exit(0)
    }

    private fun runCaptureLoop(
        targetUid: Int,
        width: Int,
        height: Int,
        callback: IShizukuFrameCallback,
    ) {
        val capture = runCatching { PrivilegedScreenCapture(width, height, targetUid) }
            .getOrElse { e ->
                Log.w(TAG, "Unable to initialize privileged capture", e)
                callback.onError("Shizuku capture unavailable: ${e.message ?: e.javaClass.simpleName}")
                running.set(false)
                return
            }

        while (running.get()) {
            val hb = runCatching { capture.captureHardwareBuffer() }
                .onFailure {
                    Log.w(TAG, "captureHardwareBuffer failed", it)
                    callback.onError("Shizuku capture failed: ${it.message ?: it.javaClass.simpleName}")
                }
                .getOrNull()

            if (hb != null) {
                val timestampNs = System.nanoTime()
                try {
                    // Do not pace, drop, inspect, or otherwise process frames here.
                    // The client callback forwards this buffer directly to RenderLoop.
                    callback.onFrame(hb, timestampNs)
                } catch (t: Throwable) {
                    Log.w(TAG, "frame callback failed", t)
                    running.set(false)
                } finally {
                    runCatching { hb.close() }
                }
            }
        }
    }


    companion object {
        private const val TAG = "ShizukuUserCapture"
    }
}
