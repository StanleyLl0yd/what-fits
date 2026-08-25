from io import BytesIO

from fastapi.testclient import TestClient
from PIL import Image

from backend.app import main as api


client = TestClient(api.app)


def png_bytes() -> bytes:
    output = BytesIO()
    Image.new("RGB", (1000, 400), "white").save(output, format="PNG")
    return output.getvalue()


def test_ocr_resolves_through_fit_without_returning_raw_text(monkeypatch):
    seen = {}

    def fake_image_to_string(image, *, lang, config, timeout):
        seen.update(mode=image.mode, size=image.size, lang=lang, config=config, timeout=timeout)
        return "SERIAL 01827364\nPANTUM P2500W\n220-240V"

    def fake_fit(q, market):
        assert "P2500W" in q
        assert market == "RU"
        return {
            "status": "EXACT",
            "query": q,
            "market": market,
            "device": {
                "id": 13,
                "brand": "Pantum",
                "canonical_name": "Pantum P2500W",
                "model_code": "P2500W",
            },
            "fits": [{"part_number": "PC-211P", "status": "VERIFIED"}],
        }

    monkeypatch.setattr(api.pytesseract, "image_to_string", fake_image_to_string)
    monkeypatch.setattr(api, "fit", fake_fit)

    response = client.post(
        "/v1/ocr?market=RU",
        content=png_bytes(),
        headers={"Content-Type": "image/png"},
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["status"] == "EXACT"
    assert payload["device"]["model_code"] == "P2500W"
    assert payload["input"] == "OCR"
    assert "query" not in payload
    assert "01827364" not in response.text
    assert seen == {
        "mode": "L",
        "size": (1200, 480),
        "lang": "eng",
        "config": "--oem 3 --psm 11",
        "timeout": 8,
    }


def test_ocr_returns_not_found_when_no_text_is_recognized(monkeypatch):
    monkeypatch.setattr(api, "extract_ocr_text", lambda content: "")

    response = client.post(
        "/v1/ocr?market=RU",
        content=png_bytes(),
        headers={"Content-Type": "image/png"},
    )

    assert response.status_code == 200
    assert response.json() == {
        "status": "NOT_FOUND",
        "market": "RU",
        "candidates": [],
        "input": "OCR",
    }


def test_ocr_rejects_unsupported_media_type():
    response = client.post(
        "/v1/ocr?market=RU",
        content=b"P2500W",
        headers={"Content-Type": "text/plain"},
    )

    assert response.status_code == 415


def test_ocr_rejects_corrupt_image():
    response = client.post(
        "/v1/ocr?market=RU",
        content=b"not a real image",
        headers={"Content-Type": "image/png"},
    )

    assert response.status_code == 415


def test_ocr_rejects_file_above_ten_megabytes():
    response = client.post(
        "/v1/ocr?market=RU",
        content=b"x" * (api.MAX_PHOTO_BYTES + 1),
        headers={"Content-Type": "image/jpeg"},
    )

    assert response.status_code == 413
