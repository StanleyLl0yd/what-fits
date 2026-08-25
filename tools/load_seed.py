from pathlib import Path
import json
import os
import re
import psycopg

ROOT = Path(__file__).resolve().parents[1]
SEED = ROOT / "data/seed_ru_printers_v0.1.jsonl"
DATABASE_URL = os.getenv("DATABASE_URL", "postgresql://whatfits:whatfits@localhost:5432/whatfits")


def norm(s: str) -> str:
    s = s.casefold().replace("ё", "е")
    return re.sub(r"[^a-zа-я0-9]+", "", s)


def one(cur, sql, params):
    cur.execute(sql, params)
    return cur.fetchone()[0]


def main():
    with psycopg.connect(DATABASE_URL) as conn:
        with conn.cursor() as cur:
            for raw in SEED.read_text(encoding="utf-8").splitlines():
                if not raw.strip():
                    continue
                item = json.loads(raw)
                brand_id = one(cur, """
                    INSERT INTO brands(name, normalized_name, website)
                    VALUES (%s,%s,%s)
                    ON CONFLICT (name) DO UPDATE SET name=EXCLUDED.name
                    RETURNING id
                """, (item["brand"], norm(item["brand"]), "https://www.pantum.ru/" if item["brand"] == "Pantum" else None))

                search_text = norm(" ".join([item["brand"], item["canonical_name"], item["model_code"], *item.get("aliases", [])]))
                device_id = one(cur, """
                    INSERT INTO device_models(brand_id, category_code, canonical_name, model_code, family, search_text, lifecycle_status)
                    VALUES (%s,%s,%s,%s,%s,%s,%s)
                    ON CONFLICT (brand_id, model_code) DO UPDATE SET
                      canonical_name=EXCLUDED.canonical_name,
                      family=EXCLUDED.family,
                      search_text=EXCLUDED.search_text,
                      lifecycle_status=EXCLUDED.lifecycle_status,
                      updated_at=now()
                    RETURNING id
                """, (brand_id, item["category"], item["canonical_name"], item["model_code"], item.get("family"), search_text, item.get("lifecycle_status", "unknown")))

                identifiers = [("model", item["model_code"])] + [("alias", a) for a in item.get("aliases", [])]
                for itype, value in identifiers:
                    cur.execute("""
                        INSERT INTO device_identifiers(device_model_id, identifier_type, identifier_value, normalized_value)
                        VALUES (%s,%s,%s,%s)
                        ON CONFLICT DO NOTHING
                    """, (device_id, itype, value, norm(value)))

                for repl in item["replacements"]:
                    part_id = one(cur, """
                        INSERT INTO parts(brand_id, canonical_name, part_number, part_kind, color, yield_pages, search_text)
                        VALUES (%s,%s,%s,%s,%s,%s,%s)
                        ON CONFLICT (brand_id, part_number) DO UPDATE SET
                          canonical_name=EXCLUDED.canonical_name,
                          part_kind=EXCLUDED.part_kind,
                          color=EXCLUDED.color,
                          yield_pages=EXCLUDED.yield_pages,
                          search_text=EXCLUDED.search_text,
                          updated_at=now()
                        RETURNING id
                    """, (brand_id, repl["canonical_name"], repl["part_number"], repl["type"], repl.get("color"), repl.get("yield_pages"), norm(repl["canonical_name"] + " " + repl["part_number"])))
                    cur.execute("""
                        INSERT INTO part_identifiers(part_id, identifier_type, identifier_value, normalized_value)
                        VALUES (%s,'part_number',%s,%s) ON CONFLICT DO NOTHING
                    """, (part_id, repl["part_number"], norm(repl["part_number"])))

                    source = repl["source"]
                    source_id = one(cur, """
                        INSERT INTO source_documents(source_type,publisher,title,url,market_code,checked_at,notes)
                        VALUES (%s,%s,%s,%s,%s,%s,%s)
                        ON CONFLICT (url) DO UPDATE SET checked_at=EXCLUDED.checked_at, title=EXCLUDED.title
                        RETURNING id
                    """, (source["type"], source["publisher"], source["title"], source["url"], source["market"], source["checked_at"], source.get("note")))

                    fit_id = one(cur, """
                        INSERT INTO compatibility_edges(device_model_id,replacement_type,part_id,market_code,status,confidence,conditions)
                        VALUES (%s,%s,%s,%s,%s,%s,%s::jsonb)
                        ON CONFLICT (device_model_id,replacement_type,part_id,market_code) DO UPDATE SET
                           status=EXCLUDED.status, confidence=EXCLUDED.confidence, conditions=EXCLUDED.conditions, updated_at=now()
                        RETURNING id
                    """, (device_id, repl["type"], part_id, item["market"], repl["status"], repl.get("confidence", 1.0), json.dumps(repl.get("conditions", {}), ensure_ascii=False)))

                    cur.execute("""
                        INSERT INTO compatibility_evidence(compatibility_id,source_document_id,evidence_note,verified_at)
                        VALUES (%s,%s,%s,%s)
                        ON CONFLICT (compatibility_id,source_document_id) DO UPDATE SET verified_at=EXCLUDED.verified_at, evidence_note=EXCLUDED.evidence_note
                    """, (fit_id, source_id, source.get("note"), source["checked_at"]))
        conn.commit()
    print("Seed loaded")

if __name__ == "__main__":
    main()
