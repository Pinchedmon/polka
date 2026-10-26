package com.pinchedmon.polka

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.sin

@Composable
fun PolkaScreen(vm: MainViewModel) {
    val p = LocalPalette.current
    val haptic = LocalHapticFeedback.current
    val snack = remember { SnackbarHostState() }
    val pops = remember { mutableStateListOf<Long>() }
    var popSeq by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()

    var askHost by remember { mutableStateOf(false) }
    var askFolder by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<RemoteFile?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var opened by remember { mutableStateOf<RemoteFile?>(null) }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) editing = uris
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        vm.add(uris)
    }

    LaunchedEffect(Unit) {
        vm.landed.collect {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val id = popSeq++
            pops += id
            scope.launch { kotlinx.coroutines.delay(1400); pops.remove(id) }
        }
    }
    LaunchedEffect(Unit) { vm.toast.collect { snack.showSnackbar(it) } }
    LaunchedEffect(vm.link) {
        if (vm.link is Link.Online) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    BackHandler(enabled = vm.browsePath != vm.folder && vm.browsePath != "/") { vm.up() }

    val sending = vm.queue.filter { it.state == UploadState.Sending || it.state == UploadState.Waiting }
    val overall = if (vm.queue.isEmpty()) 0f else {
        val active = vm.queue.filter { it.state != UploadState.Failed }
        if (active.isEmpty()) 0f else active.sumOf { it.progress.toDouble() }.toFloat() / active.size
    }

    Scaffold(
        containerColor = p.paper,
        snackbarHost = { SnackbarHost(snack) },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header(vm, onAskHost = { askHost = true }) }
            item { Segmented(listOf("Книги", "Заставки"), tab, { tab = it }) }

            item {
                Hero(
                    link = vm.link,
                    busy = sending.isNotEmpty(),
                    progress = overall,
                    pops = pops,
                    screenImage = if (tab == 1) vm.pinned?.let { vm.thumbs[it] } else null,
                    onTap = { vm.connect() },
                    onLongTap = { askHost = true },
                )
            }

            item {
                if (tab == 0) DropButton(
                    text = "Закинуть книги",
                    icon = Icons.Rounded.AutoStories,
                    hint = if (vm.link is Link.Online) "летят в ${vm.folder} · или «Поделиться → На X4» из любого приложения"
                    else "можно выбрать заранее — улетят, как только X4 появится",
                    onClick = { pick.launch(arrayOf("*/*")) },
                ) else DropButton(
                    text = "Добавить заставки",
                    icon = Icons.Rounded.Wallpaper,
                    hint = "любая картинка станет 480×800 BMP в ${MainViewModel.SLEEP_DIR}",
                    onClick = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
            }

            if (vm.queue.isNotEmpty()) {
                item {
                    SectionTitle(
                        "Летят",
                        action = if (vm.queue.any { it.state == UploadState.Done || it.state == UploadState.AlreadyThere })
                            "убрать готовые" to vm::clearFinished else null,
                    )
                }
                items(vm.queue, key = { "q${it.id}" }) { UploadRow(it, onRetry = { vm.retry(it) }) }
            }

            if (vm.link is Link.Online && tab == 1) {
                item { SleepStatus(vm) }
                item { SectionTitle("В ${MainViewModel.SLEEP_DIR}") }
                if (vm.sleepFiles.isEmpty()) item { EmptyShelf() }
                items(vm.sleepFiles.chunked(3), key = { r -> "s" + r.joinToString { it.name } }) { r ->
                    SleepRow(r, vm, onTap = { opened = it })
                }
            }

            if (vm.link is Link.Online && tab == 0) {
                item {
                    LibraryHeader(
                        path = vm.browsePath,
                        isTarget = vm.browsePath == vm.folder,
                        loading = vm.filesLoading,
                        onUp = vm::up,
                        onPin = vm::dropHere,
                        onMkdir = { askFolder = true },
                        onRefresh = vm::refresh,
                    )
                }
                if (vm.files.isEmpty() && !vm.filesLoading) {
                    item { EmptyShelf() }
                }
                items(vm.files, key = { "f${vm.browsePath}/${it.name}" }) { f ->
                    FileRow(f, onOpen = { vm.open(f) }, onLong = { toDelete = f })
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    if (editing.isNotEmpty()) WallpaperEditor(
        uris = editing,
        onDismiss = { editing = emptyList() },
        onSend = { crop, pin -> vm.addWallpapers(editing, crop, pin); editing = emptyList() },
    )
    opened?.let { f -> WallpaperSheet(f, vm, onDismiss = { opened = null }) }

    if (askHost) TextDialog(
        title = "Адрес читалки",
        hint = "IP с экрана X4, например 192.168.0.194",
        initial = vm.host ?: "",
        keyboard = KeyboardType.Uri,
        confirm = "Подключиться",
        onDismiss = { askHost = false },
        onOk = { askHost = false; vm.connect(manualHost = it) },
    )
    if (askFolder) TextDialog(
        title = "Новая папка",
        hint = "в ${vm.browsePath}",
        initial = "",
        keyboard = KeyboardType.Text,
        confirm = "Создать",
        onDismiss = { askFolder = false },
        onOk = { askFolder = false; vm.mkdir(it) },
    )
    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = p.card,
            title = { Text("Убрать с читалки?", style = MaterialTheme.typography.titleLarge, color = p.ink) },
            text = {
                Text(
                    if (f.isDirectory) "Папка «${f.name}» удалится, только если она пустая." else "«${f.name}» удалится с карты X4.",
                    color = p.muted,
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.delete(f); toDelete = null }) { Text("Удалить", color = p.ember) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Оставить", color = p.ink) } },
        )
    }
}

@Composable
private fun Header(vm: MainViewModel, onAskHost: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Полка", style = MaterialTheme.typography.displaySmall, color = p.ink)
            Text(".", style = MaterialTheme.typography.displaySmall, color = p.ember)
        }
        AnimatedContent(
            targetState = vm.link,
            transitionSpec = { (fadeIn() + slideInVertically { it / 2 }) togetherWith fadeOut() },
            label = "status",
            contentKey = { it::class },
        ) { link ->
            when (link) {
                Link.Searching -> Text("ищу твою X4 в сети…", color = p.muted, fontSize = 15.sp)
                Link.NotFound -> Column {
                    Text("X4 не видно", color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "На читалке: File Transfer → та же Wi-Fi, что у телефона. Тапни по читалке, чтобы поискать снова.",
                        color = p.muted, fontSize = 13.sp, lineHeight = 18.sp,
                    )
                    Text(
                        "ввести адрес вручную",
                        color = p.ember, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 6.dp).clickable(onClick = onAskHost),
                    )
                }
                is Link.Online -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(p.moss))
                    Spacer(Modifier.width(8.dp))
                    val s = link.status
                    Text(
                        "${s.device} на связи · ${s.host}" + if (s.mode == "AP") " · хотспот" else "",
                        color = p.ink, fontSize = 15.sp,
                    )
                    if (s.mode != "AP") {
                        Spacer(Modifier.width(8.dp))
                        Bars(s.rssi)
                    }
                }
            }
        }
    }
}

@Composable
private fun Bars(rssi: Int) {
    val p = LocalPalette.current
    val n = when {
        rssi >= -55 -> 4; rssi >= -65 -> 3; rssi >= -75 -> 2; rssi >= -85 -> 1; else -> 0
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(4) { i ->
            Box(
                Modifier.width(3.dp).height((4 + i * 3).dp).clip(RoundedCornerShape(1.dp))
                    .background(if (i < n) p.ink else p.line)
            )
        }
    }
}

/** The reader itself, drawn: sonar while searching, asleep when gone, a page that fills while books fly in. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Hero(
    link: Link,
    busy: Boolean,
    progress: Float,
    pops: List<Long>,
    screenImage: ImageBitmap?,
    onTap: () -> Unit,
    onLongTap: () -> Unit,
) {
    val p = LocalPalette.current
    val inf = rememberInfiniteTransition(label = "hero")
    val sonar by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "sonar")
    val breathe by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe"
    )
    val blink by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "blink")
    val fill by animateFloatAsState(if (busy) progress else if (link is Link.Online) 1f else 0f, spring(), label = "fill")
    val online = link is Link.Online
    val tilt by animateFloatAsState(if (link == Link.NotFound) -4f else 0f, spring(dampingRatio = 0.4f), label = "tilt")
    val body by animateColorAsState(if (online) p.ink else p.ink.copy(alpha = 0.85f), label = "body")

    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val squash by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.5f), label = "squash")

    Box(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(p.card)
            .border(1.dp, p.line, RoundedCornerShape(28.dp))
            .combinedClickable(interactionSource = press, indication = null, onClick = onTap, onLongClick = onLongTap),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2
            val cy = size.height / 2
            val w = 150.dp.toPx()
            val h = 220.dp.toPx()

            if (link == Link.Searching) {
                for (k in 0 until 3) {
                    val t = (sonar + k / 3f) % 1f
                    drawCircle(
                        color = p.ember.copy(alpha = (1f - t) * 0.35f),
                        radius = h * 0.45f + t * h * 0.55f,
                        center = Offset(cx, cy),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }

            val float = if (online && !busy) sin(breathe * Math.PI).toFloat() * 4.dp.toPx() else 0f
            val top = cy - h / 2 - float
            val left = cx - w / 2
            val r = 18.dp.toPx()

            // shadow
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.10f),
                topLeft = Offset(left + 6.dp.toPx(), top + 10.dp.toPx() + float),
                size = Size(w, h), cornerRadius = CornerRadius(r),
            )
            drawContext.transform.rotate(tilt, Offset(cx, cy))
            // body
            drawRoundRect(color = body, topLeft = Offset(left, top), size = Size(w, h), cornerRadius = CornerRadius(r))
            // e-ink screen
            val m = 12.dp.toPx()
            val sTop = top + m
            val sw = w - 2 * m
            val sh = h - 2 * m - 18.dp.toPx()
            drawRoundRect(
                color = p.screen, topLeft = Offset(left + m, sTop), size = Size(sw, sh),
                cornerRadius = CornerRadius(6.dp.toPx()),
            )
            // the X4's single front button
            drawRoundRect(
                color = p.paper.copy(alpha = 0.25f),
                topLeft = Offset(cx - 14.dp.toPx(), sTop + sh + 7.dp.toPx()),
                size = Size(28.dp.toPx(), 4.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()),
            )

            val ink = Color(0xFF2A2723)
            val lineH = 10.dp.toPx()
            val pad = 14.dp.toPx()
            val lines = ((sh - 2 * pad) / lineH).toInt()
            when (link) {
                Link.Searching -> {
                    // blinking cursor
                    drawRect(
                        ink.copy(alpha = blink),
                        topLeft = Offset(left + m + pad, sTop + pad),
                        size = Size(3.dp.toPx(), 14.dp.toPx()),
                    )
                }
                Link.NotFound -> {
                    // closed eyes: the reader is asleep
                    val ey = sTop + sh * 0.42f
                    for (dx in listOf(-22f, 22f)) {
                        drawArc(
                            ink, 20f, 140f, false,
                            topLeft = Offset(cx + dx.dp.toPx() - 10.dp.toPx(), ey - 6.dp.toPx()),
                            size = Size(20.dp.toPx(), 12.dp.toPx()),
                            style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
                is Link.Online -> if (screenImage != null && !busy) {
                    drawImage(
                        screenImage,
                        dstOffset = IntOffset((left + m).toInt(), sTop.toInt()),
                        dstSize = IntSize(sw.toInt(), sh.toInt()),
                    )
                } else {
                    // page of text, revealed by the upload progress
                    val shown = (lines * fill).toInt()
                    for (i in 0 until lines) {
                        val frac = when {
                            i % 5 == 4 -> 0.45f
                            else -> 0.72f + ((i * 37) % 23) / 100f
                        }
                        val alpha = if (i < shown) 0.85f else 0.10f
                        drawRoundRect(
                            ink.copy(alpha = alpha),
                            topLeft = Offset(left + m + pad, sTop + pad + i * lineH),
                            size = Size((sw - 2 * pad) * frac, 3.dp.toPx()),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )
                    }
                    if (busy) {
                        drawRoundRect(
                            p.ember,
                            topLeft = Offset(left + m, sTop + sh - 4.dp.toPx()),
                            size = Size(sw * progress.coerceIn(0f, 1f), 4.dp.toPx()),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )
                    }
                }
            }
        }

        // "+1" pops each time a book lands
        pops.forEach { id -> key(id) { Pop() } }

        if (link == Link.NotFound) {
            Text(
                "z z z",
                color = p.muted,
                fontFamily = Serif,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 28.dp, end = 60.dp)
                    .graphicsLayer { translationY = -breathe * 10f; alpha = 0.4f + breathe * 0.6f },
            )
        }
    }
}

@Composable
private fun key(id: Long, content: @Composable () -> Unit) = androidx.compose.runtime.key(id) { content() }

@Composable
private fun Pop() {
    val p = LocalPalette.current
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(1300, easing = FastOutSlowInEasing)) }
    Text(
        "+1",
        color = p.ember,
        fontFamily = Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        modifier = Modifier.graphicsLayer {
            translationY = -a.value * 140.dp.toPx() + 20.dp.toPx()
            translationX = 70.dp.toPx()
            alpha = 1f - a.value
            scaleX = 0.7f + a.value * 0.6f
            scaleY = scaleX
        },
    )
}

@Composable
private fun DropButton(text: String, icon: ImageVector, hint: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.45f), label = "btn")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .scale(s)
                .height(64.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(p.ember)
                .clickable(interactionSource = press, indication = null, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, tint = Color.White)
            Spacer(Modifier.width(10.dp))
            Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            hint,
            color = p.muted, fontSize = 12.sp, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SectionTitle(title: String, action: Pair<String, () -> Unit>? = null) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = p.ink, modifier = Modifier.weight(1f))
        action?.let { (label, fn) ->
            Text(label, color = p.muted, fontSize = 13.sp, modifier = Modifier.clickable(onClick = fn).padding(6.dp))
        }
    }
}

@Composable
private fun UploadRow(item: UploadItem, onRetry: () -> Unit) {
    val p = LocalPalette.current
    val prog by animateFloatAsState(item.progress, label = "prog")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(p.card)
            .border(1.dp, p.line, RoundedCornerShape(18.dp))
            .clickable(enabled = item.state == UploadState.Failed, onClick = onRetry)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.name, color = p.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            AnimatedContent(item.state, label = "st") { st ->
                when (st) {
                    UploadState.Waiting -> Text("ждёт", color = p.muted, fontSize = 12.sp)
                    UploadState.Sending -> Text("${(item.progress * 100).toInt()}%", color = p.ember, fontSize = 12.sp)
                    UploadState.Done -> Icon(Icons.Rounded.Check, null, tint = p.moss, modifier = Modifier.size(20.dp))
                    UploadState.AlreadyThere -> Text("уже на полке", color = p.muted, fontSize = 12.sp)
                    UploadState.Failed -> Icon(Icons.Rounded.ErrorOutline, null, tint = p.ember, modifier = Modifier.size(20.dp))
                }
            }
        }
        AnimatedVisibility(item.state == UploadState.Sending || item.state == UploadState.Waiting) {
            LinearProgressIndicator(
                progress = { prog },
                color = p.ember,
                trackColor = p.line,
                strokeCap = StrokeCap.Round,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(5.dp),
            )
        }
        if (item.state == UploadState.Failed) {
            Text(
                "${item.error ?: "ошибка"} · тапни, чтобы повторить",
                color = p.ember, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
        } else if (item.size > 0) {
            Text(human(item.size), color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun LibraryHeader(
    path: String,
    isTarget: Boolean,
    loading: Boolean,
    onUp: () -> Unit,
    onPin: () -> Unit,
    onMkdir: () -> Unit,
    onRefresh: () -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("На читалке", style = MaterialTheme.typography.titleLarge, color = p.ink, modifier = Modifier.weight(1f))
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = p.muted)
            else SmallIcon(Icons.Rounded.Refresh, onRefresh)
            SmallIcon(Icons.Rounded.CreateNewFolder, onMkdir)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (path != "/") {
                SmallIcon(Icons.AutoMirrored.Rounded.ArrowBack, onUp)
            }
            Text(path, color = p.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (isTarget) {
                Chip("сюда летят книги", Icons.Rounded.PushPin, p.ember, null)
            } else {
                Chip("кидать сюда", Icons.Rounded.PushPin, p.muted, onPin)
            }
        }
    }
}

@Composable
private fun Chip(text: String, icon: ImageVector, tint: Color, onClick: (() -> Unit)?) {
    val p = LocalPalette.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, p.line, RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = tint, fontSize = 12.sp)
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, null, tint = LocalPalette.current.ink, modifier = Modifier.size(20.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(f: RemoteFile, onOpen: () -> Unit, onLong: () -> Unit) {
    val p = LocalPalette.current
    val icon = when {
        f.isDirectory -> Icons.Rounded.Folder
        f.isEpub -> Icons.Rounded.AutoStories
        else -> Icons.Rounded.Description
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = { if (f.isDirectory) onOpen() }, onLongClick = onLong)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                .background(if (f.isDirectory) p.ember.copy(alpha = 0.12f) else p.card)
                .border(1.dp, p.line, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (f.isDirectory) p.ember else p.ink, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                f.name.substringBeforeLast('.').takeIf { !f.isDirectory && it.isNotEmpty() } ?: f.name,
                color = p.ink, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (f.isDirectory) "папка" else "${f.name.substringAfterLast('.', "").uppercase()} · ${human(f.size)}",
                color = p.muted, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun EmptyShelf() {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Inventory2, null, tint = p.line, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text("тут пока пусто", color = p.muted, fontFamily = Serif, fontSize = 16.sp)
    }
}

@Composable
private fun TextDialog(
    title: String,
    hint: String,
    initial: String,
    keyboard: KeyboardType,
    confirm: String,
    onDismiss: () -> Unit,
    onOk: (String) -> Unit,
) {
    val p = LocalPalette.current
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.card,
        icon = { Icon(Icons.Rounded.Wifi, null, tint = p.ember) },
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = p.ink) },
        text = {
            OutlinedTextField(
                value = v, onValueChange = { v = it }, singleLine = true,
                placeholder = { Text(hint, color = p.muted, fontSize = 13.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                shape = RoundedCornerShape(14.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (v.isNotBlank()) onOk(v) }) { Text(confirm, color = p.ember) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = p.ink) } },
    )
}

private fun human(b: Long): String = when {
    b < 0 -> ""
    b < 1024 -> "$b Б"
    b < 1024 * 1024 -> "${b / 1024} КБ"
    else -> String.format("%.1f МБ", b / 1024.0 / 1024.0)
}
