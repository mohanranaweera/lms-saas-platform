package com.lms.reportinganalytics.service.pdf;

import java.io.IOException;
import java.io.InputStream;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.util.JRLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * RPT-01: the application-owned Jasper templates, loaded ONCE at startup into
 * immutable {@link JasperReport} objects shared by every request (each fill
 * gets its own fresh parameter map and data source).
 *
 * <p>The build precompiles {@code reports/*.jrxml} to {@code *.jasper} (see
 * {@link ReportTemplatePrecompiler}, bound to {@code process-classes} in the
 * pom): a Spring Boot executable jar cannot feed its nested jars to the JDK
 * compiler, so compiling at runtime is only the fallback for an exploded
 * classpath (IDE runs). Templates contain no scriptlets, no SQL and no
 * arithmetic - only field/parameter display and formatting.
 */
@Component
public class ReportTemplates {

	public static final String SUMMARY = "reports/teacher-income-summary";

	public static final String PAYSLIP = "reports/teacher-payslip";

	public static final String STUDENT_PAYMENTS = "reports/student-payment-report";

	public static final String ATTENDANCE = "reports/attendance-report";

	public static final String COURSE_SUMMARY = "reports/course-summary-report";

	public static final String UNPAID_STUDENTS = "reports/unpaid-students-report";

	public static final String REGISTRATIONS = "reports/registration-report";

	public static final String STUDENT_BEHAVIOR = "reports/student-behavior-report";
	public static final String UNDO_PAYMENTS = "reports/undo-payments-report";

	private static final Logger log = LoggerFactory.getLogger(ReportTemplates.class);

	private final JasperReport summary;

	private final JasperReport payslip;

	private final JasperReport studentPayments;

	private final JasperReport attendance;

	private final JasperReport courseSummary;

	private final JasperReport unpaidStudents;

	private final JasperReport registrations;

	private final JasperReport studentBehavior;
	private final JasperReport undoPayments;

	public ReportTemplates() {
		this.summary = load(SUMMARY);
		this.payslip = load(PAYSLIP);
		this.studentPayments = load(STUDENT_PAYMENTS);
		this.attendance = load(ATTENDANCE);
		this.courseSummary = load(COURSE_SUMMARY);
		this.unpaidStudents = load(UNPAID_STUDENTS);
		this.registrations = load(REGISTRATIONS);
		this.studentBehavior = load(STUDENT_BEHAVIOR);
		this.undoPayments = load(UNDO_PAYMENTS);
	}

	public JasperReport summary() {
		return summary;
	}

	public JasperReport payslip() {
		return payslip;
	}

	/** RPT-02 Student Payment Report (landscape A4). */
	public JasperReport studentPayments() {
		return studentPayments;
	}

	/** RPT-03 Attendance Report (landscape A4). */
	public JasperReport attendance() {
		return attendance;
	}

	/** RPT-05 Course Summary Report (portrait A4). */
	public JasperReport courseSummary() {
		return courseSummary;
	}

	/** Unpaid Students Report (portrait A4). */
	public JasperReport unpaidStudents() {
		return unpaidStudents;
	}

	public JasperReport registrations() {
		return registrations;
	}

	public JasperReport studentBehavior() {
		return studentBehavior;
	}

	public JasperReport undoPayments() {
		return undoPayments;
	}

	static JasperReport load(String base) {
		ClassPathResource compiled = new ClassPathResource(base + ".jasper");
		try {
			if (compiled.exists()) {
				try (InputStream in = compiled.getInputStream()) {
					return (JasperReport) JRLoader.loadObject(in);
				}
			}
			log.warn("Precompiled report template {}.jasper not found; compiling {}.jrxml at startup", base, base);
			try (InputStream in = new ClassPathResource(base + ".jrxml").getInputStream()) {
				return JasperCompileManager.compileReport(in);
			}
		}
		catch (IOException | JRException ex) {
			throw new IllegalStateException("Cannot load report template " + base, ex);
		}
	}

}
