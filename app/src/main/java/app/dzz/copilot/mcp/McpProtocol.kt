package app.dzz.copilot.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** One tool offered by an MCP server. */
data class McpTool(
    val name: String,
    val description: String,
    val schema: JSONObject,
    /** The server says the tool only reads (annotations.readOnlyHint). */
    val readOnly: Boolean,
)

/**
 * The MCP wire format (JSON-RPC 2.0 over Streamable HTTP) and the OAuth helpers. Pure JSON and
 * strings, so it is unit tested; the HTTP calls live in [McpClient].
 */
object McpProtocol {
    const val VERSION = "2025-06-18"
    const val REDIRECT = "app.dzz.copilot://oauth"

    fun request(id: Int, method: String, params: JSONObject? = null): String {
        val o = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
        if (params != null) o.put("params", params)
        return o.toString()
    }

    fun notification(method: String): String = JSONObject().put("jsonrpc", "2.0").put("method", method).toString()

    fun initialize(id: Int): String = request(
        id,
        "initialize",
        JSONObject()
            .put("protocolVersion", VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "Aura").put("version", "1.2")),
    )

    fun listTools(id: Int, cursor: String?): String =
        request(id, "tools/list", cursor?.let { JSONObject().put("cursor", it) } ?: JSONObject())

    fun callTool(id: Int, name: String, args: JSONObject): String =
        request(id, "tools/call", JSONObject().put("name", name).put("arguments", args))

    /**
     * Finds the JSON-RPC response with [id] in a body that is either plain JSON or a
     * text/event-stream (one JSON message per `data:` event).
     */
    fun response(body: String, contentType: String?, id: Int): JSONObject? {
        val messages = if (contentType?.contains("event-stream", ignoreCase = true) == true || body.trimStart().startsWith("event:") ||
            body.trimStart().startsWith("data:")
        ) {
            sseData(body).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        } else {
            val t = body.trim()
            when {
                t.startsWith("[") -> runCatching { JSONArray(t) }.getOrNull()?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
                t.startsWith("{") -> listOfNotNull(runCatching { JSONObject(t) }.getOrNull())
                else -> emptyList()
            }
        }
        return messages.firstOrNull { it.has("id") && it.opt("id")?.toString() == id.toString() }
    }

    /** The data of each server-sent event (multi-line data joined with newlines). */
    fun sseData(body: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        body.replace("\r\n", "\n").split('\n').forEach { line ->
            when {
                line.isEmpty() -> {
                    if (current.isNotEmpty()) out += current.toString()
                    current.clear()
                }
                line.startsWith("data:") -> {
                    if (current.isNotEmpty()) current.append('\n')
                    current.append(line.removePrefix("data:").removePrefix(" "))
                }
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /** Error text of a JSON-RPC response, or null. */
    fun error(response: JSONObject): String? = response.optJSONObject("error")?.let { e ->
        "${e.optString("message", "erro")} (${e.optInt("code")})"
    }

    fun tools(result: JSONObject): List<McpTool> {
        val array = result.optJSONArray("tools") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val t = array.optJSONObject(i) ?: return@mapNotNull null
            val name = t.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            McpTool(
                name = name,
                description = t.optString("description").ifBlank { t.optString("title", name) }.take(900),
                schema = t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object").put("properties", JSONObject()),
                readOnly = t.optJSONObject("annotations")?.optBoolean("readOnlyHint", false) == true,
            )
        }
    }

    fun nextCursor(result: JSONObject): String? = result.optString("nextCursor").takeIf { it.isNotBlank() }

    /** Readable text of a tools/call result (text parts, resources, structured content). */
    fun resultText(result: JSONObject, limit: Int = 6_000): String {
        val parts = mutableListOf<String>()
        result.optJSONArray("content")?.let { content ->
            for (i in 0 until content.length()) {
                val c = content.optJSONObject(i) ?: continue
                when (c.optString("type")) {
                    "text" -> parts += c.optString("text")
                    "resource" -> c.optJSONObject("resource")?.let { r ->
                        parts += r.optString("text").ifBlank { "[arquivo ${r.optString("uri")}]" }
                    }
                    "resource_link" -> parts += "[link ${c.optString("name")}: ${c.optString("uri")}]"
                    "image" -> parts += "[imagem]"
                    "audio" -> parts += "[áudio]"
                }
            }
        }
        if (parts.isEmpty()) result.optJSONObject("structuredContent")?.let { parts += it.toString() }
        val text = parts.joinToString("\n").trim().ifEmpty { "(sem conteúdo)" }
        return if (text.length > limit) text.take(limit) + "\n…(cortado)" else text
    }

    /** A Gemini/OpenAI-safe function name: letters, digits and underscores, at most 64 chars. */
    fun functionName(server: String, tool: String): String {
        fun clean(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val name = "mcp_${clean(server).take(16)}_${clean(tool)}"
        return name.take(64).trimEnd('_')
    }

    /**
     * Reduces a JSON schema to what function calling accepts: one type per field (["string","null"]
     * becomes "string"), no $ref/anyOf/oneOf (a field becomes a described string), objects keep
     * their properties and required list, arrays their items.
     */
    fun cleanSchema(source: JSONObject, depth: Int = 0): JSONObject {
        val out = JSONObject()
        val type = when (val t = source.opt("type")) {
            is String -> t
            is JSONArray -> (0 until t.length()).map { t.optString(it) }.firstOrNull { it != "null" } ?: "string"
            else -> when {
                source.has("properties") -> "object"
                source.has("items") -> "array"
                else -> "string"
            }
        }
        val description = source.optString("description").take(400)
        if (depth > 5) return JSONObject().put("type", "string").put("description", description.ifBlank { "JSON" })
        out.put("type", type)
        if (description.isNotBlank()) out.put("description", description)
        source.optJSONArray("enum")?.let { e ->
            val values = JSONArray()
            for (i in 0 until e.length()) if (e.opt(i) is String) values.put(e.getString(i))
            if (values.length() > 0 && type == "string") out.put("enum", values)
        }
        when (type) {
            "object" -> {
                val props = JSONObject()
                source.optJSONObject("properties")?.let { p ->
                    p.keys().forEach { key -> p.optJSONObject(key)?.let { props.put(key, cleanSchema(it, depth + 1)) } }
                }
                out.put("properties", props)
                source.optJSONArray("required")?.let { r ->
                    val req = JSONArray()
                    for (i in 0 until r.length()) r.optString(i).takeIf { props.has(it) }?.let { req.put(it) }
                    if (req.length() > 0) out.put("required", req)
                }
            }
            "array" -> out.put("items", source.optJSONObject("items")?.let { cleanSchema(it, depth + 1) } ?: JSONObject().put("type", "string"))
        }
        return out
    }

    // --- OAuth 2.1 with PKCE, as the MCP authorization spec asks ---

    fun verifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** Where the protected-resource metadata may live, most specific first. */
    fun resourceMetadataUrls(serverUrl: String, fromHeader: String?): List<String> {
        val uri = URI(serverUrl)
        val origin = "${uri.scheme}://${uri.authority}"
        val path = uri.path.orEmpty().trimEnd('/')
        return listOfNotNull(
            fromHeader,
            if (path.isNotEmpty()) "$origin/.well-known/oauth-protected-resource$path" else null,
            "$origin/.well-known/oauth-protected-resource",
        ).distinct()
    }

    /** Where the authorization-server metadata may live (RFC 8414 and OpenID). */
    fun authServerMetadataUrls(issuer: String): List<String> {
        val uri = URI(issuer)
        val origin = "${uri.scheme}://${uri.authority}"
        val path = uri.path.orEmpty().trimEnd('/')
        return if (path.isEmpty()) {
            listOf("$origin/.well-known/oauth-authorization-server", "$origin/.well-known/openid-configuration")
        } else {
            listOf(
                "$origin/.well-known/oauth-authorization-server$path",
                "$origin/.well-known/openid-configuration$path",
                "$origin$path/.well-known/openid-configuration",
            )
        }
    }

    /** resource_metadata="…" from a WWW-Authenticate header. */
    fun resourceMetadataFromHeader(header: String?): String? =
        header?.let { Regex("resource_metadata=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }

    fun registration(clientName: String = "Aura"): String = JSONObject()
        .put("client_name", clientName)
        .put("redirect_uris", JSONArray().put(REDIRECT))
        .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
        .put("response_types", JSONArray().put("code"))
        .put("token_endpoint_auth_method", "none")
        .toString()

    fun authorizeUrl(
        endpoint: String,
        clientId: String,
        challenge: String,
        state: String,
        resource: String,
        scope: String?,
    ): String {
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        val params = buildList {
            add("response_type=code")
            add("client_id=${e(clientId)}")
            add("redirect_uri=${e(REDIRECT)}")
            add("code_challenge=${e(challenge)}")
            add("code_challenge_method=S256")
            add("state=${e(state)}")
            add("resource=${e(resource)}")
            if (!scope.isNullOrBlank()) add("scope=${e(scope)}")
        }
        val sep = if (endpoint.contains('?')) '&' else '?'
        return endpoint + sep + params.joinToString("&")
    }
}
