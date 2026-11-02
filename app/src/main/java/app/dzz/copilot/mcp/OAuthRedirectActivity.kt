package app.dzz.copilot.mcp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.dzz.copilot.MainActivity
import app.dzz.copilot.hub

/** Receives app.dzz.copilot://oauth?code=… after an MCP login and returns to the app. */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { hub().mcp.finishLogin(it) }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
