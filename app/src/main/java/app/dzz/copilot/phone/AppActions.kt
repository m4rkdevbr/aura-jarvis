package app.dzz.copilot.phone

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import java.text.Normalizer

/** Which app a search goes to, and the intents that open it already on the results. Pure, unit tested. */
object AppActions {
    const val YOUTUBE = "com.google.android.youtube"
    const val YT_MUSIC = "com.google.android.apps.youtube.music"
    const val SPOTIFY = "com.spotify.music"
    const val MAPS = "com.google.android.apps.maps"
    const val PLAY = "com.android.vending"

    enum class Target { YouTube, YouTubeMusic, Spotify, Maps, Route, PlayStore, Web }

    fun target(app: String): Target? = when (val a = fold(app)) {
        "" -> null
        else -> when {
            "music" in a && "youtube" in a -> Target.YouTubeMusic
            "youtube" in a || a == "yt" || "video" in a -> Target.YouTube
            "spotify" in a || "musica" in a -> Target.Spotify
            "rota" in a || "navega" in a || "caminho" in a -> Target.Route
            "map" in a || "lugar" in a -> Target.Maps
            "play" in a || "loja" in a || "store" in a -> Target.PlayStore
            "google" in a || "web" in a || "chrome" in a || "internet" in a -> Target.Web
            else -> null
        }
    }

    /** Intents to try in order (the app itself first, then a web fallback). */
    fun intents(target: Target, query: String): List<Intent> {
        val q = query.trim()
        val enc = Uri.encode(q)
        return when (target) {
            Target.YouTube -> listOf(
                Intent(Intent.ACTION_SEARCH).setPackage(YOUTUBE).putExtra("query", q),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$enc")).setPackage(YOUTUBE),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$enc")),
            )
            Target.YouTubeMusic -> listOf(playFromSearch(q, YT_MUSIC), Intent(Intent.ACTION_SEARCH).setPackage(YT_MUSIC).putExtra("query", q))
            Target.Spotify -> listOf(
                playFromSearch(q, SPOTIFY),
                Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$enc")).setPackage(SPOTIFY),
                playFromSearch(q, null),
            )
            Target.Maps -> listOf(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$enc")).setPackage(MAPS),
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$enc")),
            )
            Target.Route -> listOf(
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$enc")).setPackage(MAPS),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$enc")),
            )
            Target.PlayStore -> listOf(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$enc")).setPackage(PLAY))
            Target.Web -> listOf(
                Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$enc")),
            )
        }
    }

    private fun playFromSearch(q: String, pkg: String?): Intent =
        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, q)
            .apply { if (pkg != null) setPackage(pkg) }

    /** Buttons Aura never taps for the user: sending, paying, buying, deleting, confirming, signing in. */
    fun sensitive(label: String): Boolean = SENSITIVE.containsMatchIn(fold(label))

    /** A YouTube result card: its description carries views, a duration or "play video". */
    fun looksLikeVideo(label: String): Boolean {
        val f = fold(label)
        return " - " in label && (VIDEO.containsMatchIn(f))
    }

    fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

    private val SENSITIVE = Regex(
        "\\b(enviar|envia|send|pagar|pague|pagamento|pay|comprar|compra|buy|assinar|subscribe|inscrever|inscrever-se|excluir|apagar|deletar|delete|" +
            "remover|confirmar|confirm|transferir|pix|instalar|install|desinstalar|entrar|login|sign in|senha|password)\\b",
    )
    private val VIDEO = Regex("visualiza|views|\\bminutos?\\b|\\bminutes?\\b|segundos|reproduzir video|play video|ao vivo|live")
}
