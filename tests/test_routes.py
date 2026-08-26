from fastapi.testclient import TestClient

from backend.app import main as api


client = TestClient(api.app)


def device(device_id: int, model_code: str) -> dict:
    return {
        "id": device_id,
        "brand": "Pantum",
        "canonical_name": f"Pantum {model_code}",
        "model_code": model_code,
    }


def test_health():
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_root_serves_current_browser_version():
    response = client.get("/")

    assert response.status_code == 200
    assert "What Fits? v0.0.8" in response.text


def test_fit_returns_exact_device_and_fits(monkeypatch):
    resolved = device(13, "P2500W")
    fits = [{"part_number": "PC-211P", "status": "VERIFIED"}]
    monkeypatch.setattr(api, "exact_device_candidates", lambda query: [resolved])
    monkeypatch.setattr(
        api,
        "device_replacements",
        lambda device_id, market: {"device": resolved, "market": market, "fits": fits},
    )

    response = client.get("/v1/fit", params={"q": "P2500W", "market": "RU"})

    assert response.status_code == 200
    assert response.json() == {
        "status": "EXACT",
        "query": "P2500W",
        "market": "RU",
        "device": resolved,
        "fits": fits,
    }


def test_fit_never_guesses_between_exact_candidates(monkeypatch):
    candidates = [device(1, "P2500W"), device(2, "P2502W")]
    monkeypatch.setattr(api, "exact_device_candidates", lambda query: candidates)

    response = client.get(
        "/v1/fit",
        params={"q": "P2500W P2502W", "market": "RU"},
    )

    assert response.status_code == 200
    assert response.json()["status"] == "AMBIGUOUS"
    assert response.json()["candidates"] == candidates


def test_fit_never_promotes_fuzzy_candidate_to_exact(monkeypatch):
    candidates = [{**device(1, "P2500W"), "score": 0.8}]
    monkeypatch.setattr(api, "exact_device_candidates", lambda query: [])
    monkeypatch.setattr(
        api,
        "search_devices",
        lambda q, market, limit: {"query": q, "market": market, "items": candidates},
    )

    response = client.get("/v1/fit", params={"q": "P250", "market": "RU"})

    assert response.status_code == 200
    assert response.json()["status"] == "AMBIGUOUS"
    assert response.json()["candidates"] == candidates


def test_fit_returns_not_found_below_fuzzy_threshold(monkeypatch):
    candidates = [{**device(1, "P2500W"), "score": 0.2}]
    monkeypatch.setattr(api, "exact_device_candidates", lambda query: [])
    monkeypatch.setattr(
        api,
        "search_devices",
        lambda q, market, limit: {"query": q, "market": market, "items": candidates},
    )

    response = client.get("/v1/fit", params={"q": "ZXQ999", "market": "RU"})

    assert response.status_code == 200
    assert response.json() == {
        "status": "NOT_FOUND",
        "query": "ZXQ999",
        "market": "RU",
        "candidates": [],
    }


def test_fit_rejects_too_short_query():
    response = client.get("/v1/fit", params={"q": "P", "market": "RU"})

    assert response.status_code == 422


def test_normalize_matches_model_identifiers_across_separators():
    assert api.normalize("  P-2500 W ") == "p2500w"
