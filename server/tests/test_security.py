"""安全加固相关的测试：管理员口令自动生成、控制台认证、注册防爆破。"""

from __future__ import annotations

import os

import pytest
from fastapi.testclient import TestClient

from app.auth import (
    client_ip,
    load_or_create_admin_token,
    verify_admin_token,
)
from app.main import create_app
from app.schemas import CLEAR_CONFIRM_PHRASE as CLEAR_PHRASE
from app.throttle import LoginThrottle

SECRET = "test-secret"


@pytest.fixture()
def app_factory(tmp_path):
    def make(**kwargs):
        kwargs.setdefault("db_path", tmp_path / "test.db")
        kwargs.setdefault("enroll_secret", SECRET)
        kwargs.setdefault("admin_token", "admin-test-token")
        kwargs.setdefault("credentials_path", tmp_path / "credentials.txt")
        return create_app(**kwargs)

    return make


@pytest.fixture()
def client(app_factory):
    with TestClient(app_factory()) as c:
        yield c


def auth(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


# --------------------------------------------------------- 管理员口令生成


@pytest.fixture()
def no_admin_env(monkeypatch):
    """模拟"完全没有配置管理员口令"的干净环境。"""
    monkeypatch.delenv("ADMIN_TOKEN", raising=False)
    monkeypatch.setattr("app.config.ADMIN_TOKEN", "")
    return None


def test_admin_token_is_generated_and_persisted(tmp_path, no_admin_env):
    path = tmp_path / "admin_token.txt"
    token = load_or_create_admin_token(None, path)

    assert len(token) >= 32
    assert path.exists()
    assert (path.stat().st_mode & 0o777) == 0o600
    # 文件里存的应该就是这个口令
    assert token in path.read_text(encoding="utf-8")


def test_admin_token_stable_across_restarts(tmp_path, no_admin_env):
    path = tmp_path / "admin_token.txt"
    first = load_or_create_admin_token(None, path)
    second = load_or_create_admin_token(None, path)
    assert first == second, "重启后口令必须保持不变，否则登录态全失效"


def test_admin_token_regenerated_when_file_missing(tmp_path, no_admin_env):
    path = tmp_path / "admin_token.txt"
    first = load_or_create_admin_token(None, path)
    path.unlink()
    second = load_or_create_admin_token(None, path)
    assert first != second
    assert len(second) >= 32


def test_admin_token_env_override_wins(tmp_path, monkeypatch):
    path = tmp_path / "admin_token.txt"
    load_or_create_admin_token(None, path)  # 先造一个文件
    monkeypatch.setattr("app.config.ADMIN_TOKEN", "from-env")
    assert load_or_create_admin_token(None, path) == "from-env"


def test_admin_token_never_default_guessable(tmp_path, no_admin_env):
    """核心回归：不存在 change-me / admin 之类的默认口令。"""
    token = load_or_create_admin_token(None, tmp_path / "t.txt")
    for bad in ("change-me", "admin", "password", "secret", "123456", ""):
        assert token != bad


# ------------------------------------------------------------ 控制台认证


def test_admin_requires_login(client):
    r = client.get("/admin", follow_redirects=False)
    assert r.status_code in (303, 307, 302)
    assert r.headers["location"].endswith("/admin/login")


def test_admin_login_page_public(client):
    r = client.get("/admin/login")
    assert r.status_code == 200
    assert "管理员登录" in r.text


def test_admin_rejects_wrong_token(client):
    r = client.post("/admin/login", json={"token": "wrong"})
    assert r.status_code == 401


def test_admin_login_sets_httponly_cookie(app_factory):
    # Secure cookie 只在 https 下回传，这里用 https 基址模拟真实部署
    with TestClient(app_factory(), base_url="https://testserver") as c:
        r = c.post("/admin/login", json={"token": "admin-test-token"})
        assert r.status_code == 200
        cookie = r.headers["set-cookie"].lower()
        assert "httponly" in cookie
        assert "secure" in cookie
        assert "samesite=strict" in cookie

        # 带上 cookie 就能拿到页面
        page = c.get("/admin", follow_redirects=False)
        assert page.status_code == 200
        assert "条码汇总" in page.text


def test_admin_bearer_token_also_accepted(client):
    r = client.get("/admin", headers=auth("admin-test-token"), follow_redirects=False)
    assert r.status_code == 200


def test_admin_logout_clears_session(app_factory):
    with TestClient(app_factory(), base_url="https://testserver") as c:
        c.post("/admin/login", json={"token": "admin-test-token"})
        assert c.get("/admin", follow_redirects=False).status_code == 200
        c.post("/admin/logout")
        assert c.get("/admin", follow_redirects=False).status_code in (303, 307, 302)


def test_admin_page_has_no_token_input(app_factory):
    """控制台不应再让人手工填设备 token，认证走 Cookie。"""
    with TestClient(app_factory(), base_url="https://testserver") as c:
        c.post("/admin/login", json={"token": "admin-test-token"})
        html = c.get("/admin").text
    assert 'placeholder="设备 Token"' not in html
    assert "localStorage" not in html


def test_admin_login_throttled_after_failures(app_factory):
    app = app_factory()
    with TestClient(app) as c:
        for _ in range(5):
            assert c.post("/admin/login", json={"token": "wrong"}).status_code == 401
        r = c.post("/admin/login", json={"token": "wrong"})
        assert r.status_code == 429
        assert "retry-after" in {k.lower() for k in r.headers}
        # 锁定期内连正确口令也拒绝
        assert c.post("/admin/login", json={"token": "admin-test-token"}).status_code == 429


# ------------------------------------------------------------- 注册防爆破


def test_register_lockout_after_repeated_failures(app_factory):
    app = app_factory()
    with TestClient(app) as c:
        for _ in range(5):
            r = c.post("/v1/devices/register", json={"name": "x", "enroll_secret": "bad"})
            assert r.status_code == 403
        r = c.post("/v1/devices/register", json={"name": "x", "enroll_secret": "bad"})
        assert r.status_code == 429

        # 锁定期间正确口令也不能注册（防止爆破者在窗口内蒙对）
        r = c.post("/v1/devices/register", json={"name": "x", "enroll_secret": SECRET})
        assert r.status_code == 429


def test_register_success_resets_failure_counter(app_factory):
    app = app_factory()
    with TestClient(app) as c:
        for _ in range(4):
            c.post("/v1/devices/register", json={"name": "x", "enroll_secret": "bad"})
        ok = c.post("/v1/devices/register", json={"name": "ok", "enroll_secret": SECRET})
        assert ok.status_code == 200
        # 计数已被重置，又能再失败 4 次而不触发锁定
        for _ in range(4):
            r = c.post("/v1/devices/register", json={"name": "x", "enroll_secret": "bad"})
            assert r.status_code == 403


def test_throttle_lockout_expires(monkeypatch):
    clock = {"now": 1000.0}
    t = LoginThrottle(max_failures=3, window_seconds=60, lockout_seconds=10)
    monkeypatch.setattr(t, "_now", lambda: clock["now"])

    for _ in range(3):
        t.record_failure("1.2.3.4")
    assert t.retry_after("1.2.3.4") > 0

    clock["now"] += 11
    assert t.retry_after("1.2.3.4") == 0


def test_throttle_isolated_per_key():
    t = LoginThrottle(max_failures=2, window_seconds=60, lockout_seconds=10)
    t.record_failure("a")
    t.record_failure("a")
    assert t.retry_after("a") > 0
    assert t.retry_after("b") == 0


def test_throttle_bucket_count_bounded():
    t = LoginThrottle(max_failures=2, window_seconds=60, lockout_seconds=10)
    monkeypatch_cap = 20
    t._evict_if_needed  # 确保方法存在
    import app.throttle as mod

    old = mod.MAX_BUCKETS
    mod.MAX_BUCKETS = monkeypatch_cap
    try:
        for i in range(200):
            t.record_failure(f"ip-{i}")
        assert len(t._failures) <= monkeypatch_cap + 1
    finally:
        mod.MAX_BUCKETS = old


# ------------------------------------------------------------ 客户端 IP


def test_client_ip_ignores_xff_from_untrusted_peer(app_factory):
    from app.auth import configure_trusted_proxies

    configure_trusted_proxies(["127.0.0.1"])
    app = app_factory()
    with TestClient(app) as c:
        # TestClient 的 peer 是 testclient，不在可信网段，应忽略 XFF
        r = c.post(
            "/v1/devices/register",
            json={"name": "x", "enroll_secret": "bad"},
            headers={"X-Forwarded-For": "9.9.9.9"},
        )
        assert r.status_code == 403


def test_client_ip_uses_rightmost_untrusted_hop():
    """构造 request，验证取的是可信代理追加的那一跳而不是最左（可伪造）。"""

    class FakeClient:
        host = "127.0.0.1"

    class FakeRequest:
        client = FakeClient()
        headers = {"x-forwarded-for": "1.1.1.1, 2.2.2.2, 10.0.0.5"}

    from app.auth import configure_trusted_proxies

    configure_trusted_proxies(["127.0.0.1", "10.0.0.0/8"])
    assert client_ip(FakeRequest()) == "2.2.2.2"


def test_verify_admin_token_empty_never_matches():
    from app.auth import set_current_admin_token

    set_current_admin_token("abc")
    assert not verify_admin_token("")
    assert not verify_admin_token(None)
    assert verify_admin_token("abc")


# ------------------------------------------------------- 弱口令拒绝启动


def test_strict_mode_rejects_default_secret(tmp_path, monkeypatch):
    """核心回归：ENROLL_SECRET=change-me 时生产模式必须拒绝启动。"""
    monkeypatch.setattr("app.config.STRICT_SECRETS", True)
    with pytest.raises(RuntimeError, match="change-me"):
        create_app(
            db_path=tmp_path / "x.db",
            enroll_secret="change-me",
            admin_token="admin-test-token",
            credentials_path=tmp_path / "c.txt",
        )


def test_strict_mode_allows_strong_secret(tmp_path, monkeypatch):
    monkeypatch.setattr("app.config.STRICT_SECRETS", True)
    app = create_app(
        db_path=tmp_path / "y.db",
        enroll_secret="A" * 43,
        admin_token="admin-test-token",
        credentials_path=tmp_path / "c.txt",
    )
    assert app is not None


# ------------------------------------------------- 统一授权码（客户端简化）


def test_enroll_secret_works_as_api_credential(client, app_factory):
    """核心需求：客户端只拿一个授权码就能上传，无需注册。"""
    r = client.post(
        "/v1/scan-batches",
        headers=auth(SECRET),
        json={"batch_id": "batch-shared-001", "items": [{"raw_text": "AAA111"}]},
    )
    assert r.status_code == 201, r.text
    assert r.json()["accepted"] == 1

    # 同一授权码可读
    page = client.get("/v1/barcodes", headers=auth(SECRET))
    assert page.status_code == 200
    assert {i["code"] for i in page.json()["items"]} == {"AAA111"}


def test_enroll_secret_rejects_wrong_value(client):
    r = client.get("/v1/stats", headers=auth("not-the-secret"))
    assert r.status_code == 401


def test_shared_device_created_only_once(client):
    """反复用授权码不应产生大量重复设备记录。"""
    for i in range(3):
        client.post(
            "/v1/scan-batches",
            headers=auth(SECRET),
            json={"batch_id": f"batch-shared-10{i}", "items": [{"raw_text": f"Z{i}"}]},
        )
    conn = client.app.state.store.connect()
    rows = conn.execute(
        "SELECT COUNT(*) AS c FROM devices WHERE id = '__shared__'"
    ).fetchone()
    assert rows["c"] == 1


def test_device_token_still_works(client):
    """兼容性：已注册设备的 token 必须继续可用。"""
    r = client.post(
        "/v1/devices/register", json={"name": "old-pda", "enroll_secret": SECRET}
    )
    token = r.json()["token"]
    assert client.get("/v1/stats", headers=auth(token)).status_code == 200


def test_revoked_shared_device_blocks_secret(app_factory):
    app = app_factory()
    with TestClient(app) as c:
        assert c.get("/v1/stats", headers=auth(SECRET)).status_code == 200
        conn = app.state.store.connect()
        with conn:
            conn.execute(
                "UPDATE devices SET revoked_at='2026-10-23T00:00:00+00:00' WHERE id='__shared__'"
            )
        r = c.get("/v1/stats", headers=auth(SECRET))
        assert r.status_code == 403


def test_admin_cookie_can_read_data(app_factory):
    """核心 bug 回归：控制台登录后必须能读到数据，不能立刻 401。"""
    with TestClient(app_factory(), base_url="https://testserver") as c:
        # 先造点数据
        c.post(
            "/v1/scan-batches",
            headers=auth(SECRET),
            json={"batch_id": "batch-admin-001", "items": [{"raw_text": "DATA123"}]},
        )
        r = c.post("/admin/login", json={"token": "admin-test-token"})
        assert r.status_code == 200

        # Cookie 的 path 必须是 /，否则 /v1/* 收不到
        assert "path=/" in r.headers["set-cookie"].lower()
        assert "path=/admin" not in r.headers["set-cookie"].lower()

        stats = c.get("/v1/stats")
        assert stats.status_code == 200, stats.text
        assert stats.json()["barcodes"] == 1

        page = c.get("/v1/barcodes")
        assert page.status_code == 200
        assert {i["code"] for i in page.json()["items"]} == {"DATA123"}

        csv_resp = c.get("/v1/barcodes/export.csv")
        assert csv_resp.status_code == 200
        assert "DATA123" in csv_resp.text


def test_admin_cookie_cannot_write(app_factory):
    """控制台是只读的：Cookie 不能用于写入，避免被 CSRF 利用。"""
    with TestClient(app_factory(), base_url="https://testserver") as c:
        c.post("/admin/login", json={"token": "admin-test-token"})
        r = c.post(
            "/v1/scan-batches",
            json={"batch_id": "batch-csrf-001", "items": [{"raw_text": "X"}]},
        )
        assert r.status_code == 401


# --------------------------------------------------------- 一键清空条码


def _seed(client):
    client.post(
        "/v1/scan-batches",
        headers=auth(SECRET),
        json={
            "batch_id": "batch-clear-0001",
            "items": [{"raw_text": "C1"}, {"raw_text": "C2"}],
        },
    )
    client.post(
        "/v1/scan-batches",
        headers=auth(SECRET),
        json={"batch_id": "batch-clear-0002", "items": [{"raw_text": "C3"}]},
    )


def _admin_client(app_factory):
    return TestClient(app_factory(), base_url="https://testserver")


def test_clear_requires_admin(app_factory):
    """设备 token / 授权码 都不能清空数据。"""
    with TestClient(app_factory()) as c:
        _seed(c)
        r = c.post("/v1/barcodes/clear", headers=auth(SECRET), json={"confirm": CLEAR_PHRASE})
        assert r.status_code == 403
        # 数据必须还在
        assert c.get("/v1/stats", headers=auth(SECRET)).json()["barcodes"] == 3


def test_clear_requires_exact_confirm_phrase(app_factory):
    with _admin_client(app_factory) as c:
        _seed(c)
        c.post("/admin/login", json={"token": "admin-test-token"})

        for bad in ("", "清除", "clear", CLEAR_PHRASE + "x"):
            r = c.post("/v1/barcodes/clear", json={"confirm": bad})
            assert r.status_code in (401, 422), (bad, r.status_code)
        # 数据未被删除
        assert c.get("/v1/stats").json()["barcodes"] == 3


def test_clear_removes_barcodes_events_and_batches(app_factory):
    with _admin_client(app_factory) as c:
        _seed(c)
        before = c.get("/v1/stats", headers=auth(SECRET)).json()
        assert before["barcodes"] == 3
        assert before["events"] == 3

        c.post("/admin/login", json={"token": "admin-test-token"})
        r = c.post("/v1/barcodes/clear", json={"confirm": CLEAR_PHRASE})
        assert r.status_code == 200, r.text
        body = r.json()
        assert body["ok"] is True
        assert body["deleted_barcodes"] == 3
        assert body["deleted_events"] == 3
        assert body["deleted_batches"] == 2

        after = c.get("/v1/stats").json()
        assert after["barcodes"] == 0
        assert after["events"] == 0
        assert after["last_received_at"] is None
        # 设备不能被误删
        assert after["devices"] == before["devices"]


def test_clear_creates_backup(app_factory):
    app = app_factory()
    with TestClient(app, base_url="https://testserver") as c:
        _seed(c)
        c.post("/admin/login", json={"token": "admin-test-token"})
        r = c.post("/v1/barcodes/clear", json={"confirm": CLEAR_PHRASE})
        assert r.status_code == 200

        backup = r.json()["backup_path"]
        assert backup, "必须生成备份"
        from pathlib import Path

        p = Path(backup)
        assert p.exists()
        assert (p.stat().st_mode & 0o777) == 0o600

        # 备份里应该还留着被删的数据
        import sqlite3

        conn = sqlite3.connect(p)
        try:
            assert conn.execute("SELECT COUNT(*) FROM barcodes").fetchone()[0] == 3
        finally:
            conn.close()


def test_clear_is_idempotent(app_factory):
    with _admin_client(app_factory) as c:
        c.post("/admin/login", json={"token": "admin-test-token"})
        r1 = c.post("/v1/barcodes/clear", json={"confirm": CLEAR_PHRASE})
        assert r1.status_code == 200
        assert r1.json()["deleted_barcodes"] == 0

        r2 = c.post("/v1/barcodes/clear", json={"confirm": CLEAR_PHRASE})
        assert r2.status_code == 200
        assert r2.json()["deleted_barcodes"] == 0


def test_clear_allows_reupload_same_batch_id(app_factory):
    """清空后应能重新上传同一个 batch_id，否则客户端会永远同步不上去。"""
    with _admin_client(app_factory) as c:
        _seed(c)
        c.post("/admin/login", json={"token": "admin-test-token"})
        c.post("/v1/barcodes/clear", json={"confirm": CLEAR_PHRASE})

        c.post("/admin/logout")
        r = c.post(
            "/v1/scan-batches",
            headers=auth(SECRET),
            json={
                "batch_id": "batch-clear-0001",
                "items": [{"raw_text": "C1"}, {"raw_text": "C2"}],
            },
        )
        assert r.status_code == 201, r.text
        assert r.json()["accepted"] == 2


# --------------------------------------------------------------- 回收站


def _clear_with_admin(c):
    c.post("/admin/login", json={"token": "admin-test-token"})
    return c.post("/v1/barcodes/clear", json={"confirm": CLEAR_PHRASE})


def test_clear_creates_trash_entry(app_factory):
    with _admin_client(app_factory) as c:
        _seed(c)
        r = _clear_with_admin(c)
        assert r.status_code == 200
        trash_id = r.json()["trash_id"]
        assert trash_id and trash_id.startswith("tr_")

        listing = c.get("/v1/trash").json()
        assert listing["total"] == 1
        item = listing["items"][0]
        assert item["id"] == trash_id
        assert item["barcode_count"] == 3
        assert item["event_count"] == 3
        assert item["batch_count"] == 2


def test_trash_requires_admin(app_factory):
    with TestClient(app_factory()) as c:
        assert c.get("/v1/trash", headers=auth(SECRET)).status_code == 403
        assert c.delete("/v1/trash", headers=auth(SECRET)).status_code == 403
        assert (
            c.post("/v1/trash/tr_x/restore", headers=auth(SECRET)).status_code == 403
        )


def test_restore_brings_data_back(app_factory):
    with _admin_client(app_factory) as c:
        _seed(c)
        assert c.get("/v1/stats", headers=auth(SECRET)).json()["barcodes"] == 3
        _clear_with_admin(c)
        assert c.get("/v1/stats").json()["barcodes"] == 0

        trash_id = c.get("/v1/trash").json()["items"][0]["id"]
        r = c.post(f"/v1/trash/{trash_id}/restore")
        assert r.status_code == 200, r.text
        body = r.json()
        assert body["restored_barcodes"] == 3
        assert body["restored_events"] == 3
        assert body["restored_batches"] == 2

        stats = c.get("/v1/stats").json()
        assert stats["barcodes"] == 3
        assert stats["events"] == 3

        page = c.get("/v1/barcodes").json()
        assert {i["code"] for i in page["items"]} == {"C1", "C2", "C3"}

        # 恢复后快照应出站
        assert c.get("/v1/trash").json()["total"] == 0


def test_restore_is_one_shot(app_factory):
    """重复恢复同一批必须 404，否则计数会翻倍。"""
    with _admin_client(app_factory) as c:
        _seed(c)
        _clear_with_admin(c)
        trash_id = c.get("/v1/trash").json()["items"][0]["id"]

        assert c.post(f"/v1/trash/{trash_id}/restore").status_code == 200
        assert c.post(f"/v1/trash/{trash_id}/restore").status_code == 404
        assert c.get("/v1/stats").json()["barcodes"] == 3


def test_restore_merges_with_existing_data(app_factory):
    """清空后再扫新码，然后恢复，应该合并而不是覆盖。"""
    with _admin_client(app_factory) as c:
        _seed(c)
        _clear_with_admin(c)

        # 清空后新扫了 C2（原来也扫过）和 C9（全新）
        c.post("/admin/logout")
        c.post(
            "/v1/scan-batches",
            headers=auth(SECRET),
            json={
                "batch_id": "batch-after-clear",
                "items": [{"raw_text": "C2"}, {"raw_text": "C9"}],
            },
        )

        c.post("/admin/login", json={"token": "admin-test-token"})
        trash_id = c.get("/v1/trash").json()["items"][0]["id"]
        r = c.post(f"/v1/trash/{trash_id}/restore")
        assert r.status_code == 200
        # 快照里 3 条条码全部回来了（含合并的那条 T2/C2）
        assert r.json()["restored_barcodes"] == 3

        page = c.get("/v1/barcodes?limit=500").json()
        by_code = {i["code"]: i for i in page["items"]}
        assert set(by_code) == {"C1", "C2", "C3", "C9"}
        # C2 在清空前扫过 1 次、清空后又扫 1 次，合计 2
        assert by_code["C2"]["scan_count"] == 2
        assert by_code["C1"]["scan_count"] == 1


def test_delete_trash_item(app_factory):
    with _admin_client(app_factory) as c:
        _seed(c)
        _clear_with_admin(c)
        trash_id = c.get("/v1/trash").json()["items"][0]["id"]

        assert c.delete(f"/v1/trash/{trash_id}").status_code == 200
        assert c.get("/v1/trash").json()["total"] == 0
        # 删掉的快照不能再恢复
        assert c.post(f"/v1/trash/{trash_id}/restore").status_code == 404
        assert c.get("/v1/stats").json()["barcodes"] == 0


def test_delete_unknown_trash_returns_404(app_factory):
    with _admin_client(app_factory) as c:
        c.post("/admin/login", json={"token": "admin-test-token"})
        assert c.delete("/v1/trash/tr_nope").status_code == 404
        assert c.post("/v1/trash/tr_nope/restore").status_code == 404


def test_clear_all_trash(app_factory):
    with _admin_client(app_factory) as c:
        _seed(c)
        _clear_with_admin(c)
        # 再攒一批
        c.post("/admin/logout")
        c.post(
            "/v1/scan-batches",
            headers=auth(SECRET),
            json={"batch_id": "batch-2nd", "items": [{"raw_text": "D1"}]},
        )
        c.post("/admin/login", json={"token": "admin-test-token"})
        c.post("/v1/barcodes/clear", json={"confirm": CLEAR_PHRASE})
        assert c.get("/v1/trash").json()["total"] == 2

        r = c.delete("/v1/trash")
        assert r.status_code == 200
        assert r.json()["deleted"] == 2
        assert c.get("/v1/trash").json()["total"] == 0


def test_trash_is_capped(app_factory, monkeypatch):
    """回收站不能无限增长，超出上限自动丢最旧的。"""
    monkeypatch.setattr("app.config.MAX_TRASH_BATCHES", 2)
    with _admin_client(app_factory) as c:
        for i in range(4):
            c.post(
                "/v1/scan-batches",
                headers=auth(SECRET),
                json={"batch_id": f"batch-cap-{i}", "items": [{"raw_text": f"E{i}"}]},
            )
            c.post("/admin/login", json={"token": "admin-test-token"})
            c.post("/v1/barcodes/clear", json={"confirm": CLEAR_PHRASE})
        assert c.get("/v1/trash").json()["total"] == 2


def test_clear_empty_db_creates_no_trash(app_factory):
    with _admin_client(app_factory) as c:
        r = _clear_with_admin(c)
        assert r.status_code == 200
        assert r.json()["trash_id"] is None
        assert c.get("/v1/trash").json()["total"] == 0
