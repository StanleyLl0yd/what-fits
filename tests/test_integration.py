import json
from io import BytesIO
import os
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image, ImageDraw, ImageFont

from backend.app import main as api


pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        os.getenv("RUN_DB_TESTS") != "1",
        reason="set RUN_DB_TESTS=1 for PostgreSQL integration tests",
    ),
]

ROOT = Path(__file__).resolve().parents[1]
SEED = ROOT / "data" / "seed_ru_printers_v0.1.jsonl"
client = TestClient(api.app)


def load_records() -> list[dict]:
    return [
        json.loads(line)
        for line in SEED.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def test_every_seed_model_resolves_exactly_with_expected_parts():
    for item in load_records():
        response = client.get(
            "/v1/fit",
            params={"q": item["model_code"], "market": item["market"]},
        )

        assert response.status_code == 200, item["model_code"]
        payload = response.json()
        assert payload["status"] == "EXACT", item["model_code"]
        assert payload["device"]["canonical_name"] == item["canonical_name"]
        assert {fit["part_number"] for fit in payload["fits"]} == {
            replacement["part_number"] for replacement in item["replacements"]
        }


def test_verified_api_fits_keep_their_sources():
    for item in load_records():
        payload = client.get(
            "/v1/fit",
            params={"q": item["model_code"], "market": item["market"]},
        ).json()

        for fit in payload["fits"]:
            if fit["status"] != "VERIFIED":
                continue
            assert fit["source_publisher"]
            assert fit["source_title"]
            assert fit["source_url"].startswith("https://")
            assert fit["verified_at"]


def test_ocr_like_label_prefers_longest_exact_identifier():
    response = client.get(
        "/v1/fit",
        params={"q": "PANTUM P2500W 220-240V 50Hz", "market": "RU"},
    )

    assert response.status_code == 200
    assert response.json()["status"] == "EXACT"
    assert response.json()["device"]["model_code"] == "P2500W"


def test_unknown_model_is_not_guessed():
    response = client.get("/v1/fit", params={"q": "ZXQ999", "market": "RU"})

    assert response.status_code == 200
    assert response.json()["status"] == "NOT_FOUND"


def test_label_with_two_equal_exact_models_is_ambiguous():
    response = client.get(
        "/v1/fit",
        params={"q": "P2500W P2502W", "market": "RU"},
    )

    assert response.status_code == 200
    assert response.json()["status"] == "AMBIGUOUS"
    assert {item["model_code"] for item in response.json()["candidates"]} == {
        "P2500W",
        "P2502W",
    }


def test_short_fuzzy_query_is_never_exact():
    response = client.get("/v1/fit", params={"q": "P25", "market": "RU"})

    assert response.status_code == 200
    assert response.json()["status"] in {"AMBIGUOUS", "NOT_FOUND"}


@pytest.mark.skipif(
    os.getenv("RUN_OCR_TESTS") != "1",
    reason="set RUN_OCR_TESTS=1 and install Tesseract for real OCR tests",
)
def test_generated_label_resolves_end_to_end_through_real_ocr():
    image = Image.new("RGB", (1400, 420), "white")
    font = ImageFont.truetype(
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        130,
    )
    ImageDraw.Draw(image).text((45, 110), "PANTUM P2500W", fill="black", font=font)
    output = BytesIO()
    image.save(output, format="PNG")

    response = client.post(
        "/v1/ocr?market=RU",
        content=output.getvalue(),
        headers={"Content-Type": "image/png"},
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["status"] == "EXACT"
    assert payload["device"]["model_code"] == "P2500W"
    assert payload["input"] == "OCR"
    assert "query" not in payload
