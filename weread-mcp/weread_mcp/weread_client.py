"""微信读书 HTTP 适配层。

支持两种凭据模式：
- **API Key（官方 Agent Gateway）**：`POST i.weread.qq.com/api/agent/gateway`，
  `Authorization: Bearer wrk-...`，body 扁平 JSON `{api_name, skill_version, ...}`。稳定，推荐。
- **Cookie（网页版扫码登录）**：`weread.qq.com/web/shelf/sync` 等 V1 Web 接口。

扫码登录的网页版端点无公开文档（社区逆向），全部集中在文件顶部常量，运行时用抓包
校准只需改这一处；`set_api_key` / `verify_api_key` 所依赖的 Gateway 子接口名与
`skill_version` 同样是校准点。
"""

from __future__ import annotations

import logging
import re
from datetime import UTC
from typing import Any

import httpx

from .storage import CredentialStore

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# 端点集中区 —— 运行时校准只改这里
# ---------------------------------------------------------------------------
WEREAD_GATEWAY_URL = "https://i.weread.qq.com/api/agent/gateway"  # 官方 Agent Gateway
WEREAD_SHELF_URL = "https://weread.qq.com/web/shelf/sync"  # Cookie 书架
WEREAD_LOGIN_QR_URL = "https://weread.qq.com/web/login/qrcode"  # 扫码（待校准）
WEREAD_LOGIN_POLL_URL = "https://weread.qq.com/web/login/qr_verify"  # 扫码轮询（待校准）
WEREAD_BOOK_DETAIL_URL = "https://weread.qq.com/book-detail?type=1&v="  # 官方阅读跳转链接前缀

# 官方 Gateway 子接口（api_name，路径式命名）与 skill 版本。校准依据官方
# weread-skills.zip（SKILL.md frontmatter `version: 1.0.4`，运行时可用 `/_list` 拉全量）：
#   登录校验/书架 -> /shelf/sync（无参数，用户身份由 Key 自动识别）
#   搜索         -> /store/search（keyword/scope/count）
#   书籍信息     -> /book/info（bookId）
#   阅读进度     -> /book/getprogress（bookId）
#   阅读统计     -> /readdata/detail（mode: weekly/monthly/annually/overall）
#   推荐/相似    -> /book/recommend（count）、/book/similar（bookId/count/maxIdx）
GATEWAY_API_LOGIN_CHECK = "/shelf/sync"
GATEWAY_API_BOOKSHELF = "/shelf/sync"
GATEWAY_API_SEARCH = "/store/search"
GATEWAY_API_BOOK_INFO = "/book/info"
GATEWAY_API_BOOK_PROGRESS = "/book/getprogress"
GATEWAY_API_READDATA = "/readdata/detail"
GATEWAY_API_RECOMMEND = "/book/recommend"
GATEWAY_API_SIMILAR = "/book/similar"
GATEWAY_SKILL_VERSION = "1.0.4"

_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"
)


class WereadError(Exception):
    """携带稳定错误码的微信读书错误，最终映射为三层共用的 HTTP 错误码。"""

    def __init__(self, code: str, message: str, status_code: int = 502) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


def _deep_find(data: dict[str, Any], *keys: str) -> Any:
    """在嵌套 dict 里按给定键名依次查找（字段名随微信读书接口调整，防御式取值）。"""
    for key in keys:
        if key in data:
            return data[key]
        for value in data.values():
            if isinstance(value, dict):
                found = _deep_find(value, key)
                if found is not None:
                    return found
    return None


def _as_str(value: Any) -> str | None:
    """契约要求 updateTime/lastReadChapter 等为字符串；微信读书常回 int（Unix 时间戳等）。"""
    return str(value) if value is not None else None


def _book_deep_link(book_id: str) -> str | None:
    """按官方约定合成阅读跳转链接（deepLink 缺失时回退，官方书架书籍全部带此模式）。"""
    return f"{WEREAD_BOOK_DETAIL_URL}{book_id}" if book_id else None


class WereadHttpClient:
    """真实微信读书客户端。fake 模式使用 `FakeWereadHttpClient`（同协议）。"""

    def __init__(
        self,
        store: CredentialStore,
        timeout: float = 20.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._store = store
        # trust_env=False：不走系统代理。本机可能开着 Clash 等代理（127.0.0.1:7897），
        # httpx 默认 trust_env=True 会把微信读书请求也塞进代理，导致 i.weread.qq.com
        # 返回 502/超时。微信读书是可直接访问的国内服务，直连即可。
        self._http = httpx.AsyncClient(
            timeout=timeout,
            headers={"User-Agent": _UA, "Referer": "https://weread.qq.com/"},
            transport=transport,
            trust_env=False,
        )

    async def aclose(self) -> None:
        await self._http.aclose()

    # ------------------------------------------------------------------
    # 凭据
    # ------------------------------------------------------------------
    def _credentials(self) -> dict[str, Any]:
        return self._store.load()

    def _has_api_key(self) -> bool:
        return bool(self._credentials().get("apiKey"))

    def _has_cookie(self) -> bool:
        return bool(self._credentials().get("cookie"))

    # ------------------------------------------------------------------
    # 官方 Gateway（API Key 模式）
    # ------------------------------------------------------------------
    async def gateway(self, api_name: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        """调官方 Agent Gateway 子接口。返回该接口的 data（兼容两种返回形态）。"""
        creds = self._credentials()
        api_key = creds.get("apiKey")
        if not api_key:
            raise WereadError(
                "WEREAD_NOT_LOGGED_IN", "微信读书尚未登录，请先扫码或配置 API Key", status_code=401
            )
        body = {"api_name": api_name, "skill_version": GATEWAY_SKILL_VERSION, **(payload or {})}
        resp = await self._http.post(
            WEREAD_GATEWAY_URL, json=body, headers={"Authorization": f"Bearer {api_key}"}
        )
        logger.info("weread gateway api=%s status=%s", api_name, resp.status_code)
        if resp.status_code in (401, 403):
            raise WereadError("WEREAD_LOGIN_EXPIRED", "微信读书登录已过期，请重新登录", status_code=401)
        try:
            data = resp.json()
        except ValueError as exc:
            logger.warning(
                "weread gateway %s non-JSON status=%s text=%r",
                api_name, resp.status_code, resp.text[:200],
            )
            raise WereadError("WEREAD_REMOTE_ERROR", "微信读书返回异常", status_code=502) from exc
        if not isinstance(data, dict):
            raise WereadError("WEREAD_REMOTE_ERROR", "微信读书返回异常", status_code=502)
        errcode = data.get("errcode")
        if errcode not in (None, 0):
            message = data.get("errmsg") or f"errcode={errcode}"
            logger.warning("weread gateway %s errcode=%s errmsg=%s", api_name, errcode, message)
            code = "WEREAD_LOGIN_EXPIRED" if errcode in (40100, 40101, 40102) else "WEREAD_REMOTE_ERROR"
            status_code = 401 if code.startswith("WEREAD_LOGIN") else 502
            raise WereadError(code, f"微信读书接口错误：{message}", status_code=status_code)
        inner = data.get("data")
        return inner if isinstance(inner, dict) else data

    async def verify_api_key(self, api_key: str) -> dict[str, Any]:
        """用 Key 调一次轻量接口校验并取账号信息（昵称/头像）。"""
        body = {"api_name": GATEWAY_API_LOGIN_CHECK, "skill_version": GATEWAY_SKILL_VERSION}
        resp = await self._http.post(
            WEREAD_GATEWAY_URL, json=body, headers={"Authorization": f"Bearer {api_key}"}
        )
        logger.info("weread verify_api_key api=%s status=%s", GATEWAY_API_LOGIN_CHECK, resp.status_code)
        if resp.status_code in (401, 403):
            raise WereadError("WEREAD_API_KEY_INVALID", "API Key 无效或已失效，请检查后重试", status_code=401)
        try:
            data = resp.json()
        except ValueError as exc:
            logger.warning(
                "weread verify_api_key non-JSON status=%s text=%r",
                resp.status_code, resp.text[:200],
            )
            raise WereadError("WEREAD_REMOTE_ERROR", "微信读书返回异常", status_code=502) from exc
        if not isinstance(data, dict):
            raise WereadError("WEREAD_REMOTE_ERROR", "微信读书返回异常", status_code=502)
        if data.get("errcode") not in (None, 0):
            logger.warning(
                "weread verify_api_key rejected errcode=%s errmsg=%s",
                data.get("errcode"), data.get("errmsg"),
            )
            raise WereadError("WEREAD_API_KEY_INVALID", "API Key 无效或已失效，请检查后重试", status_code=401)
        inner = data.get("data") if isinstance(data.get("data"), dict) else data
        return {
            "nickname": _deep_find(inner, "nickname", "name") or None,
            "headImgUrl": _deep_find(inner, "headImgUrl", "headImg", "avatarUrl", "avatar") or None,
            "isVip": bool(_deep_find(inner, "isVip", "vip") or False),
        }

    # ------------------------------------------------------------------
    # 书架
    # ------------------------------------------------------------------
    async def shelf_sync(self) -> list[dict[str, Any]]:
        """拉取书架并归一化为 BookView 契约 dict 列表。"""
        if self._has_api_key():
            data = await self.gateway(GATEWAY_API_BOOKSHELF)
            raw_books: Any = data.get("books") or data.get("shelf") or data.get("bookShelf") or []
        else:
            cookies = self._credentials().get("cookie")
            if not cookies:
                raise WereadError(
                    "WEREAD_NOT_LOGGED_IN", "微信读书尚未登录，请先扫码或配置 API Key", status_code=401
                )
            self._http.cookies.update(cookies)
            resp = await self._http.get(
                WEREAD_SHELF_URL,
                params={"listType": 1, "mine": 1, "synckey": 0},
            )
            if resp.status_code in (401, 403):
                raise WereadError("WEREAD_LOGIN_EXPIRED", "扫码登录已过期，请重新登录", status_code=401)
            try:
                data = resp.json()
            except ValueError as exc:
                raise WereadError("WEREAD_REMOTE_ERROR", "微信读书返回异常", status_code=502) from exc
            if not isinstance(data, dict):
                raise WereadError("WEREAD_REMOTE_ERROR", "微信读书返回异常", status_code=502)
            raw_books = data.get("books") or []
        books = [self._normalize_book(item) for item in raw_books if isinstance(item, dict)]
        # 专辑/有声书与 books[] 独立，同样计入书架（BookView.type=audiobook）。
        for album in data.get("albums") or []:
            info = album.get("albumInfo") if isinstance(album, dict) else None
            if isinstance(info, dict):
                books.append(self._normalize_album(info))

        def _sort_key(book: dict[str, Any]) -> int:
            # updateTime 已按契约转成字符串，排序时还原为数值（Unix 时间戳）。
            try:
                return int(book.get("updateTime") or 0)
            except (TypeError, ValueError):
                return 0

        books.sort(key=_sort_key, reverse=True)
        return books

    def _normalize_book(self, raw: dict[str, Any]) -> dict[str, Any]:
        """微信读书原始字段 → BookView 契约（字段名随接口调整，防御式取值）。"""
        progress = _deep_find(raw, "readingProgress", "progress", "readingProgressPercent") or 0
        try:
            progress = round(float(progress) / 100 if float(progress) > 1 else float(progress), 4)
        except (TypeError, ValueError):
            progress = 0.0
        is_finished = _deep_find(raw, "finishReading", "finished") in (1, "1", True) or _deep_find(
            raw, "readingStatus", "status"
        ) in ("finished", 2)
        # 书架接口常不返回进度，只有最近阅读时间（readUpdateTime）；读过（有时间或进度>0）即视为 reading。
        update_time = _deep_find(raw, "readUpdateTime", "updateTime")
        has_read = update_time is not None or progress > 0
        status = "finished" if is_finished else ("reading" if has_read else "unread")
        book_format = _deep_find(raw, "format", "bookFormat")
        return {
            "bookId": str(_deep_find(raw, "bookId", "id") or ""),
            "title": str(_deep_find(raw, "title") or ""),
            "author": _deep_find(raw, "author"),
            "coverUrl": _deep_find(raw, "cover", "coverUrl"),
            "category": _deep_find(raw, "category"),
            "categoryId": _as_str(_deep_find(raw, "categoryId", "category_id")) or None,
            "readingProgress": progress,
            "isFinished": is_finished,
            "status": status,
            "updateTime": _as_str(update_time),
            "lastReadChapter": _as_str(_deep_find(raw, "lastReadChapter", "last_read_chapter")),
            "wordCount": _deep_find(raw, "wordCount", "word_count"),
            "format": book_format,
            "type": "audiobook" if book_format == "audio" else "book",
            "isPublic": _deep_find(raw, "secret") != 1,  # secret=1 私密
            "deepLink": _deep_find(raw, "deepLink"),  # 官方阅读跳转链接（SKILL.md 推荐直接用）
        }

    def _normalize_album(self, info: dict[str, Any]) -> dict[str, Any]:
        """专辑/有声书（albums[].albumInfo）→ BookView 契约。"""
        is_finished = info.get("finish") == 1
        update_time = info.get("lectureReadUpdateTime") or info.get("updateTime")
        return {
            "bookId": _as_str(info.get("albumId")) or "",
            "title": str(info.get("name") or ""),
            "author": info.get("authorName"),
            "coverUrl": info.get("cover"),
            "category": None,
            "categoryId": None,
            "readingProgress": 1.0 if is_finished else 0.0,
            "isFinished": is_finished,
            "status": "finished" if is_finished else ("reading" if update_time else "unread"),
            "updateTime": _as_str(update_time),
            "lastReadChapter": None,
            "wordCount": info.get("trackCount"),
            "format": "audio",
            "type": "audiobook",
            "isPublic": info.get("secret") != 1,
            "deepLink": info.get("deepLink"),
        }

    # ------------------------------------------------------------------
    # 搜索（官方 Gateway，可选能力）
    # ------------------------------------------------------------------
    async def search(self, keyword: str, count: int = 10) -> list[dict[str, Any]]:
        if not self._has_api_key():
            raise WereadError("WEREAD_NOT_LOGGED_IN", "搜索需要先配置 API Key", status_code=401)
        # scope=10 表示电子书（官方 SKILL.md 说明），count 业务参数与 api_name/skill_version 平铺。
        data = await self.gateway(GATEWAY_API_SEARCH, {"keyword": keyword, "count": count, "scope": 10})
        raw = data.get("results") or data.get("books") or []
        books = []
        for item in raw:
            if not isinstance(item, dict):
                continue
            # 搜索结果条目可能把书籍包在 book/bookInfo 子对象里，防御式解包。
            book = item.get("book") if isinstance(item.get("book"), dict) else item
            book = book.get("bookInfo") if isinstance(book.get("bookInfo"), dict) else book
            books.append(self._normalize_book(book))
        return books

    # ------------------------------------------------------------------
    # 官方 Gateway 扩展能力（信息/进度/统计/推荐/相似；均需 API Key）
    # ------------------------------------------------------------------
    async def book_info(self, book_id: str) -> dict[str, Any]:
        """书籍基本信息 → BookInfoView dict（网关字段稀疏，空字段保持 None）。"""
        data = await self.gateway(GATEWAY_API_BOOK_INFO, {"bookId": book_id})
        rating = data.get("newRatingDetail")
        rating_detail = None
        if isinstance(rating, dict):
            rating_detail = {
                "good": rating.get("good"),
                "fair": rating.get("fair"),
                "poor": rating.get("poor"),
                "recent": rating.get("recent"),
                "deepV": rating.get("deepV"),
                "myRating": rating.get("myRating"),
            }
        return {
            "bookId": _as_str(data.get("bookId")) or book_id,
            "title": data.get("title"),
            "author": data.get("author"),
            "coverUrl": data.get("cover"),
            "category": data.get("category"),
            "intro": data.get("intro"),
            "publisher": data.get("publisher"),
            "publishTime": data.get("publishTime"),
            "isbn": data.get("isbn"),
            "translator": data.get("translator"),
            "wordCount": data.get("wordCount"),
            "newRating": data.get("newRating"),
            "newRatingCount": data.get("newRatingCount"),
            "ratingDetail": rating_detail,
            "deepLink": data.get("deepLink") or _book_deep_link(book_id),
        }

    async def book_progress(self, book_id: str) -> dict[str, Any]:
        """用户对某本书的精确阅读进度 → BookProgressView dict。"""
        data = await self.gateway(GATEWAY_API_BOOK_PROGRESS, {"bookId": book_id})
        book = data.get("book") if isinstance(data.get("book"), dict) else {}
        return {
            "bookId": _as_str(data.get("bookId")) or book_id,
            "progressPercent": book.get("progress"),
            "chapterIdx": book.get("chapterIdx"),
            "chapterUid": book.get("chapterUid"),
            "readingTime": book.get("readingTime"),
            "updateTime": _as_str(book.get("updateTime")),
            "lastReadAt": _as_str(data.get("timestamp")),
        }

    async def recommend(self, count: int = 12) -> dict[str, Any]:
        """个性化推荐书籍 → {"books": [BookIntroView dict]}。"""
        data = await self.gateway(GATEWAY_API_RECOMMEND, {"count": count})
        return {
            "books": [
                self._normalize_intro_book(item)
                for item in data.get("books", [])
                if isinstance(item, dict)
            ]
        }

    async def similar(self, book_id: str, count: int = 12) -> dict[str, Any]:
        """相似书籍 → {"books": [BookIntroView dict]}（条目把书包在 item.book 里，防御式解包）。"""
        # maxIdx 必传（实测缺省返回 errcode -2003 参数格式错误），0 表示首页。
        data = await self.gateway(
            GATEWAY_API_SIMILAR, {"bookId": book_id, "count": count, "maxIdx": 0}
        )
        # 该接口结果包在 booksimilar 键下（非 data 键），兼容两种形态。
        similar_wrapper = data.get("booksimilar")
        if isinstance(similar_wrapper, dict):
            data = similar_wrapper
        books = []
        for item in data.get("books", []):
            if not isinstance(item, dict):
                continue
            book = item.get("book") if isinstance(item.get("book"), dict) else item
            books.append(self._normalize_intro_book(book))
        return {"books": books}

    async def readdata_detail(self, mode: str = "overall") -> dict[str, Any]:
        """阅读统计（周/月/年/总）→ ReadDataDetailView dict。"""
        data = await self.gateway(GATEWAY_API_READDATA, {"mode": mode})
        prefer_books = []
        for item in data.get("preferBooks", []):
            if not isinstance(item, dict):
                continue
            prefer_books.append(
                {
                    "bookId": _as_str(_deep_find(item, "bookId", "id")),
                    "title": _deep_find(item, "title"),
                    "cover": _deep_find(item, "cover", "coverUrl"),
                    "type": item.get("type"),
                }
            )
        medals = []
        for item in data.get("medals", []):
            if not isinstance(item, dict):
                continue
            medals.append(
                {
                    "name": item.get("name"),
                    "displayText": item.get("displayText"),
                    "rankText": item.get("rankText"),
                }
            )
        return {
            "totalReadTime": data.get("totalReadTime"),
            "wrReadTime": data.get("wrReadTime"),
            "wrListenTime": data.get("wrListenTime"),
            "readDays": data.get("readDays"),
            "readRate": data.get("readRate"),
            "registTime": _as_str(data.get("registTime")),
            "preferCategoryWord": data.get("preferCategoryWord"),
            "preferTimeWord": data.get("preferTimeWord"),
            "preferBooks": prefer_books,
            "medals": medals,
        }

    def _normalize_intro_book(self, raw: dict[str, Any]) -> dict[str, Any]:
        """推荐/相似书籍条目 → BookIntroView dict（deepLink 缺失时按 bookId 合成）。"""
        book_id = str(_deep_find(raw, "bookId", "id") or "")
        return {
            "bookId": book_id,
            "title": _deep_find(raw, "title"),
            "author": _deep_find(raw, "author"),
            "coverUrl": _deep_find(raw, "cover", "coverUrl"),
            "category": _deep_find(raw, "category"),
            "intro": _deep_find(raw, "intro"),
            "price": _deep_find(raw, "price"),
            "format": _deep_find(raw, "format", "bookFormat"),
            "type": "audiobook" if _deep_find(raw, "format", "bookFormat") == "audio" else "book",
            "deepLink": _deep_find(raw, "deepLink") or _book_deep_link(book_id),
        }

    # ------------------------------------------------------------------
    # 扫码登录（网页版，待校准）
    # ------------------------------------------------------------------
    async def qr_create(self) -> dict[str, Any]:
        """向微信读书网页登录取二维码内容。返回 {qrText, qrToken, expiresIn}。"""
        resp = await self._http.get(WEREAD_LOGIN_QR_URL)
        if resp.status_code != 200:
            raise WereadError("WEREAD_REMOTE_ERROR", "获取登录二维码失败，请稍后重试", status_code=502)
        text = resp.text
        # 优先解析 JSON；否则从 HTML 里抽取 vid/token，都不行则回退到登录页 URL 本身。
        token: str | None = None
        try:
            data = resp.json()
            if isinstance(data, dict):
                token = str(_deep_find(data, "vid", "token", "qrToken", "qrcodeId") or "")
        except ValueError:
            pass
        if not token:
            match = re.search(r'(?:vid|token|qrcodeId)=["\']?([0-9A-Za-z_-]{8,})', text)
            if match:
                token = match.group(1)
        qr_text = f"{WEREAD_LOGIN_QR_URL}?vid={token}" if token else WEREAD_LOGIN_QR_URL
        return {"qrText": qr_text, "qrToken": token or qr_text, "expiresIn": 180}

    async def qr_poll(self, qr_token: str) -> dict[str, Any]:
        """轮询扫码状态。返回 {status, user?, cookie?}，status ∈ PENDING/SCANNED/SUCCESS/FAILED。"""
        resp = await self._http.get(
            WEREAD_LOGIN_POLL_URL,
            params={"vid": qr_token} if not qr_token.startswith("http") else {"qrToken": qr_token},
        )
        if resp.status_code != 200:
            return {"status": "PENDING"}
        try:
            data = resp.json()
        except ValueError:
            return {"status": "PENDING"}
        if not isinstance(data, dict):
            return {"status": "PENDING"}
        errcode = data.get("errcode")
        if errcode in (40004, 40104, 40404):  # 未扫码/二维码失效等，待校准
            return {"status": "PENDING"}
        raw_status = str(data.get("status") or data.get("result") or "").upper()
        if raw_status in ("SUCCESS", "CONFIRMED", "OK", "1"):
            return {
                "status": "SUCCESS",
                "user": {
                    "nickname": _deep_find(data, "nickname", "name") or None,
                    "headImgUrl": _deep_find(data, "headImgUrl", "headImg", "avatarUrl") or None,
                },
                "cookie": self._extract_cookies(data),
            }
        if raw_status in ("SCANNED", "2", "3"):
            return {"status": "SCANNED"}
        return {"status": "PENDING"}

    def _extract_cookies(self, data: dict[str, Any]) -> dict[str, str]:
        """从扫码成功响应里取会话 Cookie（wr_vid/wr_skey 等，待校准）。"""
        cookie: dict[str, str] = {}
        vid = _deep_find(data, "vid", "userId", "userVid")
        if vid:
            cookie["wr_vid"] = str(vid)
        for key in ("wr_skey", "skey", "access_token", "refresh_token"):
            value = _deep_find(data, key)
            if value:
                cookie[key] = str(value)
        return cookie

    async def persist_qr_login(self, user: dict[str, Any] | None, cookie: dict[str, str]) -> None:
        """扫码成功后落库 Cookie 与账号信息。"""
        creds = self._credentials()
        creds.pop("apiKey", None)
        creds.update(
            {
                "loginType": "QR_CODE",
                "cookie": cookie or creds.get("cookie", {}),
                "nickname": (user or {}).get("nickname") or creds.get("nickname"),
                "headImgUrl": (user or {}).get("headImgUrl") or creds.get("headImgUrl"),
                "lastLoginAt": _now_iso(),
            }
        )
        self._store.persist(creds)

    async def login_status(self) -> dict[str, Any]:
        """读取本地凭据的登录态（不触发网络）。"""
        creds = self._credentials()
        return {
            "loggedIn": self._has_api_key() or self._has_cookie(),
            "loginType": "API_KEY" if self._has_api_key() else ("QR_CODE" if self._has_cookie() else None),
            "nickname": creds.get("nickname"),
            "headImgUrl": creds.get("headImgUrl"),
            "isVip": bool(creds.get("isVip") or False),
            "lastLoginAt": creds.get("lastLoginAt"),
        }


def _now_iso() -> str:
    from datetime import datetime

    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")
