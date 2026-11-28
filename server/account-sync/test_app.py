from contextlib import closing
import io
import json
from pathlib import Path
import re
import tempfile
import unittest
from urllib.parse import urlencode, urlsplit
from wsgiref.util import setup_testing_defaults
from wsgiref.validate import validator

from app import AccountSyncApp, BODY_LIMIT, SESSION_TTL, secret_hash


PASSWORD = "first local test password!"


def history(series, position=42):
    return {"seriesId": series, "seriesTitle": "测试剧集", "cover": "https://images.example/poster.jpg",
            "source": "APPSOURCE", "updatedAt": 12345678, "episodeId": "one", "episodeNumber": 1,
            "positionMs": position, "durationMs": 5000, "episodeCount": 0}


def favorite(series):
    return {"seriesId": series, "seriesTitle": "收藏测试", "cover": "", "source": "APPSOURCE",
            "updatedAt": 12345678, "episodeCount": 10}


def change(identifier, kind, series="", value=None, base=None):
    result = {"id": identifier, "kind": kind, "seriesId": series, "deleted": value is None}
    if value is not None:
        result["value"] = value
    if base is not None:
        result["baseCursor"] = base
    return result


class AccountSyncTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.path = Path(temp.name) / "sync.sqlite3"
        self.now = 1700000000
        self.origin = "https://sync.test"
        self.app = AccountSyncApp(self.path, self.origin, lambda: self.now)
        self.wsgi = validator(self.app)

    def request(self, path, *, payload=None, headers=None, method="POST", body=None, ip="192.0.2.42"):
        target = urlsplit(path)
        env = {"REQUEST_METHOD": method, "SCRIPT_NAME": "", "PATH_INFO": target.path, "QUERY_STRING": target.query,
               "REMOTE_ADDR": ip, "HTTP_HOST": "ignored.test"}
        if body is None:
            body = json.dumps(payload).encode() if payload is not None else b""
        env.update(CONTENT_TYPE="application/json", CONTENT_LENGTH=str(len(body)), **{"wsgi.input": io.BytesIO(body)})
        if headers:
            env.update(headers)
        setup_testing_defaults(env)
        response = {}

        def start(status, response_headers, exc_info=None):
            response["status"] = int(status.split()[0])
            response["headers"] = dict(response_headers)

        result = self.wsgi(env, start)
        try:
            data = b"".join(result).decode()
        finally:
            result.close()
        if response["headers"].get("Content-Type", "").startswith("application/json"):
            data = json.loads(data)
        return response["status"], response["headers"], data

    def start(self):
        status, _, pair = self.request("/v1/pair/start", payload={})
        self.assertEqual(status, 201)
        return pair

    def approval(self, pair, username, mode, *, password=PASSWORD, headers=None, form=None):
        status, page_headers, body = self.request(pair["verificationUri"], method="GET")
        self.assertEqual(status, 200)
        self.assertIn('value="' + pair["userCode"] + '"', body)
        csrf = re.search(r'name="csrf" value="([A-Za-z0-9_-]{43})"', body).group(1)
        fields = {"csrf": csrf, "user_code": pair["userCode"], "name": username, "password": password,
                  "mode": mode, "confirm": "yes"}
        fields.update(form or {})
        input_headers = {"HTTP_ORIGIN": self.origin, "HTTP_COOKIE": page_headers["Set-Cookie"].split(";")[0],
                         "CONTENT_TYPE": "application/x-www-form-urlencoded"}
        input_headers.update(headers or {})
        return self.request("/pair", body=urlencode(fields).encode(), headers=input_headers)

    def poll(self, pair):
        return self.request("/v1/pair/poll", payload={"deviceCode": pair["deviceCode"]})

    def connect(self, user="watch-user", mode="register"):
        pair = self.start()
        self.assertEqual(self.approval(pair, user, mode)[0], 200)
        self.now += pair["interval"]
        status, _, result = self.poll(pair)
        self.assertEqual((status, result["status"]), (200, "approved"))
        return result

    def sync(self, session, cursor=0, changes=(), imported=None, expect=200):
        headers = {"HTTP_AUTHORIZATION": "Bearer " + session["token"]} if session else {}
        request = {"cursor": cursor, "changes": list(changes)}
        if imported is not None:
            request["import"] = imported
        status, _, body = self.request("/v1/library/sync", payload=request, headers=headers)
        self.assertEqual(status, expect, body)
        return body

    def test_pairing_requires_matching_browser_confirmation_and_expires(self):
        pair = self.start()
        self.assertRegex(pair["userCode"], r"^[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}$")
        self.assertEqual(pair["verificationUriBase"], self.origin + "/pair")
        self.assertEqual(pair["verificationUri"], pair["verificationUriBase"] + "?user_code=" + pair["userCode"])
        status, headers, _ = self.poll(pair)
        self.assertEqual((status, headers["Retry-After"]), (429, str(pair["interval"])))
        self.now += pair["interval"]
        self.assertEqual(self.poll(pair)[2], {"status": "pending"})
        self.assertEqual(self.approval(pair, "watch-user", "register", form={"confirm": "no"})[0], 400)
        self.assertEqual(self.approval(pair, "watch-user", "register", form={"csrf": "invalid"})[0], 403)
        self.assertEqual(self.approval(pair, "watch-user", "register", headers={"HTTP_ORIGIN": "https://other.test"})[0], 403)
        self.now += pair["expiresIn"]
        self.assertEqual(self.poll(pair)[2], {"status": "expired"})
        self.assertEqual(self.approval(pair, "watch-user", "register")[0], 400)

    def test_pairing_delivers_one_token_and_persists_only_hashes(self):
        pair = self.start()
        with closing(self.app.connect()) as db:
            self.assertEqual(db.execute("SELECT device_hash, user_hash FROM pairs").fetchone(),
                             (secret_hash(pair["deviceCode"]), secret_hash(pair["userCode"])))
        self.assertEqual(self.approval(pair, "Watch.User", "register")[0], 200)
        self.now += pair["interval"]
        approved = self.poll(pair)[2]
        self.assertEqual(approved["accountName"], "watch.user")
        self.assertRegex(approved["token"], r"^[A-Za-z0-9_-]{43}$")
        self.assertEqual(self.poll(pair)[2], {"status": "expired"})
        with closing(self.app.connect()) as db:
            self.assertEqual(db.execute("SELECT token_hash, account_id FROM sessions").fetchone(),
                             (secret_hash(approved["token"]), approved["accountId"]))
            salt, password = db.execute("SELECT salt, password_hash FROM accounts").fetchone()
            self.assertEqual((len(salt), len(password)), (16, 64))
            self.assertNotEqual(password, PASSWORD.encode())
            self.assertEqual(db.execute("SELECT COUNT(*) FROM pairs").fetchone()[0], 0)
        self.sync(approved)
        self.now += SESSION_TTL
        self.sync(approved, expect=401)

    def test_authenticated_accounts_are_isolated_and_devices_can_revoke(self):
        first = self.connect()
        second = self.connect(mode="login")
        other = self.connect("other-user")
        self.assertEqual(first["accountId"], second["accountId"])
        self.assertNotEqual(first["token"], second["token"])
        self.assertNotEqual(first["accountId"], other["accountId"])
        self.sync(None, expect=401)
        self.sync(first, changes=[change("a:1", "history", "shared", history("shared"))])
        self.assertEqual(self.sync(second)["history"], [history("shared")])
        self.assertEqual(self.sync(other)["history"], [])
        replacement = AccountSyncApp(self.path, self.origin, lambda: self.now)
        self.wsgi = validator(replacement)
        self.assertEqual(self.sync(second)["history"], [history("shared")])
        revoked, _, _ = self.request("/v1/session/revoke", headers={"HTTP_AUTHORIZATION": "Bearer " + first["token"]})
        self.assertEqual(revoked, 204)
        self.sync(first, expect=401)
        self.assertEqual(self.sync(second)["history"], [history("shared")])

    def test_guest_import_cannot_overwrite_cloud_tombstones_or_cleared_history(self):
        account = self.connect()
        remote = self.sync(account, changes=[
            change("a:1", "history", "existing", history("existing")),
            change("a:2", "favorite", "removed"),
        ])
        guest_chunk = {"id": "guest:0", "history": [history("existing", 400), history("extra")],
                       "favorites": [favorite("removed"), favorite("kept")]}
        imported = self.sync(account, remote["cursor"], imported=guest_chunk)
        self.assertTrue(imported["imported"])
        self.assertEqual({item["seriesId"]: item["positionMs"] for item in imported["history"]}, {"existing": 42, "extra": 42})
        self.assertEqual(imported["favorites"], [favorite("kept")])
        self.assertEqual(self.sync(account, imported["cursor"], imported=guest_chunk), imported)
        cleared = self.sync(account, imported["cursor"], [change("a:3", "history_clear")])
        self.assertEqual(cleared["history"], [])
        later = self.sync(account, cleared["cursor"], imported={"id": "guest:1", "history": [history("never-uploaded")],
                                                              "favorites": [favorite("new-favorite")]})
        self.assertEqual(later["history"], [])
        self.assertEqual(later["favorites"], [favorite("new-favorite"), favorite("kept")])

    def test_remote_edits_win_only_on_conflicting_keys_and_older_base_cursors(self):
        session = self.connect()
        at_one = self.sync(session, changes=[change("a:1", "history", "series", history("series"))])
        at_two = self.sync(session, at_one["cursor"], [change("b:1", "history", "series", history("series", 1000))])
        changes = [change("a:2", "history", "series", history("series", 5), at_one["cursor"]),
                   change("a:3", "history", "different", history("different"), at_one["cursor"])]
        merged = self.sync(session, at_two["cursor"], changes)
        self.assertEqual(merged["conflicts"], ["a:2"])
        self.assertEqual(merged["accepted"], {"a:3": merged["cursor"]})
        self.assertEqual(merged["history"], [history("different"), history("series", 1000)])
        self.assertEqual(self.sync(session, merged["cursor"], changes), merged)
        rewind = self.sync(session, merged["cursor"], [change("a:4", "history", "series", history("series", 5))])
        self.assertEqual(rewind["history"][0]["positionMs"], 5)

    def test_stale_clear_loses_and_acknowledged_clear_blocks_old_progress(self):
        session = self.connect()
        saved = self.sync(session, changes=[change("a:1", "history", "initial", history("initial"))])
        stale_clear = self.sync(session, 0, [change("b:1", "history_clear")])
        self.assertEqual(stale_clear["conflicts"], ["b:1"])
        self.assertEqual(stale_clear["history"], [history("initial")])
        cleared = self.sync(session, saved["cursor"], [change("b:2", "history_clear")])
        self.assertEqual(cleared["history"], [])
        stale_write = self.sync(session, cleared["cursor"], [change("a:2", "history", "old", history("old"), saved["cursor"])])
        self.assertEqual(stale_write["conflicts"], ["a:2"])
        repopulated = self.sync(session, cleared["cursor"], [change("b:3", "history", "new", history("new"))])
        self.assertEqual(repopulated["history"], [history("new")])

    def test_accepted_versions_bind_original_action_and_batch_rolls_back_on_bad_receipts(self):
        session = self.connect()
        first = change("a:1", "favorite", "series", favorite("series"))
        second = change("a:2", "history", "series", history("series"))
        saved = self.sync(session, changes=[first, second])
        self.assertEqual(saved["accepted"], {"a:1": 1, "a:2": 2})
        enriched = change("a:1", "favorite", "series", {**favorite("series"), "seriesTitle": "已补全剧名"})
        replayed = self.sync(session, changes=[enriched])
        self.assertEqual(replayed["accepted"], {"a:1": 1})
        self.assertEqual(replayed["favorites"], [favorite("series")])
        self.sync(session, saved["cursor"], [change("new:1", "favorite", "new", favorite("new")),
                                            change("a:2", "history", "series", history("series", 700))], expect=400)
        snapshot = self.sync(session)
        self.assertEqual(snapshot["cursor"], 2)
        self.assertEqual(snapshot["favorites"], [favorite("series")])
        self.assertEqual(snapshot["history"], [history("series")])

    def test_request_limits_and_untrusted_cover_rejection(self):
        session = self.connect()
        self.assertEqual(self.request("/v1/library/sync", body=b" " * (BODY_LIMIT + 1))[0], 413)
        self.sync(session, 1, expect=400)
        self.sync(session, imported={"history": [history("too-many")] * 101}, expect=400)
        for cover in ("http://insecure.test/a", "https://example.test/image?key=private", "https://example.test/#",
                      "https://password@example.test/path", "https://example.test:bad/path"):
            self.sync(session, changes=[change("a:1", "favorite", "series", {**favorite("series"), "cover": cover})], expect=400)
        self.assertEqual(self.sync(session)["cursor"], 0)

    def test_start_and_browser_retries_are_rate_limited(self):
        pair = self.start()
        for _ in range(8):
            self.assertEqual(self.approval(pair, "watch-user", "login", password="an incorrect password")[0], 401)
        status, headers, _ = self.approval(pair, "watch-user", "register")
        self.assertEqual((status, headers["Retry-After"]), (429, "300"))
        for _ in range(11):
            self.start()
        status, headers, _ = self.request("/v1/pair/start", payload={})
        self.assertEqual((status, headers["Retry-After"]), (429, "60"))


if __name__ == "__main__":
    unittest.main()
