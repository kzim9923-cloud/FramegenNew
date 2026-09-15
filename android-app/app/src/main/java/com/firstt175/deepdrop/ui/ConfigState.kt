package com.firstt175.deepdrop.ui

import com.firstt175.deepdrop.prefs.LsfgConfig
import com.firstt175.deepdrop.prefs.AiEngine
import com.firstt175.deepdrop.prefs.CaptureSource
import com.firstt175.deepdrop.prefs.DrawerEdge
import com.firstt175.deepdrop.prefs.FramegenBackend
import com.firstt175.deepdrop.prefs.ImageEnhancementBackend
import com.firstt175.deepdrop.prefs.LsfgPreferences
import com.firstt175.deepdrop.prefs.OverlayMode
import com.firstt175.deepdrop.prefs.PresentMode
import com.firstt175.deepdrop.prefs.RifeModel
import com.firstt175.deepdrop.prefs.IfrnetModel
import com.firstt175.deepdrop.prefs.UpscaleFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Produces a StateFlow bound to [LsfgPreferences] content. Each screen that mutates prefs
 * should call [refresh] after commit so the home screen reflects updates.
 *
 * This is intentionally simple (manual refresh) to avoid pulling in DataStore for Phase 1.
 */
private val shared: MutableStateFlow<LsfgConfig> = MutableStateFlow(
    LsfgConfig(
        dllUri = null,
        dllDisplayName = null,
        shadersReady = false,
        lsfgEnabled = true,
        multiplier = 2,
        flowScale = 1.0f,
        performanceMode = true,
        hdrMode = false,
        framegenFp16 = true,
        captureSource = CaptureSource.MEDIA_PROJECTION,
        renderResolutionScale = 0.9f,
        legalAccepted = false,
        fpsCounterEnabled = false,
        frameGraphEnabled = false,
        cpuStatEnabled = false,
        gpuStatEnabled = false,
        ramStatEnabled = false,
        hudPositionUnlocked = false,
        hudPositionX = 0.02f,
        hudPositionY = 0.02f,
        drawerEdge = DrawerEdge.RIGHT,
        overlayMode = OverlayMode.ICON_BUTTON,
        presentMode = PresentMode.MAILBOX,
        waitForBusyGeneration = false,
        allowGenerationWhenBusy = false,
        losslessQueue = false,
        autoEnabledApps = emptySet(),
        trustedOverlay = false,
        gestureForwardingEnabled = false,
        framegenBackend = FramegenBackend.LSFG_DLL,
        aiModelUri = null,
        aiModelDisplayName = null,
        aiModelReady = false,
        aiModelPrecision = null,
        aiModelGraphs = emptyList(),
        activeModelDir = null,
        activeModelName = null,
        activeModelEngine = null,
        myModelsRootUri = null,
        aiEngine = AiEngine.RIFE,
        rifeModel = RifeModel.V4_17_LITE,
        ifrnetModel = IfrnetModel.S_VIMEO90K,
        imageEnhancementEnabled = false,
        imageEnhancementBackend = ImageEnhancementBackend.CPU,
        imageEnhancementContrast = 1.0f,
        imageEnhancementSaturation = 1.0f,
        upscaleEnabled = true,
        upscaleFilter = UpscaleFilter.BILINEAR,
    )
)

fun produceConfigState(prefs: LsfgPreferences): StateFlow<LsfgConfig> {
    shared.value = prefs.load()
    return shared
}

fun refreshConfigState(prefs: LsfgPreferences) {
    shared.value = prefs.load()
}
