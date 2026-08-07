from __future__ import annotations

from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """weread-mcp 配置。环境变量前缀 `WEREAD_MCP_`（如 `WEREAD_MCP_PORT=8091`）。"""

    model_config = SettingsConfigDict(env_prefix="WEREAD_MCP_", extra="ignore", case_sensitive=False)

    host: str = "127.0.0.1"
    port: int = 8091
    # 离线模式：返回样例书架 + 自动成功的假二维码，供演示与测试（不碰真实账号）。
    fake: bool = False
    # 凭据持久化路径（API Key / Cookie），默认仓库内 weread-mcp/data/credentials.json。
    credentials_path: Path = Field(default=Path("./data/credentials.json"))
    request_timeout_seconds: float = Field(default=20.0, gt=0, le=120)
