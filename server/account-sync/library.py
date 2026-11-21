"""Validation and per-account merge rules for the library sync API."""

import hashlib
import json
import re
from urllib.parse import urlsplit


MAX_NUMBER = 2**53 - 1
MAX_MUTATIONS = 250
MAX_IMPORT = 100
MAX_ACTIVE = 2000
MAX_KEYS = 8000
MAX_RECEIPTS = 100000
MUTATION_ID = re.compile(r"[A-Za-z0-9._:-]{1,128}\Z")


class LibraryError(Exception):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


def integer(value, limit=MAX_NUMBER):
    if type(value) is not int or not 0 <= value <= limit:
        raise LibraryError("invalid number")
    return value


def string(value, limit, optional=False):
    if not isinstance(value, str) or len(value) > limit or (not optional and not value.strip()):
        raise LibraryError("invalid string")
    if any(ord(c) < 32 or 0xD800 <= ord(c) <= 0xDFFF for c in value):
        raise LibraryError("invalid string")
    return value


def record(value, kind):
    if not isinstance(value, dict):
        raise LibraryError("invalid record")
    cover = string(value.get("cover", ""), 1024, optional=True)
    if cover:
        try:
            url = urlsplit(cover)
            if (len(cover.encode("utf-8")) > 1024 or url.scheme != "https" or not url.hostname
                    or url.username is not None or url.password is not None or url.port == 0
                    or any(character in cover for character in ("?", "#", "\\"))
                    or any(character.isspace() for character in cover)):
                raise ValueError()
        except ValueError:
            raise LibraryError("invalid cover URL") from None
    result = {
        "seriesId": string(value.get("seriesId"), 128),
        "seriesTitle": string(value.get("seriesTitle", ""), 256, optional=True),
        "cover": cover,
        "source": string(value.get("source"), 32),
        "updatedAt": integer(value.get("updatedAt")),
    }
    if kind == "history":
        result.update(
            episodeId=string(value.get("episodeId"), 128),
            episodeNumber=integer(value.get("episodeNumber"), 100000),
            positionMs=integer(value.get("positionMs")),
            durationMs=integer(value.get("durationMs")),
        )
    else:
        result["episodeCount"] = integer(value.get("episodeCount"), 100000)
    return result


def mutation_id(value):
    if not isinstance(value, str) or MUTATION_ID.fullmatch(value) is None:
        raise LibraryError("invalid mutation ID")
    return value


def parse_request(payload):
    cursor = integer(payload.get("cursor"))
    raw_changes = payload.get("changes", [])
    if not isinstance(raw_changes, list) or len(raw_changes) > MAX_MUTATIONS:
        raise LibraryError("invalid change batch")
    changes = []
    ids = set()
    for change in raw_changes:
        if not isinstance(change, dict):
            raise LibraryError("invalid change")
        identifier = mutation_id(change.get("id"))
        if identifier in ids:
            raise LibraryError("duplicate mutation ID")
        ids.add(identifier)
        kind = change.get("kind")
        if kind not in ("history", "favorite", "history_clear"):
            raise LibraryError("invalid library kind")
        series = string(change.get("seriesId"), 128, optional=kind == "history_clear")
        deleted = change.get("deleted")
        if type(deleted) is not bool or (kind == "history_clear" and (not deleted or series)):
            raise LibraryError("invalid deletion")
        value = None if deleted else record(change.get("value"), kind)
        if value is not None and value["seriesId"] != series:
            raise LibraryError("record key mismatch")
        changes.append((identifier, kind, series, value, integer(change.get("baseCursor", cursor))))
    imported = None
    if "import" in payload:
        data = payload["import"]
        if not isinstance(data, dict):
            raise LibraryError("invalid import")
        records = []
        for key, kind in (("history", "history"), ("favorites", "favorite")):
            rows = data.get(key, [])
            if not isinstance(rows, list) or len(rows) + len(records) > MAX_IMPORT:
                raise LibraryError("invalid import batch")
            records.extend((kind, record(row, kind)) for row in rows)
        # The same chunk without an explicit ID has the same receipt on retry.
        identifier = (mutation_id(data["id"]) if "id" in data
                      else hashlib.sha256(encode(records).encode()).hexdigest())
        imported = identifier, records
    return cursor, changes, imported


def encode(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sync(db, account, parsed, now):
    """Apply one batch and build its authoritative response in the same write transaction."""
    base, changes, imported = parsed
    state = db.execute(
        "SELECT revision, history_revision, clear_revision FROM accounts WHERE id = ?", (account,)
    ).fetchone()
    revision, history_revision, clear_revision = state
    if base > revision:
        raise LibraryError("unknown cursor")
    first_revision = revision
    own_changes = set()
    conflicts = []
    accepted = {}

    def read_key(kind, series):
        return db.execute(
            "SELECT revision, payload FROM library WHERE account_id = ? AND kind = ? AND series_id = ?",
            (account, kind, series),
        ).fetchone()

    def write(kind, series, value):
        nonlocal revision, history_revision
        revision += 1
        if kind == "history":
            history_revision = revision
        db.execute(
            """INSERT INTO library (account_id, kind, series_id, revision, payload) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (account_id, kind, series_id) DO UPDATE
            SET revision = excluded.revision, payload = excluded.payload""",
            (account, kind, series, revision, None if value is None else encode(value)),
        )

    def receipt(identifier, value, applied_revision=None, conflict=False):
        fingerprint = hashlib.sha256(encode(value).encode()).hexdigest()
        previous = db.execute(
            "SELECT fingerprint, conflict, revision FROM receipts WHERE account_id = ? AND identifier = ?",
            (account, identifier),
        ).fetchone()
        if previous is not None and previous[0] != fingerprint:
            raise LibraryError("mutation ID reused with different data")
        if previous is None and applied_revision is not None:
            db.execute("INSERT INTO receipts VALUES (?, ?, ?, ?, ?, ?)",
                       (account, identifier, fingerprint, int(conflict), applied_revision, now))
        return previous

    # Validate against the version the watch has seen. Mutations in the same accepted
    # batch may supersede one another, but cannot override a concurrent remote change.
    for identifier, kind, series, value, change_base in changes:
        if change_base > first_revision:
            raise LibraryError("unknown change cursor")
        # A favorite may gain local display metadata while the original request is in flight.
        # Its action and original timestamp still identify the same operation.
        mutation_value = value["updatedAt"] if kind == "favorite" and value is not None else value
        mutation = (kind, series, mutation_value)
        receipt_id = "change:" + identifier
        previous = receipt(receipt_id, mutation)
        if previous is not None:
            if previous[1]:
                conflicts.append(identifier)
            else:
                accepted[identifier] = previous[2]
                current = read_key(kind, series)
                if current is not None and current[0] == previous[2]:
                    own_changes.add((kind, series))
            continue
        if kind == "history_clear":
            # A newer local write in this batch must not hide an older remote change.
            conflict = change_base < state[1]
            if not conflict:
                revision += 1
                history_revision = clear_revision = revision
                db.execute("DELETE FROM library WHERE account_id = ? AND kind = 'history'", (account,))
        else:
            key = kind, series
            current = read_key(*key)
            conflict = current is not None and current[0] > change_base and key not in own_changes
            if kind == "history" and change_base < state[2]:
                conflict = True
            if not conflict:
                write(kind, series, value)
                own_changes.add(key)
        receipt(receipt_id, mutation, revision, conflict)
        if conflict:
            conflicts.append(identifier)
        else:
            accepted[identifier] = revision

    if imported is not None:
        identifier, records = imported
        receipt_id = "import:" + identifier
        if receipt(receipt_id, records) is None:
            for kind, value in records:
                series = value["seriesId"]
                if (kind != "history" or clear_revision == 0) and read_key(kind, series) is None:
                    write(kind, series, value)
            receipt(receipt_id, records, revision)

    db.execute(
        "UPDATE accounts SET revision = ?, history_revision = ?, clear_revision = ? WHERE id = ?",
        (revision, history_revision, clear_revision, account),
    )
    total, active = db.execute(
        "SELECT COUNT(*), COUNT(payload) FROM library WHERE account_id = ?", (account,)
    ).fetchone()
    if total > MAX_KEYS or active > MAX_ACTIVE:
        raise LibraryError("account library limit reached", 413)
    if changes or imported:
        # Preserve retry receipts while bounding this reference service's storage.
        db.execute(
            """DELETE FROM receipts WHERE account_id = ? AND rowid IN (
                SELECT rowid FROM receipts WHERE account_id = ? ORDER BY rowid DESC LIMIT -1 OFFSET ?
            )""",
            (account, account, MAX_RECEIPTS),
        )
    response = {"cursor": revision, "history": [], "favorites": [],
                "imported": imported is not None, "conflicts": conflicts, "accepted": accepted}
    for kind, payload in db.execute(
        """SELECT kind, payload FROM library WHERE account_id = ? AND payload IS NOT NULL
        ORDER BY revision DESC""", (account,)
    ):
        response["history" if kind == "history" else "favorites"].append(json.loads(payload))
    return response
