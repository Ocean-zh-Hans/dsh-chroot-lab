import json
import socket
import tempfile
import threading
import unittest
from pathlib import Path

# v2 客户端已归档到 docs/archive/agent-v2/（目录名含连字符无法常规导入），
# 测试仍保留其线上格式回归；用 importlib 按路径加载。
import importlib.util as _ilu
from pathlib import Path as _Path
_spec = _ilu.spec_from_file_location("dsh_agent", _Path(__file__).resolve().parent.parent / "docs/archive/agent-v2/dsh_agent.py")
_mod = _ilu.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(_mod)
AgentClient = _mod.AgentClient
AgentError = _mod.AgentError
_request_timeout = _mod._request_timeout


class MockAgent:
    def __init__(self, token):
        self.token = token
        self.listener = socket.socket()
        self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen()
        self.port = self.listener.getsockname()[1]
        self.clicks = 0
        self.pings = 0
        self.thread = threading.Thread(target=self.serve, daemon=True)
        self.thread.start()

    def serve(self):
        while True:
            try:
                conn, _ = self.listener.accept()
            except OSError:
                return
            threading.Thread(target=self.handle, args=(conn,), daemon=True).start()

    def handle(self, conn):
        with conn:
            stream = conn.makefile("rb")
            while True:
                line = stream.readline()
                if not line:
                    return
                request = json.loads(line)
                assert request["token"] == self.token
                method = request["method"]
                if method == "ping":
                    self.pings += 1
                    result = {"service": True, "port": 17321}
                elif method == "ui.click":
                    self.clicks += 1
                    result = {"performed": True}
                    if request["params"].get("idempotencyKey"):
                        result["replayed"] = self.clicks > 1
                elif method == "notifications.list":
                    result = []
                elif method == "events.subscribe":
                    self.last_subscribe = request
                    conn.sendall(json.dumps({"id": request["id"], "ok": True, "result": {
                        "subscribed": True, "bootId": "test-boot", "baseSeq": 1, "resetRequired": False}}).encode() + b"\n")
                    self.last_event = {"event": "ui.changed", "seq": 2, "data": {}}
                    conn.sendall(json.dumps(self.last_event).encode() + b"\n")
                    continue
                elif method == "events.unsubscribe":
                    conn.sendall(json.dumps({"event": "battery", "seq": 3, "data": {}}).encode() + b"\n")
                    result = {"unsubscribed": True}
                elif method == "missing.permission":
                    conn.sendall(json.dumps({"id": request["id"], "ok": False,
                        "error": {"code": "UNAVAILABLE", "message": "unsupported"}}).encode() + b"\n")
                    continue
                else:
                    result = {"ok": True}
                conn.sendall(json.dumps({"id": request["id"], "ok": True, "result": result, "via": "a11y"}).encode() + b"\n")

    def close(self):
        try:
            self.listener.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.listener.close()
        self.thread.join(timeout=1)


class AgentClientTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.token = "a" * 32
        Path(self.temp.name, "token").write_text(self.token)
        self.server = MockAgent(self.token)
        self.client = AgentClient(Path(self.temp.name, "token"), port=self.server.port)

    def tearDown(self):
        self.client.close()
        self.server.close()
        self.temp.cleanup()

    def test_ping_and_array_compatibility(self):
        self.assertTrue(self.client.call("ping")["service"])
        self.assertEqual(self.client.call("notifications.list"), [])

    def test_events_and_idempotent_unsubscribe(self):
        stream = self.client.subscribe(("ui.changed",), boot_id="test-boot", packages=("mark.via",))
        self.assertEqual(stream.read()["event"], "ui.changed")
        self.assertEqual(stream.last_seq, 2)
        self.assertEqual(self.server.last_subscribe["params"]["processId"], "test-boot")
        self.assertEqual(self.server.last_subscribe["params"]["packages"], ["mark.via"])
        self.assertNotIn("id", self.server.last_event)
        self.assertNotIn("ok", self.server.last_event)
        self.assertEqual(stream.process_id, "test-boot")
        self.assertTrue(stream.unsubscribe()["unsubscribed"])
        self.assertTrue(stream.unsubscribe()["unsubscribed"])

    def test_idempotency_metadata_is_preserved(self):
        result = self.client.call("ui.click", {"text": "safe"}, idempotency_key="test-key")
        self.assertTrue(result["performed"])
        result = self.client.call("ui.click", {"text": "safe"}, idempotency_key="test-key")
        self.assertTrue(result["replayed"])

    def test_shot_is_not_required_for_protocol(self):
        with self.assertRaises(AgentError):
            self.client.call("missing.permission")

    def test_operation_timeout_budgets(self):
        self.assertEqual(_request_timeout(15, "ping", {}), 15)
        self.assertEqual(_request_timeout(15, "backend.selftest", {}), 60)
        self.assertEqual(_request_timeout(15, "prep.automation", {}), 130)
        self.assertEqual(_request_timeout(15, "prep.restore", {}), 130)
        self.assertEqual(_request_timeout(15, "screen.record", {"durationMs": 30000}), 50)
        self.assertEqual(_request_timeout(90, "screen.record", {"durationMs": 1000}), 90)
        self.assertEqual(_request_timeout(15, "shell.exec", {"timeoutMs": 60000}), 65)

    def test_client_applies_operation_timeout(self):
        timeouts = []
        connect = self.client._socket

        class RecordingSocket:
            def __init__(self, sock):
                self.sock = sock

            def __enter__(self):
                return self

            def __exit__(self, *_):
                self.sock.close()

            def settimeout(self, value):
                timeouts.append(value)
                self.sock.settimeout(value)

            def sendall(self, data):
                return self.sock.sendall(data)

            def makefile(self, mode):
                return self.sock.makefile(mode)

        def recording_socket():
            return RecordingSocket(connect())

        self.client._socket = recording_socket
        self.client.call("prep.automation")
        self.assertEqual(timeouts[-1], 130)


if __name__ == "__main__":
    unittest.main()
