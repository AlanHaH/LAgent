from __future__ import annotations

import logging
import time
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError

from app.api import goals, health, model, plans, profile, rag, taskchat
from app.config import Settings, get_settings
from app.core.errors import (
    ServiceError,
    service_error_handler,
    unexpected_error_handler,
    validation_error_handler,
)
from app.goals.service import GoalRecommendationAiService
from app.plans.service import PlanRecommendationAiService
from app.model.client import OpenAICompatibleClient
from app.model.schemas import ModelClient
from app.profile.service import ProfileInterviewAiService
from app.rag.answer import RagAnswerService
from app.rag.embeddings import EmbeddingProvider, build_embedding_provider
from app.rag.retrieval import RagRetrievalService
from app.rag.vector_store import QdrantVectorStore
from app.taskchat.search import WebSearcher
from app.taskchat.service import TaskChatAiService

logger = logging.getLogger("ai-service")


def create_app(
    settings: Settings | None = None,
    *,
    model_client: ModelClient | None = None,
    embeddings: EmbeddingProvider | None = None,
    vector_store: QdrantVectorStore | None = None,
) -> FastAPI:
    configured = settings or get_settings()
    logging.basicConfig(
        level=getattr(logging, configured.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        app.state.settings = configured
        app.state.model_client = model_client or OpenAICompatibleClient(configured)
        app.state.embeddings = embeddings or build_embedding_provider(configured)
        app.state.vector_store = vector_store or QdrantVectorStore(configured)
        app.state.profile_service = ProfileInterviewAiService(app.state.model_client)
        app.state.goal_recommendation_service = GoalRecommendationAiService(app.state.model_client)
        app.state.plan_recommendation_service = PlanRecommendationAiService(app.state.model_client)
        app.state.retrieval_service = RagRetrievalService(
            configured, app.state.embeddings, app.state.vector_store
        )
        app.state.answer_service = RagAnswerService(configured, app.state.model_client)
        app.state.task_chat_service = TaskChatAiService(
            configured,
            app.state.model_client,
            app.state.retrieval_service,
            WebSearcher(
                max_results=configured.search_max_results,
                timeout_seconds=configured.search_timeout_seconds,
            ),
        )
        yield
        close = getattr(app.state.model_client, "close", None)
        if close is not None:
            await close()

    app = FastAPI(
        title="Adaptive Learning AI Service",
        version="1.0.0",
        docs_url="/docs" if configured.expose_docs else None,
        redoc_url=None,
        lifespan=lifespan,
    )

    @app.middleware("http")
    async def request_context(request: Request, call_next):  # type: ignore[no-untyped-def]
        started = time.perf_counter()
        supplied = request.headers.get("X-Request-Id", "").strip()
        request.state.request_id = supplied[:128] if supplied else str(uuid.uuid4())
        response = await call_next(request)
        response.headers["X-Request-Id"] = request.state.request_id
        logger.info(
            "requestId=%s method=%s path=%s status=%s latencyMs=%s",
            request.state.request_id,
            request.method,
            request.url.path,
            response.status_code,
            round((time.perf_counter() - started) * 1000),
        )
        return response

    app.add_exception_handler(ServiceError, service_error_handler)  # type: ignore[arg-type]
    app.add_exception_handler(RequestValidationError, validation_error_handler)  # type: ignore[arg-type]
    app.add_exception_handler(Exception, unexpected_error_handler)
    app.include_router(health.router)
    app.include_router(model.router)
    app.include_router(profile.router)
    app.include_router(goals.router)
    app.include_router(plans.router)
    app.include_router(rag.router)
    app.include_router(taskchat.router)
    return app


app = create_app()
