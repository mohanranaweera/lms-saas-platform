package com.lms.localizationmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.RequiresCapability;
import com.lms.localizationmanagement.service.TranslationAdminService;
import com.lms.localizationmanagement.service.TranslationTransferService;
import com.lms.localizationmanagement.service.TranslationViews.CatalogView;
import com.lms.localizationmanagement.service.TranslationViews.DraftView;
import com.lms.localizationmanagement.service.TranslationViews.EntriesPage;
import com.lms.localizationmanagement.service.TranslationViews.ExportFile;
import com.lms.localizationmanagement.service.TranslationViews.ImportReport;
import com.lms.localizationmanagement.service.TranslationViews.ReleaseView;
import com.lms.localizationmanagement.web.dto.DraftRequest;
import com.lms.localizationmanagement.web.dto.PublishRequest;
import com.lms.localizationmanagement.web.dto.ResetDraftRequest;
import com.lms.localizationmanagement.web.dto.RollbackRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Settings -> Translations (doc section 6). Thin: {@code @RequiresCapability} is the first gate
 * only; the services re-check the capability and resolve the tenant from the authenticated
 * context. No tenant id is accepted anywhere. Responses are {@code no-store}.
 */
@RestController
@RequestMapping("/api/v1/i18n/admin")
public class TranslationAdminController {

	private final TranslationAdminService adminService;

	private final TranslationTransferService transferService;

	public TranslationAdminController(TranslationAdminService adminService,
			TranslationTransferService transferService) {
		this.adminService = adminService;
		this.transferService = transferService;
	}

	private static <T> ResponseEntity<ApiResponse<T>> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(body));
	}

	@GetMapping("/entries")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TRANSLATIONS_VIEW)
	public ResponseEntity<ApiResponse<EntriesPage>> entries(@RequestParam String locale,
			@RequestParam(required = false) String namespace, @RequestParam(required = false) String q,
			@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		return ok(adminService.entries(locale, namespace, q, status, page, size));
	}

	@PutMapping("/drafts/{locale}/{key}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TRANSLATIONS_EDIT)
	public ResponseEntity<ApiResponse<DraftView>> saveDraft(@PathVariable String locale, @PathVariable String key,
			@RequestBody DraftRequest request) {
		return ok(adminService.saveDraft(locale, key, request.value(), request.expectedVersion()));
	}

	@PostMapping("/drafts/{locale}/{key}/reset")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TRANSLATIONS_EDIT)
	public ResponseEntity<ApiResponse<DraftView>> resetDraft(@PathVariable String locale, @PathVariable String key,
			@RequestBody(required = false) ResetDraftRequest request) {
		return ok(adminService.resetDraft(locale, key, request == null ? null : request.expectedVersion()));
	}

	@DeleteMapping("/drafts/{locale}/{key}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TRANSLATIONS_EDIT)
	public ResponseEntity<Void> discardDraft(@PathVariable String locale, @PathVariable String key,
			@RequestParam(required = false) Long expectedVersion) {
		adminService.discardDraft(locale, key, expectedVersion);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/preview-catalog")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TRANSLATIONS_VIEW)
	public ResponseEntity<ApiResponse<CatalogView>> previewCatalog(@RequestParam String locale,
			@RequestParam(name = "ns", required = false) String ns) {
		return ok(adminService.preview(locale, PublicI18nController.namespaces(ns)));
	}

	@PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TRANSLATIONS_EDIT)
	public ResponseEntity<ApiResponse<ImportReport>> importPo(@RequestParam String locale,
			@RequestParam(defaultValue = "true") boolean dryRun,
			@RequestPart(name = "file", required = false) MultipartFile file) {
		return ok(transferService.importPo(locale, dryRun, file));
	}

	@GetMapping("/export")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TRANSLATIONS_VIEW)
	public ResponseEntity<byte[]> export(@RequestParam String locale, @RequestParam String scope,
			@RequestParam(defaultValue = "po") String format) {
		ExportFile file = transferService.export(locale, scope, format);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(file.filename()).build().toString())
			.contentType(MediaType.parseMediaType(file.contentType()))
			.body(file.content());
	}

	@GetMapping("/releases")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TRANSLATIONS_VIEW)
	public ResponseEntity<ApiResponse<List<ReleaseView>>> releases(@RequestParam String locale) {
		return ok(adminService.releases(locale));
	}

	@PostMapping("/publish")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TRANSLATIONS_PUBLISH)
	public ResponseEntity<ApiResponse<ReleaseView>> publish(@RequestBody PublishRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(
					adminService.publish(request.locale(), request.expectedVersion(), request.summary())));
	}

	@PostMapping("/releases/{id}/rollback")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TRANSLATIONS_PUBLISH)
	public ResponseEntity<ApiResponse<ReleaseView>> rollback(@PathVariable UUID id,
			@RequestBody RollbackRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(adminService.rollback(id, request.expectedVersion(), request.summary())));
	}

}
