import os

# 避免 import app.main 时污染仓库目录
os.environ.setdefault("DB_PATH", "/tmp/opencode/barcode-import.db")
# 测试里会显式传入 enroll_secret/admin_token，关掉"默认口令拒绝启动"的强制校验
os.environ.setdefault("STRICT_SECRETS", "0")
# 测试不落盘管理员口令
os.environ.setdefault("ADMIN_TOKEN", "test-admin-token")
