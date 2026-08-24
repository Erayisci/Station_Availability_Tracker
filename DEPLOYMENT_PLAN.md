# Deployment Plan — EV Charging Analytics Platform (GCE VM + Docker Compose)

## Context

The platform is three app components plus a datastore that must run together, continuously:

- **`console`** — React/Vite SPA (static `dist/`), calls the backend via relative paths `/zes`, `/astor`, `/sarjet`.
- **`core-api`** — Spring Boot 3.2.5 (Java 17) backend on `8080`. Polls ZES/ASTOR public APIs every 5 min, runs daily cron finalization (23:59) + perf build (00:15), stores short-term state in file-based H2, reads/writes Elasticsearch.
- **`intelligence`** — Python 3.10 FastAPI ML service on `8001`. Reads/writes ES indices, keeps model artifacts on disk (`models/`).
- **Elasticsearch 8.13.4** — the canonical datastore. Stateful, must persist.

Because the system is **stateful and always-on** (persistent ES data + continuous polling + scheduled jobs), a single always-on VM running Docker Compose is the right fit — not serverless. The goal: it **runs no matter what** (survives reboots/crashes), reachable only from the user's IP.

**Decisions made:** GCE VM + Docker Compose · access **restricted by IP** · ASTOR token **externalized to env var**.

**Access recommendation (asked for advice):** Since access is firewalled to your IP, expose **nginx on port 80 over plain HTTP at the VM's public IP** and lock the firewall to your IP range(s). No domain or TLS needed for a single-user, IP-restricted dashboard — TLS adds a domain + cert-renewal burden with little benefit here. If you later want to share it broadly, add a domain + TLS (Caddy/Let's Encrypt) as a follow-up.

## Architecture on the VM

All containers on one private Docker network; **only nginx publishes a host port (80)**. ES/Java/Python/H2-console stay internal (not reachable from the internet at all).

```
internet ──(firewall: tcp/80 + tcp/22 from your IP only)──> VM
                                                             │
                                              nginx :80 (web) ── serves dist/, SPA fallback
                                                 ├── /zes /astor /sarjet ─> tracker:8080 (Java)
                                                 └── /ml/                ─> pricing:8001 (Python, manual triggers)
                                              tracker:8080  ─> elasticsearch:9200, H2 file (volume)
                                              pricing:8001  ─> elasticsearch:9200, models (volume)
                                              elasticsearch:9200 (data volume)
```

Persistence via named volumes on the VM disk: `es_data`, `tracker_data` (H2), `ml_models`. All containers `restart: unless-stopped`.

## Code changes

### 1. Make Elasticsearch host configurable (Java) — required
Every ES-touching service hardcodes `RestClient.builder(new HttpHost("localhost", 9200)).build()` (~22 files, e.g. `core-api/src/main/java/com/charging/elastic/IndexInitService.java:36`, `SocketTransformService.java:33`, `AvailabilityAnalysisService.java`, and all of `com/charging/zes/elastic/*`, `com/charging/astor/elastic/*`, `com/charging/zes/overpass/ZesStationEnrichmentService.java`). In Docker, ES is reached at `elasticsearch:9200`, so this must be configurable.

- Add helper `com.charging.elastic.EsConfig` with `public static org.apache.http.HttpHost host()` that reads env `ELASTICSEARCH_HOST` (default `localhost`) and `ELASTICSEARCH_PORT` (default `9200`).
- Mechanically replace `new HttpHost("localhost", 9200)` → `EsConfig.host()` in all ~22 sites (add `import com.charging.elastic.EsConfig;` where the class is outside that package). Localhost default preserves current local-dev behavior.

### 2. Externalize the ASTOR token (Java) — required (user-confirmed)
In `core-api/src/main/resources/application.properties:31`, replace the inline 4 KB token with:
```
astor.api.authorization=${ASTOR_API_AUTHORIZATION:}
```
Supply the real value via the container's env (from git-ignored `.env`).
> Note: the token is already in git history, so externalizing only protects future commits/images. Recommend **rotating the ASTOR token** with the provider if possible; history rewriting is out of scope.

### 3. Make ES host configurable (Python) — required
`intelligence/config.py:4`: `ES_HOST = os.environ.get("ES_HOST", "http://localhost:9200")` (add `import os`). Used by `intelligence/extract.py:21`.

### 4. Frontend — no code change
Relative paths (`/zes`, `/astor`, `/sarjet`) already work behind the nginx reverse proxy. The Vite dev proxy in `vite.config.js` stays as-is (dev only).

## New files to create

- **`core-api/Dockerfile`** — multi-stage: `maven:3.9-eclipse-temurin-17` build → `eclipse-temurin:17-jre` runtime. Run the boot jar; workdir `/app` (H2 writes `/app/data`); `JAVA_OPTS=-Xmx768m`.
- **`intelligence/Dockerfile`** — `python:3.10-slim`; `pip install -r requirements.txt`; copy code; `uvicorn app:app --host 0.0.0.0 --port 8001`.
- **`console/Dockerfile`** — stage 1 `node:22` `npm ci && npm run build`; stage 2 `nginx:alpine` serving `dist/` with the conf below. (This is the `web` image — it both serves the SPA and reverse-proxies.)
- **`console/nginx.conf`** — root `/usr/share/nginx/html`; `try_files $uri /index.html;` SPA fallback; `location /zes`, `/astor`, `/sarjet` → `proxy_pass http://tracker:8080;`; `location /ml/` → `proxy_pass http://pricing:8001/;` (strip prefix) for manual pipeline triggers.
- **`docker-compose.prod.yml`** (repo root) — services `elasticsearch` (single-node, `xpack.security.enabled=false`, `ES_JAVA_OPTS=-Xms1g -Xmx1g`, volume `es_data`), `tracker` (env `ELASTICSEARCH_HOST=elasticsearch`, `ASTOR_API_AUTHORIZATION`, volume `tracker_data:/app/data`), `pricing` (env `ES_HOST=http://elasticsearch:9200`, volume `ml_models:/app/models`), `web` (publish `80:80`, `depends_on` tracker/pricing). All `restart: unless-stopped`. Reuses the ES service shape from the existing `core-api/docker-compose.yml` (which stays for local dev).
- **`.env.example`** (committed) + **`.env`** (git-ignored) — `ASTOR_API_AUTHORIZATION=`, `ELASTICSEARCH_HOST=elasticsearch`, `ES_HOST=http://elasticsearch:9200`. Add `.env` to `.gitignore`.
- **`DEPLOY.md`** — GCP provisioning + operations runbook (below).
- **`deploy/daily-pipeline.sh`** + host cron entry — `curl` the Python `/pricing/run-all` and `/forecast/predict` daily (~00:30, after Java's 00:15 perf build) so pricing/forecast indices refresh automatically. Without this the ML outputs go stale; the Java daily crons keep running inside the app.

## GCP provisioning (in DEPLOY.md)

1. Create VM: `e2-standard-2` (2 vCPU / 8 GB — ES+JVM+Python need the headroom), Ubuntu 22.04 LTS, 50 GB disk, **auto-restart on** + **on host maintenance: migrate** (so it "runs no matter what").
2. Firewall: a tag-scoped rule allowing `tcp:80` and `tcp:22` **only from your IP/CIDR**; remove default broad ingress.
3. On the VM: install Docker Engine + compose plugin.
4. `git clone` the repo, create `.env` with the real ASTOR token, `docker compose -f docker-compose.prod.yml up -d --build`.
5. Install the daily-pipeline host cron.
6. (Reliability) Docker's `restart: unless-stopped` + VM auto-restart covers crash/reboot recovery. Note GCE persistent-disk **snapshot schedule** for backups of `es_data`.

## Verification (end-to-end)

1. `docker compose -f docker-compose.prod.yml ps` — all four containers `Up`; ES healthy (`curl -s localhost:9200/_cluster/health` inside the network).
2. From your IP, open `http://<vm-ip>/` — dashboard loads; confirm a non-asset deep path also returns the SPA (fallback works).
3. `curl http://<vm-ip>/zes/...` returns JSON from the Java backend (proxy works); a request from a non-allowlisted IP is refused (firewall works).
4. Trigger `POST /zes/poll-now` and confirm an ES index appears; confirm tracker logs show the 5-min scheduler firing.
5. `curl -XPOST http://<vm-ip>/ml/pricing/run-all?date=<today>` (or run `deploy/daily-pipeline.sh`) → confirm `zes_station_pricing-<date>` is written and the dashboard pricing page renders it.
6. Reboot the VM; confirm all containers come back automatically and data persists.

## Out of scope / follow-ups
- Domain + HTTPS/TLS (add later if sharing beyond your IP).
- ASTOR token rotation + scrubbing it from git history.
- Migrating H2 → Cloud SQL and model artifacts → GCS (not needed for single-VM; revisit if scaling out).
