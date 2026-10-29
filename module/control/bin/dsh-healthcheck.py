#!/usr/bin/env python3
import http.cookiejar
import os
import pathlib
import sys
import urllib.parse
import urllib.request


def checked_url(value):
    if len(value) > 4096:
        raise ValueError('URL too long')
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost'):
        raise ValueError('not loopback HTTP')
    if parsed.username is not None or parsed.password is not None:
        raise ValueError('userinfo is not allowed')
    if parsed.port is None or not 1 <= parsed.port <= 65535:
        raise ValueError('invalid port')
    return parsed


class LoopbackRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        checked_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def main():
    try:
        timeout = float(os.environ.get('DSH_HEALTH_TIMEOUT', '5'))
        if not 0 < timeout <= 30:
            raise ValueError('invalid timeout')
        url = pathlib.Path(os.environ.get('DSH_HEALTH_URL_FILE', '/run/dsh/url')).read_text(
            encoding='utf-8').strip()
        checked_url(url)
    except (OSError, ValueError):
        return 2
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        LoopbackRedirect(),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    try:
        with opener.open(url, timeout=timeout) as response:
            checked_url(response.geturl())
            body = response.read(2 * 1024 * 1024)
            if response.status != 200 or b'dsh' not in body.lower():
                return 1
    except Exception as error:
        print('DSH health check failed: ' + type(error).__name__, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
