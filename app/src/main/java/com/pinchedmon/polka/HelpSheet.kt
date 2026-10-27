package com.pinchedmon.polka

import androidx.annotation.StringRes
import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val GITHUB_URL = "https://github.com/Pinchedmon/polka"
const val DONATE_URL = "https://new.donatepay.ru/donate/1536180"

/** Language tag → its own name, so everyone finds theirs regardless of the current UI language. */
private val LANGUAGES = listOf("en" to "English", "ru" to "Русский", "uk" to "Українська", "de" to "Deutsch", "es" to "Español")

/** "How it works": connect, books, shelf, sleep screens, firmware — plus language, source and support. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSheet(onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val uri = LocalUriHandler.current
    val ctx = LocalContext.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.paper,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp).size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(p.line))
        },
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(top = 12.dp, bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.help_title), style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp), color = p.ink)
                Text(".", style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp), color = p.ember)
            }

            Section(Icons.Rounded.Wifi, R.string.help_connect_t, R.string.help_connect)
            Section(Icons.Rounded.AutoStories, R.string.help_books_t, R.string.help_books)
            Section(Icons.Rounded.Inventory2, R.string.help_shelf_t, R.string.help_shelf)
            Section(Icons.Rounded.Wallpaper, R.string.help_sleep_t, R.string.help_sleep)
            Section(Icons.Rounded.Memory, R.string.help_fw_t, R.string.help_fw)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.help_language), color = p.muted, fontSize = 13.sp)
                LanguagePicker()
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LinkButton(
                    Icons.Rounded.Code, stringResource(R.string.help_github), p.card, p.ink,
                    Modifier.weight(1f),
                ) { uri.openUri(GITHUB_URL) }
                LinkButton(
                    Icons.Rounded.Favorite, stringResource(R.string.help_support), p.ember.copy(alpha = 0.14f), p.ember,
                    Modifier.weight(1f),
                ) { uri.openUri(DONATE_URL) }
            }

            Box(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(p.ember)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.help_ok), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                stringResource(R.string.help_version, version), color = p.muted, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun Section(icon: ImageVector, @StringRes title: Int, @StringRes body: Int) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(p.ember.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = p.ember, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = p.ink)
            Spacer(Modifier.height(2.dp))
            Text(stringResource(body), color = p.muted, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

/** In-app language switch; see [Lang] for how the choice is applied and remembered. */
@Composable
private fun LanguagePicker() {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val current = remember { Lang.current(ctx) }
    val options = listOf("" to stringResource(R.string.help_lang_system)) + LANGUAGES
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (tag, label) ->
            val on = tag == current
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) p.ink else p.card)
                    .border(1.dp, if (on) p.ink else p.line, RoundedCornerShape(50))
                    .clickable { (ctx as? Activity)?.let { Lang.set(it, tag) } }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) { Text(label, color = if (on) p.paper else p.ink, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun LinkButton(icon: ImageVector, text: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        modifier.height(50.dp).clip(RoundedCornerShape(25.dp)).background(bg)
            .border(1.dp, p.line, RoundedCornerShape(25.dp)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
