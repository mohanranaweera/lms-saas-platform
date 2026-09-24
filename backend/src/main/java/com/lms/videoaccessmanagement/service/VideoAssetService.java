package com.lms.videoaccessmanagement.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.PayloadTooLargeException;
import com.lms.common.error.UnsupportedMediaTypeException;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.api.MaterialLookupApi;
import com.lms.contentmanagement.api.MaterialVideoOwnership;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.integrationmanagement.api.StoreObjectCommand;
import com.lms.integrationmanagement.api.StoredObject;
import com.lms.videoaccessmanagement.domain.VideoAsset;
import com.lms.videoaccessmanagement.domain.VideoPlaybackPolicy;
import com.lms.videoaccessmanagement.repository.VideoAssetRepository;
import com.lms.videoaccessmanagement.repository.VideoPlaybackPolicyRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Video-asset upload + playback-policy management (Wave 5, PAR-20-03/PAR-04).
 *
 * <p><b>Upload authorization (plan §4/§6's "two-step flow", explicitly
 * documented ordering decision):</b> {@link #upload} is loosely gated -
 * Teacher/Teacher-Assistant or staff holding {@code MATERIALS}/{@code
 * CREATE_EDIT} - because a freshly-uploaded video has no owning {@code
 * Material} yet, so there is nothing to resolve course-ownership FROM at
 * this point (unlike {@link com.lms.videoaccessmanagement.support.VideoAccessGuard},
 * which needs an already-attached {@code Material} to resolve entitlement).
 * The REAL, course-ownership-scoped entitlement check happens later, at
 * attach-to-material time, inside {@code
 * contentmanagement.material.service.MaterialService#createMaterial}'s
 * {@code VIDEO}/{@code RECORDING} branch (via {@link VideoAccessApiImpl}) -
 * this is an intentional two-step flow, not a gap: "upload first (loosely
 * gated)" then "attach second (where the real tenant/ownership check runs)".
 *
 * <p><b>Security-review fix (Wave 5 content-security review): {@link
 * #upsertPolicy} does NOT use the loose upload gate.</b> Unlike upload, a
 * policy mutation targets an EXISTING {@code videoAssetId} that (once
 * attached to a {@code Material}) has a real, resolvable course owner - so
 * the loose "any Teacher/TA in the tenant" gate is a genuine intra-tenant
 * IDOR here, not an unavoidable ordering constraint: it would let Teacher B
 * silently strip Teacher A's watermark/download/view-limit protections off a
 * video Teacher B doesn't own. {@link #requirePolicyAuthorization} resolves
 * ownership via {@link MaterialLookupApi#resolveVideoAssetOwnership}
 * (mirroring {@code
 * videoaccessmanagement.support.VideoAccessGuard#requireEntitlement}'s
 * Teacher-ownership check) and falls back to the loose upload-time gate ONLY
 * when the asset isn't yet attached to any material (nothing to check
 * ownership against yet - the same "no owner resolvable" window {@link
 * #upload} operates in).
 *
 * <p><b>Upload failure handling (documented design decision):</b> {@link
 * ObjectStorageApi#store} is synchronous - it either returns a real {@link
 * StoredObject} or throws. Because of that, there is no observable window in
 * this wave where a {@code video_asset} row would ever need to sit in
 * {@link com.lms.videoaccessmanagement.domain.VideoAssetStatus#PENDING}
 * while storage is still in flight, and no scenario where a storage failure
 * should leave behind a {@link
 * com.lms.videoaccessmanagement.domain.VideoAssetStatus#FAILED} row
 * referencing a key that was never actually stored: the entity is
 * constructed (already {@code READY}, see {@link VideoAsset}'s own javadoc)
 * ONLY after {@code store()} has already succeeded, mirroring {@code
 * MaterialService#createUploadedFileMaterial}'s identical "storage call
 * happens, then and only then is the entity constructed" pattern exactly. A
 * storage-layer exception therefore propagates with NO row ever persisted -
 * "no partial write on a storage error" is satisfied by never attempting the
 * write until after the one operation that could partially fail has already
 * fully succeeded.
 */
@Service
public class VideoAssetService {

	private static final String TEACHER_ROLE = "TEACHER";

	private static final String TEACHER_ASSISTANT_ROLE = "TEACHER_ASSISTANT";

	// I/O buffer size for the bounded streaming read - not itself the size
	// limit (maxFileSizeBytes is), mirrors MaterialService's identical constant.
	private static final int STREAM_READ_CHUNK_SIZE = 8192;

	private final VideoAssetRepository videoAssetRepository;

	private final VideoPlaybackPolicyRepository videoPlaybackPolicyRepository;

	private final ObjectStorageApi objectStorageApi;

	private final PermissionCheckService permissionCheckService;

	private final TenantContext tenantContext;

	private final MaterialLookupApi materialLookupApi;

	private final long maxFileSizeBytes;

	public VideoAssetService(VideoAssetRepository videoAssetRepository,
			VideoPlaybackPolicyRepository videoPlaybackPolicyRepository, ObjectStorageApi objectStorageApi,
			PermissionCheckService permissionCheckService, TenantContext tenantContext,
			MaterialLookupApi materialLookupApi,
			@Value("${app.video-access.asset.max-file-size-bytes}") long maxFileSizeBytes) {
		this.videoAssetRepository = videoAssetRepository;
		this.videoPlaybackPolicyRepository = videoPlaybackPolicyRepository;
		this.objectStorageApi = objectStorageApi;
		this.permissionCheckService = permissionCheckService;
		this.tenantContext = tenantContext;
		this.materialLookupApi = materialLookupApi;
		this.maxFileSizeBytes = maxFileSizeBytes;
	}

	/**
	 * Deliberately NOT {@code @Transactional} - calls {@link
	 * ObjectStorageApi}, an outbound dependency, per {@code
	 * .claude/rules/backend.md}'s "never span a transaction across an
	 * outbound call" rule, mirroring {@code MaterialService#createMaterial}'s
	 * identical non-transactional shape (the single {@code
	 * videoAssetRepository.save} call below is self-transactional via
	 * Spring Data's default per-method transaction).
	 */
	public VideoAssetView upload(MultipartFile file) {
		requireUploadOrPolicyAuthorization();
		UUID tenantId = tenantContext.getTenantId();
		UUID uploadedBy = AuthenticatedPrincipalHolder.get().userId();

		byte[] bytes = readBoundedBytes(file, maxFileSizeBytes);
		if (bytes.length == 0) {
			throw new PayloadTooLargeException("The uploaded file exceeds the maximum allowed size");
		}
		String sniffedMimeType = VideoContentSniffer.sniff(bytes);
		if (sniffedMimeType == null) {
			throw new UnsupportedMediaTypeException(
					"The uploaded file's content does not match an accepted video format (mp4, webm, or quicktime)");
		}

		String originalFilename = sanitizeFilename(file.getOriginalFilename());
		StoredObject stored = objectStorageApi.store(new StoreObjectCommand(tenantId,
				new ByteArrayInputStream(bytes), sniffedMimeType, bytes.length, originalFilename));

		VideoAsset asset = new VideoAsset(tenantId, stored.objectKey(), originalFilename, sniffedMimeType,
				stored.sizeBytes(), uploadedBy);
		asset = videoAssetRepository.save(asset);
		return toView(asset);
	}

	@Transactional
	public VideoPlaybackPolicyView upsertPolicy(UUID videoAssetId, PlaybackPolicyUpdateCommand command) {
		requirePolicyAuthorization(videoAssetId);
		validatePolicyFields(command);

		videoAssetRepository.findById(videoAssetId).orElseThrow(() -> new NotFoundException("Video asset not found"));

		VideoPlaybackPolicy policy = videoPlaybackPolicyRepository.findByVideoAssetId(videoAssetId)
			.map(existing -> {
				existing.update(command.accessStartAt(), command.accessEndAt(), command.maxViewsPerStudent(),
						command.maxWatchDurationSeconds(), command.allowSeeking(), command.allowDownload(),
						command.watermarkEnabled(), command.maxConcurrentSessions());
				return existing;
			})
			.orElseGet(() -> videoPlaybackPolicyRepository
				.save(new VideoPlaybackPolicy(tenantContext.getTenantId(), videoAssetId, command.accessStartAt(),
						command.accessEndAt(), command.maxViewsPerStudent(), command.maxWatchDurationSeconds(),
						command.allowSeeking(), command.allowDownload(), command.watermarkEnabled(),
						command.maxConcurrentSessions())));

		return toPolicyView(policy);
	}

	/**
	 * Loose upload/policy-management gate (see class javadoc): Teacher/TA
	 * (no ownership check possible yet - see class javadoc) or staff holding
	 * {@code MATERIALS}/{@code CREATE_EDIT}. Mirrors {@code
	 * LiveClassAccessGuard#requireManagementAccess}'s "no Student branch"
	 * shape - a Student caller falls through to {@link
	 * PermissionCheckService#requirePermission}, which denies with a plain
	 * 403 (Student has no grant in the matrix), same as that guard's
	 * management-only actions.
	 */
	private void requireUploadOrPolicyAuthorization() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (TEACHER_ROLE.equals(principal.role()) || TEACHER_ASSISTANT_ROLE.equals(principal.role())) {
			return;
		}
		permissionCheckService.requirePermission(DomainArea.MATERIALS, PermissionAction.CREATE_EDIT);
	}

	/**
	 * Ownership-aware policy-mutation gate (security-review fix, see class
	 * javadoc): a Teacher/TA may upsert the policy only for a video asset
	 * whose resolved owning course they teach; staff still delegates to the
	 * flat {@code MATERIALS}/{@code CREATE_EDIT} grant (ownership doesn't
	 * apply to staff, same as every other guard in this codebase). If the
	 * asset isn't attached to any material yet, there is nothing to resolve
	 * ownership from - falls back to {@link #requireUploadOrPolicyAuthorization}
	 * for that narrow pre-attach window only.
	 */
	private void requirePolicyAuthorization(UUID videoAssetId) {
		Optional<MaterialVideoOwnership> ownership = materialLookupApi.resolveVideoAssetOwnership(videoAssetId);
		if (ownership.isEmpty()) {
			requireUploadOrPolicyAuthorization();
			return;
		}
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (TEACHER_ROLE.equals(principal.role()) || TEACHER_ASSISTANT_ROLE.equals(principal.role())) {
			if (!ownership.get().teacherId().equals(principal.userId())) {
				throw new AccessDeniedException("You do not have permission to perform this action");
			}
			return;
		}
		permissionCheckService.requirePermission(DomainArea.MATERIALS, PermissionAction.CREATE_EDIT);
	}

	/**
	 * Defense-in-depth 400 validation on top of V51's own {@code
	 * ck_video_playback_policy_*} CHECK constraints (plan §5) - a clean
	 * {@link FieldValidationException} instead of a raw constraint-violation
	 * 500, mirroring {@code MaterialService#validateFieldsForType}'s spirit.
	 */
	private static void validatePolicyFields(PlaybackPolicyUpdateCommand command) {
		List<FieldError> errors = new ArrayList<>();
		if (command.accessStartAt() != null && command.accessEndAt() != null
				&& !command.accessEndAt().isAfter(command.accessStartAt())) {
			errors.add(new FieldError("accessEndAt", "accessEndAt must be after accessStartAt"));
		}
		if (command.maxViewsPerStudent() != null && command.maxViewsPerStudent() <= 0) {
			errors.add(new FieldError("maxViewsPerStudent", "maxViewsPerStudent must be positive when set"));
		}
		if (command.maxWatchDurationSeconds() != null && command.maxWatchDurationSeconds() <= 0) {
			errors.add(
					new FieldError("maxWatchDurationSeconds", "maxWatchDurationSeconds must be positive when set"));
		}
		if (command.maxConcurrentSessions() != null && command.maxConcurrentSessions() <= 0) {
			errors.add(new FieldError("maxConcurrentSessions", "maxConcurrentSessions must be positive when set"));
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException("Invalid video playback policy fields", errors);
		}
	}

	private static String sanitizeFilename(String rawFilename) {
		String name = rawFilename == null ? "upload" : rawFilename;
		int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		if (lastSlash >= 0) {
			name = name.substring(lastSlash + 1);
		}
		name = name.chars()
			.filter(c -> c >= 0x20)
			.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
			.toString();
		if (name.isBlank()) {
			name = "upload";
		}
		return name.length() > 255 ? name.substring(0, 255) : name;
	}

	/**
	 * Bounded streaming read, mirroring {@code
	 * MaterialService#readBoundedBytes} exactly - aborts as soon as the
	 * running byte count exceeds {@code maxFileSizeBytes}, never fully
	 * buffers an oversized upload before rejecting it.
	 */
	private static byte[] readBoundedBytes(MultipartFile file, long maxFileSizeBytes) {
		try (InputStream input = file.getInputStream()) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[STREAM_READ_CHUNK_SIZE];
			long totalBytesRead = 0;
			int bytesRead;
			while ((bytesRead = input.read(chunk)) != -1) {
				totalBytesRead += bytesRead;
				if (totalBytesRead > maxFileSizeBytes) {
					throw new PayloadTooLargeException("The uploaded file exceeds the maximum allowed size");
				}
				buffer.write(chunk, 0, bytesRead);
			}
			return buffer.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Failed to read uploaded video file", e);
		}
	}

	private static VideoAssetView toView(VideoAsset asset) {
		return new VideoAssetView(asset.getId(), asset.getOriginalFilename(), asset.getMimeType(),
				asset.getSizeBytes(), asset.getDurationSeconds(), asset.getStatus(), asset.getUploadedBy(),
				asset.getCreatedAt());
	}

	private static VideoPlaybackPolicyView toPolicyView(VideoPlaybackPolicy policy) {
		return new VideoPlaybackPolicyView(policy.getVideoAssetId(), policy.getAccessStartAt(),
				policy.getAccessEndAt(), policy.getMaxViewsPerStudent(), policy.getMaxWatchDurationSeconds(),
				policy.isAllowSeeking(), policy.isAllowDownload(), policy.isWatermarkEnabled(),
				policy.getMaxConcurrentSessions());
	}

}
