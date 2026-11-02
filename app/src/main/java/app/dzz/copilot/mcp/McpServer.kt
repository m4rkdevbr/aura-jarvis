package app.dzz.copilot.mcp

import org.json.JSONArray
import org.json.JSONObject

/** How Aura proves who she is to an MCP server. */
enum class McpAuth { None, Token, OAuth }

/** One saved MCP connection. Secrets live only in the encrypted settings. */
data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val auth: McpAuth = McpAuth.None,
    /** Manual key: sent as "Authorization: Bearer <token>" or in [header] when set. */
    val token: String = "",
    val header: String = "",
    val enabled: Boolean = true,
    val catalogId: String = "",
    // OAuth state.
    val clientId: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0L,
    val tokenEndpoint: String = "",
    // Last tool list, so Aura has the tools right after a restart.
    val tools: List<McpTool> = emptyList(),
    val lastError: String = "",
) {
    val needsLogin: Boolean get() = auth == McpAuth.OAuth && accessToken.isBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("url", url).put("auth", auth.name)
        .put("token", token).put("header", header).put("enabled", enabled).put("catalogId", catalogId)
        .put("clientId", clientId).put("accessToken", accessToken).put("refreshToken", refreshToken)
        .put("expiresAt", expiresAt).put("tokenEndpoint", tokenEndpoint).put("lastError", lastError)
        .put(
            "tools",
            JSONArray().also { a ->
                tools.forEach { t ->
                    a.put(
                        JSONObject().put("name", t.name).put("description", t.description)
                            .put("schema", t.schema).put("readOnly", t.readOnly),
                    )
                }
            },
        )

    companion object {
        fun fromJson(o: JSONObject): McpServer? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val url = o.optString("url").takeIf { it.isNotBlank() } ?: return null
            val tools = o.optJSONArray("tools")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { t ->
                        McpTool(
                            t.optString("name"), t.optString("description"),
                            t.optJSONObject("schema") ?: JSONObject(), t.optBoolean("readOnly"),
                        )
                    }
                }
            }.orEmpty()
            return McpServer(
                id = id,
                name = o.optString("name").ifBlank { url },
                url = url,
                auth = runCatching { McpAuth.valueOf(o.optString("auth")) }.getOrDefault(McpAuth.None),
                token = o.optString("token"),
                header = o.optString("header"),
                enabled = o.optBoolean("enabled", true),
                catalogId = o.optString("catalogId"),
                clientId = o.optString("clientId"),
                accessToken = o.optString("accessToken"),
                refreshToken = o.optString("refreshToken"),
                expiresAt = o.optLong("expiresAt"),
                tokenEndpoint = o.optString("tokenEndpoint"),
                tools = tools,
                lastError = o.optString("lastError"),
            )
        }

        fun listToJson(list: List<McpServer>): String = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()

        fun listFromJson(text: String): List<McpServer> = runCatching {
            val a = JSONArray(text)
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::fromJson) }
        }.getOrDefault(emptyList())
    }
}

/** A ready-made connection the user can add with one tap. */
data class McpCatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val auth: McpAuth,
    /** Where to get the key, for [McpAuth.Token]. */
    val keyHint: String = "",
    /** The user must paste their own address (Zapier, Make). */
    val personalUrl: Boolean = false,
)

object McpCatalog {
    val entries = listOf(
        McpCatalogEntry(
            "github", "GitHub", "Repositórios, issues, pull requests e código.",
            "https://api.githubcopilot.com/mcp/", McpAuth.Token,
            keyHint = "Crie um token em github.com/settings/personal-access-tokens e cole aqui.",
        ),
        McpCatalogEntry(
            "notion", "Notion", "Páginas, bancos de dados e notas do seu Notion.",
            "https://mcp.notion.com/mcp", McpAuth.OAuth,
        ),
        McpCatalogEntry(
            "linear", "Linear", "Tarefas, projetos e ciclos do Linear.",
            "https://mcp.linear.app/mcp", McpAuth.OAuth,
        ),
        McpCatalogEntry(
            "zapier", "Zapier (Google e +8000 apps)",
            "Gmail, Google Agenda, Drive, Planilhas e milhares de apps, pelas ações que você liberar no Zapier.",
            "", McpAuth.None, keyHint = "Crie seu servidor em mcp.zapier.com e cole o endereço dele.", personalUrl = true,
        ),
        McpCatalogEntry(
            "huggingface", "Hugging Face", "Modelos, datasets, Spaces e artigos de IA.",
            "https://huggingface.co/mcp", McpAuth.Token,
            keyHint = "Crie um token de leitura em huggingface.co/settings/tokens.",
        ),
        McpCatalogEntry(
            "deepwiki", "DeepWiki", "Explica qualquer repositório público do GitHub.",
            "https://mcp.deepwiki.com/mcp", McpAuth.None,
        ),
        McpCatalogEntry(
            "context7", "Context7", "Documentação atualizada de bibliotecas e frameworks.",
            "https://mcp.context7.com/mcp", McpAuth.None,
        ),
    )

    fun find(id: String): McpCatalogEntry? = entries.firstOrNull { it.id == id }
}

/** A plugin: a set of connections plus instructions (a skill) that teach Aura to use them. */
data class AuraPlugin(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val catalogIds: List<String> = emptyList(),
    val servers: List<Pair<String, String>> = emptyList(),
) {
    fun skillMarkdown(): String = buildString {
        append("---\nname: plugin-").append(id).append('\n')
        append("description: ").append(description.replace('\n', ' ')).append('\n')
        append("---\n").append(instructions.trim()).append('\n')
    }

    companion object {
        /** Imports a plugin from JSON: {name, description, instructions, servers:[{name,url}]}. */
        fun parse(text: String): AuraPlugin? = runCatching {
            val o = JSONObject(text.trim())
            val name = o.getString("name").trim()
            val servers = o.optJSONArray("servers")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { s -> s.optString("name").ifBlank { name } to s.getString("url") }
                }
            }.orEmpty().filter { it.second.startsWith("https://") }
            AuraPlugin(
                id = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "plugin" },
                name = name,
                description = o.optString("description").ifBlank { name },
                instructions = o.optString("instructions"),
                catalogIds = o.optJSONArray("catalog")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                servers = servers,
            ).takeIf { it.instructions.isNotBlank() || it.servers.isNotEmpty() || it.catalogIds.isNotEmpty() }
        }.getOrNull()

        val builtIn = listOf(
            AuraPlugin(
                "desenvolvedor", "Desenvolvedor",
                "Ajuda com código: repositórios no GitHub, explicação de projetos e documentação de bibliotecas.",
                """
                Quando o usuário falar de código, repositório, bug ou biblioteca:
                - Para entender um repositório público, use as ferramentas do DeepWiki.
                - Para documentação de uma biblioteca, use o Context7 (resolva o nome da biblioteca antes).
                - Para issues, pull requests e arquivos do usuário, use o GitHub; antes de criar, comentar ou fechar algo, confirme.
                Responda falado e curto; ofereça mandar detalhes pelo Telegram quando a resposta for longa.
                """.trimIndent(),
                catalogIds = listOf("github", "deepwiki", "context7"),
            ),
            AuraPlugin(
                "produtividade", "Produtividade",
                "Notas e tarefas no Notion e no Linear por voz.",
                """
                Quando o usuário pedir para anotar, buscar uma nota ou ver tarefas de trabalho:
                - Notas e documentos: procure ou crie no Notion.
                - Tarefas de equipe e bugs: use o Linear.
                Antes de criar ou alterar algo, diga em uma frase o que vai fazer e espere o sim.
                """.trimIndent(),
                catalogIds = listOf("notion", "linear"),
            ),
            AuraPlugin(
                "google-zapier", "Google pelo Zapier",
                "Gmail, Agenda, Drive e Planilhas do Google pelas ações liberadas no Zapier.",
                """
                Quando o usuário pedir algo do Gmail, Google Drive, Planilhas ou Agenda que as ferramentas do celular
                não resolvem, use as ferramentas do Zapier. Confirme antes de enviar e-mail, criar evento ou alterar planilha.
                """.trimIndent(),
                catalogIds = listOf("zapier"),
            ),
        )
    }
}
