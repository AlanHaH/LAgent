from __future__ import annotations

from fastapi import APIRouter, Depends, Request

from app.blocks.schemas import LearningBlockRequest
from app.core.responses import success
from app.core.security import require_internal_token

router = APIRouter(
    prefix="/internal/v1/learning-blocks",
    tags=["learning-blocks"],
    dependencies=[Depends(require_internal_token)],
)


@router.post("/generate")
async def generate(body: LearningBlockRequest, request: Request) -> dict[str, object]:
    result = await request.app.state.learning_block_service.generate(body)
    return success(request, result.model_dump(by_alias=True, mode="json"))

