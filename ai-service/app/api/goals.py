from __future__ import annotations

from fastapi import APIRouter, Depends, Request

from app.core.responses import success
from app.core.security import require_internal_token
from app.goals.schemas import GoalRecommendationRequest

router = APIRouter(
    prefix="/internal/v1/goals",
    tags=["goals"],
    dependencies=[Depends(require_internal_token)],
)


@router.post("/recommendations")
async def recommendations(body: GoalRecommendationRequest, request: Request) -> dict[str, object]:
    result = await request.app.state.goal_recommendation_service.recommend(body)
    return success(request, result.model_dump(by_alias=True, mode="json"))
