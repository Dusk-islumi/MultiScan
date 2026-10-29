"""明文凭据留档。

服务端数据库只保存 token 的 SHA-256，token 原文仅在注册响应里出现一次。
为了便于运维查看/找回，这里在注册成功后把设备 token 追加写入一个 0600 的文件。

注意：该文件等同于所有设备的访问权限，必须和 scan.db 一起备份、一起保护。
"""

from __future__ import annotations

import os
import threading
from datetime import datetime, timezone
from pathlib import Path

_LOCK = threading.Lock()

HEADER = """\
# ============================================================================
# Barcode Sync Server 凭据文件（明文）
# 权限：600，仅服务进程属主可读
# 说明：数据库只存 token 的 SHA-256，token 原文只在本文件留档。
# 泄露本文件 = 泄露所有设备权限；请勿提交到 git，勿随意外发。
# 查看：python3 server/deploy/show_credentials.py
# ============================================================================
# 格式：<UTC 时间> [enroll|device] key=value ...
"""


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


class CredentialLedger:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        try:
            os.chmod(self.path.parent, 0o700)
        except OSError:
            pass
        with _LOCK:
            if not self.path.exists():
                self._append_raw(HEADER)
            try:
                os.chmod(self.path, 0o600)
            except OSError:
                pass

    def _append_raw(self, text: str) -> None:
        fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        try:
            os.write(fd, text.encode("utf-8"))
        finally:
            os.close(fd)

    def _last_enroll_secret(self) -> str | None:
        try:
            lines = self.path.read_text(encoding="utf-8").splitlines()
        except OSError:
            return None
        for line in reversed(lines):
            if " [enroll] " not in line:
                continue
            for part in line.split():
                if part.startswith("secret="):
                    return part[len("secret=") :]
        return None

    def _compact(self) -> None:
        """把日志式文件压缩成快照：只保留最新 enroll 和每台设备的最新 token。

        否则轮换后旧 token 会一直留在文件和备份里，容易误用。
        """
        try:
            lines = self.path.read_text(encoding="utf-8").splitlines()
        except OSError:
            return

        header: list[str] = []
        records_started = False
        enroll: str | None = None
        devices: dict[str, str] = {}
        order: list[str] = []

        for line in lines:
            if not records_started and (line.startswith("#") or not line.strip()):
                header.append(line)
                continue
            records_started = True

            if " [enroll] " in line:
                enroll = line
            elif " [device] " in line:
                device_id = None
                for part in line.split():
                    if part.startswith("id="):
                        device_id = part[3:]
                        break
                if device_id:
                    if device_id not in devices:
                        order.append(device_id)
                    devices[device_id] = line

        output = header + ([enroll] if enroll else []) + [devices[d] for d in order]
        tmp = self.path.with_name(self.path.name + ".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        try:
            os.write(fd, ("\n".join(output) + "\n").encode("utf-8"))
        finally:
            os.close(fd)
        os.chmod(tmp, 0o600)
        os.replace(tmp, self.path)

    def record_enroll(self, secret: str, source: str = "env") -> None:
        """注册口令变化时留档（不记录每次启动）。"""
        if not secret or self._last_enroll_secret() == secret:
            return
        line = f"{_now()} [enroll] secret={secret} source={source}\n"
        with _LOCK:
            self._append_raw(line)
            self._compact()

    def record_device(
        self, device_id: str, name: str, token: str, created_at: str
    ) -> None:
        # name 放最后，允许包含空格
        line = (
            f"{_now()} [device] id={device_id} token={token} "
            f"created_at={created_at} name={name}\n"
        )
        with _LOCK:
            self._append_raw(line)
            self._compact()


def parse_credentials(path: str | Path) -> list[dict[str, str]]:
    """给展示脚本用：解析出所有设备记录。"""
    devices: list[dict[str, str]] = []
    try:
        lines = Path(path).read_text(encoding="utf-8").splitlines()
    except OSError:
        return devices

    for line in lines:
        if " [device] " not in line:
            continue
        head, _, name = line.partition(" name=")
        record: dict[str, str] = {"name": name}
        for part in head.split():
            if part.startswith("id="):
                record["device_id"] = part[3:]
            elif part.startswith("token="):
                record["token"] = part[6:]
            elif part.startswith("created_at="):
                record["created_at"] = part[11:]
        if "device_id" in record and "token" in record:
            devices.append(record)
    return devices
