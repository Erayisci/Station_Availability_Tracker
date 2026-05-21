"""
EPDK snapshot verilerinden epdk_zes_connectors ES index'ini oluşturur.

Kaynak  : ~/Downloads/dataa/HH:MM/*.json  (her klasör bir snapshot anı)
Filtre  : operatortitle == ZES_OPERATOR
Index   : epdk_zes_connectors-<SNAPSHOT_DATE>
Döküman : her (soket × snapshot_time) için bir kayıt
          → Kibana'da time field'ı üzerinden doğrudan sort/aggregate edilebilir
"""

import json
import os
import glob
import urllib.request
import urllib.error
from datetime import datetime

SNAPSHOTS_ROOT = os.path.expanduser("~/Downloads/dataa")
SNAPSHOT_DATE  = "2026-05-14"
ZES_OPERATOR   = "ZES DİJİTAL TİCARET ANONİM ŞİRKETİ"
ES_INDEX       = f"epdk_zes_connectors-{SNAPSHOT_DATE}"
BATCH_SIZE     = 500


def es_request(method, path, body=None):
    url  = f"http://localhost:9200{path}"
    data = json.dumps(body).encode() if body else None
    req  = urllib.request.Request(url, data=data, method=method,
                                   headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())


def parse_dt(s):
    if not s:
        return None
    for fmt in ("%Y-%m-%dT%H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S"):
        try:
            return datetime.strptime(s[:26], fmt)
        except ValueError:
            continue
    return None


# ── 1. Index oluştur ──────────────────────────────────────────────────────────
print(f"[1] Index oluşturuluyor: {ES_INDEX}")

mapping = {
    "settings": {"number_of_shards": 1, "number_of_replicas": 0},
    "mappings": {
        "properties": {
            "time":               {"type": "date"},
            "status":             {"type": "keyword"},
            "socket_id":          {"type": "keyword"},
            "socket_type":        {"type": "keyword"},
            "socket_power":       {"type": "float"},
            "epdk_station_id":    {"type": "keyword"},
            "epdk_station_title": {"type": "text", "fields": {"keyword": {"type": "keyword"}}},
            "location":           {"type": "geo_point"},
        }
    }
}

try:
    es_request("DELETE", f"/{ES_INDEX}")
    print("    Eski index silindi.")
except urllib.error.HTTPError:
    pass

es_request("PUT", f"/{ES_INDEX}", mapping)
print("    Index oluşturuldu.")


# ── 2. Snapshot klasörlerini oku ──────────────────────────────────────────────
print("[2] Snapshot'lar okunuyor...")

# sockets[socket_id] = {epdk_station_id, socket_type, socket_power, availability: {snap_dt: status}}
sockets        = {}
stations       = {}   # station_id → {title, lat, lng}
snapshot_times = []

snap_dirs = sorted(glob.glob(os.path.join(SNAPSHOTS_ROOT, "*:*")))

for snap_dir in snap_dirs:
    folder = os.path.basename(snap_dir)
    try:
        snap_dt = datetime.strptime(f"{SNAPSHOT_DATE}T{folder}", "%Y-%m-%dT%H:%M")
    except ValueError:
        continue
    snapshot_times.append(snap_dt)

    for path in glob.glob(os.path.join(snap_dir, "*.json")):
        try:
            with open(path, encoding="utf-8") as f:
                d = json.load(f)
        except (json.JSONDecodeError, UnicodeDecodeError):
            continue
        if d.get("operatortitle") != ZES_OPERATOR:
            continue

        sid = str(d["id"])
        if sid not in stations:
            stations[sid] = {
                "title": d.get("title", ""),
                "lat":   d["lat"],
                "lng":   d["lng"],
            }

        for sock in d.get("sockets", []):
            sock_id = str(sock["id"])
            if sock_id not in sockets:
                sockets[sock_id] = {
                    "epdk_station_id": sid,
                    "socket_type":     sock.get("subType", ""),
                    "socket_power":    sock.get("power"),
                    "availability":    {},
                }

            avail = sock.get("availability", [])
            current = None
            for a in avail:
                start = parse_dt(a.get("startTime"))
                end   = parse_dt(a.get("endTime"))
                if start and start <= snap_dt:
                    if end is None or end > snap_dt:
                        current = a["status"]
            sockets[sock_id]["availability"][snap_dt] = current

snapshot_times = sorted(set(snapshot_times))
print(f"    {len(snapshot_times)} snapshot zamanı: "
      f"{snapshot_times[0].strftime('%H:%M')} → {snapshot_times[-1].strftime('%H:%M')}")
print(f"    {len(stations)} istasyon, {len(sockets)} soket — "
      f"toplam ~{len(sockets) * len(snapshot_times):,} döküman yazılacak.")


# ── 3. Bulk index ─────────────────────────────────────────────────────────────
print("[3] ES'e yazılıyor...")

def bulk_index(docs):
    lines = []
    for doc in docs:
        lines.append(json.dumps({"index": {"_index": ES_INDEX}}))
        lines.append(json.dumps(doc, ensure_ascii=False))
    body = "\n".join(lines) + "\n"
    req  = urllib.request.Request(
        f"http://localhost:9200/_bulk",
        data=body.encode("utf-8"), method="POST",
        headers={"Content-Type": "application/x-ndjson"}
    )
    with urllib.request.urlopen(req) as resp:
        result = json.loads(resp.read())
    if result.get("errors"):
        errs = [i["index"]["error"] for i in result["items"] if "error" in i.get("index", {})]
        print(f"    UYARI: {len(errs)} hata — {errs[0]}")


batch = []
total = 0

for sock_id, sock in sockets.items():
    station = stations.get(sock["epdk_station_id"], {})
    base = {
        "socket_id":          sock_id,
        "socket_type":        sock["socket_type"],
        "socket_power":       sock["socket_power"],
        "epdk_station_id":    sock["epdk_station_id"],
        "epdk_station_title": station.get("title", ""),
        "location": {
            "lat": station.get("lat"),
            "lon": station.get("lng"),
        },
    }

    for snap_dt in snapshot_times:
        doc = {
            **base,
            "time":   snap_dt.strftime("%Y-%m-%dT%H:%M:%S"),
            "status": sock["availability"].get(snap_dt),
        }
        batch.append(doc)
        total += 1

        if len(batch) >= BATCH_SIZE:
            bulk_index(batch)
            print(f"    {total:,} döküman yazıldı…", end="\r")
            batch = []

if batch:
    bulk_index(batch)

print(f"    {total:,} döküman index'lendi.              ")

# ── 4. Özet ───────────────────────────────────────────────────────────────────
count = es_request("GET", f"/{ES_INDEX}/_count")["count"]
print()
print("══ ÖZET ══════════════════════════════════════════")
print(f"  Index              {ES_INDEX}")
print(f"  Toplam döküman     {count:,}")
print(f"  Snapshot aralığı   {snapshot_times[0].strftime('%H:%M')} → "
      f"{snapshot_times[-1].strftime('%H:%M')}  ({len(snapshot_times)} nokta)")
