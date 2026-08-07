import httpx
import pytest

from weread_mcp.storage import CredentialStore
from weread_mcp.weread_client import WereadError, WereadHttpClient


def make_client(handler, creds=None, tmp_path=None):
    store = CredentialStore(tmp_path / "credentials.json")
    if creds:
        store.persist(creds)
    return store, WereadHttpClient(store, timeout=5.0, transport=httpx.MockTransport(handler))


@pytest.mark.asyncio
async def test_gateway_sends_bearer_and_flat_body(tmp_path):
    seen = {}

    def handler(request):
        seen["url"] = str(request.url)
        seen["auth"] = request.headers.get("Authorization")
        seen["body"] = request.read()
        return httpx.Response(200, json={"errcode": 0, "data": {"books": []}})

    _, client = make_client(handler, {"apiKey": "wrk-test"}, tmp_path)
    await client.gateway("get_bookshelf", {"keyword": "k"})
    assert seen["url"] == "https://i.weread.qq.com/api/agent/gateway"
    assert seen["auth"] == "Bearer wrk-test"
    import json

    body = json.loads(seen["body"])
    assert body["api_name"] == "get_bookshelf"
    assert body["keyword"] == "k"
    assert "skill_version" in body
    await client.aclose()


@pytest.mark.asyncio
async def test_gateway_401_maps_to_login_expired(tmp_path):
    def handler(request):
        return httpx.Response(401, json={})

    _, client = make_client(handler, {"apiKey": "wrk-test"}, tmp_path)
    with pytest.raises(WereadError) as exc:
        await client.gateway("get_bookshelf")
    assert exc.value.code == "WEREAD_LOGIN_EXPIRED"
    assert exc.value.status_code == 401
    await client.aclose()


@pytest.mark.asyncio
async def test_gateway_not_logged_in(tmp_path):
    _, client = make_client(lambda request: httpx.Response(200, json={}), None, tmp_path)
    with pytest.raises(WereadError) as exc:
        await client.gateway("get_bookshelf")
    assert exc.value.code == "WEREAD_NOT_LOGGED_IN"
    assert exc.value.status_code == 401
    await client.aclose()


@pytest.mark.asyncio
async def test_verify_api_key_invalid(tmp_path):
    def handler(request):
        return httpx.Response(403, json={})

    _, client = make_client(handler, None, tmp_path)
    with pytest.raises(WereadError) as exc:
        await client.verify_api_key("wrk-bad")
    assert exc.value.code == "WEREAD_API_KEY_INVALID"
    await client.aclose()


@pytest.mark.asyncio
async def test_shelf_cookie_mode_normalizes_book(tmp_path):
    def handler(request):
        assert "weread.qq.com/web/shelf/sync" in str(request.url)
        return httpx.Response(
            200,
            json={
                "books": [
                    {
                        "bookId": 123,
                        "title": "深入理解Java虚拟机",
                        "author": "周志明",
                        "readingProgress": 0.36,
                        "category": "计算机",
                        "categoryId": 18,
                        "deepLink": "https://weread.qq.com/book-detail?type=1&v=test",
                    }
                ]
            },
        )

    _, client = make_client(handler, {"cookie": {"wr_vid": "1"}}, tmp_path)
    books = await client.shelf_sync()
    assert books[0]["bookId"] == "123"
    assert books[0]["title"] == "深入理解Java虚拟机"
    assert books[0]["readingProgress"] == 0.36
    assert books[0]["status"] == "reading"
    assert books[0]["categoryId"] == "18"
    assert books[0]["deepLink"] == "https://weread.qq.com/book-detail?type=1&v=test"
    await client.aclose()


@pytest.mark.asyncio
async def test_shelf_cookie_expired(tmp_path):
    def handler(request):
        return httpx.Response(401, json={})

    _, client = make_client(handler, {"cookie": {"wr_vid": "1"}}, tmp_path)
    with pytest.raises(WereadError) as exc:
        await client.shelf_sync()
    assert exc.value.code == "WEREAD_LOGIN_EXPIRED"
    await client.aclose()


@pytest.mark.asyncio
async def test_shelf_finished_book(tmp_path):
    def handler(request):
        return httpx.Response(
            200,
            json={
                "books": [
                    {"bookId": 1, "title": "认知觉醒", "readingStatus": "finished", "readingProgress": 1.0}
                ]
            },
        )

    _, client = make_client(handler, {"cookie": {"wr_vid": "1"}}, tmp_path)
    books = await client.shelf_sync()
    assert books[0]["isFinished"] is True
    assert books[0]["status"] == "finished"
    await client.aclose()


@pytest.mark.asyncio
async def test_book_info_normalizes(tmp_path):
    def handler(request):
        assert request.headers["authorization"] == "Bearer wrk-test"
        return httpx.Response(
            200,
            json={
                "errcode": 0,
                "data": {
                    "bookId": "3300154286",
                    "deepLink": "https://weread.qq.com/book-detail?type=1&v=3300154286",
                    "title": "被毒虫男友拖下水的女大学生（轻纪实）",
                    "author": "深蓝",
                    "cover": "https://cdn.weread.qq.com/c.jpg",
                    "category": "文学-纪实文学",
                    "intro": "她曾是名校优等生……",
                    "publisher": "出版社",
                    "publishTime": "2023-01-01 00:00:00",
                    "isbn": "9787111",
                    "wordCount": 10000,
                    "newRating": 9.2,
                    "newRatingCount": 123,
                    "newRatingDetail": {
                        "good": 90, "fair": 8, "poor": 2, "recent": 95, "deepV": 88, "myRating": "",
                    },
                },
            },
        )

    _, client = make_client(handler, {"apiKey": "wrk-test"}, tmp_path)
    info = await client.book_info("3300154286")
    assert info["bookId"] == "3300154286"
    assert info["title"].startswith("被毒虫")
    assert info["newRating"] == 9.2
    assert info["ratingDetail"]["good"] == 90
    assert info["deepLink"].startswith("https://weread.qq.com/book-detail")
    await client.aclose()


@pytest.mark.asyncio
async def test_book_progress_normalizes(tmp_path):
    def handler(request):
        return httpx.Response(
            200,
            json={
                "errcode": 0,
                "data": {
                    "bookId": "3300154286",
                    "book": {
                        "chapterUid": 21, "chapterIdx": 2, "progress": 13,
                        "readingTime": 2896, "updateTime": 1785775131,
                    },
                    "timestamp": 1785941021,
                },
            },
        )

    _, client = make_client(handler, {"apiKey": "wrk-test"}, tmp_path)
    prog = await client.book_progress("3300154286")
    assert prog["progressPercent"] == 13
    assert prog["chapterIdx"] == 2
    assert prog["readingTime"] == 2896
    assert prog["updateTime"] == "1785775131"
    assert prog["lastReadAt"] == "1785941021"
    await client.aclose()


@pytest.mark.asyncio
async def test_recommend_normalizes(tmp_path):
    def handler(request):
        return httpx.Response(
            200,
            json={
                "errcode": 0,
                "data": {
                    "books": [
                        {
                            "bookId": 3300154286,
                            "title": "被毒虫男友拖下水的女大学生（轻纪实）",
                            "author": "深蓝",
                            "intro": "本书选自《深蓝的故事3》。",
                            "cover": "https://c.jpg",
                            "category": "文学-纪实文学",
                            "deepLink": "https://weread.qq.com/book-detail?type=1&v=3300154286",
                            "price": 9.9,
                        }
                    ]
                },
            },
        )

    _, client = make_client(handler, {"apiKey": "wrk-test"}, tmp_path)
    result = await client.recommend(6)
    assert result["books"][0]["bookId"] == "3300154286"
    assert result["books"][0]["intro"]
    assert result["books"][0]["price"] == 9.9
    await client.aclose()


@pytest.mark.asyncio
async def test_similar_unwraps_item_book(tmp_path):
    def handler(request):
        return httpx.Response(
            200,
            json={
                "errcode": 0,
                "booksimilar": {
                    "sessionId": "session_abc",
                    "booksHasMore": 1,
                    "books": [
                        {
                            "idx": 1,
                            "book": {
                                "type": 0,
                                "bookInfo": {
                                    "bookId": 3300154286,
                                    "title": "被毒虫男友拖下水的女大学生（轻纪实）",
                                    "author": "深蓝",
                                    "cover": "https://c.jpg",
                                },
                            },
                        }
                    ],
                },
            },
        )

    _, client = make_client(handler, {"apiKey": "wrk-test"}, tmp_path)
    result = await client.similar("3300154286", 6)
    assert result["books"][0]["bookId"] == "3300154286"
    assert result["books"][0]["title"].startswith("被毒虫")
    # 嵌套 bookInfo 无 deepLink → 按 bookId 合成
    assert result["books"][0]["deepLink"] == "https://weread.qq.com/book-detail?type=1&v=3300154286"
    await client.aclose()


@pytest.mark.asyncio
async def test_readdata_detail_normalizes(tmp_path):
    def handler(request):
        return httpx.Response(
            200,
            json={
                "errcode": 0,
                "data": {
                    "totalReadTime": 1062443,
                    "wrReadTime": 884478,
                    "wrListenTime": 177965,
                    "readDays": 768,
                    "readRate": 83,
                    "registTime": 1545717161,
                    "preferCategoryWord": "偏好阅读心理",
                    "preferTimeWord": "偏好深夜阅读",
                    "preferBooks": [{"type": 13, "title": "我的最爱", "bookId": "x"}],
                    "medals": [
                        {"name": "想法发布", "displayText": "想法发布 10 条", "rankText": "第1位"}
                    ],
                },
            },
        )

    _, client = make_client(handler, {"apiKey": "wrk-test"}, tmp_path)
    stat = await client.readdata_detail("overall")
    assert stat["totalReadTime"] == 1062443
    assert stat["readDays"] == 768
    assert stat["preferCategoryWord"] == "偏好阅读心理"
    assert stat["medals"][0]["name"] == "想法发布"
    await client.aclose()
