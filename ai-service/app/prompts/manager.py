"""运行时系统提示词解析器：内置常量 ↔ Java 后端管理的 ACTIVE 版本。

设计目标：
- Java 后端 `prompt_template` 表（status='ACTIVE'）是运行时系统提示词的唯一权威；
- Python 通过 `/internal/v1/prompt-templates` 拉取，TTL 缓存，管理员启用新版本后约
  TTL 内生效，无需重启；
- 任何失败（Java 未起、超时、解析异常、没有对应 code）一律回退到内置常量，
  绝不影响 AI 调用；`sync_url` 为空（默认/测试）时完全不联网，恒回退内置。

版本标签：被数据库覆盖时记为 ``内置版本#dbV{versionNo}``，保证 model_run 审计
能区分内置常量与数据库版本。
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass

import httpx

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class ResolvedPrompt:
    content: str
    version: str


class PromptManager:
    def __init__(
        self,
        sync_url: str,
        internal_token: str,
        ttl_seconds: int = 60,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._enabled = bool(sync_url.strip())
        self._sync_url = sync_url.rstrip("/")
        self._internal_token = internal_token
        self._ttl = ttl_seconds
        self._transport = transport
        # 惰性创建 httpx client：sync_url 为空（离线/测试）时零网络、零副作用。
        # trust_env=False：本机可能开着 Clash 等系统代理，内部端点永远不该走系统代理。
        self._client: httpx.AsyncClient | None = None
        self._lock = asyncio.Lock()
        self._cache: dict[str, tuple[str, int]] = {}  # code -> (content, versionNo)
        self._fetched_at = 0.0

    async def get_prompt(
        self,
        code: str,
        fallback_content: str,
        fallback_version: str,
    ) -> ResolvedPrompt:
        if not self._enabled:
            return ResolvedPrompt(fallback_content, fallback_version)
        await self._ensure_fresh()
        override = self._cache.get(code)
        if override is None:
            return ResolvedPrompt(fallback_content, fallback_version)
        content, version_no = override
        return ResolvedPrompt(content, f"{fallback_version}#dbV{version_no}")

    async def _ensure_fresh(self) -> None:
        if time.monotonic() - self._fetched_at < self._ttl:
            return
        async with self._lock:
            # 双检锁：并发首次调用只拉一次。
            if time.monotonic() - self._fetched_at < self._ttl:
                return
            try:
                await self._fetch()
            except Exception as exc:  # noqa: BLE001 - 统一回退内置常量
                logger.warning("prompt_sync_failed error=%s", exc)
                # 失败也推进 fetched_at，避免 Java 未启动时每个请求都重试轰炸。
                self._fetched_at = time.monotonic()

    async def _fetch(self) -> None:
        if self._client is None:
            self._client = httpx.AsyncClient(
                timeout=5.0, trust_env=False, transport=self._transport
            )
        response = await self._client.get(
            f"{self._sync_url}/internal/v1/prompt-templates",
            headers={"X-Internal-Token": self._internal_token},
        )
        response.raise_for_status()
        payload = response.json()
        if not payload.get("success"):
            raise ValueError(f"unexpected prompt-templates payload: {payload}")
        rows = payload.get("data") or []
        cache: dict[str, tuple[str, int]] = {}
        for row in rows:
            code = row.get("code")
            content = row.get("content")
            version_no = row.get("versionNo")
            if (
                isinstance(code, str)
                and isinstance(content, str)
                and content.strip()
                and isinstance(version_no, int)
            ):
                cache[code] = (content, version_no)
        if cache:
            self._cache = cache
        self._fetched_at = time.monotonic()

    async def close(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None
