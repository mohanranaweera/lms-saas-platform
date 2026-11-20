package com.lms.financeexpensemanagement.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.financeexpensemanagement.domain.FinanceEnums.SourceType;
import com.lms.financeexpensemanagement.domain.FinanceEnums.TransactionStatus;
import com.lms.financeexpensemanagement.domain.FinanceEnums.TransactionType;
import com.lms.financeexpensemanagement.domain.FinanceTransaction;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * FIN-01: the account book is append-only. Every native aggregate below takes
 * {@code tenantId} explicitly (ADR-006: custom queries apply the tenant filter
 * themselves); callers pass the resolved context value, never client input.
 */
public interface FinanceTransactionRepository extends AppendOnlyTenantAwareRepository<FinanceTransaction, UUID> {

	default Optional<FinanceTransaction> findBySource(SourceType sourceType, UUID sourceId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("sourceType"), sourceType),
				cb.equal(root.get("sourceId"), sourceId)));
	}

	default Optional<FinanceTransaction> findByIdempotencyKey(String key) {
		return findOne((root, query, cb) -> cb.equal(root.get("idempotencyKey"), key));
	}

	default List<FinanceTransaction> findReversalsOf(UUID transactionId) {
		return findAll((root, query, cb) -> cb.equal(root.get("reversesTransactionId"), transactionId));
	}

	default List<FinanceTransaction> findUnposted() {
		return findAll((root, query, cb) -> cb.equal(root.get("status"), TransactionStatus.UNPOSTED),
				org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC,
						"createdAt"));
	}

	default boolean existsForAccount(UUID accountId) {
		return exists((root, query, cb) -> cb.or(cb.equal(root.get("fromAccountId"), accountId),
				cb.equal(root.get("toAccountId"), accountId)));
	}

	default Page<FinanceTransaction> search(String search, TransactionType type, UUID accountId, UUID categoryId,
			TransactionStatus status, LocalDate from, LocalDate to, Pageable pageable) {
		return findAll((root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (search != null && !search.isBlank()) {
				String like = "%" + search.trim().toLowerCase() + "%";
				predicates.add(cb.or(cb.like(cb.lower(root.get("note")), like),
						cb.like(cb.lower(root.get("reason")), like)));
			}
			if (type != null) {
				predicates.add(cb.equal(root.get("type"), type));
			}
			if (accountId != null) {
				predicates.add(cb.or(cb.equal(root.get("fromAccountId"), accountId),
						cb.equal(root.get("toAccountId"), accountId)));
			}
			if (categoryId != null) {
				predicates.add(cb.equal(root.get("categoryId"), categoryId));
			}
			if (status != null) {
				predicates.add(cb.equal(root.get("status"), status));
			}
			if (from != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("transactionDate"), from));
			}
			if (to != null) {
				predicates.add(cb.lessThanOrEqualTo(root.get("transactionDate"), to));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		}, pageable);
	}

	/** Balance of one account = credits - debits of its non-UNPOSTED rows. */
	@Query(value = """
			SELECT COALESCE(SUM(CASE WHEN t.to_account_id = :accountId THEN t.amount ELSE 0 END), 0)
			     - COALESCE(SUM(CASE WHEN t.from_account_id = :accountId THEN t.amount ELSE 0 END), 0)
			FROM finance_transaction t
			WHERE t.tenant_id = :tenantId AND t.status <> 'UNPOSTED'
			  AND (t.to_account_id = :accountId OR t.from_account_id = :accountId)
			""", nativeQuery = true)
	BigDecimal balanceOf(@Param("tenantId") UUID tenantId, @Param("accountId") UUID accountId);

	/** [account_id, balance] for every account of the tenant that has at least one posting. */
	@Query(value = """
			SELECT a.account_id, SUM(a.effect) FROM (
			    SELECT t.to_account_id AS account_id, t.amount AS effect
			    FROM finance_transaction t
			    WHERE t.tenant_id = :tenantId AND t.status <> 'UNPOSTED' AND t.to_account_id IS NOT NULL
			    UNION ALL
			    SELECT t.from_account_id AS account_id, -t.amount AS effect
			    FROM finance_transaction t
			    WHERE t.tenant_id = :tenantId AND t.status <> 'UNPOSTED' AND t.from_account_id IS NOT NULL
			) a GROUP BY a.account_id
			""", nativeQuery = true)
	List<Object[]> balancesByAccount(@Param("tenantId") UUID tenantId);

	/**
	 * Period activity rows: [effective_type, category_id, source_type, sign, credit, SUM(amount), COUNT(*)].
	 * A REVERSAL is folded into the row it reverses (same type/category, sign -1) so it cancels
	 * the original in every bucket; a REFUND keeps its own type (negative income).
	 */
	@Query(value = """
			SELECT COALESCE(o.type, t.type) AS eff_type,
			       COALESCE(o.category_id, t.category_id) AS category_id,
			       COALESCE(o.source_type, t.source_type) AS source_type,
			       CASE WHEN t.type = 'REVERSAL' THEN -1 ELSE 1 END AS sign,
			       (COALESCE(o.to_account_id, t.to_account_id) IS NOT NULL) AS credit,
			       SUM(t.amount), COUNT(*)
			FROM finance_transaction t
			LEFT JOIN finance_transaction o
			       ON o.tenant_id = t.tenant_id AND o.id = t.reverses_transaction_id AND t.type = 'REVERSAL'
			WHERE t.tenant_id = :tenantId AND t.status <> 'UNPOSTED'
			  AND t.transaction_date >= :from AND t.transaction_date <= :to
			GROUP BY 1, 2, 3, 4, 5
			""", nativeQuery = true)
	List<Object[]> activityByTypeAndCategory(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from,
			@Param("to") LocalDate to);

	/** Trend rows: [transaction_date, effective_type, sign, credit, SUM(amount)] (same folding as above). */
	@Query(value = """
			SELECT t.transaction_date,
			       COALESCE(o.type, t.type) AS eff_type,
			       CASE WHEN t.type = 'REVERSAL' THEN -1 ELSE 1 END AS sign,
			       (COALESCE(o.to_account_id, t.to_account_id) IS NOT NULL) AS credit,
			       SUM(t.amount)
			FROM finance_transaction t
			LEFT JOIN finance_transaction o
			       ON o.tenant_id = t.tenant_id AND o.id = t.reverses_transaction_id AND t.type = 'REVERSAL'
			WHERE t.tenant_id = :tenantId AND t.status <> 'UNPOSTED'
			  AND t.transaction_date >= :from AND t.transaction_date <= :to
			GROUP BY 1, 2, 3, 4
			ORDER BY 1
			""", nativeQuery = true)
	List<Object[]> activityByDate(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from,
			@Param("to") LocalDate to);

	/** Posted (non-UNPOSTED) student-payment income in the range, for the Student Income population note. */
	@Query(value = """
			SELECT COALESCE(SUM(CASE WHEN t.type = 'INCOME' THEN t.amount ELSE -t.amount END), 0), COUNT(*)
			FROM finance_transaction t
			WHERE t.tenant_id = :tenantId AND t.status <> 'UNPOSTED' AND t.source_type = 'LEDGER_ENTRY'
			  AND t.transaction_date >= :from AND t.transaction_date <= :to
			""", nativeQuery = true)
	List<Object[]> postedStudentIncome(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from,
			@Param("to") LocalDate to);

	@Query(value = "SELECT COUNT(*) FROM finance_transaction t WHERE t.tenant_id = :tenantId AND t.status = 'UNPOSTED'",
			nativeQuery = true)
	long countUnposted(@Param("tenantId") UUID tenantId);

}
