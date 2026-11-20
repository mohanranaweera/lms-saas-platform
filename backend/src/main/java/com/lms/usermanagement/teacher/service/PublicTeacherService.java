package com.lms.usermanagement.teacher.service;

import com.lms.common.api.PageResponse;
import com.lms.common.error.NotFoundException;
import com.lms.contentmanagement.api.TeacherProfileMediaApi;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.PublicCourseSummary;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.usermanagement.teacher.domain.ApprovalStatus;
import com.lms.usermanagement.teacher.domain.TeacherProfile;
import com.lms.usermanagement.teacher.repository.TeacherProfileRepository;
import com.lms.usermanagement.teacher.web.dto.PublicTeacherCourseResponse;
import com.lms.usermanagement.teacher.web.dto.PublicTeacherResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PublicTeacherService {

	private static final int MAX_PAGE_SIZE = 100;

	private final TeacherProfileRepository teacherProfileRepository;
	private final UserProvisioningApi userProvisioningApi;
	private final TeacherProfileMediaApi teacherProfileMediaApi;
	private final CourseLookupApi courseLookupApi;

	public PublicTeacherService(TeacherProfileRepository teacherProfileRepository,
			UserProvisioningApi userProvisioningApi, TeacherProfileMediaApi teacherProfileMediaApi,
			CourseLookupApi courseLookupApi) {
		this.teacherProfileRepository = teacherProfileRepository;
		this.userProvisioningApi = userProvisioningApi;
		this.teacherProfileMediaApi = teacherProfileMediaApi;
		this.courseLookupApi = courseLookupApi;
	}

	public PageResponse<PublicTeacherResponse> listTeachers(Pageable pageable) {
		Pageable safe = clamp(pageable);
		List<TeacherProfile> profiles = teacherProfileRepository.findAllPublicProfiles(ApprovalStatus.APPROVED, safe.getSort());
		Map<UUID, TenantUserSummary> summaries = summaries(profiles);
		List<PublicTeacherResponse> visible = profiles.stream()
			.filter(profile -> isActive(summaries.get(profile.getUserId())))
			.map(this::toResponse)
			.toList();
		int from = Math.min(safe.getPageNumber() * safe.getPageSize(), visible.size());
		int to = Math.min(from + safe.getPageSize(), visible.size());
		int totalPages = visible.isEmpty() ? 0 : (visible.size() + safe.getPageSize() - 1) / safe.getPageSize();
		return new PageResponse<>(visible.subList(from, to), safe.getPageNumber(), safe.getPageSize(), visible.size(), totalPages);
	}

	public PublicTeacherResponse getTeacher(UUID id) {
		TeacherProfile profile = teacherProfileRepository.findPublicProfileById(id, ApprovalStatus.APPROVED)
			.orElseThrow(() -> new NotFoundException("Teacher not found"));
		TenantUserSummary summary = summaries(List.of(profile)).get(profile.getUserId());
		if (!isActive(summary)) {
			throw new NotFoundException("Teacher not found");
		}
		return toResponse(profile);
	}

	private Pageable clamp(Pageable pageable) {
		int size = Math.min(Math.max(pageable.getPageSize(), 1), MAX_PAGE_SIZE);
		Sort sort = pageable.getSort().isSorted() ? pageable.getSort() : Sort.by(Sort.Order.asc("name").ignoreCase(), Sort.Order.asc("id"));
		return PageRequest.of(Math.max(pageable.getPageNumber(), 0), size, sort);
	}

	private Map<UUID, TenantUserSummary> summaries(List<TeacherProfile> profiles) {
		if (profiles.isEmpty()) return Map.of();
		return userProvisioningApi.findTenantUserSummaries(profiles.stream().map(TeacherProfile::getUserId).toList())
			.stream().collect(Collectors.toMap(TenantUserSummary::userId, Function.identity(), (a, b) -> a));
	}

	private static boolean isActive(TenantUserSummary summary) {
		return summary != null && "ACTIVE".equalsIgnoreCase(summary.status());
	}

	private PublicTeacherResponse toResponse(TeacherProfile profile) {
		String imageUrl = teacherProfileMediaApi == null || profile.getProfileMediaFileId() == null ? null
			: teacherProfileMediaApi.findLiveImage(profile.getProfileMediaFileId())
				.map(TeacherProfileMediaApi.ProfileImage::thumbnailUrl).orElse(null);
		List<PublicTeacherCourseResponse> courses = courseLookupApi.listPublicCourseSummariesByTeacherId(profile.getUserId()).stream()
			.map(course -> new PublicTeacherCourseResponse(course.id(), course.name(), course.slug(), course.category(), null)).toList();
		return new PublicTeacherResponse(profile.getId(), profile.getName(), profile.getQualifications(), profile.getDescription(), imageUrl, courses);
	}

	private PublicTeacherCourseResponse toCourseResponse(PublicCourseSummary course) {
		return new PublicTeacherCourseResponse(course.id(), course.name(), course.slug(), course.category(), course.coverImageUrl());
	}
}
