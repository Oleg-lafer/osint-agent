# Backend Working Guide

This file applies to everything under `post-clustering/`. Follow the repository-root `AGENTS.md` as well; this guide adds backend-specific rules.

## Scope and architecture

- Java 17 Maven project.
- `com.leadspotnic.App` runs the preprocessing pipeline.
- `com.leadspotnic.web.Server` runs the Javalin chat API on port `7070`.
- Keep responsibilities within the existing packages: `ingest`, `cluster`, `summarize`, `agent`, `web`, `llm`, `model`, and `persistence`.
- `com.leadspotnic.llm.OpenAi` is the only location for direct OpenAI configuration.

## Behavioral invariants

- Database integration is optional and additive. Without database configuration, local CSV input, caches, and JSON outputs must continue to work.
- Database failures must degrade gracefully unless strict database-only server startup was explicitly selected.
- Do not modify company source-post tables. Pipeline writes belong only in the `AGENT_*` tables documented by the root guide.
- Preserve stable content-hash post IDs and evidence mappings.
- Do not transform analytical model objects solely for database persistence; local and database JSON must remain structurally equivalent.
- Keep LLM and embedding work out of unit tests. Tests must be deterministic and offline by default.
- Do not print or commit credentials, source datasets, generated caches, generated knowledge-base files, or Maven build output.

## Development rules

- Keep SQL and persistence lifecycle logic in `persistence`; do not place database calls inside clustering or summarization algorithms.
- Add persistence around completed stage outputs, not within the stages that produce them.
- Keep JSON models backward-compatible unless a coordinated migration is part of the task.
- Treat changes to clustering defaults, prompts, models, or cache compatibility as product-quality changes requiring targeted validation.
- Maintain best-effort chat persistence: chat should remain usable statelessly when persistence is unavailable.
- Research logs may report actions actually performed, but must not expose hidden reasoning or chain-of-thought.

## Commands and verification

Run commands from `post-clustering/`:

```powershell
mvn test
mvn -q compile exec:java
mvn -q compile exec:java "-Dexec.mainClass=com.leadspotnic.web.Server"
```

For ordinary backend changes, run `mvn test`. Also run the relevant local, database-disabled path when changing ingestion, pipeline orchestration, caching, or server loading.

The RDS integration test writes and then deletes synthetic data. Run it only when the task explicitly calls for database integration testing and valid test configuration is present:

```powershell
$env:RUN_DB_TESTS='true'
mvn -Dtest=AgentDatabaseIntegrationTest test
```

Do not run uncached `--embed`, `--extract`, or `--summarize` workflows unless the task explicitly requires paid OpenAI calls.

## Schema and API changes

- For schema changes, update migrations, application SQL, tests, operational documentation, foreign-key expectations, and live-schema assumptions together.
- Verify row counts, JSON validity, cluster-to-post counts, foreign keys, embedding dimensions, and evidence post IDs for persistence changes.
- Preserve the documented `/status`, `/runs`, and `/chat` contracts unless a coordinated frontend/backend change is requested.
- Update this file when backend commands, architecture, configuration, schema names, or runtime behavior change.
