package com.lms.ledgersettlementmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.ledgersettlementmanagement.service.RevenueLineSplitter.LinePart;
import com.lms.ledgersettlementmanagement.service.RevenueLineSplitter.LineShare;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** RPT-01 spec 2-R.3: line split of one course allocation. */
class RevenueLineSplitterTest {

	private static final UUID L1 = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final UUID L2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

	private static final UUID L3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

	private static BigDecimal d(String value) {
		return new BigDecimal(value);
	}

	private static BigDecimal sum(List<LinePart> parts) {
		return parts.stream().map(LinePart::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	@Test
	void splitsProRataByLineShareAndSumsExactlyToTheAllocation() {
		List<LinePart> parts = RevenueLineSplitter.split(d("7000.00"),
				List.of(new LineShare(L1, 1, d("3500.00")), new LineShare(L2, 2, d("3500.00"))));
		assertThat(parts).extracting(LinePart::amount).containsExactly(d("3500.00"), d("3500.00"));
	}

	@Test
	void roundingRemainderGoesToTheLargestShareTiesLowestLineNo() {
		// 100.00 over three equal lines: 33.33 x 3 = 99.99, remainder 0.01 -> line_no 1.
		List<LinePart> equal = RevenueLineSplitter.split(d("100.00"), List.of(new LineShare(L3, 3, d("1.00")),
				new LineShare(L1, 1, d("1.00")), new LineShare(L2, 2, d("1.00"))));
		assertThat(sum(equal)).isEqualByComparingTo("100.00");
		assertThat(equal).filteredOn(p -> p.lineNo() == 1).extracting(LinePart::amount).containsExactly(d("33.34"));
		// Unequal: the largest share takes the remainder.
		List<LinePart> unequal = RevenueLineSplitter.split(d("-10.00"),
				List.of(new LineShare(L1, 1, d("1.00")), new LineShare(L2, 2, d("2.00"))));
		assertThat(sum(unequal)).isEqualByComparingTo("-10.00");
		assertThat(unequal).extracting(LinePart::amount).containsExactly(d("-3.33"), d("-6.67"));
	}

	@Test
	void zeroShareCourseOrZeroAmountGetsNoLinesAndZeroPartsAreOmitted() {
		assertThat(RevenueLineSplitter.split(d("0.00"), List.of(new LineShare(L1, 1, d("5.00"))))).isEmpty();
		assertThat(RevenueLineSplitter.split(d("10.00"), List.of(new LineShare(L1, 1, d("0.00"))))).isEmpty();
		// A 100% discount reward line (share 0) next to a paid line gets no part.
		List<LinePart> parts = RevenueLineSplitter.split(d("900.00"),
				List.of(new LineShare(L1, 1, d("900.00")), new LineShare(L2, 2, d("0.00"))));
		assertThat(parts).extracting(LinePart::lineId).containsExactly(L1);
	}

}
