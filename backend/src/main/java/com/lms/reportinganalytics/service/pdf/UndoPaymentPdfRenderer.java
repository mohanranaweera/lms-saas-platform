package com.lms.reportinganalytics.service.pdf;

import com.lms.reportinganalytics.service.UndoPaymentRun;
import com.lms.reportinganalytics.service.UndoPaymentRow;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperPrint;
import org.springframework.stereotype.Component;

@Component
public class UndoPaymentPdfRenderer {
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);
	private final ReportTemplates templates;
	public UndoPaymentPdfRenderer(ReportTemplates templates) { this.templates = templates; }
	public byte[] render(UndoPaymentRun run) { return JasperPdfSupport.export(fill(run), "Undo Payments Report"); }
	public JasperPrint fill(UndoPaymentRun run) {
		var report = run.report(); ZoneId zone = ZoneId.of(report.timezone());
		List<UndoPaymentPdfRow> rows = java.util.stream.IntStream.range(0, run.rows().size()).mapToObj(i -> {
			UndoPaymentRow r=run.rows().get(i); return new UndoPaymentPdfRow(Integer.toString(i+1), r.studentName(), r.indexNumber(), r.courseName(), r.reason(), DATE.format(r.undoDate().atZone(zone)), r.amount().setScale(2).toPlainString());
		}).toList();
		String generated = JasperPdfSupport.TIMESTAMP.format(report.generatedAt().atZone(zone)); Map<String,Object> p=new HashMap<>();
		p.put("REPORT_TITLE", "Undo Payments Report"); p.put("INSTITUTE", report.institute().name()); p.put("DATE_RANGE", report.filters().startDate()+" to "+report.filters().endDate()); p.put("GENERATED", "Generated: "+generated+" ("+report.timezone()+")"); p.put("TOTAL", Integer.toString(report.distinctUndoPayments())); p.put("TOTAL_AMOUNT", report.totalAmount().setScale(2).toPlainString()); p.put("FOOTER", "Undo Payments Report generated on "+generated+" | "+report.institute().name()); p.put("EMPTY", rows.isEmpty()); p.put(JRParameter.REPORT_LOCALE, Locale.US);
		return JasperPdfSupport.fill(templates.undoPayments(), p, rows);
	}
}
