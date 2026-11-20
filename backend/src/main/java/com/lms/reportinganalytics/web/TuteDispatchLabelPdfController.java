package com.lms.reportinganalytics.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.RequiresCapability;
import com.lms.reportinganalytics.service.pdf.TuteDispatchLabelPdfRenderer;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.tenantmanagement.api.TenantSummary;
import com.lms.tutemanagement.api.TuteDispatchLabelApi;
import com.lms.tutemanagement.api.TuteDispatchLabelApi.DispatchFilter;
import com.lms.tutemanagement.api.TuteDispatchLabelApi.DispatchLabel;
import com.lms.tutemanagement.api.TuteDispatchLabelApi.LabelRequest;
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
 * TUTE-01 {@code POST /api/v1/tute-dispatch/labels}: the A4 label sheet (2 x 3) for exactly the
 * selected recipient groups, prepared and authorized by tute-management's {@link
 * TuteDispatchLabelApi} (which re-checks {@code TUTE_DISPATCH_PRINT_LABELS}, the caller's course
 * scope and the selection, and audits the export). Printing never changes anything. The sender
 * name is the tenant's configured institute name, else its registered name - never the request.
 * Response: {@code application/pdf}, {@code Cache-Control: no-store}, {@code X-Label-Count}.
 */
@RestController
public class TuteDispatchLabelPdfController {

	public static final String LABEL_COUNT_HEADER = "X-Label-Count";

	/** Queue filters for {@code allFiltered} printing. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record LabelFilters(String search, UUID courseId, UUID tuteId, Boolean twoPlus) {
	}

	/** {@code {fulfillmentIds[<=3000]}} or {@code {allFiltered: true, filters, expectedLabelCount}}. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record LabelsRequest(List<UUID> fulfillmentIds, Boolean allFiltered, LabelFilters filters,
			Integer expectedLabelCount) {
	}

	private final TuteDispatchLabelApi labelApi;

	private final TuteDispatchLabelPdfRenderer renderer;

	private final TenantConfigApi tenantConfigApi;

	private final TenantLookupApi tenantLookupApi;

	private final TenantContext tenantContext;

	public TuteDispatchLabelPdfController(TuteDispatchLabelApi labelApi, TuteDispatchLabelPdfRenderer renderer,
			TenantConfigApi tenantConfigApi, TenantLookupApi tenantLookupApi, TenantContext tenantContext) {
		this.labelApi = labelApi;
		this.renderer = renderer;
		this.tenantConfigApi = tenantConfigApi;
		this.tenantLookupApi = tenantLookupApi;
		this.tenantContext = tenantContext;
	}

	@PostMapping("/api/v1/tute-dispatch/labels")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TUTE_DISPATCH_PRINT_LABELS)
	public ResponseEntity<byte[]> labels(@RequestBody LabelsRequest request) {
		LabelFilters filters = request.filters();
		DispatchFilter filter = filters == null ? new DispatchFilter(null, null, null, false)
				: new DispatchFilter(filters.search(), filters.courseId(), filters.tuteId(),
						Boolean.TRUE.equals(filters.twoPlus()));
		List<DispatchLabel> labels = labelApi.prepareLabels(new LabelRequest(request.fulfillmentIds(),
				Boolean.TRUE.equals(request.allFiltered()), filter, request.expectedLabelCount()));
		byte[] pdf = renderer.render(tenantName(), labels);
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.cacheControl(CacheControl.noStore())
			.header(LABEL_COUNT_HEADER, Integer.toString(labels.size()))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename("tute-dispatch-labels.pdf").build().toString())
			.body(pdf);
	}

	private String tenantName() {
		UUID tenantId = tenantContext.getTenantId();
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
