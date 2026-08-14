from __future__ import annotations

from datetime import date
from typing import Any, Literal
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field, model_validator


class PlanKnowledgePoint(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    id: int = Field(gt=0)
    name: str = Field(min_length=1, max_length=120)


class PlanKnowledgeDependency(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    predecessor_id: int = Field(alias="predecessorId", gt=0)
    successor_id: int = Field(alias="successorId", gt=0)


class PlanCriterion(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    criterion_id: str = Field(alias="criterionId", min_length=2, max_length=100)
    text: str = Field(min_length=1, max_length=1000)


class PlanMilestone(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    id: int = Field(gt=0)
    public_id: str = Field(alias="publicId", min_length=1, max_length=64)
    sequence_no: int = Field(alias="sequenceNo", ge=1)
    name: str = Field(min_length=1, max_length=120)
    due_date: date = Field(alias="dueDate")
    weight: str | None = None
    acceptance_criteria: list[PlanCriterion] = Field(
        default_factory=list, alias="acceptanceCriteria", max_length=10
    )


class PlanProject(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    id: int = Field(gt=0)
    public_id: str = Field(alias="publicId", min_length=1, max_length=64)
    name: str = Field(min_length=1, max_length=120)
    description: str | None = Field(default=None, max_length=2000)
    priority: Literal["LOW", "MEDIUM", "HIGH", "URGENT"]
    start_date: date = Field(alias="startDate")
    due_date: date = Field(alias="dueDate")
    deliverables: Any = None
    repository_url: str | None = Field(default=None, alias="repositoryUrl", max_length=500)
    contribution_weight: str | None = Field(default=None, alias="contributionWeight")
    milestones: list[PlanMilestone] = Field(default_factory=list, max_length=20)


class PlanRecommendationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    user_id: int = Field(alias="userId", gt=0)
    goal_name: str = Field(alias="goalName", min_length=1, max_length=200)
    direction_name: str = Field(alias="directionName", min_length=1, max_length=120)
    current_stage: Literal["BEGINNER", "INTERMEDIATE", "ADVANCED"] = Field(alias="currentStage")
    plan_start_date: date = Field(alias="planStartDate")
    plan_end_date: date = Field(alias="planEndDate")
    background_text: str | None = Field(default=None, alias="backgroundText", max_length=2000)
    goal_description: str | None = Field(default=None, alias="goalDescription", max_length=2000)
    goal_type: str | None = Field(default=None, alias="goalType", max_length=40)
    goal_priority: str | None = Field(default=None, alias="goalPriority", max_length=20)
    direction_id: int | None = Field(default=None, alias="directionId", gt=0)
    custom_direction: str | None = Field(default=None, alias="customDirection", max_length=120)
    goal_start_date: date | None = Field(default=None, alias="goalStartDate")
    goal_due_date: date | None = Field(default=None, alias="goalDueDate")
    goal_weekly_budget_minutes: int | None = Field(
        default=None, alias="goalWeeklyBudgetMinutes", ge=10, le=10080
    )
    goal_success_criteria: list[PlanCriterion] = Field(
        default_factory=list, alias="goalSuccessCriteria", max_length=20
    )
    goal_profile_version: int | None = Field(default=None, alias="goalProfileVersion", gt=0)
    scheduling_profile_version: int | None = Field(
        default=None, alias="schedulingProfileVersion", gt=0
    )
    project: PlanProject | None = None
    knowledge_points: list[PlanKnowledgePoint] = Field(
        default_factory=list, alias="knowledgePoints", max_length=50
    )
    knowledge_dependencies: list[PlanKnowledgeDependency] = Field(
        default_factory=list, alias="knowledgeDependencies", max_length=2500
    )
    satisfied_prerequisite_ids: list[int] = Field(
        default_factory=list, alias="satisfiedPrerequisiteIds", max_length=50
    )
    allowed_space_ids: list[int] = Field(default_factory=list, alias="allowedSpaceIds", max_length=20)
    allowed_document_version_ids: list[int] = Field(
        default_factory=list, alias="allowedDocumentVersionIds", max_length=500
    )
    knowledge_top_k: int = Field(default=12, alias="knowledgeTopK", ge=3, le=20)
    user_requirement: str | None = Field(default=None, alias="userRequirement", max_length=1000)
    weekly_available_minutes: int = Field(alias="weeklyAvailableMinutes", ge=10, le=10080)
    daily_recommended_tasks: int = Field(default=2, alias="dailyRecommendedTasks", ge=1, le=20)
    focus_minutes: int = Field(default=45, alias="focusMinutes", ge=10, le=180)
    exploration_mode: bool = Field(default=False, alias="explorationMode")
    count: int = Field(default=6, ge=2, le=10)

    @model_validator(mode="after")
    def validate_context(self) -> PlanRecommendationRequest:
        if self.plan_end_date < self.plan_start_date:
            raise ValueError("planEndDate cannot precede planStartDate")
        candidate_ids = {item.id for item in self.knowledge_points}
        for dependency in self.knowledge_dependencies:
            if dependency.predecessor_id not in candidate_ids or dependency.successor_id not in candidate_ids:
                raise ValueError("knowledgeDependencies must reference input knowledgePoints")
        if not set(self.satisfied_prerequisite_ids).issubset(candidate_ids):
            raise ValueError("satisfiedPrerequisiteIds must reference input knowledgePoints")
        return self


class PlanTaskItem(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    client_ref: str = Field(
        default_factory=lambda: f"task-{uuid4()}", alias="clientRef", min_length=6, max_length=80
    )
    title: str = Field(min_length=2, max_length=200)
    task_type: Literal["LEARNING", "PRACTICE", "REVIEW", "ASSESSMENT"] = Field(alias="taskType")
    priority: Literal["LOW", "MEDIUM", "HIGH", "URGENT"] = "MEDIUM"
    estimated_minutes: int = Field(alias="estimatedMinutes", ge=10, le=120)
    knowledge_point_ids: list[int | str] = Field(
        default_factory=list, alias="knowledgePointIds", max_length=10
    )
    source_chunk_ids: list[int] = Field(default_factory=list, alias="sourceChunkIds", max_length=12)
    learning_objective: str = Field(alias="learningObjective", min_length=2, max_length=1000)
    source_queries: list[str] = Field(default_factory=list, alias="sourceQueries", max_length=8)
    acceptance_criteria: list[str] = Field(alias="acceptanceCriteria", min_length=1, max_length=5)
    milestone_id: int | None = Field(default=None, alias="milestoneId", gt=0)
    covered_goal_criterion_ids: list[str] = Field(
        default_factory=list, alias="coveredGoalCriterionIds", max_length=20
    )
    covered_milestone_criterion_ids: list[str] = Field(
        default_factory=list, alias="coveredMilestoneCriterionIds", max_length=20
    )
    reason: str = Field(min_length=5, max_length=500)


class PlanRecommendationModelOutput(BaseModel):
    model_config = ConfigDict(extra="ignore")

    tasks: list[PlanTaskItem] = Field(min_length=1, max_length=10)


class PlanRecommendationCompleted(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    tasks: list[PlanTaskItem]
    prompt_version: str = Field(alias="promptVersion")
    model_run: dict[str, Any] = Field(alias="modelRun")
