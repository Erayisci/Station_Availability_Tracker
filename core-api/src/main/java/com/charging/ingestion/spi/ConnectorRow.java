package com.charging.ingestion.spi;

import java.util.List;

/**
 * One connector, with the day's observed status windows — the canonical unit
 * crossing the L1 → L2 boundary.
 *
 * <h2>Why the platform's grain is the connector, not the station</h2>
 * A provider's payload nests station → EVSE → connector, but every metric above
 * L1 is per-connector or an aggregate of connectors: capacity is
 * {@code connector_count × 1440} minutes, utilization is charging minutes over
 * that capacity. Flattening to this grain at the boundary means no layer above
 * has to walk a nesting depth that differs per provider — the two-level
 * station/EVSE/connector shape is a ZES detail, not a domain fact. Station
 * attributes are denormalised onto each row for the same reason L2 already
 * denormalises them into Elasticsearch: the consumers filter and group by them,
 * and there is no join.
 *
 * <h2>Boxed types are deliberate</h2>
 * Every optional field is a wrapper, not a primitive: a provider that does not
 * report {@code maxPowerKw} must be able to say "absent" rather than "0", which
 * would silently become a real value in a power-weighted aggregate. Absent
 * fields are written to Elasticsearch as JSON {@code null}, which the mappings
 * accept.
 *
 * @param connectorId       provider-unique connector id; becomes the ES document id
 * @param connectorType     plug standard, e.g. {@code CCS}, {@code Type2}
 * @param connectorNo       ordinal of the connector on its EVSE
 * @param maxPowerKw        rated power in kW
 * @param stationId         provider-unique station id
 * @param stationName       display name
 * @param address           free-text address
 * @param cityName          district / town
 * @param provinceName      province, the primary geographic facet in the console
 * @param lat               WGS-84 latitude
 * @param lng               WGS-84 longitude
 * @param distance          provider-reported distance from the poll origin, if any
 * @param openingTime       opening time as reported, {@code null} when 24/7
 * @param closingTime       closing time as reported
 * @param isRestricted      access limited to a subset of users
 * @param isRoaming         station belongs to a roaming partner, not the operator
 * @param evseId            parent EVSE id — retained for traceability
 * @param evseLocationDesc  free-text placement note, e.g. bay number
 * @param isAutocharge      plug-and-charge supported
 * @param observedDate      the date (yyyy-MM-dd) these windows were observed on
 * @param availability      the day's status windows, chronological
 */
public record ConnectorRow(
        String  connectorId,
        String  connectorType,
        Integer connectorNo,
        Integer maxPowerKw,
        String  stationId,
        String  stationName,
        String  address,
        String  cityName,
        String  provinceName,
        Double  lat,
        Double  lng,
        Double  distance,
        String  openingTime,
        String  closingTime,
        Boolean isRestricted,
        Boolean isRoaming,
        String  evseId,
        String  evseLocationDesc,
        Boolean isAutocharge,
        String  observedDate,
        List<AvailabilityWindow> availability) {

    /** True when the station's coordinates are usable for spatial work. */
    public boolean hasCoordinates() {
        return lat != null && lng != null;
    }
}
