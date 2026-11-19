package ru.kai.bb

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

private fun pickFileIntent(multiple: Boolean, accept: Array<String>): Intent {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
    intent.type = "*/*"
    val mimes = accept.map { it.trim() }.filter { it.contains("/") && !it.startsWith(".") }
    if (mimes.isNotEmpty()) intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
    if (multiple) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    return intent
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(prefs: SitePrefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val bridge = remember { WebBridge(context, prefs) }

    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("") }
    var pageUrl by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var loadedOnce by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    var logoutAsk by remember { mutableStateOf(false) }
    var desktopMode by remember { mutableStateOf(prefs.desktopMode) }
    var zoom by remember { mutableIntStateOf(prefs.textZoom) }
    var fileChooser by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var webPermission by remember { mutableStateOf<PermissionRequest?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = fileChooser
        fileChooser = null
        if (callback == null) return@rememberLauncherForActivityResult
        val data = result.data
        val clip = data?.clipData
        val uris: Array<Uri>? = when {
            result.resultCode != Activity.RESULT_OK -> null
            clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
            data?.data != null -> arrayOf(data.data!!)
            else -> null
        }
        callback.onReceiveValue(uris)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val request = webPermission
        webPermission = null
        if (request == null) return@rememberLauncherForActivityResult
        if (granted.values.all { it }) {
            request.grant(request.resources)
        } else {
            request.deny()
            scope.launch { snackbar.showSnackbar(context.getString(R.string.media_permission)) }
        }
    }

    fun startDownload(req: DownloadRequest) {
        val hint = DownloadNames.guess(req.url, req.contentDisposition, req.mimeType)
        scope.launch {
            val waiting = launch {
                snackbar.showSnackbar(
                    context.getString(R.string.downloading, hint),
                    duration = SnackbarDuration.Indefinite,
                )
            }
            val result = runCatching { FileDownloader.download(context, req) }
            waiting.cancel()
            result.fold(
                onSuccess = { saved ->
                    val answer = snackbar.showSnackbar(
                        message = context.getString(R.string.download_done, saved.name),
                        actionLabel = context.getString(R.string.open_file),
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                    if (answer == SnackbarResult.ActionPerformed &&
                        !FileDownloader.open(context, saved)
                    ) {
                        snackbar.showSnackbar(context.getString(R.string.no_app_for_file))
                    }
                },
                onFailure = { error ->
                    snackbar.showSnackbar(
                        context.getString(R.string.download_failed, error.message ?: "—"),
                    )
                },
            )
        }
    }

    SideEffect {
        bridge.onProgress = { value ->
            progress = value
            if (value >= 100) loadedOnce = true
        }
        bridge.onNav = { back, forward, currentUrl, title ->
            canBack = back
            canForward = forward
            if (currentUrl.isNotBlank()) pageUrl = currentUrl
            if (title.isNotBlank()) pageTitle = title
        }
        bridge.onPageError = { message ->
            // null = ошибки нет (так же вызывается перед каждой загрузкой), "" = ошибка без описания
            if (message == null) {
                errorMsg = null
            } else {
                errorMsg = message.ifBlank { context.getString(R.string.error_title) }
                loadedOnce = true
            }
        }
        bridge.onToast = { message -> scope.launch { snackbar.showSnackbar(message) } }
        bridge.onDownload = { request -> startDownload(request) }
        bridge.onFileChooser = { callback, multiple, accept ->
            fileChooser = callback
            runCatching { filePicker.launch(pickFileIntent(multiple, accept)) }
                .onFailure {
                    fileChooser = null
                    callback?.onReceiveValue(null)
                }
        }
        bridge.onWebPermission = { request ->
            val needed = buildList {
                if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                    add(android.Manifest.permission.CAMERA)
                }
                if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    add(android.Manifest.permission.RECORD_AUDIO)
                }
            }
            if (needed.isEmpty()) {
                request.deny()
            } else {
                webPermission = request
                permissionLauncher.launch(needed.toTypedArray())
            }
        }
    }

    LaunchedEffect(Unit) {
        if (bridge.web.url.isNullOrBlank()) {
            bridge.load(SiteRules.startUrl(prefs.lastUrl))
        }
    }

    BackHandler(enabled = canBack) {
        if (!bridge.goBack()) canBack = false
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.logo_art),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = pageTitle.ifBlank { stringResource(R.string.app_name) },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.site_host),
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { sheetOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu))
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    IconButton(onClick = { bridge.goBack() }, enabled = canBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                    IconButton(onClick = { bridge.goForward() }, enabled = canForward) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = stringResource(R.string.forward),
                        )
                    }
                    IconButton(onClick = { bridge.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.reload))
                    }
                    IconButton(onClick = { bridge.load(Site.HOME_URL) }) {
                        Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.home))
                    }
                    IconButton(onClick = { sheetOpen = true }) {
                        Icon(
                            painterResource(R.drawable.ic_tune),
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
        ) {
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            } else {
                Spacer(Modifier.height(3.dp))
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { bridge.web },
                    modifier = Modifier.fillMaxSize(),
                )

                if (!loadedOnce) {
                    SplashOverlay(progress)
                }

                val message = errorMsg
                if (message != null) {
                    ErrorOverlay(
                        reason = message,
                        onRetry = {
                            errorMsg = null
                            bridge.load(pageUrl.ifBlank { Site.ENTRY_URL })
                        },
                    )
                }
            }
        }
    }

    if (sheetOpen) {
        SettingsSheet(
            desktopMode = desktopMode,
            zoom = zoom,
            onDesktopMode = { desktop ->
                if (desktop != desktopMode) {
                    desktopMode = desktop
                    bridge.applyViewMode(desktop)
                }
            },
            onZoom = { zoom = it },
            onZoomFinished = { bridge.applyTextZoom(zoom) },
            onReload = { sheetOpen = false; bridge.reload() },
            onBrowser = { sheetOpen = false; bridge.openInBrowser() },
            onDownload = {
                sheetOpen = false
                val current = bridge.web.url
                if (current != null) {
                    startDownload(
                        DownloadRequest(
                            url = current,
                            userAgent = bridge.web.settings.userAgentString,
                            mimeType = "application/octet-stream",
                            referer = current,
                        ),
                    )
                }
            },
            onLogout = { sheetOpen = false; logoutAsk = true },
            onAbout = { sheetOpen = false; aboutOpen = true },
            onDismiss = { sheetOpen = false },
        )
    }

    if (logoutAsk) {
        AlertDialog(
            onDismissRequest = { logoutAsk = false },
            title = { Text(stringResource(R.string.logout_title)) },
            text = { Text(stringResource(R.string.logout_text)) },
            confirmButton = {
                TextButton(onClick = {
                    logoutAsk = false
                    bridge.logout()
                }) { Text(stringResource(R.string.logout)) }
            },
            dismissButton = {
                TextButton(onClick = { logoutAsk = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (aboutOpen) {
        AboutDialog(onDismiss = { aboutOpen = false })
    }
}

@Composable
private fun SplashOverlay(progress: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.logo_art),
                contentDescription = null,
                modifier = Modifier.size(132.dp),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "КНИТУ-КАИ · учебный портал",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(26.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (progress > 0) stringResource(R.string.loading) else stringResource(R.string.loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorOverlay(reason: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.error_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.error_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    desktopMode: Boolean,
    zoom: Int,
    onDesktopMode: (Boolean) -> Unit,
    onZoom: (Int) -> Unit,
    onZoomFinished: () -> Unit,
    onReload: () -> Unit,
    onBrowser: () -> Unit,
    onDownload: () -> Unit,
    onLogout: () -> Unit,
    onAbout: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(18.dp))

            Text(
                text = stringResource(R.string.site_view),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = !desktopMode,
                    onClick = { onDesktopMode(false) },
                    label = { Text(stringResource(R.string.view_mobile)) },
                )
                FilterChip(
                    selected = desktopMode,
                    onClick = { onDesktopMode(true) },
                    label = { Text(stringResource(R.string.view_desktop)) },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.view_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.text_size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$zoom %",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = zoom.toFloat(),
                onValueChange = { onZoom(it.toInt()) },
                onValueChangeFinished = onZoomFinished,
                valueRange = SiteRules.MIN_ZOOM.toFloat()..SiteRules.MAX_ZOOM.toFloat(),
                steps = 7,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SheetAction(
                icon = { Icon(Icons.Filled.Refresh, null) },
                label = stringResource(R.string.reload),
                onClick = onReload,
            )
            SheetAction(
                icon = { Icon(painterResource(R.drawable.ic_open_in_browser), null) },
                label = stringResource(R.string.open_in_browser),
                onClick = onBrowser,
            )
            SheetAction(
                icon = { Icon(painterResource(R.drawable.ic_download), null) },
                label = stringResource(R.string.download_page),
                onClick = onDownload,
            )
            SheetAction(
                icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) },
                label = stringResource(R.string.logout),
                onClick = onLogout,
            )
            SheetAction(
                icon = { Icon(Icons.Filled.Info, null) },
                label = stringResource(R.string.about),
                onClick = onAbout,
            )
        }
    }
}

@Composable
private fun SheetAction(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
        ) { icon() }
        Spacer(Modifier.width(16.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Image(
                painter = painterResource(R.drawable.logo_art),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
            )
        },
        title = { Text(stringResource(R.string.app_name), textAlign = TextAlign.Center) },
        text = {
            Column {
                Text(stringResource(R.string.about_text))
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "${stringResource(R.string.version)} ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.about_legal),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
    )
}
