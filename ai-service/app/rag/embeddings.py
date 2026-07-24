from __future__ import annotations

import asyncio
import hashlib
import math
import re
from typing import Protocol

from app.config import Settings
from app.core.errors import ServiceError


class EmbeddingProvider(Protocol):
    @property
    def name(self) -> str: ...

    @property
    def degraded(self) -> bool: ...

    async def dimension(self) -> int: ...

    async def encode_documents(self, texts: list[str]) -> list[list[float]]: ...

    async def encode_query(self, text: str) -> list[float]: ...


class HashEmbeddingProvider:
    _word = re.compile(r"[a-z0-9_+#.]+", re.IGNORECASE)

    def __init__(self, dimension: int = 384, *, fallback: bool = False) -> None:
        self._dimension = dimension
        self._fallback = fallback

    @property
    def name(self) -> str:
        return f"LOCAL_HASHED_{self._dimension}"

    @property
    def degraded(self) -> bool:
        return self._fallback

    async def dimension(self) -> int:
        return self._dimension

    async def encode_documents(self, texts: list[str]) -> list[list[float]]:
        return [self._encode(text) for text in texts]

    async def encode_query(self, text: str) -> list[float]:
        return self._encode(text)

    def _encode(self, text: str) -> list[float]:
        values = [0.0] * self._dimension
        for token in self._tokens(text):
            digest = hashlib.sha256(token.encode("utf-8")).digest()
            index = int.from_bytes(digest[:4], "big") % self._dimension
            values[index] += 1.0 if digest[4] & 1 == 0 else -1.0
        norm = math.sqrt(sum(value * value for value in values))
        return [value / norm for value in values] if norm else values

    def _tokens(self, text: str) -> set[str]:
        lowered = (text or "").lower()
        tokens = set(self._word.findall(lowered))
        chars = list(lowered)
        for index, char in enumerate(chars):
            if "\u4e00" <= char <= "\u9fff":
                tokens.add(char)
                if index + 1 < len(chars) and "\u4e00" <= chars[index + 1] <= "\u9fff":
                    tokens.add(char + chars[index + 1])
        return tokens


class SentenceTransformerEmbeddingProvider:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._model: object | None = None
        self._lock = asyncio.Lock()
        self._dimension: int | None = None

    @property
    def name(self) -> str:
        return self._settings.embedding_model

    @property
    def degraded(self) -> bool:
        return False

    async def dimension(self) -> int:
        await self._load()
        assert self._dimension is not None
        return self._dimension

    async def encode_documents(self, texts: list[str]) -> list[list[float]]:
        model = await self._load()
        return await asyncio.to_thread(self._encode, model, texts, "document")

    async def encode_query(self, text: str) -> list[float]:
        model = await self._load()
        values = await asyncio.to_thread(self._encode, model, [text], "query")
        return values[0]

    async def _load(self) -> object:
        if self._model is not None:
            return self._model
        async with self._lock:
            if self._model is not None:
                return self._model
            try:
                from sentence_transformers import SentenceTransformer

                model = await asyncio.to_thread(
                    SentenceTransformer,
                    self._settings.embedding_model,
                    device=self._settings.embedding_device,
                )
                dimension = model.get_sentence_embedding_dimension()
            except Exception as error:
                raise ServiceError(
                    "AI_DEPENDENCY_UNAVAILABLE",
                    "Embedding 模型加载失败",
                    status_code=503,
                    retryable=True,
                ) from error
            self._model = model
            self._dimension = int(dimension)
            return model

    @staticmethod
    def _encode(model: object, texts: list[str], kind: str) -> list[list[float]]:
        method_name = "encode_query" if kind == "query" else "encode_document"
        method = getattr(model, method_name, None)
        if method is None:
            method = model.encode  # type: ignore[attr-defined]
        encoded = method(texts, normalize_embeddings=True, show_progress_bar=False)
        return [[float(value) for value in row] for row in encoded]


class ResilientEmbeddingProvider:
    def __init__(self, primary: EmbeddingProvider, fallback: HashEmbeddingProvider | None) -> None:
        self._active: EmbeddingProvider = primary
        self._fallback = fallback
        self._switch_lock = asyncio.Lock()

    @property
    def name(self) -> str:
        return self._active.name

    @property
    def degraded(self) -> bool:
        return self._active.degraded

    async def dimension(self) -> int:
        try:
            return await self._active.dimension()
        except ServiceError:
            if not await self._activate_fallback():
                raise
            return await self._active.dimension()

    async def encode_documents(self, texts: list[str]) -> list[list[float]]:
        try:
            return await self._active.encode_documents(texts)
        except ServiceError:
            if not await self._activate_fallback():
                raise
            return await self._active.encode_documents(texts)

    async def encode_query(self, text: str) -> list[float]:
        try:
            return await self._active.encode_query(text)
        except ServiceError:
            if not await self._activate_fallback():
                raise
            return await self._active.encode_query(text)

    async def _activate_fallback(self) -> bool:
        if self._fallback is None or self._active is self._fallback:
            return False
        async with self._switch_lock:
            self._active = self._fallback
        return True


def build_embedding_provider(settings: Settings) -> EmbeddingProvider:
    if settings.embedding_provider == "hash":
        return HashEmbeddingProvider(settings.embedding_hash_dimension)
    primary = SentenceTransformerEmbeddingProvider(settings)
    fallback = (
        HashEmbeddingProvider(settings.embedding_hash_dimension, fallback=True)
        if settings.allow_hash_fallback
        else None
    )
    return ResilientEmbeddingProvider(primary, fallback)
