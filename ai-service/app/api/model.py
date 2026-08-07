from __future__ import annotations

import asyncio

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse

from app.api.streaming import EventQueue, event_stream
from app.core.errors import request_id
from app.core.responses import success
from app.core.security import require_internal_token
from app.model.runtime import RuntimeModelManager
from app.model.schemas import CompletionRequest, RuntimeModelConfiguration

router = APIRouter(
    prefix="/internal/v1/model",
    tags=["model"],
    dependencies=[Depends(require_internal_token)],
)


def runtime_manager(request: Request) -> RuntimeModelManager:
    manager = request.app.state.model_client
    if not isinstance(manager, RuntimeModelManager):
        raise RuntimeError("runtime model manager is unavailable")
    return manager


@router.get("/configuration")
async def configuration(request: Request) -> dict[str, object]:
    return success(request, runtime_manager(request).status())


@router.post("/probe")
async def probe(request: Request) -> dict[str, object]:
    result = await asyncio.wait_for(
        runtime_manager(request).complete(
            "You are a connectivity probe. Follow the user's instruction exactly.",
            "Reply with exactly: OK",
            max_output_tokens=8,
        ),
        timeout=15,
    )
    return success(
        request,
        {
            "status": "UP",
            "model": result.model,
            "latencyMs": result.latency_ms,
        },
    )


@router.post("/configuration:test")
async def test_configuration(
    body: RuntimeModelConfiguration, request: Request
) -> dict[str, object]:
    result = await runtime_manager(request).test(body)
    return success(request, result)


@router.put("/configuration")
async def apply_configuration(
    body: RuntimeModelConfiguration, request: Request
) -> dict[str, object]:
    result = await runtime_manager(request).configure(body)
    return success(request, result)


@router.post("/completions")
async def complete(body: CompletionRequest, request: Request) -> dict[str, object]:
    result = await request.app.state.model_client.complete(
        body.system_prompt,
        body.user_prompt,
        max_output_tokens=body.max_output_tokens,
    )
    return success(request, result.model_dump(by_alias=True, mode="json"))


@router.post("/completions:stream")
async def complete_stream(body: CompletionRequest, request: Request) -> StreamingResponse:
    async def worker(queue: EventQueue) -> None:
        async def delta(piece: str) -> None:
            await queue.put(("message.delta", {"delta": piece}))

        result = await request.app.state.model_client.complete_streaming(
            body.system_prompt,
            body.user_prompt,
            delta,
            max_output_tokens=body.max_output_tokens,
        )
        await queue.put(("message.completed", result.model_dump(mode="json")))

    stream = event_stream({"requestId": request_id(request)}, worker)
    return StreamingResponse(stream, media_type="text/event-stream", headers={"Cache-Control": "no-cache"})
