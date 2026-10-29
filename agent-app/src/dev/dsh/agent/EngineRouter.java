package dev.dsh.agent;

import org.json.JSONObject;

/** v3 引擎入口：把 ui.view / ui.act 这类新方法转给 Kotlin 引擎。 */
final class EngineRouter {
    private EngineRouter() {
    }

    static boolean handles(String method) {
        return UiEngine.handles(method);
    }

    static Object dispatch(String method, JSONObject params) throws Exception {
        return UiEngine.get().call(method, params);
    }
}
