package online.mmpg.remote.protocol

import org.json.JSONObject

data class ProtocolResult(
    val ok: Boolean,
    val code: String,
    val message: String
) {
    override fun toString(): String = JSONObject().apply {
        put("ok", ok)
        put("code", code)
        put("message", message)
    }.toString()
}
