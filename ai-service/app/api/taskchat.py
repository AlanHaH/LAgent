from __future__ import annotations

from fastapi import APIRouter, Depends, Request

from app.core.responses import success
from app.core.security import require_internal_token
from app.taskchat.schemas import TaskChatRequest

router = APIRouter(
    prefix="/internal/v1/task-chats",
    tags=["task-chat"],
    dependencies=[Depends(require_internal_token)],
)


@router.post("")
async def chat(body: TaskChatRequest, request: Request) -> dict[str, object]:
    result = await request.app.state.task_chat_service.chat(body)
    return success(request, result.model_dump(by_alias=True, mode="json"))
