package com.fourkites.event.scheduler.repository;

import com.fourkites.event.scheduler.model.EventHistory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventHistoryRepository extends JpaRepository<EventHistory, UUID> {
}
