package com.lms.tenantmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.PayloadTooLargeException;
import com.lms.common.error.UnsupportedMediaTypeException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.integrationmanagement.api.DownloadOverrides;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.integrationmanagement.api.SignedDownloadUrl;
import com.lms.integrationmanagement.api.StoreObjectCommand;
import com.lms.integrationmanagement.api.StoredObject;
import com.lms.tenantmanagement.api.TenantAssetType;
import com.lms.tenantmanagement.domain.TenantAsset;
import com.lms.tenantmanagement.repository.TenantAssetRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.lms.contentmanagement.material.service.ContentSniffer;

@Service
public class TenantAssetService {
	private static final Duration PREVIEW_TTL = Duration.ofMinutes(5);
	/** Anonymous public-page URLs (logo, favicon, banners) must outlive a page that stays open. */
	static final Duration PUBLIC_URL_TTL = Duration.ofMinutes(60);
	private static final long ONE_MB = 1024L * 1024;
	/** Live assets allowed per gallery (hero banner) type. */
	public static final int MAX_GALLERY_ASSETS = 6;
	/** One live asset per tenant; a new upload replaces (soft-deletes) the previous one. */
	private static final Set<TenantAssetType> SINGLETON_TYPES = EnumSet.of(TenantAssetType.SITE_LOGO,
			TenantAssetType.SITE_FAVICON, TenantAssetType.RECEIPT_LOGO);
	private static final Set<TenantAssetType> GALLERY_TYPES = EnumSet.of(TenantAssetType.DESKTOP_HERO_BANNER,
			TenantAssetType.MOBILE_HERO_BANNER);
	/** Types an anonymous visitor of the tenant's public page may receive signed URLs for. */
	private static final Set<TenantAssetType> PUBLIC_TYPES = EnumSet.of(TenantAssetType.SITE_LOGO,
			TenantAssetType.SITE_FAVICON, TenantAssetType.DESKTOP_HERO_BANNER, TenantAssetType.MOBILE_HERO_BANNER);
	private final TenantAssetRepository repository;
	private final TenantContext tenantContext;
	private final PermissionCheckService permissionCheckService;
	private final ObjectStorageApi objectStorageApi;
	private final AuditLogApi auditLogApi;
	private final ApplicationEventPublisher eventPublisher;
	private final TransactionTemplate transactionTemplate;

	public TenantAssetService(TenantAssetRepository repository, TenantContext tenantContext,
			PermissionCheckService permissionCheckService, ObjectStorageApi objectStorageApi,
			AuditLogApi auditLogApi, ApplicationEventPublisher eventPublisher,
			PlatformTransactionManager transactionManager) {
		this.repository = repository;
		this.tenantContext = tenantContext;
		this.permissionCheckService = permissionCheckService;
		this.objectStorageApi = objectStorageApi;
		this.auditLogApi = auditLogApi;
		this.eventPublisher = eventPublisher;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	public TenantAsset upload(TenantAssetType type, MultipartFile file) {
		if (type == null) {
			throw new FieldValidationException("Asset purpose is required",
					List.of(new com.lms.common.api.FieldError("type", "Asset purpose is required")));
		}
		permissionCheckService.requirePermission(DomainArea.BRANDING_SETTINGS, PermissionAction.CREATE_EDIT);
		if (!SINGLETON_TYPES.contains(type) && !GALLERY_TYPES.contains(type)) {
			throw new FieldValidationException("Profile images must use profile-specific endpoints",
					List.of(new com.lms.common.api.FieldError("type", "Profile image purpose is not available here")));
		}
		if (file == null || file.isEmpty()) throw new FieldValidationException("An image is required", List.of(new com.lms.common.api.FieldError("file", "An image upload is required")));
		byte[] bytes = readBounded(file, maxBytes(type));
		String contentType = ContentSniffer.sniff(bytes);
		if (!"image/png".equals(contentType) && !"image/jpeg".equals(contentType)) {
			throw new UnsupportedMediaTypeException("Only PNG and JPEG images are supported");
		}
		// Cheap pre-check so a full gallery never touches storage; re-checked under the lock below.
		if (GALLERY_TYPES.contains(type) && repository.countLiveByType(type) >= MAX_GALLERY_ASSETS) {
			throw new TenantAssetLimitExceededException(MAX_GALLERY_ASSETS);
		}
		UUID tenantId = tenantContext.getTenantId();
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		StoredObject stored = objectStorageApi.store(new StoreObjectCommand(tenantId, new ByteArrayInputStream(bytes), contentType,
			bytes.length, safeName(file.getOriginalFilename())));
		try {
			return transactionTemplate.execute(status -> {
				repository.acquireLock("tenant-asset:" + tenantId + ":" + type.name());
				if (SINGLETON_TYPES.contains(type)) {
					repository.findAllLiveByType(type).forEach(existing -> {
						existing.markDeleted(actorId, Instant.now());
						repository.save(existing);
						eventPublisher.publishEvent(new TenantAssetDeletedEvent(existing.getObjectKey(), existing.getId()));
					});
					// The partial unique index (V104) checks the new row immediately; flush the soft delete first.
					repository.flush();
				}
				else if (repository.countLiveByType(type) >= MAX_GALLERY_ASSETS) {
					throw new TenantAssetLimitExceededException(MAX_GALLERY_ASSETS);
				}
				TenantAsset saved = repository.save(new TenantAsset(tenantId, type, stored.objectKey(), contentType,
					stored.sizeBytes(), sha256(bytes), actorId));
				auditLogApi.record(AuditLogEntry.of(actorId, "tenant_asset.uploaded", "tenant_asset", saved.getId()));
				return saved;
			});
		}
		catch (RuntimeException ex) {
			deleteQuietly(stored.objectKey());
			throw ex;
		}
	}

	/** Per-purpose upload limit: logo/receipt logo 2 MB, favicon 1 MB, hero banners 2 MB. */
	static long maxBytes(TenantAssetType type) {
		return switch (type) {
			case SITE_FAVICON -> ONE_MB;
			case SITE_LOGO, RECEIPT_LOGO, DESKTOP_HERO_BANNER, MOBILE_HERO_BANNER -> 2 * ONE_MB;
			case TEACHER_PROFILE_IMAGE, STUDENT_PROFILE_IMAGE -> throw new IllegalArgumentException(
					"Profile images are not uploaded through tenant assets");
		};
	}

	/** Live assets of {@code type} in the caller's tenant, oldest first. */
	@Transactional(readOnly = true)
	public List<TenantAsset> listLiveByType(TenantAssetType type) {
		permissionCheckService.requirePermission(DomainArea.BRANDING_SETTINGS, PermissionAction.VIEW);
		if (type == null) {
			throw new FieldValidationException("Asset purpose is required",
					List.of(new com.lms.common.api.FieldError("type", "Asset purpose is required")));
		}
		return repository.findAllLiveByType(type);
	}

	/**
	 * Anonymous public-page read: signed ({@link #PUBLIC_URL_TTL}) inline URLs of
	 * the current (host-resolved) tenant's live assets of {@code type}, oldest
	 * first. Only {@link #PUBLIC_TYPES} are ever exposed this way - a receipt
	 * logo or profile image yields an empty list. A URL that fails to sign is
	 * skipped rather than failing the whole page.
	 */
	@Transactional(readOnly = true)
	public List<String> publicAssetUrls(TenantAssetType type) {
		if (type == null || !PUBLIC_TYPES.contains(type)) {
			return List.of();
		}
		List<String> urls = new ArrayList<>();
		for (TenantAsset asset : repository.findAllLiveByType(type)) {
			try {
				urls.add(objectStorageApi.generateSignedDownloadUrl(asset.getObjectKey(), PUBLIC_URL_TTL,
						DownloadOverrides.inline(asset.getContentType(), "asset")).url());
			}
			catch (RuntimeException ignored) {
				// Degrade to "no image" for this asset; the public page must still render.
			}
		}
		return List.copyOf(urls);
	}

	@Transactional(readOnly = true)
	public TenantAsset findLiveByType(TenantAssetType type) {
		permissionCheckService.requirePermission(DomainArea.BRANDING_SETTINGS, PermissionAction.VIEW);
		return repository.findLiveByType(type).orElseThrow(() -> new NotFoundException("Tenant asset not found"));
	}

	@Transactional(readOnly = true)
	public SignedDownloadUrl preview(UUID id) {
		permissionCheckService.requirePermission(DomainArea.BRANDING_SETTINGS, PermissionAction.VIEW);
		TenantAsset asset = repository.findLiveById(id).orElseThrow(() -> new NotFoundException("Tenant asset not found"));
		return objectStorageApi.generateSignedDownloadUrl(asset.getObjectKey(), PREVIEW_TTL,
			DownloadOverrides.inline(asset.getContentType(), "asset"));
	}

	@Transactional
	public void delete(UUID id) {
		permissionCheckService.requirePermission(DomainArea.BRANDING_SETTINGS, PermissionAction.CREATE_EDIT);
		TenantAsset asset = repository.findLiveById(id).orElseThrow(() -> new NotFoundException("Tenant asset not found"));
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		asset.markDeleted(actorId, Instant.now());
		repository.save(asset);
		auditLogApi.record(AuditLogEntry.of(actorId, "tenant_asset.deleted", "tenant_asset", asset.getId()));
		eventPublisher.publishEvent(new TenantAssetDeletedEvent(asset.getObjectKey(), asset.getId()));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onDeleted(TenantAssetDeletedEvent event) { deleteQuietly(event.objectKey()); }

	private void deleteQuietly(String key) { try { objectStorageApi.delete(key); } catch (RuntimeException ignored) { } }
	private static String safeName(String raw) { if (raw == null || raw.isBlank()) return "asset"; int slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\')); return raw.substring(slash + 1).replaceAll("[^A-Za-z0-9._-]", "_"); }
	private static String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
	private static byte[] readBounded(MultipartFile file, long max) { try (InputStream in = file.getInputStream()) { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; long total = 0; int n; while ((n = in.read(buf)) >= 0) { total += n; if (total > max) throw new PayloadTooLargeException("Image exceeds maximum size"); out.write(buf, 0, n); } return out.toByteArray(); } catch (IOException e) { throw new UncheckedIOException(e); } }
	public record TenantAssetDeletedEvent(String objectKey, UUID assetId) { }
}
