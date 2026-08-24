package com.charging.ingestion.spi;

/**
 * Competing charging supply within a radius of one station.
 *
 * <p>Feeds {@code effective_supply} → {@code demand_supply_ratio} →
 * {@code recommendation_code} in L2's performance stage. Note that this counts
 * <em>the provider's own</em> nearby stations, not a market-wide register: it
 * measures how much alternative capacity the same operator already has in the
 * area, which is the question the expand/relocate recommendation actually asks.
 *
 * <h2>Unavailability is a value, not an exception</h2>
 * A per-station lookup that fails must not fail the day's build — 1,800
 * stations are enriched concurrently and a handful of timeouts is normal. So a
 * failed lookup returns {@link #unavailable(String)} rather than throwing, and
 * carries {@code -1} counts. That sentinel is the published wire value
 * ({@code nearby_stations_count: -1}) and is what makes L2 classify supply as
 * {@code UNKNOWN} and emit {@code INSUFFICIENT_DATA} instead of a
 * recommendation derived from a zero it never measured.
 *
 * @param stationCount   nearby stations found, or {@code -1} if the lookup failed
 * @param connectorCount total connectors across those stations, or {@code -1}
 * @param error          provider error message when the lookup failed, else {@code null}
 */
public record NearbySupply(int stationCount, int connectorCount, String error) {

    /** Sentinel written to {@code nearby_*_count} when a lookup did not succeed. */
    public static final int NOT_MEASURED = -1;

    /** A successful measurement. */
    public static NearbySupply of(int stationCount, int connectorCount) {
        return new NearbySupply(stationCount, connectorCount, null);
    }

    /** A failed lookup — counts become {@link #NOT_MEASURED}, reason retained. */
    public static NearbySupply unavailable(String error) {
        return new NearbySupply(NOT_MEASURED, NOT_MEASURED, error);
    }

    /** True when the counts are real measurements rather than the sentinel. */
    public boolean isMeasured() {
        return stationCount >= 0;
    }
}
