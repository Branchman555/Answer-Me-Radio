package radio.reply.demo

import radio.reply.context.ContextJson
import radio.reply.dj.*

enum class FakeDjResult { SUCCESS, ERROR, INVALID_JSON, NEVER }

/** Deterministic local stand-in for POST /api/v1/dj/scripts. Makes no network calls. */
class FakeDjScriptProvider {
    var result = FakeDjResult.SUCCESS
    var delayMillis = 0L
    val requestBodies = mutableListOf<String>()
    fun response(request: DjRequest, simulation: Boolean): String? {
        requestBodies += ContextJson.encode(request)
        return when (result) {
            FakeDjResult.SUCCESS -> ContextJson.encode(LocalDjScripts.mock(request, simulation))
            FakeDjResult.INVALID_JSON -> "<invalid-json>"
            FakeDjResult.ERROR, FakeDjResult.NEVER -> null
        }
    }
}
