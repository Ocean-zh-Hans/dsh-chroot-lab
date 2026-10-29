import os
import pathlib
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith('/slow'):
            time.sleep(1)
        if self.path.startswith('/redirect'):
            self.send_response(302)
            self.send_header('Location', 'http://127.0.0.1.evil.example/')
            self.end_headers()
            return
        status = 503 if self.path.startswith('/status') else 200
        body = b'plain' if self.path.startswith('/plain') else b'<html>DSH health</html>'
        self.send_response(status)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except BrokenPipeError:
            pass

    def log_message(self, *args):
        pass


def main():
    script = sys.argv[1]
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix='dsh-health-') as directory:
            url_file = pathlib.Path(directory) / 'url'
            env = dict(os.environ, DSH_HEALTH_URL_FILE=str(url_file), DSH_HEALTH_TIMEOUT='0.2')

            def check(url, expected):
                url_file.write_text(url, encoding='utf-8')
                result = subprocess.run([sys.executable, script], env=env,
                                        capture_output=True, text=True, timeout=10)
                assert result.returncode == expected, (url, result.returncode, result.stderr)
                assert 'token=' not in result.stdout + result.stderr

            base = f'http://127.0.0.1:{server.server_port}'
            check(base + '/ok?token=test', 0)
            check(base + '/plain', 1)
            check(base + '/status', 1)
            check(base + '/slow', 1)
            check(base + '/redirect', 1)
            check('http://127.0.0.1.evil.example/', 2)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=3)
    print('test_healthcheck: ok')


if __name__ == '__main__':
    main()
