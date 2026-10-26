package com.pinchedmon.polka

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Two-way pill switch used for tabs and fit/crop. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(p.card)
            .border(1.dp, p.line, RoundedCornerShape(22.dp))
            .padding(4.dp)
    ) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * selected, label = "seg")
        Box(
            Modifier.offset(x = x).width(w).fillMaxSize()
                .clip(RoundedCornerShape(18.dp)).background(p.ink)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val c by animateColorAsState(if (i == selected) p.paper else p.muted, label = "segc")
                Box(
                    Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(18.dp)).clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = c, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

/** What the reader will do when it falls asleep, and the switch to make it use our pictures. */
@Composable
fun SleepStatus(vm: MainViewModel) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card)
            .border(1.dp, p.line, RoundedCornerShape(18.dp)).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (vm.pinned != null) Icons.Rounded.PushPin else Icons.Rounded.Shuffle, null,
                tint = p.ember, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (vm.pinned != null) "Всегда: ${vm.pinned!!.removeSuffix(".bmp")}"
                else if (vm.hasRootSleep) "Всегда: /sleep.bmp"
                else "Каждый раз случайная из /sleep",
                color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (vm.hasRootSleep) {
                Text(
                    "вернуть случайные", color = p.ember, fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = vm::unpin).padding(4.dp),
                )
            }
        }
        if (vm.sleepMode != null && !vm.customOn) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bedtime, null, tint = p.muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Читалка сейчас не показывает свои картинки",
                    color = p.muted, fontSize = 13.sp, modifier = Modifier.weight(1f),
                )
                Text(
                    "включить", color = p.ember, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(onClick = vm::enableCustom).padding(4.dp),
                )
            }
        }
    }
}

/** Three-per-row gallery of what's in /sleep, each in a tiny e-ink frame. */
@Composable
fun SleepRow(row: List<RemoteFile>, vm: MainViewModel, onTap: (RemoteFile) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        row.forEach { f ->
            Box(Modifier.weight(1f)) {
                MiniReader(vm.thumbs[f.name], pinned = f.name == vm.pinned, onClick = { onTap(f) })
            }
        }
        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
fun MiniReader(image: ImageBitmap?, pinned: Boolean, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(0.66f)
            .clip(RoundedCornerShape(12.dp))
            .background(p.ink)
            .then(if (pinned) Modifier.border(2.dp, p.ember, RoundedCornerShape(12.dp)) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 14.dp)
    ) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(p.screen),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(image, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            } else {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = p.muted)
            }
        }
        if (pinned) {
            Icon(
                Icons.Rounded.PushPin, null, tint = p.ember,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color.White).padding(2.dp),
            )
        }
    }
}

/** Preview + fit/crop before sending. Shows exactly the grayscale 480×800 the reader will get. */
@Composable
fun WallpaperEditor(uris: List<Uri>, onDismiss: () -> Unit, onSend: (crop: Boolean, pin: Boolean) -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var crop by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(crop) {
        runCatching {
            withContext(Dispatchers.Default) {
                SleepImage.render(SleepImage.load(ctx, uris.first()), crop).asImageBitmap()
            }
        }.onSuccess { preview = it }.onFailure { failed = true }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(28.dp)).background(p.card).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (uris.size == 1) "Новая заставка" else "Заставки · ${uris.size}",
                style = MaterialTheme.typography.titleLarge, color = p.ink,
            )
            Spacer(Modifier.height(14.dp))
            Box(Modifier.width(170.dp)) {
                MiniReader(preview, pinned = false)
                if (failed) Text("не открылась", color = p.ember, modifier = Modifier.align(Alignment.Center))
            }
            if (uris.size > 1) {
                Text("и ещё ${uris.size - 1}", color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(14.dp))
            Segmented(listOf("Заполнить", "Вписать"), if (crop) 0 else 1, { crop = it == 0 })
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(p.ember)
                    .clickable { onSend(crop, true) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (uris.size == 1) "Поставить на читалку" else "Закинуть и поставить первую",
                    color = Color.White, fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "просто в коллекцию (случайная при каждом сне)",
                color = p.muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp).clickable { onSend(crop, false) }.padding(6.dp),
            )
        }
    }
}

/** Tapped wallpaper: big look, make it permanent or throw it away. */
@Composable
fun WallpaperSheet(f: RemoteFile, vm: MainViewModel, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val isPinned = f.name == vm.pinned
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(28.dp)).background(p.card).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.width(190.dp)) { MiniReader(vm.thumbs[f.name], pinned = isPinned) }
            Text(
                f.name.removeSuffix(".bmp"), color = p.ink, fontFamily = Serif, fontSize = 17.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
                    .background(if (isPinned) p.line else p.ember)
                    .clickable { if (isPinned) vm.unpin() else vm.pin(f.name); onDismiss() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (isPinned) "Открепить — снова случайные" else "Показывать всегда эту",
                    color = if (isPinned) p.ink else Color.White, fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "удалить с читалки", color = p.ember, fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp).clickable { vm.deleteWallpaper(f.name); onDismiss() }.padding(6.dp),
            )
        }
    }
}
