package com.lms.reportinganalytics.service.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.api.CalendarOccurrence;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** CAL-01 timetable PDF: selectable text, all occurrences, grid + agenda, empty state, page X of Y. */
class TimetablePdfRendererTest {

	private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

	private final TimetablePdfRenderer renderer = new TimetablePdfRenderer(new ReportTemplates());

	private static CalendarOccurrence occurrence(String date, String start, String end, String course,
			boolean cancelled) {
		LocalDate day = LocalDate.parse(date);
		LocalTime from = LocalTime.parse(start);
		LocalTime to = LocalTime.parse(end);
		return new CalendarOccurrence("r_" + UUID.randomUUID() + "_" + date.replace("-", ""), "RECURRING",
				UUID.randomUUID(), course, "slug", day.atTime(from).atZone(COLOMBO).toInstant(),
				day.atTime(to).atZone(COLOMBO).toInstant(), day, from, to, List.of("Nimal Perera"),
				cancelled ? "CANCELLED" : "SCHEDULED", cancelled, null, null, null);
	}

	private static String text(byte[] pdf) throws Exception {
		PdfReader reader = new PdfReader(pdf);
		try {
			StringBuilder text = new StringBuilder();
			PdfTextExtractor extractor = new PdfTextExtractor(reader);
			for (int page = 1; page <= reader.getNumberOfPages(); page++) {
				text.append(extractor.getTextFromPage(page)).append('\n');
			}
			return text.toString();
		}
		finally {
			reader.close();
		}
	}

	private static int count(String haystack, String needle) {
		int count = 0;
		for (int index = haystack.indexOf(needle); index >= 0; index = haystack.indexOf(needle, index + 1)) {
			count++;
		}
		return count;
	}

	@Test
	void monthViewListsEveryOccurrenceInTheGridAndTheAgenda() throws Exception {
		List<CalendarOccurrence> occurrences = new ArrayList<>();
		// A crowded day: every class must be listed, none dropped behind a "+N more".
		for (int i = 0; i < 8; i++) {
			occurrences.add(occurrence("2026-11-06", String.format("%02d:00", 8 + i), String.format("%02d:45", 8 + i),
					"Course" + i + " Biology", false));
		}
		occurrences.add(occurrence("2026-11-20", "07:30", "09:30", "Chemistry & <Physics>", true));
		TimetablePdfRenderer.Input input = new TimetablePdfRenderer.Input("Advance Online Class",
				TimetablePdfRenderer.View.MONTH, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-12-06"), null,
				COLOMBO, Instant.parse("2026-11-06T04:30:00Z"), occurrences);

		List<TimetablePdfRow> rows = TimetablePdfRenderer.rows(input);
		assertThat(rows.stream().filter(r -> TimetablePdfRenderer.ITEM.equals(r.kind())).count()).isEqualTo(9);
		assertThat(rows.stream().filter(r -> TimetablePdfRenderer.WEEK.equals(r.kind())).count()).isEqualTo(5);

		byte[] pdf = renderer.render(input);
		assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
		String text = text(pdf);
		assertThat(text).contains("Class Timetable", "Advance Online Class", "November 2026", "All Courses",
				"Asia/Colombo", "Generated: 06-11-2026 10:00:00", "Agenda", "Friday, 06 November 2026",
				"Nimal Perera", "Cancelled", "Chemistry & <Physics>", "Page 1 of");
		for (int i = 0; i < 8; i++) {
			// once in the grid cell, once in the agenda
			assertThat(count(text, "Course" + i + " Biology")).as("course %s", i).isEqualTo(2);
		}
		assertThat(text).doesNotContain("No classes scheduled in this period.");
	}

	@Test
	void weekViewIsAnAgendaOnly() throws Exception {
		TimetablePdfRenderer.Input input = new TimetablePdfRenderer.Input("Inst", TimetablePdfRenderer.View.WEEK,
				LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-08"), "Biology",
				COLOMBO, Instant.parse("2026-11-06T04:30:00Z"),
				List.of(occurrence("2026-11-02", "08:00", "10:00", "Biology", false),
						occurrence("2026-11-04", "08:00", "10:00", "Biology", false)));

		assertThat(TimetablePdfRenderer.rows(input)).extracting(TimetablePdfRow::kind)
			.containsExactly("DATE", "ITEM", "DATE", "ITEM");
		String text = text(renderer.render(input));
		assertThat(text).contains("Course: Biology", "8:00 AM – 10:00 AM", "Monday, 02 November 2026");
		assertThat(text).doesNotContain("Sunday\n").doesNotContain("Agenda");
	}

	@Test
	void emptyPeriodSaysSo() throws Exception {
		TimetablePdfRenderer.Input input = new TimetablePdfRenderer.Input("Inst", TimetablePdfRenderer.View.DAY,
				LocalDate.parse("2026-11-06"), LocalDate.parse("2026-11-07"), null, COLOMBO,
				Instant.parse("2026-11-06T04:30:00Z"), List.of());

		String text = text(renderer.render(input));
		assertThat(text).contains("No classes scheduled in this period.", "Friday, 06 November 2026", "Page 1 of");
	}

}
