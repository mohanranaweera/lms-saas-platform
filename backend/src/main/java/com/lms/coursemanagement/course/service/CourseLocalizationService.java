package com.lms.coursemanagement.course.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseLocalization;
import com.lms.coursemanagement.course.repository.CourseLocalizationRepository;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.localizationmanagement.api.LocaleApi;
import com.lms.localizationmanagement.api.LocaleInputs;
import com.lms.localizationmanagement.api.LocalizationException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * I18N-01 section 8: public title/description variants of a course. The canonical course row
 * keeps its id, slug, owner and authorization; variants are additional fields only. Editing and
 * publishing use the course's own edit authorization ({@code COURSES_EDIT} at the controller,
 * {@link CourseAccessGuard} {@code CREATE_EDIT} here - a Teacher only for a course they own).
 * Drafts are never public. Any supported locale may be prepared, including one the tenant has
 * not enabled yet; the storefront only serves enabled locales.
 */
@Service
public class CourseLocalizationService {

	public static final String COURSE_LOCALIZATION_STALE = "COURSE_LOCALIZATION_STALE";

	static final int MAX_TITLE_LENGTH = 255;

	static final int MAX_DESCRIPTION_LENGTH = 20_000;

	public record VariantView(String locale, String title, String description, String status,
			String publishedTitle, String publishedDescription, Instant publishedAt, UUID publishedBy,
			boolean hasUnpublishedChanges, long version, Instant updatedAt) {
	}

	public record VariantsView(UUID courseId, String contentLocale, String originalTitle,
			List<VariantView> variants) {
	}

	private final CourseRepository courseRepository;

	private final CourseLocalizationRepository localizationRepository;

	private final CourseAccessGuard courseAccessGuard;

	private final LocaleApi localeApi;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final Clock clock = Clock.systemUTC();

	public CourseLocalizationService(CourseRepository courseRepository,
			CourseLocalizationRepository localizationRepository, CourseAccessGuard courseAccessGuard,
			LocaleApi localeApi, AuditLogApi auditLogApi, TenantContext tenantContext) {
		this.courseRepository = courseRepository;
		this.localizationRepository = localizationRepository;
		this.courseAccessGuard = courseAccessGuard;
		this.localeApi = localeApi;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	// ------------------------------------------------------------------ editor

	@Transactional(readOnly = true)
	public VariantsView list(UUID courseId) {
		Course course = editableCourse(courseId);
		return new VariantsView(course.getId(), course.getContentLocale(), course.getName(),
				localizationRepository.findByCourseId(courseId).stream().map(CourseLocalizationService::view).toList());
	}

	@Transactional(readOnly = true)
	public VariantView get(UUID courseId, String localeParam) {
		editableCourse(courseId);
		String locale = supportedLocale(localeParam);
		return localizationRepository.findByCourseIdAndLocale(courseId, locale)
			.map(CourseLocalizationService::view)
			.orElseThrow(() -> new NotFoundException("Course translation not found"));
	}

	/** Creates/updates the working copy; {@code expectedVersion} null = the variant must not exist yet. */
	@Transactional
	public VariantView save(UUID courseId, String localeParam, String title, String description,
			Long expectedVersion) {
		editableCourse(courseId);
		String locale = supportedLocale(localeParam);
		String cleanTitle = title == null ? "" : title.strip();
		if (cleanTitle.isEmpty() || cleanTitle.length() > MAX_TITLE_LENGTH) {
			throw new LocalizationException(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR,
					"title is required and must be at most " + MAX_TITLE_LENGTH + " characters");
		}
		String cleanDescription = description == null || description.isBlank() ? null : description.strip();
		if (cleanDescription != null && cleanDescription.length() > MAX_DESCRIPTION_LENGTH) {
			throw new LocalizationException(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR,
					"description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
		}
		Optional<CourseLocalization> existing = localizationRepository.findByCourseIdAndLocale(courseId, locale);
		CourseLocalization variant;
		if (existing.isPresent()) {
			requireVersion(existing.get(), expectedVersion, true);
			variant = existing.get();
		}
		else {
			if (expectedVersion != null) {
				throw stale();
			}
			variant = new CourseLocalization(tenantContext.getTenantId(), courseId, locale);
		}
		variant.edit(cleanTitle, cleanDescription);
		return view(flush(variant));
	}

	@Transactional
	public VariantView publish(UUID courseId, String localeParam, Long expectedVersion) {
		editableCourse(courseId);
		CourseLocalization variant = existingVariant(courseId, localeParam);
		requireVersion(variant, expectedVersion, false);
		variant.publish(AuthenticatedPrincipalHolder.get().userId(), clock.instant());
		CourseLocalization saved = flush(variant);
		audit("course_localization.published", saved);
		return view(saved);
	}

	@Transactional
	public VariantView unpublish(UUID courseId, String localeParam, Long expectedVersion) {
		editableCourse(courseId);
		CourseLocalization variant = existingVariant(courseId, localeParam);
		requireVersion(variant, expectedVersion, false);
		variant.unpublish();
		CourseLocalization saved = flush(variant);
		audit("course_localization.unpublished", saved);
		return view(saved);
	}

	private Course editableCourse(UUID courseId) {
		// Tenant-scoped finder: another tenant's course id is simply not found (404).
		Course course = courseRepository.findById(courseId).orElseThrow(() -> new NotFoundException("Course not found"));
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		return course;
	}

	private CourseLocalization existingVariant(UUID courseId, String localeParam) {
		String locale = supportedLocale(localeParam);
		return localizationRepository.findByCourseIdAndLocale(courseId, locale)
			.orElseThrow(() -> new NotFoundException("Course translation not found"));
	}

	private String supportedLocale(String localeParam) {
		return localeApi.canonicalize(localeParam).orElseThrow(() -> LocalizationException.localeNotSupported(localeParam));
	}

	/** {@code required} = the caller must send the version; otherwise it is checked only when sent. */
	private static void requireVersion(CourseLocalization variant, Long expectedVersion, boolean required) {
		if ((required && expectedVersion == null)
				|| (expectedVersion != null && variant.getVersion() != expectedVersion)) {
			throw stale();
		}
	}

	private CourseLocalization flush(CourseLocalization variant) {
		try {
			return localizationRepository.saveAndFlush(variant);
		}
		catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException ex) {
			throw stale();
		}
	}

	private static LocalizationException stale() {
		return new LocalizationException(HttpStatus.CONFLICT, COURSE_LOCALIZATION_STALE,
				"This translation was changed by someone else. Reload and try again.");
	}

	private void audit(String action, CourseLocalization variant) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("courseId", variant.getCourseId().toString());
		metadata.put("locale", variant.getLocale());
		auditLogApi.record(new AuditLogEntry(AuthenticatedPrincipalHolder.get().userId(), action,
				"course_localization", variant.getId(), null, metadata));
	}

	static VariantView view(CourseLocalization v) {
		return new VariantView(v.getLocale(), v.getTitle(), v.getDescription(), v.getStatus().name(),
				v.getPublishedTitle(), v.getPublishedDescription(), v.getPublishedAt(), v.getPublishedBy(),
				v.hasUnpublishedChanges(), v.getVersion(), v.getUpdatedAt());
	}

	// ------------------------------------------------------------------ public storefront

	/**
	 * The storefront views with the PUBLISHED variant of the request's resolved locale applied
	 * (doc section 2 chain: {@code locale} param / {@code X-LMS-Locale} / preference / cookie /
	 * ...), else the original. {@code contentLocale} = the locale actually served ({@code null}
	 * for a legacy original); {@code availableLocales} = the declared original language plus the
	 * published variants in enabled locales. Drafts are never read here.
	 */
	@Transactional(readOnly = true)
	public List<PublicCourseView> localizePublic(List<PublicCourseView> views, LocaleInputs inputs) {
		if (views.isEmpty()) {
			return views;
		}
		String locale = localeApi.resolve(inputs).locale();
		List<String> enabled = localeApi.enabledLocales();
		Map<UUID, List<CourseLocalization>> published = localizationRepository
			.findPublishedByCourseIdIn(views.stream().map(PublicCourseView::id).toList())
			.stream()
			.collect(Collectors.groupingBy(CourseLocalization::getCourseId));
		List<PublicCourseView> out = new ArrayList<>(views.size());
		for (PublicCourseView view : views) {
			List<CourseLocalization> variants = published.getOrDefault(view.id(), List.of());
			TreeSet<String> available = new TreeSet<>();
			if (view.contentLocale() != null) {
				available.add(view.contentLocale());
			}
			variants.stream().map(CourseLocalization::getLocale).filter(enabled::contains).forEach(available::add);
			Optional<CourseLocalization> match = locale.equals(view.contentLocale()) ? Optional.empty()
					: variants.stream().filter(v -> v.getLocale().equals(locale)).findFirst();
			if (match.isPresent()) {
				CourseLocalization v = match.get();
				String description = v.getPublishedDescription() != null ? v.getPublishedDescription()
						: view.description();
				out.add(view.localized(v.getPublishedTitle(), description, v.getLocale(), List.copyOf(available)));
			}
			else {
				out.add(view.localized(view.name(), view.description(), view.contentLocale(), List.copyOf(available)));
			}
		}
		return out;
	}

}
