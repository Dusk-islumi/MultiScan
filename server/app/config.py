import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent

DEFAULT_DB_PATH = BASE_DIR / "data" / "scan.db"
DEFAULT_CREDENTIALS_PATH = BASE_DIR / "data" / "credentials.txt"
DEFAULT_ENROLL_SECRET = "change-me"

# 看到默认口令就拒绝启动。测试用 STRICT_SECRETS=0 关掉（conftest 已设置）。
STRICT_SECRETS = os.getenv("STRICT_SECRETS", "1").strip().lower() not in {
    "0",
    "false",
    "no",
}

MAX_BATCH_ITEMS = int(os.getenv("MAX_BATCH_ITEMS", "500"))
MAX_CODE_LEN = 512

# 生产环境默认关闭 /docs /redoc，需要时用 ENABLE_DOCS=1 打开
DOCS_ENABLED = os.getenv("ENABLE_DOCS", "0").strip().lower() in {"1", "true", "yes"}

# 设备 last_seen_at 写库节流（秒），避免每个读请求都产生一次写事务
LAST_SEEN_THROTTLE_SECONDS = int(os.getenv("LAST_SEEN_THROTTLE_SECONDS", "60"))

# 客户端时间最多允许超前多少秒，超过则按服务器时间处理
MAX_CLOCK_SKEW_SECONDS = int(os.getenv("MAX_CLOCK_SKEW_SECONDS", "300"))

# 管理员控制台口令。留空则**自动生成**随机口令并写入 data/admin_token.txt（0600），
# 不需要人工配置，也不需要记忆默认值。
ADMIN_TOKEN = os.getenv("ADMIN_TOKEN", "").strip()
ADMIN_TOKEN_PATH = Path(
    os.getenv("ADMIN_TOKEN_PATH", str(BASE_DIR / "data" / "admin_token.txt"))
)

# ---- 反向代理信任 ----
# 只有当直连对端在信任网段内时才采信 X-Forwarded-For。
# 默认信任回环（nginx 与 uvicorn 同机），其它情况用直连地址。
TRUSTED_PROXIES = tuple(
    p.strip()
    for p in os.getenv("TRUSTED_PROXIES", "127.0.0.1,::1").split(",")
    if p.strip()
)

# 注册接口：同一 IP 的失败次数上限（配合 throttle 使用）
ENROLL_MAX_FAILURES = int(os.getenv("ENROLL_MAX_FAILURES", "5"))
ENROLL_LOCKOUT_SECONDS = int(os.getenv("ENROLL_LOCKOUT_SECONDS", "300"))
ENROLL_FAILURE_WINDOW_SECONDS = int(os.getenv("ENROLL_FAILURE_WINDOW_SECONDS", "900"))

# 回收站最多保留多少批快照，超出后自动丢弃最旧的，避免库无限膨胀
MAX_TRASH_BATCHES = int(os.getenv("MAX_TRASH_BATCHES", "20"))
# 单个快照的 payload 上限（字节），超过则放弃入回收站但照常清空
MAX_TRASH_PAYLOAD_BYTES = int(os.getenv("MAX_TRASH_PAYLOAD_BYTES", str(8 * 1024 * 1024)))
