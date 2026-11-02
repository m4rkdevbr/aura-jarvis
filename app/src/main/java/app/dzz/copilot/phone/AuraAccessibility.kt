package app.dzz.copilot.phone

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional accessibility access (the user turns it on in Android settings): lets Aura read the
 * text on the screen, press back, home, recents or open notifications, and tap an item the user
 * asked for (a video in YouTube results, a song). It never taps anything that sends, buys, pays,
 * deletes or confirms (see [AppActions.sensitive]); those stay with the user.
 */
sealed class TapResult {
    data class Done(val what: String) : TapResult()
    object NotFound : TapResult()
    object Sensitive : TapResult()
    object NoAccess : TapResult()
}

class AuraAccessibility : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: AuraAccessibility? = null

        val enabled: Boolean get() = instance != null

        fun screenText(limit: Int = 3_000): String? {
            val service = instance ?: return null
            val root = service.rootInActiveWindow ?: return ""
            val lines = LinkedHashSet<String>()
            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 40 || lines.sumOf { it.length } > limit) return
                val text = node.text?.toString()?.trim().orEmpty()
                    .ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
                if (text.isNotEmpty() && !node.isPassword) lines += text
                for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
            }
            walk(root, 0)
            val app = root.packageName?.toString().orEmpty()
            return ("App: $app\n" + lines.joinToString("\n")).take(limit)
        }

        /** Taps the first visible item whose text or description contains [label]. */
        fun tap(label: String): TapResult {
            val service = instance ?: return TapResult.NoAccess
            val root = service.rootInActiveWindow ?: return TapResult.NotFound
            val wanted = AppActions.fold(label)
            if (wanted.isBlank()) return TapResult.NotFound
            val node = find(root) { AppActions.fold(it).contains(wanted) } ?: return TapResult.NotFound
            if (AppActions.sensitive(describe(node))) return TapResult.Sensitive
            return if (click(node)) TapResult.Done(describe(node).take(120)) else TapResult.NotFound
        }

        /** Taps the first video of a YouTube results list (its card mentions views or duration). */
        fun tapFirstVideo(): TapResult {
            val service = instance ?: return TapResult.NoAccess
            val root = service.rootInActiveWindow ?: return TapResult.NotFound
            val node = find(root) { AppActions.looksLikeVideo(it) } ?: return TapResult.NotFound
            return if (click(node)) TapResult.Done(describe(node).substringBefore(" - ").take(120)) else TapResult.NotFound
        }

        fun foreground(): String = instance?.rootInActiveWindow?.packageName?.toString().orEmpty()

        private fun describe(node: AccessibilityNodeInfo): String =
            node.text?.toString()?.trim().orEmpty().ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }

        private fun find(root: AccessibilityNodeInfo, match: (String) -> Boolean): AccessibilityNodeInfo? {
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.addLast(root to 0)
            while (queue.isNotEmpty()) {
                val (node, depth) = queue.removeFirst()
                val text = describe(node)
                if (text.isNotEmpty() && node.isVisibleToUser && !node.isPassword && match(text)) return node
                if (depth < 40) for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it to depth + 1) }
            }
            return null
        }

        private fun click(start: AccessibilityNodeInfo): Boolean {
            var node: AccessibilityNodeInfo? = start
            var hops = 0
            while (node != null && hops < 8) {
                if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node = node.parent
                hops++
            }
            return false
        }

        fun global(action: String): Boolean {
            val service = instance ?: return false
            val code = when (action.trim().lowercase()) {
                "back", "voltar" -> GLOBAL_ACTION_BACK
                "home", "inicio", "início" -> GLOBAL_ACTION_HOME
                "recents", "recentes" -> GLOBAL_ACTION_RECENTS
                "notifications", "notificacoes", "notificações" -> GLOBAL_ACTION_NOTIFICATIONS
                else -> return false
            }
            return service.performGlobalAction(code)
        }
    }
}
