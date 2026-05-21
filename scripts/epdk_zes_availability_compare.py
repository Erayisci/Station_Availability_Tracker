"""
EPDK snapshot vs ZES station — IN_USE / Charging uyum analizi.

Soru: EPDK IN_USE gösterirken ZES aynı connectorʼda Charging görüyor mu?

Eşleştirme:
  İstasyon  → Haversine < MAX_DISTANCE_M
  Connector → Aynı type grubu içinde power'a göre sıralı N:N pairing

Giriş:  ~/Downloads/dataa/HH:MM/  (snapshot klasörleri)
        zes_stations-2026-05-14   (ES index)
Çıktı:  scripts/output/comparison_YYYYMMDD_HHMM.json
"""

import json
import math
import os
import glob
import urllib.request
from datetime import datetime
from collections import defaultdict
from itertools import zip_longest

# ── Ayarlar ──────────────────────────────────────────────────────────────────
SNAPSHOTS_ROOT = os.path.expanduser("~/Downloads/dataa")
SNAPSHOT_DATE  = "2026-05-14"
ZES_OPERATOR   = "ZES DİJİTAL TİCARET ANONİM ŞİRKETİ"
ES_INDEX       = f"zes_stations-{SNAPSHOT_DATE}"
MAX_DISTANCE_M = 200
OUTPUT_DIR     = os.path.join(os.path.dirname(__file__), "output")

EPDK_TYPE_MAP = {
    "AC_TYPE2":   "AC_TYPE2",
    "DC_CCS":     "DC_CCS",
    "DC_CHADEMO": "DC_CHADEMO",
    "AC_TYPE1":   "AC_TYPE1",
}

ZES_TYPE_MAP = {
    "AC_Type_2":  "AC_TYPE2",
    "DC_CCS":     "DC_CCS",
    "DC_CHAdeMO": "DC_CHADEMO",
    "AC_Type_1":  "AC_TYPE1",
}


# ── Yardımcı fonksiyonlar ────────────────────────────────────────────────────
def haversine_m(lat1, lng1, lat2, lng2):
    R = 6_371_000
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlam = math.radians(lng2 - lng1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlam / 2) ** 2
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def parse_dt(s):
    if not s:
        return None
    for fmt in ("%Y-%m-%dT%H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S"):
        try:
            return datetime.strptime(s[:26], fmt)
        except ValueError:
            continue
    return None


def zes_status_at(availability, t):
    """ZES connectorʼın t anındaki raw statusʼunu döndürür."""
    active = None
    for a in availability:
        start = parse_dt(a.get("startTime"))
        if start is None or start > t:
            break
        end = parse_dt(a.get("endTime"))
        if end is None or end > t:
            active = a["status"]
    return active


def match_connectors(epdk_sockets_meta, zes_connectors):
    """
    EPDK soketlerini ZES connectorlarına eşleştir.

    Strateji: aynı type grubu içinde power'a göre büyükten küçüğe sırala,
    sonra index bazlı 1:1 eşleştir. Güce göre sıralama, aynı fiziksel
    chargerʼların karşılıklı gelmesini sağlar.

    Dönüş: {epdk_socket_id: zes_connector_dict veya None}
    """
    epdk_by_type = defaultdict(list)
    for sid, meta in epdk_sockets_meta.items():
        norm = EPDK_TYPE_MAP.get(meta.get("subType", ""), meta.get("subType", ""))
        epdk_by_type[norm].append((sid, meta))

    zes_by_type = defaultdict(list)
    for c in zes_connectors:
        zes_by_type[c["type"]].append(c)

    result = {}
    for typ, epdk_list in epdk_by_type.items():
        epdk_sorted = sorted(epdk_list, key=lambda x: x[1].get("power") or 0, reverse=True)
        zes_sorted  = sorted(zes_by_type.get(typ, []), key=lambda c: c["power"] or 0, reverse=True)

        for epdk_item, zes_c in zip_longest(epdk_sorted, zes_sorted):
            if epdk_item is None:
                continue
            sid, _ = epdk_item
            result[sid] = zes_c  # None → eşleşecek ZES connector bulunamadı
    return result


# ── ES yardımcıları ──────────────────────────────────────────────────────────
def es_request(method, path, body=None):
    url  = f"http://localhost:9200{path}"
    data = json.dumps(body).encode() if body else None
    req  = urllib.request.Request(url, data=data, method=method,
                                   headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())


def es_scroll_all(index):
    hits = []
    resp = es_request("POST", f"/{index}/_search?scroll=2m&size=500",
                       {"query": {"match_all": {}}})
    scroll_id = resp["_scroll_id"]
    hits.extend(resp["hits"]["hits"])
    while True:
        resp  = es_request("POST", "/_search/scroll",
                            {"scroll": "2m", "scroll_id": scroll_id})
        batch = resp["hits"]["hits"]
        if not batch:
            break
        hits.extend(batch)
        scroll_id = resp["_scroll_id"]
    try:
        es_request("DELETE", "/_search/scroll", {"scroll_id": scroll_id})
    except Exception:
        pass
    return hits


# ── 1. Snapshot klasörlerini tara ────────────────────────────────────────────
print("[1] Snapshot klasörleri taranıyor...")

snap_dirs      = sorted(glob.glob(os.path.join(SNAPSHOTS_ROOT, "*:*")))
snapshot_times = []
epdk_data      = {}   # epdk_id → {title, lat, lng, sockets: {sid: {snap_dt: raw_status}}}
socket_meta    = {}   # epdk_id → {sid: {subType, power}}

for snap_dir in snap_dirs:
    folder = os.path.basename(snap_dir)
    try:
        snap_dt = datetime.strptime(f"{SNAPSHOT_DATE}T{folder}", "%Y-%m-%dT%H:%M")
    except ValueError:
        continue
    snapshot_times.append(snap_dt)

    for path in glob.glob(os.path.join(snap_dir, "*.json")):
        with open(path, encoding="utf-8") as f:
            d = json.load(f)
        if d.get("operatortitle") != ZES_OPERATOR:
            continue

        eid = d["id"]
        if eid not in epdk_data:
            epdk_data[eid] = {
                "title":   d.get("title", ""),
                "lat":     d["lat"],
                "lng":     d["lng"],
                "sockets": defaultdict(dict),
            }
            socket_meta[eid] = {}

        for sock in d.get("sockets", []):
            sid   = sock["id"]
            avail = sock.get("availability", [])
            current_status = None
            for a in avail:
                start = parse_dt(a.get("startTime"))
                end   = parse_dt(a.get("endTime"))
                if start and start <= snap_dt:
                    if end is None or end > snap_dt:
                        current_status = a["status"]
            epdk_data[eid]["sockets"][sid][snap_dt] = current_status

            if sid not in socket_meta[eid]:
                socket_meta[eid][sid] = {
                    "subType": sock.get("subType", ""),
                    "power":   sock.get("power"),
                }

snapshot_times = sorted(set(snapshot_times))
print(f"    {len(snapshot_times)} snapshot zamanı: "
      f"{snapshot_times[0].strftime('%H:%M')} → {snapshot_times[-1].strftime('%H:%M')}")
print(f"    {len(epdk_data)} unique ZES istasyonu (EPDK)")


# ── 2. ES'ten ZES istasyonlarını çek ─────────────────────────────────────────
print(f"[2] ES index çekiliyor: {ES_INDEX}")
zes_stations = []
for hit in es_scroll_all(ES_INDEX):
    s          = hit["_source"]
    connectors = []
    for evse in s.get("evses", []):
        for c in evse.get("connectors", []):
            connectors.append({
                "connector_id": c["id"],
                "type":         ZES_TYPE_MAP.get(c.get("type", ""), c.get("type", "")),
                "power":        c.get("maxElectricPower"),
                "availability": sorted(
                    c.get("availability", []),
                    key=lambda a: a.get("startTime", "")
                ),
            })
    zes_stations.append({
        "zes_id":     s["id"],
        "name":       s.get("name", ""),
        "lat":        s["latitude"],
        "lng":        s["longitude"],
        "connectors": connectors,
    })
print(f"    {len(zes_stations)} ZES istasyonu çekildi.")


# ── 3. Geo-match + IN_USE / Charging karşılaştırması ─────────────────────────
print("[3] Eşleştirme ve karşılaştırma yapılıyor...")

results       = []
matched_count = 0
unmatched     = []

for eid, edata in epdk_data.items():
    best_dist, best_zes = float("inf"), None
    for zes in zes_stations:
        d = haversine_m(edata["lat"], edata["lng"], zes["lat"], zes["lng"])
        if d < best_dist:
            best_dist, best_zes = d, zes

    if best_dist > MAX_DISTANCE_M:
        unmatched.append({
            "epdk_id":        eid,
            "epdk_title":     edata["title"],
            "lat":            edata["lat"],
            "lng":            edata["lng"],
            "nearest_zes":    best_zes["name"] if best_zes else None,
            "nearest_dist_m": round(best_dist, 1),
        })
        continue

    matched_count += 1

    # Connector eşleştirmesi: type bazlı sıralı N:N pairing
    socket_to_zes = match_connectors(socket_meta[eid], best_zes["connectors"])

    connector_results = []

    for sid, time_statuses in edata["sockets"].items():
        meta      = socket_meta[eid].get(sid, {})
        epdk_type = EPDK_TYPE_MAP.get(meta.get("subType", ""), meta.get("subType", ""))
        zes_c     = socket_to_zes.get(sid)

        if zes_c is None:
            connector_results.append({
                "epdk_socket_id":   sid,
                "epdk_type":        meta.get("subType"),
                "epdk_power":       meta.get("power"),
                "zes_connector_id": None,
                "match_status":     "NO_CONNECTOR_MATCH",
            })
            continue

        # ── IN_USE / Charging timeslot analizi ───────────────────────────────
        # Her snapshot için: EPDK IN_USE muydu? ZES o anda Charging miydi?
        timepoints    = []
        inuse_count   = 0
        inuse_and_zes_charging = 0

        for snap_dt in snapshot_times:
            epdk_raw = time_statuses.get(snap_dt)
            zes_raw  = zes_status_at(zes_c["availability"], snap_dt)

            is_inuse      = epdk_raw == "IN_USE"
            is_zes_charge = zes_raw  == "Charging"

            if is_inuse:
                inuse_count += 1
                if is_zes_charge:
                    inuse_and_zes_charging += 1

            timepoints.append({
                "time":           snap_dt.strftime("%H:%M"),
                "epdk_raw":       epdk_raw,
                "zes_raw":        zes_raw,
                "epdk_in_use":    is_inuse,
                "zes_charging":   is_zes_charge,
                # True: EPDK IN_USE iken ZES de Charging
                "confirmed":      is_inuse and is_zes_charge,
                # True: EPDK IN_USE iken ZES Charging değil (tutarsızlık)
                "discrepancy":    is_inuse and not is_zes_charge,
            })

        # Agreement = IN_USE anlarında ZES Charging oranı
        charging_agreement_pct = (
            round(inuse_and_zes_charging / inuse_count * 100, 1)
            if inuse_count > 0 else None
        )

        connector_results.append({
            "epdk_socket_id":         sid,
            "epdk_type":              meta.get("subType"),
            "epdk_power":             meta.get("power"),
            "zes_connector_id":       zes_c["connector_id"],
            "zes_type":               zes_c["type"],
            "zes_power":              zes_c["power"],
            "inuse_snapshots":        inuse_count,
            "zes_charging_confirmed": inuse_and_zes_charging,
            "charging_agreement_pct": charging_agreement_pct,
            "timepoints":             timepoints,
        })

    results.append({
        "epdk_id":    eid,
        "epdk_title": edata["title"],
        "zes_id":     best_zes["zes_id"],
        "zes_name":   best_zes["name"],
        "distance_m": round(best_dist, 1),
        "connectors": connector_results,
    })


# ── 4. Özet & Çıktı ──────────────────────────────────────────────────────────
scored = [
    c["charging_agreement_pct"]
    for r in results
    for c in r["connectors"]
    if c.get("charging_agreement_pct") is not None
]
avg_agreement = round(sum(scored) / len(scored), 1) if scored else None

# IN_USE olan ama ZES Charging olmayan connector sayısı
discrepancy_connectors = sum(
    1 for r in results
    for c in r["connectors"]
    if c.get("inuse_snapshots", 0) > 0
    and c.get("charging_agreement_pct") is not None
    and c["charging_agreement_pct"] < 100.0
)

summary = {
    "snapshot_date":             SNAPSHOT_DATE,
    "snapshot_times":            [t.strftime("%H:%M") for t in snapshot_times],
    "epdk_zes_stations":         len(epdk_data),
    "matched_stations":          matched_count,
    "unmatched_epdk_stations":   len(unmatched),
    "connectors_with_inuse":     len(scored),
    "avg_charging_agreement_pct": avg_agreement,
    "fully_confirmed_connectors": sum(1 for a in scored if a == 100.0),
    "discrepancy_connectors":    discrepancy_connectors,
    "generated_at":              datetime.now().isoformat(),
}

output = {
    "summary":          summary,
    "matched_stations": results,
    "unmatched_epdk":   unmatched,
}

os.makedirs(OUTPUT_DIR, exist_ok=True)
ts       = datetime.now().strftime("%Y%m%d_%H%M")
out_path = os.path.join(OUTPUT_DIR, f"comparison_{ts}.json")
with open(out_path, "w", encoding="utf-8") as f:
    json.dump(output, f, ensure_ascii=False, indent=2)

# dashboard.html her zaman bu dosyayı yüklesin
latest_path = os.path.join(OUTPUT_DIR, "latest.json")
with open(latest_path, "w", encoding="utf-8") as f:
    json.dump(output, f, ensure_ascii=False, indent=2)

print()
print("══ ÖZET ══════════════════════════════════════════")
for k, v in summary.items():
    if k == "snapshot_times":
        print(f"  {'snapshot_times':<38} {v[0]} → {v[-1]} ({len(v)} nokta)")
    else:
        print(f"  {k:<38} {v}")
print(f"\n  Çıktı: {out_path}")
