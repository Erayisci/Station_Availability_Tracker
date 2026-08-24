package com.charging.kernel.persistence;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "connector_status_history", indexes = {
        @Index(name = "idx_track_date", columnList = "trackDate"),
        @Index(name = "idx_connector_date_open", columnList = "connectorId, trackDate")
})
public class ConnectorStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String stationId;
    private String evseId;
    private String connectorId;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime; // null = slot currently open
    private LocalDate trackDate;

    public Long getId()                        { return id; }
    public String getStationId()               { return stationId; }
    public void setStationId(String v)         { this.stationId = v; }
    public String getEvseId()                  { return evseId; }
    public void setEvseId(String v)            { this.evseId = v; }
    public String getConnectorId()             { return connectorId; }
    public void setConnectorId(String v)       { this.connectorId = v; }
    public String getStatus()                  { return status; }
    public void setStatus(String v)            { this.status = v; }
    public LocalDateTime getStartTime()        { return startTime; }
    public void setStartTime(LocalDateTime v)  { this.startTime = v; }
    public LocalDateTime getEndTime()          { return endTime; }
    public void setEndTime(LocalDateTime v)    { this.endTime = v; }
    public LocalDate getTrackDate()            { return trackDate; }
    public void setTrackDate(LocalDate v)      { this.trackDate = v; }
}
