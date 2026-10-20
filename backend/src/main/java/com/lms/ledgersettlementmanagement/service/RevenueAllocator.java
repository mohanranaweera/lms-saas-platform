package com.lms.ledgersettlementmanagement.service;

import com.lms.ledgersettlementmanagement.api.LedgerRevenueAllocation;
import com.lms.paymentmanagement.api.OrderRevenueBasis;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ADR-029 allocation of one ledger entry to courses - pure, deterministic,
 * derived only from the order's immutable snapshots.
 *
 * <ul>
 * <li>Legacy order: the whole amount to {@code order.course_id}.</li>
 * <li>Line order: each course gets {@code amount x share / orderAmount}
 * (2 dp, HALF_UP). For a confirmed payment ({@code amount = orderAmount})
 * that is exactly the course's line-total share; a refund/chargeback is split
 * pro rata to the same shares. The postage part of the amount is never
 * allocated. The attributable total is {@code amount x sum(shares) /
 * orderAmount} (2 dp, HALF_UP); the rounding remainder goes to the largest
 * share (ties: lowest course id), so allocations sum exactly to it.</li>
 * </ul>
 */
final class RevenueAllocator {

	private RevenueAllocator() {
	}

	static List<LedgerRevenueAllocation> allocate(BigDecimal amount, OrderRevenueBasis basis) {
		if (basis == null || amount == null) {
			return List.of();
		}
		if (!basis.lineOrder()) {
			return basis.legacyCourseId() == null ? List.of()
					: List.of(new LedgerRevenueAllocation(basis.legacyCourseId(), amount));
		}
		BigDecimal orderAmount = basis.orderAmount();
		if (orderAmount == null || orderAmount.signum() <= 0 || amount.signum() == 0) {
			return List.of();
		}
		BigDecimal magnitude = amount.abs();
		List<Map.Entry<UUID, BigDecimal>> shares = new ArrayList<>(basis.courseShares().entrySet());
		// Largest share first; ties by lowest course id - the remainder target.
		shares.sort(Comparator.<Map.Entry<UUID, BigDecimal>, BigDecimal>comparing(Map.Entry::getValue)
			.reversed()
			.thenComparing(Map.Entry::getKey));
		BigDecimal shareTotal = BigDecimal.ZERO;
		for (Map.Entry<UUID, BigDecimal> share : shares) {
			shareTotal = shareTotal.add(share.getValue());
		}
		BigDecimal attributable = magnitude.multiply(shareTotal).divide(orderAmount, 2, RoundingMode.HALF_UP);
		if (attributable.compareTo(magnitude) > 0) {
			attributable = magnitude.setScale(2, RoundingMode.HALF_UP);
		}
		List<BigDecimal> parts = new ArrayList<>();
		BigDecimal allocated = BigDecimal.ZERO;
		for (Map.Entry<UUID, BigDecimal> share : shares) {
			BigDecimal part = magnitude.multiply(share.getValue()).divide(orderAmount, 2, RoundingMode.HALF_UP);
			parts.add(part);
			allocated = allocated.add(part);
		}
		if (!parts.isEmpty()) {
			parts.set(0, parts.get(0).add(attributable.subtract(allocated)));
		}
		List<LedgerRevenueAllocation> result = new ArrayList<>();
		for (int i = 0; i < shares.size(); i++) {
			BigDecimal part = parts.get(i);
			result.add(new LedgerRevenueAllocation(shares.get(i).getKey(),
					amount.signum() < 0 ? part.negate() : part));
		}
		result.sort(Comparator.comparing(LedgerRevenueAllocation::courseId));
		return List.copyOf(result);
	}

}
