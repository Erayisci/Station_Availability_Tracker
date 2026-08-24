package com.charging.kernel.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConnectorStatusHistoryRepository extends JpaRepository<ConnectorStatusHistory, Long> {

    /** All history records for a given day. */
    List<ConnectorStatusHistory> findByTrackDate(LocalDate date);

    /** All open (endTime IS NULL) slots for a given day — used for end-of-day close-out. */
    List<ConnectorStatusHistory> findByTrackDateAndEndTimeIsNull(LocalDate date);

    /** The single open slot for a specific connector on a given day. */
    Optional<ConnectorStatusHistory> findByConnectorIdAndTrackDateAndEndTimeIsNull(String connectorId, LocalDate date);

    /** All open slots for a specific connector on a given day (should normally be 1, guards against duplicates). */
    List<ConnectorStatusHistory> findAllByConnectorIdAndTrackDateAndEndTimeIsNull(String connectorId, LocalDate date);

    /** Cleanup: remove history older than the given date. */
    @Transactional
    void deleteByTrackDateBefore(LocalDate date);
}
