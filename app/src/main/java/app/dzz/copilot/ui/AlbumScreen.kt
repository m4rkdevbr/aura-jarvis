package app.dzz.copilot.ui

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AlbumItem(val uri: Uri, val name: String, val added: Long, val kind: String)

@Composable
fun AlbumScreen() {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var selected by remember { mutableStateOf<AlbumItem?>(null) }
    var granted by remember {
        mutableStateOf(mediaPermissions().all { ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED })
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.all { it }
    }
    LaunchedEffect(granted) {
        if (granted) items = withContext(Dispatchers.IO) { loadAlbum(context) }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        item {
            PageHeader(
                title = "Álbum",
                subtitle = "Toque para ver · segure para compartilhar",
            )
        }
        if (!granted) {
            item {
                WideButton("Permitir ver a galeria") { ask.launch(mediaPermissions()) }
            }
        }
        selected?.let { current ->
            item {
                SectionCard("Selecionada") {
                    Text(current.name, style = mutedStyle())
                    TextButton(onClick = { shareAlbumItem(context, current) }) {
                        Text("Compartilhar")
                    }
                    TextButton(onClick = { selected = null }) { Text("Fechar") }
                }
            }
        }
        items(items, key = { it.uri.toString() }) { media ->
            ColumnThumb(
                item = media,
                onClick = {
                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(media.uri, if (media.kind == "video") "video/*" else "image/*")
                    view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(view) }
                },
                onLongClick = {
                    selected = media
                    shareAlbumItem(context, media)
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnThumb(item: AlbumItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(item.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(item.uri) {
        if (item.kind != "image") return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(item.uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = 4 })
            }
        }
    }
    val modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1f)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = item.name,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(if (item.kind == "video") "Vídeo\n${item.name}" else item.name)
        }
    }
}

private fun shareAlbumItem(context: android.content.Context, item: AlbumItem) {
    val mime = if (item.kind == "video") "video/*" else "image/*"
    val share = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, item.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(share, "Compartilhar mídia"))
    }
}

private fun loadAlbum(context: android.content.Context): List<AlbumItem> {
    val resolver = context.contentResolver
    val found = mutableListOf<AlbumItem>()
    fun query(uri: Uri, kind: String) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val selection = if (Build.VERSION.SDK_INT >= 29) "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" else null
        val args = if (Build.VERSION.SDK_INT >= 29) arrayOf("%DZZ Copiloto%") else null
        resolver.query(uri, projection, selection, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (cursor.moveToNext()) {
                found += AlbumItem(
                    uri = android.content.ContentUris.withAppendedId(uri, cursor.getLong(idColumn)),
                    name = cursor.getString(nameColumn) ?: kind,
                    added = cursor.getLong(dateColumn),
                    kind = kind,
                )
            }
        }
    }
    query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image")
    query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video")
    return found.sortedByDescending { it.added }
}

private fun mediaPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}
