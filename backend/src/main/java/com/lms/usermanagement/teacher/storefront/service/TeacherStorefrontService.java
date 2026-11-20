package com.lms.usermanagement.teacher.storefront.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.ApiErrorCodes;
import com.lms.common.api.PageResponse;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.StorefrontException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.usermanagement.teacher.domain.TeacherProfile;
import com.lms.usermanagement.teacher.repository.TeacherProfileRepository;
import com.lms.usermanagement.teacher.storefront.domain.TeacherStorefront;
import com.lms.usermanagement.teacher.storefront.domain.TeacherStorefrontProduct;
import com.lms.usermanagement.teacher.storefront.domain.TeacherStorefrontSlug;
import com.lms.usermanagement.teacher.storefront.repository.TeacherStorefrontProductRepository;
import com.lms.usermanagement.teacher.storefront.repository.TeacherStorefrontRepository;
import com.lms.usermanagement.teacher.storefront.repository.TeacherStorefrontSlugRepository;
import com.lms.usermanagement.teacher.storefront.web.dto.StorefrontDtos.AssignableCourseResponse;
import com.lms.usermanagement.teacher.storefront.web.dto.StorefrontDtos.StorefrontAdminResponse;
import com.lms.usermanagement.teacher.storefront.web.dto.StorefrontDtos.StorefrontProductItem;
import com.lms.usermanagement.teacher.storefront.web.dto.StorefrontDtos.StorefrontProductResponse;
import com.lms.usermanagement.teacher.storefront.web.dto.StorefrontDtos.StorefrontProductsRequest;
import com.lms.usermanagement.teacher.storefront.web.dto.StorefrontDtos.StorefrontUpdateRequest;
import com.lms.usermanagement.teacher.storefront.web.dto.StorefrontDtos.TeacherStorefrontRowResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STORE-01 / ADR-037: Tenant Admin (and Operator/Manager when granted)
 * management of teacher storefronts - enable/disable, slug, product list.
 * Every write re-checks {@code TEACHER_STOREFRONT_MANAGE} here (not only on
 * the controller) and writes an audit entry in the same transaction. Tenant
 * identity comes only from {@link TenantContext}; teachers are addressed by
 * their {@code tenant_user.id} and must have a teacher profile in the
 * caller's tenant (404 otherwise - a foreign teacher id is never revealed).
 */
@Service
public class TeacherStorefrontService {

	static final String TARGET = "teacher_storefront";

	static final Set<String> RESERVED_SLUGS = Set.of("www", "api", "admin", "app", "mail", "login", "static", "cdn",
			"assets", "platform", "auth", "student", "teacher", "tenant", "support", "help", "status", "docs", "blog");

	private static final Pattern SLUG = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])$");

	private static final int MAX_PAGE_SIZE = 100;

	private static final int MAX_PRODUCTS = 200;

	private final TeacherStorefrontRepository storefrontRepository;

	private final TeacherStorefrontSlugRepository slugRepository;

	private final TeacherStorefrontProductRepository productRepository;

	private final TeacherProfileRepository teacherProfileRepository;

	private final CourseLookupApi courseLookupApi;

	private final AuditLogApi auditLogApi;

	private final PermissionCheckService permissionCheckService;

	private final TenantLookupApi tenantLookupApi;

	private final TenantContext tenantContext;

	private final String tenantUrlTemplate;

	public TeacherStorefrontService(TeacherStorefrontRepository storefrontRepository,
			TeacherStorefrontSlugRepository slugRepository, TeacherStorefrontProductRepository productRepository,
			TeacherProfileRepository teacherProfileRepository, CourseLookupApi courseLookupApi,
			AuditLogApi auditLogApi, PermissionCheckService permissionCheckService, TenantLookupApi tenantLookupApi,
			TenantContext tenantContext, @Value("${app.public.tenant-url-template:}") String tenantUrlTemplate) {
		this.storefrontRepository = storefrontRepository;
		this.slugRepository = slugRepository;
		this.productRepository = productRepository;
		this.teacherProfileRepository = teacherProfileRepository;
		this.courseLookupApi = courseLookupApi;
		this.auditLogApi = auditLogApi;
		this.permissionCheckService = permissionCheckService;
		this.tenantLookupApi = tenantLookupApi;
		this.tenantContext = tenantContext;
		this.tenantUrlTemplate = tenantUrlTemplate;
	}

	// ------------------------------------------------------------------
	// Reads.
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public PageResponse<TeacherStorefrontRowResponse> list(int page, int size) {
		permissionCheckService.requireCapability(Capability.TEACHER_STOREFRONT_MANAGE);
		PageRequest request = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
				TeacherProfileRepository.DISPLAY_ORDER);
		Page<TeacherProfile> profiles = teacherProfileRepository.findAll(request);
		Map<UUID, TeacherStorefront> storefronts = storefrontRepository
			.findByTeacherIdIn(profiles.getContent().stream().map(TeacherProfile::getUserId).toList())
			.stream()
			.collect(Collectors.toMap(TeacherStorefront::getTeacherId, Function.identity()));
		return PageResponse.from(profiles.map(profile -> {
			TeacherStorefront storefront = storefronts.get(profile.getUserId());
			return new TeacherStorefrontRowResponse(profile.getUserId(), profile.getName(),
					storefront == null ? null : toAdmin(storefront, true));
		}));
	}

	@Transactional(readOnly = true)
	public StorefrontAdminResponse get(UUID teacherId) {
		permissionCheckService.requireCapability(Capability.TEACHER_STOREFRONT_MANAGE);
		requireTeacher(teacherId);
		TeacherStorefront storefront = storefrontRepository.findByTeacherId(teacherId)
			.orElseThrow(() -> new NotFoundException("Storefront not found"));
		return toAdmin(storefront, true);
	}

	/** The signed-in Teacher's own storefront, read-only, without assignable courses. */
	@Transactional(readOnly = true)
	public StorefrontAdminResponse getOwn() {
		var principal = AuthenticatedPrincipalHolder.get();
		if (principal == null || !"TEACHER".equals(principal.role())) {
			throw new AccessDeniedException("Only a teacher may view their own storefront");
		}
		TeacherStorefront storefront = storefrontRepository.findByTeacherId(principal.userId())
			.orElseThrow(() -> new NotFoundException("Storefront not found"));
		return toAdmin(storefront, false);
	}

	// ------------------------------------------------------------------
	// Writes.
	// ------------------------------------------------------------------

	/**
	 * Creates the teacher's storefront on first call, otherwise changes its
	 * slug and/or enabled flag. A slug ever used by another teacher's
	 * storefront in this tenant is 409 {@code STOREFRONT_SLUG_TAKEN}; the
	 * storefront's own retired slug may be taken back.
	 */
	@Transactional
	public StorefrontAdminResponse upsert(UUID teacherId, StorefrontUpdateRequest request) {
		permissionCheckService.requireCapability(Capability.TEACHER_STOREFRONT_MANAGE);
		requireTeacher(teacherId);
		String slug = validSlug(request == null ? null : request.slug());
		Boolean enabled = request == null ? null : request.enabled();
		UUID tenantId = tenantContext.getTenantId();
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		Optional<TeacherStorefront> existing = storefrontRepository.findByTeacherId(teacherId);
		Optional<TeacherStorefrontSlug> slugRow = slugRepository.findBySlug(slug);
		UUID ownId = existing.map(TeacherStorefront::getId).orElse(null);
		if (slugRow.isPresent() && !slugRow.get().getStorefrontId().equals(ownId)) {
			throw slugTaken();
		}
		if (storefrontRepository.findBySlug(slug).filter(other -> !other.getId().equals(ownId)).isPresent()) {
			throw slugTaken();
		}
		Instant now = Instant.now();
		try {
			TeacherStorefront storefront;
			if (existing.isEmpty()) {
				storefront = storefrontRepository
					.saveAndFlush(new TeacherStorefront(tenantId, teacherId, slug, Boolean.TRUE.equals(enabled)));
				slugRepository.save(new TeacherStorefrontSlug(tenantId, storefront.getId(), slug, now));
				Map<String, Object> metadata = new LinkedHashMap<>();
				metadata.put("teacherId", teacherId.toString());
				metadata.put("slug", slug);
				metadata.put("enabled", storefront.isEnabled());
				auditLogApi.record(new AuditLogEntry(actorId, "teacher_storefront.created", TARGET,
						storefront.getId(), null, metadata));
			}
			else {
				storefront = existing.get();
				String previousSlug = storefront.getSlug();
				if (!slug.equals(previousSlug)) {
					slugRepository.findBySlug(previousSlug).ifPresent(row -> row.retire(now));
					if (slugRow.isPresent()) {
						slugRow.get().reinstate();
					}
					else {
						slugRepository.save(new TeacherStorefrontSlug(tenantId, storefront.getId(), slug, now));
					}
					storefront.changeSlug(slug);
					auditLogApi.record(new AuditLogEntry(actorId, "teacher_storefront.slug_changed", TARGET,
							storefront.getId(), null, Map.of("teacherId", teacherId.toString(), "previousSlug",
									previousSlug, "newSlug", slug)));
				}
				if (enabled != null && enabled != storefront.isEnabled()) {
					storefront.setEnabled(enabled);
					auditLogApi.record(new AuditLogEntry(actorId,
							enabled ? "teacher_storefront.enabled" : "teacher_storefront.disabled", TARGET,
							storefront.getId(), null, Map.of("teacherId", teacherId.toString(), "previousEnabled",
									!enabled, "newEnabled", enabled)));
				}
				storefrontRepository.save(storefront);
			}
			storefrontRepository.flush();
			slugRepository.flush();
			return toAdmin(storefront, true);
		}
		catch (DataIntegrityViolationException ex) {
			// A concurrent writer took the slug between the checks above and the flush.
			throw slugTaken();
		}
	}

	/**
	 * Full replace of the storefront's product list. Every course must be one
	 * the teacher is assigned to in this tenant (a foreign-tenant or
	 * unassigned id is 422 {@code STOREFRONT_COURSE_NOT_ASSIGNED}). Never
	 * touches enrollments or entitlements (ADR-037 decision 9).
	 */
	@Transactional
	public StorefrontAdminResponse replaceProducts(UUID teacherId, StorefrontProductsRequest request) {
		permissionCheckService.requireCapability(Capability.TEACHER_STOREFRONT_MANAGE);
		requireTeacher(teacherId);
		TeacherStorefront storefront = storefrontRepository.findByTeacherId(teacherId)
			.orElseThrow(() -> new NotFoundException("Storefront not found"));
		List<StorefrontProductItem> items = (request == null || request.items() == null) ? List.of()
				: request.items();
		if (items.size() > MAX_PRODUCTS) {
			throw new StorefrontException(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR,
					"A storefront can list at most " + MAX_PRODUCTS + " courses");
		}
		Set<UUID> seen = new HashSet<>();
		for (StorefrontProductItem item : items) {
			if (item == null || item.courseId() == null) {
				throw new StorefrontException(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR,
						"Every item needs a courseId");
			}
			if (!seen.add(item.courseId())) {
				throw new StorefrontException(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR,
						"A course may be listed only once");
			}
		}
		for (StorefrontProductItem item : items) {
			// Tenant-scoped: another tenant's course id is simply "not assigned".
			if (!courseLookupApi.isTeacherAssigned(item.courseId(), teacherId)) {
				throw new StorefrontException(HttpStatus.UNPROCESSABLE_CONTENT,
						ApiErrorCodes.STOREFRONT_COURSE_NOT_ASSIGNED,
						"A storefront can only list courses its teacher is assigned to",
						Map.of("courseId", item.courseId().toString()));
			}
		}
		UUID tenantId = tenantContext.getTenantId();
		List<TeacherStorefrontProduct> current = productRepository.findByStorefrontId(storefront.getId());
		List<String> before = describe(current);
		Map<UUID, TeacherStorefrontProduct> byCourse = new HashMap<>();
		current.forEach(product -> byCourse.put(product.getCourseId(), product));
		List<TeacherStorefrontProduct> removed = current.stream()
			.filter(product -> !seen.contains(product.getCourseId()))
			.toList();
		if (!removed.isEmpty()) {
			productRepository.deleteAllInBatch(removed);
		}
		for (int i = 0; i < items.size(); i++) {
			StorefrontProductItem item = items.get(i);
			boolean published = item.published() == null || item.published();
			int sortOrder = item.sortOrder() == null ? i : item.sortOrder();
			TeacherStorefrontProduct product = byCourse.get(item.courseId());
			if (product == null) {
				productRepository.save(new TeacherStorefrontProduct(tenantId, storefront.getId(), teacherId,
						item.courseId(), published, sortOrder));
			}
			else {
				product.update(published, sortOrder);
				productRepository.save(product);
			}
		}
		productRepository.flush();
		List<String> after = describe(productRepository.findByStorefrontId(storefront.getId()));
		if (!before.equals(after)) {
			auditLogApi.record(new AuditLogEntry(AuthenticatedPrincipalHolder.get().userId(),
					"teacher_storefront.products_changed", TARGET, storefront.getId(), null,
					Map.of("teacherId", teacherId.toString(), "before", before, "after", after)));
		}
		return toAdmin(storefront, true);
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	/** ADR-037 section 4. Input is trimmed and lower-cased before the checks. */
	static String validSlug(String raw) {
		String slug = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
		if (!SLUG.matcher(slug).matches() || slug.contains("--")) {
			throw new StorefrontException(HttpStatus.BAD_REQUEST, ApiErrorCodes.STOREFRONT_SLUG_INVALID,
					"Use 3-40 lowercase letters, digits or single hyphens, not starting or ending with a hyphen");
		}
		if (RESERVED_SLUGS.contains(slug)) {
			throw new StorefrontException(HttpStatus.BAD_REQUEST, ApiErrorCodes.STOREFRONT_SLUG_RESERVED,
					"This address is reserved. Choose another one");
		}
		return slug;
	}

	private static StorefrontException slugTaken() {
		return new StorefrontException(HttpStatus.CONFLICT, ApiErrorCodes.STOREFRONT_SLUG_TAKEN,
				"This address is already used by another teacher's storefront");
	}

	private TeacherProfile requireTeacher(UUID teacherId) {
		if (teacherId == null) {
			throw new NotFoundException("Teacher not found");
		}
		return teacherProfileRepository.findByUserId(teacherId)
			.orElseThrow(() -> new NotFoundException("Teacher not found"));
	}

	private static List<String> describe(List<TeacherStorefrontProduct> products) {
		return products.stream()
			.map(product -> product.getCourseId() + ":" + (product.isPublished() ? "published" : "hidden") + ":"
					+ product.getSortOrder())
			.sorted()
			.toList();
	}

	private StorefrontAdminResponse toAdmin(TeacherStorefront storefront, boolean withAssignable) {
		List<TeacherStorefrontProduct> products = productRepository.findByStorefrontId(storefront.getId());
		Map<UUID, CourseSummary> courses = products.isEmpty() ? Map.of()
				: courseLookupApi
					.getCourseSummaries(products.stream().map(TeacherStorefrontProduct::getCourseId)
						.collect(Collectors.toSet()))
					.stream()
					.collect(Collectors.toMap(CourseSummary::id, Function.identity()));
		List<StorefrontProductResponse> productViews = new ArrayList<>();
		for (TeacherStorefrontProduct product : products) {
			CourseSummary course = courses.get(product.getCourseId());
			productViews.add(new StorefrontProductResponse(product.getCourseId(),
					course == null ? null : course.name(), course == null ? null : course.slug(),
					product.isPublished(), product.getSortOrder()));
		}
		List<String> previousSlugs = slugRepository.findByStorefrontId(storefront.getId())
			.stream()
			.filter(TeacherStorefrontSlug::isRetired)
			.map(TeacherStorefrontSlug::getSlug)
			.toList();
		List<AssignableCourseResponse> assignable = withAssignable
				? courseLookupApi.listAssignedCourseSummaries(storefront.getTeacherId())
					.stream()
					.map(course -> new AssignableCourseResponse(course.id(), course.name(), course.slug()))
					.toList()
				: null;
		String previewUrl = tenantLookupApi
			.storefrontOrigin(tenantContext.getTenantId(), storefront.getSlug(), tenantUrlTemplate)
			.orElse(null);
		return new StorefrontAdminResponse(storefront.getId(), storefront.getTeacherId(), storefront.getSlug(),
				storefront.isEnabled(), previewUrl, storefront.isEnabled() ? previewUrl : null, previousSlugs,
				productViews, assignable);
	}

}
