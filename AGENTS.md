# AGENTS.md

This file applies to the entire repository. It is the operating guide for coding agents working on What Fits?.

## Product goal

What Fits? answers one high-stakes compatibility question for the Russian market:

```text
device model -> compatible replacement -> supporting source
```

The current prototype covers printers and MFPs. Its catalog contains 50 Pantum models for market `RU`; the running API and browser UI are version `0.0.3`.

Accuracy is more important than recall. A missing result is acceptable; a confidently wrong cartridge is not.

## Non-negotiable product rules

- Never infer a compatible part from a similar model name alone.
- Never silently turn a fuzzy match into an exact device match. Ask the user to choose among candidates.
- OCR is only an input method. OCR text must pass through the same device resolution and `/v1/fit` compatibility logic as typed text.
- Keep market-specific compatibility explicit. Do not copy EU, US, CN, or generic catalog data into `RU` without evidence for the Russian version.
- Show “verified” or the equivalent Russian wording only for a compatibility edge whose stored status and evidence justify it.
- Every compatibility claim must retain its source, publisher, market, and verification date.
- Marketplace listings, reviews, search snippets, and AI-generated answers are discovery aids, not sufficient evidence for `VERIFIED`.
- If evidence is ambiguous or conflicts, use a weaker status such as `UNDER_REVIEW` or omit the edge. Do not guess.

## Repository map

- `backend/app/main.py`: FastAPI application, device matching, compatibility queries, and the root page handler.
- `backend/static/index.html`: dependency-free, same-origin browser interface. It calls `/v1/fit`.
- `backend/requirements.txt`: pinned API dependencies.
- `db/schema.sql`: PostgreSQL schema and reference data used when a new database volume is initialized.
- `data/seed_format.schema.json`: JSON Schema for seed records.
- `data/seed_ru_printers_v0.1.jsonl`: one exact device model per line, including evidence-backed replacement edges.
- `docs/DATA_NOTES.md`: provenance and market caveats for the catalog.
- `tools/validate_seed.py`: validates every JSONL record against the seed schema.
- `tools/load_seed.py`: idempotently loads or updates seed records in PostgreSQL.
- `tools/demo_search.py`: zero-database catalog search demo; it is not the production matching algorithm.
- `docker-compose.yml`: local PostgreSQL 16 and Python 3.12 API services.
- `docs/index.html`: separate minimal GitHub Pages project page; it is not the FastAPI UI.

## Current API contract

- `GET /` serves `backend/static/index.html`.
- `GET /health` returns `{"status":"ok"}`.
- `GET /v1/devices/search?q=...&market=RU` returns fuzzy candidates. This endpoint must not imply confirmed compatibility.
- `GET /v1/devices/{id}/replacements?market=RU` returns the stored parts and evidence for one device.
- `GET /v1/fit?q=...&market=RU` is the main product endpoint and returns exactly one of:
  - `EXACT`: one exact model or alias was resolved, with its `fits`.
  - `AMBIGUOUS`: multiple or only fuzzy candidates were found; the client must ask for confirmation.
  - `NOT_FOUND`: no sufficiently reliable candidate was found.

Exact matching is deliberately OCR-friendly: a normalized model identifier may occur inside a longer label string, and the longest matching identifier wins. Preserve this behavior when refactoring. If multiple devices share the best exact rank, return `AMBIGUOUS` rather than picking one.

The normalization contract is shared conceptually by the API and loaders: case-fold, convert `ё` to `е`, and remove non-alphanumeric separators. Keep database identifiers and query normalization compatible.

When changing response fields or statuses, update the browser UI and documentation in the same change. When changing a user-visible release version, keep the FastAPI version and the UI footer aligned.

## Development setup

Run commands from the repository root.

Start the database:

```bash
docker compose up -d db
```

Create a local Python environment and install the tools:

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r backend/requirements.txt -r tools/requirements.txt
```

Validate and load the catalog:

```bash
python tools/validate_seed.py
DATABASE_URL=postgresql://whatfits:whatfits@localhost:5432/whatfits python tools/load_seed.py
```

Start the API:

```bash
docker compose up -d api
```

The UI is at `http://localhost:8000/`; interactive API documentation is at `http://localhost:8000/docs`.

Do not delete the PostgreSQL volume as a routine reset. It destroys local data. Also note that edits to `db/schema.sql` are not applied to an already initialized volume automatically.

## Required checks

Run the checks appropriate to the files changed. At minimum, before committing:

```bash
python tools/validate_seed.py
python -m compileall -q backend tools
```

For API, matching, database, or UI changes, also run the stack and check:

```bash
curl -fsS http://localhost:8000/health
curl -fsS "http://localhost:8000/v1/fit?q=P2500W&market=RU"
curl -fsS "http://localhost:8000/v1/fit?q=PANTUM%20P2500W%20220-240V%2050Hz&market=RU"
curl -fsS "http://localhost:8000/v1/fit?q=ZXQ999&market=RU"
```

Expected invariants:

- `P2500W` resolves to `Pantum P2500W` and includes `PC-211P`.
- Longer OCR-like label text still resolves to `P2500W`.
- An unknown model returns `NOT_FOUND`, not a guessed part.
- A fuzzy or genuinely ambiguous query requires user confirmation.
- The browser UI handles `EXACT`, `AMBIGUOUS`, `NOT_FOUND`, network errors, and empty `fits` without exposing raw HTML.

There is no automated test suite yet. Add focused tests when introducing non-trivial matching, parsing, upload, or schema behavior; do not rely only on manual browser checks.

## Python and SQL conventions

- Target Python 3.12 and follow the existing small, direct FastAPI style.
- Use type hints for new reusable functions and concise docstrings for matching rules or other non-obvious behavior.
- Keep request validation in FastAPI parameters or explicit validation functions.
- Use parameterized SQL only. Never interpolate request values into SQL strings.
- Keep database connections and cursors in context managers and keep transactions short.
- Avoid broad exception handling. Return intentional HTTP errors without leaking credentials or database internals.
- Extract shared logic rather than allowing API search normalization and loader normalization to drift apart.
- Pin new production dependencies in the appropriate requirements file and add a dependency only when the standard library or current stack is insufficient.

## Browser UI conventions

- The product UI is Russian-first and mobile-friendly.
- Keep the UI usable without a frontend build step unless a migration is explicitly requested.
- Use same-origin API calls so local and deployed behavior remain simple.
- Treat API, OCR, URL, and user data as untrusted. Pass dynamic text through the existing `esc()` helper before inserting it into HTML.
- Never place secrets or private service credentials in browser JavaScript.
- Preserve accessible labels, keyboard submission, loading states, error states, and `aria-live` result announcements.
- External links must use `rel="noopener noreferrer"`.

If camera/OCR is added:

- Keep manual model entry available as a fallback.
- Require an explicit confirmation step whenever OCR does not produce one exact model.
- Limit accepted file types and upload size, handle camera permission denial, and avoid retaining or logging photos unless retention is an explicit product requirement.
- Do not expose an OCR provider key to the browser. Route provider calls through the backend.

## Catalog and evidence changes

Before adding or changing catalog records:

1. Check the manufacturer’s market-specific product page, consumable page, or official manual.
2. Confirm that the exact model code is listed, including suffixes such as `W`, `DN`, or `FDW`.
3. Record the source URL, publisher, source type, market, and actual check date.
4. Add aliases only when they identify the same device, not merely a related family.
5. Run `python tools/validate_seed.py`.
6. Load the seed into a test database and verify `/v1/fit` for the affected exact models and nearby ambiguous models.
7. Update `docs/DATA_NOTES.md` when adding a source family, manufacturer, market caveat, or materially different verification rule.

JSONL records must remain one complete JSON object per physical line. Do not pretty-print the seed file.

The seed schema, database reference values, loader, API, and UI form one contract. For example, a new replacement type may require coordinated updates to:

- `data/seed_format.schema.json`
- `db/schema.sql`
- `tools/load_seed.py`
- `backend/static/index.html`
- documentation and tests

Do not alter historical `checked_at`, `source_checked_at`, or `verified_at` dates unless the source was actually rechecked on the new date.

## Database schema changes

`db/schema.sql` initializes new databases; it is not a migration system. For a schema change:

- keep initialization of a fresh database correct;
- provide a safe forward-migration path for existing volumes, or clearly document the one-time manual step;
- keep foreign keys, uniqueness, and market scoping intact;
- avoid destructive migration or volume-removal instructions unless the user explicitly approves losing local data.

## Security and privacy

- Never commit `.env` files, credentials, access tokens, database dumps, uploaded photos, or personal device identifiers.
- Keep secrets in environment variables and provide only placeholder examples in documentation.
- Validate and bound all future text, image, and file inputs.
- Do not log OCR images or extracted serial numbers by default.
- Do not add analytics, trackers, remote fonts, or third-party browser scripts without an explicit product decision.

## Change and Git discipline

- Inspect the working tree before editing and preserve unrelated user changes.
- Keep changes focused; do not combine catalog expansion, schema redesign, and UI restyling unless the task requires them together.
- Update documentation when commands, behavior, data scope, or version numbers change.
- Use an imperative commit subject that describes the outcome.
- Commit only files belonging to the requested change.
- Never force-push, rewrite shared history, delete branches, or discard local changes without explicit permission.

## Definition of done

A change is done only when:

- the requested behavior works end to end;
- compatibility certainty and regional evidence rules are preserved;
- relevant validation, syntax, API, and UI checks pass;
- documentation and version labels are consistent with the implementation;
- no secret, generated cache, local environment, or user data is included in the commit.
