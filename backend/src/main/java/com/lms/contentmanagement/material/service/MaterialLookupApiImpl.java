package com.lms.contentmanagement.material.service;

import com.lms.contentmanagement.api.MaterialLookupApi;
import com.lms.contentmanagement.api.MaterialVideoOwnership;
import com.lms.contentmanagement.material.domain.Material;
import com.lms.contentmanagement.material.repository.MaterialRepository;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.LessonOwnership;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link MaterialLookupApi}'s single implementation. Deliberately resolves
 * ownership via {@code material.lessonId} only, never {@code
 * material.sessionId} - every material reachable through the ONE existing
 * material endpoint ({@code
 * .../lessons/{lessonId}/materials}) always carries a non-null {@code
 * lessonId} (the path segment), even when it also carries an optional {@code
 * sessionId}; resolving a session-only material's owning course would
 * require a narrow read on {@code liveclassmanagement.api}, which does not
 * exist yet and no current material can actually reach this method without
 * a lessonId. This is a deliberate scope-narrowing judgment call (see Wave 5
 * final report) - a future session-only material creation path would need to
 * either populate {@code lessonId} too, or this method extended with a
 * {@code liveclassmanagement} lookup.
 */
@Component
class MaterialLookupApiImpl implements MaterialLookupApi {

	private final MaterialRepository materialRepository;

	private final CourseLookupApi courseLookupApi;

	MaterialLookupApiImpl(MaterialRepository materialRepository, CourseLookupApi courseLookupApi) {
		this.materialRepository = materialRepository;
		this.courseLookupApi = courseLookupApi;
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> findVideoAssetIdsInUse(Collection<UUID> videoAssetIds) {
		return materialRepository.findByVideoAssetIds(videoAssetIds)
			.stream()
			.map(Material::getVideoAssetId)
			.collect(Collectors.toSet());
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.Map<UUID, Integer> countLessonAttachments(Collection<UUID> videoAssetIds) {
		return materialRepository.countLessonAttachmentsByVideoAssetIds(videoAssetIds);
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> findVideoAssetIdsForTeacher(UUID teacherUserId) {
		Set<UUID> lessonIds = courseLookupApi.findLessonIdsByTeacherId(teacherUserId);
		return materialRepository.findVideoAssetIdsByLessonIds(lessonIds);
	}

	@Override
	public Optional<MaterialVideoOwnership> resolveVideoAssetOwnership(UUID videoAssetId) {
		Optional<Material> materialOpt = materialRepository.findByVideoAssetId(videoAssetId);
		if (materialOpt.isEmpty()) {
			return Optional.empty();
		}
		Material material = materialOpt.get();
		if (material.getLessonId() == null) {
			return Optional.empty();
		}
		Optional<LessonOwnership> ownershipOpt = courseLookupApi.resolveLessonOwnership(material.getLessonId());
		return ownershipOpt.map(ownership -> new MaterialVideoOwnership(material.getId(), ownership.courseId(),
				ownership.teacherId(), ownership.courseOpenToEnrolledStudents()));
	}

}
