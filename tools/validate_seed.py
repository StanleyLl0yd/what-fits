from pathlib import Path
import json
import sys

try:
    import jsonschema
except ImportError:
    print("Install validator dependency: pip install jsonschema")
    sys.exit(2)

root = Path(__file__).resolve().parents[1]
schema = json.loads((root / "data/seed_format.schema.json").read_text(encoding="utf-8"))
seed_path = root / "data/seed_ru_printers_v0.1.jsonl"
validator = jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())
errors = []
count = 0
for lineno, line in enumerate(seed_path.read_text(encoding="utf-8").splitlines(), 1):
    if not line.strip():
        continue
    count += 1
    obj = json.loads(line)
    for err in validator.iter_errors(obj):
        errors.append((lineno, err.message))

if errors:
    for line, msg in errors:
        print(f"line {line}: {msg}")
    sys.exit(1)
print(f"OK: {count} device records validated")
