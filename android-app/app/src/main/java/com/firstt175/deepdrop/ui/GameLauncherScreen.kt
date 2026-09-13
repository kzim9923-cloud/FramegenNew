@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.firstt175.deepdrop.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Delete
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.firstt175.deepdrop.R
import com.firstt175.deepdrop.prefs.LsfgPreferences
import com.firstt175.deepdrop.prefs.AppLanguage
import com.firstt175.deepdrop.prefs.AppLanguagePrefs
import com.firstt175.deepdrop.session.AdbDisplayController
import com.firstt175.deepdrop.session.AppDisplayProfile
import com.firstt175.deepdrop.session.AppDisplayProfileStore
import com.firstt175.deepdrop.session.DisplayOverrideState
import com.firstt175.deepdrop.session.LsfgForegroundService
import com.firstt175.deepdrop.session.LsfgLog
import com.firstt175.deepdrop.session.PhysicalDisplayInfo
import com.firstt175.deepdrop.session.ShizukuDisplayPermission
import com.firstt175.deepdrop.ui.components.LsfgCard
import com.firstt175.deepdrop.ui.components.LsfgLogoMark
import com.firstt175.deepdrop.ui.components.SectionHeader
import com.firstt175.deepdrop.ui.components.StatusPill
import com.firstt175.deepdrop.ui.components.StatusTone
import com.firstt175.deepdrop.ui.components.ToggleRow
import com.firstt175.deepdrop.ui.components.ValueSlider
import com.firstt175.deepdrop.ui.rememberAppIconPainter
import com.firstt175.deepdrop.ui.theme.LsfgSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class LaunchableApp(
    val label: String,
    val packageName: String,
    val isGame: Boolean,
)

// android:appCategory="game" in the manifest is what ApplicationInfo.category
// reports, but most sideloaded/indie game APKs (like the one visible in the
// screenshot) never set it — that's why "Games" showed 0 even with an
// obvious game installed. FLAG_IS_GAME is the older pre-category signal some
// devices/APKs still carry, and a ".game." segment in the package name is a
// solid fallback for the many APKs that set neither.
@Suppress("DEPRECATION")
private fun looksLikeGame(ai: ApplicationInfo): Boolean {
    if (ai.category == ApplicationInfo.CATEGORY_GAME) return true
    if ((ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0) return true
    val segments = ai.packageName.lowercase().split(".")
    return segments.contains("game") || segments.contains("games")
}

private const val PREFS_LAUNCHER = "game_launcher"
private const val KEY_MANUAL_GAMES = "manual_games"
private const val KEY_HIDDEN_GAMES = "hidden_games"

private fun getManualGamePackages(context: Context): Set<String> =
    context.getSharedPreferences(PREFS_LAUNCHER, Context.MODE_PRIVATE)
        .getStringSet(KEY_MANUAL_GAMES, emptySet())
        ?.toSet()
        .orEmpty()

private fun addManualGame(context: Context, packageName: String) {
    val prefs = context.getSharedPreferences(PREFS_LAUNCHER, Context.MODE_PRIVATE)
    val games = prefs.getStringSet(KEY_MANUAL_GAMES, emptySet())?.toMutableSet() ?: mutableSetOf()
    games += packageName
    prefs.edit().putStringSet(KEY_MANUAL_GAMES, games).apply()
}

private fun removeGameFromLauncher(context: Context, packageName: String) {
    val prefs = context.getSharedPreferences(PREFS_LAUNCHER, Context.MODE_PRIVATE)
    val manual = prefs.getStringSet(KEY_MANUAL_GAMES, emptySet())?.toMutableSet() ?: mutableSetOf()
    val hidden = prefs.getStringSet(KEY_HIDDEN_GAMES, emptySet())?.toMutableSet() ?: mutableSetOf()
    manual.remove(packageName)
    hidden += packageName
    prefs.edit()
        .putStringSet(KEY_MANUAL_GAMES, manual)
        .putStringSet(KEY_HIDDEN_GAMES, hidden)
        .apply()
}

private fun unhideGame(context: Context, packageName: String) {
    val prefs = context.getSharedPreferences(PREFS_LAUNCHER, Context.MODE_PRIVATE)
    val hidden = prefs.getStringSet(KEY_HIDDEN_GAMES, emptySet())?.toMutableSet() ?: mutableSetOf()
    hidden.remove(packageName)
    prefs.edit().putStringSet(KEY_HIDDEN_GAMES, hidden).apply()
}

private fun getHiddenGamePackages(context: Context): Set<String> =
    context.getSharedPreferences(PREFS_LAUNCHER, Context.MODE_PRIVATE)
        .getStringSet(KEY_HIDDEN_GAMES, emptySet())
        ?.toSet()
        .orEmpty()

private fun loadLaunchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    val manualGames = getManualGamePackages(context)
    val hiddenGames = getHiddenGamePackages(context)
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .asSequence()
        .mapNotNull { info ->
            val ai = info.activityInfo?.applicationInfo ?: return@mapNotNull null
            if (ai.packageName == context.packageName || ai.packageName in hiddenGames) return@mapNotNull null
            LaunchableApp(
                label = ai.loadLabel(pm).toString().ifBlank { ai.packageName },
                packageName = ai.packageName,
                isGame = looksLikeGame(ai) || ai.packageName in manualGames,
            )
        }
        .distinctBy { it.packageName }
        .sortedWith(compareByDescending<LaunchableApp> { it.isGame }.thenBy { it.label.lowercase() })
        .toList()
}

private fun openAppInfo(context: Context, packageName: String) {
    val intent = Intent(ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    context.startActivity(intent)
}

/**
 * Refresh rates the device's default display can actually run at, read from
 * its [Display.Mode] list (all modes share resolution class but differ in
 * Hz on most phones). Falls back to just the display's current refresh
 * rate if the mode list can't be read for some reason.
 */
private fun getSupportedRefreshRates(context: Context): List<Int> {
    val display = runCatching {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        @Suppress("DEPRECATION")
        wm?.defaultDisplay
    }.getOrNull() ?: return emptyList()

    val fromModes = runCatching {
        display.supportedModes
            ?.map { it.refreshRate }
            .orEmpty()
    }.getOrElse { emptyList() }

    val rates = fromModes.ifEmpty {
        runCatching { listOf(display.refreshRate) }.getOrElse { emptyList() }
    }

    return rates
        .map { Math.round(it) }
        .filter { it > 0 }
        .distinct()
        .sorted()
}

private const val TAG_PRE_LAUNCH_DISPLAY = "LsfgPreLaunchDisplay"

// How long to wait after starting the target app's Activity before forcing
// the resolution/DPI override, so the app is already in the foreground
// (per product decision: open the app first, then apply the scaling —
// not the other way around). There's no cross-process "target process is
// now up" hook available here without Shizuku/root, so this is a short
// fixed delay rather than an exact signal.
private const val POST_LAUNCH_DISPLAY_DELAY_MS = 600L

/**
 * Applies (or clears) the per-app forced size/density override AFTER the
 * target app has been launched and given a moment to reach the foreground.
 *
 * Returns the resolved profile so the caller can log/display it.
 */
private suspend fun applyDisplayProfileAfterLaunch(
    context: Context,
    packageName: String,
): AppDisplayProfile? {
    if (!AdbDisplayController.isReady(context)) return null
    return runCatching {
        val current = AdbDisplayController.readDisplay(context) ?: return@runCatching null
        // Always (re)derive from the true physical panel size/stable density,
        // never from whatever size/density might currently be force-applied
        // from a previous session, so the percent-based calculation can't
        // compound across launches.
        val stored = AppDisplayProfileStore.captureOriginalIfMissing(context, packageName, current)
        if (stored.originalWidth <= 0 || stored.originalHeight <= 0) return@runCatching stored

        if (stored.enabled && stored.percent < 100) {
            val applied = AdbDisplayController.apply(context, stored)
            if (applied) {
                DisplayOverrideState.markApplied(context, packageName)
            }
            LsfgLog.i(
                TAG_PRE_LAUNCH_DISPLAY,
                "Post-launch display for $packageName: ${stored.percent}% -> " +
                    "${stored.calculatedWidth}x${stored.calculatedHeight} @ ${stored.calculatedDpi}dpi " +
                    "applied=$applied",
            )
        } else {
            // No per-app override for this app (or 100%): clear any stale
            // forced size/density left over from a previous app's session.
            AdbDisplayController.reset(context)
            DisplayOverrideState.clear(context)
        }
        stored
    }.onFailure {
        LsfgLog.e(TAG_PRE_LAUNCH_DISPLAY, "applyDisplayProfileAfterLaunch failed", it)
    }.getOrNull()
}


@Composable
fun GameLauncherScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(emptyList<LaunchableApp>()) }
    var filter by remember { mutableStateOf(1) } // 0 = all, 1 = games, 2 = apps
    var query by remember { mutableStateOf("") }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showAddGameDialog by remember { mutableStateOf(false) }

    val prefs = remember { LsfgPreferences(context) }
    val configState by produceConfigState(prefs).collectAsState()

    suspend fun launchApp(app: LaunchableApp) {
        val profile = withContext(Dispatchers.IO) {
            AppDisplayProfileStore.load(context, app.packageName)
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.toast_no_launch_button, app.label),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        if (profile.enabled) {
            delay(POST_LAUNCH_DISPLAY_DELAY_MS)
            withContext(Dispatchers.IO) {
                applyDisplayProfileAfterLaunch(context, app.packageName)
            }
        }
    }

    suspend fun refresh() {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (AdbDisplayController.isReady(context) && !LsfgForegroundService.isRunning.value) {
                AdbDisplayController.restoreIfDrifted(context)
            }
        }
        refresh()
    }

    val games = remember(apps) { apps.filter { it.isGame } }
    val normalApps = remember(apps) { apps.filter { !it.isGame } }
    val visibleApps = remember(apps, filter, query) {
        val source = when (filter) {
            1 -> games
            2 -> normalApps
            else -> apps
        }
        if (query.isBlank()) source
        else source.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    val drawerState = androidx.compose.material3.rememberDrawerState(
        initialValue = androidx.compose.material3.DrawerValue.Closed
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            androidx.compose.material3.ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.background,
                drawerContentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LsfgLogoMark(size = 52.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                "Deepdrop",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                            )
                            Text(
                                "ตัวเปิดเกม",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    androidx.compose.material3.NavigationDrawerItem(
                        label = { Text("เกมของฉัน") },
                        selected = filter == 1,
                        onClick = {
                            scope.launch { drawerState.close() }
                            filter = 1
                        },
                        icon = { Icon(Icons.Filled.Gamepad, null) },
                    )
                    androidx.compose.material3.NavigationDrawerItem(
                        label = { Text(stringResource(R.string.profile_button)) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            nav.navigate(Routes.PROFILE)
                        },
                        icon = { Icon(Icons.Filled.AccountCircle, null) },
                    )
                    androidx.compose.material3.NavigationDrawerItem(
                        label = { Text("การตั้งค่า") },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            nav.navigate(Routes.SETTINGS)
                        },
                        icon = { Icon(Icons.Filled.DisplaySettings, null) },
                    )
                    androidx.compose.material3.NavigationDrawerItem(
                        label = { Text("ตั้งค่าภาษา") },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            showLanguageDialog = true
                        },
                        icon = { Icon(Icons.Filled.Language, null) },
                    )
                    androidx.compose.material3.NavigationDrawerItem(
                        label = { Text(stringResource(R.string.credits_title)) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            nav.navigate(Routes.CREDITS)
                        },
                        icon = { Icon(Icons.Filled.Info, null) },
                    )

                    Spacer(Modifier.weight(1f))

                    LsfgCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Text(
                            "อุปกรณ์ของคุณ",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            android.os.Build.MODEL,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                        val mem = android.app.ActivityManager.MemoryInfo()
                        am?.getMemoryInfo(mem)
                        val totalGb = mem.totalMem / (1024.0 * 1024.0 * 1024.0)
                        Text(
                            "RAM %.1f GB".format(totalGb),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Deepdrop Launcher v${com.firstt175.deepdrop.BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
        ) {
            // Header: logo + title.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LsfgLogoMark(size = 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "ตัวเปิดเกม",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                    )
                    Text(
                        "เล่นเกมได้สบายขึ้น จัดการได้ง่ายกว่า",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Search bar, deliberately full width like the reference.
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                singleLine = true,
                leadingIcon = {
                    Icon(
                        androidx.compose.material.icons.Icons.Filled.Search,
                        contentDescription = null,
                    )
                },
                placeholder = { Text("ค้นหาเกมหรือแอป...") },
                shape = RoundedCornerShape(24.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "เกมของฉัน",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
            }

            // Game grid is the main visual area; it remains the only scrolling
            // region so the header and bottom navigation stay fixed.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (visibleApps.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Apps, null, Modifier.size(52.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(
                                stringResource(R.string.empty_no_apps_title),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        // Adaptive instead of a hardcoded column count: in
                        // portrait this settles at ~3 columns (same as
                        // before), but in landscape — or on a tablet — the
                        // extra width now fills in with more columns instead
                        // of stretching each tile into an oversized card.
                        columns = GridCells.Adaptive(minSize = 100.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = 2.dp,
                            bottom = 18.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        gridItems(visibleApps, key = { it.packageName }) { app ->
                            LauncherGameTile(
                                nav = nav,
                                app = app,
                                onLaunch = { scope.launch { launchApp(app) } },
                                onRemove = {
                                    removeGameFromLauncher(context, app.packageName)
                                    scope.launch { refresh() }
                                },
                            )
                        }

                        if (filter == 1 && query.isBlank()) {
                            item(key = "add-game") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(0.82f)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .clickable { showAddGameDialog = true },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Filled.Add, null, Modifier.size(38.dp))
                                        Spacer(Modifier.height(6.dp))
                                        Text("เพิ่มเกม")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom navigation mirrors the reference: Home / My Games / Profile.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LauncherBottomItem(
                    selected = false,
                    icon = Icons.Filled.DisplaySettings,
                    label = "ตั้งค่า",
                    onClick = { nav.navigate(Routes.SETTINGS) },
                )
                LauncherBottomItem(
                    selected = filter == 1,
                    icon = Icons.Filled.Gamepad,
                    label = "เกมของฉัน",
                    onClick = { filter = 1 },
                )
                LauncherBottomItem(
                    selected = false,
                    icon = Icons.Filled.AccountCircle,
                    label = "โปรไฟล์",
                    onClick = { nav.navigate(Routes.PROFILE) },
                )
            }
        }

        if (showAddGameDialog) {
            AddGameDialog(
                installedApps = apps,
                onAdd = { packageName ->
                    unhideGame(context, packageName)
                    addManualGame(context, packageName)
                    showAddGameDialog = false
                    scope.launch { refresh() }
                },
                onDismiss = { showAddGameDialog = false },
            )
        }

        if (showLanguageDialog) {
            LanguagePickerDialog(onDismiss = { showLanguageDialog = false })
        }

        // Retain the existing overflow actions without changing their behavior.
        Box {
            DropdownMenu(
                expanded = showMoreMenu,
                onDismissRequest = { showMoreMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.language_menu_item)) },
                    leadingIcon = { Icon(Icons.Filled.Language, null) },
                    onClick = {
                        showMoreMenu = false
                        showLanguageDialog = true
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.credits_title)) },
                    leadingIcon = { Icon(Icons.Filled.Info, null) },
                    onClick = {
                        showMoreMenu = false
                        nav.navigate(Routes.CREDITS)
                    },
                )
            }
        }
    }
}

@Composable
private fun LauncherBottomItem(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(110.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(25.dp),
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun AddGameDialog(
    installedApps: List<LaunchableApp>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val candidates = installedApps
        .filterNot { it.isGame }
        .sortedBy { it.label.lowercase() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("เพิ่มเกม") },
        text = {
            if (candidates.isEmpty()) {
                Text("ไม่พบแอปที่สามารถเพิ่มเป็นเกมได้")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(candidates, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onAdd(app.packageName) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Gamepad, null, Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, fontWeight = FontWeight.SemiBold)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(Icons.Filled.Add, "เพิ่ม")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("ยกเลิก") }
        },
    )
}

@Composable
private fun LauncherGameTile(
    nav: NavHostController,
    app: LaunchableApp,
    onLaunch: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(AppDisplayProfileStore.load(context, app.packageName)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onLaunch,
                onLongClick = { showMenu = true },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            val iconPainter = rememberAppIconPainter(app.packageName, 72)
            if (iconPainter != null) {
                Image(
                    painter = iconPainter,
                    contentDescription = app.label,
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(18.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Apps, null, Modifier.size(32.dp))
                }
            }

            if (profile.enabled) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(22.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        null,
                        Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        Spacer(Modifier.height(7.dp))
        Text(
            app.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )

        AppQuickActionsMenu(
            expanded = showMenu,
            onDismiss = { showMenu = false },
            onSettings = { showMenu = false; showSettings = true },
            onAppInfo = { showMenu = false; openAppInfo(context, app.packageName) },
            onRemove = onRemove?.let { remove ->
                {
                    showMenu = false
                    remove()
                    Toast.makeText(context, "ลบ ${app.label} ออกจากเกมของฉันแล้ว", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    if (showSettings) {
        AppCardSettingsDialog(
            nav = nav,
            packageName = app.packageName,
            label = app.label,
            initial = profile,
            onDismiss = { showSettings = false },
            onSaved = {
                profile = it
                showSettings = false
            },
        )
    }
}

@Composable
private fun LanguagePickerDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? android.app.Activity
    var selected by remember { mutableStateOf(AppLanguagePrefs.get(ctx)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language_menu_item)) },
        text = {
            Column {
                val options = listOf(
                    AppLanguage.SYSTEM to stringResource(R.string.language_system),
                    AppLanguage.ENGLISH to stringResource(R.string.language_english),
                    AppLanguage.THAI to stringResource(R.string.language_thai),
                )
                options.forEach { (lang, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = lang },
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selected == lang,
                            onClick = { selected = lang },
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                AppLanguagePrefs.set(ctx, selected)
                onDismiss()
                activity?.recreate()
            }) { Text(stringResource(R.string.language_apply)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.crash_dialog_dismiss))
            }
        },
    )
}

/**
 * Long-press context menu shared by every layout (list row, grid tile,
 * Switch-style tile): settings, app info — the same pair a regular
 * Android home screen shows on long-press, minus uninstall.
 */
@Composable
private fun AppQuickActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onAppInfo: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_settings)) },
            leadingIcon = { Icon(Icons.Filled.DisplaySettings, contentDescription = null) },
            onClick = onSettings,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_app_info)) },
            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
            onClick = onAppInfo,
        )
        if (onRemove != null) {
            DropdownMenuItem(
                text = { Text("ลบออกจากเกมของฉัน") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = onRemove,
            )
        }
    }
}

private const val TAG_APP_CARD_SETTINGS = "LsfgAppCardSettings"

@Composable
private fun AppCardSettingsDialog(
    nav: NavHostController,
    packageName: String,
    label: String,
    initial: AppDisplayProfile,
    onDismiss: () -> Unit,
    onSaved: (AppDisplayProfile) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf(initial) }
    var percent by remember { mutableStateOf(initial.percent.toFloat()) }
    var clean by remember { mutableStateOf(initial.dynamicClean) }
    var noAnimations by remember { mutableStateOf(initial.disableAnimations) }
    var keepAwake by remember { mutableStateOf(initial.keepAwake) }
    var fixedPerfMode by remember { mutableStateOf(initial.fixedPerformanceMode) }
    var dozeWhitelist by remember { mutableStateOf(initial.dozeWhitelist) }
    var forceStopBg by remember { mutableStateOf(initial.forceStopBackground) }
    var refreshRateHz by remember { mutableStateOf(initial.lockRefreshRateHz) }
    var wifiLock by remember { mutableStateOf(initial.wifiHighPerfLock) }
    var info by remember { mutableStateOf<PhysicalDisplayInfo?>(null) }
    val supportedHz = remember { getSupportedRefreshRates(context) }
    val refreshRateOptions = remember(supportedHz) { listOf(0) + supportedHz }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_app_settings_title, label)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LsfgSpacing.md),
            ) {
                val secureGranted = ShizukuDisplayPermission.hasWriteSecureSettings(context)
                // Shizuku/WRITE_SECURE_SETTINGS granting itself now lives only
                // on the single Setup screen — this dialog just flags it here
                // when it's still missing, instead of duplicating the flow.
                if (!secureGranted) {
                    LsfgCard(
                        onClick = { nav.navigate(Routes.SETUP) },
                        accent = true,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.status_no_permission), fontWeight = FontWeight.SemiBold)
                                Text(
                                    "ไปที่หน้าตั้งค่าเริ่มต้นเพื่อให้สิทธิ์",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StatusPill(label = "ยังไม่ได้ให้สิทธิ์", tone = StatusTone.Warn)
                        }
                    }
                }

                LsfgCard {
                    SectionHeader(eyebrow = "จอแสดงผล")
                    Spacer(Modifier.height(LsfgSpacing.sm))
                    if (profile.originalWidth > 0) {
                        Text(
                            stringResource(R.string.display_original_format, profile.originalWidth, profile.originalHeight, profile.originalDpi),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.display_calculated_format, profile.calculatedWidth, profile.calculatedHeight, profile.calculatedDpi),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            stringResource(R.string.display_original_not_saved),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(LsfgSpacing.sm))
                    ValueSlider(
                        title = "ความละเอียด",
                        valueDisplay = "${percent.toInt()}%",
                        description = null,
                        value = percent,
                        onValueChange = { percent = ((it / 5f).toInt() * 5).coerceIn(25, 100).toFloat() },
                        range = 25f..100f,
                        steps = 14,
                    )
                }

                LsfgCard {
                    SectionHeader(eyebrow = "พฤติกรรมของเซสชัน")
                    Spacer(Modifier.height(LsfgSpacing.sm))
                    ToggleRow(
                        icon = Icons.Filled.Refresh,
                        title = "ล้างพื้นหลังแบบไดนามิก",
                        checked = clean,
                        onCheckedChange = { clean = it },
                    )
                    ToggleRow(
                        icon = Icons.Filled.Speed,
                        title = stringResource(R.string.disable_animations_title),
                        checked = noAnimations,
                        onCheckedChange = { noAnimations = it },
                    )
                    ToggleRow(
                        icon = Icons.Filled.BatteryFull,
                        title = stringResource(R.string.keep_awake_title),
                        checked = keepAwake,
                        onCheckedChange = { keepAwake = it },
                    )
                }

                val shizukuReady = ShizukuDisplayPermission.isShizukuAvailable()
                LsfgCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionHeader(
                            eyebrow = "ประสิทธิภาพ",
                            title = null,
                            modifier = Modifier.weight(1f),
                        )
                        if (!shizukuReady) {
                            StatusPill(label = "ต้องใช้ Shizuku", tone = StatusTone.Warn)
                        }
                    }
                    Spacer(Modifier.height(LsfgSpacing.sm))
                    ToggleRow(
                        icon = Icons.Filled.Speed,
                        title = "โหมดประสิทธิภาพคงที่",
                        description = "ล็อกความถี่ CPU/GPU ไว้ที่สูงสุดตลอดเซสชัน",
                        checked = fixedPerfMode,
                        onCheckedChange = { fixedPerfMode = it },
                    )
                    ToggleRow(
                        icon = Icons.Filled.BatteryFull,
                        title = "รายการยกเว้น Doze",
                        description = "ป้องกันไม่ให้บริการพื้นหลัง (แชทเสียง ฯลฯ) ถูกจำกัดการทำงาน",
                        checked = dozeWhitelist,
                        onCheckedChange = { dozeWhitelist = it },
                    )
                    ToggleRow(
                        icon = Icons.Filled.Apps,
                        title = "บังคับหยุดแอปพื้นหลัง",
                        description = "ปิดแอปอื่นที่ติดตั้งไว้ทั้งหมดก่อนเริ่มเกม",
                        checked = forceStopBg,
                        onCheckedChange = { forceStopBg = it },
                    )
                    ToggleRow(
                        icon = Icons.Filled.DisplaySettings,
                        title = "ล็อก Wi-Fi ประสิทธิภาพสูง",
                        description = "ป้องกันไม่ให้วิทยุ Wi-Fi พักระหว่างแพ็กเก็ต (ลดอาการปิงกระชาก)",
                        checked = wifiLock,
                        onCheckedChange = { wifiLock = it },
                    )
                }

                LsfgCard {
                    SectionHeader(eyebrow = "ล็อกอัตรารีเฟรช")
                    Spacer(Modifier.height(LsfgSpacing.sm))
                    if (supportedHz.isEmpty()) {
                        Text(
                            "อ่านค่าอัตรารีเฟรชที่รองรับของอุปกรณ์นี้ไม่ได้",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        refreshRateOptions.forEach { hz ->
                            FilterChip(
                                selected = refreshRateHz == hz,
                                onClick = { refreshRateHz = hz },
                                label = { Text(if (hz == 0) "อัตโนมัติ" else "${hz}Hz") },
                            )
                        }
                    }
                    Spacer(Modifier.height(LsfgSpacing.sm))
                    Text(
                        stringResource(R.string.limit_background_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (!AdbDisplayController.isReady(context)) {
                    if (ShizukuDisplayPermission.isShizukuAvailable()) {
                        scope.launch {
                            val granted = ShizukuDisplayPermission.grantWriteSecureSettings(context)
                            if (!granted) Toast.makeText(context, context.getString(R.string.toast_shizuku_grant_failed), Toast.LENGTH_LONG).show()
                        }
                    } else {
                        AdbDisplayController.requestPermission()
                        val command = AdbDisplayController.grantCommand()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("ADB grant", command))
                        Toast.makeText(context, context.getString(R.string.toast_no_permission_copied_adb), Toast.LENGTH_LONG).show()
                    }
                    return@Button
                }
                val current = AdbDisplayController.readDisplay(context)
                if (current == null) {
                    LsfgLog.w(TAG_APP_CARD_SETTINGS, "Save[$packageName]: readDisplay() returned null, see LsfgAdbDisplay log above")
                    Toast.makeText(context, context.getString(R.string.toast_read_display_failed), Toast.LENGTH_SHORT).show()
                    return@Button
                }
                val captured = AppDisplayProfileStore.captureOriginalIfMissing(context, packageName, current)
                val saved = AppDisplayProfileStore.withPercent(context, packageName, percent.toInt()).copy(
                    dynamicClean = clean,
                    maxBackgroundApps = 1,
                    disableAnimations = noAnimations,
                    keepAwake = keepAwake,
                    fixedPerformanceMode = fixedPerfMode,
                    dozeWhitelist = dozeWhitelist,
                    forceStopBackground = forceStopBg,
                    lockRefreshRateHz = refreshRateHz,
                    wifiHighPerfLock = wifiLock,
                ).also { AppDisplayProfileStore.save(context, packageName, it) }
                onSaved(saved)
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Button(onClick = {
                if (!AdbDisplayController.isReady(context)) {
                    LsfgLog.w(TAG_APP_CARD_SETTINGS, "ReadReal[$packageName]: not ready, prompting for permission")
                    AdbDisplayController.requestPermission()
                    val command = AdbDisplayController.grantCommand()
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("ADB grant", command))
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_adb_copied_with_command, command),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    val current = AdbDisplayController.readDisplay(context)
                    if (current == null) {
                        LsfgLog.w(TAG_APP_CARD_SETTINGS, "ReadReal[$packageName]: readDisplay() returned null, see LsfgAdbDisplay log above")
                    }
                    if (current != null) info = current
                    val captured = current?.let { AppDisplayProfileStore.captureOriginalIfMissing(context, packageName, it) }
                    if (captured != null) {
                        profile = captured
                        percent = captured.percent.toFloat()
                    }
                }
            }) { Text(stringResource(R.string.action_read_real_display)) }
        },
    )
}
