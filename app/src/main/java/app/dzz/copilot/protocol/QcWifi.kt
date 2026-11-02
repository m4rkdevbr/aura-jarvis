package app.dzz.copilot.protocol

data class WifiHint(
    val ssid: String? = null,
    val password: String? = null,
    val ip: String? = null,
) {
    fun merge(other: WifiHint) = WifiHint(
        ssid = other.ssid ?: ssid,
        password = other.password ?: password,
        ip = other.ip ?: ip,
    )
}

object QcWifi {
    fun parse(cmd: Int, payload: ByteArray): WifiHint {
        var hint = WifiHint()
        hint = hint.merge(parseTransfer(payload))
        if (cmd == QcProtocol.CONTROL && payload.size >= 6 && payload[0] == 2.toByte() && payload[1] == 3.toByte()) {
            hint = hint.merge(WifiHint(ip = ipv4(payload, 2)))
        }
        if (cmd == QcProtocol.IP_NOTIFY) {
            hint = hint.merge(WifiHint(ip = ipv4(payload, 1) ?: ipv4(payload, 0)))
        }
        return hint
    }

    private fun parseTransfer(payload: ByteArray): WifiHint {
        if (payload.size < 8) return WifiHint()
        if (payload[0] != 2.toByte() || payload[1] != 1.toByte() || payload[2] != 4.toByte()) return WifiHint()
        val ssidLength = le(payload, 4)
        val passLength = le(payload, 6)
        if (ssidLength !in 1..48 || passLength !in 0..48) return WifiHint()
        if (payload.size < 8 + ssidLength + passLength) return WifiHint()
        val ssid = payload.decode(8, ssidLength)
        val password = payload.decode(8 + ssidLength, passLength)
        if (ssid.isBlank()) return WifiHint()
        return WifiHint(ssid = ssid, password = password.ifBlank { null })
    }

    fun ipv4(payload: ByteArray, offset: Int): String? {
        if (offset < 0 || offset + 4 > payload.size) return null
        val parts = IntArray(4) { payload[offset + it].toInt() and 0xFF }
        if (parts[0] == 0 || parts[0] >= 224) return null
        return parts.joinToString(".")
    }

    private fun le(payload: ByteArray, offset: Int): Int {
        return (payload[offset].toInt() and 0xFF) or ((payload[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun ByteArray.decode(offset: Int, length: Int): String {
        return copyOfRange(offset, offset + length).toString(Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
    }
}
