from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AI_", extra="ignore", case_sensitive=False)

    env: Literal["local", "test", "production"] = "local"
    service_host: str = "127.0.0.1"
    service_port: int = Field(default=8090, ge=1, le=65535)
    internal_token: SecretStr = SecretStr("local-ai-internal-token-change-me-32")

    # 运行时系统提示词同步：从 Java 后端 /internal/v1/prompt-templates 拉取 ACTIVE 版本，
    # TTL 内自动生效，无需重启。sync_url 为空时禁用同步，回退到内置常量（离线/测试）。
    prompt_sync_url: str = ""
    prompt_sync_ttl_seconds: int = Field(default=60, ge=5, le=3600)

    model_enabled: bool = True
    model_base_url: str = ""
    model_api_key: SecretStr = SecretStr("")
    model_name: str = ""
    model_timeout_seconds: float = Field(default=60, gt=0, le=300)
    model_connect_timeout_seconds: float = Field(default=10, gt=0, le=60)
    model_max_output_tokens: int = Field(default=1200, ge=1, le=8000)
    model_max_concurrency: int = Field(default=8, ge=1, le=64)
    model_thinking: Literal["enabled", "disabled", ""] = "disabled"
    model_allow_http: bool = False

    embedding_provider: Literal["sentence_transformers", "hash"] = "sentence_transformers"
    embedding_model: str = "BAAI/bge-small-zh-v1.5"
    embedding_device: str = "cpu"
    embedding_batch_size: int = Field(default=32, ge=1, le=512)
    embedding_hash_dimension: int = Field(default=384, ge=64, le=4096)
    allow_hash_fallback: bool = False

    qdrant_mode: Literal["local", "server", "memory"] = "local"
    qdrant_path: Path = Path("./data/qdrant")
    qdrant_url: str = "http://127.0.0.1:6333"
    qdrant_api_key: SecretStr = SecretStr("")
    qdrant_timeout_seconds: float = Field(default=10, gt=0, le=60)

    rag_candidate_k: int = Field(default=20, ge=1, le=100)
    rag_top_k: int = Field(default=5, ge=1, le=20)
    rag_max_context_chars: int = Field(default=16000, ge=1000, le=100000)
    rag_evidence_threshold: float = Field(default=0.18, ge=0, le=1)
    rag_vector_weight: float = Field(default=0.85, ge=0, le=1)

    search_max_results: int = Field(default=5, ge=1, le=10)
    search_timeout_seconds: float = Field(default=12, ge=3, le=60)

    weread_mcp_enabled: bool = True
    weread_mcp_url: str = "http://127.0.0.1:8091/mcp"
    weread_mcp_timeout_seconds: float = Field(default=30, gt=0, le=120)
    # 离线模式：不连 weread-mcp，用 FakeWereadMcpClient 返回样例书架，供演示/测试。
    weread_mcp_fake: bool = False

    ocr_enabled: bool = True
    ocr_max_file_mb: int = Field(default=50, ge=1, le=200)
    ocr_max_pages: int = Field(default=100, ge=1, le=500)
    ocr_dpi: int = Field(default=180, ge=120, le=300)
    ocr_max_page_pixels: int = Field(default=20_000_000, ge=1_000_000, le=50_000_000)
    ocr_min_confidence: float = Field(default=0.45, ge=0, le=1)
    ocr_min_text_chars: int = Field(default=10, ge=1, le=1000)
    ocr_max_concurrency: int = Field(default=1, ge=1, le=4)
    ocr_timeout_seconds: float = Field(default=300, ge=10, le=1800)

    log_level: str = "INFO"
    log_path: Path = Path("./logs/python-ai.log")
    expose_docs: bool = True

    @field_validator("internal_token")
    @classmethod
    def validate_internal_token(cls, value: SecretStr) -> SecretStr:
        if len(value.get_secret_value()) < 32:
            raise ValueError("AI_INTERNAL_TOKEN must contain at least 32 characters")
        return value

    @property
    def model_configured(self) -> bool:
        return bool(
            self.model_enabled
            and self.model_base_url.strip()
            and self.model_api_key.get_secret_value().strip()
            and self.model_name.strip()
        )

    @model_validator(mode="after")
    def validate_production_secrets(self) -> Settings:
        if self.env == "production" and self.internal_token.get_secret_value().startswith("local-ai-"):
            raise ValueError("AI_INTERNAL_TOKEN must be replaced in production")
        return self


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
