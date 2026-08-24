package com.charging.ingestion;

import com.charging.ingestion.spi.NearbySupplyProvider;
import com.charging.ingestion.spi.ProviderScoped;
import com.charging.ingestion.spi.StationPayloadMapper;
import com.charging.ingestion.spi.StationStatusProvider;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves provider capabilities by {@code providerId} — the lookup that lets
 * layers above ingestion ask for "this network's mapper" without naming a class.
 *
 * <h2>Why a registry rather than autowiring the interface</h2>
 * With one implementation, {@code @Autowired StationPayloadMapper} works and
 * looks abstract. With two it fails at startup, and the usual fix — an
 * {@code @Primary} or a qualifier — silently reintroduces the coupling this
 * package exists to remove. Indexing the implementations by {@code providerId}
 * is what makes "add a network without editing anything above layer 1" literally
 * true instead of aspirational.
 *
 * <h2>Why {@link ObjectProvider} and not {@code List} injection</h2>
 * Injecting {@code List<StationStatusProvider>} in the constructor would create
 * every tracker while this bean is being constructed, and one of them
 * ({@code ZesStatusTrackerService}) depends on L2's transform service, which
 * depends back on this registry. That is a genuine bean cycle, and Spring Boot
 * rejects cycles by default — the context would fail to start even though
 * everything compiles.
 *
 * <p>{@code ObjectProvider} defers resolution to the moment of the lookup, and
 * each lookup resolves <em>only the capability it needs</em>. L2's
 * {@code @PostConstruct} therefore pulls in mappers alone (leaf beans, no
 * dependencies back), while trackers are first resolved by a scheduler or a
 * request, long after the context is built. The cycle never forms.
 *
 * <p>Lookups re-index on each call rather than caching. They happen a handful of
 * times a day — one per pipeline stage, one per manual trigger — so the map
 * build is irrelevant, and avoiding a cache removes both the lazy-init
 * synchronisation and any chance of a stale view.
 *
 * <h2>Capabilities are independent</h2>
 * A provider need not implement all three. Polling and mapping are separable —
 * an archived network keeps its mapper for backfill after its API is gone — and
 * {@link NearbySupplyProvider} is genuinely optional, so it is the one lookup
 * that returns {@link Optional} and the one L2 is written to survive without.
 *
 * @see #soleProviderId() for how the single-provider call sites stay honest
 */
@Component
public class ProviderRegistry {

    private final ObjectProvider<StationStatusProvider> trackerBeans;
    private final ObjectProvider<StationPayloadMapper>  mapperBeans;
    private final ObjectProvider<NearbySupplyProvider>  nearbySupplyBeans;

    public ProviderRegistry(ObjectProvider<StationStatusProvider> trackerBeans,
                            ObjectProvider<StationPayloadMapper> mapperBeans,
                            ObjectProvider<NearbySupplyProvider> nearbySupplyBeans) {
        this.trackerBeans      = trackerBeans;
        this.mapperBeans       = mapperBeans;
        this.nearbySupplyBeans = nearbySupplyBeans;
    }

    /**
     * Logs the capability matrix once the context is fully built, and forces the
     * duplicate-id check on every capability.
     *
     * <p>Deliberately on {@link ApplicationReadyEvent} rather than
     * {@code @PostConstruct}: resolving trackers during startup is what would
     * re-create the bean cycle described in the class note. Waiting until the
     * context is ready means a misconfiguration still fails fast and loudly, but
     * after wiring rather than during it.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logRegisteredProviders() {
        Map<String, StationStatusProvider> trackers = trackers();
        Map<String, StationPayloadMapper>  mappers  = mappers();
        Map<String, NearbySupplyProvider>  nearby   = nearbySupply();

        for (String id : union(trackers, mappers, nearby)) {
            System.out.printf("[PROVIDER] '%s' kayıtlı — poll:%s map:%s nearby:%s%n",
                    id,
                    trackers.containsKey(id) ? "✓" : "—",
                    mappers.containsKey(id)  ? "✓" : "—",
                    nearby.containsKey(id)   ? "✓" : "—");
        }
    }

    /** Every provider registered under any capability, sorted. */
    public Set<String> providerIds() {
        return union(trackers(), mappers(), nearbySupply());
    }

    /**
     * Providers whose payload can be interpreted, i.e. the ones that can produce
     * a canonical dataset. L2 uses this to create one index template per
     * provider it may actually be asked to transform.
     */
    public Set<String> mapperProviderIds() {
        return mappers().keySet();
    }

    /**
     * The payload mapper for {@code providerId}.
     *
     * @throws IllegalArgumentException if none is registered — a configuration
     *         error, not a runtime condition, so it fails loudly
     */
    public StationPayloadMapper mapperFor(String providerId) {
        return require(mappers(), providerId, "StationPayloadMapper");
    }

    /** The tracker for {@code providerId}. */
    public StationStatusProvider trackerFor(String providerId) {
        return require(trackers(), providerId, "StationStatusProvider");
    }

    /**
     * The proximity lookup for {@code providerId}, if the network offers one.
     *
     * <p>Empty is a normal answer: L2 degrades to {@code NOT_MEASURED} counts
     * rather than failing the build.
     */
    public Optional<NearbySupplyProvider> nearbySupplyFor(String providerId) {
        return Optional.ofNullable(nearbySupply().get(providerId));
    }

    /**
     * The single registered provider, for call sites that predate multi-provider
     * support — the manual trigger endpoints.
     *
     * <p>Deliberately throws when there is more than one rather than picking a
     * default. Those call sites have no basis for choosing, so the second
     * network's arrival should surface as an immediate, specific failure naming
     * what to parameterise, not as a pipeline that quietly only ever rebuilds one
     * operator's data.
     *
     * @throws IllegalStateException if zero or more than one provider is registered
     */
    public String soleProviderId() {
        Set<String> ids = providerIds();
        if (ids.size() == 1) {
            return ids.iterator().next();
        }
        if (ids.isEmpty()) {
            throw new IllegalStateException(
                    "Hiç provider kayıtlı değil: ingestion/providers/ altında "
                    + "StationStatusProvider uygulayan bir @Service bulunamadı.");
        }
        throw new IllegalStateException(
                "Birden fazla provider kayıtlı " + ids + " — bu çağrı noktası "
                + "provider parametresi almalı. soleProviderId() yalnızca tek "
                + "provider varken kullanılabilir.");
    }

    // ── Resolution ───────────────────────────────────────────────────────────

    private Map<String, StationStatusProvider> trackers() {
        return index(trackerBeans, "StationStatusProvider");
    }

    private Map<String, StationPayloadMapper> mappers() {
        return index(mapperBeans, "StationPayloadMapper");
    }

    private Map<String, NearbySupplyProvider> nearbySupply() {
        return index(nearbySupplyBeans, "NearbySupplyProvider");
    }

    private static <T extends ProviderScoped> Map<String, T> index(ObjectProvider<T> beans, String capability) {
        Map<String, T> byId = new LinkedHashMap<>();
        for (T component : beans) {
            T previous = byId.put(component.providerId(), component);
            if (previous != null) {
                throw new IllegalStateException(String.format(
                        "providerId '%s' için iki %s bulundu: %s ve %s",
                        component.providerId(), capability,
                        previous.getClass().getSimpleName(),
                        component.getClass().getSimpleName()));
            }
        }
        return byId;
    }

    @SafeVarargs
    private static Set<String> union(Map<String, ? extends ProviderScoped>... maps) {
        Set<String> ids = new TreeSet<>();
        for (Map<String, ? extends ProviderScoped> map : maps) {
            ids.addAll(map.keySet());
        }
        return ids;
    }

    private static <T> T require(Map<String, T> byId, String providerId, String capability) {
        T component = byId.get(providerId);
        if (component == null) {
            throw new IllegalArgumentException(String.format(
                    "providerId '%s' için %s kayıtlı değil (kayıtlı olanlar: %s)",
                    providerId, capability, byId.keySet()));
        }
        return component;
    }
}
