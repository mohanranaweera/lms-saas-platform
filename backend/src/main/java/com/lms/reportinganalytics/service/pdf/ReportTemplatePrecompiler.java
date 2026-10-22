package com.lms.reportinganalytics.service.pdf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;

/**
 * Build-time step (exec-maven-plugin, {@code process-classes}): compiles every
 * {@code *.jrxml} under {@code args[0]} to {@code *.jasper} under {@code
 * args[1]}, so the packaged application never needs a Java compiler at
 * runtime. Fails the build on any template error. Not used at runtime.
 */
public final class ReportTemplatePrecompiler {

	private ReportTemplatePrecompiler() {
	}

	public static void main(String[] args) throws IOException, JRException {
		if (args.length != 2) {
			throw new IllegalArgumentException("usage: ReportTemplatePrecompiler <jrxmlDir> <outputDir>");
		}
		Path source = Path.of(args[0]);
		Path target = Path.of(args[1]);
		Files.createDirectories(target);
		try (Stream<Path> files = Files.list(source)) {
			for (Path jrxml : files.filter(p -> p.getFileName().toString().endsWith(".jrxml")).sorted().toList()) {
				String name = jrxml.getFileName().toString().replaceFirst("\\.jrxml$", ".jasper");
				JasperCompileManager.compileReportToFile(jrxml.toString(), target.resolve(name).toString());
				System.out.println("Compiled report template " + jrxml.getFileName() + " -> " + name);
			}
		}
	}

}
