from __future__ import annotations

import asyncio
import hashlib
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from qdrant_client import QdrantClient, models

from app.config import Settings
from app.core.errors import ServiceError
from app.rag.schemas import IndexRequest


@dataclass(frozen=True)
class VectorHit:
    chunk_id: int
    score: float
    payload: dict[str, Any]


class QdrantVectorStore:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        try:
            if settings.qdrant_mode == "memory":
                self._client = QdrantClient(":memory:")
            elif settings.qdrant_mode == "local":
                path = Path(settings.qdrant_path).resolve()
                path.mkdir(parents=True, exist_ok=True)
                self._client = QdrantClient(path=str(path))
            else:
                key = settings.qdrant_api_key.get_secret_value() or None
                self._client = QdrantClient(
                    url=settings.qdrant_url,
                    api_key=key,
                    timeout=max(1, round(settings.qdrant_timeout_seconds)),
                )
        except Exception as error:
            raise ServiceError(
                "AI_DEPENDENCY_UNAVAILABLE", "Qdrant 初始化失败", status_code=503, retryable=True
            ) from error
        self._collection: str | None = None
        self._dimension: int | None = None
        self._lock = asyncio.Lock()

    @property
    def collection(self) -> str:
        if self._collection is None:
            raise ServiceError("AI_DEPENDENCY_UNAVAILABLE", "向量集合尚未初始化", status_code=503)
        return self._collection

    async def ensure_collection(self, embedding_name: str, dimension: int) -> str:
        safe_hash = hashlib.sha256(embedding_name.encode("utf-8")).hexdigest()[:12]
        name = f"learning_chunks_{safe_hash}_{dimension}"
        if self._collection == name and self._dimension == dimension:
            return name
        async with self._lock:
            try:
                exists = await asyncio.to_thread(self._client.collection_exists, name)
                if not exists:
                    await asyncio.to_thread(
                        self._client.create_collection,
                        collection_name=name,
                        vectors_config=models.VectorParams(size=dimension, distance=models.Distance.COSINE),
                    )
                    await self._create_payload_indexes(name)
            except Exception as error:
                raise ServiceError(
                    "AI_DEPENDENCY_UNAVAILABLE",
                    "Qdrant 集合初始化失败",
                    status_code=503,
                    retryable=True,
                ) from error
            self._collection = name
            self._dimension = dimension
        return name

    async def index(self, request: IndexRequest, vectors: list[list[float]]) -> int:
        if len(vectors) != len(request.chunks):
            raise ServiceError("AI_REQUEST_INVALID", "Chunk 与向量数量不一致", status_code=400)
        if self._dimension is None or any(len(vector) != self._dimension for vector in vectors):
            raise ServiceError("AI_REQUEST_INVALID", "Embedding 向量维度不一致", status_code=400)
        points = [
            models.PointStruct(
                id=chunk.chunk_id,
                vector=vector,
                payload=self._payload(request, chunk.model_dump(by_alias=True), "STAGING"),
            )
            for chunk, vector in zip(request.chunks, vectors, strict=True)
        ]
        selector = self._document_filter(request.owner_user_id, request.document_version_id)
        try:
            # MySQL chunks are authoritative. Clearing this exact owner/version pair makes retries
            # idempotent and cannot affect another user's document with the same numeric version id.
            await asyncio.to_thread(
                self._client.delete,
                collection_name=self.collection,
                points_selector=selector,
                wait=True,
            )
            await asyncio.to_thread(
                self._client.upsert,
                collection_name=self.collection,
                points=points,
                wait=True,
            )
            count = await asyncio.to_thread(
                self._client.count,
                collection_name=self.collection,
                count_filter=selector,
                exact=True,
            )
            if count.count != len(points):
                raise RuntimeError("qdrant point count mismatch")
            active = [
                models.PointStruct(
                    id=point.id,
                    vector=point.vector,
                    payload={**(point.payload or {}), "indexStatus": "ACTIVE"},
                )
                for point in points
            ]
            await asyncio.to_thread(
                self._client.upsert,
                collection_name=self.collection,
                points=active,
                wait=True,
            )
            return len(active)
        except Exception as error:
            try:
                await self.delete(request.owner_user_id, request.document_version_id)
            except ServiceError:
                pass
            raise ServiceError(
                "AI_DEPENDENCY_UNAVAILABLE", "向量索引写入失败", status_code=503, retryable=True
            ) from error

    async def search(
        self,
        vector: list[float],
        *,
        user_id: int,
        allowed_space_ids: list[int],
        allowed_document_ids: list[int],
        allowed_document_version_ids: list[int],
        limit: int,
    ) -> list[VectorHit]:
        conditions: list[models.Condition] = [
            models.FieldCondition(key="indexStatus", match=models.MatchValue(value="ACTIVE")),
        ]
        if allowed_document_ids:
            # 问答可精确到文件：按 documentId 过滤；否则按空间过滤
            conditions.append(
                models.FieldCondition(
                    key="documentId", match=models.MatchAny(any=allowed_document_ids)
                )
            )
        else:
            conditions.append(
                models.FieldCondition(
                    key="spaceId", match=models.MatchAny(any=allowed_space_ids)
                )
            )
        if allowed_document_version_ids:
            conditions.append(
                models.FieldCondition(
                    key="documentVersionId", match=models.MatchAny(any=allowed_document_version_ids)
                )
            )
        access = models.Filter(
            should=[
                models.Filter(
                    must=[
                        models.FieldCondition(key="visibility", match=models.MatchValue(value="PRIVATE")),
                        models.FieldCondition(key="ownerUserId", match=models.MatchValue(value=user_id)),
                    ]
                ),
                models.FieldCondition(key="visibility", match=models.MatchValue(value="PUBLIC")),
            ]
        )
        query_filter = models.Filter(must=[*conditions, access])
        try:
            result = await asyncio.to_thread(
                self._client.query_points,
                collection_name=self.collection,
                query=vector,
                query_filter=query_filter,
                limit=limit,
                with_payload=True,
            )
        except Exception as error:
            raise ServiceError(
                "AI_DEPENDENCY_UNAVAILABLE", "向量检索失败", status_code=503, retryable=True
            ) from error
        hits: list[VectorHit] = []
        for point in result.points:
            if not isinstance(point.id, int):
                continue
            hits.append(VectorHit(point.id, float(point.score), dict(point.payload or {})))
        return hits

    async def delete(self, owner_user_id: int, document_version_id: int) -> int:
        selector = self._document_filter(owner_user_id, document_version_id)
        try:
            before = await asyncio.to_thread(
                self._client.count,
                collection_name=self.collection,
                count_filter=selector,
                exact=True,
            )
            await asyncio.to_thread(
                self._client.delete,
                collection_name=self.collection,
                points_selector=selector,
                wait=True,
            )
            return before.count
        except Exception as error:
            raise ServiceError(
                "AI_DEPENDENCY_UNAVAILABLE", "向量索引删除失败", status_code=503, retryable=True
            ) from error

    async def ping(self) -> bool:
        try:
            await asyncio.to_thread(self._client.get_collections)
            return True
        except Exception:
            return False

    async def _create_payload_indexes(self, collection: str) -> None:
        if self._settings.qdrant_mode != "server":
            return
        fields = {
            "ownerUserId": models.PayloadSchemaType.INTEGER,
            "spaceId": models.PayloadSchemaType.INTEGER,
            "documentId": models.PayloadSchemaType.INTEGER,
            "documentVersionId": models.PayloadSchemaType.INTEGER,
            "visibility": models.PayloadSchemaType.KEYWORD,
            "indexStatus": models.PayloadSchemaType.KEYWORD,
        }
        for field, schema in fields.items():
            await asyncio.to_thread(
                self._client.create_payload_index,
                collection_name=collection,
                field_name=field,
                field_schema=schema,
                wait=True,
            )

    @staticmethod
    def _document_filter(owner_user_id: int, document_version_id: int) -> models.Filter:
        return models.Filter(
            must=[
                models.FieldCondition(key="ownerUserId", match=models.MatchValue(value=owner_user_id)),
                models.FieldCondition(
                    key="documentVersionId", match=models.MatchValue(value=document_version_id)
                ),
            ]
        )

    @staticmethod
    def _payload(request: IndexRequest, chunk: dict[str, Any], status: str) -> dict[str, Any]:
        return {
            "ownerUserId": request.owner_user_id,
            "spaceId": request.space_id,
            "documentId": request.document_id,
            "documentVersionId": request.document_version_id,
            "chunkId": chunk["chunkId"],
            "chunkNo": chunk["chunkNo"],
            "visibility": request.visibility,
            "indexStatus": status,
            "textHash": chunk["textHash"],
            "titlePath": chunk["titlePath"],
            "pageFrom": chunk["pageFrom"],
            "pageTo": chunk["pageTo"],
            "language": chunk["language"],
            "text": chunk["text"],
        }
