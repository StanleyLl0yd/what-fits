import os
import re
from pathlib import Path

from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import FileResponse
import psycopg
from psycopg.rows import dict_row

DATABASE_URL = os.getenv("DATABASE_URL", "postgresql://whatfits:whatfits@localhost:5432/whatfits")
WEB_INDEX = Path(__file__).resolve().parents[1] / "static" / "index.html"

app = FastAPI(title="What Fits? API", version="0.0.5")


def normalize(value: str) -> str:
    value = value.casefold().replace("ё", "е")
    return re.sub(r"[^a-zа-я0-9]+", "", value)


def conn():
    return psycopg.connect(DATABASE_URL, row_factory=dict_row)


@app.get("/", include_in_schema=False)
def web_app():
    if not WEB_INDEX.exists():
        raise HTTPException(500, "web interface not found")
    return FileResponse(WEB_INDEX)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/v1/devices/search")
def search_devices(
    q: str = Query(min_length=2),
    market: str = "RU",
    limit: int = Query(10, ge=1, le=50),
):
    nq = normalize(q)
    with conn() as c, c.cursor() as cur:
        cur.execute(
            """
            SELECT DISTINCT d.id, b.name AS brand, d.canonical_name, d.model_code,
                   GREATEST(similarity(d.search_text, %s), similarity(lower(d.model_code), %s)) AS score
            FROM device_models d
            JOIN brands b ON b.id = d.brand_id
            LEFT JOIN device_identifiers i ON i.device_model_id = d.id
            WHERE d.search_text %% %s
               OR lower(d.model_code) %% %s
               OR i.normalized_value %% %s
               OR i.normalized_value = %s
            ORDER BY score DESC, d.canonical_name
            LIMIT %s
            """,
            (nq, nq, nq, nq, nq, nq, limit),
        )
        return {"query": q, "market": market, "items": cur.fetchall()}


@app.get("/v1/devices/{device_id}/replacements")
def device_replacements(device_id: int, market: str = "RU"):
    with conn() as c, c.cursor() as cur:
        cur.execute(
            """
            SELECT d.id, b.name AS brand, d.canonical_name, d.model_code
            FROM device_models d
            JOIN brands b ON b.id = d.brand_id
            WHERE d.id = %s
            """,
            (device_id,),
        )
        device = cur.fetchone()
        if not device:
            raise HTTPException(404, "device not found")

        cur.execute(
            """
            SELECT ce.id AS fit_id, ce.replacement_type, ce.status, ce.confidence,
                   p.canonical_name, p.part_number, p.color, p.yield_pages,
                   s.publisher AS source_publisher, s.title AS source_title, s.url AS source_url,
                   e.verified_at
            FROM compatibility_edges ce
            JOIN parts p ON p.id = ce.part_id
            LEFT JOIN compatibility_evidence e ON e.compatibility_id = ce.id
            LEFT JOIN source_documents s ON s.id = e.source_document_id
            WHERE ce.device_model_id = %s AND ce.market_code = %s
            ORDER BY ce.replacement_type, p.yield_pages NULLS LAST, p.part_number
            """,
            (device_id, market),
        )
        return {"device": device, "market": market, "fits": cur.fetchall()}


def exact_device_candidates(q: str):
    """
    Find an exact model/alias inside the input string.

    This is intentionally OCR-friendly. For example:
    "PANTUM P2500W 220-240V 50Hz" -> Pantum P2500W.

    If both a shorter and a longer identifier are present (for example
    P2500 and P2500W), the longest identifier wins.
    """
    nq = normalize(q)

    with conn() as c, c.cursor() as cur:
        cur.execute(
            """
            SELECT
                d.id,
                b.name AS brand,
                d.canonical_name,
                d.model_code,
                MAX(
                    CASE
                        WHEN i.normalized_value = %s THEN 100000
                        ELSE length(i.normalized_value)
                    END
                ) AS match_rank
            FROM device_models d
            JOIN brands b ON b.id = d.brand_id
            JOIN device_identifiers i ON i.device_model_id = d.id
            WHERE
                i.normalized_value = %s
                OR (
                    length(i.normalized_value) >= 4
                    AND %s LIKE '%%' || i.normalized_value || '%%'
                )
            GROUP BY d.id, b.name, d.canonical_name, d.model_code
            ORDER BY match_rank DESC, d.canonical_name
            LIMIT 10
            """,
            (nq, nq, nq),
        )
        rows = cur.fetchall()

    if not rows:
        return []

    best_rank = rows[0]["match_rank"]
    result = []

    for row in rows:
        if row["match_rank"] != best_rank:
            break
        item = dict(row)
        item.pop("match_rank", None)
        result.append(item)

    return result


@app.get("/v1/fit")
def fit(
    q: str = Query(min_length=2),
    market: str = "RU",
):
    """
    Main What Fits endpoint.

    EXACT:
        One device was resolved unambiguously and its compatible
        replacements are returned.

    AMBIGUOUS:
        Several possible devices were found. The client must ask the user
        to confirm the exact model instead of guessing.

    NOT_FOUND:
        No sufficiently plausible device was found.
    """

    # Prefer exact model/alias matches, including identifiers embedded in OCR text.
    exact = exact_device_candidates(q)

    if len(exact) == 1:
        device = exact[0]
        replacements = device_replacements(device_id=device["id"], market=market)
        return {
            "status": "EXACT",
            "query": q,
            "market": market,
            "device": device,
            "fits": replacements["fits"],
        }

    if len(exact) > 1:
        return {
            "status": "AMBIGUOUS",
            "query": q,
            "market": market,
            "candidates": exact,
        }

    # Fallback to fuzzy search. Fuzzy matching is never allowed to silently
    # select a device: the user must confirm the model.
    result = search_devices(q=q, market=market, limit=8)
    candidates = result["items"]

    if not candidates or candidates[0]["score"] < 0.35:
        return {
            "status": "NOT_FOUND",
            "query": q,
            "market": market,
            "candidates": [],
        }

    return {
        "status": "AMBIGUOUS",
        "query": q,
        "market": market,
        "candidates": candidates[:5],
    }
