package com.charging.astor.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "astor_station_status_history", indexes = {
        @Index(name = "idx_astor_track_date",       columnList = "trackDate"),
        @Index(name = "idx_astor_station_date_open", columnList = "stationId, trackDate")
})
public class AstorStationStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String stationId;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime; // null = slot currently open
    private LocalDate trackDate;

    public Long getId()                        { return id; }
    public String getStationId()               { return stationId; }
    public void setStationId(String v)         { this.stationId = v; }
    public String getStatus()                  { return status; }
    public void setStatus(String v)            { this.status = v; }
    public LocalDateTime getStartTime()        { return startTime; }
    public void setStartTime(LocalDateTime v)  { this.startTime = v; }
    public LocalDateTime getEndTime()          { return endTime; }
    public void setEndTime(LocalDateTime v)    { this.endTime = v; }
    public LocalDate getTrackDate()            { return trackDate; }
    public void setTrackDate(LocalDate v)      { this.trackDate = v; }
}
