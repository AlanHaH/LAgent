from __future__ import annotations

import asyncio
import tempfile
from pathlib import Path
from typing import Annotated

from fastapi import APIRouter, Depends, File, Request, UploadFile

from app.core.errors import ServiceError
from app.core.responses import success
from app.core.security import require_internal_token

router = APIRouter(
    prefix="/internal/v1/ocr",
    tags=["ocr"],
    dependencies=[Depends(require_internal_token)],
)


@router.post("/pdf")
async def ocr_pdf(
    request: Request,
    file: Annotated[UploadFile, File()],
) -> dict[str, object]:
    settings = request.app.state.settings
    max_bytes = settings.ocr_max_file_mb * 1024 * 1024
    temp_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(prefix="lagent-ocr-", suffix=".pdf", delete=False) as target:
            temp_path = Path(target.name)
            total = 0
            first_chunk = True
            while chunk := await file.read(1024 * 1024):
                total += len(chunk)
                if total > max_bytes:
                    raise ServiceError(
                        "OCR_FILE_TOO_LARGE",
                        "扫描 PDF 超过 OCR 文件大小限制",
                        status_code=422,
                        details={"maxFileMb": settings.ocr_max_file_mb},
                    )
                if first_chunk:
                    first_chunk = False
                    if not chunk.startswith(b"%PDF-"):
                        raise ServiceError("OCR_PDF_INVALID", "上传内容不是有效 PDF", status_code=422)
                target.write(chunk)
        if temp_path is None or temp_path.stat().st_size == 0:
            raise ServiceError("OCR_PDF_INVALID", "PDF 文件为空", status_code=422)
        result = await request.app.state.ocr_service.extract(temp_path)
        return success(request, result.model_dump(by_alias=True, mode="json"))
    finally:
        await file.close()
        if temp_path is not None:
            await asyncio.to_thread(temp_path.unlink, missing_ok=True)
