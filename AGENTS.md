# AGENTS.md

This file applies to the entire repository. It is the operating guide for coding agents working on What Fits?.

## Product goal

What Fits? answers one high-stakes compatibility question for the Russian market:

```text
device model -> compatible replacement -> supporting source
```

The final product is a native Android application distributed through RuStore. The web UI is an auxiliary prototype, not the primary client. The current prototype covers printers and MFPs, contains 50 Pantum models for market `RU`, and all user-visible components are version `0.0.10`.

Accuracy is more important than recall. A missing result is acceptable; a confidently wrong cartridge is not.

## Non-negotiable product rules

- Never infer a compatible part from a similar model name alone.
- Never silently turn a fuzzy match into an exact device match. Ask the user to choose among candidates.
- OCR is only an input method. OCR text must pass through the same device resolution and `/v1/fit` compatibility logic as typed text.
- The Android application must work without Google Play Services and without a network connection for camera capture, OCR, manual model search, and compatibility results from its bundled catalog.
- Do not add `com.google.android.gms`, Firebase, ML Kit, Google Billing, Google Analytics, FCM, or Google In-App Updates to Android runtime configurations. The `verifyNoGoogleRuntime` task is mandatory and must remain enabled in CI.
- The RuStore client must be a complete native application, not a WebView wrapper around the browser prototype.
- Keep market-specific compatibility explicit. Do not copy EU, US, CN, or generic catalog data into `RU` without evidence for the Russian version.
- Show “verified” or the equivalent Russian wording only for a compatibility edge whose stored status and evidence justify it.
- Every compatibility claim must retain its source, publisher, market, and verification date.
- Marketplace listings, reviews, search snippets, and AI-generated answers are discovery aids, not sufficient evidence for `VERIFIED`.
- If evidence is ambiguous or conflicts, use a weaker status such as `UNDER_REVIEW` or omit the edge. Do not guess.

## Repository map

- `backend/app/main.py`: FastAPI application, bounded in-memory OCR, device matching, compatibility queries, and the root page handler.
- `backend/static/index.html`: dependency-free, same-origin browser interface. It calls `/v1/fit` and `/v1/ocr`.
- `backend/Dockerfile`: Python 3.12 API image with the Tesseract English OCR engine.
- `android/`: native Kotlin/Jetpack Compose application for RuStore. Its core flow is offline-first and GMS-free.
- `android/app/src/main/java/app/whatfits/catalog/`: local JSONL parsing and conservative device matching.
- `android/app/src/main/java/app/whatfits/camera/`: CameraX capture that keeps the image on the device.
- `android/app/src/main/java/app/whatfits/ocr/`: PP-OCRv6 primary recognition, Tesseract fallback, bounded image handling, and local OCR.
- `android/app/build.gradle.kts`: pinned Android dependencies, verified bundled OCR model generation, and runtime dependency audit.
- `android/third_party/`: provenance and license for the vendored official PaddleOCR Android SDK AAR.
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
- `requirements-dev.txt`: pinned local and CI test dependencies.
- `tests/`: unit, catalog-policy, and PostgreSQL integration tests.
- `android/app/src/test/`: JVM tests for the Android catalog parser and matching rules.
- `tests/fixtures/camera/`: manifest and privacy rules for future anonymized OCR regression images.
- `.github/workflows/ci.yml`: CI validation on pushes and pull requests.

## Current API contract

- `GET /` serves `backend/static/index.html`.
- `GET /health` returns `{"status":"ok"}`.
- `GET /v1/devices/search?q=...&market=RU` returns fuzzy candidates. This endpoint must not imply confirmed compatibility.
- `GET /v1/devices/{id}/replacements?market=RU` returns the stored parts and evidence for one device.
- `GET /v1/fit?q=...&market=RU` is the main product endpoint and returns exactly one of:
  - `EXACT`: one exact model or alias was resolved, with its `fits`.
  - `AMBIGUOUS`: multiple or only fuzzy candidates were found; the client must ask for confirmation.
  - `NOT_FOUND`: no sufficiently reliable candidate was found.
- `POST /v1/ocr?market=RU` accepts a raw JPEG, PNG, or WebP request body (at most 10 MB) with the matching `Content-Type`, recognizes label text in memory, and returns the same statuses after resolving through `fit`. It adds `input: "OCR"` and deliberately omits the raw OCR `query`.

Exact matching is deliberately OCR-friendly: a normalized model identifier may occur inside a longer label string, and the longest matching identifier wins. Preserve this behavior when refactoring. If multiple devices share the best exact rank, return `AMBIGUOUS` rather than picking one.

The normalization contract is shared conceptually by the API and loaders: case-fold, convert `ё` to `е`, and remove non-alphanumeric separators. Keep database identifiers and query normalization compatible.

The Android offline matcher must preserve the same certainty contract: identifiers embedded in longer OCR text are eligible for exact resolution, the longest identifier wins, equal best matches return `AMBIGUOUS`, and fuzzy input never becomes `EXACT` without confirmation. Android loads `data/seed_ru_printers_v0.1.jsonl` into the APK; do not create a divergent hand-maintained mobile catalog.

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
python -m pip install -r requirements-dev.txt
```

Validate and load the catalog:

```bash
python tools/validate_seed.py
DATABASE_URL=postgresql://whatfits:whatfits@localhost:5432/whatfits python tools/load_seed.py
```

Build and start the API:

```bash
docker compose up -d --build api
```

The UI is at `http://localhost:8000/`; interactive API documentation is at `http://localhost:8000/docs`.

Build the Android application with JDK 17, Android SDK Platform 36, and Build Tools 36.1.0. Use the checked-in Gradle Wrapper; it pins Gradle 9.4.1 and verifies the distribution checksum:

```bash
./android/gradlew -p android --no-daemon :app:testDebugUnitTest :app:lintDebug :app:verifyNoGoogleRuntime :app:assembleDebug
```

Do not delete the PostgreSQL volume as a routine reset. It destroys local data. Also note that edits to `db/schema.sql` are not applied to an already initialized volume automatically.

## Required checks

Run the checks appropriate to the files changed. At minimum, before committing:

```bash
python tools/validate_seed.py
python -m compileall -q backend tools tests
python -m pytest
```

For Android changes, also run the Android build command above. It must produce `android/app/build/outputs/apk/debug/app-debug.apk`; CI uploads this file as a short-lived test artifact.

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
- Camera capture and file selection keep the photo in the browser until explicit recognition, enforce JPEG/PNG/WebP and 10 MB limits, and release object URLs when the photo is replaced or the page closes.
- OCR bounds encoded size, decoded pixel count, processing dimensions, text length, and recognition time; it does not persist the image or return raw recognized text.

The PostgreSQL integration suite is enabled with `RUN_DB_TESTS=1` after initializing and loading a test database. The generated-image Tesseract test additionally uses `RUN_OCR_TESTS=1` and requires the OCR binary plus DejaVu fonts. Add focused tests when introducing non-trivial matching, parsing, upload, or schema behavior; do not rely only on manual browser checks.

## Android and RuStore conventions

- Target a native Kotlin and Jetpack Compose application. Camera features use CameraX; do not replace the client with a WebView.
- `minSdk` is 26 and the APK ABI is `arm64-v8a` starting with v0.0.10 because the official PaddleOCR Android SDK requires Android 8.0 and its universal native runtime makes the APK impractically large. This is a documented product tradeoff for materially better offline OCR; do not narrow support further without another explicit decision.
- The only required Android permission in the current product is `CAMERA`. Do not add `INTERNET`, storage, advertising ID, contacts, location, microphone, or notification permissions without a feature that requires them and an explicit privacy review.
- Use the pinned official PaddleOCR Android SDK and PP-OCRv6 tiny ONNX models as the primary OCR path. Retain Tesseract4Android with pinned `tessdata_fast` as the local fallback.
- All OCR models must be verified by SHA-256 during the build and bundled into the APK. Runtime model downloads are not allowed for the core OCR path.
- Treat the SDK provenance record and SHA-256 verification for build-time OCR assets as supply-chain controls. Do not weaken them or use moving branch URLs.
- Keep photos and raw OCR text on the device. Do not log them, save captures to shared storage, or upload them to the API.
- During OCR quality testing, Android may display recognized text locally in a clearly labeled diagnostic card. Never transmit or log that diagnostic text.
- The bundled catalog is the offline source of truth. Future network updates must be signed, validated, atomic, and retain the bundled catalog as a fallback.
- External evidence links may require a browser and network, but lack of network must not prevent showing the locally stored compatibility result.
- RuStore release signing keys, tokens, and console credentials must never be committed. CI may build unsigned/debug artifacts without them.
- Keep `versionName`, `versionCode`, the FastAPI version, both web version labels, README, and this file synchronized for product releases.

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

For camera/OCR work:

- Keep manual model entry available as a fallback.
- Preserve the current separate camera and gallery inputs, preview, retake, and remove flow.
- Require an explicit confirmation step whenever OCR does not produce one exact model.
- Limit accepted file types, encoded size, decoded pixel count, and OCR runtime; handle camera permission denial and avoid retaining or logging photos unless retention is an explicit product requirement.
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
- Treat `README.md` maintenance as a required part of every implementation change. After changing code, API behavior, UI, schema, dependencies, catalog scope, commands, or a version, review and update the README in the same change before pushing.
- Do not finish a version update until the FastAPI version, browser UI version, GitHub Pages version, and README version/current-state description agree.
- Update other documentation when commands, behavior, data scope, evidence rules, or architecture change.
- Use an imperative commit subject that describes the outcome.
- Commit only files belonging to the requested change.
- Never force-push, rewrite shared history, delete branches, or discard local changes without explicit permission.

## Definition of done

A change is done only when:

- the requested behavior works end to end;
- compatibility certainty and regional evidence rules are preserved;
- relevant validation, syntax, API, and UI checks pass;
- `README.md` was reviewed and updated for every code, data, schema, UI, API, dependency, command, or version change;
- documentation and version labels are consistent with the implementation;
- no secret, generated cache, local environment, or user data is included in the commit.

## Mandatory repository-wide audit and deep-refactoring protocol

When a task requests a full audit, cleanup, optimization, simplification, or deep refactoring, the rules in this section are mandatory.

A repository-wide audit is an implementation task, not merely a request for recommendations.

### Objective

Audit the entire repository and reduce the codebase to the minimum necessary complexity while preserving 100% of its current functionality and externally observable behavior.

The refactored project must preserve, unless an explicitly requested bug fix requires otherwise:

- user-facing functionality;
- application behavior;
- UI/UX;
- business or game logic;
- public APIs and contracts;
- persisted and exchanged data formats;
- platform-specific behavior;
- documented capabilities;
- edge-case semantics.

The goal is minimum **necessary complexity**, not minimum line count.

Do not perform code golf.

A change is justified only when it objectively reduces one or more of:

- code volume;
- duplication;
- conceptual complexity;
- branching or state;
- coupling;
- maintenance burden;
- dependency surface;
- runtime cost;
- regression risk.

If a change merely makes the implementation different without making it demonstrably smaller, simpler, safer, clearer, or more efficient, leave the code unchanged.

Default decision rule:

- if code is proven unnecessary, delete it;
- if code can be objectively simplified, simplify it;
- if duplicate responsibilities can be safely consolidated, consolidate them;
- if an abstraction no longer provides value, remove it;
- if the benefit is uncertain, preserve the existing working behavior.

### Required audit scope

Before making repository-wide refactoring changes, inspect the complete repository rather than only recently changed files or obvious hotspots.

Review all relevant areas, including:

- production source code;
- tests and test utilities;
- resources and assets;
- configuration files;
- build scripts and build configuration;
- CI/CD workflows;
- release tooling;
- dependencies and development dependencies;
- static-analysis and security configuration;
- documentation;
- platform-specific integration;
- generated-code integration points;
- directory and module structure.

First establish:

1. the actual architecture;
2. authoritative sources of state and business logic;
3. all current user-visible functionality;
4. important internal behavior and contracts;
5. persistence and compatibility requirements;
6. framework-, build-, platform-, or convention-driven entry points.

Only then begin removing or consolidating code.

### Required removal candidates

Actively search for and remove, when proven safe:

- dead code;
- unreachable code;
- unused functions;
- unused classes;
- unused methods;
- unused variables;
- unused constants;
- unused types;
- unused interfaces;
- unused imports and exports;
- unused files;
- unused resources and assets;
- obsolete legacy code that no longer participates in current behavior;
- temporary workarounds whose original constraint no longer exists;
- duplicate or near-duplicate implementations;
- unnecessary abstraction layers;
- unnecessary wrappers;
- unnecessary helper functions;
- adapter chains that add no meaningful semantics;
- redundant data conversions;
- redundant intermediate objects;
- redundant intermediate state;
- redundant copies and transformations;
- defensive checks for states already guaranteed by types, architecture, invariants, or earlier validation;
- repeated validation of already validated data;
- repeated checks of the same condition;
- redundant fallbacks;
- obsolete compatibility branches;
- unused feature flags;
- obsolete configuration options;
- unused dependencies;
- unused development dependencies;
- duplicate-purpose dependencies;
- commented-out historical code;
- stale TODO/FIXME items;
- leftovers from previous migrations or refactors.

Pay particular attention to patterns such as:

- repeated null/state/bounds checks;
- nested checks of the same condition;
- repeated validation across adjacent layers;
- unnecessary `try/catch` blocks;
- wrapper -> wrapper -> wrapper call chains;
- DTO/model conversion chains;
- multiple representations of the same authoritative state;
- temporary states that duplicate existing state;
- compatibility paths left behind after migrations;
- helpers with only one trivial caller;
- abstractions created for future scenarios that never materialized.

### Proving code is unused

Do not classify code as unused solely because a textual search finds no direct call.

Before removing anything, check for indirect or convention-driven use through:

- callbacks;
- events;
- observers;
- dependency injection;
- reflection;
- dynamic loading;
- serialization/deserialization;
- framework conventions;
- lifecycle hooks;
- manifests;
- resources;
- routing;
- configuration;
- generated code;
- build scripts;
- CI/CD;
- release tooling;
- plugins;
- native/platform integration;
- test or debug tooling;
- external/public contracts.

When uncertainty remains, preserve the code until its lack of use can be demonstrated.

### Required simplification review

Look for opportunities to safely:

- shorten code without reducing readability;
- simplify control flow;
- reduce nesting;
- reduce mutable state;
- reduce the number of branches;
- merge equivalent paths;
- merge components with the same responsibility;
- remove unnecessary temporary values;
- remove unnecessary objects and allocations;
- remove redundant copies;
- remove repeated transformations;
- remove redundant loops or passes over the same data;
- compute invariant values once instead of repeatedly;
- replace custom code with standard language, platform, framework, or library facilities when clearly simpler;
- eliminate repeated business logic;
- centralize genuinely shared logic when doing so reduces total code and conceptual complexity;
- reduce coupling;
- remove premature generalization;
- remove architecture that exists only for hypothetical future requirements.

Do not introduce an abstraction merely to satisfy DRY.

Similar-looking code should only be unified when the resulting abstraction reduces real duplication and overall complexity.

### Architecture review

Explicitly determine whether:

- historical layers are still necessary;
- historical components are still necessary;
- abstractions are disproportionate to the actual problem;
- interfaces exist with only one implementation and no meaningful boundary benefit;
- classes or modules can be safely merged;
- classes or modules can be safely deleted;
- responsibilities are unnecessarily fragmented;
- there is premature generalization;
- there is speculative extensibility;
- infrastructure exists only for hypothetical future features;
- current architecture reflects requirements that no longer exist;
- duplicated state or business logic creates unnecessary coupling;
- implementation complexity is proportional to actual product complexity.

Prefer the simplest architecture that fully supports the current product.

Do not perform a large rewrite merely because another architecture appears cleaner, newer, or more fashionable.

### Performance review

Review performance only where there is practical value.

Look for:

- repeated calculations;
- repeated queries;
- repeated reads;
- unnecessary parsing;
- repeated transformations of unchanged data;
- avoidable allocations;
- unnecessary copies;
- redundant loops;
- multiple passes that can safely become one;
- unnecessary render/re-render/rebuild/recompute work;
- inappropriate data structures for actual access patterns;
- invariant operations performed repeatedly instead of once;
- unnecessary work in frequently executed paths.

Do not introduce micro-optimizations that reduce readability or maintainability without an obvious or measurable benefit.

### Dependency review

Review every production and development dependency.

For each dependency:

- verify that it is actually used;
- verify that its purpose is still required;
- remove unused dependencies;
- remove obsolete dependencies;
- identify multiple libraries serving the same purpose;
- consolidate overlapping dependencies where safe;
- prefer standard language/platform/framework functionality when it clearly replaces a dependency with less total complexity.

A dependency may be replaced by a small local implementation only when doing so clearly reduces overall complexity, risk, maintenance cost, or artifact size.

Do not replace a mature, well-maintained library with custom code merely to reduce the dependency count.

### Non-negotiable behavior-preservation rules

Unless explicitly requested, a repository-wide cleanup or refactor must not intentionally:

- remove user-facing functionality;
- change existing UX;
- change visual behavior;
- change business logic;
- change game rules;
- change public APIs;
- change public contracts;
- change persisted data formats;
- change exchanged data formats;
- change edge-case behavior;
- change platform behavior;
- weaken compatibility;
- weaken accessibility;
- weaken security or privacy controls;
- reduce functionality merely to reduce code size;
- add unrelated functionality;
- introduce new architectural layers only to satisfy generic best practices;
- perform a broad rewrite without a demonstrated need.

Repository-specific invariants defined elsewhere in this file remain mandatory throughout the refactor.

### Required execution workflow

For a repository-wide audit or deep refactor:

1. Inspect the entire repository.

2. Establish the current:
   - architecture;
   - feature set;
   - user-visible behavior;
   - internal contracts;
   - authoritative state;
   - persistence behavior;
   - platform constraints.

3. Collect reliable baseline statistics where practical.

4. Build an internal candidate list grouped into:
   - deletion;
   - consolidation;
   - simplification;
   - architecture reduction;
   - dependency cleanup;
   - practical performance optimization.

5. Validate every removal against both direct and indirect usage.

6. Rank candidates by:
   - confidence;
   - regression risk;
   - complexity reduction;
   - maintenance benefit.

7. Apply changes in small, logically coherent groups rather than as one uncontrolled rewrite.

8. After every meaningful group, run the relevant available checks, such as:
   - unit tests;
   - integration tests;
   - regression tests;
   - lint;
   - formatting checks;
   - type checking;
   - compilation;
   - build;
   - static analysis;
   - dependency/security checks;
   - project-specific validation.

9. If critical behavior lacks sufficient test coverage for a contemplated risky change, first add the minimum regression test required to capture the existing behavior.

10. Keep behavior-preserving refactoring separate from unrelated feature development.

11. When a candidate cannot be proven safe, leave it unchanged and record the reason.

12. Complete the first refactoring pass.

13. Perform a mandatory second full pass over the already-refactored repository.

14. During the second pass, look again for:
   - newly exposed simplification opportunities;
   - remaining dead code;
   - residual duplication;
   - unnecessary abstractions;
   - unnecessary wrappers;
   - redundant checks;
   - unused dependencies;
   - residual legacy;
   - temporary structures made obsolete by the first pass.

15. Finish with the complete available project verification suite.

### Validation requirements

The final verification should include every applicable repository check.

Examples include:

- clean build;
- complete unit-test suite;
- integration tests;
- regression tests;
- lint;
- formatting verification;
- type checking;
- static analysis;
- dependency audit;
- security checks;
- platform-specific builds;
- production build;
- project-specific verification scripts.

Do not claim a check passed unless it was actually executed successfully.

If a check cannot be run because of environment, credentials, hardware, platform, unavailable tooling, or another external limitation, state that explicitly.

### Comments during refactoring

Preserve the repository's comment policy.

As a default:

- keep comments to the minimum necessary;
- keep source-code comments in English;
- remove stale comments;
- remove misleading comments;
- remove redundant comments;
- do not add comments that merely narrate obvious code;
- retain comments that explain a non-obvious reason, constraint, workaround, invariant, compatibility requirement, or important contract.

Do not replace clear code with explanatory comments when the code itself can be made self-explanatory.

### Completion standard

A repository-wide audit/refactoring task is not complete merely because:

- code was formatted;
- static analysis was run;
- recommendations were listed;
- potential improvements were described;
- only recently modified files were reviewed.

Safe and justified improvements must be implemented directly.

The final codebase should be objectively smaller, simpler, less duplicated, less coupled, easier to reason about, or easier to maintain while preserving behavior.

### Required final report

At completion, report:

1. **Removed**
   - dead code;
   - unused files/resources;
   - obsolete compatibility code;
   - unnecessary abstractions;
   - other removed components.

2. **Consolidated**
   - duplicated logic;
   - equivalent components;
   - repeated validation;
   - shared responsibilities.

3. **Simplified**
   - control flow;
   - state;
   - architecture;
   - transformations;
   - hot paths.

4. **Dependencies**
   - dependencies removed;
   - duplicate-purpose dependencies consolidated;
   - dependency changes intentionally not made and why.

5. **Legacy**
   - legacy components found;
   - which were removed;
   - which remain and why.

6. **Intentionally unchanged**
   - candidates reviewed but preserved;
   - reason removal or simplification could not be proven safe.

7. **Verification**
   - tests run;
   - builds run;
   - lint/typecheck/static-analysis/security checks run;
   - their results.

8. **Limitations**
   - areas that could not be safely optimized;
   - missing coverage;
   - unavailable environment/tooling;
   - external constraints.

9. **Before/after statistics**, when they can be measured reliably:
   - file count;
   - source line count;
   - dependency count;
   - test count;
   - production/build artifact size;
   - other project-relevant metrics.

Never invent statistics.

Use the same counting method for before and after values.

### Final principle

When choosing between:

- preserving working code whose necessity is uncertain;
- deleting code because it appears unnecessary;

preserve the working behavior until the code is proven unnecessary.

The final goal is a codebase containing only the complexity required to implement the project's current functionality: as small, clean, understandable, maintainable, and efficient as reasonably possible, without functional regressions.

## App icon source artwork

- When the project owner provides a new app icon as a PNG and identifies it as the app icon, treat that exact PNG as the canonical source artwork.
- Keep that source as the original raster PNG. Do not trace, vectorize, redraw, restyle, recreate, or convert it to SVG, vector PDF, Android VectorDrawable, SF Symbol, or any other vector representation unless the project owner explicitly requests it.
- Do not overwrite, recompress, optimize in place, or otherwise rewrite the canonical PNG. Keep the uploaded source unchanged.
- Platform-required derivatives may be generated only as raster derivatives of that PNG. Resizing and required raster packaging/container formats such as PNG size variants, ICO, or ICNS are allowed, but the visible artwork must remain unchanged: no cropping, padding, color changes, removed details, or other design edits unless explicitly requested.
- If an older icon in another format is currently canonical, keep it until the project owner explicitly supplies a replacement PNG as the new app icon. Once supplied, that PNG becomes the canonical source and the asset pipeline should derive required icons from it rather than converting it to a vector source.
