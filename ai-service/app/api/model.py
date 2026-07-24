from __future__ import annotations

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse

from app.api.streaming import EventQueue, event_stream
from app.core.errors import request_id
from app.core.responses import success
from app.core.security import require_internal_token
from app.model.schemas import CompletionRequest

router = APIRouter(
    prefix="/internal/v1/model",
    tags=["model"],
    dependencies=[Depends(require_internal_token)],
)


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
