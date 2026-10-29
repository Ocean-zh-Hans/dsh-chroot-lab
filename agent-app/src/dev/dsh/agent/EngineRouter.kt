package dev.dsh.agent

import org.json.JSONObject

internal object EngineRouter {
    @JvmStatic
    fun handles(method: String): Boolean = UiEngine.handles(method)

    @JvmStatic
    fun dispatch(method: String, params: JSONObject): Any? = UiEngine.get().call(method, params)
}
