"""
EPDK resmi API'sinden TÜM şarj istasyonlarının KONUM kaydı (tüm markalar, gerçek koordinat).

Kaynak : https://apigateway.epdk.gov.tr/sarjIstasyonlari   (GET, body={} — TokenSigner
         EpdkApiService.java ile aynı mantık; SSL doğrulaması kapalı, auth header yok)
Yanıt  : { statusCode, numRows, data: [ { sarjIstasyonuNo, sarjIstasyonuAdi, marka,
         adres, enlem, boylam, hizmetSekli, sarjAgiIsletmecisiUnvan,
         sarjIstasyonuIsletmecisi, ... } ] }
Index  : epdk_all_stations  — her istasyon bir kayıt, _id = sarjIstasyonuNo (ör. "ŞRJ/9055"),
         source="epdk". enlem/boylam doğrudan geo_point olur (geocoding YOK).

Not    : API gateway'de KOTA limiti var; tükenince HTTP 429 "ERR-227" döner. O durumda
         --cache <dosya.json> ile daha önce kaydedilmiş yanıt okunur. Başarılı çekimde
         yanıt otomatik olarak cache dosyasına yazılır (Java'daki gibi).

Kullanım:
    python3 scripts/index_epdk_api_stations.py [--cache stations-cache.json]
                                               [--save-only] [--dry-run]
"""

import json
import os
import ssl
import sys
import urllib.error
import urllib.request

API_URL      = "https://apigateway.epdk.gov.tr/sarjIstasyonlari"
ES_INDEX     = "epdk_all_stations"
ZES_OPERATOR = "ZES DİJİTAL TİCARET ANONİM ŞİRKETİ"
BATCH_SIZE   = 1000
CACHE_FILE   = os.path.join(os.path.dirname(__file__), "stations-cache.json")


# ── ES yardımcısı ─────────────────────────────────────────────────────────────
def es_request(method, path, body=None, ndjson=False):
    url = f"http://localhost:9200{path}"
    if ndjson:
        data, ctype = body.encode("utf-8"), "application/x-ndjson"
    else:
        data = json.dumps(body).encode() if body is not None else None
        ctype = "application/json"
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": ctype})
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())


# ── EPDK API çağrısı (Java EpdkApiService.fetchFromApi mantığı) ────────────────
def fetch_from_api():
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    req = urllib.request.Request(
        API_URL, data=b"{}", method="GET",
        headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60, context=ctx) as r:
            body = r.read().decode("utf-8")
            status = r.status
    except urllib.error.HTTPError as e:
        body, status = e.read().decode("utf-8", "replace"), e.code
    except urllib.error.URLError as e:
        print(f"    API çağrısı başarısız: {e}")
        return None

    if status != 200:
        snippet = body[:200].replace("\n", " ")
        print(f"    EPDK API HTTP {status} — {snippet}")
        return None
    try:
        payload = json.loads(body)
    except json.JSONDecodeError:
        print("    API 200 döndü fakat JSON parse edilemedi")
        return None
    if "fault" in payload:
        print(f"    API fault: {payload['fault'].get('faultString')}")
        return None
    data = payload.get("data") or []
    print(f"    EPDK API'den {len(data)} istasyon çekildi")
    return payload


def load_cache(path):
    if not os.path.exists(path):
        print(f"    Cache dosyası yok: {path}")
        return None
    with open(path, encoding="utf-8") as f:
        payload = json.load(f)
    print(f"    Cache'den {len(payload.get('data') or [])} istasyon yüklendi: {path}")
    return payload


def save_cache(payload, path):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    print(f"    Cache yazıldı: {path}")


# ── Doküman kurulumu ──────────────────────────────────────────────────────────
def build_doc(s):
    try:
        lat = float(s["enlem"]); lon = float(s["boylam"])
        location = {"lat": lat, "lon": lon}
    except (TypeError, ValueError, KeyError):
        location = None
    brand = (s.get("marka") or "")
    network = (s.get("sarjAgiIsletmecisiUnvan") or "")
    station_op = (s.get("sarjIstasyonuIsletmecisi") or "")
    is_zes = "ZES" in brand.upper() or "ZES DİJİTAL" in f"{network} {station_op}".upper()
    doc = {
        "epdk_station_id":  s.get("sarjIstasyonuNo") or "",
        "title":            s.get("sarjIstasyonuAdi") or "",
        "brand":            brand,
        "service_type":     s.get("hizmetSekli") or "",
        "network_operator": network,
        "station_operator": station_op,
        "address":          s.get("adres") or "",
        "is_green":         s.get("yesilSarjIstasyonuMu") or "",
        "is_zes":           is_zes,
        "source":           "epdk",
    }
    if location:
        doc["location"] = location
    return doc, is_zes, location is not None


def ensure_mapping():
    try:
        es_request("GET", f"/{ES_INDEX}")
    except urllib.error.HTTPError:
        es_request("PUT", f"/{ES_INDEX}", {
            "settings": {"number_of_shards": 1, "number_of_replicas": 0},
            "mappings": {"properties": {
                "epdk_station_id": {"type": "keyword"},
                "title":           {"type": "text", "fields": {"keyword": {"type": "keyword"}}},
                "brand":           {"type": "keyword"},
                "is_zes":          {"type": "boolean"},
                "location":        {"type": "geo_point"},
            }},
        })
    es_request("PUT", f"/{ES_INDEX}/_mapping", {"properties": {
        "address":          {"type": "text"},
        "service_type":     {"type": "keyword"},
        "network_operator": {"type": "keyword"},
        "station_operator": {"type": "keyword"},
        "is_green":         {"type": "keyword"},
        "source":           {"type": "keyword"},
    }})


def bulk_index(batch):
    lines = []
    for d in batch:
        _id = d["epdk_station_id"] or None
        meta = {"index": {"_index": ES_INDEX}}
        if _id:
            meta["index"]["_id"] = _id
        lines.append(json.dumps(meta))
        lines.append(json.dumps(d, ensure_ascii=False))
    res = es_request("POST", "/_bulk", "\n".join(lines) + "\n", ndjson=True)
    if res.get("errors"):
        errs = [i["index"]["error"] for i in res["items"] if "error" in i.get("index", {})]
        print(f"    UYARI: {len(errs)} hata — {errs[0]}")


# ── main ──────────────────────────────────────────────────────────────────────
def main():
    argv = sys.argv[1:]
    dry_run   = "--dry-run" in argv
    save_only = "--save-only" in argv
    cache = CACHE_FILE
    if "--cache" in argv:
        cache = argv[argv.index("--cache") + 1]

    # 1. Veri al: önce API, olmazsa cache
    print("[1] EPDK API çağrılıyor…")
    payload = fetch_from_api()
    if payload:
        save_cache(payload, cache)
    else:
        print("[1] API başarısız — cache'e başvuruluyor")
        payload = load_cache(cache)
    if not payload or not payload.get("data"):
        sys.exit("Veri alınamadı (API kotası dolu olabilir, cache de yok).")

    stations = payload["data"]
    print(f"[1] {len(stations)} istasyon")
    if save_only:
        print("[save-only] yalnızca cache yazıldı, index'lenmedi.")
        return

    # 2. Doküman kur
    docs, n_zes, n_loc = [], 0, 0
    for s in stations:
        doc, is_zes, has_loc = build_doc(s)
        docs.append(doc); n_zes += is_zes; n_loc += has_loc
    print(f"[2] {len(docs)} doküman  ({n_zes} ZES, {n_loc} koordinatlı, "
          f"{len(docs) - n_loc} koordinatsız)")

    if dry_run:
        print("[dry-run] örnek doküman:")
        print(json.dumps(docs[0], ensure_ascii=False, indent=2))
        return

    # 3. Mapping + bulk index
    ensure_mapping()
    batch = []
    for i, d in enumerate(docs, 1):
        batch.append(d)
        if len(batch) >= BATCH_SIZE:
            bulk_index(batch); batch = []
            print(f"    {i:,} yazıldı…", end="\r")
    if batch:
        bulk_index(batch)

    es_request("POST", f"/{ES_INDEX}/_refresh")
    total = es_request("GET", f"/{ES_INDEX}/_count")["count"]
    print(f"\n══ ÖZET ════════════════════════════════")
    print(f"  EPDK istasyon   {len(docs):,}  ({n_zes} ZES)")
    print(f"  Koordinatlı     {n_loc:,}")
    print(f"  Index toplam    {total:,}  (sarj + epdk birlikte)")


if __name__ == "__main__":
    main()
