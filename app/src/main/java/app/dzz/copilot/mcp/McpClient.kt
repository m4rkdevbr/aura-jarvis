package app.dzz.copilot.mcp

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The server wants a login (HTTP 401); [header] is its WWW-Authenticate value. */
class McpAuthRequired(val header: String?) : IOException("o servidor pediu login")

/**
 * Talks to one MCP server over Streamable HTTP: initialize once, then tools/list and tools/call.
 * Blocking; call from an IO thread. [authHeader] gives the current header (name to value) or null.
 */
class McpClient(
    private val url: String,
    private val authHeader: () -> Pair<String, String>?,
    private val http: OkHttpClient = shared,
) {
    private var sessionId: String? = null
    private var protocol: String? = null
    private var nextId = 1
    private var ready = false

    @Synchronized
    fun tools(): List<McpTool> {
        ensureReady()
        val out = mutableListOf<McpTool>()
        var cursor: String? = null
        var pages = 0
        do {
            val result = rpc { id -> McpProtocol.listTools(id, cursor) }
            out += McpProtocol.tools(result)
            cursor = McpProtocol.nextCursor(result)
        } while (cursor != null && ++pages < 10)
        return out
    }

    @Synchronized
    fun call(name: String, args: JSONObject): Pair<String, Boolean> {
        ensureReady()
        val result = try {
            rpc { id -> McpProtocol.callTool(id, name, args) }
        } catch (e: SessionGone) {
            ready = false
            ensureReady()
            rpc { id -> McpProtocol.callTool(id, name, args) }
        }
        return McpProtocol.resultText(result) to result.optBoolean("isError", false)
    }

    private fun ensureReady() {
        if (ready) return
        sessionId = null
        val result = rpc { id -> McpProtocol.initialize(id) }
        protocol = result.optString("protocolVersion").ifBlank { McpProtocol.VERSION }
        post(McpProtocol.notification("notifications/initialized"))
        ready = true
    }

    private class SessionGone : IOException("sessão expirada")

    private fun rpc(body: (Int) -> String): JSONObject {
        val id = nextId++
        val (text, type) = post(body(id))
        val response = McpProtocol.response(text, type, id)
            ?: throw IOException("resposta inválida do servidor MCP")
        McpProtocol.error(response)?.let { throw IOException(it) }
        return response.optJSONObject("result") ?: JSONObject()
    }

    private fun post(json: String): Pair<String, String?> {
        val builder = Request.Builder().url(url)
            .header("Accept", "application/json, text/event-stream")
            .post(json.toRequestBody(JSON))
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        protocol?.let { builder.header("MCP-Protocol-Version", it) }
        authHeader()?.let { (k, v) -> builder.header(k, v) }
        http.newCall(builder.build()).execute().use { response ->
            response.header("Mcp-Session-Id")?.let { sessionId = it }
            when {
                response.code == 401 -> throw McpAuthRequired(response.header("WWW-Authenticate"))
                response.code == 404 && sessionId != null && ready -> throw SessionGone()
                response.code == 202 -> return "" to null
                !response.isSuccessful -> throw IOException("HTTP ${response.code}: ${response.body?.string().orEmpty().take(160)}")
            }
            return response.body?.string().orEmpty() to response.header("Content-Type")
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        val shared: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}

/** What the login needs, found from the server's metadata. */
data class OAuthEndpoints(
    val authorize: String,
    val token: String,
    val register: String?,
    val scope: String?,
)

/** MCP authorization: discovery, dynamic client registration, token exchange and refresh. */
class McpOAuth(private val http: OkHttpClient = McpClient.shared) {
    fun discover(serverUrl: String, wwwAuthenticate: String?): OAuthEndpoints {
        var issuer: String? = null
        var scope: String? = null
        for (u in McpProtocol.resourceMetadataUrls(serverUrl, McpProtocol.resourceMetadataFromHeader(wwwAuthenticate))) {
            val meta = getJson(u) ?: continue
            issuer = meta.optJSONArray("authorization_servers")?.optString(0)?.takeIf { it.isNotBlank() }
            scope = meta.optJSONArray("scopes_supported")?.let { a -> (0 until a.length()).joinToString(" ") { a.optString(it) } }
                ?.takeIf { it.isNotBlank() }
            if (issuer != null) break
        }
        val uri = java.net.URI(serverUrl)
        val origin = "${uri.scheme}://${uri.authority}"
        val base = issuer ?: origin
        for (u in McpProtocol.authServerMetadataUrls(base)) {
            val meta = getJson(u) ?: continue
            val authorize = meta.optString("authorization_endpoint")
            val token = meta.optString("token_endpoint")
            if (authorize.isNotBlank() && token.isNotBlank()) {
                return OAuthEndpoints(authorize, token, meta.optString("registration_endpoint").ifBlank { null }, scope)
            }
        }
        // Older servers without metadata: the default paths of the 2025-03-26 spec.
        return OAuthEndpoints("$origin/authorize", "$origin/token", "$origin/register", scope)
    }

    fun register(endpoint: String): String {
        val request = Request.Builder().url(endpoint)
            .post(McpProtocol.registration().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("registro do app recusado (HTTP ${r.code})")
            return JSONObject(body).optString("client_id").ifBlank { throw IOException("registro sem client_id") }
        }
    }

    /** Exchanges a code (or a refresh token) for tokens: (access, refresh, expiresAt). */
    fun token(endpoint: String, form: Map<String, String>): Triple<String, String, Long> {
        val body = FormBody.Builder().also { b -> form.forEach { (k, v) -> b.add(k, v) } }.build()
        val request = Request.Builder().url(endpoint).header("Accept", "application/json").post(body).build()
        http.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("login recusado (HTTP ${r.code}): ${text.take(120)}")
            val o = JSONObject(text)
            val access = o.optString("access_token").ifBlank { throw IOException("login sem token") }
            val expires = o.optLong("expires_in", 0L).let { if (it > 0) System.currentTimeMillis() + it * 1000 else 0L }
            return Triple(access, o.optString("refresh_token"), expires)
        }
    }

    private fun getJson(url: String): JSONObject? = runCatching {
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty())
        }
    }.getOrNull()
}
