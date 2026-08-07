import pytest

from weread_mcp import build_mcp
from weread_mcp.config import Settings
from weread_mcp.fake import FakeWereadHttpClient
from weread_mcp.qr_login import QrLoginManager
from weread_mcp.server import (
    handle_book_info,
    handle_book_progress,
    handle_get_bookshelf,
    handle_login_qrcode,
    handle_login_status,
    handle_logout,
    handle_readdata_detail,
    handle_recommend,
    handle_search,
    handle_set_api_key,
    handle_similar,
)
from weread_mcp.storage import CredentialStore


def make_fake(tmp_path):
    store = CredentialStore(tmp_path / "credentials.json")
    client = FakeWereadHttpClient(store)
    qr = QrLoginManager(client)
    return store, client, qr


@pytest.mark.asyncio
async def test_login_status_empty(tmp_path):
    _, client, qr = make_fake(tmp_path)
    status = await handle_login_status(client, qr)
    assert status["loggedIn"] is False
    assert status["loginType"] is None
    assert status["loginQr"] is None


@pytest.mark.asyncio
async def test_qr_login_then_status_success(tmp_path):
    _, client, qr = make_fake(tmp_path)
    qr_view = await handle_login_qrcode(client, qr)
    assert qr_view["status"] == "PENDING"
    assert qr_view["qrBase64"].startswith("data:image/png;base64,")

    status = await handle_login_status(client, qr)
    assert status["loggedIn"] is False
    assert status["loginQr"]["status"] in ("PENDING", "SCANNED")

    status = await handle_login_status(client, qr)  # fake 第二次轮询自动 SUCCESS
    assert status["loggedIn"] is True
    assert status["loginType"] == "QR_CODE"
    assert status["nickname"] == "示例读者"


@pytest.mark.asyncio
async def test_set_api_key_then_bookshelf(tmp_path):
    store, client, qr = make_fake(tmp_path)
    result = await handle_set_api_key(client, store, "wrk-fake-key")
    assert result["loggedIn"] is True
    assert result["loginType"] == "API_KEY"

    shelf = await handle_get_bookshelf(client)
    assert shelf["total"] == 4
    assert shelf["books"][0]["title"]
    assert shelf["books"][0]["bookId"]

    await handle_logout(store, qr)
    assert (await handle_login_status(client, qr))["loggedIn"] is False


@pytest.mark.asyncio
async def test_set_api_key_invalid(tmp_path):
    store, client, qr = make_fake(tmp_path)
    result = await handle_set_api_key(client, store, "not-a-key")
    assert result["isError"] is True
    assert result["code"] == "WEREAD_API_KEY_INVALID"


@pytest.mark.asyncio
async def test_search(tmp_path):
    _, client, qr = make_fake(tmp_path)
    await handle_set_api_key(client, CredentialStore(tmp_path / "credentials.json"), "wrk-fake-key")
    result = await handle_search(client, "Java", 5)
    assert result["books"]
    assert all("java" in b["title"].lower() for b in result["books"])


@pytest.mark.asyncio
async def test_extended_gateway_tools(tmp_path):
    store, client, qr = make_fake(tmp_path)
    await handle_set_api_key(client, store, "wrk-fake-key")

    info = await handle_book_info(client, "fake-001")
    assert info["bookId"] == "fake-001"
    assert info["title"]

    prog = await handle_book_progress(client, "fake-001")
    assert prog["progressPercent"] == 36

    rec = await handle_recommend(client, 6)
    assert rec["books"]

    sim = await handle_similar(client, "fake-101", 6)
    assert sim["books"]

    stat = await handle_readdata_detail(client, "overall")
    assert stat["totalReadTime"] > 0
    assert stat["medals"]


@pytest.mark.asyncio
async def test_extended_tools_require_api_key(tmp_path):
    _, client, qr = make_fake(tmp_path)
    result = await handle_book_info(client, "fake-001")
    assert result["isError"] is True
    assert result["code"] == "WEREAD_NOT_LOGGED_IN"


@pytest.mark.asyncio
async def test_build_mcp_registers_tools(tmp_path):
    settings = Settings(fake=True, credentials_path=tmp_path / "credentials.json")
    mcp = build_mcp(settings)
    tools = await mcp.list_tools()
    names = {tool.name for tool in tools}
    assert {
        "login_qrcode", "login_status", "set_api_key", "logout",
        "get_bookshelf", "search_books",
        "book_info", "book_progress", "recommend_books", "similar_books", "readdata_detail",
    } <= names
