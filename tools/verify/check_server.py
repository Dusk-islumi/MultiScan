#!/usr/bin/env python3
"""验收用：检查本地 dev server / 真实服务端上的合并表是否符合预期。

用法示例：
    python tools/verify/check_server.py --base http://127.0.0.1:8000 \
        --code 0123456789 --code 9012345678 --expect-barcodes 2 --expect-scan-count 0123456789=2

退出码：0=全部断言通过；1=有断言失败。
仅依赖标准库；只读（除了 GET）。
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import sys
import urllib.error
import urllib.request


def http_get(url: str) -> tuple[int, bytes, dict]:
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, resp.read(), dict(resp.headers)
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read(), dict(exc.headers or {})


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Verify MultiScan server merged barcode table")
    parser.add_argument("--base", default="http://127.0.0.1:8000")
    parser.add_argument("--code", action="append", default=[], help="expected barcode code (repeatable)")
    parser.add_argument("--expect-barcodes", type=int, default=None)
    parser.add_argument("--expect-scan-count", action="append", default=[],
                        help="code=count assertion (repeatable)")
    parser.add_argument("--min-devices", type=int, default=None)
    parser.add_argument("--check-csv", action="store_true", default=True)
    parser.add_argument("--check-admin", action="store_true", default=True)
    parser.add_argument("--q", default="", help="filter query for /v1/barcodes")
    args = parser.parse_args(argv)

    failures: list[str] = []
    base = args.base.rstrip("/")

    status, body, _ = http_get(f"{base}/health")
    if status != 200:
        failures.append(f"/health status={status}")
    else:
        health = json.loads(body)
        print(f"[health] ok={health.get('ok')} version={health.get('version')} server_time={health.get('server_time')}")
        if health.get("ok") is not True:
            failures.append("/health ok is not true")

    query = f"?q={args.q}" if args.q else ""
    status, body, _ = http_get(f"{base}/v1/barcodes{query}")
    if status != 200:
        failures.append(f"/v1/barcodes status={status}")
        barcodes: list[dict] = []
    else:
        payload = json.loads(body)
        barcodes = payload.get("items", [])
        total = payload.get("total")
        print(f"[barcodes] total={total}")
        for item in barcodes:
            print(f"  - {item.get('code')} scan_count={item.get('scan_count')} "
                  f"device_count={item.get('device_count')} last_seen_at={item.get('last_seen_at')}")
        if args.expect_barcodes is not None and int(total) != args.expect_barcodes:
            failures.append(f"barcodes total {total} != expected {args.expect_barcodes}")

    by_code = {str(item.get("code")): item for item in barcodes}
    for code in args.code:
        if code not in by_code:
            failures.append(f"missing expected code {code!r}")
    for assertion in args.expect_scan_count:
        if "=" not in assertion:
            failures.append(f"bad --expect-scan-count {assertion!r} (want code=count)")
            continue
        code, _, expected = assertion.partition("=")
        item = by_code.get(code)
        if item is None:
            failures.append(f"missing code {code!r} for scan_count assertion")
        elif int(item.get("scan_count", -1)) != int(expected):
            failures.append(f"{code}: scan_count {item.get('scan_count')} != {expected}")
    if args.min_devices is not None:
        for item in barcodes:
            if int(item.get("device_count", 0)) < args.min_devices:
                failures.append(f"{item.get('code')}: device_count {item.get('device_count')} < {args.min_devices}")

    status, body, headers = http_get(f"{base}/v1/barcodes/export.csv")
    if status != 200:
        failures.append(f"/v1/barcodes/export.csv status={status}")
    else:
        bom_ok = body.startswith(b"\xef\xbb\xbf")
        csv_text = body.decode("utf-8-sig")
        rows = list(csv.reader(io.StringIO(csv_text)))
        header = rows[0] if rows else []
        expected_header = ["barcode", "first_seen_at", "last_seen_at", "scan_count", "device_count"]
        print(f"[csv] bom={bom_ok} header={header} rows={max(0, len(rows) - 1)}")
        if not bom_ok:
            failures.append("CSV has no UTF-8 BOM")
        if header != expected_header:
            failures.append(f"CSV header {header} != {expected_header}")

    status, body, _ = http_get(f"{base}/admin")
    if status != 200:
        failures.append(f"/admin status={status}")
    else:
        print(f"[admin] status={status} bytes={len(body)}")

    if failures:
        print("\nFAILED:")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print("\nALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
