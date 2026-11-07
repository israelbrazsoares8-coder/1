package br.ibs.brazlauncher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

const val COLUMNS = 10

data class Tile(val id: String, val pkg: String, val span: Int)
data class Page(val name: String, val tiles: List<Tile>)
data class AppInfo(val pkg: String, val label: String)

sealed class Dlg {
    object Menu : Dlg()
    object Add : Dlg()
    object Rename : Dlg()
    object RemovePage : Dlg()
    data class TileOpts(val id: String) : Dlg()
}

// ---------- Armazenamento ----------

fun defaultPages(): List<Page> = (1..4).map { Page(it.toString(), emptyList()) }

fun iconFile(ctx: Context, id: String) = File(ctx.filesDir, "icon_$id.png")

fun loadPages(ctx: Context): List<Page> {
    val raw = ctx.getSharedPreferences("braz", Context.MODE_PRIVATE).getString("pages", null)
        ?: return defaultPages()
    return try {
        val arr = JSONArray(raw)
        val list = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val ta = p.getJSONArray("tiles")
            Page(
                p.getString("name"),
                (0 until ta.length()).map { j ->
                    val t = ta.getJSONObject(j)
                    Tile(t.getString("id"), t.getString("pkg"), t.getInt("span"))
                }
            )
        }
        if (list.isEmpty()) defaultPages() else list
    } catch (e: Exception) {
        defaultPages()
    }
}

fun savePages(ctx: Context, pages: List<Page>) {
    val arr = JSONArray()
    pages.forEach { pg ->
        val ta = JSONArray()
        pg.tiles.forEach { t ->
            ta.put(JSONObject().put("id", t.id).put("pkg", t.pkg).put("span", t.span))
        }
        arr.put(JSONObject().put("name", pg.name).put("tiles", ta))
    }
    ctx.getSharedPreferences("braz", Context.MODE_PRIVATE).edit().putString("pages", arr.toString()).apply()
}

// ---------- Ícones ----------

private val appIconCache = HashMap<String, ImageBitmap>()

fun appIcon(ctx: Context, pkg: String): ImageBitmap? {
    appIconCache[pkg]?.let { return it }
    return try {
        val b = ctx.packageManager.getApplicationIcon(pkg).toBitmap(256, 256).asImageBitmap()
        appIconCache[pkg] = b
        b
    } catch (e: Exception) {
        null
    }
}

fun loadTileIcon(ctx: Context, tile: Tile): ImageBitmap? {
    val f = iconFile(ctx, tile.id)
    if (f.exists()) {
        BitmapFactory.decodeFile(f.path)?.let { return it.asImageBitmap() }
    }
    return appIcon(ctx, tile.pkg)
}

fun saveCustomIcon(ctx: Context, uri: Uri, file: File): Boolean {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return false
        val side = minOf(bmp.width, bmp.height)
        val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val out = Bitmap.createScaledBitmap(square, 512, 512, true)
        file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    } catch (e: Exception) {
        false
    }
}

fun loadApps(ctx: Context): List<AppInfo> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

fun appLabel(ctx: Context, pkg: String): String = try {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (e: Exception) {
    pkg
}

// ---------- Activity ----------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LauncherScreen()
            }
        }
    }
}

// ---------- Tela principal ----------

@Composable
fun LauncherScreen() {
    val ctx = LocalContext.current
    var pages by remember { mutableStateOf(loadPages(ctx)) }
    var current by remember { mutableStateOf(0) }
    var iconVersion by remember { mutableStateOf(0) }
    var dialog by remember { mutableStateOf<Dlg?>(null) }
    var pendingIconId by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current

    fun commit(p: List<Page>) {
        pages = p
        savePages(ctx, p)
    }

    fun editPage(idx: Int, f: (Page) -> Page) {
        commit(pages.mapIndexed { i, pg -> if (i == idx) f(pg) else pg })
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = pendingIconId
        if (uri != null && id != null) {
            if (saveCustomIcon(ctx, uri, iconFile(ctx, id))) {
                iconVersion++
            } else {
                Toast.makeText(ctx, "Não foi possível usar essa foto", Toast.LENGTH_SHORT).show()
            }
        }
        pendingIconId = null
    }

    BackHandler(enabled = true) { }

    val cur = current.coerceIn(0, pages.size - 1)
    val page = pages[cur]

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            key(cur) {
                if (page.tiles.isEmpty()) {
                    Text(
                        "Página vazia.\nToque em ⋮ e escolha \"Adicionar apps nesta página\".",
                        color = Color.Gray,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(COLUMNS),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(0.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(
                        page.tiles,
                        key = { _, t -> t.id },
                        span = { _, t -> GridItemSpan(t.span.coerceIn(1, COLUMNS)) }
                    ) { _, tile ->
                        TileView(
                            tile = tile,
                            iconVersion = iconVersion,
                            onClick = {
                                val i = ctx.packageManager.getLaunchIntentForPackage(tile.pkg)
                                if (i != null) {
                                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    ctx.startActivity(i)
                                } else {
                                    Toast.makeText(ctx, "App não encontrado", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onLong = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                dialog = Dlg.TileOpts(tile.id)
                            }
                        )
                    }
                }
            }
        }

        // Barra inferior com botões de página
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(Color(0xFF0B0B0B)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            ) {
                pages.forEachIndexed { i, pg ->
                    val sel = i == cur
                    Box(
                        Modifier
                            .width(60.dp)
                            .height(52.dp)
                            .padding(5.dp)
                            .background(
                                if (sel) Color.White else Color(0xFF2A2A2A),
                                RoundedCornerShape(10.dp)
                            )
                            .combinedClickable(
                                onClick = { current = i },
                                onLongClick = {
                                    current = i
                                    dialog = Dlg.Rename
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            pg.name,
                            color = if (sel) Color.Black else Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1
                        )
                    }
                }
            }
            Box(
                Modifier
                    .width(52.dp)
                    .height(52.dp)
                    .clickable { dialog = Dlg.Menu },
                contentAlignment = Alignment.Center
            ) {
                Text("⋮", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    // ---------- Diálogos ----------
    when (val d = dialog) {
        null -> {}

        Dlg.Menu -> AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Fechar") } },
            title = { Text("Braz Launcher") },
            text = {
                Column {
                    MenuItem("Adicionar apps nesta página") { dialog = Dlg.Add }
                    MenuItem("Renomear esta página") { dialog = Dlg.Rename }
                    MenuItem("Nova página (novo botão)") {
                        commit(pages + Page((pages.size + 1).toString(), emptyList()))
                        current = pages.size - 1
                        dialog = null
                    }
                    if (pages.size > 1) {
                        MenuItem("Remover esta página") { dialog = Dlg.RemovePage }
                    }
                    MenuItem("Definir como launcher padrão") {
                        dialog = null
                        try {
                            ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Abra Configurações > Apps > Apps padrão", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )

        Dlg.Add -> AddAppsDialog(
            added = page.tiles.map { it.pkg }.toSet(),
            onAdd = { pkg, span ->
                editPage(cur) { pg ->
                    pg.copy(tiles = pg.tiles + Tile(UUID.randomUUID().toString(), pkg, span))
                }
            },
            onDismiss = { dialog = null }
        )

        Dlg.Rename -> RenameDialog(
            initial = page.name,
            onSave = { n ->
                editPage(cur) { it.copy(name = n.ifBlank { (cur + 1).toString() }) }
                dialog = null
            },
            onDismiss = { dialog = null }
        )

        Dlg.RemovePage -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Remover página ${page.name}?") },
            text = { Text("Os apps dessa página saem do launcher (os apps continuam instalados no celular).") },
            confirmButton = {
                TextButton(onClick = {
                    page.tiles.forEach { iconFile(ctx, it.id).delete() }
                    commit(pages.filterIndexed { i, _ -> i != cur })
                    current = (cur - 1).coerceAtLeast(0)
                    dialog = null
                }) { Text("Remover") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancelar") } }
        )

        is Dlg.TileOpts -> {
            val idx = page.tiles.indexOfFirst { it.id == d.id }
            if (idx >= 0) {
                val tile = page.tiles[idx]
                TileOptionsDialog(
                    tile = tile,
                    label = appLabel(ctx, tile.pkg),
                    canBack = idx > 0,
                    canFwd = idx < page.tiles.size - 1,
                    onSpan = { s ->
                        editPage(cur) { pg ->
                            pg.copy(tiles = pg.tiles.map { if (it.id == tile.id) it.copy(span = s) else it })
                        }
                    },
                    onChangeIcon = {
                        pendingIconId = tile.id
                        dialog = null
                        photoPicker.launch("image/*")
                    },
                    onResetIcon = {
                        iconFile(ctx, tile.id).delete()
                        iconVersion++
                    },
                    onMove = { dir ->
                        editPage(cur) { pg ->
                            val l = pg.tiles.toMutableList()
                            val t = l.removeAt(idx)
                            l.add(idx + dir, t)
                            pg.copy(tiles = l)
                        }
                    },
                    onRemove = {
                        iconFile(ctx, tile.id).delete()
                        editPage(cur) { pg -> pg.copy(tiles = pg.tiles.filter { it.id != tile.id }) }
                        dialog = null
                    },
                    onDismiss = { dialog = null }
                )
            }
        }
    }
}

@Composable
fun MenuItem(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun TileView(
    tile: Tile,
    iconVersion: Int,
    onClick: () -> Unit,
    onLong: () -> Unit
) {
    val ctx = LocalContext.current
    val bmp = remember(tile.id, tile.pkg, iconVersion) { loadTileIcon(ctx, tile) }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color(0xFF111111))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun RenameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nome do botão") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 6) text = it },
                singleLine = true,
                label = { Text("Até 6 caracteres") }
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
fun AddAppsDialog(added: Set<String>, onAdd: (String, Int) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val apps = remember { loadApps(ctx) }
    var query by remember { mutableStateOf("") }
    var span by remember { mutableStateOf(2) }
    val shown = remember(query, apps) { apps.filter { it.label.contains(query, ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adicionar apps") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Pronto") } },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Buscar") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Tamanho ao adicionar: $span", modifier = Modifier.padding(top = 8.dp))
                Slider(
                    value = span.toFloat(),
                    onValueChange = { span = it.roundToInt() },
                    valueRange = 1f..COLUMNS.toFloat(),
                    steps = COLUMNS - 2
                )
                LazyColumn(Modifier.height(280.dp)) {
                    items(shown, key = { it.pkg }) { app ->
                        val isAdded = app.pkg in added
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isAdded) { onAdd(app.pkg, span) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val icon = remember(app.pkg) { appIcon(ctx, app.pkg) }
                            if (icon != null) {
                                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(36.dp))
                            } else {
                                Spacer(Modifier.size(36.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(app.label, modifier = Modifier.weight(1f))
                            if (isAdded) Text("✓")
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun TileOptionsDialog(
    tile: Tile,
    label: String,
    canBack: Boolean,
    canFwd: Boolean,
    onSpan: (Int) -> Unit,
    onChangeIcon: () -> Unit,
    onResetIcon: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        text = {
            Column {
                Text("Tamanho: ${tile.span} (1 = muito pequeno, $COLUMNS = tela toda)")
                Slider(
                    value = tile.span.toFloat(),
                    onValueChange = { onSpan(it.roundToInt()) },
                    valueRange = 1f..COLUMNS.toFloat(),
                    steps = COLUMNS - 2
                )
                MenuItem("Trocar ícone por uma foto", onChangeIcon)
                MenuItem("Voltar ao ícone original", onResetIcon)
                if (canBack) MenuItem("Mover para antes") { onMove(-1) }
                if (canFwd) MenuItem("Mover para depois") { onMove(1) }
                MenuItem("Remover da página", onRemove)
            }
        }
    )
}
