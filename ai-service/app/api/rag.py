from __future__ import annotations

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import StreamingResponse

from app.api.streaming import EventQueue, event_stream
from app.core.errors import request_id
from app.core.responses import success
from app.core.security import require_internal_token
from app.rag.schemas import AnswerRequest, IndexRequest, SearchRequest

router = APIRouter(
    prefix="/internal/v1/rag",
    tags=["rag"],
    dependencies=[Depends(require_internal_token)],
)


@router.post("/indexes")
async def create_index(body: IndexRequest, request: Request) -> dict[str, object]:
    result = await request.app.state.retrieval_service.index(body)
    return success(request, result.model_dump(by_alias=True, mode="json"))


@router.delete("/indexes/{document_version_id}")
async def delete_index(
    document_version_id: int,
    request: Request,
    owner_user_id: int = Query(alias="ownerUserId", gt=0),
) -> dict[str, object]:
    deleted = await request.app.state.retrieval_service.delete(owner_user_id, document_version_id)
    return success(
        request,
        {"documentVersionId": document_version_id, "ownerUserId": owner_user_id, "deletedPoints": deleted},
    )


@router.post("/searches")
async def search(body: SearchRequest, request: Request) -> dict[str, object]:
    result = await request.app.state.retrieval_service.search(body)
    return success(request, result.model_dump(by_alias=True, mode="json"))


@router.post("/answers:stream")
async def answer(body: AnswerRequest, request: Request) -> StreamingResponse:
    async def worker(queue: EventQueue) -> None:
        for evidence in body.evidence:
            await queue.put(
                (
                    "citation.ready",
                    evidence.model_dump(by_alias=True, mode="json"),
                )
            )

        async def delta(piece: str) -> None:
            await queue.put(("message.delta", {"delta": piece}))

        result = await request.app.state.answer_service.answer(body, delta)
        await queue.put(("message.completed", result.model_dump(by_alias=True, mode="json")))

    stream = event_stream({"requestId": request_id(request)}, worker)
    return StreamingResponse(stream, media_type="text/event-stream", headers={"Cache-Control": "no-cache"})
