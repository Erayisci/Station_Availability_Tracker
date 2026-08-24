package com.charging.ingestion.providers.zes;

import com.charging.ingestion.spi.AvailabilityWindow;
import com.charging.ingestion.spi.ConnectorRow;
import com.charging.ingestion.spi.ConnectorStatus;
import com.charging.ingestion.spi.StationPayloadMapper;
import com.charging.kernel.es.IndexCatalog;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ZES payload → canonical {@link ConnectorRow}.
 *
 * <p>ZES'e özgü alan adlarının ve iç içe geçmiş yapının bilindiği tek yer.
 * Bu eşleme daha önce L2'deki connector transform servisinin içindeydi; oradaki
 * {@code isRoamingLocation}, {@code maxElectricPower}, {@code evses[].connectors[]}
 * gibi isimler ZES'in şemasıydı, yani "sağlayıcıdan bağımsız" olması gereken
 * katman aslında şemayı en iyi bilen katmandı. Eşleme buraya taşındı.
 *
 * <h2>ZES yapısı</h2>
 * <pre>
 *   station { id, name, address, cityName, provinceName, latitude, longitude, …
 *     evses[] { id, locationDescription, isAutoChargeSupported, …
 *       connectors[] { id, type, connectorNo, maxElectricPower, …
 *         availability[] { status, startTime, endTime } } } }
 * </pre>
 * {@code availability} dizisi ZES'ten gelmez — L1 tracker'ı H2'deki gözlemlerden
 * üretip günlük snapshot'a ekler (bkz. {@link ZesStatusTrackerService}).
 *
 * <h2>Durum sözlüğü</h2>
 * ZES {@code "Available"} ve {@code "Charging"} değerlerini zaten kanonik kodlarla
 * aynı yazımda gönderiyor, bu yüzden çeviri kimlik dönüşümü. Yine de
 * {@link ConnectorStatus} üzerinden geçiriliyor: bu, eşlemenin bir tesadüf değil
 * bir sözleşme olduğunu belirtir ve tanınmayan durumlar olduğu gibi aktarılır
 * (L3 durumları isimlerine göre topluyor).
 */
@Component
public class ZesStationPayloadMapper implements StationPayloadMapper {

    /**
     * {@code zes_stations-*}, {@code zes_connector_transform-*} … öneki.
     * Literal {@link IndexCatalog} içinde tanımlı: id index adının parçası
     * olduğu için depolama sözleşmesine aittir.
     */
    public static final String PROVIDER_ID = IndexCatalog.PROVIDER_ZES;

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ConnectorRow> toConnectorRows(Map<String, Object> stationDoc) {
        List<ConnectorRow> rows = new ArrayList<>();
        if (stationDoc == null) return rows;

        String  stationId    = str(stationDoc.get("id"));
        String  stationName  = (String) stationDoc.get("name");
        String  address      = (String) stationDoc.get("address");
        String  cityName     = (String) stationDoc.get("cityName");
        String  provinceName = (String) stationDoc.get("provinceName");
        Double  lat          = dbl(stationDoc.get("latitude"));
        Double  lng          = dbl(stationDoc.get("longitude"));
        Double  distance     = dbl(stationDoc.get("distance"));
        String  openingTime  = (String) stationDoc.get("openingTime");
        String  closingTime  = (String) stationDoc.get("closingTime");
        Boolean isRestricted = bool(stationDoc.get("isRestricted"));
        Boolean isRoaming    = bool(stationDoc.get("isRoamingLocation"));
        String  observedDate = (String) stationDoc.get("fetchedAt");

        List<Map<String, Object>> evses =
                (List<Map<String, Object>>) stationDoc.getOrDefault("evses", List.of());

        for (Map<String, Object> evse : evses) {
            if (evse == null) continue;
            String  evseId       = str(evse.get("id"));
            String  evseLocDesc  = (String) evse.get("locationDescription");
            Boolean isAutocharge = bool(evse.get("isAutoChargeSupported"));

            List<Map<String, Object>> connectors =
                    (List<Map<String, Object>>) evse.getOrDefault("connectors", List.of());

            for (Map<String, Object> connector : connectors) {
                if (connector == null) continue;

                rows.add(new ConnectorRow(
                        str(connector.get("id")),
                        (String) connector.get("type"),
                        integer(connector.get("connectorNo")),
                        integer(connector.get("maxElectricPower")),
                        stationId,
                        stationName,
                        address,
                        cityName,
                        provinceName,
                        lat,
                        lng,
                        distance,
                        openingTime,
                        closingTime,
                        isRestricted,
                        isRoaming,
                        evseId,
                        evseLocDesc,
                        isAutocharge,
                        observedDate,
                        toWindows((List<Map<String, Object>>) connector.getOrDefault("availability", List.of()))
                ));
            }
        }
        return rows;
    }

    private List<AvailabilityWindow> toWindows(List<Map<String, Object>> rawAvailability) {
        List<AvailabilityWindow> windows = new ArrayList<>(rawAvailability.size());
        for (Map<String, Object> slot : rawAvailability) {
            if (slot == null) continue;
            windows.add(new AvailabilityWindow(
                    canonicalStatus((String) slot.get("status")),
                    (String) slot.get("startTime"),
                    (String) slot.get("endTime")
            ));
        }
        return windows;
    }

    /**
     * ZES durum adını kanonik koda çevirir; tanınmayan durum olduğu gibi döner.
     * (ZES yazımı kanonik yazımla aynı olduğu için pratikte kimlik dönüşümü —
     * eşlemenin nerede yapıldığını göstermek için açıkça yazıldı.)
     */
    private String canonicalStatus(String zesStatus) {
        if (ConnectorStatus.AVAILABLE.wire().equals(zesStatus)) return ConnectorStatus.AVAILABLE.wire();
        if (ConnectorStatus.CHARGING.wire().equals(zesStatus))  return ConnectorStatus.CHARGING.wire();
        return zesStatus;
    }

    // ── Tip dönüşümleri: eksik alan 0/false değil null olmalı ────────────────

    private static String str(Object v) {
        return v != null ? String.valueOf(v) : null;
    }

    private static Integer integer(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private static Double dbl(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static Boolean bool(Object v) {
        return v instanceof Boolean b ? b : null;
    }
}
