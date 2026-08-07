"""PromptManager 离线测试：sync_url 为空恒回退；注入 MockTransport 模拟 Java 端点。"""

from __future__ import annotations

import httpx

from app.prompts.manager import PromptManager

BUILTIN_CONTENT = "内置提示词"
BUILTIN_VERSION = "builtin-v1"
CODE = "GOAL_RECOMMENDATION"


def _manager(
    data: list[dict[str, object]],
    *,
    status: int = 200,
    error: Exception | None = None,
    ttl: int = 60,
) -> tuple[PromptManager, list[httpx.Request]]:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if error is not None:
            raise error
        if status != 200:
            return httpx.Response(status, json={"success": False, "error": {"code": "FAIL"}})
        return httpx.Response(200, json={"success": True, "data": data})

    manager = PromptManager(
        "http://backend:8080",
        "test-internal-token-1234567890123456",
        ttl_seconds=ttl,
        transport=httpx.MockTransport(handler),
    )
    return manager, requests


async def test_disabled_returns_builtin() -> None:
    manager = PromptManager("", "test-internal-token-1234567890123456")
    resolved = await manager.get_prompt(CODE, BUILTIN_CONTENT, BUILTIN_VERSION)
    assert (resolved.content, resolved.version) == (BUILTIN_CONTENT, BUILTIN_VERSION)
    await manager.close()


async def test_normal_fetch_returns_db_override() -> None:
    manager, requests = _manager([{"code": CODE, "versionNo": 2, "content": "数据库版本"}])
    resolved = await manager.get_prompt(CODE, BUILTIN_CONTENT, BUILTIN_VERSION)
    assert (resolved.content, resolved.version) == ("数据库版本", f"{BUILTIN_VERSION}#dbV2")
    assert len(requests) == 1
    assert requests[0].headers["X-Internal-Token"] == "test-internal-token-1234567890123456"
    await manager.close()


async def test_missing_code_falls_back_to_builtin() -> None:
    manager, _ = _manager([{"code": "OTHER_CODE", "versionNo": 1, "content": "别的"}])
    resolved = await manager.get_prompt(CODE, BUILTIN_CONTENT, BUILTIN_VERSION)
    assert (resolved.content, resolved.version) == (BUILTIN_CONTENT, BUILTIN_VERSION)
    await manager.close()


async def test_blank_content_row_is_ignored() -> None:
    manager, _ = _manager([{"code": CODE, "versionNo": 1, "content": ""}])
    resolved = await manager.get_prompt(CODE, BUILTIN_CONTENT, BUILTIN_VERSION)
    assert (resolved.content, resolved.version) == (BUILTIN_CONTENT, BUILTIN_VERSION)
    await manager.close()


async def test_http_failure_falls_back_without_raising() -> None:
    manager, _ = _manager([], status=500)
    resolved = await manager.get_prompt(CODE, BUILTIN_CONTENT, BUILTIN_VERSION)
    assert (resolved.content, resolved.version) == (BUILTIN_CONTENT, BUILTIN_VERSION)
    await manager.close()


async def test_connect_error_falls_back_without_raising() -> None:
    manager, _ = _manager([], error=httpx.ConnectError("backend down"))
    resolved = await manager.get_prompt(CODE, BUILTIN_CONTENT, BUILTIN_VERSION)
    assert (resolved.content, resolved.version) == (BUILTIN_CONTENT, BUILTIN_VERSION)
    await manager.close()


async def test_ttl_serves_cached_override() -> None:
    manager, requests = _manager([{"code": CODE, "versionNo": 3, "content": "数据库版本"}])
    first = await manager.get_prompt(CODE, BUILTIN_CONTENT, BUILTIN_VERSION)
    second = await manager.get_prompt(CODE, BUILTIN_CONTENT, BUILTIN_VERSION)
    assert first == second
    assert (first.content, first.version) == ("数据库版本", f"{BUILTIN_VERSION}#dbV3")
    assert len(requests) == 1
    await manager.close()


async def test_ttl_expired_refetches() -> None:
    manager, requests = _manager([{"code": CODE, "versionNo": 1, "content": "数据库版本"}], ttl=0)
    await manager.get_prompt(CODE, BUILTIN_CONTENT, BUILTIN_VERSION)
    await manager.get_prompt(CODE, BUILTIN_CONTENT, BUILTIN_VERSION)
    assert len(requests) == 2
    await manager.close()


async def test_failed_fetch_throttles_next_attempt() -> None:
    manager, requests = _manager([], error=httpx.ConnectError("backend down"))
    await manager.get_prompt(CODE, BUILTIN_CONTENT, BUILTIN_VERSION)
    # 失败也推进 fetched_at：TTL 内不重试轰炸 Java。
    await manager.get_prompt(CODE, BUILTIN_CONTENT, BUILTIN_VERSION)
    assert len(requests) == 1
    await manager.close()
