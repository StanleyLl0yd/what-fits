from pathlib import Path
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SEED = ROOT / "data/seed_ru_printers_v0.1.jsonl"


def norm(s: str) -> str:
    s = s.casefold().replace("ё", "е")
    return re.sub(r"[^a-zа-я0-9]+", "", s)


def load():
    return [json.loads(x) for x in SEED.read_text(encoding="utf-8").splitlines() if x.strip()]


def score(q: str, item: dict) -> int:
    nq = norm(q)
    candidates = [item["model_code"], item["canonical_name"], *item.get("aliases", [])]
    vals = [norm(x) for x in candidates]
    if nq in vals:
        return 100
    if any(nq in v for v in vals):
        return 80
    if any(v in nq for v in vals):
        return 60
    # Very small typo-tolerant fallback for the zero-dependency demo.
    qset = set(nq[i:i+2] for i in range(max(0, len(nq)-1)))
    best = 0
    for v in vals:
        vset = set(v[i:i+2] for i in range(max(0, len(v)-1)))
        if qset and vset:
            best = max(best, int(50 * len(qset & vset) / len(qset | vset)))
    return best


def main():
    q = " ".join(sys.argv[1:]).strip() or "P2500W"
    items = sorted(((score(q, x), x) for x in load()), key=lambda z: (-z[0], z[1]["canonical_name"]))
    items = [x for s, x in items if s >= 30][:5]
    if not items:
        print("Ничего не найдено")
        raise SystemExit(1)
    for idx, item in enumerate(items, 1):
        print(f"{idx}. {item['canonical_name']} [{item['market']}]")
        for r in item["replacements"]:
            print(f"   - {r['type']}: {r['part_number']} — {r['status']} — {r.get('yield_pages') or '?'} стр.")
            print(f"     source: {r['source']['url']}")

if __name__ == "__main__":
    main()
