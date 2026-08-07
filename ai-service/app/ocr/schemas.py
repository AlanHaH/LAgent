from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


class OcrPage(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    page_no: int = Field(alias="pageNo", ge=1)
    text: str = Field(min_length=1)
    confidence: float = Field(ge=0, le=1)


class PdfOcrResult(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    pages: list[OcrPage]
    page_count: int = Field(alias="pageCount", ge=1)
    recognized_pages: int = Field(alias="recognizedPages", ge=0)
    character_count: int = Field(alias="characterCount", ge=0)
    average_confidence: float = Field(alias="averageConfidence", ge=0, le=1)
    engine: str
