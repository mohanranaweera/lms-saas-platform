package com.lms.ledgersettlementmanagement.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlement;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlementKind;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlementStatus;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Tenant-scoped (ADR-006), append-only. No method accepts a tenant id. */
public interface TeacherSettlementRepository extends AppendOnlyTenantAwareRepository<TeacherSettlement, UUID> {

	/** A REGULAR settlement for this teacher whose period overlaps {@code [start, end]}. */
	default boolean existsOverlappingRegular(UUID teacherId, LocalDate start, LocalDate end) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("teacherId"), teacherId),
				cb.equal(root.get("kind"), TeacherSettlementKind.REGULAR),
				cb.lessThanOrEqualTo(root.get("periodStart"), end),
				cb.greaterThanOrEqualTo(root.get("periodEnd"), start)));
	}

	default Page<TeacherSettlement> search(UUID teacherId, TeacherSettlementStatus status,
			TeacherSettlementKind kind, Pageable pageable) {
		return findAll((root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (teacherId != null) {
				predicates.add(cb.equal(root.get("teacherId"), teacherId));
			}
			if (status != null) {
				predicates.add(cb.equal(root.get("status"), status));
			}
			if (kind != null) {
				predicates.add(cb.equal(root.get("kind"), kind));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		}, pageable);
	}

	default List<TeacherSettlement> findAdjustmentsOf(UUID settlementId) {
		return findAll((root, query, cb) -> cb.equal(root.get("adjustsSettlementId"), settlementId),
				Sort.by(Sort.Direction.ASC, "calculatedAt"));
	}

	default List<TeacherSettlement> findAdjustmentsOfAny(List<UUID> settlementIds) {
		if (settlementIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("adjustsSettlementId").in(settlementIds));
	}

}
