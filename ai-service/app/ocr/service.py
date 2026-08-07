from __future__ import annotations

import asyncio
import logging
from pathlib import Path
from typing import Any

from app.config import Settings
from app.core.errors import ServiceError
from app.ocr.schemas import OcrPage, PdfOcrResult

logger = logging.getLogger(__name__)


class PdfOcrService:
    def __init__(self, settings: Settings, *, engine: Any | None = None) -> None:
        self._settings = settings
        self._engine = engine
        self._semaphore = asyncio.Semaphore(settings.ocr_max_concurrency)

    async def extract(self, path: Path) -> PdfOcrResult:
        if not self._settings.ocr_enabled:
            raise ServiceError(
                "OCR_DEPENDENCY_UNAVAILABLE",
                "扫描 PDF OCR 未启用",
                status_code=503,
            )
        async with self._semaphore:
            try:
                return await asyncio.wait_for(
                    asyncio.to_thread(self._extract_sync, path),
                    timeout=self._settings.ocr_timeout_seconds,
                )
            except TimeoutError as error:
                raise ServiceError(
                    "OCR_TIMEOUT",
                    "扫描 PDF OCR 处理超时",
                    status_code=504,
                    retryable=True,
                    details={"timeoutSeconds": self._settings.ocr_timeout_seconds},
                ) from error

    def _extract_sync(self, path: Path) -> PdfOcrResult:
        try:
            import numpy as np
            import pymupdf
        except ImportError as error:
            raise ServiceError(
                "OCR_DEPENDENCY_UNAVAILABLE",
                "OCR 依赖未安装，请安装 Python AI 的 OCR 依赖",
                status_code=503,
            ) from error

        try:
            document = pymupdf.open(path)  # type: ignore[no-untyped-call]
        except Exception as error:
            raise ServiceError("OCR_PDF_INVALID", "PDF 文件损坏或无法打开", status_code=422) from error

        try:
            page_count = document.page_count
            if page_count < 1:
                raise ServiceError("OCR_PDF_INVALID", "PDF 没有页面", status_code=422)
            if page_count > self._settings.ocr_max_pages:
                raise ServiceError(
                    "OCR_PAGE_LIMIT_EXCEEDED",
                    "扫描 PDF 页数超过 OCR 限制",
                    status_code=422,
                    details={"pageCount": page_count, "maxPages": self._settings.ocr_max_pages},
                )

            if self._engine is None:
                self._engine = self._build_engine()
            engine = self._engine
            pages: list[OcrPage] = []
            all_scores: list[float] = []
            character_count = 0
            for page_index in range(page_count):
                page = document.load_page(page_index)  # type: ignore[no-untyped-call]
                pixmap = page.get_pixmap(
                    dpi=self._settings.ocr_dpi,
                    colorspace=pymupdf.csRGB,
                    alpha=False,
                )
                if pixmap.width * pixmap.height > self._settings.ocr_max_page_pixels:
                    raise ServiceError(
                        "OCR_PAGE_TOO_LARGE",
                        "PDF 页面像素尺寸超过 OCR 安全限制",
                        status_code=422,
                        details={"pageNo": page_index + 1},
                    )
                image = np.frombuffer(pixmap.samples, dtype=np.uint8).reshape(
                    pixmap.height, pixmap.width, pixmap.n
                )
                result = engine(image)
                texts = tuple(getattr(result, "txts", ()) or ())
                scores = tuple(getattr(result, "scores", ()) or ())
                accepted: list[str] = []
                accepted_scores: list[float] = []
                for index, text in enumerate(texts):
                    value = str(text).strip()
                    score = float(scores[index]) if index < len(scores) else 0.0
                    if value and score >= self._settings.ocr_min_confidence:
                        accepted.append(value)
                        accepted_scores.append(score)
                if not accepted:
                    continue
                page_text = "\n".join(accepted)
                character_count += len(page_text)
                all_scores.extend(accepted_scores)
                pages.append(
                    OcrPage(
                        page_no=page_index + 1,
                        text=page_text,
                        confidence=sum(accepted_scores) / len(accepted_scores),
                    )
                )

            if character_count < self._settings.ocr_min_text_chars:
                raise ServiceError(
                    "OCR_NO_TEXT",
                    "OCR 未识别到足够的可用文字",
                    status_code=422,
                    details={
                        "pageCount": page_count,
                        "recognizedPages": len(pages),
                        "characterCount": character_count,
                    },
                )
            logger.info(
                "pdf_ocr_completed pages=%s recognizedPages=%s characterCount=%s",
                page_count,
                len(pages),
                character_count,
            )
            return PdfOcrResult(
                pages=pages,
                page_count=page_count,
                recognized_pages=len(pages),
                character_count=character_count,
                average_confidence=sum(all_scores) / len(all_scores) if all_scores else 0,
                engine="rapidocr-onnxruntime",
            )
        finally:
            document.close()  # type: ignore[no-untyped-call]

    @staticmethod
    def _build_engine() -> Any:
        try:
            from rapidocr import RapidOCR

            return RapidOCR()
        except (ImportError, RuntimeError) as error:
            raise ServiceError(
                "OCR_DEPENDENCY_UNAVAILABLE",
                "RapidOCR 或 ONNX Runtime 不可用",
                status_code=503,
            ) from error
