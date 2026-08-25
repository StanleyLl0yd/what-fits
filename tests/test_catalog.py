import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SEED = ROOT / "data" / "seed_ru_printers_v0.1.jsonl"
AUTHORITATIVE_VERIFIED_SOURCES = {
    "manufacturer_official",
    "manufacturer_manual",
    "licensed_data",
}


def load_records() -> list[dict]:
    return [
        json.loads(line)
        for line in SEED.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def test_catalog_keeps_current_coverage_baseline():
    records = load_records()

    assert len(records) >= 50


def test_device_keys_are_unique():
    records = load_records()
    keys = [(item["brand"], item["model_code"], item["market"]) for item in records]

    assert len(keys) == len(set(keys))


def test_verified_edges_have_authoritative_evidence():
    for item in load_records():
        assert item["replacements"], item["canonical_name"]

        for replacement in item["replacements"]:
            if replacement["status"] != "VERIFIED":
                continue

            source = replacement["source"]
            assert source["type"] in AUTHORITATIVE_VERIFIED_SOURCES
            assert source["publisher"].strip()
            assert source["title"].strip()
            assert source["url"].startswith("https://")
            assert source["market"] == item["market"]
            assert source["checked_at"]

