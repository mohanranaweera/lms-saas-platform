package com.lms.localizationmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.localizationmanagement.api.LocaleInputs;
import com.lms.localizationmanagement.service.PublicI18nService;
import com.lms.localizationmanagement.service.PublicI18nService.SettingsView;
import com.lms.localizationmanagement.service.TranslationViews.CatalogView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * I18N-01 public reads (doc section 6), GET only, on {@code SecurityFilterChainConfig}'s
 * {@code permitAll()} list. The tenant is the one {@code TenantResolutionFilter} resolved from
 * the host - no tenant id is accepted. A signed-in caller's saved preference is used when a
 * valid token is sent.
 */
@RestController
@RequestMapping("/api/v1/public/i18n")
public class PublicI18nController {

	static final String VARY = "Accept-Language, Cookie, Authorization, X-LMS-Locale";

	private final PublicI18nService publicI18nService;

	public PublicI18nController(PublicI18nService publicI18nService) {
		this.publicI18nService = publicI18nService;
	}

	@GetMapping("/settings")
	public ResponseEntity<ApiResponse<SettingsView>> settings() {
		return ResponseEntity.ok().cacheControl(CacheControl.noCache())
			.body(ApiResponse.success(publicI18nService.settings()));
	}

	@GetMapping("/catalog")
	public ResponseEntity<ApiResponse<CatalogView>> catalog(HttpServletRequest request,
			@RequestParam(name = "ns", required = false) String ns) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noCache())
			.header(HttpHeaders.VARY, VARY)
			.body(ApiResponse.success(publicI18nService.catalog(LocaleInputs.from(request), namespaces(ns))));
	}

	static List<String> namespaces(String ns) {
		return ns == null || ns.isBlank() ? List.of() : Arrays.stream(ns.split(",")).limit(50).toList();
	}

}
