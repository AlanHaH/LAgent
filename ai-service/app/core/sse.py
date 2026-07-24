from __future__ import annotations

import json
from collections.abc import AsyncIterator
from typing import Any


def encode_sse(event: str, data: Any) -> bytes:
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"), default=str)
    lines = "".join(f"data: {line}\n" for line in payload.splitlines() or [""])
    return f"event: {event}\n{lines}\n".encode()


async def single_event_stream(event: str, data: Any) -> AsyncIterator[bytes]:
    yield encode_sse(event, data)
