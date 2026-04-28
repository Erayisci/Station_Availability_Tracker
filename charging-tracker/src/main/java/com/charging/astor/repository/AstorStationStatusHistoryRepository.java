package com.charging.astor.repository;

import com.charging.astor.model.AstorStationStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface AstorStationStatusHistoryRepository extends JpaRepository<AstorStationStatusHistory, Long> {

    /** All history records for a given day. */
    List<AstorStationStatusHistory> findByTrackDate(LocalDate date);

    /** All open (endTime IS NULL) slots for a given day — used for PostConstruct restore and end-of-day close-out. */
    List<AstorStationStatusHistory> findByTrackDateAndEndTimeIsNull(LocalDate date);

    /** All open slots for a specific station on a given day (should normally be 1). */
    List<AstorStationStatusHistory> findAllByStationIdAndTrackDateAndEndTimeIsNull(String stationId, LocalDate date);

    /** Cleanup: remove history older than the given date. */
    @Transactional
    void deleteByTrackDateBefore(LocalDate date);
}
