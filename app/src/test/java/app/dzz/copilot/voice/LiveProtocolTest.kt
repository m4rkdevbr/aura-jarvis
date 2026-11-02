package app.dzz.copilot.voice

import app.dzz.copilot.brain.Schema
import app.dzz.copilot.brain.SimpleTool
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LiveProtocolTest {
    @Test
    fun setupCarriesModelVoiceToolsAndTranscription() {
        val tools = LiveProtocol.functions(
            listOf(
                SimpleTool("agenda", "Ler a agenda") { "" },
                SimpleTool("remember", "Guardar", Schema.obj("fact" to Schema.str("O fato"), required = listOf("fact"))) { "" },
            ),
        )
        val setup = JSONObject(LiveProtocol.setup("gemini-x-live", "Leda", "Você é a Aura", tools)).getJSONObject("setup")
        assertEquals("models/gemini-x-live", setup.getString("model"))
        val gen = setup.getJSONObject("generationConfig")
        assertEquals("AUDIO", gen.getJSONArray("responseModalities").getString(0))
        assertEquals(
            "Leda",
            gen.getJSONObject("speechConfig").getJSONObject("voiceConfig").getJSONObject("prebuiltVoiceConfig").getString("voiceName"),
        )
        assertTrue(setup.has("inputAudioTranscription"))
        assertTrue(setup.has("outputAudioTranscription"))
        val decls = setup.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals(2, decls.length())
        // A tool without arguments has no parameters (Gemini rejects an empty OBJECT).
        assertFalse(decls.getJSONObject(0).has("parameters"))
        val params = decls.getJSONObject(1).getJSONObject("parameters")
        assertEquals("OBJECT", params.getString("type"))
        assertEquals("STRING", params.getJSONObject("properties").getJSONObject("fact").getString("type"))
        assertEquals("fact", params.getJSONArray("required").getString(0))
    }

    @Test
    fun parsesAudioTranscriptsToolCallsAndTurnEnd() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val b64 = Base64.getEncoder().encodeToString(pcm)
        val content = """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}]},
            "inputTranscription":{"text":"que horas são"},"outputTranscription":{"text":"São dez"},"turnComplete":true}}"""
        val events = LiveProtocol.parse(content)
        assertEquals(LiveEvent.UserText("que horas são"), events[0])
        assertArrayEquals(pcm, (events[1] as LiveEvent.Audio).pcm)
        assertEquals(LiveEvent.ModelText("São dez"), events[2])
        assertEquals(LiveEvent.TurnDone, events[3])

        val call = LiveProtocol.parse("""{"toolCall":{"functionCalls":[{"id":"c1","name":"agenda","args":{"dia":"hoje"}}]}}""")
        val tool = call.single() as LiveEvent.ToolCall
        assertEquals("c1", tool.id)
        assertEquals("agenda", tool.name)
        assertEquals("hoje", tool.args.getString("dia"))

        assertEquals(listOf(LiveEvent.SetupDone), LiveProtocol.parse("""{"setupComplete":{}}"""))
        assertEquals(listOf(LiveEvent.Interrupted), LiveProtocol.parse("""{"serverContent":{"interrupted":true}}"""))
        assertTrue(LiveProtocol.parse("not json").isEmpty())

        val reply = JSONObject(LiveProtocol.toolResponse("c1", "agenda", "Nada hoje"))
            .getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("c1", reply.getString("id"))
        assertEquals("Nada hoje", reply.getJSONObject("response").getString("result"))
    }

    @Test
    fun duplexGroupsFramesAndHoldsEchoWhilePlaying() {
        val duplex = LiveDuplex(bargeRms = 3_000.0, bargeFrames = 3, chunkFrames = 5, tailFrames = 2)
        val quiet = ShortArray(320) { 200 }
        repeat(4) { assertTrue(duplex.push(quiet, 320, playing = false) is LiveDuplex.Out.Hold) }
        val sent = duplex.push(quiet, 320, playing = false)
        assertTrue(sent is LiveDuplex.Out.Send)
        assertEquals(5 * 640, (sent as LiveDuplex.Out.Send).pcm.size)

        // Aura talking: her echo (moderate level) never goes out.
        val echo = ShortArray(320) { 1_500 }
        repeat(20) { assertTrue(duplex.push(echo, 320, playing = true) is LiveDuplex.Out.Hold) }
        // Short tail after playback is still held.
        assertTrue(duplex.push(echo, 320, playing = false) is LiveDuplex.Out.Hold)
    }

    @Test
    fun loudVoiceWhilePlayingIsBargeIn() {
        val duplex = LiveDuplex(bargeRms = 3_000.0, bargeFrames = 3, chunkFrames = 5, tailFrames = 2)
        val loud = ShortArray(320) { 6_000 }
        val softEcho = ShortArray(320) { 800 }
        repeat(15) { assertTrue(duplex.push(softEcho, 320, playing = true) is LiveDuplex.Out.Hold) }
        assertTrue(duplex.push(loud, 320, playing = true) is LiveDuplex.Out.Hold)
        assertTrue(duplex.push(loud, 320, playing = true) is LiveDuplex.Out.Hold)
        val out = duplex.push(loud, 320, playing = true)
        assertTrue(out is LiveDuplex.Out.BargeIn)
        // The held frames are released so the first words are not lost.
        assertEquals(6 * 640, (out as LiveDuplex.Out.BargeIn).pcm.size)
    }

    @Test
    fun auraOwnEchoNeverInterruptsHer() {
        val duplex = LiveDuplex(bargeRms = 3_000.0, bargeFrames = 12)
        // Her voice comes back loud in the glasses microphone: 5000 RMS for 3 s.
        val echo = ShortArray(320) { if (it % 2 == 0) 5_000 else -5_000 }
        repeat(150) { assertTrue(duplex.push(echo, 320, playing = true) is LiveDuplex.Out.Hold) }
        assertTrue(duplex.threshold > 10_000)
        // The user talking over her, much louder and close to the mic, does get through.
        val user = ShortArray(320) { if (it % 2 == 0) 16_000 else -16_000 }
        var out: LiveDuplex.Out = LiveDuplex.Out.Hold
        repeat(12) { out = duplex.push(user, 320, playing = true) }
        assertTrue(out is LiveDuplex.Out.BargeIn)
    }

    @Test
    fun voicePrintCanVetoAnInterruption() {
        val duplex = LiveDuplex(bargeRms = 3_000.0, bargeFrames = 4, isUser = { false })
        val tv = ShortArray(320) { 9_000 }
        repeat(40) { assertTrue(duplex.push(tv, 320, playing = true) is LiveDuplex.Out.Hold) }
    }
}
