package com.lms.tenantmanagement.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ApplicationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.qr.QrCodes;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantAssetType;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.tenantmanagement.api.TenantStatus;
import com.lms.tenantmanagement.config.InstituteConnectionProperties;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.tenantmanagement.repository.TenantRepository;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * INST-QR-01: the Institute QR / connection link (contract:
 * {@code docs/api/institute-connection.md}).
 *
 * <p>The connection URL is {@code {base-url}/connect/v1/{connection_code}}: a
 * platform-owned origin from configuration plus the tenant's immutable public code
 * (V127). It never contains a tenant subdomain, custom domain, name, credential or
 * token, so it survives renames and address changes and grants nothing - scanning it
 * only selects an institute; normal sign-in is still required.
 *
 * <p>Admin views take the tenant id from trusted context (the Tenant Admin's resolved
 * tenant, or the Platform Admin's path id behind the PLATFORM_ADMIN role). The public
 * resolver takes only the code; it ignores the request host and never accepts a
 * redirect target. The sign-in destination comes from {@link
 * TenantLookupApi#tenantOrigin} - the configured URL template, or the tenant's
 * verified ACTIVE primary custom domain.
 */
@Service
public class InstituteConnectionService {

	public static final String PATH_PREFIX = "/connect/v1/";

	/** Contract version encoded in the URL path. */
	public static final int VERSION = 1;

	private static final Pattern CODE = Pattern.compile("^[a-z0-9]{12}$");

	/** 12 px per module + the 4-module quiet zone: ~440 px for a typical URL, crisp when printed. */
	static final int QR_PIXELS_PER_MODULE = 12;

	private static final Set<TenantStatus> CONNECTABLE = EnumSet.of(TenantStatus.ACTIVE, TenantStatus.TRIAL);

	private final TenantRepository tenantRepository;

	private final TenantConfigApi tenantConfigApi;

	private final TenantAssetService tenantAssetService;

	private final TenantLookupApi tenantLookupApi;

	private final InstituteConnectionProperties properties;

	private final String tenantUrlTemplate;

	public InstituteConnectionService(TenantRepository tenantRepository, TenantConfigApi tenantConfigApi,
			TenantAssetService tenantAssetService, TenantLookupApi tenantLookupApi,
			InstituteConnectionProperties properties,
			@Value("${app.public.tenant-url-template:}") String tenantUrlTemplate) {
		this.tenantRepository = tenantRepository;
		this.tenantConfigApi = tenantConfigApi;
		this.tenantAssetService = tenantAssetService;
		this.tenantLookupApi = tenantLookupApi;
		this.properties = properties;
		this.tenantUrlTemplate = tenantUrlTemplate;
	}

	/** What the admin card shows for {@code tenantId} (a trusted id, never request input). */
	@Transactional(readOnly = true)
	public InstituteConnection forTenant(UUID tenantId) {
		if (!properties.isConfigured()) {
			throw new NotConfiguredException();
		}
		Tenant tenant = tenantRepository.findById(tenantId)
			.orElseThrow(() -> new NotFoundException("Institute not found"));
		String url = connectionUrl(tenant.getConnectionCode());
		Identity identity = identity(tenant);
		return new InstituteConnection(tenant.getId(), identity.name(), identity.logoUrl(),
				tenant.getConnectionCode(), url, Base64.getEncoder().encodeToString(QrCodes.png(url, QR_PIXELS_PER_MODULE)),
				CONNECTABLE.contains(tenant.getStatus()), mobileApps());
	}

	/**
	 * The public resolver. Unknown, malformed and not-currently-usable codes all give
	 * the same 404, so the endpoint reveals nothing about tenant status and never
	 * falls through to another tenant.
	 */
	@Transactional(readOnly = true)
	public PublicInstituteConnection resolve(String code) {
		if (!properties.isConfigured() || code == null || !CODE.matcher(code).matches()) {
			throw notFound();
		}
		Tenant tenant = tenantRepository.findByConnectionCode(code)
			.filter(t -> CONNECTABLE.contains(t.getStatus()))
			.orElseThrow(InstituteConnectionService::notFound);
		Identity identity = identity(tenant);
		Optional<String> origin = tenantLookupApi.tenantOrigin(tenant.getId(), tenantUrlTemplate);
		return new PublicInstituteConnection(VERSION, tenant.getConnectionCode(),
				connectionUrl(tenant.getConnectionCode()), identity.name(), identity.logoUrl(), origin.orElse(null),
				origin.map(o -> o + "/login").orElse(null), mobileApps());
	}

	private String connectionUrl(String code) {
		return properties.getBaseUrl() + PATH_PREFIX + code;
	}

	private MobileApps mobileApps() {
		return new MobileApps(properties.getAndroidAppUrl(), properties.getIosAppUrl());
	}

	/**
	 * Display name and logo, read under the given tenant's context: an uploaded site
	 * logo wins, then the configured {@code logo_url}. The previous context (if any)
	 * is restored, so a Tenant Admin request keeps its own.
	 */
	private Identity identity(Tenant tenant) {
		UUID previous = TenantContextHolder.isSet() ? new TenantContextHolder().getTenantId() : null;
		TenantContextHolder.set(tenant.getId());
		try {
			String name = text(tenantConfigApi.resolveDomain(tenant.getId(), ConfigDomain.GENERAL).get("institute_name"))
				.orElse(tenant.getName());
			String logo = tenantAssetService.publicAssetUrls(TenantAssetType.SITE_LOGO)
				.stream()
				.findFirst()
				.or(() -> text(tenantConfigApi.resolveDomain(tenant.getId(), ConfigDomain.BRANDING).get("logo_url")))
				.orElse(null);
			return new Identity(name, logo);
		}
		finally {
			if (previous == null) {
				TenantContextHolder.clear();
			}
			else {
				TenantContextHolder.set(previous);
			}
		}
	}

	private static Optional<String> text(Object value) {
		return value instanceof String s && !s.isBlank() ? Optional.of(s.trim()) : Optional.empty();
	}

	private static NotFoundException notFound() {
		return new NotFoundException("This institute link is not valid or the institute is not available.");
	}

	/** 503: no platform origin is configured, so no link (or QR) can be built. */
	static final class NotConfiguredException extends ApplicationException {

		NotConfiguredException() {
			super(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCodes.INSTITUTE_CONNECTION_NOT_CONFIGURED,
					"Institute connection links are not configured on this platform yet.");
		}

	}

	private record Identity(String name, String logoUrl) {
	}

	public record MobileApps(String androidUrl, String iosUrl) {
	}

	/** Admin view. {@code qrPngBase64} is the QR of exactly {@code connectionUrl}. */
	public record InstituteConnection(UUID tenantId, String instituteName, String logoUrl, String connectionCode,
			String connectionUrl, String qrPngBase64, boolean connectable, MobileApps mobileApps) {
	}

	/** Public, allowlisted view - no tenant id, contacts, status or settings. */
	public record PublicInstituteConnection(int version, String connectionCode, String connectionUrl,
			String instituteName, String logoUrl, String siteOrigin, String signInUrl, MobileApps mobileApps) {
	}

}
