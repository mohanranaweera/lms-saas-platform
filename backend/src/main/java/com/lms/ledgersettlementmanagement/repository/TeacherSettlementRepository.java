package com.lms.ledgersettlementmanagement.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.ledgersettlementmanagement.api.InstantRange;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlement;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlementKind;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlementStatus;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
			else {
				// Wallet entries (V123) are listed by the wallet, not as settlements.
				predicates.add(root.get("kind").in(TeacherSettlementKind.REGULAR, TeacherSettlementKind.ADJUSTMENT));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		}, pageable);
	}

	/**
	 * RPT-01: PAID rows (REGULAR and ADJUSTMENT) of {@code teacherIds} whose
	 * {@code paidAt} falls in any of {@code windows}. Tenant-scoped.
	 */
	default List<TeacherSettlement> findPaidInWindows(Collection<UUID> teacherIds, List<InstantRange> windows) {
		if (teacherIds.isEmpty() || windows.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> {
			List<Predicate> any = new ArrayList<>();
			for (InstantRange window : windows) {
				any.add(cb.and(cb.greaterThanOrEqualTo(root.get("paidAt"), window.fromInclusive()),
						cb.lessThan(root.get("paidAt"), window.toExclusive())));
			}
			return cb.and(root.get("teacherId").in(teacherIds),
					cb.equal(root.get("status"), TeacherSettlementStatus.PAID), cb.or(any.toArray(Predicate[]::new)));
		}, Sort.by(Sort.Direction.ASC, "paidAt"));
	}

	/**
	 * TEACHER-WALLET-01: the signed amounts that make up the wallet balance (amount payable to the
	 * teacher) - unpaid settlements/adjustments (CALCULATED) and wallet entries (POSTED).
	 */
	default List<BigDecimal> findPayableAmounts(UUID teacherId) {
		return findAttributeValues((root, query, cb) -> cb.and(cb.equal(root.get("teacherId"), teacherId),
				root.get("status").in(TeacherSettlementStatus.CALCULATED, TeacherSettlementStatus.POSTED)),
				"shareAmount", BigDecimal.class);
	}

	/** Unpaid (CALCULATED) settlement and settlement-adjustment amounts - the settlement part of the balance. */
	default List<BigDecimal> findUnpaidSettlementAmounts(UUID teacherId) {
		return findAttributeValues((root, query, cb) -> cb.and(cb.equal(root.get("teacherId"), teacherId),
				cb.equal(root.get("status"), TeacherSettlementStatus.CALCULATED)), "shareAmount", BigDecimal.class);
	}

	default Optional<TeacherSettlement> findByIdempotencyKey(String idempotencyKey) {
		return findOne((root, query, cb) -> cb.equal(root.get("idempotencyKey"), idempotencyKey));
	}

	default Page<TeacherSettlement> findWalletEntries(UUID teacherId, Collection<TeacherSettlementKind> kinds,
			Pageable pageable) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("teacherId"), teacherId),
				root.get("kind").in(kinds)), pageable);
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
