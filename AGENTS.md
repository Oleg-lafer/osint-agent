# Leadspotnic Repository Guide

This file is the authoritative working guide for this repository. Keep it current when architecture, commands, configuration, database names, or operational behavior changes.

## Product purpose

Leadspotnic ingests social-media posts from CSV, discovers clusters without predefined labels,
extracts structured entities and evidence, summarizes the clusters, builds a consolidated
overview, and serves a sourced chat interface.

The repository contains:

- `post-clustering/`: Java 17 backend, analysis pipeline, MySQL persistence, and Javalin API.
- `frontend/`: React 19 + Vite chat interface.

The online chat optionally persists conversational sessions and messages to MySQL. Persistence
is best-effort: chat continues statelessly when the database is absent or unavailable.

## Core invariants

- `post_qualification` is the default source when `DB_CREDENTIALS_FILE` is configured and no
  explicit CSV path is supplied. CSV remains available as an explicit input and offline fallback.
- The local pipeline is the behavioral baseline. Database integration must remain additive and must not change ingestion, embeddings, graph construction, clustering, extraction, summarization, or consolidation logic.
- Local caches and JSON outputs must continue to be written when database persistence is enabled.
- MySQL persistence is optional and best-effort. Missing configuration or a database failure must log a warning and allow local processing to continue.
- Never commit API keys, database credentials, source datasets, generated caches, or generated knowledge-base files.
- Do not modify company source-post tables. Offline processing persists only to the three pipeline
  `AGENT_*` tables described below; online chat may use the two chat tables.
- Preserve content-hash post IDs: evidence links and CSV-to-database matching depend on them.

## Pipeline and data flow

The entry point is `com.leadspotnic.App`.

1. `PostQualificationLoader` reads recent rows for a watch list from MySQL when database
   credentials are configured and no CSV path is supplied. It maps `userId` to profile name,
   `content` to text, and `creation_time` to publish date. Defaults are watch list `1406` and
   the last `14` days, capped to the newest `3000` matching rows by default.
   `--post-source=post-qualification` selects this input (the default). A separate execution with
   `--post-source=post-summary` uses `PostSummaryLoader`, mapping `summary` to text,
   `creation_time` to publish date, and using `post_summary` as the profile name. Its defaults are
   the last `60` days and search term `airport`. The sources are never combined in one run.
   Otherwise, `CsvLoader` reads a supplied CSV or the bundled
   `src/main/resources/posts.csv`.
   - Required columns: `profile_name`, `text`, `publish_date`.
   - Optional column: `embedding`, containing a JSON float array.
   - Current policy drops normalized text shorter than 15 characters and keeps duplicate texts.
   - Each `Post` receives a stable content-hash ID derived from author, date, and text.
2. `Embedder` loads compatible vectors from `embeddings-cache.json`, uses vectors supplied in the CSV, or fetches missing vectors with `--embed`.
3. `SimilarityGraph` builds a cosine-similarity k-nearest-neighbor graph.
4. `Clusterer` applies Leiden community detection.
5. `ClusterSplitter` re-clusters or chunks clusters larger than the configured maximum, then final cluster numbers are assigned.
6. With `--extract`, `Extractor` performs separate WHAT, WHO, and WHERE extraction calls for every cluster. It maps small prompt-local evidence references back to real content-hash post IDs. Results go to `entities.json` and `extractions-cache.json`.
7. With `--summarize`, `Summarizer` creates a `ClusterSummary` (`who`, `what`, `where`, `when`) for every cluster. Results are cached in `summaries-cache.json`.
8. `Consolidator` creates a `ConsolidatedSummary`: total posts, cluster count, one dataset overview, and all per-cluster summaries. `KnowledgeBase` writes it to `knowledge-base.json`.
9. When configured, `DatabasePipeline` mirrors the same stage outputs to MySQL. It must not transform the analytical objects before storage.

Default algorithm parameters:

- `--k=15`
- `--min-sim=0.25`
- `--resolution=3.0`
- `--max-cluster=100`
- `--split-resolution=1.0`
- `--samples=4`

Treat parameter changes as product-quality changes. Validate cluster sizes, isolated posts, representative samples, and downstream summaries rather than relying only on a successful run.

## Local and database output mapping

The canonical equivalence is:

| Pipeline object | Local output | Database output |
|---|---|---|
| Accepted post and normalized text | In memory / source query or CSV | `AGENT_post_processing` |
| Post embedding | `embeddings-cache.json` | `AGENT_post_processing.embedding` |
| `ClusterSummary` | `knowledge-base.json` and `summaries-cache.json` | `AGENT_clusters.cluster_summary` |
| `ClusterExtraction` | `entities.json` and `extractions-cache.json` | `AGENT_clusters.entities_and_evidence` |
| Consolidated overview | `knowledge-base.json` | `AGENT_pipeline_runs.dataset_overview` |

For a single run, the canonical local objects and their database JSON should be structurally identical. Compare parsed JSON, not pretty-print whitespace or raw file size alone.

## MySQL persistence

Database: MySQL on AWS RDS. Default schema: `leadspot_main`.

### `AGENT_pipeline_runs`

One row per pipeline execution. Stores status, embedding model, CLI parameters and CSV path,
consolidated overview, timestamps, usage metrics, and any failure message.

- `id`: unique preprocessing execution identity (the `PreProcessing_run_id` referenced by clusters).
- `post_group_id`: logical post-set identity. Separate executions over the same post set reuse this
  value while retaining different `id` values. New runs accept `--post-group-id=<value>`, then
  fall back to `POST_GROUP_ID`; when neither is supplied, a new UUID prevents accidental grouping.
  Rows created before this field was introduced remain null because their grouping is unknown.

Expected lifecycle: `RUNNING` to `COMPLETED` or `FAILED`.

Completed runs store `processed_post_count` (accepted posts that entered processing), `duration_ms`,
aggregate input/output/total token counts,
`estimated_cost_usd`, and `usage_details`. The JSON details retain per-stage/per-model usage and
the pricing basis used by the run. Cached work costs zero tokens; estimates use the configured
execution-time prices for `gpt-4o-mini` and `text-embedding-3-small`.

### `AGENT_clusters`

One row per final cluster, after oversized-cluster splitting.

- `PreProcessing_run_id`: owning run; foreign key to `AGENT_pipeline_runs.id`.
- `cluster_number`: cluster identity within the run.
- `post_count`: declared number of assigned posts.
- `cluster_summary`: complete serialized `ClusterSummary` JSON.
- `entities_and_evidence`: complete serialized `ClusterExtraction` JSON.

The pair `(PreProcessing_run_id, cluster_number)` is unique.

### `AGENT_post_processing`

One row per accepted CSV occurrence per run.

- Database-source rows use their originating table (`post_qualification` or `post_summary`) in
  `source_table`; CSV rows use `source_table = 'CSV'`.
- `source_post_id` is the content-hash ID; repeated identical occurrences receive `#2`, `#3`, and so on to satisfy uniqueness without changing `Post` identity.
- `normalized_text` contains the exact text processed by the current pipeline despite its historical column name.
- `embedding` contains the vector as JSON.
- `cluster_id` points to `AGENT_clusters.id` and may be null for unfinished or failed processing.
- Normal successful statuses progress through `PENDING`, `EMBEDDED`, and `CLUSTERED`.

Deleting a pipeline run cascades to its clusters and processing rows. Deleting a cluster sets referencing `cluster_id` values to null.

### Database configuration

Set `DB_CREDENTIALS_FILE` to a file containing `host`, `user`, and `password`. Optional overrides:

- `DB_NAME` (default `leadspot_main`)
- `DB_PORT` (default `3306`)
- `AGENT_PIPELINE_RUN_ID` (server: select a specific completed summarized run)
- `POST_GROUP_ID` (pipeline: reusable logical post-group identifier; CLI `--post-group-id` wins)
- `WATCH_LIST_ID` (source query; default `1406`)
- `POST_LOOKBACK_DAYS` (source query; default `14`)
- `POST_LIMIT` (maximum newest `post_qualification` rows; default `3000`)
- `POST_SUMMARY_LOOKBACK_DAYS` (`post_summary` lookback; default `60`)
- `POST_SUMMARY_SEARCH_TERM` (`post_summary.summary` substring; default `airport`)
- `POST_SUMMARY_LIMIT` (maximum newest matching `post_summary` rows; default `3000`)

### Online chat persistence

Migration `V3__create_chat_tables.sql` adds the two online-only tables:

- `AGENT_chat_sessions`: one UUID-keyed conversation with optional external `user_id`, status,
  one immutable `pipeline_run_id`, and lifecycle timestamps.
- `AGENT_chat_messages`: ordered user and assistant messages. Assistant rows also store the
  cluster IDs, sources, research log, elapsed time, model, and attempt status.

Migration `V4__bind_chat_sessions_to_pipeline_run.sql` moves preprocessing-run ownership from
individual assistant messages to the session. Every persisted conversation therefore stays on
one knowledge-base snapshot. `AGENT_chat_sessions.pipeline_run_id` references
`AGENT_pipeline_runs.id`, and its clusters are the rows whose `PreProcessing_run_id` matches it.

`POST /chat` accepts optional `sessionId` and `userId`. With database configuration, omitting
`sessionId` creates a session for the requested `pipelineRunId` and returns its ID; subsequent
requests reuse it and cannot change its run. The newest ten
completed transcript messages are included as untrusted context. Unknown and closed persisted
sessions return 404 and 409 respectively. If chat persistence fails, the request is answered
statelessly and returns a null session ID.

Do not hard-code or print credentials. Do not commit machine-specific credential paths.

The chat server selects the newest completed run containing an overview and summarized clusters
unless `AGENT_PIPELINE_RUN_ID` is set. If loading is unavailable or unusable, it falls back to
local files. The initially selected database run reloads enriched drill-down posts from
`post_qualification`; selectable historical runs use their exact persisted texts and embeddings
from `AGENT_post_processing` so evidence cannot drift across preprocessing runs.

## OpenAI configuration

All direct OpenAI configuration is centralized in `com.leadspotnic.llm.OpenAi`.

- Chat model: `gpt-4o-mini`
- Embedding model: `text-embedding-3-small`
- API base: `https://api.openai.com/v1`
- Key: raw key text in the gitignored `KEYS_AND_CREDENTIALS/OPEN_AI.txt` file

LLM and embedding calls can cost money. Unit tests must remain offline. Do not run full uncached LLM workflows unless the task calls for it.

## Chat server

Entry point: `com.leadspotnic.web.Server`; port `7070`.

- `GET /status`: readiness, total post count, and cluster count.
- `GET /runs`: completed summarized preprocessing runs available for chat selection.
- `POST /chat`: accepts
  `{"query":"...","sessionId":null,"userId":null,"pipelineRunId":123}` and
  returns the session ID, answer, elapsed time, sources, and research log.

The server loads original posts separately because the three pipeline AGENT tables do not store
all author/date fields needed for drill-down. CSV resolution order is:

1. First server command argument
2. `POSTS_CSV`
3. CSV path recorded in the selected database run
4. The bundled `src/main/resources/posts.csv`

When database embedding coverage does not cover every CSV post, the server uses the local embedding cache rather than mixing vector sources.

## Commands

On Windows, start the live chat backend and frontend together from the repository root. The launcher
checks prerequisites, installs frontend dependencies when needed, waits for backend readiness,
and stops both services on Ctrl+C. It defaults `DB_CREDENTIALS_FILE` to the gitignored
`KEYS_AND_CREDENTIALS/DataBase_Credentials.txt` when the variable is unset and enables strict
database-only mode. Startup fails instead of reading local CSV, JSON, or embedding caches if
the selected run or its source data is unavailable or incomplete:

```powershell
& '.\live chat.ps1'
```

Run the database-backed PreProcessing pipeline and pass the number of newest qualification posts
to process as its required argument. The launcher creates two independent runs: one from
`post_qualification` and one from `post_summary`. Each invokes paid OpenAI calls and neither starts
live chat:

```powershell
& '.\PreProcessing pipeline.ps1'

# Override the limits by hand: qualification limit first, summary limit second
& '.\PreProcessing pipeline.ps1' 1000 500
```

Run backend commands from `post-clustering/`.

```powershell
# Offline tests
mvn test

# Use the bundled CSV and existing compatible embedding cache
mvn -q compile exec:java

# Full bundled pipeline; invokes OpenAI for uncached work
mvn -q compile exec:java "-Dexec.args=--embed --extract --summarize"

# Full pipeline with an explicit CSV
mvn -q compile exec:java "-Dexec.args=C:\path\to\posts.csv --embed --extract --summarize"

# A second independent execution for the same logical post group reuses this value
mvn -q compile exec:java "-Dexec.args=--post-group-id=group-A --embed --extract --summarize"

# Chat server
mvn -q compile exec:java "-Dexec.mainClass=com.leadspotnic.web.Server"
```

Run frontend commands from `frontend/`.

```powershell
npm install
npm run dev
npm run lint
npm run build
```

## Generated and sensitive files

These files are intentionally ignored and should not be committed:

- `post-clustering/.env`
- `KEYS_AND_CREDENTIALS/`
- `post-clustering/data/`
- `embeddings-cache.json`
- `summaries-cache.json`
- `extractions-cache.json`
- `knowledge-base.json`
- `entities.json`
- Maven `target/`
- frontend `node_modules/` and `dist/`

Caches include a model identifier. Do not silently reuse data from a different embedding or chat model.

## Verification expectations

For ordinary backend changes:

1. Run `mvn test`.
2. Confirm local execution still works without `DB_CREDENTIALS_FILE`.
3. For persistence changes, test both database-disabled fallback and the opt-in RDS integration test.
4. For schema or mapping changes, verify row counts, JSON validity, cluster-to-post counts, foreign keys, embedding dimensions, and evidence post IDs.
5. For frontend changes, run `npm run lint` and `npm run build`.

The RDS integration test is intentionally opt-in because it writes a synthetic pipeline run and deletes it afterward. Enable it only with explicit database-test configuration:

```powershell
$env:RUN_DB_TESTS='true'
mvn -Dtest=AgentDatabaseIntegrationTest test
```

When evaluating persistence quality, preserve an independent local baseline. Exact equality between current local and current database output proves faithful storage, but it does not by itself prove equivalence to an older pipeline version. Separate LLM runs may differ in wording even with the same model and temperature, so combine structural comparison, fixed-response tests, and code review.

## Change guidance

- Prefer small changes within the existing package boundaries: `model`, `ingest`, `cluster`, `summarize`, `agent`, `web`, `llm`, and `persistence`.
- Keep persistence concerns inside `persistence`; do not embed SQL into analytical stages.
- Add database calls around completed stage outputs, never inside the algorithms that produce them.
- Keep JSON models backward-compatible unless a coordinated local-file and database migration is explicitly requested.
- When renaming schema objects, update application SQL, tests, operational documentation, foreign-key expectations, and live schema together; verify existing data before and after.
- Do not treat LLM output length alone as quality. Check factual coverage, evidence linkage, missing fields, cluster coherence, and unsupported claims.
- Research logs must describe only actions the agent actually performed and must not expose private chain-of-thought.
