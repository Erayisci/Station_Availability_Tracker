package com.charging.ingestion.spi;

/**
 * Answers "how much competing capacity sits within {@code radiusKm} of this
 * point" for one provider.
 *
 * <h2>Why this is an optional capability</h2>
 * Unlike polling and mapping, not every network exposes a proximity query. L2
 * therefore treats this as optional: with no implementation registered for a
 * provider, {@code nearby_*_count} stay at {@link NearbySupply#NOT_MEASURED},
 * supply is classified {@code UNKNOWN} and the station's recommendation becomes
 * {@code INSUFFICIENT_DATA}. The performance stage still builds — the utilization
 * half of it never needed this — so a network without proximity data degrades to
 * fewer fields rather than to a failed pipeline.
 *
 * <h2>Why L2 asks through an interface</h2>
 * The performance stage previously autowired {@code ZesApiClient} and walked its
 * {@code data[].evses[].connectors[]} response inline. That was the one place a
 * layer reached <em>upward</em> into a specific provider's HTTP client, and it
 * put an auth scheme and a payload shape in the middle of derived-metric code.
 * The counts are all L2 ever wanted.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><b>Never throws.</b> Return {@link NearbySupply#unavailable(String)} on
 *       timeout, auth failure or an unparseable response. A day's build enriches
 *       ~1,800 stations concurrently; individual failures are expected and must
 *       stay individual.</li>
 *   <li><b>Thread-safe.</b> Called from a fixed pool, one call per station.</li>
 *   <li><b>Self-inclusive or not, consistently.</b> Whether the origin station
 *       counts itself is the provider's convention; keep it stable, because the
 *       {@code FEW}/{@code MODERATE}/{@code MANY} thresholds are calibrated
 *       against it.</li>
 * </ul>
 */
public interface NearbySupplyProvider extends ProviderScoped {

    /**
     * Counts stations and connectors around a point.
     *
     * @param lat      WGS-84 latitude of the origin station
     * @param lng      WGS-84 longitude
     * @param radiusKm search radius in kilometres (the pipeline uses 1.0)
     * @return measured counts, or {@link NearbySupply#unavailable(String)}
     */
    NearbySupply fetchNearbySupply(double lat, double lng, double radiusKm);
}
