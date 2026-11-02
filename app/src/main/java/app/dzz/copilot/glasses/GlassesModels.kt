package app.dzz.copilot.glasses

enum class LinkPhase {
    Idle,
    Scanning,
    Connecting,
    Discovering,
    /** BLE up, but no QC / alternate write channel for commands. */
    Linked,
    Ready,
    Failed,
}

data class ScanHit(
    val name: String,
    val address: String,
    val rssi: Int,
    val likely: Boolean,
    val audioRoute: Boolean = false,
)

data class GlassesSnapshot(
    val phase: LinkPhase = LinkPhase.Idle,
    val status: String = "Desconectado",
    val name: String? = null,
    val address: String? = null,
    val battery: Int? = null,
    val charging: Boolean = false,
    val firmware: String? = null,
    val qcChannel: Boolean = false,
    val alternateChannel: Boolean = false,
    val mode: String = "parado",
    val audioPackets: Int = 0,
    val wifiSsid: String? = null,
    val wifiPassword: String? = null,
    val wifiIp: String? = null,
    val lastError: String? = null,
)
