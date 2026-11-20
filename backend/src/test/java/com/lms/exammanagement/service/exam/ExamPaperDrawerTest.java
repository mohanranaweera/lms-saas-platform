package com.lms.exammanagement.service.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ExamPaperDrawerTest {

	private static final List<Integer> BANK = IntStream.rangeClosed(1, 10).boxed().toList();

	@Test
	void drawsExactlyTheQuotaWithoutDuplicatesInBankOrder() {
		for (int count = 0; count <= BANK.size(); count++) {
			List<Integer> picked = ExamPaperDrawer.pick(BANK, count, new Random(count));
			assertThat(picked).hasSize(count);
			assertThat(new HashSet<>(picked)).hasSize(count);
			assertThat(picked).isSorted();
			assertThat(BANK).containsAll(picked);
		}
	}

	@Test
	void theSameSeedGivesTheSameDrawAndDifferentSeedsVary() {
		assertThat(ExamPaperDrawer.pick(BANK, 4, new Random(42))).isEqualTo(ExamPaperDrawer.pick(BANK, 4, new Random(42)));
		var distinct = new HashSet<List<Integer>>();
		for (long seed = 0; seed < 50; seed++) {
			distinct.add(ExamPaperDrawer.pick(BANK, 4, new Random(seed)));
		}
		assertThat(distinct.size()).isGreaterThan(10);
	}

	@Test
	void rejectsImpossibleQuotas() {
		assertThatThrownBy(() -> ExamPaperDrawer.pick(BANK, 11, new Random(1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ExamPaperDrawer.pick(BANK, -1, new Random(1)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void windowStatusBoundaries() {
		Instant start = Instant.parse("2026-11-15T09:00:00Z");
		Instant end = Instant.parse("2026-11-15T10:00:00Z");
		assertThat(ExamWindowStatus.of(start.minusNanos(1), start, end)).isEqualTo(ExamWindowStatus.UPCOMING);
		assertThat(ExamWindowStatus.of(start, start, end)).isEqualTo(ExamWindowStatus.IN_PROGRESS);
		assertThat(ExamWindowStatus.of(end.minusNanos(1), start, end)).isEqualTo(ExamWindowStatus.IN_PROGRESS);
		assertThat(ExamWindowStatus.of(end, start, end)).isEqualTo(ExamWindowStatus.FINISHED);
	}

}
