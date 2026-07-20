"""
EPDK EPVYS resmi şarj istasyonu listesinden KONUM kaydı (tüm markalar).

Kaynak sayfa (reCAPTCHA korumalı, otomatik sorgulanamaz):
  https://lisans.epdk.gov.tr/epvys-web/faces/pages/lisans/elektrikSarjAgiIsletmeci/
  sarjIstasyonuOzetSorgula.xhtml

Akış:
  1. Sayfada 'Ben robot değilim' işaretlenip sorgu çalıştırılır (gerekirse il bazında
     ya da satır/sayfa sayısı büyütülerek). Sonuç tablosu görününce tarayıcıdan
     "Sayfayı Kaydet → Yalnızca HTML" ile kaydedilir. Birden çok dosya (il/sayfa
     başına) verilebilir; istasyon no'ya göre tekilleştirilir.
  2. Bu script kaydedilen HTML'(ler)i parse eder. PrimeFaces datatable kolon sırası:
       0 İstasyon No  1 İstasyon Adı  2 Hizmet Şekli  3 Marka
       4 Şarj Ağı İşletmecisi  5 Şarj İstasyonu İşletmecisi  6 Adres  7 Soket Bilgileri
  3. Koordinat: EPDK'da lat/lon YOK. Önce sarj.com.tr snapshot'ından (index_epdk_
     all_stations.py ile aynı kaynak) istasyon adı + marka eşleşmesiyle GERÇEK koordinat
     yeniden kullanılır. Eşleşmeyenler epdk_unmatched.csv'ye yazılır; --nominatim ile
     OSM'den (ücretsiz, yavaş, daha az isabetli) best-effort geocode edilebilir.
  4. epdk_all_stations index'ine eklenir (_id = İstasyon No, ör. "ŞRJ/9055").
     Mevcut sarj-id'li dokümanlar SİLİNMEZ; bu kayıtlar source="epdk" ile eklenir.

Kullanım:
    python3 scripts/index_epdk_epvys_stations.py <html_dosya_veya_klasor> [--nominatim]
                                                  [--snapshot <klasor>] [--dry-run]
"""

import csv
import glob
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser

ES_INDEX        = "epdk_all_stations"
ZES_OPERATOR    = "ZES DİJİTAL TİCARET ANONİM ŞİRKETİ"
BATCH_SIZE      = 1000
DATATABLE_TBODY = "sarjIstasyonuList_data"   # PrimeFaces datatable gövdesi (id eki)
UNMATCHED_CSV   = os.path.join(os.path.dirname(__file__), "epdk_unmatched.csv")

# Kolon index -> alan adı (parse edilen tabloda)
COLS = {
    0: "epdk_station_id",
    1: "title",
    2: "service_type",
    3: "brand",
    4: "network_operator",
    5: "station_operator",
    6: "address",
}


# ── ES yardımcıları ───────────────────────────────────────────────────────────
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


# ── HTML parse: PrimeFaces datatable satırları ────────────────────────────────
class StationTableParser(HTMLParser):
    """sarjIstasyonuList_data tbody'sindeki <tr>/<td>'leri sıralı metin olarak toplar."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.rows = []
        self._in_tbody = False
        self._td_depth = 0      # data-cell <td> içindeki iç içe tag derinliği
        self._row = None
        self._cell = None

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "tbody" and DATATABLE_TBODY in (a.get("id") or ""):
            self._in_tbody = True
            return
        if not self._in_tbody:
            return
        if tag == "tr":
            self._row = []
        elif tag == "td" and self._row is not None and self._td_depth == 0:
            self._cell = []
            self._td_depth = 1
        elif self._cell is not None:
            self._td_depth += 1

    def handle_endtag(self, tag):
        if not self._in_tbody:
            return
        if tag == "tbody":
            self._in_tbody = False
        elif tag == "td" and self._cell is not None:
            self._td_depth -= 1
            if self._td_depth == 0:
                text = re.sub(r"\s+", " ", "".join(self._cell)).strip()
                self._row.append(text)
                self._cell = None
        elif self._cell is not None and tag != "tr":
            self._td_depth = max(0, self._td_depth - 1)
        elif tag == "tr" and self._row is not None:
            if any(c for c in self._row):
                self.rows.append(self._row)
            self._row = None

    def handle_data(self, data):
        if self._cell is not None:
            self._cell.append(data)


def parse_html_file(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        p = StationTableParser()
        p.feed(f.read())
    out = []
    for row in p.rows:
        rec = {}
        for idx, field in COLS.items():
            rec[field] = row[idx] if idx < len(row) else ""
        if rec.get("epdk_station_id"):
            out.append(rec)
    return out


# ── İsim normalizasyonu (eşleştirme için) ─────────────────────────────────────
_TR = str.maketrans({
    "İ": "i", "I": "i", "ı": "i", "Ş": "s", "ş": "s", "Ğ": "g", "ğ": "g",
    "Ü": "u", "ü": "u", "Ö": "o", "ö": "o", "Ç": "c", "ç": "c",
})


def norm(s):
    s = (s or "").translate(_TR).lower()
    return re.sub(r"[^a-z0-9]+", " ", s).strip()


# ── sarj.com.tr snapshot'ından koordinat indeksi ──────────────────────────────
def load_snapshot_coords(folder):
    """(title)->coord ve (brand|title)->coord eşleştirme tabloları döner."""
    by_title, by_brand_title = {}, {}
    for path in glob.glob(os.path.join(folder, "*.json")):
        try:
            with open(path, encoding="utf-8") as f:
                d = json.load(f)
            lat, lng = float(d["lat"]), float(d["lng"])
        except (json.JSONDecodeError, UnicodeDecodeError, TypeError, ValueError, KeyError):
            continue
        t, b = norm(d.get("title", "")), norm(d.get("brand", ""))
        if t:
            by_title.setdefault(t, (lat, lng))
            if b:
                by_brand_title.setdefault(f"{b}|{t}", (lat, lng))
    return by_title, by_brand_title


def reuse_coord(rec, by_title, by_brand_title):
    t, b = norm(rec["title"]), norm(rec["brand"])
    return (by_brand_title.get(f"{b}|{t}")
            or by_title.get(t))


# ── Nominatim (opsiyonel, leftover geocode) ───────────────────────────────────
def geocode_nominatim(address):
    q = urllib.parse.urlencode({"q": address, "format": "json", "limit": 1,
                                "countrycodes": "tr"})
    req = urllib.request.Request(
        f"https://nominatim.openstreetmap.org/search?{q}",
        headers={"User-Agent": "charging-scripts-epdk-geocoder/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            res = json.loads(r.read())
        if res:
            return float(res[0]["lat"]), float(res[0]["lon"])
    except (urllib.error.URLError, ValueError, KeyError, IndexError):
        pass
    return None


# ── main ──────────────────────────────────────────────────────────────────────
def main():
    argv = sys.argv[1:]
    use_nominatim = "--nominatim" in argv
    dry_run = "--dry-run" in argv
    snapshot = None
    if "--snapshot" in argv:
        snapshot = argv[argv.index("--snapshot") + 1]
    paths = [a for a in argv if not a.startswith("--")
             and a != snapshot]
    if not paths:
        sys.exit("Kullanım: index_epdk_epvys_stations.py <html_dosya_veya_klasor> "
                 "[--nominatim] [--snapshot <klasor>] [--dry-run]")

    # 0. HTML dosyalarını topla
    html_files = []
    for p in paths:
        if os.path.isdir(p):
            html_files += sorted(glob.glob(os.path.join(p, "*.htm*")))
        else:
            html_files.append(p)
    if not html_files:
        sys.exit(f"HTML dosyası bulunamadı: {paths}")
    print(f"[0] {len(html_files)} HTML dosyası")

    # 1. Parse + tekilleştir
    stations = {}   # istasyon_no -> rec
    for hf in html_files:
        recs = parse_html_file(hf)
        for r in recs:
            stations.setdefault(r["epdk_station_id"], r)
        print(f"    {os.path.basename(hf)}: {len(recs)} satır")
    print(f"[1] {len(stations)} tekil istasyon")
    if not stations:
        sys.exit("Tabloda satır yok. HTML 'Yalnızca HTML' olarak ve sorgu sonucu "
                 "görünürken kaydedildi mi?")

    # 2. Koordinat eşleştirme (sarj snapshot reuse)
    if snapshot is None:
        dirs = sorted(glob.glob(os.path.expanduser("~/Downloads/dataa/*/")))
        snapshot = dirs[0] if dirs else None
    by_title, by_brand_title = ({}, {})
    if snapshot and os.path.isdir(snapshot):
        by_title, by_brand_title = load_snapshot_coords(snapshot)
        print(f"[2] snapshot: {snapshot}  ({len(by_title)} isim)")
    else:
        print("[2] snapshot bulunamadı — koordinat reuse atlanıyor")

    matched = unmatched = geocoded = 0
    unmatched_rows = []
    for r in stations.values():
        coord = reuse_coord(r, by_title, by_brand_title)
        if coord:
            r["_coord"], r["_geo_src"] = coord, "reuse"
            matched += 1
        else:
            r["_coord"], r["_geo_src"] = None, None
            unmatched += 1
            unmatched_rows.append(r)
    print(f"[2] eşleşen {matched}, eşleşmeyen {unmatched}")

    # 2b. Leftover -> Nominatim (opsiyonel)
    if use_nominatim and unmatched_rows:
        print(f"[2b] {len(unmatched_rows)} adres Nominatim ile geocode ediliyor "
              f"(~{len(unmatched_rows) * 1.1:.0f}sn)…")
        for r in unmatched_rows:
            c = geocode_nominatim(r["address"])
            if c:
                r["_coord"], r["_geo_src"] = c, "nominatim"
                geocoded += 1
            time.sleep(1.1)
        print(f"[2b] Nominatim ile {geocoded} eklendi")

    # 2c. Hâlâ koordinatsızları CSV'ye yaz
    still = [r for r in stations.values() if not r["_coord"]]
    if still:
        with open(UNMATCHED_CSV, "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["istasyon_no", "istasyon_adi", "marka", "adres"])
            for r in still:
                w.writerow([r["epdk_station_id"], r["title"], r["brand"], r["address"]])
        print(f"[2c] {len(still)} koordinatsız -> {UNMATCHED_CSV}")

    # 3. Index mapping (additive — mevcut dokümanları silmez)
    if not dry_run:
        es_request("PUT", f"/{ES_INDEX}", {
            "settings": {"number_of_shards": 1, "number_of_replicas": 0},
            "mappings": {"properties": {
                "epdk_station_id":  {"type": "keyword"},
                "title":            {"type": "text", "fields": {"keyword": {"type": "keyword"}}},
                "brand":            {"type": "keyword"},
                "is_zes":           {"type": "boolean"},
                "location":         {"type": "geo_point"},
            }},
        }) if not es_index_exists() else None
        es_request("PUT", f"/{ES_INDEX}/_mapping", {"properties": {
            "address":          {"type": "text"},
            "service_type":     {"type": "keyword"},
            "network_operator": {"type": "keyword"},
            "station_operator": {"type": "keyword"},
            "source":           {"type": "keyword"},
            "geo_source":       {"type": "keyword"},
        }})

    # 4. Dokümanları kur + bulk index
    docs = []
    n_zes = 0
    for r in stations.values():
        ops = f"{r['network_operator']} {r['station_operator']}".upper()
        is_zes = "ZES" in r["brand"].upper() or "ZES DİJİTAL" in ops
        n_zes += is_zes
        doc = {
            "epdk_station_id":  r["epdk_station_id"],
            "title":            r["title"],
            "brand":            r["brand"],
            "service_type":     r["service_type"],
            "network_operator": r["network_operator"],
            "station_operator": r["station_operator"],
            "address":          r["address"],
            "is_zes":           is_zes,
            "source":           "epdk",
            "geo_source":       r["_geo_src"],
        }
        if r["_coord"]:
            doc["location"] = {"lat": r["_coord"][0], "lon": r["_coord"][1]}
        docs.append(doc)

    if dry_run:
        print(f"[dry-run] {len(docs)} doküman hazır (yazılmadı). Örnek:")
        print(json.dumps(docs[0], ensure_ascii=False, indent=2))
        return

    def bulk(batch):
        lines = []
        for d in batch:
            lines.append(json.dumps({"index": {"_index": ES_INDEX,
                                               "_id": d["epdk_station_id"]}}))
            lines.append(json.dumps(d, ensure_ascii=False))
        res = es_request("POST", "/_bulk", "\n".join(lines) + "\n", ndjson=True)
        if res.get("errors"):
            errs = [i["index"]["error"] for i in res["items"]
                    if "error" in i.get("index", {})]
            print(f"    UYARI: {len(errs)} hata — {errs[0]}")

    batch = []
    for i, d in enumerate(docs, 1):
        batch.append(d)
        if len(batch) >= BATCH_SIZE:
            bulk(batch); batch = []
            print(f"    {i:,} yazıldı…", end="\r")
    if batch:
        bulk(batch)

    es_request("POST", f"/{ES_INDEX}/_refresh")
    total = es_request("GET", f"/{ES_INDEX}/_count")["count"]
    with_loc = matched + geocoded
    print(f"\n══ ÖZET ════════════════════════════════")
    print(f"  EPDK istasyon     {len(docs):,}  ({n_zes} ZES)")
    print(f"  Koordinatlı       {with_loc:,}  (reuse {matched}, nominatim {geocoded})")
    print(f"  Koordinatsız      {len(docs) - with_loc:,}")
    print(f"  Index toplam      {total:,}  (sarj + epdk birlikte)")


def es_index_exists():
    try:
        es_request("GET", f"/{ES_INDEX}")
        return True
    except urllib.error.HTTPError:
        return False


if __name__ == "__main__":
    main()
