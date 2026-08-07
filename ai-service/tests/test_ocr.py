from __future__ import annotations

from types import SimpleNamespace

import numpy as np
import pymupdf
import pytest

from app.config import Settings
from app.core.errors import ServiceError
from app.ocr.service import PdfOcrService


class FakeOcrEngine:
    def __init__(self) -> None:
        self.calls = 0

    def __call__(self, image: np.ndarray) -> SimpleNamespace:
        assert image.ndim == 3
        self.calls += 1
        return SimpleNamespace(
            txts=(f"第{self.calls}页识别文本", "低置信度噪声"),
            scores=(0.96, 0.10),
        )


@pytest.mark.asyncio
async def test_pdf_ocr_renders_each_page_and_keeps_page_numbers(tmp_path) -> None:
    pdf = tmp_path / "scan.pdf"
    _blank_pdf(pdf, pages=2)
    engine = FakeOcrEngine()
    service = PdfOcrService(
        Settings(
            env="test",
            ocr_min_text_chars=4,
            ocr_min_confidence=0.45,
        ),
        engine=engine,
    )

    result = await service.extract(pdf)

    assert engine.calls == 2
    assert result.page_count == 2
    assert result.recognized_pages == 2
    assert [page.page_no for page in result.pages] == [1, 2]
    assert result.pages[0].text == "第1页识别文本"
    assert result.average_confidence == pytest.approx(0.96)


@pytest.mark.asyncio
async def test_pdf_ocr_rejects_page_count_over_limit(tmp_path) -> None:
    pdf = tmp_path / "too-many-pages.pdf"
    _blank_pdf(pdf, pages=2)
    service = PdfOcrService(
        Settings(env="test", ocr_max_pages=1),
        engine=FakeOcrEngine(),
    )

    with pytest.raises(ServiceError) as captured:
        await service.extract(pdf)

    assert captured.value.code == "OCR_PAGE_LIMIT_EXCEEDED"
    assert captured.value.details == {"pageCount": 2, "maxPages": 1}


def _blank_pdf(path, *, pages: int) -> None:  # type: ignore[no-untyped-def]
    document = pymupdf.open()
    for _ in range(pages):
        document.new_page(width=320, height=240)
    document.save(path)
    document.close()
