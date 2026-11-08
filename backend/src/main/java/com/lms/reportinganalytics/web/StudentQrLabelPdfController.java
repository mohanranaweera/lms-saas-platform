package com.lms.reportinganalytics.web;

import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.RequiresCapability;
import com.lms.common.tenant.TenantContext;
import com.lms.reportinganalytics.service.pdf.StudentQrLabelPdfRenderer;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.tenantmanagement.api.TenantSummary;
import com.lms.usermanagement.api.StudentQrLabelApi;
import com.lms.usermanagement.api.StudentQrLabelApi.StudentQrLabel;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * STU-MGMT-02 {@code POST /api/v1/students/qr-export}: the A4 QR label sheet for exactly
 * the selected students (at most 500), re-filtered to the caller's tenant by
 * user-management's {@link StudentQrLabelApi} (which re-checks {@code STUDENTS} VIEW).
 * The tenant name comes from the tenant's configured institute name, else its
 * registered name - never from the request.
 */
@RestController
public class StudentQrLabelPdfController {

	/** {@code {studentIds[] <= 500}}. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record QrExportRequest(@NotNull @Size(min = 1, max = StudentQrLabelApi.MAX_LABELS) List<UUID> studentIds) {
	}

	private final StudentQrLabelApi studentQrLabelApi;

	private final StudentQrLabelPdfRenderer renderer;

	private final TenantConfigApi tenantConfigApi;

	private final TenantLookupApi tenantLookupApi;

	private final TenantContext tenantContext;

	public StudentQrLabelPdfController(StudentQrLabelApi studentQrLabelApi, StudentQrLabelPdfRenderer renderer,
			TenantConfigApi tenantConfigApi, TenantLookupApi tenantLookupApi, TenantContext tenantContext) {
		this.studentQrLabelApi = studentQrLabelApi;
		this.renderer = renderer;
		this.tenantConfigApi = tenantConfigApi;
		this.tenantLookupApi = tenantLookupApi;
		this.tenantContext = tenantContext;
	}

	@PostMapping("/api/v1/students/qr-export")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'VIEW')")
	@RequiresCapability(Capability.STUDENTS_EXPORT_QR)
	public ResponseEntity<byte[]> export(@Valid @RequestBody QrExportRequest request) {
		List<StudentQrLabel> labels = studentQrLabelApi.findQrLabels(request.studentIds());
		byte[] pdf = renderer.render(tenantName(), labels);
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.cacheControl(CacheControl.noStore())
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename("student-qr-labels.pdf").build().toString())
			.body(pdf);
	}

	private String tenantName() {
		var tenantId = tenantContext.getTenantId();
		return tenantConfigApi.resolveValue(tenantId, ConfigDomain.GENERAL, "institute_name")
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.filter(name -> !name.isBlank())
			.orElseGet(() -> tenantLookupApi.resolveTenantSummaries(Set.of(tenantId))
				.stream()
				.findFirst()
				.map(TenantSummary::name)
				.orElse(""));
	}

}
