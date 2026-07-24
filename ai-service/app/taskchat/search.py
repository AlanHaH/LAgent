from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass

from app.core.errors import ServiceError

logger = logging.getLogger("ai-service")


@dataclass(frozen=True)
class WebResult:
    title: str
    url: str
    snippet: str


class WebSearcher:
    """DuckDuckGo/Bing 聚合搜索（ddgs 库，免 key）。同步库，用线程池包装。"""

    def __init__(self, max_results: int = 5, timeout_seconds: float = 12.0) -> None:
        self._max_results = max_results
        self._timeout = timeout_seconds

    async def search(self, query: str) -> list[WebResult]:
        try:
            return await asyncio.wait_for(asyncio.to_thread(self._search_sync, query), timeout=self._timeout + 3)
        except ServiceError:
            raise
        except Exception as error:  # noqa: BLE001 - 搜索失败统一映射为服务不可用
            logger.warning("web search failed: %s %s", type(error).__name__, error)
            raise ServiceError("AI_DEPENDENCY_UNAVAILABLE", "联网搜索暂时不可用，请稍后再试") from error

    def _search_sync(self, query: str) -> list[WebResult]:
        from ddgs import DDGS

        results: list[WebResult] = []
        with DDGS(timeout=self._timeout) as client:
            for item in client.text(query, max_results=self._max_results):
                url = str(item.get("href", "")).strip()
                if not url.startswith("http"):
                    continue
                results.append(
                    WebResult(
                        title=str(item.get("title", "")).strip()[:120] or url,
                        url=url[:500],
                        snippet=str(item.get("body", "")).strip()[:500],
                    )
                )
        if not results:
            raise ServiceError("AI_DEPENDENCY_UNAVAILABLE", "联网搜索没有返回可用结果，请稍后再试")
        return results
