package com.midas.d3.persistence.repository;

import com.midas.d3.persistence.entity.MidasEvolutionLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link MidasEvolutionLogEntity}.
 *
 * <p>All write operations are handled by {@link com.midas.d3.evolution.ContinuousImprovementService}.
 * Dashboard queries are served by {@link com.midas.d3.web.DashboardService}.
 */
@Repository
public interface MidasEvolutionLogRepository extends JpaRepository<MidasEvolutionLogEntity, UUID> {

    /**
     * Returns all evolution log entries ordered by creation date descending (newest first).
     * Used to populate the "История изменений" tab on the frontend dashboard.
     *
     * @return list of log entries; empty when no evolution cycles have run
     */
    List<MidasEvolutionLogEntity> findAllByOrderByCreatedAtDesc();
}
