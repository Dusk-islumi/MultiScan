"""登录失败限速（进程内滑动窗口）。

公网入口只有 nginx 的 rate limit，那是按 IP 的粗粒度限制，
无法阻止"慢速枚举"或换 IP 的分布式爆破。这里在应用层再加一道：
按来源 IP + 账号维度统计最近的失败次数，超过阈值就临时锁定。

设计取舍：
- 只统计**失败**，成功即清零，正常用户完全无感
- 进程内存储（服务是单进程 uvicorn），不用引入 Redis
- 锁定量固定为 60 秒，不做指数退避，避免误锁后无法自愈
"""

from __future__ import annotations

import threading
import time
from collections import deque

# 窗口内允许的失败次数，超过即锁定
MAX_FAILURES = 5
# 失败计数窗口（秒）：失败记录只在这个时间范围内累计
FAILURE_WINDOW_SECONDS = 300
# 触发后的锁定时长（秒）
LOCKOUT_SECONDS = 60

# 桶数量上限，防止大量伪造 IP 撑爆内存
MAX_BUCKETS = 10000


class LoginThrottle:
    """按 key（通常为 client_ip 或 client_ip|account）记录失败次数。"""

    def __init__(
        self,
        max_failures: int = MAX_FAILURES,
        window_seconds: float = FAILURE_WINDOW_SECONDS,
        lockout_seconds: float = LOCKOUT_SECONDS,
    ) -> None:
        self.max_failures = max_failures
        self.window_seconds = window_seconds
        self.lockout_seconds = lockout_seconds
        self._failures: dict[str, deque[float]] = {}
        self._locked_until: dict[str, float] = {}
        self._lock = threading.Lock()

    def _now(self) -> float:
        return time.monotonic()

    def _prune(self, key: str, now: float) -> deque[float]:
        bucket = self._failures.get(key)
        if bucket is None:
            return deque()
        cutoff = now - self.window_seconds
        while bucket and bucket[0] < cutoff:
            bucket.popleft()
        return bucket

    def _evict_if_needed(self, now: float) -> None:
        """桶过多时清掉过期项，仍过多则整体丢弃最老的桶。"""
        if len(self._failures) <= MAX_BUCKETS:
            return
        for key in list(self._failures):
            if not self._prune(key, now):
                self._failures.pop(key, None)
        for key, until in list(self._locked_until.items()):
            if until <= now:
                self._locked_until.pop(key, None)
        # 兜底：极端情况下直接清空，宁可放过也不撑爆内存
        if len(self._failures) > MAX_BUCKETS:
            self._failures.clear()

    def retry_after(self, key: str) -> int:
        """还剩多少秒才解锁；0 表示未锁定。"""
        now = self._now()
        with self._lock:
            until = self._locked_until.get(key)
            if until is None:
                return 0
            remaining = until - now
            if remaining <= 0:
                self._locked_until.pop(key, None)
                return 0
            return int(remaining) + 1

    def record_failure(self, key: str) -> None:
        now = self._now()
        with self._lock:
            self._evict_if_needed(now)
            bucket = self._prune(key, now)
            bucket.append(now)
            self._failures[key] = bucket
            if len(bucket) >= self.max_failures:
                self._locked_until[key] = now + self.lockout_seconds

    def reset(self, key: str) -> None:
        with self._lock:
            self._failures.pop(key, None)
            self._locked_until.pop(key, None)
