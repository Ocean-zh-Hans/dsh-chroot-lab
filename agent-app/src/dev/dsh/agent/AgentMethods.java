package dev.dsh.agent;

import java.util.HashSet;
import java.util.Set;

final class AgentMethods {
    static final int API_VERSION = 2;
    static final String VERSION = "0.2.0";
    static final String BUILD_TIME = "2026-10-29";

    private static final Set<String> WRITES = new HashSet<>(java.util.Arrays.asList(
            "ui.click", "ui.longClick", "ui.doubleTap", "ui.gesture", "ui.swipe", "ui.drag", "ui.fling",
            "ui.setText", "ui.appendText", "ui.clearText", "ui.selectAll", "ui.copy", "ui.paste", "ui.cut",
            "ui.key", "ui.global", "ui.scrollUntil", "ui.highlight", "ime.show", "ime.hide", "ime.set",
            "clipboard.set", "clipboard.clear",
            "activity.start", "app.openUrl", "intent.send", "settings.put", "prep.automation", "prep.restore",
            "power.wake", "power.sleep", "power.reboot", "lock.unlock", "net.wifi", "net.data", "net.airplane",
            "display.setRotation", "display.setDensity", "dnd.set", "volume.set", "volume.mute", "location.mock",
            "media.play", "media.pause", "media.next", "media.prev", "notifications.dismiss", "notifications.click",
            "notifications.reply", "call.dial", "fs.put", "fs.mkdir", "fs.rm", "share.file", "shell.exec",
            "security.setMode", "security.tokenRotate", "security.tokenPolicy"));

    private AgentMethods() {
    }

    static boolean isMutation(String method) {
        if (WRITES.contains(method) || method.equals("screen.record") || method.equals("sms.send")) return true;
        if (method.equals("package.list") || method.equals("settings.get") || method.equals("power.state") || method.equals("net.status")) return false;
        return method.startsWith("package.") || method.startsWith("perm.") || method.startsWith("task.remove")
                || method.startsWith("task.split") || method.startsWith("task.pip");
    }
}
