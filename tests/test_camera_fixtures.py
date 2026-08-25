import json
from pathlib import Path


FIXTURES = Path(__file__).resolve().parent / "fixtures" / "camera"
MANIFEST = FIXTURES / "manifest.json"
ALLOWED_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp"}
ALLOWED_STATUSES = {"EXACT", "AMBIGUOUS", "NOT_FOUND", "OCR_ERROR"}
MAX_PHOTO_BYTES = 10 * 1024 * 1024


def test_camera_fixture_manifest_contract():
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))

    assert manifest["version"] == 1
    ids = [case["id"] for case in manifest["cases"]]
    assert len(ids) == len(set(ids))

    for case in manifest["cases"]:
        fixture = FIXTURES / case["file"]
        assert fixture.is_file(), case["id"]
        assert fixture.suffix.casefold() in ALLOWED_SUFFIXES
        assert fixture.stat().st_size <= MAX_PHOTO_BYTES
        assert case["expected_status"] in ALLOWED_STATUSES
        if case["expected_status"] == "EXACT":
            assert case["expected_model_code"].strip()

