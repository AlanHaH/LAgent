"""离线假数据客户端（`WEREAD_MCP_FAKE=1`）。

与 `WereadHttpClient` 同协议：登录自动成功、书架返回示例书、搜索按关键词过滤。
用于离线演示、Playwright 与全链路冒烟，绝不触碰真实微信读书账号。
"""

from __future__ import annotations

from datetime import UTC
from typing import Any

from .storage import CredentialStore
from .weread_client import WereadError

_SAMPLE_BOOKS = [
    {
        "bookId": "fake-001",
        "title": "深入理解Java虚拟机",
        "author": "周志明",
        "coverUrl": "",
        "category": "计算机",
        "categoryId": "18",
        "readingProgress": 0.36,
        "isFinished": False,
        "status": "reading",
        "updateTime": "2026-08-01T08:00:00Z",
        "lastReadChapter": "第3章 垃圾收集器",
        "wordCount": 468000,
        "format": "txt",
        "type": "book",
        "isPublic": False,
    },
    {
        "bookId": "fake-002",
        "title": "认知觉醒",
        "author": "周岭",
        "coverUrl": "",
        "category": "个人成长",
        "categoryId": "5",
        "readingProgress": 1.0,
        "isFinished": True,
        "status": "finished",
        "updateTime": "2026-07-20T08:00:00Z",
        "lastReadChapter": None,
        "wordCount": 190000,
        "format": "epub",
        "type": "book",
        "isPublic": True,
    },
    {
        "bookId": "fake-003",
        "title": "置身事内",
        "author": "兰小欢",
        "coverUrl": "",
        "category": "经济",
        "categoryId": "9",
        "readingProgress": 0.12,
        "isFinished": False,
        "status": "reading",
        "updateTime": "2026-07-28T08:00:00Z",
        "lastReadChapter": "第二章",
        "wordCount": 260000,
        "format": "epub",
        "type": "book",
        "isPublic": False,
    },
    {
        "bookId": "fake-004",
        "title": "置身事内：中国政府与经济发展",
        "author": "兰小欢",
        "coverUrl": "",
        "category": "经济",
        "categoryId": "9",
        "readingProgress": 0.0,
        "isFinished": False,
        "status": "unread",
        "updateTime": "2026-07-10T08:00:00Z",
        "lastReadChapter": None,
        "wordCount": 260000,
        "format": "txt",
        "type": "book",
        "isPublic": False,
    },
]

_SAMPLE_INTRO_BOOKS = [
    {
        "bookId": "fake-101",
        "title": "置身事内：中国政府与经济发展",
        "author": "兰小欢",
        "coverUrl": "",
        "category": "经济",
        "intro": "从地方政府投融资的角度理解中国经济的入门佳作。",
        "price": 19.9,
        "format": "epub",
        "type": "book",
        "deepLink": "https://weread.qq.com/book-detail?type=1&v=fake-101",
    },
    {
        "bookId": "fake-102",
        "title": "小岛经济学",
        "author": "彼得·希夫",
        "coverUrl": "",
        "category": "经济",
        "intro": "用寓言讲透宏观经济学。",
        "price": 25.0,
        "format": "epub",
        "type": "book",
        "deepLink": "https://weread.qq.com/book-detail?type=1&v=fake-102",
    },
]


class FakeWereadHttpClient:
    """离线的微信读书客户端替身。"""

    def __init__(self, store: CredentialStore) -> None:
        self._store = store
        self._qr_poll_count = 0

    async def aclose(self) -> None:
        return None

    def _credentials(self) -> dict[str, Any]:
        return self._store.load()

    async def login_status(self) -> dict[str, Any]:
        creds = self._credentials()
        logged = bool(creds.get("apiKey") or creds.get("cookie"))
        return {
            "loggedIn": logged,
            "loginType": "API_KEY" if creds.get("apiKey") else ("QR_CODE" if creds.get("cookie") else None),
            "nickname": creds.get("nickname") or ("示例读者" if logged else None),
            "headImgUrl": creds.get("headImgUrl"),
            "isVip": bool(creds.get("isVip") or False),
            "lastLoginAt": creds.get("lastLoginAt"),
        }

    async def verify_api_key(self, api_key: str) -> dict[str, Any]:
        if not api_key.startswith("wrk-"):
            raise WereadError("WEREAD_API_KEY_INVALID", "API Key 格式不正确，应以 wrk- 开头", status_code=401)
        self._store.persist(
            {"loginType": "API_KEY", "apiKey": api_key, "nickname": "示例读者", "lastLoginAt": _now()}
        )
        return {"nickname": "示例读者", "headImgUrl": None, "isVip": True}

    async def shelf_sync(self) -> list[dict[str, Any]]:
        if not (self._credentials().get("apiKey") or self._credentials().get("cookie")):
            raise WereadError("WEREAD_NOT_LOGGED_IN", "微信读书尚未登录", status_code=401)
        return [dict(book) for book in _SAMPLE_BOOKS]

    async def search(self, keyword: str, count: int = 10) -> list[dict[str, Any]]:
        if not (self._credentials().get("apiKey") or self._credentials().get("cookie")):
            raise WereadError("WEREAD_NOT_LOGGED_IN", "微信读书尚未登录", status_code=401)
        kw = keyword.lower()
        matches = [
            dict(b)
            for b in _SAMPLE_BOOKS
            if kw in str(b["title"]).lower() or kw in str(b["author"]).lower()
        ]
        return matches[:count]

    # ------------------------------------------------------------------
    # 官方 Gateway 扩展能力（与 WereadHttpClient 一致：仅 API Key 模式）
    # ------------------------------------------------------------------
    async def book_info(self, book_id: str) -> dict[str, Any]:
        if not self._credentials().get("apiKey"):
            raise WereadError("WEREAD_NOT_LOGGED_IN", "微信读书尚未登录", status_code=401)
        return {
            "bookId": book_id,
            "title": "深入理解Java虚拟机（第3版）",
            "author": "周志明",
            "coverUrl": "",
            "category": "计算机",
            "intro": "Java 开发者必读的 JVM 权威指南。",
            "publisher": "机械工业出版社",
            "publishTime": "2019-12-01",
            "isbn": "9787111641247",
            "translator": None,
            "wordCount": 468000,
            "newRating": 9.5,
            "newRatingCount": 12034,
            "ratingDetail": {
                "good": 90, "fair": 8, "poor": 2, "recent": 95, "deepV": 88, "myRating": "暂无评分",
            },
            "deepLink": f"https://weread.qq.com/book-detail?type=1&v={book_id}",
        }

    async def book_progress(self, book_id: str) -> dict[str, Any]:
        if not self._credentials().get("apiKey"):
            raise WereadError("WEREAD_NOT_LOGGED_IN", "微信读书尚未登录", status_code=401)
        return {
            "bookId": book_id,
            "progressPercent": 36,
            "chapterIdx": 3,
            "chapterUid": 123456,
            "readingTime": 2896,
            "updateTime": "2026-08-01T08:00:00Z",
            "lastReadAt": "2026-08-01T08:00:00Z",
        }

    async def recommend(self, count: int = 12) -> dict[str, Any]:
        if not self._credentials().get("apiKey"):
            raise WereadError("WEREAD_NOT_LOGGED_IN", "微信读书尚未登录", status_code=401)
        return {"books": [dict(b) for b in _SAMPLE_INTRO_BOOKS][:count]}

    async def similar(self, book_id: str, count: int = 12) -> dict[str, Any]:
        if not self._credentials().get("apiKey"):
            raise WereadError("WEREAD_NOT_LOGGED_IN", "微信读书尚未登录", status_code=401)
        return {"books": [dict(b) for b in _SAMPLE_INTRO_BOOKS][:count]}

    async def readdata_detail(self, mode: str = "overall") -> dict[str, Any]:
        if not self._credentials().get("apiKey"):
            raise WereadError("WEREAD_NOT_LOGGED_IN", "微信读书尚未登录", status_code=401)
        return {
            "totalReadTime": 1062443,
            "wrReadTime": 884478,
            "wrListenTime": 177965,
            "readDays": 768,
            "readRate": 83,
            "registTime": "1545717161",
            "preferCategoryWord": "偏好阅读心理",
            "preferTimeWord": "偏好深夜阅读",
            "preferBooks": [
                {"bookId": "fake-201", "title": "我的最爱", "cover": "", "type": 13},
                {"bookId": "fake-202", "title": "心理学与生活", "cover": "", "type": 1},
            ],
            "medals": [
                {
                    "name": "想法发布",
                    "displayText": "想法发布 10 条",
                    "rankText": "第 4704109 位获得此勋章的书友",
                },
            ],
        }

    async def qr_create(self) -> dict[str, Any]:
        return {"qrText": "https://weread.qq.com/fake-login", "qrToken": "fake-qr-token", "expiresIn": 180}

    async def qr_poll(self, qr_token: str) -> dict[str, Any]:
        self._qr_poll_count += 1
        if self._qr_poll_count < 2:
            return {"status": "PENDING"}
        return {
            "status": "SUCCESS",
            "user": {"nickname": "示例读者", "headImgUrl": None},
            "cookie": {"fake": "1"},
        }

    async def persist_qr_login(self, user: dict[str, Any] | None, cookie: dict[str, str]) -> None:
        self._store.persist(
            {
                "loginType": "QR_CODE",
                "cookie": cookie,
                "nickname": (user or {}).get("nickname") or "示例读者",
                "headImgUrl": (user or {}).get("headImgUrl"),
                "lastLoginAt": _now(),
            }
        )


def _now() -> str:
    from datetime import datetime

    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")
