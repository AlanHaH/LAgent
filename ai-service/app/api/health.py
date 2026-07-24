from __future__ import annotations

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from app.core.errors import ServiceError
from app.core.responses import success

router = APIRouter(tags=["health"])


@router.get("/health/live")
async def live(request: Request) -> dict[str, object]:
    return success(request, {"status": "UP"})


@router.get("/health/ready")
async def ready(request: Request) -> JSONResponse:
    model = request.app.state.model_client
    embeddings = request.app.state.embeddings
    qdrant_ok = await request.app.state.vector_store.ping()
    embedding_ready = True
    try:
        dimension = await embeddings.dimension()
        await request.app.state.vector_store.ensure_collection(embeddings.name, dimension)
    except ServiceError:
        embedding_ready = False
        dimension = None
    dependencies = {
        "model": {"ready": model.configured, "name": model.model_name or None},
        "embedding": {
            "ready": embedding_ready,
            "name": embeddings.name,
            "dimension": dimension,
            "degraded": embeddings.degraded,
        },
        "qdrant": {"ready": qdrant_ok},
    }
    is_ready = bool(model.configured and embedding_ready and qdrant_ok)
    payload = success(request, {"status": "UP" if is_ready else "DOWN", "dependencies": dependencies})
    return JSONResponse(status_code=200 if is_ready else 503, content=payload)
