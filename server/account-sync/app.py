"""Local account and watch-pairing service. Start with python app.py."""

import argparse
from contextlib import closing, contextmanager
import hashlib
import hmac
from html import escape
from http import HTTPStatus
from http.cookies import CookieError, SimpleCookie
import json
import logging
import re
import secrets
import sqlite3
import time
from urllib.parse import parse_qs, urlsplit
from wsgiref.simple_server import WSGIRequestHandler, make_server

import library


PAIR_TTL = 300
POLL_INTERVAL = 3
SESSION_TTL = 30 * 86400
BODY_LIMIT = 512 * 1024
USER_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
USER_CODE = re.compile(r"[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}")
ACCOUNT_NAME = re.compile(r"[a-z0-9_.-]{3,32}")
TOKEN = re.compile(r"[A-Za-z0-9_-]{43}")
DUMMY_SALT = bytes(16)
LOGGER = logging.getLogger(__name__)

SCHEMA = """
PRAGMA journal_mode = WAL;
CREATE TABLE IF NOT EXISTS accounts (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT UNIQUE NOT NULL,
    salt BLOB NOT NULL,
    password_hash BLOB NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    history_revision INTEGER NOT NULL DEFAULT 0,
    clear_revision INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS pairs (
    device_hash TEXT PRIMARY KEY NOT NULL,
    user_hash TEXT UNIQUE NOT NULL,
    expires_at INTEGER NOT NULL,
    next_poll INTEGER NOT NULL,
    account_id TEXT REFERENCES accounts(id)
);
CREATE TABLE IF NOT EXISTS sessions (
    token_hash TEXT PRIMARY KEY NOT NULL,
    account_id TEXT NOT NULL REFERENCES accounts(id),
    expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS limits (
    action TEXT NOT NULL,
    owner TEXT NOT NULL,
    window_start INTEGER NOT NULL,
    count INTEGER NOT NULL,
    PRIMARY KEY (action, owner)
);
CREATE TABLE IF NOT EXISTS library (
    account_id TEXT NOT NULL REFERENCES accounts(id),
    kind TEXT NOT NULL,
    series_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    payload TEXT,
    PRIMARY KEY (account_id, kind, series_id)
);
CREATE TABLE IF NOT EXISTS receipts (
    account_id TEXT NOT NULL REFERENCES accounts(id),
    identifier TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    conflict INTEGER NOT NULL,
    revision INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (account_id, identifier)
);
"""


class RequestError(Exception):
    def __init__(self, status, message, retry_after=None):
        super().__init__(message)
        self.status = status
        self.retry_after = retry_after


def secret_hash(value):
    return hashlib.sha256(value.encode("ascii")).hexdigest()


def password_hash(password, salt):
    return hashlib.scrypt(password, salt=salt, n=2**15, r=8, p=3, maxmem=64 * 1024**2)


def security_headers():
    return [
        ("Cache-Control", "no-store"),
        ("Referrer-Policy", "no-referrer"),
        ("X-Content-Type-Options", "nosniff"),
        ("X-Frame-Options", "DENY"),
        ("Content-Security-Policy",
         "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; "
         "frame-ancestors 'none'; base-uri 'none'"),
    ]


def reply(start, status, body=b"", content_type="application/json; charset=utf-8", extra=()):
    headers = security_headers() + list(extra)
    if status != 204:
        headers.extend([("Content-Type", content_type), ("Content-Length", str(len(body)))])
    start(f"{status} {HTTPStatus(status).phrase}", headers)
    return [body] if status != 204 else []


def json_reply(start, result, status=200, extra=()):
    return reply(start, status, library.encode(result).encode("utf-8"), extra=extra)


def read_body(environ, media_type, limit):
    if environ.get("CONTENT_TYPE", "").split(";", 1)[0].strip().lower() != media_type:
        raise RequestError(415, "unsupported content type")
    try:
        length = int(environ.get("CONTENT_LENGTH", ""))
    except ValueError:
        raise RequestError(411, "content length required") from None
    if length < 0:
        raise RequestError(400, "invalid content length")
    if length > limit:
        raise RequestError(413, "request is too large")
    body = environ["wsgi.input"].read(length)
    if len(body) != length:
        raise RequestError(400, "incomplete request")
    return body


def read_json(environ, limit=BODY_LIMIT):
    body = read_body(environ, "application/json", limit)
    try:
        data = json.loads(body.decode("utf-8"))
    except (UnicodeError, ValueError, RecursionError):
        raise RequestError(400, "invalid request JSON") from None
    if not isinstance(data, dict):
        raise RequestError(400, "expected a JSON object")
    return data


def html_page(contents):
    return ("""<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>红果终端 · 连接手表</title>
<style>
body{color:#171717;background:#fafafa;font:1rem system-ui,sans-serif;margin:0}
main{max-width:27rem;margin:auto;padding:1.25rem}
label{display:block;margin:1rem 0}input:not([type=checkbox]),button{font:inherit;box-sizing:border-box;padding:.65rem}
input:not([type=checkbox]){display:block;width:100%;margin-top:.3rem}
button{background:#a01835;color:white;border:0;border-radius:.4rem;margin:.3rem .25rem .3rem 0}
.message{color:#a01835}.code{font-family:monospace;letter-spacing:.1em}
</style><main><h1>连接手表</h1>""" + contents + "</main></html>").encode("utf-8")


def pairing_form(user_code, csrf, message=None):
    notice = f'<p class="message" role="alert">{escape(message)}</p>' if message else ""
    return html_page(
        notice + """<p>请确认配对码与手表上显示的一致。授权后，这块手表可访问你的播放历史和收藏。</p>
<form action="/pair" method="post">
<input type="hidden" name="csrf" value="%s">
<label>手表配对码<input name="user_code" class="code" value="%s" required maxlength="9"
autocomplete="off" autocapitalize="characters"></label>
<label>账号<input name="name" required minlength="3" maxlength="32" autocomplete="username"
placeholder="3–32 位英文字母、数字、点、横线或下划线"></label>
<label>密码<input name="password" type="password" minlength="12" maxlength="128"
required autocomplete="current-password"></label>
<label><input type="checkbox" name="confirm" value="yes" required> 我持有这块手表，并确认屏幕上的配对码相同。</label>
<button name="mode" value="login">登录并连接</button>
<button name="mode" value="register">注册并连接</button>
</form>""" % (escape(csrf, quote=True), escape(user_code, quote=True))
    )


class AccountSyncApp:
    def __init__(self, database_path, public_origin, clock=time.time):
        origin = urlsplit(public_origin)
        loopback = origin.hostname in ("localhost", "127.0.0.1", "::1")
        if (not origin.hostname or (origin.scheme != "https" and not (origin.scheme == "http" and loopback))
                or origin.path not in ("", "/") or "?" in public_origin or "#" in public_origin
                or origin.username is not None or origin.password is not None
                or any(character.isspace() or ord(character) < 32 for character in public_origin)):
            raise ValueError("public origin must be HTTPS, or HTTP on loopback for development")
        default_port = 443 if origin.scheme == "https" else 80
        host = f"[{origin.hostname}]" if ":" in origin.hostname else origin.hostname.encode("idna").decode("ascii")
        port_suffix = f":{origin.port}" if origin.port not in (None, default_port) else ""
        self.public_origin = f"{origin.scheme}://{host}{port_suffix}"
        self.secure_cookie = origin.scheme == "https"
        self.database_path = database_path
        self.clock = clock
        with closing(self.connect()) as db:
            db.executescript(SCHEMA)

    def connect(self):
        db = sqlite3.connect(self.database_path, timeout=5, isolation_level=None)
        db.execute("PRAGMA foreign_keys = ON")
        return db

    @contextmanager
    def transaction(self):
        with closing(self.connect()) as db:
            db.execute("BEGIN IMMEDIATE")
            with db:
                yield db

    def limit(self, db, action, owner, now, count, seconds):
        previous = db.execute(
            "SELECT window_start, count FROM limits WHERE action = ? AND owner = ?", (action, owner)
        ).fetchone()
        if previous and now - previous[0] < seconds:
            if previous[1] >= count:
                raise RequestError(429, "try again later", max(1, seconds - (now - previous[0])))
            db.execute("UPDATE limits SET count = count + 1 WHERE action = ? AND owner = ?", (action, owner))
        else:
            db.execute("INSERT OR REPLACE INTO limits VALUES (?, ?, ?, 1)", (action, owner, now))

    def charge(self, action, owner, now, count, seconds):
        with self.transaction() as db:
            self.limit(db, action, owner, now, count, seconds)

    def begin_pair(self, environ, ip, now):
        read_json(environ, 1024)
        with self.transaction() as db:
            self.limit(db, "start", ip, now, 12, 60)
            db.execute("DELETE FROM limits WHERE window_start < ?", (now - 3600,))
            db.execute("DELETE FROM pairs WHERE expires_at <= ?", (now,))
            db.execute("DELETE FROM sessions WHERE expires_at <= ?", (now,))
            for _ in range(5):
                device = secrets.token_urlsafe(32)
                letters = "".join(secrets.choice(USER_CODE_ALPHABET) for _ in range(8))
                code = letters[:4] + "-" + letters[4:]
                try:
                    db.execute(
                        "INSERT INTO pairs (device_hash, user_hash, expires_at, next_poll) VALUES (?, ?, ?, ?)",
                        (secret_hash(device), secret_hash(code), now + PAIR_TTL, now + POLL_INTERVAL),
                    )
                except sqlite3.IntegrityError:
                    continue
                return {"deviceCode": device, "userCode": code,
                        "verificationUri": self.public_origin + "/pair?user_code=" + code,
                        "verificationUriBase": self.public_origin + "/pair",
                        "expiresIn": PAIR_TTL, "interval": POLL_INTERVAL}
            raise RequestError(503, "could not start pairing")

    def poll(self, environ, ip, now):
        code = read_json(environ, 1024).get("deviceCode")
        if not isinstance(code, str) or not TOKEN.fullmatch(code):
            raise RequestError(400, "invalid device code")
        self.charge("poll", ip, now, 120, 60)
        key = secret_hash(code)
        with self.transaction() as db:
            row = db.execute(
                """SELECT p.expires_at, p.next_poll, p.account_id, a.name
                FROM pairs p LEFT JOIN accounts a ON a.id = p.account_id WHERE p.device_hash = ?""", (key,)
            ).fetchone()
            if row is None or row[0] <= now:
                return {"status": "expired"}
            if row[1] > now:
                raise RequestError(429, "polling too fast", row[1] - now)
            if row[2] is None:
                db.execute("UPDATE pairs SET next_poll = ? WHERE device_hash = ?", (now + POLL_INTERVAL, key))
                return {"status": "pending"}
            token = secrets.token_urlsafe(32)
            db.execute("INSERT INTO sessions VALUES (?, ?, ?)", (secret_hash(token), row[2], now + SESSION_TTL))
            db.execute("DELETE FROM pairs WHERE device_hash = ?", (key,))
            return {"status": "approved", "token": token, "accountId": row[2], "accountName": row[3]}

    def get_pair_form(self, environ, start):
        query = environ.get("QUERY_STRING", "")
        fields = parse_qs(query) if len(query) <= 128 else {}
        code = fields.get("user_code", [""])[0].strip().upper()
        if not USER_CODE.fullmatch(code):
            code = ""
        csrf = secrets.token_urlsafe(32)
        cookie = f"hg_pair_csrf={csrf}; Path=/pair; Max-Age={PAIR_TTL}; SameSite=Strict; HttpOnly"
        if self.secure_cookie:
            cookie += "; Secure"
        return reply(start, 200, pairing_form(code, csrf), "text/html; charset=utf-8", [("Set-Cookie", cookie)])

    def approve(self, environ, ip, now, start):
        supplied_origin = environ.get("HTTP_ORIGIN")
        if supplied_origin is not None and supplied_origin != self.public_origin:
            raise RequestError(403, "请从配对页面重新打开。")
        body = read_body(environ, "application/x-www-form-urlencoded", 4096)
        try:
            fields = parse_qs(body.decode(), max_num_fields=8, errors="strict")
            cookie = SimpleCookie(environ.get("HTTP_COOKIE", ""))
        except (CookieError, ValueError):
            raise RequestError(400, "无效的表单。") from None

        def field(name):
            return fields.get(name, [""])[0]

        csrf = field("csrf")
        saved_csrf = cookie.get("hg_pair_csrf")
        if (not TOKEN.fullmatch(csrf) or saved_csrf is None or not TOKEN.fullmatch(saved_csrf.value)
                or not hmac.compare_digest(csrf, saved_csrf.value)):
            raise RequestError(403, "请重新打开手表配对页面。")
        self.charge("browser", ip, now, 20, 300)
        code = field("user_code").strip().upper()
        name = field("name").strip().lower()
        password = field("password").encode("utf-8")
        mode = field("mode")
        if (field("confirm") != "yes" or not USER_CODE.fullmatch(code) or not ACCOUNT_NAME.fullmatch(name)
                or not 12 <= len(password) <= 512 or not 12 <= len(field("password")) <= 128
                or mode not in ("login", "register")):
            return reply(start, 400, pairing_form(code, csrf, "请核对配对码、账号和密码。密码需 12–128 位。"),
                         "text/html; charset=utf-8")
        self.charge("account", name, now, 8, 300)
        code_hash = secret_hash(code)
        with closing(self.connect()) as db:
            pending = db.execute(
                "SELECT 1 FROM pairs WHERE user_hash = ? AND account_id IS NULL AND expires_at > ?", (code_hash, now)
            ).fetchone()
            account = db.execute("SELECT id, salt, password_hash FROM accounts WHERE name = ?", (name,)).fetchone()
        if not pending:
            return reply(start, 400, pairing_form(code, csrf, "此配对码已失效，请在手表上重新获取。"), "text/html; charset=utf-8")
        if mode == "login":
            calculated = password_hash(password, account[1] if account else DUMMY_SALT)
            if account is None or not hmac.compare_digest(calculated, account[2]):
                return reply(start, 401, pairing_form(code, csrf, "账号或密码不正确。"), "text/html; charset=utf-8")
            account_id = account[0]
        else:
            account_id = secrets.token_urlsafe(16)
            salt = secrets.token_bytes(16)
            calculated = password_hash(password, salt)
        with self.transaction() as db:
            if mode == "register":
                try:
                    db.execute("INSERT INTO accounts (id, name, salt, password_hash) VALUES (?, ?, ?, ?)",
                               (account_id, name, salt, calculated))
                except sqlite3.IntegrityError:
                    return reply(start, 409, pairing_form(code, csrf, "此账号无法注册，请尝试登录或使用其他账号。"),
                                 "text/html; charset=utf-8")
            updated = db.execute(
                """UPDATE pairs SET account_id = ? WHERE user_hash = ?
                AND account_id IS NULL AND expires_at > ?""",
                (account_id, code_hash, int(self.clock())),
            ).rowcount
            if updated != 1:
                raise RequestError(400, "此配对码已失效，请在手表上重新获取。")
        return reply(start, 200, html_page("<p>手表已授权。回到手表继续设置历史和收藏同步。</p>"),
                     "text/html; charset=utf-8")

    def device_session(self, db, environ, now):
        auth = environ.get("HTTP_AUTHORIZATION", "")
        token = auth.removeprefix("Bearer ")
        if token == auth or not TOKEN.fullmatch(token):
            raise RequestError(401, "sign in again")
        key = secret_hash(token)
        session = db.execute("SELECT account_id FROM sessions WHERE token_hash = ? AND expires_at > ?",
                             (key, now)).fetchone()
        if session is None:
            raise RequestError(401, "sign in again")
        return key, session[0]

    def sync(self, environ, now):
        # Reject oversized/malformed input before opening a library transaction.
        parsed = library.parse_request(read_json(environ))
        with closing(self.connect()) as db:
            _, account = self.device_session(db, environ, now)
        self.charge("sync", account, now, 30, 60)
        with self.transaction() as db:
            # Re-check to serialize any concurrent device revocation with writes.
            _, account = self.device_session(db, environ, now)
            return library.sync(db, account, parsed, now)

    def __call__(self, environ, start_response):
        method = environ.get("REQUEST_METHOD")
        path = environ.get("PATH_INFO")
        ip = environ.get("REMOTE_ADDR", "unknown")[:64]
        now = int(self.clock())
        try:
            if path == "/healthz" and method == "GET":
                return json_reply(start_response, {"ok": True})
            if path == "/pair" and method == "GET":
                return self.get_pair_form(environ, start_response)
            if path == "/pair" and method == "POST":
                return self.approve(environ, ip, now, start_response)
            if path == "/v1/pair/start" and method == "POST":
                return json_reply(start_response, self.begin_pair(environ, ip, now), status=201)
            if path == "/v1/pair/poll" and method == "POST":
                return json_reply(start_response, self.poll(environ, ip, now))
            if path == "/v1/session/revoke" and method == "POST":
                with self.transaction() as db:
                    key, _ = self.device_session(db, environ, now)
                    db.execute("DELETE FROM sessions WHERE token_hash = ?", (key,))
                return reply(start_response, 204)
            if path == "/v1/library/sync" and method == "POST":
                return json_reply(start_response, self.sync(environ, now))
            raise RequestError(404, "not found")
        except (RequestError, library.LibraryError) as error:
            extra = []
            retry = getattr(error, "retry_after", None)
            if retry is not None:
                extra.append(("Retry-After", str(retry)))
            if path == "/pair":
                return reply(start_response, error.status, html_page(
                    f'<p class="message">{escape(str(error))}</p><p>请返回手表并重新打开配对页面。</p>'
                ), "text/html; charset=utf-8", extra)
            return json_reply(start_response, {"error": str(error)}, error.status, extra)
        except sqlite3.Error:
            LOGGER.warning("Account sync database operation failed")
            return json_reply(start_response, {"error": "temporarily unavailable"}, 503)


class QuietRequestHandler(WSGIRequestHandler):
    def log_request(self, code="-", size="-"):
        # Pairing codes can appear in the query string, so do not log request URLs.
        pass

    def log_message(self, format, *args):
        # The standard handler can include raw request lines in parse errors.
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", default="account-sync.sqlite3")
    parser.add_argument("--listen", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--public-origin")
    args = parser.parse_args()
    if args.listen not in ("127.0.0.1", "localhost"):
        parser.error("the reference server only binds to loopback")
    origin = args.public_origin or f"http://localhost:{args.port}"
    app = AccountSyncApp(args.database, origin)
    with make_server(args.listen, args.port, app, handler_class=QuietRequestHandler) as server:
        print(f"Account sync test server listening on http://{args.listen}:{args.port}", flush=True)
        server.serve_forever()


if __name__ == "__main__":
    main()
