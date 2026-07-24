from __future__ import annotations

from fastapi import APIRouter, Depends, Request

from app.core.responses import success
from app.core.security import require_internal_token
from app.plans.schemas import PlanRecommendationRequest

router = APIRouter(
    prefix="/internal/v1/plans",
    tags=["plans"],
    dependencies=[Depends(require_internal_token)],
)


@router.post("/recommendations")
async def recommendations(body: PlanRecommendationRequest, request: Request) -> dict[str, object]:
    result = await request.app.state.plan_recommendation_service.recommend(body)
    return success(request, result.model_dump(by_alias=True, mode="json"))
