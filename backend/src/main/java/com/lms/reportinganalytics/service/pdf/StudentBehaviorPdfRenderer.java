package com.lms.reportinganalytics.service.pdf;

import com.lms.reportinganalytics.service.StudentBehaviorRow;
import com.lms.reportinganalytics.service.StudentBehaviorRun;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperPrint;
import org.springframework.stereotype.Component;

@Component
public class StudentBehaviorPdfRenderer {
    private final ReportTemplates templates;
    public StudentBehaviorPdfRenderer(ReportTemplates templates) { this.templates = templates; }
    public byte[] render(StudentBehaviorRun run) { return JasperPdfSupport.export(fill(run), "Student Behavior Report"); }
    public JasperPrint fill(StudentBehaviorRun run) {
        var report = run.report(); ZoneId zone = ZoneId.of(report.timezone());
        List<StudentBehaviorPdfRow> rows = new ArrayList<>();
        add(rows, "New Students", run.newStudents()); add(rows, "Dropped Students", run.droppedStudents()); add(rows, "Continuing Students", run.continuingStudents());
        String generated = JasperPdfSupport.TIMESTAMP.format(report.generatedAt().atZone(zone));
        Map<String, Object> params = new HashMap<>();
        params.put("INSTITUTE", report.institute().name()); params.put("COURSE", report.course().name());
        params.put("MONTH", report.filters().billingMonthLabel()); params.put("GENERATED", "Generated: " + generated + " (" + report.timezone() + ")");
        params.put("NEW_COUNT", Integer.toString(report.counts().newStudents())); params.put("DROPPED_COUNT", Integer.toString(report.counts().droppedStudents()));
        params.put("CONTINUING_COUNT", Integer.toString(report.counts().continuingStudents()));
        params.put("FOOTER", "Student Behavior Report generated on " + generated + " | " + report.institute().name());
        params.put("EMPTY", rows.isEmpty()); params.put(JRParameter.REPORT_LOCALE, Locale.US);
        return JasperPdfSupport.fill(templates.studentBehavior(), params, rows);
    }
    private static void add(List<StudentBehaviorPdfRow> target, String category, List<StudentBehaviorRow> source) {
        if (source.isEmpty()) {
            target.add(new StudentBehaviorPdfRow(category, "", "", "No students in this category", ""));
            return;
        }
        for (int i = 0; i < source.size(); i++) { StudentBehaviorRow row = source.get(i); target.add(new StudentBehaviorPdfRow(category, Integer.toString(i + 1), dash(row.indexNumber()), row.studentName(), dash(row.mobile()))); }
    }
    private static String dash(String value) { return value == null || value.isBlank() ? "—" : value; }
}
