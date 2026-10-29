from app.credentials import CredentialLedger, parse_credentials


def test_credentials_file_keeps_only_latest(tmp_path):
    path = tmp_path / "credentials.txt"
    ledger = CredentialLedger(path)

    ledger.record_enroll("old-secret")
    ledger.record_device("dev_1", "phone", "token-old", "2026-10-23T00:00:00+00:00")
    ledger.record_enroll("new-secret")
    ledger.record_device("dev_1", "phone", "token-new", "2026-10-23T00:00:01+00:00")
    ledger.record_device("dev_2", "pad", "token-2", "2026-10-23T00:00:02+00:00")

    text = path.read_text(encoding="utf-8")
    assert text.count("[enroll]") == 1
    assert "secret=new-secret" in text
    assert "old-secret" not in text
    assert text.count("[device]") == 2
    assert "token-old" not in text

    devices = {d["device_id"]: d for d in parse_credentials(path)}
    assert devices["dev_1"]["token"] == "token-new"
    assert devices["dev_2"]["name"] == "pad"
    assert (path.stat().st_mode & 0o777) == 0o600
