from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class TaskChatTurn(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    role: Literal["USER", "ASSISTANT"]
    content: str = Field(min_length=1, max_length=2000)


class TaskChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    user_id: int = Field(alias="userId", gt=0)
    task_title: str = Field(alias="taskTitle", min_length=1, max_length=200)
    task_type: str | None = Field(default=None, alias="taskType", max_length=40)
    message: str = Field(min_length=1, max_length=2000)
    history: list[TaskChatTurn] = Field(default_factory=list, max_length=400)
    allowed_space_ids: list[int] = Field(default_factory=list, alias="allowedSpaceIds", max_length=100)
    top_k: int = Field(default=5, alias="topK", ge=1, le=10)


class TaskChatCitation(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    citation_id: str = Field(alias="citationId")
    source_type: Literal["KNOWLEDGE", "WEB"] = Field(alias="sourceType")
    chunk_id: int | None = Field(default=None, alias="chunkId")
    file_name: str | None = Field(default=None, alias="fileName")
    title: str | None = None
    url: str | None = None
    quote_preview: str = Field(default="", alias="quotePreview")


class TaskChatResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    answer: str
    mode: Literal["KNOWLEDGE", "WEB"]
    citations: list[TaskChatCitation]
    model_run: dict[str, object] | None = Field(default=None, alias="modelRun")
