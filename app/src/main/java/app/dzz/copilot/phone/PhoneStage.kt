package app.dzz.copilot.phone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLEncoder

class PhoneStage {
    private val _page = MutableStateFlow<String?>(null)
    val page: StateFlow<String?> = _page.asStateFlow()

    fun openSearch(query: String) {
        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        _page.value = "https://www.google.com/search?q=$encoded&hl=pt-BR"
    }

    fun clear() {
        _page.value = null
    }
}
