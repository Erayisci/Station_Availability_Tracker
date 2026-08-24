package com.charging.ingestion.spi;

import java.util.List;
import java.util.Map;

/**
 * Translates one provider's raw station payload into canonical
 * {@link ConnectorRow}s — the only place a provider's field names, nesting depth
 * and status vocabulary are understood.
 *
 * <h2>Why this is a separate capability from polling</h2>
 * {@link StationStatusProvider} acquires data live; this interprets a payload
 * that has already landed. Splitting them is what lets L2 re-derive history:
 * the raw daily snapshot ({@code {provider}_stations-{date}}) is kept verbatim,
 * and a rebuild months later re-reads it through this mapper without the
 * provider's API being reachable — or still existing in that shape. A provider
 * whose API is gone can keep a mapper for its archived data and drop its poller.
 *
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><b>Total.</b> Every connector present in the payload yields exactly one
 *       row. A station with no EVSEs yields none — return empty, never null.</li>
 *   <li><b>Pure.</b> No I/O, no clock, no shared state. Called concurrently
 *       while scrolling a day's snapshot, and called again during backfill, so
 *       the same input must always produce the same rows.</li>
 *   <li><b>Lenient.</b> A malformed or partial station must not throw: absent
 *       fields become {@code null}. One bad document cannot be allowed to abort
 *       a day's build.</li>
 *   <li><b>Canonicalising.</b> Translate the provider's names for
 *       {@link ConnectorStatus#AVAILABLE} and {@link ConnectorStatus#CHARGING}
 *       to their wire codes; pass any other state through verbatim.</li>
 * </ul>
 */
public interface StationPayloadMapper extends ProviderScoped {

    /**
     * Flattens one raw station document to canonical connector rows.
     *
     * @param rawStationDocument the provider payload as it was landed — for ZES,
     *                           a document from {@code zes_stations-{date}}
     * @return one row per connector, possibly empty; never {@code null}
     */
    List<ConnectorRow> toConnectorRows(Map<String, Object> rawStationDocument);
}
