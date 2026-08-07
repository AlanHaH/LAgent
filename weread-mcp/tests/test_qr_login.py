import pytest

from weread_mcp.qr_login import QrLoginManager


class StubClient:
    def __init__(self, poll_results, on_persist=None):
        self._polls = iter(poll_results)
        self.persisted = None
        self._on_persist = on_persist

    async def qr_create(self):
        return {"qrText": "https://weread.qq.com/x", "qrToken": "t1", "expiresIn": 180}

    async def qr_poll(self, qr_token):
        return next(self._polls)

    async def persist_qr_login(self, user, cookie):
        self.persisted = (user, cookie)


@pytest.mark.asyncio
async def test_qr_state_machine_to_success():
    client = StubClient(
        [
            {"status": "PENDING"},
            {"status": "SCANNED"},
            {"status": "SUCCESS", "user": {"nickname": "张三"}, "cookie": {"wr_vid": "1"}},
        ]
    )
    manager = QrLoginManager(client)

    view = await manager.create()
    assert view["status"] == "PENDING"
    assert view["qrBase64"].startswith("data:image/png;base64,")
    assert manager.active

    assert (await manager.poll())["status"] == "PENDING"
    assert (await manager.poll())["status"] == "SCANNED"
    assert (await manager.poll())["status"] == "SUCCESS"
    assert client.persisted is not None
    assert client.persisted[0]["nickname"] == "张三"
    assert not manager.active  # 成功后会话已清空
    assert await manager.poll() is None


@pytest.mark.asyncio
async def test_qr_expired_clears_session():
    client = StubClient([{"status": "PENDING"}])
    manager = QrLoginManager(client)
    await manager.create()
    manager._session.expires_at = 0  # 强制过期
    view = await manager.poll()
    assert view["status"] == "EXPIRED"
    assert not manager.active
    assert client.persisted is None


@pytest.mark.asyncio
async def test_qr_create_returns_data_url():
    client = StubClient([])
    manager = QrLoginManager(client)
    view = await manager.create()
    assert view["qrBase64"].startswith("data:image/png;base64,")
    assert view["expiresAt"]
