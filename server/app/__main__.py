"""命令行入口：``barcode-sync`` 或 ``python -m app``。

仅做参数解析与 uvicorn 启动，不含业务逻辑。
"""

from __future__ import annotations

import argparse
import os
import sys

from . import config


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="barcode-sync",
        description="启动条码同步服务",
    )
    parser.add_argument(
        "--host",
        default=os.getenv("HOST", "127.0.0.1"),
        help="监听地址，默认 127.0.0.1（只允许本机，公网请走 Nginx）",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=int(os.getenv("PORT", "18080")),
        help="监听端口，默认 18080",
    )
    parser.add_argument(
        "--reload",
        action="store_true",
        help="开发模式：代码变更自动重载",
    )
    args = parser.parse_args(argv)

    try:
        import uvicorn
    except ImportError:
        print("缺少 uvicorn，请先安装：pip install 'uvicorn[standard]'", file=sys.stderr)
        return 1

    # 把 config 里的默认值落成环境变量，保证 create_app() 读到的路径一致
    os.environ.setdefault("DB_PATH", str(config.DEFAULT_DB_PATH))
    os.environ.setdefault("CREDENTIALS_PATH", str(config.DEFAULT_CREDENTIALS_PATH))

    uvicorn.run(
        "app.main:app",
        host=args.host,
        port=args.port,
        reload=args.reload,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
