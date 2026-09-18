package com.jobscheduler.job;

import org.springframework.data.jpa.domain.Specification;

/** Optional filters for listing jobs; a null argument means "don't filter on this". */
final class JobSpecifications {

    private JobSpecifications() {
    }

    static Specification<Job> hasStatus(JobStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    static Specification<Job> hasType(String type) {
        return (root, query, cb) -> type == null ? null : cb.equal(root.get("type"), type);
    }
}
