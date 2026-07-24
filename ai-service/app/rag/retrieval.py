from __future__ import annotations

import re
import time
from collections import Counter

from app.config import Settings
from app.rag.embeddings import EmbeddingProvider
from app.rag.schemas import IndexRequest, IndexResult, SearchHit, SearchRequest, SearchResult
from app.rag.vector_store import QdrantVectorStore, VectorHit


class RagRetrievalService:
    def __init__(
        self,
        settings: Settings,
        embeddings: EmbeddingProvider,
        vector_store: QdrantVectorStore,
    ) -> None:
        self._settings = settings
        self._embeddings = embeddings
        self._store = vector_store

    async def initialize(self) -> str:
        dimension = await self._embeddings.dimension()
        return await self._store.ensure_collection(self._embeddings.name, dimension)

    async def index(self, request: IndexRequest) -> IndexResult:
        dimension = await self._embeddings.dimension()
        collection = await self._store.ensure_collection(self._embeddings.name, dimension)
        vectors = await self._embeddings.encode_documents([chunk.text for chunk in request.chunks])
        indexed = await self._store.index(request, vectors)
        return IndexResult(
            document_version_id=request.document_version_id,
            indexed_chunks=indexed,
            embedding_model=self._embeddings.name,
            embedding_dimension=dimension,
            collection=collection,
            degraded=self._embeddings.degraded,
        )

    async def search(self, request: SearchRequest) -> SearchResult:
        started = time.perf_counter()
        collection = await self.initialize()
        query_vector = await self._embeddings.encode_query(request.query)
        candidates = await self._store.search(
            query_vector,
            user_id=request.user_id,
            allowed_space_ids=request.allowed_space_ids,
            allowed_document_version_ids=request.allowed_document_version_ids,
            limit=request.candidate_k,
        )
        ranked = self._rerank(request.query, candidates)
        selected = self._select_diverse(ranked, request.top_k)
        hits = [
            self._to_hit(index, hit, score, keyword)
            for index, (hit, score, keyword) in enumerate(selected, 1)
        ]
        sufficient = bool(hits and hits[0].score >= self._settings.rag_evidence_threshold)
        return SearchResult(
            evidence_sufficient=sufficient,
            hits=hits,
            embedding_model=self._embeddings.name,
            collection=collection,
            degraded=self._embeddings.degraded,
            latency_ms=round((time.perf_counter() - started) * 1000),
        )

    async def delete(self, owner_user_id: int, document_version_id: int) -> int:
        await self.initialize()
        return await self._store.delete(owner_user_id, document_version_id)

    def _rerank(self, query: str, hits: list[VectorHit]) -> list[tuple[VectorHit, float, float]]:
        weight = self._settings.rag_vector_weight
        ranked: list[tuple[VectorHit, float, float]] = []
        for hit in hits:
            keyword = self._keyword_score(query, str(hit.payload.get("text", "")))
            vector = max(0.0, min(1.0, (hit.score + 1.0) / 2.0))
            score = weight * vector + (1.0 - weight) * keyword
            ranked.append((hit, score, keyword))
        ranked.sort(key=lambda item: (item[1], item[0].score), reverse=True)
        return ranked

    @staticmethod
    def _select_diverse(
        ranked: list[tuple[VectorHit, float, float]], top_k: int
    ) -> list[tuple[VectorHit, float, float]]:
        selected: list[tuple[VectorHit, float, float]] = []
        per_document: Counter[int] = Counter()
        seen_hashes: set[str] = set()
        max_per_document = max(2, (top_k + 1) // 2)
        for item in ranked:
            payload = item[0].payload
            document_id = int(payload.get("documentId", 0))
            text_hash = str(payload.get("textHash", ""))
            if text_hash and text_hash in seen_hashes:
                continue
            if per_document[document_id] >= max_per_document:
                continue
            selected.append(item)
            per_document[document_id] += 1
            if text_hash:
                seen_hashes.add(text_hash)
            if len(selected) == top_k:
                break
        return selected

    @classmethod
    def _keyword_score(cls, query: str, text: str) -> float:
        query_tokens = cls._tokens(query)
        if not query_tokens:
            return 0.0
        text_tokens = cls._tokens(text)
        return len(query_tokens & text_tokens) / len(query_tokens)

    @staticmethod
    def _tokens(value: str) -> set[str]:
        normalized = value.lower()
        words = set(re.findall(r"[a-z0-9_]+", normalized))
        chinese = "".join(re.findall(r"[\u4e00-\u9fff]", normalized))
        words.update(chinese[index : index + 2] for index in range(max(0, len(chinese) - 1)))
        if len(chinese) == 1:
            words.add(chinese)
        return words

    @staticmethod
    def _to_hit(
        order: int,
        hit: VectorHit,
        score: float,
        keyword_score: float,
    ) -> SearchHit:
        payload = hit.payload
        text = str(payload.get("text", "")).strip()
        return SearchHit(
            citation_id=f"S{order}",
            chunk_id=hit.chunk_id,
            document_id=int(payload["documentId"]),
            document_version_id=int(payload["documentVersionId"]),
            score=round(score, 6),
            vector_score=round(float(hit.score), 6),
            keyword_score=round(keyword_score, 6),
            title_path=list(payload.get("titlePath") or []),
            page_from=payload.get("pageFrom"),
            page_to=payload.get("pageTo"),
            quote_preview=text[:500],
        )
