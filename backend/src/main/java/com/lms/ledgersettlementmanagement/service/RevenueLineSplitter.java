package com.lms.ledgersettlementmanagement.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * RPT-01 spec 2-R.3 - splits ONE course allocation produced by {@link
 * RevenueAllocator} across that course's order lines, never recomputing the
 * allocation from the order: each line gets {@code |amount| x lineShare /
 * courseShare} (2 dp, HALF_UP), the rounding remainder goes to the largest
 * line share (ties: lowest {@code line_no}), and the sign of the allocation is
 * kept. A course whose shares sum to 0 gets no lines; zero parts are omitted.
 * The parts therefore always sum exactly to the allocation. Pure and
 * deterministic.
 */
final class RevenueLineSplitter {

	private RevenueLineSplitter() {
	}

	/** One order line of the course: its id, number and A2 share ({@code line_total - fee}). */
	record LineShare(UUID lineId, int lineNo, BigDecimal share) {

	}

	record LinePart(UUID lineId, int lineNo, BigDecimal amount) {

	}

	static List<LinePart> split(BigDecimal allocation, List<LineShare> lines) {
		if (allocation == null || allocation.signum() == 0 || lines == null || lines.isEmpty()) {
			return List.of();
		}
		BigDecimal total = BigDecimal.ZERO;
		for (LineShare line : lines) {
			total = total.add(line.share());
		}
		if (total.signum() <= 0) {
			return List.of();
		}
		BigDecimal magnitude = allocation.abs().setScale(2, RoundingMode.HALF_UP);
		List<BigDecimal> parts = new ArrayList<>();
		BigDecimal allocated = BigDecimal.ZERO;
		int target = -1;
		for (int i = 0; i < lines.size(); i++) {
			LineShare line = lines.get(i);
			BigDecimal part = magnitude.multiply(line.share()).divide(total, 2, RoundingMode.HALF_UP);
			parts.add(part);
			allocated = allocated.add(part);
			if (target < 0) {
				target = i;
			}
			else {
				int byShare = line.share().compareTo(lines.get(target).share());
				if (byShare > 0 || (byShare == 0 && line.lineNo() < lines.get(target).lineNo())) {
					target = i;
				}
			}
		}
		parts.set(target, parts.get(target).add(magnitude.subtract(allocated)));
		List<LinePart> result = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++) {
			BigDecimal part = parts.get(i);
			if (part.signum() != 0) {
				result.add(new LinePart(lines.get(i).lineId(), lines.get(i).lineNo(),
						allocation.signum() < 0 ? part.negate() : part));
			}
		}
		return List.copyOf(result);
	}

}
