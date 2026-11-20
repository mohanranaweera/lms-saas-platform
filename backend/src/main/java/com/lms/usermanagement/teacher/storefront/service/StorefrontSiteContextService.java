package com.lms.usermanagement.teacher.storefront.service;

import com.lms.common.tenant.StorefrontContext;
import com.lms.common.tenant.StorefrontContextHolder;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.api.TeacherProfileMediaApi;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.usermanagement.teacher.domain.TeacherProfile;
import com.lms.usermanagement.teacher.repository.TeacherProfileRepository;
import com.lms.usermanagement.teacher.storefront.web.dto.StorefrontDtos.SiteContextResponse;
import com.lms.usermanagement.teacher.storefront.web.dto.StorefrontDtos.StorefrontPublicResponse;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STORE-01: {@code GET /api/v1/public/site-context} - tells the site whether
 * the host it is served on is the tenant site or a teacher storefront. The
 * storefront comes only from {@link StorefrontContextHolder}, which {@code
 * TenantResolutionFilter} set from the trusted host; nothing is read from the
 * request here.
 */
@Service
@Transactional(readOnly = true)
public class StorefrontSiteContextService {

	static final String KIND_TENANT = "TENANT";

	static final String KIND_STOREFRONT = "STOREFRONT";

	private final TeacherProfileRepository teacherProfileRepository;

	private final TeacherProfileMediaApi teacherProfileMediaApi;

	private final TenantLookupApi tenantLookupApi;

	private final TenantContext tenantContext;

	public StorefrontSiteContextService(TeacherProfileRepository teacherProfileRepository,
			TeacherProfileMediaApi teacherProfileMediaApi, TenantLookupApi tenantLookupApi,
			TenantContext tenantContext) {
		this.teacherProfileRepository = teacherProfileRepository;
		this.teacherProfileMediaApi = teacherProfileMediaApi;
		this.tenantLookupApi = tenantLookupApi;
		this.tenantContext = tenantContext;
	}

	public SiteContextResponse siteContext() {
		Optional<StorefrontContext> current = StorefrontContextHolder.current();
		if (current.isEmpty()) {
			return new SiteContextResponse(KIND_TENANT, null);
		}
		StorefrontContext storefront = current.get();
		Optional<TeacherProfile> profile = teacherProfileRepository.findByUserId(storefront.teacherId());
		boolean publicProfile = profile.map(p -> !p.isPublicProfileDisabled()).orElse(false);
		String photoUrl = null;
		if (publicProfile && teacherProfileMediaApi != null && profile.get().getProfileMediaFileId() != null) {
			photoUrl = teacherProfileMediaApi.findLiveImage(profile.get().getProfileMediaFileId())
				.map(TeacherProfileMediaApi.ProfileImage::thumbnailUrl)
				.orElse(null);
		}
		String host = tenantLookupApi.storefrontHost(tenantContext.getTenantId(), storefront.slug()).orElse(null);
		return new SiteContextResponse(KIND_STOREFRONT,
				new StorefrontPublicResponse(storefront.slug(), storefront.teacherId(),
						profile.map(TeacherProfile::getName).orElse(null), photoUrl,
						publicProfile ? profile.get().getDescription() : null, host));
	}

}
