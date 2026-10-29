#!/usr/bin/env python3
"""多设备一致性 / 压力测试。

覆盖：
  - N 台设备并发注册、并发上传
  - 跨设备同一码合并（scan_count / device_count）
  - 同一 batch 并发重放幂等
  - batch_id 被其他设备抢占 -> 409
  - 错误 token / 错误注册口令 / 超大 batch
  - 分页、CSV 导出、stats 与实际上传量一致

用法：
  python3 server/tests/load_multi_device.py --base http://127.0.0.1:18081 \
      --secret test-secret --devices 12 --batches 8 --per-batch 20

建议对着临时实例跑，不要污染生产库。
"""

from __future__ import annotations

import argparse
import concurrent.futures as cf
import random
import statistics
import sys
import time
import uuid

import httpx

SHARED_CODE = "999999999999"


class Checker:
    def __init__(self) -> None:
        self.failures: list[str] = []

    def check(self, ok: bool, label: str, detail: str = "") -> None:
        if ok:
            print(f"  [OK]   {label}")
        else:
            print(f"  [FAIL] {label}  {detail}")
            self.failures.append(f"{label} {detail}".strip())


def pct(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    values = sorted(values)
    idx = min(len(values) - 1, int(round((p / 100) * (len(values) - 1))))
    return values[idx]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:18081")
    ap.add_argument("--secret", default="test-secret")
    ap.add_argument("--devices", type=int, default=12)
    ap.add_argument("--batches", type=int, default=8)
    ap.add_argument("--per-batch", type=int, default=20)
    ap.add_argument("--concurrency", type=int, default=32)
    ap.add_argument("--timeout", type=float, default=30.0)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    if args.per_batch < 2:
        ap.error("--per-batch 至少为 2（需要放共享码）")

    base = args.base.rstrip("/")
    run_id = uuid.uuid4().hex[:8]
    rng = random.Random(args.seed)
    c = Checker()
    latencies: list[float] = []

    print(f"目标 {base}  设备={args.devices}  批次/设备={args.batches}  "
          f"码/批={args.per_batch}  并发={args.concurrency}  run={run_id}")

    client = httpx.Client(
        base_url=base,
        timeout=args.timeout,
        limits=httpx.Limits(max_connections=args.concurrency + 10),
    )

    # ---------------------------------------------------------- 负向鉴权
    print("\n[1] 鉴权")
    r = client.get("/health")
    c.check(r.status_code == 200, "health 200", r.text[:120])

    r = client.post(
        "/v1/devices/register", json={"name": "bad", "enroll_secret": "definitely-wrong"}
    )
    c.check(r.status_code == 403, "错误注册口令 -> 403", f"got {r.status_code}")

    r = client.get("/v1/stats")
    c.check(r.status_code == 401, "无 token -> 401", f"got {r.status_code}")

    r = client.get("/v1/stats", headers={"Authorization": "Bearer nope"})
    c.check(r.status_code == 401, "错误 token -> 401", f"got {r.status_code}")

    # ---------------------------------------------------------- 注册
    print(f"\n[2] 并发注册 {args.devices} 台设备")

    def register(i: int) -> dict:
        t0 = time.perf_counter()
        resp = client.post(
            "/v1/devices/register",
            json={"name": f"loadtest-{run_id}-{i:02d}", "enroll_secret": args.secret},
        )
        latencies.append((time.perf_counter() - t0) * 1000)
        resp.raise_for_status()
        return resp.json()

    try:
        with cf.ThreadPoolExecutor(max_workers=min(args.concurrency, args.devices)) as pool:
            devices = list(pool.map(register, range(args.devices)))
    except httpx.HTTPError as exc:
        print(f"注册失败：{exc}")
        return 2

    c.check(len(devices) == args.devices, "注册数量")
    c.check(len({d["device_id"] for d in devices}) == args.devices, "device_id 唯一")

    tokens = [d["token"] for d in devices]

    # ---------------------------------------------------------- 上传
    print(f"\n[3] 并发上传 {args.devices * args.batches} 个批次")

    code_pool = [f"{rng.randrange(10**11, 10**12):012d}" for _ in range(400)]
    if SHARED_CODE in code_pool:
        code_pool.remove(SHARED_CODE)

    jobs: list[tuple[int, int, list[str]]] = []
    for di in range(args.devices):
        for bi in range(args.batches):
            sample = rng.sample(code_pool, args.per_batch)
            if bi == 0:
                sample[0] = SHARED_CODE
            jobs.append((di, bi, sample))

    batch_ids: dict[tuple[int, int], str] = {}
    for di, bi, _ in jobs:
        batch_ids[(di, bi)] = f"load-{run_id}-{di:02d}-{bi:02d}"

    def upload(job: tuple[int, int, list[str]]) -> tuple[int, int, int, str]:
        di, bi, codes = job
        t0 = time.perf_counter()
        resp = client.post(
            "/v1/scan-batches",
            headers={"Authorization": f"Bearer {tokens[di]}"},
            json={
                "batch_id": batch_ids[(di, bi)],
                "app_version": "loadtest-1.0",
                "items": [{"raw_text": code, "format": "Code128"} for code in codes],
            },
        )
        latencies.append((time.perf_counter() - t0) * 1000)
        if resp.status_code != 201:
            return resp.status_code, 0, 0, resp.text[:160]
        body = resp.json()
        return resp.status_code, body["accepted"], body["duplicated"], ""

    total_accepted = 0
    total_duplicated = 0
    bad: list[tuple] = []
    t_start = time.perf_counter()
    with cf.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        for result in pool.map(upload, jobs):
            if result[0] != 201:
                bad.append(result)
            else:
                total_accepted += result[1]
                total_duplicated += result[2]
    elapsed = time.perf_counter() - t_start

    c.check(not bad, "全部批次 201", str(bad[:3]))
    c.check(total_duplicated == 0, "首次上传无重复", f"duplicated={total_duplicated}")
    c.check(
        total_accepted == len(jobs) * args.per_batch,
        "accepted 总数正确",
        f"{total_accepted} != {len(jobs) * args.per_batch}",
    )
    print(
        f"  吞吐 {len(jobs) / elapsed:.1f} batch/s, "
        f"p50={pct(latencies, 50):.0f}ms p95={pct(latencies, 95):.0f}ms "
        f"max={max(latencies):.0f}ms"
    )

    # ---------------------------------------------------------- 合并校验
    print("\n[4] 跨设备合并")
    shared = client.get(
        "/v1/barcodes",
        headers={"Authorization": f"Bearer {tokens[0]}"},
        params={"q": SHARED_CODE},
    ).json()
    if shared["total"] != 1:
        c.check(False, "共享码存在", f"total={shared['total']}")
    else:
        item = shared["items"][0]
        c.check(item["scan_count"] == args.devices, "共享码 scan_count",
                f"{item['scan_count']} != {args.devices}")
        c.check(item["device_count"] == args.devices, "共享码 device_count",
                f"{item['device_count']} != {args.devices}")

    stats = client.get("/v1/stats", headers={"Authorization": f"Bearer {tokens[0]}"}).json()
    c.check(stats["events"] == total_accepted, "stats.events 与 accepted 一致",
            f"{stats['events']} != {total_accepted}")
    c.check(stats["devices"] >= args.devices, "stats.devices", str(stats["devices"]))

    page = client.get(
        "/v1/barcodes",
        headers={"Authorization": f"Bearer {tokens[0]}"},
        params={"limit": 5, "offset": 0},
    ).json()
    c.check(len(page["items"]) <= 5, "分页 limit 生效")
    c.check(page["total"] == stats["barcodes"], "分页 total == stats.barcodes",
            f"{page['total']} != {stats['barcodes']}")

    csv_resp = client.get("/v1/barcodes/export.csv", headers={"Authorization": f"Bearer {tokens[0]}"})
    c.check(csv_resp.status_code == 200, "CSV 导出 200", str(csv_resp.status_code))
    c.check(SHARED_CODE in csv_resp.text, "CSV 含共享码")

    # ---------------------------------------------------------- 幂等重放
    print("\n[5] 同一 batch 并发重放")
    replay_batch = f"load-{run_id}-replay"
    replay_codes = [f"7{rng.randrange(10**10, 10**11):011d}" for _ in range(args.per_batch)]

    def replay(_: int):
        t0 = time.perf_counter()
        resp = client.post(
            "/v1/scan-batches",
            headers={"Authorization": f"Bearer {tokens[0]}"},
            json={
                "batch_id": replay_batch,
                "items": [{"raw_text": code} for code in replay_codes],
            },
        )
        latencies.append((time.perf_counter() - t0) * 1000)
        return resp.status_code, resp.json()["accepted"] if resp.status_code == 201 else -1

    with cf.ThreadPoolExecutor(max_workers=8) as pool:
        replays = list(pool.map(replay, range(8)))

    c.check(all(code == 201 for code, _ in replays), "重放全部 201", str(replays))
    c.check(sum(acc for _, acc in replays) == args.per_batch, "重放只入库一次",
            f"sum={sum(acc for _, acc in replays)}")

    stats2 = client.get("/v1/stats", headers={"Authorization": f"Bearer {tokens[0]}"}).json()
    c.check(stats2["events"] == stats["events"] + args.per_batch, "重放后 events 只 +1 批",
            f"{stats2['events']} != {stats['events'] + args.per_batch}")

    # ---------------------------------------------------------- 抢占/超限
    print("\n[6] 防滥用")
    hijack = client.post(
        "/v1/scan-batches",
        headers={"Authorization": f"Bearer {tokens[1]}"},
        json={
            "batch_id": batch_ids[(0, 0)],
            "items": [{"raw_text": "123123123123"}],
        },
    )
    c.check(hijack.status_code == 409, "其他设备抢占 batch_id -> 409",
            f"got {hijack.status_code}")

    oversized = client.post(
        "/v1/scan-batches",
        headers={"Authorization": f"Bearer {tokens[0]}"},
        json={
            "batch_id": f"load-{run_id}-oversize",
            "items": [{"raw_text": f"{i:012d}"} for i in range(501)],
        },
    )
    c.check(oversized.status_code in (413, 422), "501 条 item -> 413/422",
            f"got {oversized.status_code}")

    bad_token = client.post(
        "/v1/scan-batches",
        headers={"Authorization": "Bearer invalid"},
        json={"batch_id": f"load-{run_id}-badtoken", "items": [{"raw_text": "1"}]},
    )
    c.check(bad_token.status_code == 401, "伪造 token -> 401", f"got {bad_token.status_code}")

    # ---------------------------------------------------------- 汇总
    print("\n" + "=" * 60)
    if c.failures:
        print(f"FAILED: {len(c.failures)} 项")
        for item in c.failures:
            print("  -", item)
        print(
            f"提示：本次 run_id={run_id}，若对着生产库跑，请清理 "
            f"name LIKE 'loadtest-{run_id}%' 的设备及其数据。"
        )
        return 1

    print(f"PASS: 全部检查通过（run_id={run_id}，{len(jobs) + 1} 个批次）")
    print(f"  总请求延迟 p50={pct(latencies, 50):.0f}ms "
          f"p95={pct(latencies, 95):.0f}ms p99={pct(latencies, 99):.0f}ms")
    return 0


if __name__ == "__main__":
    sys.exit(main())
