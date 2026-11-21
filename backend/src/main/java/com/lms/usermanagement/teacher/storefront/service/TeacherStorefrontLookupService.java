package com.lms.usermanagement.teacher.storefront.service;

import com.lms.common.tenant.StorefrontHostResolver;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.usermanagement.api.TeacherStorefrontLookupApi;
import com.lms.usermanagement.teacher.domain.ApprovalStatus;
import com.lms.usermanagement.teacher.repository.TeacherProfileRepository;
import com.lms.usermanagement.teacher.storefront.domain.TeacherStorefront;
import com.lms.usermanagement.teacher.storefront.domain.TeacherStorefrontProduct;
import com.lms.usermanagement.teacher.storefront.domain.TeacherStorefrontSlug;
import java.util.stream.Collectors;
import com.lms.usermanagement.teacher.storefront.repository.TeacherStorefrontProductRepository;
import com.lms.usermanagement.teacher.storefront.repository.TeacherStorefrontRepository;
import com.lms.usermanagement.teacher.storefront.repository.TeacherStorefrontSlugRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STORE-01 / ADR-037: {@link TeacherStorefrontLookupApi} and {@link StorefrontHostResolver} over the tenant-scoped
 * storefront repositories. Always reads fresh (ADR-037 decision 10): one
 * indexed lookup per request, and only on storefront-shaped hosts.
 */
@Service
@Transactional(readOnly = true)
public class TeacherStorefrontLookupService implements TeacherStorefrontLookupApi, StorefrontHostResolver {

	private final TeacherStorefrontRepository storefrontRepository;

	private final TeacherStorefrontSlugRepository slugRepository;

	private final TeacherStorefrontProductRepository productRepository;

	private final TenantLookupApi tenantLookupApi;

	private final TenantContext tenantContext;

	private final TeacherProfileRepository teacherProfileRepository;

	private final UserProvisioningApi userProvisioningApi;

	public TeacherStorefrontLookupService(TeacherStorefrontRepository storefrontRepository,
			TeacherStorefrontSlugRepository slugRepository, TeacherStorefrontProductRepository productRepository,
			TenantLookupApi tenantLookupApi, TenantContext tenantContext,
			TeacherProfileRepository teacherProfileRepository, UserProvisioningApi userProvisioningApi) {
		this.teacherProfileRepository = teacherProfileRepository;
		this.userProvisioningApi = userProvisioningApi;
		this.storefrontRepository = storefrontRepository;
		this.slugRepository = slugRepository;
		this.productRepository = productRepository;
		this.tenantLookupApi = tenantLookupApi;
		this.tenantContext = tenantContext;
	}

	@Override
	public HostLookup resolveForHost(String slug) {
		if (slug == null || slug.isBlank()) {
			return HostLookup.notFound();
		}
		String normalized = slug.trim().toLowerCase(Locale.ROOT);
		Optional<TeacherStorefront> current = storefrontRepository.findBySlug(normalized);
		if (current.isPresent()) {
			TeacherStorefront storefront = current.get();
			return (storefront.isEnabled() && isTeacherLive(storefront.getTeacherId())) ? new HostLookup(HostStatus.FOUND, storefront.getId(),
					storefront.getTeacherId(), storefront.getSlug(), null) : HostLookup.notFound();
		}
		Optional<TeacherStorefrontSlug> retired = slugRepository.findBySlug(normalized)
			.filter(TeacherStorefrontSlug::isRetired);
		if (retired.isEmpty()) {
			return HostLookup.notFound();
		}
		Optional<TeacherStorefront> owner = storefrontRepository.findById(retired.get().getStorefrontId())
			.filter(TeacherStorefront::isEnabled)
			.filter(storefront -> isTeacherLive(storefront.getTeacherId()));
		if (owner.isEmpty()) {
			return HostLookup.notFound();
		}
		return tenantLookupApi.storefrontHost(tenantContext.getTenantId(), owner.get().getSlug())
			.map(host -> new HostLookup(HostStatus.MOVED, null, null, owner.get().getSlug(), host))
			.orElseGet(HostLookup::notFound);
	}

	/**
	 * A storefront only resolves while its teacher is an approved, ACTIVE
	 * tenant user - a suspended teacher's storefront fails closed (404), the
	 * same rule the public teacher directory applies.
	 */
	private boolean isTeacherLive(UUID teacherId) {
		boolean approved = teacherProfileRepository.findByUserId(teacherId)
			.map(profile -> profile.getApprovalStatus() == ApprovalStatus.APPROVED)
			.orElse(false);
		if (!approved) {
			return false;
		}
		List<TenantUserSummary> users = userProvisioningApi.findTenantUserSummaries(List.of(teacherId));
		return users.stream().anyMatch(user -> "ACTIVE".equalsIgnoreCase(user.status()));
	}

	@Override
	public Set<UUID> findTeachersOnStorefront() {
		List<TeacherStorefront> enabled = storefrontRepository.findAllEnabled();
		if (enabled.isEmpty()) {
			return Set.of();
		}
		return liveTeachers(enabled.stream().map(TeacherStorefront::getTeacherId).collect(Collectors.toSet()));
	}

	@Override
	public Optional<String> soleStorefrontHostPublishing(UUID courseId) {
		if (courseId == null) {
			return Optional.empty();
		}
		Set<UUID> storefrontIds = productRepository.findPublishedByCourseId(courseId)
			.stream()
			.map(TeacherStorefrontProduct::getStorefrontId)
			.collect(Collectors.toSet());
		if (storefrontIds.isEmpty()) {
			return Optional.empty();
		}
		List<TeacherStorefront> enabled = storefrontRepository.findAllById(storefrontIds)
			.stream()
			.filter(TeacherStorefront::isEnabled)
			.toList();
		Set<UUID> live = liveTeachers(enabled.stream().map(TeacherStorefront::getTeacherId).collect(Collectors.toSet()));
		List<TeacherStorefront> candidates = enabled.stream()
			.filter(storefront -> live.contains(storefront.getTeacherId()))
			.toList();
		if (candidates.size() != 1) {
			return Optional.empty();
		}
		return tenantLookupApi.storefrontHost(tenantContext.getTenantId(), candidates.get(0).getSlug());
	}

	/** Batched {@link #isTeacherLive}: approved profile AND ACTIVE tenant user. */
	private Set<UUID> liveTeachers(Set<UUID> teacherIds) {
		if (teacherIds.isEmpty()) {
			return Set.of();
		}
		Set<UUID> approved = teacherProfileRepository.findApprovedByUserIdIn(teacherIds)
			.stream()
			.map(profile -> profile.getUserId())
			.collect(Collectors.toSet());
		if (approved.isEmpty()) {
			return Set.of();
		}
		return userProvisioningApi.findTenantUserSummaries(List.copyOf(approved))
			.stream()
			.filter(user -> "ACTIVE".equalsIgnoreCase(user.status()))
			.map(TenantUserSummary::userId)
			.filter(approved::contains)
			.collect(Collectors.toUnmodifiableSet());
	}

	@Override
	public Set<UUID> findPublishedCourseIds(UUID storefrontId) {
		if (storefrontId == null) {
			return Set.of();
		}
		Set<UUID> ids = new LinkedHashSet<>();
		productRepository.findPublishedByStorefrontId(storefrontId)
			.forEach(product -> ids.add(product.getCourseId()));
		return ids;
	}

	@Override
	public Optional<String> enabledStorefrontOrigin(UUID storefrontId, String urlTemplate) {
		if (storefrontId == null) {
			return Optional.empty();
		}
		return storefrontRepository.findById(storefrontId)
			.filter(TeacherStorefront::isEnabled)
			.flatMap(storefront -> tenantLookupApi.storefrontOrigin(tenantContext.getTenantId(), storefront.getSlug(),
					urlTemplate));
	}

}
