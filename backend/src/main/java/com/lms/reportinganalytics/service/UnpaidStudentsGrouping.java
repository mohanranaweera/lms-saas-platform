package com.lms.reportinganalytics.service;

import java.util.List;

/** Stable paging over immutable run rows. */
public final class UnpaidStudentsGrouping {

	private UnpaidStudentsGrouping() {
	}

	public record RowsPage(int page, int size, int totalRows, int totalPages, List<UnpaidStudentRow> rows) {
	}

	public static RowsPage page(List<UnpaidStudentRow> rows, int page, int size) {
		int total = rows.size();
		int pages = total == 0 ? 0 : (int) ((total + (long) size - 1) / size);
		int from = Math.min(total, page * size);
		int to = Math.min(total, from + size);
		return new RowsPage(page, size, total, pages, rows.subList(from, to));
	}

}
