package com.charging.kernel.es;

import java.time.LocalDate;

/**
 * The Elasticsearch naming contract — the single integration point between the
 * three services in this platform.
 *
 * <h2>Why this class exists</h2>
 * The Java backend, the Python ML pipeline and the dashboard never call each
 * other. They exchange data exclusively through <b>date-suffixed indices</b>,
 * so the index name <em>is</em> the API. Before this catalog those names were
 * duplicated as private string constants across ~15 services and again inside
 * the Python package: a rename could not be performed safely, and no single
 * file described the pipeline. Centralising the names makes the contract
 * explicit and greppable from both ends.
 *
 * <h2>Naming rule</h2>
 * Every index in the chain is {@code {providerId}_{dataset}-{yyyy-MM-dd}}, one
 * index per day, except {@link #ZES_STATION_ENVIRONMENT} which is a single
 * static enrichment index (station environment does not change daily).
 *
 * <p>Two independent decisions are encoded in that shape. <b>Date-suffixing</b>
 * is what makes each pipeline stage independently rebuildable: dropping and
 * rebuilding one day never touches another. <b>Provider-prefixing</b> is what
 * lets a second network land beside the first without colliding — which is why
 * the prefix is reachable as a function ({@link #stations(String)} and
 * friends) and not only as a hard-coded constant.
 *
 * <h2>Chain and ownership</h2>
 * <pre>
 *   L1 ingestion      H2 (authoritative, zes.history.keepDays retention)
 *          │          ZES_STATIONS  ← raw provider payload, written by the adapter
 *          │                 │
 *   L2 materialization       └──▶ ZES_CONNECTOR_TRANSFORM ──▶ ZES_STATION_PERFORMANCE   [Java]
 *          │                                    │
 *   L4 intelligence                             ├──▶ ZES_STATION_FEATURES                     [Python]
 *                                               ├──▶ ZES_STATION_FORECAST                     [Python]
 *                                               ├──▶ ZES_STATION_OPPORTUNITY                  [Python]
 *                                               ├──▶ ZES_STATION_HOURLY_PERFORMANCE           [Python]
 *                                               └──▶ ZES_STATION_HOURLY_FORECAST              [Python]
 * </pre>
 * The first index sits at L1, not L2: it holds the provider's payload verbatim,
 * so it is a landing zone rather than a derived dataset. L2 begins at the first
 * canonical stage, which is the first one whose schema the platform chose.
 * Python-owned indices are declared here as well: Java reads them and must
 * agree on the name, so the contract belongs in one place regardless of which
 * runtime writes it. The mirror of this file is
 * {@code intelligence/kernel/index_catalog.py}.
 */
public final class IndexCatalog {

    private IndexCatalog() {
    }

    // ── Provider-scoped datasets ─────────────────────────────────────────────

    /**
     * Registered provider identifiers. Declared here rather than in the adapter
     * because the id is <em>part of the index name</em>, so it belongs to the
     * storage contract; {@code ingestion/providers/zes} references this constant
     * rather than repeating the literal (L1 → L0 is the allowed direction).
     */
    public static final String PROVIDER_ZES = "zes";

    /* Dataset suffixes. Private so that a name can only be produced through the
     * helpers or the pre-composed constants below — the two can then never drift. */
    private static final String DS_STATIONS            = "_stations";
    private static final String DS_CONNECTOR_TRANSFORM = "_connector_transform";
    private static final String DS_STATION_PERFORMANCE = "_station_performance";

    /**
     * Daily raw station snapshot for {@code providerId}, enriched with the day's
     * availability slots. Written by that provider's L1 adapter.
     */
    public static String stations(String providerId) {
        return providerId + DS_STATIONS;
    }

    /** Connector-level explosion of the snapshot; source of all analytics. */
    public static String connectorTransform(String providerId) {
        return providerId + DS_CONNECTOR_TRANSFORM;
    }

    /** Per-station utilization, demand/supply tiers and recommendation codes. */
    public static String stationPerformance(String providerId) {
        return providerId + DS_STATION_PERFORMANCE;
    }

    // ── L1/L2 chain, pre-composed for ZES ────────────────────────────────────

    /** {@code zes_stations} — see {@link #stations(String)}. */
    public static final String ZES_STATIONS = PROVIDER_ZES + DS_STATIONS;

    /** {@code zes_connector_transform} — see {@link #connectorTransform(String)}. */
    public static final String ZES_CONNECTOR_TRANSFORM = PROVIDER_ZES + DS_CONNECTOR_TRANSFORM;

    /** {@code zes_station_performance} — see {@link #stationPerformance(String)}. */
    public static final String ZES_STATION_PERFORMANCE = PROVIDER_ZES + DS_STATION_PERFORMANCE;

    // ── L3 analytics — static enrichment, not date-suffixed ──────────────────

    /** Station environment classification from OpenStreetMap (Overpass). */
    public static final String ZES_STATION_ENVIRONMENT = "zes_station_environment";

    // ── L4 intelligence — written by the Python pipeline, read by Java ───────

    /** ML feature snapshot per station. */
    public static final String ZES_STATION_FEATURES = "zes_station_features";

    /** 1-day-ahead utilization forecast. */
    public static final String ZES_STATION_FORECAST = "zes_station_forecast";

    /** Growth-potential score (0-100) per station. */
    public static final String ZES_STATION_OPPORTUNITY = "zes_station_opportunity";

    /** Observed hourly utilization, per station and segment. */
    public static final String ZES_STATION_HOURLY_PERFORMANCE = "zes_station_hourly_performance";

    /** Daily forecast disaggregated to hours; revenue base for pricing. */
    public static final String ZES_STATION_HOURLY_FORECAST = "zes_station_hourly_forecast";

    // ── Naming helpers ───────────────────────────────────────────────────────

    /** {@code prefix-yyyy-MM-dd}; {@code date == null} means today. */
    public static String nameFor(String prefix, String date) {
        return prefix + "-" + (date != null ? date : LocalDate.now().toString());
    }

    /** {@code prefix-yyyy-MM-dd}. */
    public static String nameFor(String prefix, LocalDate date) {
        return prefix + "-" + date;
    }

    /** Wildcard matching every day of one stage, e.g. for {@code cat indices}. */
    public static String allDates(String prefix) {
        return prefix + "-*";
    }

    /** Index-template name owned by the service that writes {@code prefix}. */
    public static String templateFor(String prefix) {
        return prefix + "_template";
    }

    /** Extracts {@code yyyy-MM-dd} from a concrete index name, or null. */
    public static String dateOf(String indexName, String prefix) {
        String p = prefix + "-";
        return indexName != null && indexName.startsWith(p) ? indexName.substring(p.length()) : null;
    }
}
