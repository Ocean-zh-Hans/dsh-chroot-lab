#!/usr/bin/env python3
"""
phone-mcp — DSH-side phone facade for the v3 Agent APK.

The server is deliberately small: it is the cross-backend seam, not a second
agent. It keeps one APK session alive, exposes semantic phone.* tools over MCP
stdio, and marks page state stale after a shell action (phone.open) so the next
APK action cannot use an old ref.

Protocol: newline-delimited JSON-RPC 2.0 on stdin/stdout (MCP stdio).
No third-party dependencies.
"""
from __future__ import annotations

import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
import uuid
from typing import Any, Dict, List, Optional

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 17321
DEFAULT_TOKEN_FILE = "/data/data/dev.dsh.agent/files/token"
MAX_LINE = 20 * 1024 * 1024
SOCKET_TIMEOUT = 180.0
PACKAGE_RE = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_.]*$")
ACTIVITY_RE = re.compile(r"^[A-Za-z0-9_.$]+(?:/[A-Za-z0-9_.$]+)?$")


class PhoneError(Exception):
    def __init__(
        self,
        code: str,
        message: str,
        detail: Optional[Dict[str, Any]] = None,
        via: str = "none",
        retryable: bool = False,
        result_state: str = "",
        replayed: bool = False,
        took_ms: int = 0,
        hint: str = "",
    ):
        super().__init__(message)
        self.code = code
        self.message = message
        self.detail = detail or {}
        self.via = via
        self.retryable = retryable
        self.result_state = result_state
        self.replayed = replayed
        self.took_ms = took_ms
        self.hint = hint

    def as_dict(self) -> Dict[str, Any]:
        out: Dict[str, Any] = {"code": self.code, "message": self.message, "detail": self.detail}
        if self.via and self.via != "none":
            out["via"] = self.via
        if self.retryable:
            out["retryable"] = True
        if self.result_state:
            out["resultState"] = self.result_state
        if self.replayed:
            out["replayed"] = True
        if self.took_ms:
            out["tookMs"] = self.took_ms
        if self.hint:
            out["hint"] = self.hint
        return out


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _text(value: Any) -> List[Dict[str, Any]]:
    return [{"type": "text", "text": value if isinstance(value, str) else _json(value)}]


class PhoneClient:
    """One persistent JSONL connection to the APK, plus one APK session."""

    def __init__(
        self,
        host: str = DEFAULT_HOST,
        port: int = DEFAULT_PORT,
        token_file: str = DEFAULT_TOKEN_FILE,
    ):
        self.host = host
        self.port = port
        self.token_file = token_file
        self._sock: Optional[socket.socket] = None
        self._token: str = ""
        self._next_id = 0
        self._lock = threading.RLock()
        self.session_id: Optional[str] = None
        self.page: Optional[Dict[str, Any]] = None

    # ------------------------------------------------------------- transport
    def _read_token(self) -> str:
        try:
            with open(self.token_file, "r", encoding="utf-8") as fh:
                token = fh.read().strip()
        except OSError as exc:
            raise PhoneError(
                "TOKEN_MISSING",
                "cannot read Agent token: " + self.token_file,
                {"error": str(exc), "fix": "start the Agent app or set DSH_PHONE_TOKEN_FILE"},
            )
        if not token:
            raise PhoneError("TOKEN_MISSING", "Agent token is empty: " + self.token_file)
        return token

    def _connect(self) -> None:
        if self._sock is not None:
            return
        token = self._read_token()
        try:
            sock = socket.create_connection((self.host, self.port), timeout=5.0)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.settimeout(SOCKET_TIMEOUT)
        except OSError as exc:
            raise PhoneError(
                "CONNECTION_FAILED",
                "cannot connect to Agent APK at %s:%s" % (self.host, self.port),
                {"error": str(exc), "fix": "open DSH Agent and check sys.status"},
            )
        self._sock = sock
        self._token = token

    def _disconnect(self) -> None:
        sock, self._sock = self._sock, None
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass

    def _readline(self) -> str:
        assert self._sock is not None
        chunks = bytearray()
        while True:
            try:
                chunk = self._sock.recv(65536)
            except (OSError, socket.timeout) as exc:
                self._disconnect()
                raise PhoneError("CONNECTION_LOST", "Agent connection lost", {"error": str(exc)})
            if not chunk:
                self._disconnect()
                raise PhoneError("CONNECTION_LOST", "Agent closed the connection")
            chunks.extend(chunk)
            if len(chunks) > MAX_LINE:
                self._disconnect()
                raise PhoneError("OUTPUT_TOO_LARGE", "Agent response exceeds %d bytes" % MAX_LINE)
            if b"\n" in chunks:
                line, _, rest = chunks.partition(b"\n")
                # A JSONL response is one line; rest should not contain a full
                # response for this protocol, but keep it simple and reconnect
                # if a server ever pipelines.
                return line.decode("utf-8", "replace")

    def _request(self, method: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        self._connect()
        self._next_id += 1
        request_id = self._next_id
        payload = {
            "id": request_id,
            "token": self._token,
            "method": method,
            "params": params or {},
        }
        try:
            assert self._sock is not None
            self._sock.sendall((_json(payload) + "\n").encode("utf-8"))
        except (OSError, socket.timeout) as exc:
            self._disconnect()
            raise PhoneError("CONNECTION_LOST", "cannot send request to Agent", {"error": str(exc)})
        line = self._readline()
        try:
            response = json.loads(line)
        except json.JSONDecodeError as exc:
            self._disconnect()
            raise PhoneError("BAD_RESPONSE", "Agent returned invalid JSON", {"error": str(exc)})
        if response.get("id") != request_id:
            # A late event or stale response. The APK uses one request per
            # connection, so treat it as a protocol error.
            raise PhoneError("BAD_RESPONSE", "Agent response id mismatch", {"expected": request_id, "got": response.get("id")})
        if not response.get("ok"):
            error = response.get("error") or {}
            raise PhoneError(
                str(error.get("code") or "AGENT_ERROR"),
                str(error.get("message") or "Agent request failed"),
                error.get("detail") if isinstance(error.get("detail"), dict) else {},
                via=str(response.get("via") or "none"),
                retryable=bool(error.get("retryable")),
                result_state=str(response.get("resultState") or ""),
                replayed=bool(response.get("replayed")),
                took_ms=int(response.get("tookMs") or 0),
                hint=str(error.get("hint") or ""),
            )
        result = response.get("result")
        return result if isinstance(result, dict) else {"value": result}

    # ------------------------------------------------------------- session
    def _ensure_session(self) -> None:
        if self.session_id:
            return
        listed = self._request("session.list", {})
        for item in listed.get("sessions") or []:
            if isinstance(item, dict) and item.get("name") == "dsh":
                self.session_id = str(item.get("id"))
                self._request("session.use", {"id": self.session_id})
                return
        opened = self._request("session.open", {"name": "dsh", "backend": "auto"})
        session = opened.get("session") or {}
        session_id = session.get("id") or opened.get("current")
        if not session_id:
            raise PhoneError("SESSION_FAILED", "Agent did not return a session id", {"result": opened})
        self.session_id = str(session_id)

    def call(self, method: str, params: Optional[Dict[str, Any]] = None, session: bool = True) -> Dict[str, Any]:
        with self._lock:
            if session and not self.session_id:
                self._ensure_session()
            payload = dict(params or {})
            if session:
                payload["session"] = self.session_id
            try:
                return self._request(method, payload)
            except PhoneError as exc:
                # token rotation: reconnect once with a freshly-read token.
                if exc.code == "UNAUTHORIZED":
                    self._disconnect()
                    return self._request(method, payload)
                # The APK keeps sessions in memory. If it restarted, reopen once.
                if session and exc.code == "NOT_FOUND" and "session" in exc.message.lower():
                    self.session_id = None
                    self._ensure_session()
                    payload["session"] = self.session_id
                    return self._request(method, payload)
                raise

    # ------------------------------------------------------------- semantic
    @staticmethod
    def _page_of(result: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        page = result.get("page")
        return page if isinstance(page, dict) else None

    def observe(self, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        with self._lock:
            payload = dict(params or {})
            payload.setdefault("maxChars", 2500)
            result = self.call("ui.view", payload)
            self.page = self._page_of(result) or self.page
            return result

    def act(self, params: Dict[str, Any]) -> Dict[str, Any]:
        with self._lock:
            result = self.call("ui.act", params)
            self.page = self._page_of(result) or self.page
            return result

    def wait(self, params: Dict[str, Any]) -> Dict[str, Any]:
        with self._lock:
            result = self.call("ui.wait", params)
            self.page = self._page_of(result) or self.page
            return result

    def fill(self, params: Dict[str, Any]) -> Dict[str, Any]:
        with self._lock:
            result = self.call("ui.fill", params)
            self.page = self._page_of(result) or self.page
            return result

    def gesture(self, params: Dict[str, Any]) -> Dict[str, Any]:
        with self._lock:
            result = self.call("ui.gesture", params)
            self.page = self._page_of(result) or self.page
            return result

    def invalidate(self) -> Dict[str, Any]:
        with self._lock:
            result = self.call("ui.invalidate", {})
            self.page = None
            return result

    def open_app(self, package: str, activity: Optional[str] = None, timeout_ms: int = 20000) -> Dict[str, Any]:
        if not PACKAGE_RE.match(package):
            raise PhoneError("INVALID_PARAMS", "invalid package name: " + package)
        if activity and not ACTIVITY_RE.match(activity):
            raise PhoneError("INVALID_PARAMS", "invalid activity name: " + activity)
        if activity:
            cmd = ["am", "start", "--user", "0", "-W", "-n", package + "/" + activity]
        else:
            cmd = ["monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1"]
        try:
            proc = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        except FileNotFoundError:
            raise PhoneError("UNAVAILABLE", "Android shell command is not on PATH", {"command": cmd[0]})
        except subprocess.TimeoutExpired:
            raise PhoneError("TIMEOUT", "launch command timed out", {"command": " ".join(cmd)})
        if proc.returncode != 0:
            raise PhoneError(
                "SHELL_FAILED",
                "launch command failed: " + " ".join(cmd),
                {"stdout": proc.stdout[-2000:], "stderr": proc.stderr[-2000:], "code": proc.returncode},
            )
        # Shell action may have changed the page; never let an old APK ref cross it.
        self.invalidate()
        spec: Dict[str, Any] = {"package": package}
        if activity:
            spec["activity"] = activity
        wait = self.wait({"for": spec, "timeoutMs": max(500, min(int(timeout_ms), 60000))})
        observation = self.observe({"maxChars": 2500})
        return {
            "ok": True,
            "package": package,
            "activity": activity or "",
            "shell": (proc.stdout or proc.stderr or "").strip()[-2000:],
            "wait": wait,
            "observation": observation,
        }

    def status(self) -> Dict[str, Any]:
        return self.call("sys.status", {})

    def notify(self, op: str, args: Dict[str, Any]) -> Dict[str, Any]:
        if op == "list":
            return self.call("notif.list", {})
        if op == "history":
            return self.call("notif.history", {"limit": int(args.get("limit", 50))})
        if op == "dismiss":
            if not args.get("key"):
                raise PhoneError("INVALID_PARAMS", "notify dismiss requires key")
            return self.call("notif.dismiss", {"key": args["key"]})
        if op == "reply":
            if not args.get("key") or not args.get("text"):
                raise PhoneError("INVALID_PARAMS", "notify reply requires key and text")
            return self.call("notif.reply", {"key": args["key"], "text": args["text"]})
        raise PhoneError("INVALID_PARAMS", "notify op must be list|history|dismiss|reply")

    def clipboard(self, op: str, args: Dict[str, Any]) -> Dict[str, Any]:
        payload = {"op": op}
        if op == "set":
            payload["text"] = args.get("text", "")
        return self.call("ui.clipboard", payload)

    def handoff(self, action: str) -> Dict[str, Any]:
        if action == "request":
            return self.call("handoff.request", {"reason": ""})
        if action == "status":
            return self.status()
        raise PhoneError("UNSUPPORTED", "handoff resume is a human action in the APK notification", {"action": action})


class PhoneServer:
    def __init__(self) -> None:
        self._do_depth = 0
        self.client = PhoneClient(
            host=os.environ.get("DSH_PHONE_HOST", DEFAULT_HOST),
            port=int(os.environ.get("DSH_PHONE_PORT", str(DEFAULT_PORT))),
            token_file=os.environ.get("DSH_PHONE_TOKEN_FILE", DEFAULT_TOKEN_FILE),
        )

    # ------------------------------------------------------------- tools
    def tools(self) -> List[Dict[str, Any]]:
        return [
            {
                "name": "observe",
                "description": "读取当前手机屏幕：结构化无障碍树、前台包/Activity、键盘、可选截图。开始任何手机任务先调用它；返回 page(g,seq)，后续 ref 只在同一 page 内有效。",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "maxChars": {"type": "integer", "default": 2500, "description": "树文本上限"},
                        "mode": {"type": "string", "description": "auto|full|compact"},
                        "screenshot": {"type": "boolean", "default": False, "description": "同一帧附 JPEG 截图"},
                        "windows": {"type": "boolean", "default": False, "description": "附窗口列表"},
                    },
                },
            },
            {
                "name": "act",
                "description": "在 ref 或 where 上执行一个 UI 动作（tap/longClick/setText/appendText/clearText/focus/scroll/swipe/drag/press/global/copy/cut/paste/dismiss/scrollIntoView/setSelection/doubleTap）。返回 page 与 change；旧 ref 会得到 STALE_PAGE。",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "op": {"type": "string", "description": "动作名"},
                        "ref": {"type": "string", "description": "@e123，来自 observe"},
                        "where": {"type": "object", "description": "Query.where 条件"},
                        "text": {"type": "string"},
                        "key": {"type": "string"},
                        "x1": {"type": "integer"}, "y1": {"type": "integer"},
                        "x2": {"type": "integer"}, "y2": {"type": "integer"},
                        "durationMs": {"type": "integer"},
                        "direction": {"type": "string"},
                        "percent": {"type": "number"},
                        "amount": {"type": "integer"},
                        "page": {"type": "object", "description": "期望的 page；不匹配直接 STALE_PAGE，不发动作"},
                    },
                    "required": ["op"],
                },
            },
            {
                "name": "fill",
                "description": "聚焦输入框、填值、可选提交并读回结果；比 act(setText) 多 settle/校验。",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "ref": {"type": "string"},
                        "where": {"type": "object"},
                        "text": {"type": "string"},
                        "append": {"type": "boolean", "default": False},
                        "submit": {"type": "boolean", "default": False},
                        "page": {"type": "object"},
                    },
                    "required": ["text"],
                },
            },
            {
                "name": "expect",
                "description": "零等待断言：检查 text/textGone/ref/gone/package/activity/keyboard；失败返回候选，不 sleep。",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "for": {"type": "object", "description": "条件对象"},
                        "text": {"type": "string"}, "textGone": {"type": "string"},
                        "ref": {"type": "string"}, "gone": {"type": "string"},
                        "package": {"type": "string"}, "activity": {"type": "string"},
                        "keyboard": {"type": "boolean"},
                    },
                },
            },
            {
                "name": "wait",
                "description": "等待页面条件（text/textGone/ref/gone/package/activity/keyboard/idle），返回新的 page；用于 phone.open 之后或跨后端动作之后。",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "for": {"type": "object"},
                        "text": {"type": "string"}, "textGone": {"type": "string"},
                        "ref": {"type": "string"}, "gone": {"type": "string"},
                        "package": {"type": "string"}, "activity": {"type": "string"},
                        "keyboard": {"type": "boolean"}, "idle": {"type": "boolean"},
                        "timeoutMs": {"type": "integer", "default": 8000},
                    },
                },
            },
            {
                "name": "gesture",
                "description": "多指手势：paths 或 preset（pinchIn/pinchOut/twoFingerSwipe）。必须先传 observe 返回的 page。",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "page": {"type": "object", "description": "observe 返回的 page；g/seq 不匹配直接 STALE_PAGE"},
                        "paths": {"type": "array", "items": {"type": "object"}},
                        "preset": {"type": "string"},
                        "direction": {"type": "string"},
                        "center": {"type": "array", "items": {"type": "number"}},
                        "radius": {"type": "number"}, "scale": {"type": "number"},
                    },
                },
            },
            {
                "name": "notify",
                "description": "通知：list 当前、history 历史（最多100，含已移除）、dismiss 按 key、reply 按 key+text。",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "op": {"type": "string", "enum": ["list", "history", "dismiss", "reply"]},
                        "key": {"type": "string"}, "text": {"type": "string"},
                        "limit": {"type": "integer", "default": 50},
                    },
                    "required": ["op"],
                },
            },
            {
                "name": "clipboard",
                "description": "剪贴板：get/set/clear/history；history 只在 APK 进程内，最多30条。",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "op": {"type": "string", "enum": ["get", "set", "clear", "history"]},
                        "text": {"type": "string"},
                    },
                    "required": ["op"],
                },
            },
            {
                "name": "open",
                "description": "启动 App/Activity 并等待页面可用；内部用 shell 启动 + APK wait，模型不需要选后端。",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "package": {"type": "string"},
                        "activity": {"type": "string", "description": "可选，例如 .MainActivity"},
                        "timeoutMs": {"type": "integer", "default": 20000},
                    },
                    "required": ["package"],
                },
            },
            {
                "name": "status",
                "description": "Agent/APK 状态：前台包/Activity、page、无障碍/通知/平台能力、paused。",
                "inputSchema": {"type": "object", "properties": {}},
            },
            {
                "name": "handoff",
                "description": "请求人类接管或查询状态；resume 必须由人点通知，工具不能代替。",
                "inputSchema": {
                    "type": "object",
                    "properties": {"action": {"type": "string", "enum": ["request", "status"]}},
                    "required": ["action"],
                },
            },
            {
                "name": "done",
                "description": "标记当前任务完成/失败：更新人类可见的在场状态与任务回执。任务结束时调用。",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "title": {"type": "string", "description": "人类可读的任务名"},
                        "status": {"type": "string", "enum": ["ok", "failed", "cancelled"], "default": "ok"},
                        "taskId": {"type": "string"},
                        "steps": {"type": "array", "items": {"type": "object"}},
                        "error": {"type": "string"},
                    },
                    "required": ["title"],
                },
            },
            {
                "name": "receipt",
                "description": "查询任务回执（最近 N 条）。",
                "inputSchema": {
                    "type": "object",
                    "properties": {"limit": {"type": "integer", "default": 20}},
                },
            },
            {
                "name": "do",
                "description": "带 expect 检查点的语义步骤序列；每步是 {tool, args}，只做 phone.* 语义动作，不做通用宏。",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "steps": {"type": "array", "items": {"type": "object"}},
                    },
                    "required": ["steps"],
                },
            },
        ]

    def _page_arg(self, args: Dict[str, Any]) -> None:
        if not self.client.page:
            raise PhoneError("STALE_PAGE", "no page yet; call observe first")
        expected = args.get("page")
        if not isinstance(expected, dict):
            raise PhoneError("STALE_PAGE", "page is required; call observe first")
        if expected.get("g") != self.client.page.get("g") or expected.get("seq") != self.client.page.get("seq"):
            raise PhoneError(
                "STALE_PAGE",
                "page is stale; call observe again",
                {"expected": expected, "current": self.client.page},
            )

    def call_tool(self, name: str, args: Dict[str, Any]) -> Dict[str, Any]:
        try:
            if name == "observe":
                result = self.client.observe(args)
                text = str(result.get("text") or "")
                page = result.get("page") or {}
                if page:
                    text += "\n\n[page g=%s seq=%s pkg=%s activity=%s]" % (
                        page.get("g"), page.get("seq"), page.get("pkg", ""), page.get("activity", "")
                    )
                content = _text(text)
                shot = result.get("shot")
                if isinstance(shot, dict) and shot.get("data"):
                    content.append({"type": "image", "data": shot["data"], "mimeType": "image/jpeg"})
                return {"content": content}
            if name == "act":
                self._page_arg(args)
                payload = {k: v for k, v in args.items() if k != "page"}
                result = self.client.act(payload)
                return {"content": _text(result)}
            if name == "fill":
                self._page_arg(args)
                payload = {k: v for k, v in args.items() if k != "page"}
                result = self.client.fill(payload)
                return {"content": _text(result)}
            if name == "expect":
                spec = args.get("for") if isinstance(args.get("for"), dict) else {
                    k: v for k, v in args.items() if k != "for"
                }
                result = self.client.call("ui.expect", {"for": spec})
                return {"content": _text(result)}
            if name == "wait":
                spec = args.get("for") if isinstance(args.get("for"), dict) else {
                    k: v for k, v in args.items() if k not in ("for", "timeoutMs")
                }
                payload = {"for": spec, "timeoutMs": int(args.get("timeoutMs", 8000))}
                result = self.client.wait(payload)
                return {"content": _text(result)}
            if name == "gesture":
                self._page_arg(args)
                result = self.client.gesture({k: v for k, v in args.items() if k != "page"})
                return {"content": _text(result)}
            if name == "notify":
                op = str(args.get("op", ""))
                return {"content": _text(self.client.notify(op, args))}
            if name == "clipboard":
                op = str(args.get("op", ""))
                return {"content": _text(self.client.clipboard(op, args))}
            if name == "open":
                result = self.client.open_app(
                    str(args.get("package", "")),
                    str(args["activity"]) if args.get("activity") else None,
                    int(args.get("timeoutMs", 20000)),
                )
                return {"content": _text(result)}
            if name == "status":
                return {"content": _text(self.client.status())}
            if name == "handoff":
                return {"content": _text(self.client.handoff(str(args.get("action", ""))))}
            if name == "done":
                payload = {
                    "title": str(args.get("title", "")),
                    "status": str(args.get("status", "ok")),
                    "taskId": str(args.get("taskId", "")),
                    "steps": args.get("steps") or [],
                    "error": str(args.get("error", "")),
                }
                return {"content": _text(self.client.call("receipts.append", payload))}
            if name == "receipt":
                return {"content": _text(self.client.call("receipts.list", {"limit": int(args.get("limit", 20))}))}
            if name == "do":
                return {"content": _text(self._run_steps(args.get("steps") or []))}
            raise PhoneError("UNKNOWN_TOOL", "unknown tool: " + name)
        except PhoneError as exc:
            return {"isError": True, "content": _text({"ok": False, "error": exc.as_dict()})}
        except Exception as exc:  # keep the MCP stream alive
            return {"isError": True, "content": _text({"ok": False, "error": {"code": "INTERNAL", "message": str(exc)}})}

    def _run_steps(self, steps: Any) -> Dict[str, Any]:
        if not isinstance(steps, list) or len(steps) > 50:
            raise PhoneError("INVALID_PARAMS", "steps must be a list of at most 50 items")
        if self._do_depth >= 4:
            raise PhoneError("INVALID_PARAMS", "do nesting is limited to 4 levels")
        self._do_depth += 1
        completed: List[Dict[str, Any]] = []
        try:
            for index, step in enumerate(steps):
                if not isinstance(step, dict):
                    raise PhoneError("INVALID_PARAMS", "step %d is not an object" % index)
                tool = str(step.get("tool") or "")
                args = step.get("args") if isinstance(step.get("args"), dict) else {}
                if tool == "sleep":
                    time.sleep(max(0.0, min(float(args.get("ms", 0)) / 1000.0, 30.0)))
                    completed.append({"step": index, "tool": tool, "ok": True})
                    continue
                result = self.call_tool(tool, args)
                completed.append({"step": index, "tool": tool, "ok": not result.get("isError")})
                if result.get("isError"):
                    return {"ok": False, "failedStep": index, "completed": completed, "error": result}
            return {"ok": True, "completed": completed}
        finally:
            self._do_depth -= 1


def _read_message() -> Optional[Dict[str, Any]]:
    for raw in sys.stdin:
        raw = raw.strip()
        if not raw:
            continue
        try:
            message = json.loads(raw)
        except json.JSONDecodeError:
            continue
        if isinstance(message, dict):
            return message
    return None


def main() -> None:
    server = PhoneServer()
    for message in iter(_read_message, None):
        method = message.get("method")
        request_id = message.get("id")
        params = message.get("params") if isinstance(message.get("params"), dict) else {}
        if method == "initialize":
            result = {
                "protocolVersion": params.get("protocolVersion", "2024-11-05"),
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "phone", "version": "0.3.0"},
            }
        elif method == "tools/list":
            result = {"tools": server.tools()}
        elif method == "tools/call":
            result = server.call_tool(str(params.get("name", "")), params.get("arguments") or {})
        elif method == "ping":
            result = {}
        elif method in ("notifications/initialized", "notifications/cancelled"):
            continue
        else:
            if request_id is None:
                continue
            print(_json({"jsonrpc": "2.0", "id": request_id, "error": {"code": -32601, "message": "method not found: " + str(method)}}), flush=True)
            continue
        if request_id is None:
            continue
        print(_json({"jsonrpc": "2.0", "id": request_id, "result": result}), flush=True)


if __name__ == "__main__":
    main()
