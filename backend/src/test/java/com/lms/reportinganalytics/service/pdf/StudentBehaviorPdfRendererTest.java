package com.lms.reportinganalytics.service.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.reportinganalytics.service.StudentBehaviorReport;
import com.lms.reportinganalytics.service.StudentBehaviorRow;
import com.lms.reportinganalytics.service.StudentBehaviorRun;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StudentBehaviorPdfRendererTest {
    private final StudentBehaviorPdfRenderer renderer = new StudentBehaviorPdfRenderer(new ReportTemplates());

    @Test
    void rendersThreeSectionsIncludingEmptyCategory() throws Exception {
        byte[] bytes = renderer.render(run());
        PdfReader reader = new PdfReader(bytes);
        try {
            StringBuilder text = new StringBuilder();
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) text.append(extractor.getTextFromPage(page));
            assertThat(text.toString()).contains("Student Behaviour Report", "New Students", "Dropped Students",
                    "Continuing Students", "No students in this category", "0001", "Page 1");
        } finally { reader.close(); }
    }

    private static StudentBehaviorRun run() {
        Instant generated = Instant.parse("2026-10-25T08:29:00Z");
        StudentBehaviorReport report = new StudentBehaviorReport(UUID.randomUUID(), generated.plusSeconds(900), generated,
                "Asia/Colombo", new StudentBehaviorReport.Institute("Advance Online Class"),
                new StudentBehaviorReport.CourseRef(UUID.randomUUID(), "Chemistry"),
                new StudentBehaviorReport.Filters(YearMonth.of(2026, 10), "October 2026"),
                new StudentBehaviorReport.Classification("CONFIRMED_CASH_MONTHLY_COURSE_COVERAGE",
                        YearMonth.of(2026, 9), "AVAILABLE", true, generated),
                new StudentBehaviorReport.Counts(1, 0, 1));
        StudentBehaviorRow added = new StudentBehaviorRow(UUID.randomUUID(), UUID.randomUUID(), "0001",
                "දිගු නම மாணவர்", "new@example.test", "0771234567");
        StudentBehaviorRow continuing = new StudentBehaviorRow(UUID.randomUUID(), UUID.randomUUID(), "0002",
                "Continuing Student", "", "");
        return new StudentBehaviorRun(report, List.of(added), List.of(), List.of(continuing));
    }
}
