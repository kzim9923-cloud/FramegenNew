package com.firstt175.deepdrop

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.memory.MemoryCache
import com.firstt175.deepdrop.session.AppIntegrity
import com.firstt175.deepdrop.session.CrashReporter
import com.firstt175.deepdrop.session.GamepadInputManager
import com.firstt175.deepdrop.session.LsfgLog
import com.firstt175.deepdrop.session.AdbDisplayController

class LsfgApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // Install the crash reporter as early as possible so even a crash during
        // the first JNI load attempt gets captured (NativeBridge static init
        // already loads the .so; the call below only configures the handler).
        CrashReporter.install(this)
        LsfgLog.init(this)

        // Emergency display recovery: if a previous session died before its
        // cleanup path ran, restore native size/DPI in the background. This
        // never blocks application startup and is harmless when no override exists.
        Thread({
            runCatching { AdbDisplayController.restoreIfDrifted(this) }
                .onFailure { LsfgLog.e("DisplayRecovery", "startup recovery failed", it) }
        }, "deepdrop-display-recovery").start()

        // App-wide controller connect/disconnect detection — registered once
        // here so both the settings UI and the in-game overlays can observe
        // GamepadInputManager.connected without each running their own
        // InputManager listener.
        GamepadInputManager.init(this)

        // Informational only — see AppIntegrity's kdoc. Recorded to the local
        // log so it's visible in an exported diagnostics report if a user
        // ever needs support with a copy that turns out not to be official;
        // this never alters app behavior.
        when (AppIntegrity.check(this)) {
            AppIntegrity.Result.UNOFFICIAL ->
                LsfgLog.w("AppIntegrity", "Running build's signature does not match the official release key")
            AppIntegrity.Result.OFFICIAL ->
                LsfgLog.i("AppIntegrity", "Build signature verified as official")
            AppIntegrity.Result.NOT_CONFIGURED -> Unit
        }
    }

    /**
     * Coil's default ImageLoader sizes its in-memory bitmap cache to 25% of
     * available app RAM, which is unnecessary here — the app only ever
     * loads locally-decoded app icons (already downsampled, see
     * GameLauncherScreen) and one small bundled GIF avatar on the Credits
     * screen. Capping it well below the default keeps that cache from
     * quietly growing into a large, mostly-unused RAM reservation. Nothing
     * here touches the frame-generation pipeline, which does its own
     * memory management separately in native code.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.08)
                .build()
        }
        .build()
}
