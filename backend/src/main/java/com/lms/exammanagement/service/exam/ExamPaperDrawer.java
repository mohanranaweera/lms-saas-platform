package com.lms.exammanagement.service.exam;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Uniform random selection without replacement (D3 paper draw). Pure and deterministic for
 * a given {@link RandomGenerator} state, so tests can seed it. The picked elements are
 * returned in their original (bank) order, so a paper reads in module bank order.
 */
public final class ExamPaperDrawer {

	private ExamPaperDrawer() {
	}

	/**
	 * @return exactly {@code count} distinct elements of {@code candidates}
	 * @throws IllegalArgumentException if {@code count} is negative or exceeds the candidates
	 */
	public static <T> List<T> pick(List<T> candidates, int count, RandomGenerator random) {
		if (count < 0 || count > candidates.size()) {
			throw new IllegalArgumentException("Cannot draw " + count + " of " + candidates.size());
		}
		int size = candidates.size();
		int[] indexes = new int[size];
		for (int i = 0; i < size; i++) {
			indexes[i] = i;
		}
		// Partial Fisher-Yates: the first `count` slots become a uniform random sample.
		for (int i = 0; i < count; i++) {
			int j = i + random.nextInt(size - i);
			int tmp = indexes[i];
			indexes[i] = indexes[j];
			indexes[j] = tmp;
		}
		int[] chosen = Arrays.copyOf(indexes, count);
		Arrays.sort(chosen);
		List<T> picked = new ArrayList<>(count);
		for (int index : chosen) {
			picked.add(candidates.get(index));
		}
		return picked;
	}

}
