from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationInfo, field_validator


class IndexChunk(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    chunk_id: int = Field(alias="chunkId", gt=0)
    chunk_no: int = Field(alias="chunkNo", ge=1)
    text: str = Field(min_length=1, max_length=10_000)
    text_hash: str = Field(alias="textHash", pattern=r"^[a-fA-F0-9]{64}$")
    title_path: list[str] = Field(default_factory=list, alias="titlePath", max_length=20)
    page_from: int | None = Field(default=None, alias="pageFrom", ge=1)
    page_to: int | None = Field(default=None, alias="pageTo", ge=1)
    language: str = Field(default="zh-CN", max_length=20)

    @field_validator("page_to")
    @classmethod
    def page_range(cls, value: int | None, info: ValidationInfo) -> int | None:
        page_from = info.data.get("page_from")
        if value is not None and page_from is not None and value < page_from:
            raise ValueError("pageTo cannot precede pageFrom")
        return value


class IndexRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    index_request_id: str = Field(alias="indexRequestId", min_length=8, max_length=80)
    owner_user_id: int = Field(alias="ownerUserId", gt=0)
    space_id: int = Field(alias="spaceId", gt=0)
    document_id: int = Field(alias="documentId", gt=0)
    document_version_id: int = Field(alias="documentVersionId", gt=0)
    visibility: Literal["PRIVATE", "PUBLIC"]
    chunks: list[IndexChunk] = Field(min_length=1, max_length=1000)

    @field_validator("chunks")
    @classmethod
    def unique_chunk_ids(cls, value: list[IndexChunk]) -> list[IndexChunk]:
        ids = [item.chunk_id for item in value]
        numbers = [item.chunk_no for item in value]
        if len(ids) != len(set(ids)) or len(numbers) != len(set(numbers)):
            raise ValueError("chunkId and chunkNo must be unique within a document version")
        return value


class IndexResult(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    document_version_id: int = Field(alias="documentVersionId")
    indexed_chunks: int = Field(alias="indexedChunks")
    embedding_model: str = Field(alias="embeddingModel")
    embedding_dimension: int = Field(alias="embeddingDimension")
    collection: str
    degraded: bool


class SearchRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    user_id: int = Field(alias="userId", gt=0)
    query: str = Field(min_length=1, max_length=2000)
    allowed_space_ids: list[int] = Field(alias="allowedSpaceIds", min_length=1, max_length=100)
    allowed_document_version_ids: list[int] = Field(
        default_factory=list, alias="allowedDocumentVersionIds", max_length=500
    )
    top_k: int = Field(default=5, alias="topK", ge=1, le=20)
    candidate_k: int = Field(default=20, alias="candidateK", ge=1, le=100)


class SearchHit(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    citation_id: str = Field(alias="citationId")
    chunk_id: int = Field(alias="chunkId")
    document_id: int = Field(alias="documentId")
    document_version_id: int = Field(alias="documentVersionId")
    score: float
    vector_score: float = Field(alias="vectorScore")
    keyword_score: float = Field(alias="keywordScore")
    title_path: list[str] = Field(alias="titlePath")
    page_from: int | None = Field(alias="pageFrom")
    page_to: int | None = Field(alias="pageTo")
    quote_preview: str = Field(alias="quotePreview")


class SearchResult(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    evidence_sufficient: bool = Field(alias="evidenceSufficient")
    hits: list[SearchHit]
    embedding_model: str = Field(alias="embeddingModel")
    collection: str
    degraded: bool
    latency_ms: int = Field(alias="latencyMs")


class AnswerEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    citation_id: str = Field(alias="citationId", pattern=r"^S[1-9][0-9]*$")
    chunk_id: int = Field(alias="chunkId", gt=0)
    document_id: int = Field(alias="documentId", gt=0)
    document_version_id: int = Field(alias="documentVersionId", gt=0)
    file_name: str = Field(alias="fileName", min_length=1, max_length=255)
    quote_preview: str = Field(alias="quotePreview", min_length=1, max_length=4000)
    title_path: list[str] = Field(default_factory=list, alias="titlePath", max_length=20)
    page_from: int | None = Field(default=None, alias="pageFrom", ge=1)
    page_to: int | None = Field(default=None, alias="pageTo", ge=1)


class AnswerRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    user_id: int = Field(alias="userId", gt=0)
    question: str = Field(min_length=1, max_length=2000)
    evidence_sufficient: bool = Field(alias="evidenceSufficient")
    evidence: list[AnswerEvidence] = Field(max_length=20)


class AnswerCompleted(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    content: str
    answer_mode: Literal["RAG_AI", "RAG_FALLBACK", "INSUFFICIENT"] = Field(alias="answerMode")
    evidence_level: Literal["SUFFICIENT", "INSUFFICIENT"] = Field(alias="evidenceLevel")
    citation_ids: list[str] = Field(alias="citationIds")
    prompt_version: str = Field(alias="promptVersion")
    model_run: dict[str, object] | None = Field(default=None, alias="modelRun")
    replacement_required: bool = Field(default=False, alias="replacementRequired")
