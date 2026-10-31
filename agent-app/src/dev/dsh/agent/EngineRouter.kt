package dev.dsh.agent

import org.json.JSONObject

internal object EngineRouter {
    @JvmStatic
    fun handles(method: String): Boolean = UiEngine.handles(method) || DeviceBridge.handles(method)

    @JvmStatic
    fun dispatch(method: String, params: JSONObject): Any? =
        if (UiEngine.handles(method)) UiEngine.get().call(method, params)
        else DeviceBridge.call(method, params)
}
