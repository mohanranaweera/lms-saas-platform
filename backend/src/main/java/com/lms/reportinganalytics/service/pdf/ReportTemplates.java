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

	private static final Logger log = LoggerFactory.getLogger(ReportTemplates.class);

	private final JasperReport summary;

	private final JasperReport payslip;

	public ReportTemplates() {
		this.summary = load(SUMMARY);
		this.payslip = load(PAYSLIP);
	}

	public JasperReport summary() {
		return summary;
	}

	public JasperReport payslip() {
		return payslip;
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
