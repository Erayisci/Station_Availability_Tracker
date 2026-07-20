"""
Tüm-marka EPDK istasyon KONUM kaydı (tek snapshot'tan, marka filtresi YOK).

Amaç: lokasyonel arz/rekabet yoğunluğu hesabı için Türkiye genelindeki TÜM şarj
istasyonlarının (164 operatör) coğrafi kaydı. İstasyon konumları statik olduğu
için tek bir snapshot anı yeterlidir.

Kaynak  : ~/Downloads/dataa/HH:MM/*.json  (varsayılan: ilk klasör)  — her dosya bir istasyon
Filtre  : YOK (index_epdk_zes_connectors.py'nin aksine tüm operatörler alınır)
Index   : epdk_all_stations
Döküman : her istasyon için bir kayıt — { epdk_station_id, location, operator_title,
          brand, title, is_zes }   (sadece konum + kimlik; soket/zaman serisi YOK)

Kullanım:
    python3 scripts/index_epdk_all_stations.py [SNAPSHOT_FOLDER]
"""

import json
import os
import glob
import sys
import urllib.request
import urllib.error

ES_INDEX     = "epdk_all_stations"
ZES_OPERATOR = "ZES DİJİTAL TİCARET ANONİM ŞİRKETİ"
BATCH_SIZE   = 1000


def es_request(method, path, body=None, ndjson=False):
    url = f"http://localhost:9200{path}"
    if ndjson:
        data = body.encode("utf-8")
        ctype = "application/x-ndjson"
    else:
        data = json.dumps(body).encode() if body is not None else None
        ctype = "application/json"
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": ctype})
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())


# ── 0. Snapshot klasörünü seç ─────────────────────────────────────────────────
if len(sys.argv) > 1:
    folder = sys.argv[1]
else:
    dirs = sorted(glob.glob(os.path.expanduser("~/Downloads/dataa/*/")))
    if not dirs:
        sys.exit("Snapshot klasörü bulunamadı: ~/Downloads/dataa/*/")
    folder = dirs[0]
print(f"[0] Snapshot klasörü: {folder}")


# ── 1. Index oluştur ──────────────────────────────────────────────────────────
mapping = {
    "settings": {"number_of_shards": 1, "number_of_replicas": 0},
    "mappings": {
        "properties": {
            "epdk_station_id": {"type": "keyword"},
            "operator_title":  {"type": "keyword"},
            "brand":           {"type": "keyword"},
            "title":           {"type": "text", "fields": {"keyword": {"type": "keyword"}}},
            "is_zes":          {"type": "boolean"},
            "location":        {"type": "geo_point"},
        }
    }
}
try:
    es_request("DELETE", f"/{ES_INDEX}")
    print(f"[1] Eski index silindi: {ES_INDEX}")
except urllib.error.HTTPError:
    pass
es_request("PUT", f"/{ES_INDEX}", mapping)
print(f"[1] Index oluşturuldu: {ES_INDEX}")


# ── 2. İstasyonları oku (dedupe by id, geçersiz koordinatları atla) ───────────
stations = {}   # id -> doc
skipped_coords = 0
for path in glob.glob(os.path.join(folder, "*.json")):
    try:
        with open(path, encoding="utf-8") as f:
            d = json.load(f)
    except (json.JSONDecodeError, UnicodeDecodeError):
        continue
    sid = str(d.get("id"))
    if sid in stations:
        continue
    try:
        lat = float(d["lat"]); lng = float(d["lng"])
    except (TypeError, ValueError, KeyError):
        skipped_coords += 1
        continue
    op = d.get("operatortitle", "")
    stations[sid] = {
        "epdk_station_id": sid,
        "operator_title":  op,
        "brand":           d.get("brand", ""),
        "title":           d.get("title", ""),
        "is_zes":          op == ZES_OPERATOR,
        "location":        {"lat": lat, "lon": lng},
    }

n_zes = sum(1 for s in stations.values() if s["is_zes"])
print(f"[2] {len(stations)} istasyon ({n_zes} ZES, {len(stations) - n_zes} diğer marka), "
      f"{skipped_coords} geçersiz koordinat atlandı.")


# ── 3. Bulk index ─────────────────────────────────────────────────────────────
def bulk_index(docs):
    lines = []
    for doc in docs:
        lines.append(json.dumps({"index": {"_index": ES_INDEX, "_id": doc["epdk_station_id"]}}))
        lines.append(json.dumps(doc, ensure_ascii=False))
    res = es_request("POST", "/_bulk", "\n".join(lines) + "\n", ndjson=True)
    if res.get("errors"):
        errs = [i["index"]["error"] for i in res["items"] if "error" in i.get("index", {})]
        print(f"    UYARI: {len(errs)} hata — {errs[0]}")


batch, total = [], 0
for doc in stations.values():
    batch.append(doc)
    total += 1
    if len(batch) >= BATCH_SIZE:
        bulk_index(batch); batch = []
        print(f"    {total:,} yazıldı…", end="\r")
if batch:
    bulk_index(batch)

es_request("POST", f"/{ES_INDEX}/_refresh")
count = es_request("GET", f"/{ES_INDEX}/_count")["count"]
print(f"\n══ ÖZET ════════════════════════════════")
print(f"  Index            {ES_INDEX}")
print(f"  Toplam istasyon  {count:,}  ({n_zes} ZES)")
