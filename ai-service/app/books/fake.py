"""离线替身 FakeWereadMcpClient。

与 `WereadMcpClient` 同协议：扫码自动成功（第 3 次轮询）、书架返回示例书。
既作测试替身，也供 `AI_WEREAD_MCP_FAKE=1` 的生产路径使用。
"""

from __future__ import annotations

from typing import Any

from app.core.errors import ServiceError

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

_FAKE_QR = (
    "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCA"
    "AAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
)


class FakeWereadMcpClient:
    def __init__(self) -> None:
        self._logged_in = False
        self._login_type: str | None = None
        self._nickname: str | None = None
        self._qr_started = False
        self._qr_polls = 0

    async def login_status(self) -> dict[str, Any]:
        if not self._logged_in and self._qr_started:
            self._qr_polls += 1
            if self._qr_polls >= 3:
                self._logged_in = True
                self._login_type = "QR_CODE"
                self._nickname = "示例读者"
                self._qr_started = False
            else:
                status = "SCANNED" if self._qr_polls == 2 else "PENDING"
                message = "已扫码，请在手机上确认" if self._qr_polls == 2 else "请用微信扫一扫登录"
                return {
                    "loggedIn": False,
                    "loginType": None,
                    "nickname": None,
                    "headImgUrl": None,
                    "isVip": False,
                    "lastLoginAt": None,
                    "loginQr": {
                        "status": status,
                        "qrBase64": _FAKE_QR,
                        "qrToken": "fake-qr-token",
                        "message": message,
                        "expiresAt": None,
                    },
                }
        return self._status_view()

    def _status_view(self) -> dict[str, Any]:
        return {
            "loggedIn": self._logged_in,
            "loginType": self._login_type,
            "nickname": self._nickname,
            "headImgUrl": None,
            "isVip": False,
            "lastLoginAt": None,
            "loginQr": None,
        }

    async def login_qrcode(self) -> dict[str, Any]:
        if self._logged_in:
            return {
                "status": "SUCCESS",
                "qrBase64": None,
                "qrToken": None,
                "message": "已登录",
                "expiresAt": None,
            }
        self._qr_started = True
        self._qr_polls = 0
        return {
            "status": "PENDING",
            "qrBase64": _FAKE_QR,
            "qrToken": "fake-qr-token",
            "message": "请用微信扫一扫登录",
            "expiresAt": None,
        }

    async def set_api_key(self, api_key: str) -> dict[str, Any]:
        if not api_key.startswith("wrk-"):
            raise ServiceError(
                "WEREAD_API_KEY_INVALID", "API Key 格式不正确，应以 wrk- 开头", status_code=401
            )
        self._logged_in = True
        self._login_type = "API_KEY"
        self._nickname = "示例读者"
        return self._status_view()

    async def logout(self) -> dict[str, Any]:
        self._logged_in = False
        self._login_type = None
        self._nickname = None
        self._qr_started = False
        self._qr_polls = 0
        return {"loggedIn": False}

    async def get_bookshelf(self) -> dict[str, Any]:
        if not self._logged_in:
            raise ServiceError(
                "WEREAD_NOT_LOGGED_IN", "微信读书尚未登录，请先扫码或配置 API Key", status_code=401
            )
        books = [dict(book) for book in _SAMPLE_BOOKS]
        return {
            "total": len(books),
            "readingCount": sum(1 for b in books if b["status"] == "reading"),
            "finishedCount": sum(1 for b in books if b["isFinished"]),
            "books": books,
        }

    async def search_books(self, keyword: str, count: int) -> dict[str, Any]:
        if not self._logged_in:
            raise ServiceError(
                "WEREAD_NOT_LOGGED_IN", "微信读书尚未登录，请先扫码或配置 API Key", status_code=401
            )
        kw = keyword.lower()
        books = [
            dict(b)
            for b in _SAMPLE_BOOKS
            if kw in str(b["title"]).lower() or kw in str(b["author"]).lower()
        ][:count]
        return {"books": books}

    def _require_login(self) -> None:
        if not self._logged_in:
            raise ServiceError(
                "WEREAD_NOT_LOGGED_IN", "微信读书尚未登录，请先扫码或配置 API Key", status_code=401
            )

    async def book_info(self, book_id: str) -> dict[str, Any]:
        self._require_login()
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
        self._require_login()
        return {
            "bookId": book_id,
            "progressPercent": 36,
            "chapterIdx": 3,
            "chapterUid": 123456,
            "readingTime": 2896,
            "updateTime": "2026-08-01T08:00:00Z",
            "lastReadAt": "2026-08-01T08:00:00Z",
        }

    async def readdata_detail(self, mode: str) -> dict[str, Any]:
        self._require_login()
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

    async def recommend_books(self, count: int) -> dict[str, Any]:
        self._require_login()
        return {"books": [dict(b) for b in _SAMPLE_INTRO_BOOKS][:count]}

    async def similar_books(self, book_id: str, count: int) -> dict[str, Any]:
        self._require_login()
        return {"books": [dict(b) for b in _SAMPLE_INTRO_BOOKS][:count]}
