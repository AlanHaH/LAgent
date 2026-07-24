from __future__ import annotations

import hashlib

import pytest

from app.config import Settings
from app.rag.answer import RagAnswerService
from app.rag.embeddings import HashEmbeddingProvider
from app.rag.retrieval import RagRetrievalService
from app.rag.schemas import AnswerRequest, IndexRequest, SearchRequest
from app.rag.vector_store import QdrantVectorStore
from tests.fakes import FakeModelClient


def settings() -> Settings:
    return Settings(
        env="test",
        internal_token="x" * 32,
        model_base_url="https://example.test/v1",
        model_api_key="test-key",
        model_name="fake-model",
        embedding_provider="hash",
        qdrant_mode="memory",
        rag_evidence_threshold=0.0,
    )


def index_request() -> IndexRequest:
    text = "JWT 无状态认证不需要在服务端保存会话，适合水平扩展。"
    return IndexRequest.model_validate(
        {
            "indexRequestId": "request-0001",
            "ownerUserId": 1,
            "spaceId": 10,
            "documentId": 20,
            "documentVersionId": 21,
            "visibility": "PRIVATE",
            "chunks": [
                {
                    "chunkId": 100,
                    "chunkNo": 1,
                    "text": text,
                    "textHash": hashlib.sha256(text.encode()).hexdigest(),
                    "titlePath": ["认证"],
                    "pageFrom": 1,
                    "pageTo": 1,
                    "language": "zh-CN",
                }
            ],
        }
    )


@pytest.mark.asyncio
async def test_index_search_permission_filter_and_delete() -> None:
    configured = settings()
    retrieval = RagRetrievalService(
        configured,
        HashEmbeddingProvider(),
        QdrantVectorStore(configured),
    )
    indexed = await retrieval.index(index_request())
    assert indexed.indexed_chunks == 1

    owner_result = await retrieval.search(
        SearchRequest.model_validate(
            {"userId": 1, "query": "无状态认证如何扩展", "allowedSpaceIds": [10]}
        )
    )
    assert [item.chunk_id for item in owner_result.hits] == [100]
    assert owner_result.hits[0].citation_id == "S1"

    other_result = await retrieval.search(
        SearchRequest.model_validate(
            {"userId": 2, "query": "无状态认证如何扩展", "allowedSpaceIds": [10]}
        )
    )
    assert other_result.hits == []
    assert await retrieval.delete(1, 21) == 1


@pytest.mark.asyncio
async def test_rag_answer_validates_citations_and_falls_back() -> None:
    configured = settings()
    valid = FakeModelClient(answer="它便于水平扩展。[S1]")
    service = RagAnswerService(configured, valid)
    request = AnswerRequest.model_validate(
        {
            "userId": 1,
            "question": "有什么好处？",
            "evidenceSufficient": True,
            "evidence": [
                {
                    "citationId": "S1",
                    "chunkId": 100,
                    "documentId": 20,
                    "documentVersionId": 21,
                    "fileName": "认证.md",
                    "quotePreview": "无状态认证适合水平扩展。",
                }
            ],
        }
    )
    result = await service.answer(request, _ignore)
    assert result.answer_mode == "RAG_AI"
    assert result.citation_ids == ["S1"]

    invalid = RagAnswerService(configured, FakeModelClient(answer="模型编造了引用。[S9]"))
    fallback = await invalid.answer(request, _ignore)
    assert fallback.answer_mode == "RAG_FALLBACK"
    assert fallback.replacement_required is True
    assert fallback.citation_ids == ["S1"]


async def _ignore(_: str) -> None:
    return None
