package com.lms.paymentmanagement.order.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.paymentmanagement.order.domain.StudentOrderLine;
import jakarta.persistence.criteria.Predicate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped (ADR-006), insert-only access to {@code student_order_line}
 * (V87, KLASS-T4c). Finders are {@code default} methods over the inherited,
 * structurally tenant-filtered {@code findAll}; deletes are refused (order
 * lines are financial history). The entity itself is {@code @Immutable}, so
 * {@code save} can only ever insert.
 */
public interface StudentOrderLineRepository extends AppendOnlyTenantAwareRepository<StudentOrderLine, UUID> {

	Sort LINE_ORDER = Sort.by("lineNo");

	default List<StudentOrderLine> findAllByOrderId(UUID orderId) {
		return findAll((root, query, cb) -> cb.equal(root.get("orderId"), orderId), LINE_ORDER);
	}

	default List<StudentOrderLine> findAllByOrderIdIn(Collection<UUID> orderIds) {
		if (orderIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("orderId").in(orderIds), LINE_ORDER);
	}

	/** ADR-029: every line for {@code courseId} (tenant-scoped via the inherited {@code findAll}). */
	default List<StudentOrderLine> findAllByCourseId(UUID courseId) {
		return findAll((root, query, cb) -> cb.equal(root.get("courseId"), courseId), LINE_ORDER);
	}

	default boolean existsByOrderId(UUID orderId) {
		return exists((root, query, cb) -> cb.equal(root.get("orderId"), orderId));
	}

	/**
	 * RPT-01: {@code MONTHLY} lines billed in one of {@code months} (each
	 * month an equality pair on {@code period_year}/{@code period_month};
	 * callers cap the set at 36). Tenant-scoped via the inherited {@code findAll}.
	 */
	default List<StudentOrderLine> findMonthlyLinesInMonths(Set<YearMonth> months) {
		if (months.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> {
			List<Predicate> anyMonth = new ArrayList<>();
			for (YearMonth month : months) {
				anyMonth.add(cb.and(cb.equal(root.get("periodYear"), (short) month.getYear()),
						cb.equal(root.get("periodMonth"), (short) month.getMonthValue())));
			}
			return cb.and(cb.equal(root.get("scope"), "MONTHLY"), cb.or(anyMonth.toArray(Predicate[]::new)));
		});
	}

	/** RPT-01: {@code LESSON} lines for any of {@code lessonIds} (tenant-scoped). */
	default List<StudentOrderLine> findLessonLinesForLessons(Collection<UUID> lessonIds) {
		if (lessonIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("scope"), "LESSON"),
				root.get("lessonId").in(lessonIds)));
	}

}
