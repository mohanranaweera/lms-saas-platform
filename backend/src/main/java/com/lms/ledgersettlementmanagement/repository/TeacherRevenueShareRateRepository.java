package com.lms.ledgersettlementmanagement.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.ledgersettlementmanagement.domain.TeacherRevenueShareRate;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/** Tenant-scoped (ADR-006), append-only. No method accepts a tenant id. */
public interface TeacherRevenueShareRateRepository extends AppendOnlyTenantAwareRepository<TeacherRevenueShareRate, UUID> {

	/** Newest first; {@code teacherId == null} lists every teacher's history. */
	default List<TeacherRevenueShareRate> findHistory(UUID teacherId) {
		Sort sort = Sort.by(Sort.Direction.DESC, "effectiveFrom");
		if (teacherId == null) {
			return findAll(sort);
		}
		return findAll((root, query, cb) -> cb.equal(root.get("teacherId"), teacherId), sort);
	}

	/** The rate in effect on {@code date}: latest {@code effectiveFrom <= date}. */
	default Optional<TeacherRevenueShareRate> findEffectiveOn(UUID teacherId, LocalDate date) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("teacherId"), teacherId),
				cb.lessThanOrEqualTo(root.get("effectiveFrom"), date)),
				Sort.by(Sort.Direction.DESC, "effectiveFrom"))
			.stream()
			.findFirst();
	}

	/** Any rate that STARTS strictly after {@code afterExclusive} and on/before {@code toInclusive}. */
	default boolean existsStartingWithin(UUID teacherId, LocalDate afterExclusive, LocalDate toInclusive) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("teacherId"), teacherId),
				cb.greaterThan(root.get("effectiveFrom"), afterExclusive),
				cb.lessThanOrEqualTo(root.get("effectiveFrom"), toInclusive)));
	}

	default boolean existsForTeacherOn(UUID teacherId, LocalDate effectiveFrom) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("teacherId"), teacherId),
				cb.equal(root.get("effectiveFrom"), effectiveFrom)));
	}

}
