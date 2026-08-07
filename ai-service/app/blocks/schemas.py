from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


class BlockSource(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    source_type: str = Field(alias="sourceType", min_length=2, max_length=40)
    title: str = Field(min_length=1, max_length=255)
    url: str | None = Field(default=None, max_length=1000)
    quote_preview: str | None = Field(default=None, alias="quotePreview", max_length=3000)


class LearningBlockRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    user_id: int = Field(alias="userId", gt=0)
    title: str = Field(min_length=2, max_length=200)
    objective: str = Field(min_length=2, max_length=1000)
    direction_name: str = Field(alias="directionName", min_length=1, max_length=120)
    current_stage: str = Field(alias="currentStage", min_length=2, max_length=80)
    exploration_required: bool = Field(alias="explorationRequired")
    sources: list[BlockSource] = Field(default_factory=list, max_length=20)
    source_queries: list[str] = Field(default_factory=list, alias="sourceQueries", max_length=10)


class BlockExercise(BaseModel):
    model_config = ConfigDict(extra="ignore")

    prompt: str = Field(min_length=2, max_length=1200)
    answer: str = Field(min_length=1, max_length=2000)
    explanation: str = Field(min_length=1, max_length=2000)


class BlockTestQuestion(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str = Field(min_length=1, max_length=40)
    type: Literal["SINGLE_CHOICE", "TRUE_FALSE"]
    stem: str = Field(min_length=2, max_length=1200)
    options: list[str] = Field(min_length=2, max_length=6)
    answer: str = Field(min_length=1, max_length=200)
    analysis: str = Field(min_length=1, max_length=2000)

    @field_validator("id", mode="before")
    @classmethod
    def coerce_id(cls, value: object) -> object:
        # 模型经常输出数字题号（1、2、3…），统一转成字符串再校验
        return str(value) if isinstance(value, int) else value


class LearningBlockModelOutput(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    material_markdown: str = Field(alias="materialMarkdown", min_length=100, max_length=12000)
    exercises: list[BlockExercise] = Field(min_length=2, max_length=5)
    test_questions: list[BlockTestQuestion] = Field(
        alias="testQuestions", min_length=3, max_length=6
    )
    source_notes: list[str] = Field(default_factory=list, alias="sourceNotes", max_length=10)

    @field_validator("source_notes", mode="before")
    @classmethod
    def coerce_notes(cls, value: object) -> object:
        # 模型有时把 sourceNotes 输出成单个字符串而不是数组
        if isinstance(value, str):
            return [value]
        return value


class LearningBlockCompleted(LearningBlockModelOutput):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    prompt_version: str = Field(alias="promptVersion")

