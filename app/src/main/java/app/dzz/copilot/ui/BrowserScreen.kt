package app.dzz.copilot.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.dzz.copilot.hub

@Composable
fun BrowserScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val url by context.hub().stage.page.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("Fechar") }
        if (url.isNullOrBlank()) {
            Text("Nenhuma página pedida.", style = mutedStyle())
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { host ->
                    WebView(host).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                        loadUrl(url!!)
                    }
                },
                update = { view ->
                    val next = url
                    if (!next.isNullOrBlank() && view.url != next) view.loadUrl(next)
                },
            )
        }
    }
}
