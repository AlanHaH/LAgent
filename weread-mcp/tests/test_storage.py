from weread_mcp.storage import CredentialStore


def test_roundtrip(tmp_path):
    store = CredentialStore(tmp_path / "credentials.json")
    assert store.load() == {}
    store.persist({"loginType": "API_KEY", "apiKey": "wrk-x", "nickname": "张三"})
    data = store.load()
    assert data["loginType"] == "API_KEY"
    assert data["apiKey"] == "wrk-x"
    assert store._path.exists()
    assert not store._path.with_suffix(".tmp").exists()  # 原子写后无残留临时文件


def test_clear(tmp_path):
    store = CredentialStore(tmp_path / "credentials.json")
    store.persist({"apiKey": "wrk-x"})
    store.clear()
    assert store.load() == {}
    assert not store._path.exists()


def test_load_corrupt_file(tmp_path):
    path = tmp_path / "credentials.json"
    path.write_text("{broken json", encoding="utf-8")
    assert CredentialStore(path).load() == {}
