package com.lms.exammanagement.service.exam;

import com.lms.exammanagement.domain.ExamResultLabel;
import com.lms.exammanagement.domain.ExamType;
import com.lms.exammanagement.service.exam.ExamManagementViews.ManagedStudentRowView;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The management CSV export (contract "Slice D"): UTF-8 with BOM, CRLF rows, every cell
 * double-quoted (so index numbers such as {@code 0006} stay text and commas / quotes / line
 * breaks in names are safe), and formula-injection hardening - a cell starting with {@code = + -
 * @ TAB CR} is prefixed with {@code '}.
 *
 * <p>Columns: MCQ {@code Index Number,First Name,Last Name,Result,Marks}; WRITTEN adds {@code
 * Answer Document,Marked Paper Document} (Yes/No); PHYSICAL adds {@code Attendance}. The Result
 * cell is the label with spaces ({@code NOT PARTICIPATED}).
 */
final class ExamResultsCsv {

	private static final byte[] BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

	private ExamResultsCsv() {
	}

	static byte[] write(ExamType type, List<ManagedStudentRowView> rows) {
		StringBuilder csv = new StringBuilder();
		List<String> header = new ArrayList<>(List.of("Index Number", "First Name", "Last Name", "Result", "Marks"));
		if (type == ExamType.WRITTEN) {
			header.add("Answer Document");
			header.add("Marked Paper Document");
		}
		else if (type == ExamType.PHYSICAL) {
			header.add("Attendance");
		}
		line(csv, header);
		for (ManagedStudentRowView row : rows) {
			List<String> cells = new ArrayList<>();
			cells.add(row.indexNumber());
			cells.add(row.firstName());
			cells.add(row.lastName());
			cells.add(labelText(row.result()));
			cells.add(marks(row.marks()));
			if (type == ExamType.WRITTEN) {
				cells.add(row.answerDocuments().isEmpty() ? "No" : "Yes");
				cells.add(row.markedPaper() == null ? "No" : "Yes");
			}
			else if (type == ExamType.PHYSICAL) {
				cells.add(row.attendance() == null ? "" : capitalized(row.attendance().name()));
			}
			line(csv, cells);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(BOM);
		out.writeBytes(csv.toString().getBytes(StandardCharsets.UTF_8));
		return out.toByteArray();
	}

	static String fileName(String examName) {
		String slug = examName == null ? "" : examName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
		slug = slug.replaceAll("^-+|-+$", "");
		if (slug.isEmpty()) {
			slug = "exam";
		}
		if (slug.length() > 60) {
			slug = slug.substring(0, 60);
		}
		return slug + "-results.csv";
	}

	/** Formula-injection hardening + RFC 4180 quoting. */
	static String cell(String value) {
		String text = value == null ? "" : value;
		if (!text.isEmpty()) {
			char first = text.charAt(0);
			if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
				text = "'" + text;
			}
		}
		return '"' + text.replace("\"", "\"\"") + '"';
	}

	private static void line(StringBuilder csv, List<String> cells) {
		for (int i = 0; i < cells.size(); i++) {
			if (i > 0) {
				csv.append(',');
			}
			csv.append(cell(cells.get(i)));
		}
		csv.append("\r\n");
	}

	private static String labelText(ExamResultLabel label) {
		return label == null ? "" : label.name().replace('_', ' ');
	}

	private static String marks(BigDecimal marks) {
		return marks == null ? "" : marks.stripTrailingZeros().toPlainString();
	}

	private static String capitalized(String value) {
		return value.charAt(0) + value.substring(1).toLowerCase(Locale.ROOT);
	}

}
