from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Awaitable, Callable
from typing import Any

from app.core.errors import ServiceError
from app.core.sse import encode_sse

EventQueue = asyncio.Queue[tuple[str, dict[str, Any]] | None]
Worker = Callable[[EventQueue], Awaitable[None]]


async def event_stream(started: dict[str, Any], worker: Worker) -> AsyncIterator[bytes]:
    queue: EventQueue = asyncio.Queue()

    async def run() -> None:
        try:
            await worker(queue)
        except ServiceError as error:
            await queue.put(
                (
                    "message.failed",
                    {
                        "code": error.code,
                        "message": error.message,
                        "retryable": error.retryable,
                        "details": error.details,
                    },
                )
            )
        except asyncio.CancelledError:
            raise
        except Exception:
            await queue.put(
                (
                    "message.failed",
                    {
                        "code": "AI_INTERNAL_ERROR",
                        "message": "AI 服务内部错误",
                        "retryable": False,
                        "details": {},
                    },
                )
            )
        finally:
            await queue.put(None)

    task = asyncio.create_task(run())
    try:
        yield encode_sse("message.started", started)
        while True:
            item = await queue.get()
            if item is None:
                break
            event, data = item
            yield encode_sse(event, data)
    finally:
        if not task.done():
            task.cancel()
        await asyncio.gather(task, return_exceptions=True)
