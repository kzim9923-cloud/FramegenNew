package com.firstt175.deepdrop.ui

import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.firstt175.deepdrop.R
import com.firstt175.deepdrop.prefs.AiEngine
import com.firstt175.deepdrop.prefs.FramegenBackend
import com.firstt175.deepdrop.prefs.RifeModel
import com.firstt175.deepdrop.prefs.IfrnetModel
import com.firstt175.deepdrop.prefs.LsfgPreferences
import com.firstt175.deepdrop.session.BundledIfrnetModel
import com.firstt175.deepdrop.session.BundledRifeModel
import com.firstt175.deepdrop.session.AssetModelScanner
import com.firstt175.deepdrop.session.AssetModelInfo
import com.firstt175.deepdrop.session.MyModelFolderScanner
import com.firstt175.deepdrop.session.UserModelFolder
import com.firstt175.deepdrop.session.ExtractResult
import com.firstt175.deepdrop.session.NativeBridge
import com.firstt175.deepdrop.session.ShaderExtractor
import com.firstt175.deepdrop.ui.components.CollapsibleSection
import com.firstt175.deepdrop.ui.components.IconBadge
import com.firstt175.deepdrop.ui.components.LsfgCard
import com.firstt175.deepdrop.ui.components.LsfgSecondaryButton
import com.firstt175.deepdrop.ui.components.LsfgTopBar
import com.firstt175.deepdrop.ui.components.SectionHeader
import com.firstt175.deepdrop.ui.theme.LsfgPrimary
import com.firstt175.deepdrop.ui.theme.LsfgStatusGood
import com.firstt175.deepdrop.ui.theme.LsfgStatusWarn

private sealed class ExtractionState {
    data object Idle : ExtractionState()
    data object Running : ExtractionState()
    data class Done(val success: Boolean, val message: String?) : ExtractionState()
}

private sealed class ImportState {
    data object Idle : ImportState()
    data object Running : ImportState()
    data class Done(val success: Boolean, val message: String?) : ImportState()
}

/** Sum of all file sizes under [path], in MB. Used to show the on-disk size of whichever
 *  model directory (bundled extraction or a "My Model" import) is currently active. */
private fun dirSizeMb(path: String): Float {
    val dir = java.io.File(path)
    if (!dir.exists()) return 0f
    val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    return bytes / (1024f * 1024f)
}

/** Mirrors lsfg_android::kNcnnErr* in NcnnInterpolator.hpp. */
private fun describeNcnnError(code: Int, engine: AiEngine = AiEngine.RIFE): String = when (code) {
    0 -> "ok"
    -1 -> "This build doesn't include ncnn (see the LSFG_HAVE_NCNN block in CMakeLists.txt — " +
        "download the ncnn Android Vulkan SDK and rebuild)."
    -2 -> "Bundled model files are missing from storage. Try \"Test model load\" again — " +
        "if it keeps failing, the app install may be corrupt."
    -3 -> if (engine == AiEngine.IFRNET) {
        "ncnn rejected ifrnet.param/ifrnet.bin, or the ifrnet.Warp custom layer failed to " +
            "register — check logcat tag lsfg-ncnn."
    } else {
        "ncnn rejected flownet.param/flownet.bin, or the rife.Warp custom layer failed to " +
            "register — check logcat tag lsfg-ncnn."
    }
    -4 -> "Model isn't loaded."
    -5 -> "Invalid arguments passed to the native interpolator."
    else -> "Unknown ncnn error ($code)"
}

@Composable
fun DllPickerScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val prefs = remember { LsfgPreferences(ctx) }
    val state by produceConfigState(prefs).collectAsState()

    var pickError by remember { mutableStateOf<String?>(null) }
    var extractionState by remember { mutableStateOf<ExtractionState>(ExtractionState.Idle) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var customPending by remember { mutableStateOf<UserModelFolder?>(null) }
    var modelRootPickerPending by remember { mutableStateOf<Uri?>(null) }
    var modelScanNonce by remember { mutableStateOf(0) }
    var pendingAssetModel by remember { mutableStateOf<AssetModelInfo?>(null) }
    var customModelMessage by remember { mutableStateOf<String?>(null) }
    // Label of whichever model was last imported via "MY MODEL" (as opposed to picked
    // from ASSET MODELS). Lets the MY MODEL card show its own name/size/status only
    // when it's actually the currently active model, instead of showing stale info
    // after the user switches to a different asset model.
    var lastCustomImportLabel by remember { mutableStateOf<String?>(null) }
    val assetModels = remember { AssetModelScanner.scan(ctx) }
    val userModels = remember(state.myModelsRootUri, modelScanNonce) {
        state.myModelsRootUri?.let { runCatching { MyModelFolderScanner.scan(ctx, Uri.parse(it)) }.getOrDefault(emptyList()) }
            ?: emptyList()
    }

    var importState by remember { mutableStateOf<ImportState>(ImportState.Idle) }
    // Bumped by the "Test model load" button to force the LaunchedEffect below to
    // re-run even when none of its other keys (backend/useVulkan/cpuThreads) changed.
    var testLoadNonce by remember { mutableStateOf(0) }

    // Serialises settings-screen native model loads. Native ncnn load/release
    // is not cancellable, so two LaunchedEffects must never race each other.
    // The actual native section below runs NonCancellable and holds this mutex
    // until release+load has completely finished.
    val aiLoadMutex = remember { Mutex() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = ctx.contentResolver
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "Lossless.dll"
        if (!name.equals("Lossless.dll", ignoreCase = true)) {
            pickError = "Selected file is \"$name\", expected \"Lossless.dll\". Pick the correct file."
            return@rememberLauncherForActivityResult
        }
        pickError = null
        prefs.setDll(uri.toString(), name)
        refreshConfigState(prefs)
        pendingUri = uri
        extractionState = ExtractionState.Running
    }

    val myModelsLocationPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            MyModelFolderScanner.ensureRoot(ctx, uri)
        }.onSuccess { rootUri ->
            prefs.setMyModelsRootUri(rootUri.toString())
            modelScanNonce++
            customModelMessage = "Model folder ready. Put model folders inside it, then tap REFRESH."
        }.onFailure {
            customModelMessage = "Couldn't create model folder: ${it.message}"
        }
    }

    /*
     * IMPORTANT: do not clear customPending/pendingAssetModel at the start of
     * these effects. Changing a LaunchedEffect key from inside the effect
     * cancels that same coroutine. The old code did exactly that, so tapping a
     * MY MODEL entry could cancel the copy/load job and report:
     * "The coroutine scope left the composition".
     *
     * Keep the pending value stable for the whole import, then clear it only
     * after the work and preference updates have completed. Cancellation from
     * leaving this screen is allowed to cancel the job normally and is not
     * turned into a fake "model load failed" error.
     */
    LaunchedEffect(customPending?.uri) {
        val pending = customPending ?: return@LaunchedEffect

        val result: Result<File> = try {
            Result.success(
                withContext(Dispatchers.IO) {
                    MyModelFolderScanner.importToPrivate(ctx, pending)
                }
            )
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            Result.failure(t)
        }

        result.onSuccess { dir ->
            prefs.setActiveModel(dir.absolutePath, pending.name, pending.engine)
            prefs.setFramegenBackend(FramegenBackend.NCNN_AI)
            prefs.setAiEngine(if (pending.engine == 1) AiEngine.IFRNET else AiEngine.RIFE)
            refreshConfigState(prefs)
            lastCustomImportLabel = pending.name
            customModelMessage = "Selected ${pending.name}. Loading on GPU..."
            testLoadNonce++
        }.onFailure {
            customModelMessage = "Model load failed: ${it.message ?: "unknown error"}"
        }

        // Clear only after the operation above has completed. This avoids
        // cancelling this LaunchedEffect while its native/model setup is in
        // progress.
        if (customPending?.uri == pending.uri) {
            customPending = null
        }
    }

    LaunchedEffect(pendingAssetModel?.dir) {
        val model = pendingAssetModel ?: return@LaunchedEffect

        val result: Result<File> = try {
            Result.success(
                withContext(Dispatchers.IO) {
                    AssetModelScanner.extract(ctx, model)
                }
            )
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            Result.failure(t)
        }

        result.onSuccess { dir ->
            prefs.setActiveModel(dir.absolutePath, model.label, model.engine)
            prefs.setAiEngine(if (model.engine == 1) AiEngine.IFRNET else AiEngine.RIFE)
            prefs.setFramegenBackend(FramegenBackend.NCNN_AI)
            refreshConfigState(prefs)
            testLoadNonce++
        }.onFailure {
            customModelMessage = "Asset model failed: ${it.message ?: "unknown error"}"
        }

        if (pendingAssetModel?.dir == model.dir) {
            pendingAssetModel = null
        }
    }

    LaunchedEffect(pendingUri, extractionState) {
        val uri = pendingUri
        if (uri != null && extractionState is ExtractionState.Running) {
            val result = withContext(Dispatchers.IO) { ShaderExtractor.extract(ctx, uri) }
            when (result) {
                is ExtractResult.Success -> {
                    prefs.setShadersReady(true)
                    refreshConfigState(prefs)
                    extractionState = ExtractionState.Done(success = true, message = null)
                }
                is ExtractResult.Failure -> {
                    prefs.setShadersReady(false)
                    refreshConfigState(prefs)
                    extractionState = ExtractionState.Done(success = false, message = result.message)
                }
            }
            pendingUri = null
        }
    }

    // Both engines' models ship as bundled APK assets (see BundledRifeModel /
    // BundledIfrnetModel) — there's nothing to pick anymore. This
    // LaunchedEffect just extracts-if-needed and does a real
    // NativeBridge.initAiInterpolator() call so the status card reflects
    // whether the model actually loads on this device, not just whether the
    // asset exists. Re-runs whenever the compute settings OR the selected
    // engine change, since both affect whether load succeeds, and once on
    // first entering this backend's settings. rifeUseVulkan/ifrnetUseVulkan
    // are no longer user-editable (see the COMPUTE card below — GPU/CPU is
    // now always hybrid, not a toggle) but stay in this key list since
    // they're still the allowGpu flag threaded through to
    // NativeBridge.initAiInterpolator() below.
    LaunchedEffect(
        state.framegenBackend, state.aiEngine, state.rifeModel, state.ifrnetModel,
        state.activeModelDir, state.activeModelEngine,
        testLoadNonce,
    ) {
        if (state.framegenBackend != FramegenBackend.NCNN_AI) return@LaunchedEffect
        val engine = state.aiEngine
        importState = ImportState.Running
        val (success, message) = withContext(NonCancellable + Dispatchers.IO) {
            aiLoadMutex.withLock {
                // Never let a cancelled Compose effect release an interpolator
                // while another native load is still running. This was a real
                // crash risk when leaving the screen during ncnn initialisation.
                NativeBridge.releaseAiInterpolator()

                // If the user picked an ASSET MODEL or imported one via MY MODEL
                // for the currently selected engine, that directory is the one
                // actually meant to be running — load THAT, not the generic
                // bundled default.
                val activeDir = state.activeModelDir
                val activeEngine = state.activeModelEngine

                // HARD RULE: when a model is selected in MY MODEL / ASSET MODELS,
                // that exact directory is the only model allowed to load. Never
                // silently replace it with a bundled asset when the directory is
                // missing, invalid, or belongs to another engine.
                val modelDir = if (activeDir != null) {
                    if (activeEngine != engine.nativeValue) {
                        return@withLock false to "Selected model belongs to another engine; no fallback model will be used."
                    }
                    val dir = File(activeDir)
                    val paramName = if (engine == AiEngine.IFRNET) "ifrnet.param" else "flownet.param"
                    val binName = if (engine == AiEngine.IFRNET) "ifrnet.bin" else "flownet.bin"
                    if (!dir.isDirectory || File(dir, paramName).length() <= 0 || File(dir, binName).length() <= 0) {
                        return@withLock false to "Selected model files are missing or invalid; no fallback model will be used."
                    }
                    dir.absolutePath
                } else {
                    // No explicit selection: only then use the normal bundled model.
                    when (engine) {
                        AiEngine.IFRNET -> {
                            if (!BundledIfrnetModel.ensureExtracted(ctx, state.ifrnetModel)) {
                                return@withLock false to "Couldn't extract the bundled model from the APK's assets."
                            }
                            BundledIfrnetModel.modelDir(ctx, state.ifrnetModel).absolutePath
                        }
                        AiEngine.RIFE -> {
                            if (!BundledRifeModel.ensureExtracted(ctx, state.rifeModel)) {
                                return@withLock false to "Couldn't extract the bundled model from the APK's assets."
                            }
                            BundledRifeModel.modelDir(ctx, state.rifeModel).absolutePath
                        }
                    }
                }

                val loadCode = NativeBridge.initAiInterpolator(
                    modelDir,
                    true,
                    -1,
                    // GPU-only AI. CPU thread tuning is intentionally ignored.
                    1,
                    engine.nativeValue,
                )
                if (loadCode == 0) {
                    true to (if (engine == AiEngine.IFRNET) "ifrnet (fp16)" else "flownet (fp16)")
                } else {
                    false to describeNcnnError(loadCode, engine)
                }
            }
        }
        prefs.setAiModelReady(
            success,
            if (success) "fp16" else null,
            if (success) listOf(if (engine == AiEngine.IFRNET) "ifrnet" else "flownet") else emptyList(),
        )
        refreshConfigState(prefs)
        importState = ImportState.Done(success = success, message = message)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LsfgTopBar(
            title = stringResource(R.string.nav_dll),
            onBack = { nav.popBackStack() },
        )

        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_framegen_backend), title = null)
            Spacer(Modifier.height(4.dp))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.framegenBackend == FramegenBackend.LSFG_DLL,
                    onClick = {
                        prefs.setFramegenBackend(FramegenBackend.LSFG_DLL)
                        refreshConfigState(prefs)
                    },
                    label = { Text(stringResource(R.string.backend_lsfg_dll)) },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = state.framegenBackend == FramegenBackend.NCNN_AI,
                    onClick = {
                        prefs.setFramegenBackend(FramegenBackend.NCNN_AI)
                        refreshConfigState(prefs)
                    },
                    label = { Text(stringResource(R.string.backend_ncnn_ai)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Only the settings for the currently selected backend are shown below —
        // the LSFG_DLL card (Lossless.dll / shader extraction) and the NCNN_AI
        // cards (model bundle + compute path) used to render together
        // regardless of which pipeline was active, which made it impossible to
        // tell which values were actually in effect. Whichever backend isn't
        // selected keeps its saved state untouched; it's just not shown.
        if (state.framegenBackend == FramegenBackend.LSFG_DLL) {

        val statusIcon: ImageVector
        val statusTint = when {
            extractionState is ExtractionState.Done && !(extractionState as ExtractionState.Done).success -> {
                statusIcon = Icons.Filled.Error
                MaterialTheme.colorScheme.error
            }
            state.shadersReady -> {
                statusIcon = Icons.Filled.CheckCircle
                LsfgStatusGood
            }
            state.dllDisplayName != null -> {
                statusIcon = Icons.AutoMirrored.Filled.InsertDriveFile
                LsfgStatusWarn
            }
            else -> {
                statusIcon = Icons.AutoMirrored.Filled.InsertDriveFile
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        }

        LsfgCard(accent = state.shadersReady) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = statusIcon, tint = statusTint, size = 48.dp)
                Spacer(Modifier.size(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.dllDisplayName ?: "No file selected",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when {
                            state.shadersReady -> "Shaders extracted and cached."
                            state.dllDisplayName != null -> "DLL selected. Shaders not extracted yet."
                            else -> stringResource(R.string.dll_status_none)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (extractionState is ExtractionState.Running) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Extracting and translating shaders…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    color = LsfgPrimary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val s = extractionState
            if (s is ExtractionState.Done) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (s.success) "Extraction succeeded. SPIR-V cached."
                    else "Extraction failed: ${s.message}",
                    color = if (s.success) LsfgStatusGood else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (pickError != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = pickError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        LsfgCard {
            Text(
                text = "SOURCE",
                style = MaterialTheme.typography.labelSmall,
                color = LsfgPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Pick Lossless.dll from your own legally purchased copy of Lossless Scaling on Steam.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Don't have it yet? Buy Lossless Scaling on Steam first — you'll need " +
                    "a legitimate copy before this screen can do anything.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LsfgSecondaryButton(
                text = "Get Lossless Scaling on Steam",
                onClick = {
                    ctx.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            Uri.parse("https://store.steampowered.com/app/993090/Lossless_Scaling/"),
                        ),
                    )
                },
                leadingIcon = Icons.Filled.FileOpen,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = extractionState !is ExtractionState.Running,
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = LsfgPrimary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.Icon(
                    Icons.Filled.FileOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.dll_pick_button))
            }
            Spacer(Modifier.height(8.dp))
            LsfgSecondaryButton(
                text = stringResource(R.string.dll_reextract_button),
                onClick = {
                    val uri = state.dllUri?.let(Uri::parse) ?: return@LsfgSecondaryButton
                    prefs.setShadersReady(false)
                    refreshConfigState(prefs)
                    pendingUri = uri
                    extractionState = ExtractionState.Running
                },
                enabled = state.dllUri != null && extractionState !is ExtractionState.Running,
                leadingIcon = Icons.Filled.Refresh,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        } // framegenBackend == LSFG_DLL

        if (state.framegenBackend == FramegenBackend.NCNN_AI) {

        LsfgCard {
            Text(
                text = "ENGINE",
                style = MaterialTheme.typography.labelSmall,
                color = LsfgPrimary,
            )
            Spacer(Modifier.height(8.dp))

            // Clear, single-glance summary of what's actually running right now — the engine,
            // the specific model variant, its size, and whether it's confirmed loaded (vs. just
            // selected). Everything below this line is for *changing* that; this line answers
            // "what am I using right now" without having to read the whole card.
            val activeLabel = state.activeModelName
                ?: if (state.aiEngine == AiEngine.IFRNET) state.ifrnetModel.label else state.rifeModel.label
            val activeSizeMb = state.activeModelDir?.let(::dirSizeMb)
                ?: if (state.aiEngine == AiEngine.IFRNET) state.ifrnetModel.sizeMb else state.rifeModel.sizeMb
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconBadge(
                    icon = if (state.aiModelReady) Icons.Filled.CheckCircle else Icons.Filled.Psychology,
                    tint = if (state.aiModelReady) LsfgStatusGood else MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 28.dp,
                )
                Spacer(Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Currently using: ${if (state.aiEngine == AiEngine.IFRNET) "IFRNet" else "RIFE"} — $activeLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "%.1f MB · %s".format(
                            activeSizeMb,
                            when {
                                importState is ImportState.Running -> "loading…"
                                state.aiModelReady -> "loaded on GPU"
                                else -> "not confirmed loaded yet"
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (importState is ImportState.Running) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    color = LsfgPrimary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (importState is ImportState.Done && !(importState as ImportState.Done).success) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Load failed: ${(importState as ImportState.Done).message ?: "unknown error"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

            Text(text = "ASSET MODELS — ${assetModels.size} models", style = MaterialTheme.typography.labelSmall, color = LsfgPrimary)
            Spacer(Modifier.height(8.dp))
            assetModels.forEach { model ->
                val active = state.activeModelName == model.label && state.activeModelEngine == model.engine
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (active) Modifier else Modifier.clickable { pendingAssetModel = model },
                        )
                        .padding(vertical = 6.dp),
                ) {
                    IconBadge(
                        icon = if (active) Icons.Filled.CheckCircle else Icons.AutoMirrored.Filled.InsertDriveFile,
                        tint = if (active) LsfgStatusGood else MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 24.dp,
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(model.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${if (model.engine == 1) "IFRNet" else "RIFE"} · %.1f MB".format(model.sizeMb),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (active) {
                        Text("ACTIVE", style = MaterialTheme.typography.labelSmall, color = LsfgStatusGood)
                    }
                }
            }
        }

        LsfgCard {
            Text(text = "MY MODEL", style = MaterialTheme.typography.labelSmall, color = LsfgPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Choose a storage location once. DeepDrop creates a DeepDropModels folder there. Put unlimited model folders inside it; each folder name becomes the model name automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            LsfgSecondaryButton(
                text = if (state.myModelsRootUri == null) "CHOOSE MODEL STORAGE" else "CHANGE STORAGE",
                onClick = { myModelsLocationPicker.launch(null) },
                leadingIcon = Icons.Filled.FileOpen,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.myModelsRootUri != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Folder: DeepDropModels",
                    style = MaterialTheme.typography.bodySmall,
                    color = LsfgStatusGood,
                )
                Spacer(Modifier.height(8.dp))
                LsfgSecondaryButton(
                    text = "REFRESH MODELS (${userModels.size})",
                    onClick = { modelScanNonce++ },
                    leadingIcon = Icons.Filled.Refresh,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                if (userModels.isEmpty()) {
                    Text(
                        text = "No supported model folders found yet. Create a folder inside DeepDropModels and place its NCNN model files inside it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    userModels.forEach { model ->
                        val active = state.activeModelName == model.name &&
                            state.activeModelEngine == model.engine
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (active) Modifier else Modifier.clickable { customPending = model })
                                .padding(vertical = 7.dp),
                        ) {
                            IconBadge(
                                icon = if (active) Icons.Filled.CheckCircle
                                else Icons.AutoMirrored.Filled.InsertDriveFile,
                                tint = if (active) LsfgStatusGood
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                size = 24.dp,
                            )
                            Spacer(Modifier.size(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(model.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = if (model.engine == 0) "RIFE · NCNN" else "IFRNet · NCNN",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (active) {
                                Text("ACTIVE", style = MaterialTheme.typography.labelSmall, color = LsfgStatusGood)
                            }
                        }
                    }
                }
            }

            if (lastCustomImportLabel != null && state.activeModelName == lastCustomImportLabel) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Selected: ${state.activeModelName}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = if (state.aiModelReady) "Loaded on GPU." else "Loading / not confirmed yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.aiModelReady) LsfgStatusGood
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            customModelMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        LsfgCard {
            Text(
                text = "AI MODEL (NCNN)",
                style = MaterialTheme.typography.labelSmall,
                color = LsfgPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.height(12.dp))
            LsfgSecondaryButton(
                text = stringResource(R.string.ai_model_test_load_button),
                onClick = { testLoadNonce++ },
                enabled = importState !is ImportState.Running,
                leadingIcon = Icons.Filled.Refresh,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        } // framegenBackend == NCNN_AI

        DeviceInfoCard()
    }
}

/**
 * GPU-only device card. Deliberately contains no CPU telemetry or polling:
 * reading /proc/stat/sysfs is removed from the runtime path.
 */
@Composable
private fun DeviceInfoCard() {
    val vulkanApiVersion = remember {
        runCatching { NativeBridge.getVulkanApiVersion() }.getOrDefault("unknown")
    }
    val gpuVendor = remember { runCatching { NativeBridge.getGpuVendor() }.getOrDefault("unknown") }
    val gpuDeviceType = remember { runCatching { NativeBridge.getGpuDeviceType() }.getOrDefault("unknown") }
    val gpuDriverVersion = remember { runCatching { NativeBridge.getGpuDriverVersion() }.getOrDefault("unknown") }
    val gpuVramMb = remember { runCatching { NativeBridge.getGpuVramMb() }.getOrDefault(-1L) }
    val gpuName = remember {
        runCatching { NativeBridge.getVulkanGpuName(0) }.getOrDefault(null)?.takeIf { it.isNotBlank() }
    }

}
