# DEPLOY — ZES Platform on Google Cloud (always-on VM)

Deploys the full stack (Elasticsearch + Java tracker + Python ML + nginx/SPA) to a
single always-on GCE VM via Docker Compose. It survives reboots/crashes and is
reachable only from your IP.

- **What runs continuously:** the Java tracker polls ZES status every 5 min and runs
  its daily crons (23:59 finalize, 00:15 perf build) inside the container; a host cron
  refreshes the ML pricing/forecast indices daily at 00:30.
- **Access:** plain HTTP on port 80 at the VM's public IP, firewalled to your IP only.
  No domain/TLS (single-user). Add TLS later if you share it more widely.

---

## 0. One-time local prep

```bash
gcloud version   # install from https://cloud.google.com/sdk/docs/install if missing
gcloud auth login
```

Find your public IP (for the firewall rule): `curl -s ifconfig.me`

---

## 1. Project (you already have billing)

```bash
PROJECT_ID="zes-prod-$(date +%s)"        # must be globally unique
gcloud projects create "$PROJECT_ID"

# Link to your existing billing account:
gcloud billing accounts list                                   # copy the ACCOUNT_ID
gcloud billing projects link "$PROJECT_ID" --billing-account=<ACCOUNT_ID>

gcloud config set project "$PROJECT_ID"
gcloud services enable compute.googleapis.com
```

---

## 2. Firewall — your IP only

```bash
MY_IP="$(curl -s ifconfig.me)/32"

# Remove the broad default SSH rule if present (optional hardening).
# Allow HTTP + SSH only from your IP, scoped to the `zes` network tag:
gcloud compute firewall-rules create zes-allow-me \
  --direction=INGRESS --action=ALLOW \
  --rules=tcp:80,tcp:22 \
  --source-ranges="$MY_IP" \
  --target-tags=zes
```

> If your ISP IP changes, update it: `gcloud compute firewall-rules update zes-allow-me --source-ranges="$(curl -s ifconfig.me)/32"`

---

## 3. Create the VM

`e2-standard-2` (2 vCPU / 8 GB) — ES + JVM + Python need the headroom. Auto-restart on;
migrate on host maintenance → "runs no matter what".

```bash
ZONE="europe-west1-b"   # pick one close to you
gcloud compute instances create zes-vm \
  --zone="$ZONE" \
  --machine-type=e2-standard-2 \
  --image-family=ubuntu-2204-lts --image-project=ubuntu-os-cloud \
  --boot-disk-size=50GB --boot-disk-type=pd-balanced \
  --tags=zes \
  --maintenance-policy=MIGRATE \
  --restart-on-failure
```

> Doğrulama: `gcloud compute instances list` — `zes-vm` RUNNING görünmeli.

Get the public IP:
```bash
gcloud compute instances describe zes-vm --zone="$ZONE" \
  --format='get(networkInterfaces[0].accessConfigs[0].natIP)'
```

SSH in:
```bash
gcloud compute ssh zes-vm --zone="$ZONE"
```

---

## 4. Install Docker on the VM

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl git
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo tee /etc/apt/keyrings/docker.asc >/dev/null
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker "$USER"   # then log out/in (or run docker with sudo)
```

**Elasticsearch needs raised `vm.max_map_count`:**
```bash
echo 'vm.max_map_count=262144' | sudo tee /etc/sysctl.d/99-es.conf
sudo sysctl --system
```

---

## 5. Clone + configure + launch

The repo is **private**, and GitHub no longer accepts an account password over HTTPS.
Give the VM a **read-only deploy key** (repo-scoped, no expiry, `git pull` keeps working):

```bash
# --- on the VM ---
ssh-keygen -t ed25519 -C "zes-vm" -f ~/.ssh/id_ed25519 -N ""
cat ~/.ssh/id_ed25519.pub
```

Copy that public key → GitHub repo → **Settings → Deploy keys → Add deploy key**
(paste it, leave "Allow write access" **unchecked**). Then:

```bash
ssh -T git@github.com          # accept the host fingerprint; "successfully authenticated" is expected
git clone git@github.com:Erayisci/Station_Availability_Tracker.git charging
cd charging

cp .env.example .env
nano .env          # set ASTOR_API_AUTHORIZATION (or leave blank to disable ASTOR)

docker compose -f docker-compose.prod.yml up -d --build
```

First build takes a few minutes (Maven + npm). Check status:
```bash
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f tracker   # watch the 5-min poller
```

---

## 6. Daily ML refresh (host cron)

The Java daily crons run inside the container. Add a host cron for the Python pipeline:

```bash
crontab -e
# add (adjust the repo path):
30 0 * * *  /home/$USER/charging/deploy/daily-pipeline.sh >> /var/log/zes-pipeline.log 2>&1
```

> The ML pipeline needs a trained model + accumulated ZES data first. On a fresh deploy,
> let the tracker poll for a while, then train once manually:
> `curl -X POST http://localhost/ml/train` and `curl -X POST http://localhost/ml/forecast/train`
> before the daily refresh produces meaningful output.

---

## 7. Backups — ES data disk snapshots

```bash
gcloud compute resource-policies create snapshot-schedule zes-daily-snap \
  --region=europe-west1 --max-retention-days=14 \
  --start-time=02:00 --daily-schedule
gcloud compute disks add-resource-policies zes-vm \
  --zone="$ZONE" --resource-policies=zes-daily-snap
```

---

## 8. Verify (end-to-end)

1. `docker compose -f docker-compose.prod.yml ps` → all 4 up; ES healthy.
2. From your IP: open `http://<VM_IP>/` — dashboard loads; a deep SPA route also loads
   (fallback works). From another network → connection refused (firewall works).
3. `curl http://<VM_IP>/zes/...` returns JSON (proxy works).
4. `curl -X POST http://<VM_IP>/zes/poll-now` → a ZES index appears; tracker logs show
   the scheduler firing.
5. `curl -X POST "http://<VM_IP>/ml/pricing/run-all?date=$(date +%F)"` → writes the
   pricing index; the dashboard pricing page renders it.
6. Reboot: `gcloud compute instances reset zes-vm --zone="$ZONE"` → all containers
   auto-return and data persists.

---

## Operations cheatsheet

| Task | Command (on the VM, in repo dir) |
|------|----------------------------------|
| Update code | `git pull && docker compose -f docker-compose.prod.yml up -d --build` |
| Restart one service | `docker compose -f docker-compose.prod.yml restart tracker` |
| Tail logs | `docker compose -f docker-compose.prod.yml logs -f tracker` |
| Stop all | `docker compose -f docker-compose.prod.yml down` (keeps volumes) |
| ES health | `docker exec es curl -s localhost:9200/_cluster/health` |

**Rough cost:** ~US $50–70/mo (always-on `e2-standard-2` + 50 GB disk, region-dependent).

## Follow-ups (out of scope here)
- Domain + HTTPS/TLS (Caddy/Let's Encrypt) if sharing beyond your IP.
- Rotate the ASTOR token and scrub it from git history.
- Migrate H2 → Cloud SQL and models → GCS if you outgrow a single VM.
