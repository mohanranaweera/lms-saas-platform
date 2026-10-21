package com.lms.contentmanagement.material.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.error.ConflictException;
import com.lms.common.error.MediaNotReadyException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.material.domain.Material;
import com.lms.contentmanagement.material.domain.MaterialType;
import com.lms.contentmanagement.material.domain.MaterialVisibility;
import com.lms.contentmanagement.material.repository.MaterialRepository;
import com.lms.contentmanagement.material.web.dto.LessonMediaResponse;
import com.lms.coursemanagement.api.LessonOwnership;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.videoaccessmanagement.api.VideoAccessApi;
import com.lms.videoaccessmanagement.api.VideoAssetDetails;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LessonMedia facade over the existing content-management Material(VIDEO) rows
 * (VIDEO Phase 6, VID-ADR-003). The association is removed on detach, while the
 * owned video asset remains in the Media Library.
 */
@Service
public class LessonMediaService {

	private final MaterialRepository materialRepository;
	private final MaterialAccessGuard materialAccessGuard;
	private final VideoAccessApi videoAccessApi;
	private final TenantContext tenantContext;
	private final AuditLogApi auditLogApi;

	public LessonMediaService(MaterialRepository materialRepository, MaterialAccessGuard materialAccessGuard,
			VideoAccessApi videoAccessApi, TenantContext tenantContext, AuditLogApi auditLogApi) {
		this.materialRepository = materialRepository;
		this.materialAccessGuard = materialAccessGuard;
		this.videoAccessApi = videoAccessApi;
		this.tenantContext = tenantContext;
		this.auditLogApi = auditLogApi;
	}

	@Transactional(readOnly = true)
	public List<LessonMediaResponse> list(UUID courseId, UUID lessonId) {
		LessonOwnership ownership = materialAccessGuard.requireLessonAccess(courseId, lessonId, PermissionAction.VIEW);
		List<Material> materials = materialRepository.findVideoMaterialsByLessonId(lessonId);
		if (materialAccessGuard.isStudent()) {
			materials = materials.stream().filter(material -> material.getVisibility() == MaterialVisibility.VISIBLE).toList();
		}
		Map<UUID, VideoAssetDetails> details = java.util.Optional.ofNullable(videoAccessApi.getDetailsBatch(
				materials.stream().map(Material::getVideoAssetId).toList(), tenantContext.getTenantId())).orElse(Map.of());
		return materials.stream()
				.filter(material -> details.containsKey(material.getVideoAssetId()))
				.map(material -> toResponse(material, ownership.courseId(), details.get(material.getVideoAssetId())))
				.toList();
	}

	@Transactional
	public LessonMediaResponse attach(UUID courseId, UUID lessonId, UUID mediaAssetId, String requestedTitle,
			Integer requestedSequence) {
		LessonOwnership ownership = materialAccessGuard.requireLessonAccess(courseId, lessonId, PermissionAction.CREATE_EDIT);
		UUID tenantId = tenantContext.getTenantId();
		VideoAssetDetails details = videoAccessApi.getDetails(mediaAssetId, tenantId)
				.orElseThrow(() -> new NotFoundException("Media asset not found"));
		if (!"READY".equals(details.status())) {
			throw new MediaNotReadyException("This video is still processing.");
		}
		if (materialRepository.findVideoByLessonIdAndVideoAssetId(lessonId, mediaAssetId).isPresent()) {
			throw new ConflictException("This video is already attached to the lesson.");
		}
		int sequence = requestedSequence == null ? materialRepository.findMaxSequenceByLessonId(lessonId) + 1
				: requestedSequence;
		if (materialRepository.existsByLessonIdAndSequence(lessonId, sequence)) {
			throw new ConflictException("A material with this position already exists in this lesson.");
		}
		String title = requestedTitle == null || requestedTitle.isBlank() ? details.title() : requestedTitle.trim();
		Material material = Material.video(tenantId, lessonId, null, MaterialType.VIDEO, title, mediaAssetId, sequence,
				null, null, null, AuthenticatedPrincipalHolder.get().userId());
		material = materialRepository.save(material);
		auditLogApi.record(new AuditLogEntry(AuthenticatedPrincipalHolder.get().userId(), "lesson_media.attached",
				"material", material.getId(), null,
				Map.of("lessonId", lessonId, "courseId", ownership.courseId(), "mediaAssetId", mediaAssetId,
						"title", title)));
		return toResponse(material, ownership.courseId(), details);
	}

	@Transactional
	public LessonMediaResponse update(UUID courseId, UUID lessonId, UUID lessonMediaId, String title, Integer sequence,
			MaterialVisibility visibility) {
		LessonOwnership ownership = materialAccessGuard.requireLessonAccess(courseId, lessonId, PermissionAction.CREATE_EDIT);
		Material material = findVideoMaterial(lessonId, lessonMediaId);
		if (!material.getSequence().equals(sequence)
				&& materialRepository.existsByLessonIdAndSequenceAndIdNot(lessonId, sequence, material.getId())) {
			throw new ConflictException("A material with this position already exists in this lesson.");
		}
		material.setTitle(title.trim());
		material.setSequence(sequence);
		material.setVisibility(visibility);
		material = materialRepository.save(material);
		VideoAssetDetails details = videoAccessApi.getDetails(material.getVideoAssetId(), tenantContext.getTenantId())
				.orElseThrow(() -> new NotFoundException("Media asset not found"));
		return toResponse(material, ownership.courseId(), details);
	}

	@Transactional
	public void detach(UUID courseId, UUID lessonId, UUID lessonMediaId) {
		LessonOwnership ownership = materialAccessGuard.requireLessonAccess(courseId, lessonId, PermissionAction.DELETE);
		Material material = findVideoMaterial(lessonId, lessonMediaId);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		UUID mediaAssetId = material.getVideoAssetId();
		// Keep the row: video_watch_session history references it (V77 FK).
		// The video asset stays in the media library (V82).
		material.markRemoved(actorId, java.time.Instant.now());
		auditLogApi.record(new AuditLogEntry(actorId, "lesson_media.detached", "material", lessonMediaId, null,
				Map.of("lessonId", lessonId, "courseId", ownership.courseId(), "mediaAssetId", mediaAssetId,
						"title", material.getTitle())));
	}

	private Material findVideoMaterial(UUID lessonId, UUID lessonMediaId) {
		return materialRepository.findByIdAndLessonId(lessonMediaId, lessonId)
				.filter(material -> material.getMaterialType() == MaterialType.VIDEO
						|| material.getMaterialType() == MaterialType.RECORDING)
				.orElseThrow(() -> new NotFoundException("Lesson media not found"));
	}

	private static LessonMediaResponse toResponse(Material material, UUID courseId, VideoAssetDetails details) {
		return new LessonMediaResponse(material.getId(), material.getLessonId(), courseId, material.getVideoAssetId(),
				material.getTitle(), material.getSequence(), material.getVisibility(), details.provider(),
				details.durationSeconds(), details.status(), material.getCreatedAt(), material.getUpdatedAt());
	}

}
