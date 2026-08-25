import os
import re
from fastapi import FastAPI, HTTPException, Query
import psycopg
from psycopg.rows import dict_row

DATABASE_URL = os.getenv("DATABASE_URL", "postgresql://whatfits:whatfits@localhost:5432/whatfits")
app = FastAPI(title="What Fits? API", version="0.0.1")


def normalize(value: str) -> str:
    value = value.casefold().replace("ё", "е")
    return re.sub(r"[^a-zа-я0-9]+", "", value)


def conn():
    return psycopg.connect(DATABASE_URL, row_factory=dict_row)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/v1/devices/search")
def search_devices(q: str = Query(min_length=2), market: str = "RU", limit: int = Query(10, ge=1, le=50)):
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
            FROM device_models d JOIN brands b ON b.id=d.brand_id
            WHERE d.id=%s
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
            JOIN parts p ON p.id=ce.part_id
            LEFT JOIN compatibility_evidence e ON e.compatibility_id=ce.id
            LEFT JOIN source_documents s ON s.id=e.source_document_id
            WHERE ce.device_model_id=%s AND ce.market_code=%s
            ORDER BY ce.replacement_type, p.yield_pages NULLS LAST, p.part_number
            """,
            (device_id, market),
        )
        return {"device": device, "market": market, "fits": cur.fetchall()}
