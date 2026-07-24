from __future__ import annotations

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse

from app.api.streaming import EventQueue, event_stream
from app.core.errors import request_id
from app.core.security import require_internal_token
from app.profile.schemas import ProfileTurnRequest

router = APIRouter(
    prefix="/internal/v1/profile",
    tags=["profile"],
    dependencies=[Depends(require_internal_token)],
)


@router.post("/interview-turns:stream")
async def interview_turn(body: ProfileTurnRequest, request: Request) -> StreamingResponse:
    async def worker(queue: EventQueue) -> None:
        async def delta(piece: str) -> None:
            await queue.put(("message.delta", {"delta": piece}))

        result = await request.app.state.profile_service.turn(body, delta)
        await queue.put(("message.completed", result.model_dump(by_alias=True, mode="json")))

    stream = event_stream(
        {"requestId": request_id(request), "sessionId": body.session_id},
        worker,
    )
    return StreamingResponse(stream, media_type="text/event-stream", headers={"Cache-Control": "no-cache"})
