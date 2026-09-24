package com.lms.usermanagement.student.service;

import com.lms.common.error.PayloadTooLargeException;
import com.lms.common.error.UnsupportedMediaTypeException;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Wave 3 (PAR-03-03) - {@code POST /api/v1/students/bulk-import}. Staff
 * -gated ({@code STUDENTS}/{@code CREATE_EDIT}), server-side MIME/size
 * validation BEFORE any row is attempted (per {@code
 * .claude/rules/security.md}'s upload-validation contract), then every row
 * is attempted independently via {@link StudentService#createStudent} - each
 * call is its own Spring-proxied transaction (a genuine cross-bean call, not
 * a self-invocation), so one row's failure/rollback never affects another
 * row's already-committed success. Per-row result list, never all-or-nothing.
 *
 * <p>Minimal, dependency-free CSV parsing (plain comma-split, one row per
 * line, optional header row auto-detected) - no quoted/embedded-comma field
 * support. Columns: {@code name,email,password} (matching {@link
 * com.lms.usermanagement.student.web.dto.StudentCreateRequest}'s exact
 * shape) - guardian/school/grade/stream/mobile fields are not part of the
 * bulk-import row shape in this pass (an admin-created account, same as the
 * existing single-create endpoint).
 */
@Service
public class StudentBulkImportService {

	private static final long MAX_FILE_SIZE_BYTES = 2L * 1024 * 1024;

	private static final int MAX_ROWS = 1000;

	private final PermissionCheckService permissionCheckService;

	private final StudentService studentService;

	public StudentBulkImportService(PermissionCheckService permissionCheckService, StudentService studentService) {
		this.permissionCheckService = permissionCheckService;
		this.studentService = studentService;
	}

	public List<BulkImportRowResult> importCsv(MultipartFile file) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT);
		validateFile(file);

		List<BulkImportRowResult> results = new ArrayList<>();
		List<String> lines = readLines(file);
		if (lines.size() > MAX_ROWS) {
			throw new PayloadTooLargeException("CSV file exceeds the maximum of " + MAX_ROWS + " rows");
		}

		int rowNumber = 0;
		boolean headerSkipped = false;
		for (String line : lines) {
			rowNumber++;
			if (line.isBlank()) {
				continue;
			}
			if (!headerSkipped && isHeaderRow(line)) {
				headerSkipped = true;
				continue;
			}
			results.add(importRow(rowNumber, line));
		}
		return results;
	}

	private BulkImportRowResult importRow(int rowNumber, String line) {
		try {
			String[] columns = line.split(",", -1);
			if (columns.length < 3) {
				return BulkImportRowResult.failed(rowNumber, "Expected columns: name,email,password");
			}
			String name = columns[0].trim();
			String email = columns[1].trim();
			String password = columns[2].trim();
			if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
				return BulkImportRowResult.failed(rowNumber, "name, email, and password must not be blank");
			}
			StudentAccount account = studentService.createStudent(name, email, password);
			return BulkImportRowResult.created(rowNumber, account.id());
		}
		catch (RuntimeException ex) {
			// Continue-on-error, per this class's own javadoc - never lets
			// one bad row abort the whole batch. RuntimeException (not a
			// blanket Exception/Throwable) so a genuinely fatal error still
			// propagates rather than being silently recorded as a row
			// failure.
			return BulkImportRowResult.failed(rowNumber, ex.getMessage());
		}
	}

	private static boolean isHeaderRow(String line) {
		String normalized = line.toLowerCase(Locale.ROOT);
		return normalized.contains("email") && normalized.contains("name");
	}

	private void validateFile(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new UnsupportedMediaTypeException("A non-empty CSV file is required");
		}
		if (file.getSize() > MAX_FILE_SIZE_BYTES) {
			throw new PayloadTooLargeException("CSV file exceeds the maximum allowed size");
		}
		String contentType = file.getContentType();
		String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
		boolean looksLikeCsv = filename.endsWith(".csv")
				|| (contentType != null && (contentType.equalsIgnoreCase("text/csv")
						|| contentType.equalsIgnoreCase("application/vnd.ms-excel")
						|| contentType.equalsIgnoreCase("text/plain")
						|| contentType.equalsIgnoreCase("application/csv")
						|| contentType.equalsIgnoreCase("application/octet-stream")));
		if (!looksLikeCsv) {
			throw new UnsupportedMediaTypeException("Only CSV files are accepted");
		}
	}

	private List<String> readLines(MultipartFile file) {
		List<String> lines = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				lines.add(line);
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException("Failed to read CSV file", e);
		}
		return lines;
	}

}
