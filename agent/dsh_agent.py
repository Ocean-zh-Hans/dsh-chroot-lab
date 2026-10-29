#!/usr/bin/env python3
"""Small standard-library client for the DSH Agent JSON-lines protocol."""
from __future__ import annotations

import base64
import json
import os
import socket
import subprocess
import uuid
import weakref
from pathlib import Path


class AgentError(RuntimeError):
    def __init__(self, code, message="agent request failed", detail=None, via=None, result_state=None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.detail = detail or {}
        self.via = via
        self.result_state = result_state

    def __str__(self):
        return f"{self.code}: {self.message}"


class EventStream:
    def __init__(self, client, types, since=0, boot_id=None, packages=(), process_id=None):
        self.client = client
        self.socket = client._socket()
        self.last_seq = since
        self.reset_required = False
        self.closed = False
        process_id = process_id or boot_id
        params = {"types": list(types), "since": since}
        if process_id is not None:
            params["processId"] = process_id
        if packages:
            params["packages"] = list(packages)
        ack = client._send(self.socket, "events.subscribe", params)
        self.boot_id = ack["result"]["bootId"]
        self.process_id = ack["result"].get("processId", self.boot_id)
        self.last_seq = ack["result"]["baseSeq"]
        self.reset_required = ack["result"].get("resetRequired", False)

    def read(self, timeout=None):
        if self.closed:
            raise AgentError("CLOSED", "event stream is closed")
        self.socket.settimeout(timeout)
        while True:
            response = self.client._read(self.socket)
            if response.get("event") == "events.reset":
                self.reset_required = True
            if "seq" in response:
                self.last_seq = max(self.last_seq, response["seq"])
            return response

    def _control(self, method, params):
        self.socket.settimeout(self.client.timeout)
        request_id = uuid.uuid4().hex
        request = {"token": self.client.token, "id": request_id, "method": method, "params": params}
        self.socket.sendall(json.dumps(request, ensure_ascii=False).encode() + b"\n")
        attempts = 0
        while True:
            try:
                response = self.client._read(self.socket)
            except AgentError as error:
                if error.code == "TIMEOUT" and attempts == 0:
                    attempts += 1
                    continue
                raise
            if response.get("id") != request_id:
                if "seq" in response:
                    self.last_seq = max(self.last_seq, response["seq"])
                continue
            if not response.get("ok"):
                error = response.get("error", {})
                raise AgentError(error.get("code", "INTERNAL"), error.get("message", "event control failed"))
            return response

    def unsubscribe(self):
        if self.closed:
            return {"unsubscribed": True}
        self.closed = True
        try:
            return self._control("events.unsubscribe", {})["result"]
        finally:
            self.socket.close()

    close = unsubscribe

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.unsubscribe()


class AgentClient:
    def __init__(self, token, host="127.0.0.1", port=17321, timeout=15):
        if isinstance(token, (str, Path)) and Path(token).is_file():
            token = Path(token).read_text().strip()
        if not isinstance(token, str) or len(token) != 32 or any(c not in "0123456789abcdef" for c in token):
            raise ValueError("invalid agent token")
        self.token = token
        self.host = host
        self.port = port
        self.timeout = timeout
        self.last_via = None
        self.last_degraded = None
        self.took_ms = None
        self.last_response = None
        self._forward = None
        self._serial = None
        self._readers = weakref.WeakKeyDictionary()

    def _socket(self):
        sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        sock.settimeout(self.timeout)
        return sock

    def _reader(self, sock):
        if sock not in self._readers:
            self._readers[sock] = sock.makefile("rb")
        return self._readers[sock]

    def _read(self, sock):
        try:
            data = self._reader(sock).readline(16 * 1024 * 1024)
        except OSError as error:
            if isinstance(error, TimeoutError) or getattr(error, "winerror", None) == 10060 or "timed out" in str(error).lower():
                reader = self._readers.pop(sock, None)
                if reader is not None:
                    try:
                        reader.close()
                    except Exception:
                        pass
                raise AgentError("TIMEOUT", "socket timed out") from error
            raise
        if not data:
            raise AgentError("TIMEOUT", "agent closed the connection", result_state="UNKNOWN")
        return json.loads(data)

    def _send(self, sock, method, params):
        request = {"token": self.token, "id": uuid.uuid4().hex, "method": method, "params": params}
        sock.sendall(json.dumps(request, ensure_ascii=False).encode() + b"\n")
        response = self._read(sock)
        self.last_response = response
        self.last_via = response.get("via")
        self.last_degraded = response.get("degraded")
        self.took_ms = response.get("tookMs")
        if not response.get("ok"):
            error = response.get("error", {})
            raise AgentError(error.get("code", "INTERNAL"), error.get("message", "agent request failed"),
                             error.get("detail"), response.get("via"), response.get("resultState"))
        return response

    def call(self, method, params=None, retries=0, idempotency_key=None):
        params = dict(params or {})
        if idempotency_key is not None:
            params["idempotencyKey"] = idempotency_key
        attempts = max(0, min(2, retries))
        for attempt in range(attempts + 1):
            try:
                with self._socket() as sock:
                    sock.settimeout(_request_timeout(self.timeout, method, params))
                    return self._send(sock, method, params)["result"]
            except (socket.timeout, TimeoutError) as error:
                readonly = method in READ_METHODS or method.endswith((".get", ".list", ".status", ".dump", ".find", ".info"))
                if not readonly or attempt >= attempts:
                    raise AgentError("TIMEOUT", "socket timed out") from error
            except OSError as error:
                readonly = method in READ_METHODS or method.endswith((".get", ".list", ".status", ".dump", ".find", ".info"))
                if "timed out" not in str(error).lower():
                    raise
                if not readonly or attempt >= attempts:
                    raise AgentError("TIMEOUT", "socket timed out") from error
            except AgentError as error:
                readonly = method in READ_METHODS or method.endswith((".get", ".list", ".status", ".dump", ".find", ".info"))
                if error.code != "TIMEOUT" or error.result_state == "UNKNOWN" or not readonly or attempt >= attempts:
                    raise
        raise AssertionError("unreachable")

    def macro(self, steps, **params):
        return self.call("macro", {"steps": steps, **params})

    def job_start(self, method, params=None):
        return self.call("job.start", {"method": method, "params": params or {}})

    def job_wait(self, job_id, timeout=60):
        import time
        deadline = time.monotonic() + timeout
        while True:
            status = self.call("job.status", {"jobId": job_id})
            if status["state"] in ("done", "failed", "cancelled"):
                return status
            if time.monotonic() >= deadline:
                raise AgentError("TIMEOUT", "job did not finish")
            time.sleep(0.1)

    def subscribe(self, types=("ui.changed", "app.foreground"), since=0, boot_id=None, packages=(), process_id=None):
        return EventStream(self, types, since, boot_id, packages, process_id)

    def save_shot(self, path, params=None):
        result = self.call("shot", params or {})
        target = Path(path)
        temporary = target.with_name(target.name + f".{uuid.uuid4().hex}.tmp")
        try:
            temporary.write_bytes(base64.b64decode(result["base64"]))
            os.replace(temporary, target)
        finally:
            temporary.unlink(missing_ok=True)
        return {k: v for k, v in result.items() if k != "base64"}

    @classmethod
    def from_adb(cls, serial=None, local_port=0, remote_port=17321):
        adb = ["adb"] + (["-s", serial] if serial else [])
        if local_port == 0:
            with socket.socket() as sock:
                sock.bind(("127.0.0.1", 0))
                local_port = sock.getsockname()[1]
        subprocess.check_output(adb + ["forward", f"tcp:{local_port}", f"tcp:{remote_port}"])
        try:
            token = subprocess.check_output(adb + ["exec-out", "run-as", "dev.dsh.agent", "cat", "files/token"]).decode().strip()
            client = cls(token, port=local_port)
            client._forward = local_port
            client._serial = serial
            return client
        except Exception:
            subprocess.run(adb + ["forward", "--remove", f"tcp:{local_port}"], check=False)
            raise

    def close(self):
        if self._forward is not None:
            adb = ["adb"] + (["-s", self._serial] if self._serial else [])
            subprocess.run(adb + ["forward", "--remove", f"tcp:{self._forward}"], check=False,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            self._forward = None

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


def _request_timeout(default, method, params):
    if method in ("backend.selftest", "selftest"):
        return max(default, 60)
    if method in ("prep.automation", "prep.restore"):
        return max(default, 130)
    if method == "screen.record":
        duration = max(1000, min(180000, int(params.get("durationMs", 10000))))
        return max(default, duration / 1000 + 20)
    if method == "shell.exec":
        timeout = max(1000, min(120000, int(params.get("timeoutMs", 120000))))
        return max(default, timeout / 1000 + 5)
    return default


READ_METHODS = {
    "ping", "shot", "sys.info", "backend.status", "backend.selftest", "selftest", "ui.dump", "ui.find", "ui.query",
    "ui.texts", "ui.windows", "ui.path", "ui.refs", "ui.explain", "ui.wait", "ui.waitIdle", "screen.diff",
    "session.current", "session.status", "session.list", "session.saved.list", "package.list", "notifications.list",
    "clipboard.get", "media.nowPlaying", "net.status", "power.state", "lock.state", "ime.status", "volume.get",
}


def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("method", help="JSON method, or events.subscribe")
    parser.add_argument("params", nargs="?", default="{}", help="JSON object, @file, or - for stdin")
    parser.add_argument("--token-file", default="/data/data/dev.dsh.agent/files/token")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=17321)
    parser.add_argument("--serial")
    parser.add_argument("--shot", help="write a shot to this local PNG path instead of printing base64")
    args = parser.parse_args()
    try:
        if args.serial:
            client = AgentClient.from_adb(args.serial, remote_port=args.port)
        else:
            client = AgentClient(Path(args.token_file), host=args.host, port=args.port)
        with client:
            if args.method == "events.subscribe":
                params = json.loads(args.params) if args.params != "-" else json.load(__import__("sys").stdin)
                stream = client.subscribe(**params)
                with stream:
                    while True:
                        print(json.dumps(stream.read(), ensure_ascii=False), flush=True)
            params = json.loads(args.params) if args.params != "-" else json.load(__import__("sys").stdin)
            if not isinstance(params, dict):
                raise ValueError("params must be a JSON object")
            if args.shot:
                result = client.save_shot(args.shot, params)
            else:
                result = client.call(args.method, params)
            print(json.dumps(result, ensure_ascii=False))
    except (AgentError, ValueError, OSError, json.JSONDecodeError) as error:
        code = error.code if isinstance(error, AgentError) else "CLIENT_ERROR"
        print(json.dumps({"ok": False, "error": {"code": code, "message": str(error)}}, ensure_ascii=False))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
