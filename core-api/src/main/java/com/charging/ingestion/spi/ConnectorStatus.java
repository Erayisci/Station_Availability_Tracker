package com.charging.ingestion.spi;

/**
 * The canonical connector-status vocabulary — the three states the platform
 * gives meaning to, independent of any provider's own status names.
 *
 * <h2>Why an enum with an explicit wire code</h2>
 * The status string is not internal: it is written into
 * {@code {provider}_connector_transform-{date}.availability.status} and read
 * from there by L3 analytics, the Python intelligence service
 * ({@code config.CHARGING_STATUS}) and three console chart components that use
 * it as a Recharts {@code dataKey}. The wire codes below are therefore a
 * published contract and must not be renamed — {@link #wire()} exists so the
 * literal appears in exactly one place instead of being retyped at each
 * comparison site.
 *
 * <h2>Deliberately not exhaustive</h2>
 * A provider may report states beyond these three (faulted, reserved,
 * out-of-service, vendor-specific codes). Those are <b>passed through
 * untranslated</b> by the mapper rather than collapsed into a catch-all,
 * because L3 aggregates statuses by name — a {@code terms} aggregation over
 * whatever values exist — so folding unknown states together would erase
 * information the analytics layer legitimately reports. This enum names only
 * what the platform's own arithmetic depends on:
 * {@link #CHARGING} drives utilization, {@link #AVAILABLE} drives idle time.
 *
 * <p>A mapper's obligation is therefore narrow: translate its provider's names
 * for <em>these three</em> states to the codes below, and emit anything else
 * verbatim.
 */
public enum ConnectorStatus {

    /** Connector is free and usable. Sums into {@code total_available_minutes}. */
    AVAILABLE("Available"),

    /** Connector is delivering energy. Sums into {@code total_charging_minutes}
     *  and is the numerator of {@code charging_utilization_pct}. */
    CHARGING("Charging"),

    /** No observation covers this interval — the initial state of every
     *  connector at 00:00 and the gap filler L3 uses so per-slot totals stay
     *  constant. Never reported by a provider; synthesised by the platform. */
    UNKNOWN("Unknown");

    private final String wire;

    ConnectorStatus(String wire) {
        this.wire = wire;
    }

    /**
     * The exact string this status is stored and compared as. Stable contract —
     * see the class note before changing.
     */
    public String wire() {
        return wire;
    }

    /** True when {@code status} is the canonical charging code. Null-safe. */
    public static boolean isCharging(String status) {
        return CHARGING.wire.equals(status);
    }

    /** True when {@code status} is the canonical available code. Null-safe. */
    public static boolean isAvailable(String status) {
        return AVAILABLE.wire.equals(status);
    }
}
