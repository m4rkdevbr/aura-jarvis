package app.dzz.copilot.copilot

import android.util.Log
import app.dzz.copilot.brain.ChatModel
import app.dzz.copilot.brain.LocalTurns
import app.dzz.copilot.brain.ModelTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** The brain's turn on Gemma 4 inside the app, with only the tools that fit the request. */
class GemmaChatModel(private val gemma: () -> GemmaLocal?) : ChatModel {
    override suspend fun turn(messages: JSONArray, tools: JSONArray): ModelTurn? = withContext(Dispatchers.IO) {
        val engine = gemma() ?: return@withContext null
        val plan = LocalTurns.plan(messages) ?: return@withContext null
        val picked = LocalTurns.pickTools(
            tools,
            LocalTurns.lastUserText(messages),
            MAX_TOOLS,
            called = plan.history.flatMap { turn -> turn.calls.map { it.name } }.toSet(),
        )
        val (text, calls) = engine.turn(plan, picked) ?: return@withContext null
        Log.i("AuraGemma", "turno local: ${text.length} letras, ferramentas ${calls.map { it.name }} (${picked.size} oferecidas)")
        if (text.isBlank() && calls.isEmpty()) null else LocalTurns.turn(text, calls)
    }

    private companion object {
        /** A small model picks better, and much faster, from a short list. */
        const val MAX_TOOLS = 10
    }
}
