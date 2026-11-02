package app.dzz.copilot.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {
    @Test
    fun readsJsonAndEventStreamResponses() {
        val json = """{"jsonrpc":"2.0","id":3,"result":{"ok":true}}"""
        assertTrue(McpProtocol.response(json, "application/json", 3)!!.getJSONObject("result").getBoolean("ok"))
        val sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n" +
            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"tools\":[]}}\n\n"
        assertTrue(McpProtocol.response(sse, "text/event-stream", 4)!!.has("result"))
        assertNull(McpProtocol.response(sse, "text/event-stream", 9))
        val err = JSONObject("""{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}""")
        assertEquals("Method not found (-32601)", McpProtocol.error(err))
    }

    @Test
    fun parsesToolsAndResults() {
        val result = JSONObject(
            """{"tools":[{"name":"search_pages","description":"Busca","inputSchema":{"type":"object","properties":{"q":{"type":"string"}}},
               "annotations":{"readOnlyHint":true}},{"name":"create_page","inputSchema":{"type":"object"}}],"nextCursor":"abc"}""",
        )
        val tools = McpProtocol.tools(result)
        assertEquals(2, tools.size)
        assertTrue(tools[0].readOnly)
        assertEquals("abc", McpProtocol.nextCursor(result))
        assertFalse(McpRules.needsConfirm(tools[0]))
        assertTrue(McpRules.needsConfirm(tools[1]))
        val call = JSONObject("""{"content":[{"type":"text","text":"linha 1"},{"type":"image","data":"x"}],"isError":false}""")
        assertEquals("linha 1\n[imagem]", McpProtocol.resultText(call))
    }

    @Test
    fun functionNamesAndSchemasAreModelSafe() {
        assertEquals("mcp_github_list_pull_requests", McpProtocol.functionName("GitHub", "list-pull.requests"))
        assertTrue(McpProtocol.functionName("Servidor com nome enorme demais", "x".repeat(80)).length <= 64)
        val schema = JSONObject(
            """{"type":"object","properties":{"a":{"type":["string","null"]},"b":{"anyOf":[{"type":"number"}]},
               "c":{"type":"array","items":{"type":"object","properties":{"d":{"type":"integer"}}}}},"required":["a","zz"],
               "additionalProperties":false,"${'$'}schema":"x"}""",
        )
        val clean = McpProtocol.cleanSchema(schema)
        assertEquals("string", clean.getJSONObject("properties").getJSONObject("a").getString("type"))
        assertEquals("string", clean.getJSONObject("properties").getJSONObject("b").getString("type"))
        assertEquals("integer", clean.getJSONObject("properties").getJSONObject("c").getJSONObject("items")
            .getJSONObject("properties").getJSONObject("d").getString("type"))
        assertEquals(1, clean.getJSONArray("required").length())
        assertFalse(clean.has("additionalProperties"))
    }

    @Test
    fun oauthHelpers() {
        // RFC 7636 appendix B example.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", McpProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertEquals(
            listOf("https://x.com/.well-known/oauth-protected-resource/mcp", "https://x.com/.well-known/oauth-protected-resource"),
            McpProtocol.resourceMetadataUrls("https://x.com/mcp", null),
        )
        assertEquals("https://x.com/meta", McpProtocol.resourceMetadataFromHeader("Bearer resource_metadata=\"https://x.com/meta\""))
        val url = McpProtocol.authorizeUrl("https://a.com/authorize", "cid", "ch", "st", "https://x.com/mcp", "read")
        assertTrue(url.startsWith("https://a.com/authorize?response_type=code&client_id=cid"))
        assertTrue(url.contains("redirect_uri=app.dzz.copilot%3A%2F%2Foauth"))
        assertTrue(url.contains("code_challenge_method=S256") && url.contains("scope=read"))
    }

    @Test
    fun serversRoundTripAndPluginsParse() {
        val s = McpServer("id1", "Notion", "https://mcp.notion.com/mcp", McpAuth.OAuth, accessToken = "t",
            tools = listOf(McpTool("search", "Busca", JSONObject().put("type", "object"), true)))
        val back = McpServer.listFromJson(McpServer.listToJson(listOf(s))).single()
        assertEquals(s.copy(tools = emptyList()), back.copy(tools = emptyList()))
        assertEquals("search", back.tools.single().name)
        assertTrue(McpRules.direct(back))
        val plugin = AuraPlugin.parse("""{"name":"Minha Pesquisa","instructions":"Use a busca.","servers":[{"name":"Busca","url":"https://b.com/mcp"},{"url":"http://inseguro"}]}""")!!
        assertEquals("minha-pesquisa", plugin.id)
        assertEquals(1, plugin.servers.size)
        assertTrue(plugin.skillMarkdown().startsWith("---\nname: plugin-minha-pesquisa\n"))
        assertNull(AuraPlugin.parse("lixo"))
        assertTrue(McpCatalog.entries.all { it.personalUrl || it.url.startsWith("https://") })
    }
}
