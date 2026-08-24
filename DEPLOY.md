# DEPLOY — ZES Platform on Hetzner Cloud (always-on VM)

Deploys the full stack (Elasticsearch + core-api + intelligence + nginx/SPA) to a
single always-on Hetzner CX22 via Docker Compose. It survives reboots/crashes and is
reachable only from your IP.

- **What runs continuously:** `core-api` polls ZES status every 5 min and runs
  its daily crons (23:59 finalize, 00:15 perf build) inside the container; a host cron
  refreshes the ML pricing/forecast indices daily at 00:30.
- **Access:** plain HTTP on port 80 at the server's public IP, firewalled to your IP only.
  No domain/TLS (single-user). Add TLS later if you share it more widely.
- **Sizing:** CX22 is 2 vCPU / 4 GB / 40 GB NVMe. The stack fits because every heap is
  capped: ES 768m, core-api 768m (`JAVA_OPTS` in its Dockerfile), intelligence ~500m.
  Measured footprint is small — H2 holds 7 days (`zes.history.keepDays`) in ~33 MB, and
  the daily ES indices for ~1,800 stations grow far slower than 40 GB/year.

> Why not GCE: an always-on `e2-standard-2` + 50 GB disk runs ~US $50–60/mo for the same
> workload. Spot instances are ~65% cheaper but wrong here — a preemption during the 23:59
> finalize loses that day permanently, since H2 is authoritative and only keeps 7 days.

---

## 0. One-time local prep

Create a Hetzner Cloud account at <https://console.hetzner.cloud>, then a **project**
(e.g. `zes-prod`). Two ways to drive it — pick one:

**Web console** (no tooling): do steps 1–2 by clicking, then SSH in and continue at step 3.

**`hcloud` CLI** (scriptable, used below):
```bash
# https://github.com/hetznercloud/cli/releases — grab the linux-amd64 tarball
curl -fsSL https://github.com/hetznercloud/cli/releases/latest/download/hcloud-linux-amd64.tar.gz \
  | tar xz -C /tmp && sudo install /tmp/hcloud /usr/local/bin/

# Console → Security → API tokens → generate a Read & Write token, then:
hcloud context create zes-prod     # paste the token when prompted
```

Upload your SSH public key once so new servers trust it:
```bash
hcloud ssh-key create --name eray --public-key-from-file ~/.ssh/id_ed25519.pub
```

Find your public IP (for the firewall rule): `curl -s ifconfig.me`

---

## 1. Create the server

```bash
hcloud server create \
  --name zes-vm \
  --type cx22 \
  --image ubuntu-22.04 \
  --location nbg1 \
  --ssh-key eray
```

> `nbg1` = Nuremberg. `fsn1` (Falkenstein) and `hel1` (Helsinki) are equivalent in price;
> Hetzner has no Turkish region, so all three are ~40–60 ms from TR.

> Doğrulama: `hcloud server list` — `zes-vm` `running` görünmeli.

Get the public IP and SSH in:
```bash
IP=$(hcloud server ip zes-vm)
ssh root@"$IP"
```

---

## 2. Firewall — your IP only

Hetzner Cloud Firewalls are free and sit in front of the NIC, so this is the real
perimeter (nothing but `console` publishes a port anyway).

```bash
MY_IP="$(curl -s ifconfig.me)/32"

hcloud firewall create --name zes-allow-me
hcloud firewall add-rule zes-allow-me --direction in --protocol tcp --port 22 --source-ips "$MY_IP"
hcloud firewall add-rule zes-allow-me --direction in --protocol tcp --port 80 --source-ips "$MY_IP"
hcloud firewall apply-to-resource zes-allow-me --type server --server zes-vm
```

> If your ISP IP changes, replace the rules:
> `hcloud firewall delete-rule zes-allow-me --direction in --protocol tcp --port 80 --source-ips <old>`
> then re-add with the new one.

---

## 3. Prepare the OS (swap + kernel setting)

**Swap** — 4 GB is comfortable but not roomy; a 2 GB swapfile turns a transient spike
(an ML run overlapping a big aggregation) into slowness instead of an OOM kill.
```bash
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
sysctl -w vm.swappiness=10 && echo 'vm.swappiness=10' > /etc/sysctl.d/99-swap.conf
```

**Elasticsearch needs raised `vm.max_map_count`:**
```bash
echo 'vm.max_map_count=262144' > /etc/sysctl.d/99-es.conf
sysctl --system
```

---

## 4. Install Docker

```bash
apt-get update
apt-get install -y ca-certificates curl git
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | tee /etc/apt/keyrings/docker.asc >/dev/null
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" | \
  tee /etc/apt/sources.list.d/docker.list >/dev/null
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

---

## 5. Clone + configure + launch

The repo is **private**, and GitHub no longer accepts an account password over HTTPS.
Give the server a **read-only deploy key** (repo-scoped, no expiry, `git pull` keeps working):

```bash
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
nano .env          # defaults match the compose service names; usually no edit needed

docker compose -f docker-compose.prod.yml up -d --build
```

First build takes several minutes (Maven + npm) and is the peak memory moment on a
4 GB box — the swapfile from step 3 covers it. Check status:
```bash
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f core-api   # watch the 5-min poller
```

---

## 6. Daily ML refresh (host cron)

The Java daily crons run inside the container. Add a host cron for the Python pipeline:

```bash
crontab -e
# add:
30 0 * * *  /root/charging/deploy/daily-pipeline.sh >> /var/log/zes-pipeline.log 2>&1
```

> The ML pipeline needs a trained model + accumulated ZES data first. On a fresh deploy,
> let core-api poll for a while, then train once manually:
> `curl -X POST http://localhost/ml/train` and `curl -X POST http://localhost/ml/forecast/train`
> before the daily refresh produces meaningful output. Until then the console's
> intelligence pages render empty — that is expected, not a failure.

---

## 7. Backups

Hetzner has two mechanisms. **Automatic backups** are the cheap, hands-off one:

```bash
hcloud server enable-backup zes-vm     # +20% of server price, 7 daily slots, whole-disk
```

Manual snapshot before a risky change:
```bash
hcloud server create-image zes-vm --type snapshot --description "pre-upgrade $(date +%F)"
```

> Backups image the whole disk, so ES data, H2 and trained models are all covered —
> but they are crash-consistent, not quiesced. For a clean ES copy, take it while the
> stack is stopped (`docker compose ... down`), or accept that a restore may need
> `docker compose ... up` to replay a partially-written shard.

---

## 8. Verify (end-to-end)

1. `docker compose -f docker-compose.prod.yml ps` → all 4 up; ES healthy.
2. `free -h` → ES + JVM resident well under 4 GB, swap barely touched at idle.
3. From your IP: open `http://<IP>/` — dashboard loads; a deep SPA route also loads
   (fallback works). From another network → connection refused (firewall works).
4. `curl http://<IP>/zes/...` returns JSON (proxy works).
5. `curl -X POST http://<IP>/zes/poll-now` → a ZES index appears; core-api logs show
   the scheduler firing.
6. `curl -X POST "http://<IP>/ml/pricing/run-all?date=$(date +%F)"` → writes the
   pricing index; the dashboard pricing page renders it.
7. Reboot: `hcloud server reboot zes-vm` → all containers auto-return and data persists.

---

## Operations cheatsheet

| Task | Command (on the server, in repo dir) |
|------|--------------------------------------|
| Update code | `git pull && docker compose -f docker-compose.prod.yml up -d --build` |
| Restart one service | `docker compose -f docker-compose.prod.yml restart core-api` |
| Tail logs | `docker compose -f docker-compose.prod.yml logs -f core-api` |
| Stop all | `docker compose -f docker-compose.prod.yml down` (keeps volumes) |
| ES health | `docker exec es curl -s localhost:9200/_cluster/health` |
| Memory check | `docker stats --no-stream` |

**Rough cost:** ~€4.3/mo — CX22 €3.79 + primary IPv4 €0.50. Automatic backups add ~€0.76.
Hetzner bills hourly with a monthly cap, so a destroyed server stops costing immediately.

## Follow-ups (out of scope here)
- Domain + HTTPS/TLS (Caddy/Let's Encrypt) if sharing beyond your IP.
- If the 40 GB disk fills, attach a Hetzner Volume and move the `es_data` bind target
  onto it — no re-architecture needed.
