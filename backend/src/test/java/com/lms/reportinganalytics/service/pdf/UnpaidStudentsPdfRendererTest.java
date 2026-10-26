package com.lms.reportinganalytics.service.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.reportinganalytics.service.UnpaidStudentRow;
import com.lms.reportinganalytics.service.UnpaidStudentsReport;
import com.lms.reportinganalytics.service.UnpaidStudentsRun;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UnpaidStudentsPdfRendererTest {

	private final UnpaidStudentsPdfRenderer renderer = new UnpaidStudentsPdfRenderer(new ReportTemplates());

	@Test
	void multiPagePdfRepeatsHeadersAndCarriesPageNumbersAndUnicodeRows() throws Exception {
		List<UnpaidStudentRow> rows = new ArrayList<>();
		for (int index = 1; index <= 80; index++) {
			rows.add(new UnpaidStudentRow(UUID.randomUUID(), UUID.randomUUID(), String.format("%04d", index),
					index == 1 ? "දිගු නම மாணவர்" : "Student with a deliberately long printable name " + index,
					null, index % 2 == 0 ? null : "071 234 5678", index % 3));
		}
		UnpaidStudentsReport report = report(rows.size(), false);
		byte[] pdf = renderer.render(new UnpaidStudentsRun(report, rows));
		PdfReader reader = new PdfReader(pdf);
		try {
			assertThat(reader.getNumberOfPages()).isGreaterThan(1);
			PdfTextExtractor extractor = new PdfTextExtractor(reader);
			for (int page = 1; page <= reader.getNumberOfPages(); page++) {
				String text = extractor.getTextFromPage(page);
				assertThat(text).contains("INDEX", "STUDENT NAME", "MOBILE", "ATTENDANCE", "Page " + page);
			}
			assertThat(extractor.getTextFromPage(1)).contains("At Least One Attended: No", "80", "Chemistry");
		}
		finally {
			reader.close();
		}
	}

	@Test
	void emptyPdfStillShowsAppliedMetadata() throws Exception {
		byte[] pdf = renderer.render(new UnpaidStudentsRun(report(0, true), List.of()));
		PdfReader reader = new PdfReader(pdf);
		try {
			String text = new PdfTextExtractor(reader).getTextFromPage(1);
			assertThat(text).contains("Unpaid Students Report", "At Least One Attended: Yes",
					"No unpaid students match these filters");
		}
		finally {
			reader.close();
		}
	}

	private static UnpaidStudentsReport report(int count, boolean attended) {
		Instant generated = Instant.parse("2026-10-23T08:29:00Z");
		return new UnpaidStudentsReport(UUID.randomUUID(), generated.plusSeconds(900), generated, "Asia/Colombo",
				new UnpaidStudentsReport.Institute("Advance Online Class"),
				new UnpaidStudentsReport.CourseRef(UUID.randomUUID(), "2027 A/L Chemistry Theory - Live Online Class"),
				new UnpaidStudentsReport.Filters(YearMonth.of(2026, 10), "October 2026", attended),
				new UnpaidStudentsReport.EnrollmentBasis("CURRENT_COURSE_PARTICIPATION", generated), count);
	}

}
