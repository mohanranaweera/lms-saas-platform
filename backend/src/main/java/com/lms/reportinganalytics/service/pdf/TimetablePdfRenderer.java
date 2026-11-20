package com.lms.reportinganalytics.service.pdf;

import com.lms.coursemanagement.api.CalendarOccurrence;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperPrint;
import org.springframework.stereotype.Component;

/**
 * CAL-01 timetable PDF (docs/specs/class-calendar.md §6.5), rendered with the
 * shared JasperReports stack - selectable, Unicode text (the "LMS Sans" Noto
 * font set), landscape A4, "Page X of Y" footer. MONTH = Sunday-first 7-column
 * grid whose cells list every occurrence (cells stretch, never truncate) plus
 * a complete agenda appendix grouped by date; WEEK/DAY = the agenda only.
 * Occurrences are rendered exactly as given (already scoped, overridden and
 * sorted by the calendar service).
 */
@Component
public class TimetablePdfRenderer {

	public enum View {
		MONTH, WEEK, DAY
	}

	/**
	 * @param from first date (inclusive) and {@code to} end date (exclusive), tenant-local
	 * @param courseLabel the course filter's name, or {@code null} for all courses
	 */
	public record Input(String instituteName, View view, LocalDate from, LocalDate to, String courseLabel,
			ZoneId zone, Instant generatedAt, List<CalendarOccurrence> occurrences) {
	}

	static final String GRID_HEAD = "GRID_HEAD";

	static final String WEEK = "WEEK";

	static final String AGENDA_TITLE = "AGENDA_TITLE";

	static final String DATE = "DATE";

	static final String ITEM = "ITEM";

	static final String EMPTY = "EMPTY";

	static final String EMPTY_MESSAGE = "No classes scheduled in this period.";

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

	private static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy",
			Locale.ENGLISH);

	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

	private final ReportTemplates templates;

	public TimetablePdfRenderer(ReportTemplates templates) {
		this.templates = templates;
	}

	public byte[] render(Input input) {
		return JasperPdfSupport.export(fill(input), "Class Timetable");
	}

	public JasperPrint fill(Input input) {
		String generated = JasperPdfSupport.TIMESTAMP.format(input.generatedAt().atZone(input.zone()));
		String institute = input.instituteName() == null || input.instituteName().isBlank() ? "Class Timetable"
				: input.instituteName();
		Map<String, Object> params = new HashMap<>();
		params.put("INSTITUTE", institute);
		params.put("PERIOD", "Period: " + periodLabel(input));
		params.put("COURSE", "Course: " + (input.courseLabel() == null ? "All Courses" : input.courseLabel()));
		params.put("TIMEZONE", "Timezone: " + input.zone().getId());
		params.put("GENERATED", "Generated: " + generated + " (" + input.zone().getId() + ")");
		params.put("FOOTER", "Class Timetable | " + institute + " | Generated " + generated);
		params.put(JRParameter.REPORT_LOCALE, Locale.US);
		return JasperPdfSupport.fill(templates.timetable(), params, rows(input));
	}

	/** The template's data rows, in print order (package-visible for tests). */
	static List<TimetablePdfRow> rows(Input input) {
		List<TimetablePdfRow> rows = new ArrayList<>();
		Map<LocalDate, List<CalendarOccurrence>> byDate = new LinkedHashMap<>();
		for (CalendarOccurrence occurrence : input.occurrences()) {
			byDate.computeIfAbsent(occurrence.localDate(), date -> new ArrayList<>()).add(occurrence);
		}
		if (input.view() == View.MONTH) {
			addGrid(rows, input, byDate);
			if (!input.occurrences().isEmpty()) {
				rows.add(TimetablePdfRow.of(AGENDA_TITLE, "Agenda"));
			}
		}
		if (input.occurrences().isEmpty()) {
			rows.add(TimetablePdfRow.of(EMPTY, EMPTY_MESSAGE));
			return rows;
		}
		byDate.forEach((date, occurrences) -> {
			rows.add(TimetablePdfRow.of(DATE, LONG_DAY.format(date)));
			for (CalendarOccurrence occurrence : occurrences) {
				rows.add(TimetablePdfRow.item(timeRange(occurrence), courseLine(occurrence),
						String.join(", ", occurrence.teacherNames()), statusLabel(occurrence)));
			}
		});
		return rows;
	}

	private static void addGrid(List<TimetablePdfRow> rows, Input input,
			Map<LocalDate, List<CalendarOccurrence>> byDate) {
		String[] head = new String[7];
		DayOfWeek day = DayOfWeek.SUNDAY;
		for (int index = 0; index < 7; index++) {
			head[index] = day.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
			day = day.plus(1);
		}
		rows.add(new TimetablePdfRow(GRID_HEAD, head[0], head[1], head[2], head[3], head[4], head[5], head[6], "", "",
				"", "", ""));
		YearMonth focus = focusMonth(input.from(), input.to());
		LocalDate last = input.to().minusDays(1);
		LocalDate cursor = input.from().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
		LocalDate gridEnd = last.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
		while (!cursor.isAfter(gridEnd)) {
			String[] cells = new String[7];
			for (int index = 0; index < 7; index++) {
				LocalDate date = cursor.plusDays(index);
				boolean inRange = !date.isBefore(input.from()) && date.isBefore(input.to());
				cells[index] = cell(date, YearMonth.from(date).equals(focus) && inRange,
						inRange ? byDate.getOrDefault(date, List.of()) : List.of());
			}
			rows.add(TimetablePdfRow.week(cells));
			cursor = cursor.plusWeeks(1);
		}
	}

	/** Styled-markup cell text: the day number (muted outside the focus month), then every occurrence. */
	private static String cell(LocalDate date, boolean focusDay, List<CalendarOccurrence> occurrences) {
		StringBuilder text = new StringBuilder();
		String color = focusDay ? "#111827" : "#9CA3AF";
		text.append("<style isBold=\"true\" forecolor=\"").append(color).append("\">")
			.append(date.getDayOfMonth())
			.append("</style>");
		for (CalendarOccurrence occurrence : occurrences) {
			String line = escape(clock(occurrence.startLocalTime()) + " " + occurrence.courseName());
			text.append('\n');
			if (occurrence.cancelled()) {
				text.append("<style isStrikeThrough=\"true\" forecolor=\"#6B7280\">")
					.append(line)
					.append("</style> (Cancelled)");
			}
			else {
				text.append(line);
			}
		}
		return text.toString();
	}

	static YearMonth focusMonth(LocalDate from, LocalDate to) {
		long span = ChronoUnit.DAYS.between(from, to);
		return YearMonth.from(from.plusDays(span / 2));
	}

	private static String periodLabel(Input input) {
		LocalDate last = input.to().minusDays(1);
		String range = last.equals(input.from()) ? DAY.format(input.from())
				: DAY.format(input.from()) + " – " + DAY.format(last);
		return switch (input.view()) {
			case MONTH -> MONTH.format(focusMonth(input.from(), input.to())) + " (" + range + ")";
			case DAY -> last.equals(input.from()) ? LONG_DAY.format(input.from()) : range;
			case WEEK -> range;
		};
	}

	private static String timeRange(CalendarOccurrence occurrence) {
		return clock(occurrence.startLocalTime()) + " – " + clock(occurrence.endLocalTime());
	}

	private static String courseLine(CalendarOccurrence occurrence) {
		if (occurrence.sessionTitle() == null || occurrence.sessionTitle().isBlank()) {
			return occurrence.courseName();
		}
		return occurrence.courseName() + " — " + occurrence.sessionTitle();
	}

	private static String statusLabel(CalendarOccurrence occurrence) {
		if (occurrence.cancelled()) {
			return "Cancelled";
		}
		return switch (occurrence.status() == null ? "" : occurrence.status()) {
			case "LIVE" -> "Live";
			case "OVER" -> "Over";
			case "CANCELLED" -> "Cancelled";
			default -> "Scheduled";
		};
	}

	private static String clock(LocalTime time) {
		return time == null ? "" : CLOCK.format(time);
	}

	/** Escapes text for Jasper's styled markup. */
	static String escape(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

}
