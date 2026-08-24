package com.charging.ingestion.spi;

/**
 * The contract every charging-network operator must satisfy to be tracked by
 * this platform — layer 1's only outward-facing abstraction.
 *
 * <h2>Why the platform is provider-agnostic</h2>
 * Charging networks expose incompatible APIs — different auth, payload shape,
 * pagination and status vocabulary — yet everything above layer 1 consumes the
 * same thing: a stream of connector status slots and a daily snapshot. Pinning
 * that boundary here means the ~5,000 lines of materialization, analytics and
 * intelligence code never learn which operator a station came from. Adding a
 * network is then an adapter, not a fork of the pipeline.
 *
 * <h2>Polling is one of three capabilities</h2>
 * This interface covers acquisition only. Interpreting a landed payload is
 * {@link StationPayloadMapper}, and measuring competing supply is
 * {@link NearbySupplyProvider}; {@code com.charging.ingestion.ProviderRegistry}
 * resolves all three by {@code providerId}. They are separate because they are
 * separately optional — an archived network keeps a mapper for backfill after
 * its API is gone, and a network with no proximity query simply has no third
 * implementation.
 *
 * <h2>Status: one implementation</h2>
 * {@code providers/zes} is currently the only implementor. 
 * It is a reference for onboarding new networks.
 *
 * <p>Layer 2 <em>is</em> now provider-agnostic: it reads canonical
 * {@link ConnectorRow}s and asks for supply through an interface, so onboarding
 * a network no longer means editing the transform chain. What remains coupled is
 * further up — L3 analytics, the Python service and three console charts compare
 * {@link ConnectorStatus} wire strings directly, and every read endpoint is
 * still mounted under {@code /zes/}. See this package's README for the honest
 * remaining list.
 *
 * <h2>Invariants an adapter must uphold</h2>
 * <ol>
 *   <li><b>H2 is authoritative, Elasticsearch is derived.</b> {@code pollAndTrack}
 *       must persist observed transitions to the relational store before any ES
 *       write happens. Every index downstream can then be rebuilt from H2
 *       within the retention window, and from the previous ES stage outside it.</li>
 *   <li><b>Slots are closed exactly once.</b> An open slot spans polls; the
 *       end-of-day call is what closes the survivors, so calling it twice for
 *       one date must not double-count.</li>
 *   <li><b>Idempotent by date.</b> Re-running a day rebuilds that day's index
 *       in place and never touches another date's.</li>
 *   <li><b>Failure is contained.</b> A provider outage must fail its own poll
 *       only — schedulers catch and log per provider so one network going down
 *       cannot stall another's chain.</li>
 * </ol>
 */
public interface StationStatusProvider extends ProviderScoped {

    /**
     * Restores slots left open by a previous process lifetime.
     *
     * <p>Open slots live in memory between polls, so a restart mid-day would
     * otherwise orphan them and under-report that day's occupancy. Invoked
     * once at startup.
     */
    void loadTodayOpenSlots();

    /**
     * Polls the provider once and persists any status transitions to H2.
     *
     * @return number of stations observed in this poll
     */
    int pollAndTrack() throws Exception;

    /**
     * Materialises the day's H2 history into the provider's daily snapshot index.
     *
     * @param finalRun {@code true} for the end-of-day run, which also closes
     *                 every still-open slot at midnight; {@code false} for an
     *                 intraday preview that leaves open slots untouched
     * @return number of station documents written
     */
    int indexDailyToElastic(boolean finalRun) throws Exception;
}
