package com.jobscheduler.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RecurringJobRepository extends JpaRepository<RecurringJob, UUID> {
}
