package com.charging.ingestion.providers.zes;

import com.charging.ingestion.spi.NearbySupply;
import com.charging.ingestion.spi.NearbySupplyProvider;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ZES yakın-istasyon API'si ile rakip arz ölçümü.
 *
 * <p>L2'nin performans aşaması daha önce {@link ZesApiClient}'ı doğrudan
 * autowire edip {@code data[].evses[].connectors[]} yanıtını kendi içinde
 * geziyordu — bir katmanın sağlayıcının HTTP istemcisine <em>yukarı doğru</em>
 * uzandığı tek yerdi. L2'nin ihtiyacı olan tek şey iki sayıydı; bu sınıf o
 * sayıları üretir, yanıtın şekli burada kalır.
 *
 * <h2>Hata bir değerdir, istisna değil</h2>
 * Günlük derlemede ~1.800 istasyon 20 thread ile paralel zenginleştiriliyor;
 * tek tek zaman aşımları normaldir. Bu yüzden hiçbir hata dışarı atılmaz,
 * {@link NearbySupply#unavailable(String)} olarak döner ve o istasyon
 * {@code INSUFFICIENT_DATA} tavsiyesi alır — ölçülmemiş bir sıfırdan türetilmiş
 * yanlış bir tavsiye yerine.
 */
@Component
public class ZesNearbySupplyProvider implements NearbySupplyProvider {

    @Autowired
    private ZesApiClient apiClient;

    @Override
    public String providerId() {
        return ZesStationPayloadMapper.PROVIDER_ID;
    }

    @Override
    public NearbySupply fetchNearbySupply(double lat, double lng, double radiusKm) {
        try {
            Map<String, Object> response = apiClient.fetchNearbyStations(lat, lng, "", radiusKm);
            Object data = response.get("data");
            if (!(data instanceof List<?> stations)) {
                return NearbySupply.unavailable("Yanıtta 'data' dizisi yok");
            }
            return NearbySupply.of(stations.size(), countConnectors(stations));
        } catch (Exception e) {
            return NearbySupply.unavailable(e.getMessage());
        }
    }

    /** station[].evses[].connectors[] üçlü iç içe yapısındaki soketleri sayar. */
    private int countConnectors(List<?> stations) {
        return stations.stream()
                .filter(station -> station instanceof Map)
                .mapToInt(station -> {
                    Object evses = ((Map<?, ?>) station).get("evses");
                    if (!(evses instanceof List<?> evseList)) return 0;
                    return evseList.stream()
                            .filter(evse -> evse instanceof Map)
                            .mapToInt(evse -> {
                                Object connectors = ((Map<?, ?>) evse).get("connectors");
                                return connectors instanceof List<?> list ? list.size() : 0;
                            })
                            .sum();
                })
                .sum();
    }
}
