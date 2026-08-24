package com.charging.ingestion.spi;

/**
 * One observed interval during which a connector held a single status.
 *
 * <p>The unit the whole platform is built on: L2 derives durations and
 * half-hour slots from these, L3 aggregates them, L4 trains on them.
 *
 * <h2>Why the timestamps are strings</h2>
 * They arrive as ISO-8601 local date-times (from the provider payload via H2,
 * {@code LocalDateTime.toString()}) and are written back into Elasticsearch
 * verbatim. Parsing to {@code LocalDateTime} here and re-formatting on the way
 * out would silently normalise the format — dropping or adding fractional
 * seconds — and change what lands in the index for no gain, since L2 already
 * parses them once to compute duration. Keeping them as the provider's own
 * strings makes the landing value and the indexed value identical by
 * construction.
 *
 * @param status    canonical status code — see {@link ConnectorStatus}; a
 *                  provider-specific state not covered there passes through as-is
 * @param startTime ISO-8601 local date-time the interval opened
 * @param endTime   ISO-8601 local date-time it closed, or {@code null} while
 *                  the slot is still open (closed by the end-of-day run)
 */
public record AvailabilityWindow(String status, String startTime, String endTime) {

    /** True while this window has not been closed yet. */
    public boolean isOpen() {
        return endTime == null;
    }
}
