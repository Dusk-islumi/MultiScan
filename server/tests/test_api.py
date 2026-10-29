from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.main import create_app

SECRET = "test-secret"


@pytest.fixture()
def client(tmp_path):
    app = create_app(db_path=tmp_path / "test.db", enroll_secret=SECRET)
    with TestClient(app) as c:
        yield c


def register(client: TestClient, name: str) -> dict:
    r = client.post(
        "/v1/devices/register",
        json={"name": name, "enroll_secret": SECRET},
    )
    assert r.status_code == 200, r.text
    return r.json()


def auth(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def send(client: TestClient, token: str, batch_id: str, codes: list[str]):
    return client.post(
        "/v1/scan-batches",
        headers=auth(token),
        json={
            "batch_id": batch_id,
            "app_version": "0.1.0",
            "captured_at": "2026-10-23T08:00:00+08:00",
            "items": [
                {"raw_text": code, "format": "Code128", "line_count": 5}
                for code in codes
            ],
        },
    )


def test_health(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["ok"] is True


def test_admin_page(client):
    r = client.get("/admin", follow_redirects=True)
    assert r.status_code == 200
    assert "条码汇总" in r.text
    assert client.get("/", follow_redirects=False).status_code in (302, 307)


def test_register_rejects_bad_secret(client):
    r = client.post(
        "/v1/devices/register",
        json={"name": "x", "enroll_secret": "wrong"},
    )
    assert r.status_code == 403


def test_ingest_requires_token(client):
    r = client.post(
        "/v1/scan-batches",
        json={"batch_id": "batch-0001", "items": [{"raw_text": "123"}]},
    )
    assert r.status_code == 401


def test_merge_across_devices(client):
    a = register(client, "device-a")
    b = register(client, "device-b")

    r1 = send(client, a["token"], "batch-aaaa-0001", ["111", "222"])
    assert r1.status_code == 201, r1.text
    assert r1.json()["accepted"] == 2

    r2 = send(client, b["token"], "batch-bbbb-0001", ["222", "333"])
    assert r2.status_code == 201, r2.text
    assert r2.json()["accepted"] == 2

    page = client.get("/v1/barcodes", headers=auth(a["token"])).json()
    assert page["total"] == 3
    by_code = {i["code"]: i for i in page["items"]}
    assert by_code["222"]["scan_count"] == 2
    assert by_code["222"]["device_count"] == 2
    assert by_code["111"]["device_count"] == 1

    stats = client.get("/v1/stats", headers=auth(a["token"])).json()
    assert stats == {
        "barcodes": 3,
        "events": 4,
        "devices": 2,
        "last_received_at": stats["last_received_at"],
    }


def test_replay_batch_is_idempotent(client):
    a = register(client, "device-a")
    send(client, a["token"], "batch-aaaa-0002", ["111", "222"])

    replay = send(client, a["token"], "batch-aaaa-0002", ["111", "222"])
    assert replay.status_code == 201
    body = replay.json()
    assert body["accepted"] == 0
    assert body["duplicated"] == 2

    page = client.get("/v1/barcodes", headers=auth(a["token"])).json()
    by_code = {i["code"]: i for i in page["items"]}
    assert by_code["111"]["scan_count"] == 1


def test_dedup_within_batch(client):
    a = register(client, "device-a")
    r = send(client, a["token"], "batch-aaaa-0003", ["999", "999"])
    assert r.json()["accepted"] == 1
    assert r.json()["duplicated"] == 1


def test_search_and_paging(client):
    a = register(client, "device-a")
    send(client, a["token"], "batch-aaaa-0004", ["111", "222", "333"])

    page = client.get(
        "/v1/barcodes", headers=auth(a["token"]), params={"q": "22"}
    ).json()
    assert page["total"] == 1
    assert page["items"][0]["code"] == "222"

    paged = client.get(
        "/v1/barcodes", headers=auth(a["token"]), params={"limit": 2, "offset": 2}
    ).json()
    assert paged["total"] == 3
    assert len(paged["items"]) == 1


def test_export_csv(client):
    a = register(client, "device-a")
    send(client, a["token"], "batch-aaaa-0005", ["001", "002"])

    r = client.get("/v1/barcodes/export.csv", headers=auth(a["token"]))
    assert r.status_code == 200
    assert r.text.startswith("\ufeff")
    assert "barcode,first_seen_at,last_seen_at,scan_count,device_count" in r.text
    assert "001" in r.text and "002" in r.text


def test_empty_items_rejected(client):
    a = register(client, "device-a")
    r = client.post(
        "/v1/scan-batches",
        headers=auth(a["token"]),
        json={"batch_id": "batch-aaaa-0006", "items": []},
    )
    assert r.status_code == 422


def test_concurrent_devices_and_replays(client):
    """多设备并发 + 同批次并发重放，不能 500，也不能重复计数。"""
    from concurrent.futures import ThreadPoolExecutor

    with ThreadPoolExecutor(max_workers=8) as pool:
        tokens = list(pool.map(lambda i: register(client, f"dev-{i}")["token"], range(8)))

    def send(i: int):
        codes = [f"C{(i * 3 + j) % 20:03d}" for j in range(10)]
        return client.post(
            "/v1/scan-batches",
            headers=auth(tokens[i % len(tokens)]),
            json={
                "batch_id": f"batch-conc-{i:04d}",
                "items": [{"raw_text": c} for c in codes],
            },
        )

    with ThreadPoolExecutor(max_workers=8) as pool:
        responses = list(pool.map(send, range(24)))

    assert all(r.status_code == 201 for r in responses), [
        (r.status_code, r.text) for r in responses if r.status_code != 201
    ]

    # 同一个 batch 并发重放 8 次，只能被接收一次
    def replay(_):
        return client.post(
            "/v1/scan-batches",
            headers=auth(tokens[0]),
            json={
                "batch_id": "batch-conc-replay",
                "items": [{"raw_text": f"R{i}"} for i in range(5)],
            },
        )

    with ThreadPoolExecutor(max_workers=8) as pool:
        replays = list(pool.map(replay, range(8)))

    assert all(r.status_code == 201 for r in replays)
    assert sum(r.json()["accepted"] for r in replays) == 5

    stats = client.get("/v1/stats", headers=auth(tokens[0])).json()
    assert stats["events"] == 24 * 10 + 5


def test_batch_id_cannot_be_hijacked_by_another_device(client):
    a = register(client, "device-a")
    b = register(client, "device-b")
    send(client, a["token"], "batch-hijack-0001", ["111"])

    r = send(client, b["token"], "batch-hijack-0001", ["222"])
    assert r.status_code == 409

    page = client.get("/v1/barcodes", headers=auth(a["token"])).json()
    assert {i["code"] for i in page["items"]} == {"111"}


def test_credentials_file_records_token(client):
    a = register(client, "仓库手机")
    path = client.app.state.credentials_path
    text = path.read_text(encoding="utf-8")
    assert a["device_id"] in text
    assert a["token"] in text
    assert "仓库手机" in text
    assert (path.stat().st_mode & 0o777) == 0o600


def test_future_captured_at_is_clamped(client):
    from datetime import datetime, timezone

    a = register(client, "device-a")
    r = client.post(
        "/v1/scan-batches",
        headers=auth(a["token"]),
        json={
            "batch_id": "batch-future-0001",
            "captured_at": "2099-01-01T00:00:00Z",
            "items": [{"raw_text": "123"}],
        },
    )
    assert r.status_code == 201

    item = client.get("/v1/barcodes", headers=auth(a["token"])).json()["items"][0]
    seen = datetime.fromisoformat(item["last_seen_at"])
    assert seen <= datetime.now(timezone.utc).replace(microsecond=0)
    assert seen.year != 2099


def test_csv_formula_injection_escaped(client):
    a = register(client, "device-a")
    send(client, a["token"], "batch-csv-0001", ["=SUM(A1:A2)"])

    r = client.get("/v1/barcodes/export.csv", headers=auth(a["token"]))
    assert "'=SUM(A1:A2)" in r.text


def test_control_characters_rejected(client):
    a = register(client, "device-a")
    r = send(client, a["token"], "batch-ctrl-0001", ["12\n34"])
    assert r.status_code == 422


def test_security_headers(client):
    r = client.get("/health")
    assert r.headers["X-Content-Type-Options"] == "nosniff"
    assert r.headers["X-Frame-Options"] == "DENY"

    admin = client.get("/admin")
    assert "frame-ancestors 'none'" in admin.headers["Content-Security-Policy"]
    assert admin.headers["Cache-Control"] == "no-store"


def test_query_length_limit(client):
    a = register(client, "device-a")
    r = client.get(
        "/v1/barcodes", headers=auth(a["token"]), params={"q": "x" * 201}
    )
    assert r.status_code == 422


def test_revoked_device_token_rejected(client):
    a = register(client, "device-a")
    assert client.get("/v1/stats", headers=auth(a["token"])).status_code == 200

    conn = client.app.state.store.connect()
    with conn:
        conn.execute(
            "UPDATE devices SET revoked_at = '2026-10-23T00:00:00+00:00' WHERE id = ?",
            (a["device_id"],),
        )

    assert client.get("/v1/stats", headers=auth(a["token"])).status_code == 401


def test_health_reports_db_failure(client, monkeypatch):
    import sqlite3

    def boom(self):
        raise sqlite3.OperationalError("disk I/O error")

    monkeypatch.setattr(type(client.app.state.store), "connect", boom)
    r = client.get("/health")
    assert r.status_code == 503
    assert r.json()["ok"] is False
