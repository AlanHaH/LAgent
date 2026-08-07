from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any


class CredentialStore:
    """微信读书凭据的原子持久化。

    写入先落 `{path}.tmp` 再 `os.replace`，避免半截文件；文件权限 0600。
    凭据（wrk- Key / Cookie）等同账号凭证，绝不写进日志。
    """

    def __init__(self, path: Path) -> None:
        self._path = path

    def load(self) -> dict[str, Any]:
        if not self._path.exists():
            return {}
        try:
            data = json.loads(self._path.read_text(encoding="utf-8"))
            return data if isinstance(data, dict) else {}
        except (json.JSONDecodeError, OSError):
            return {}

    def persist(self, data: dict[str, Any]) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self._path.with_suffix(self._path.suffix + ".tmp")
        tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(tmp, self._path)
        try:
            os.chmod(self._path, 0o600)
        except OSError:
            # Windows 上 chmod 语义有限，忽略即可。
            pass

    def clear(self) -> None:
        if self._path.exists():
            self._path.unlink()
