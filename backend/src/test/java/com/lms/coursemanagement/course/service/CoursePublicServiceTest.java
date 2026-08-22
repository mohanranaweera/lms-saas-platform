package com.lms.coursemanagement.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.api.PageResponse;
import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.repository.CourseRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * Mockito-only unit coverage for {@link CoursePublicService} (MVP-008),
 * required by the module plan §18 ("Draft/Private courses never returned
 * from the storefront-scoped read method, called directly - not via HTTP").
 * The real HTTP-layer equivalent lives in {@code
 * CoursePublicStorefrontIntegrationTest}; this class isolates that the
 * service queries the repository via the inherited, tenant-scoped {@code
 * findAll(Specification, Pageable)} - never a broader read followed by a
 * post-fetch filter - and that the public projection never leaks {@code
 * teacherId} or audit columns. The actual PUBLIC-only predicate content of
 * the {@code Specification} passed to the repository is proven at the
 * integration level ({@code CoursePublicStorefrontIntegrationTest}), not
 * here - a Mockito mock cannot meaningfully evaluate a JPA Criteria
 * predicate without a real {@code EntityManager}.
 */
@ExtendWith(MockitoExtension.class)
class CoursePublicServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	@Mock
	private CourseRepository courseRepository;

	private CoursePublicService coursePublicService;

	@BeforeEach
	void setUp() {
		coursePublicService = new CoursePublicService(courseRepository);
	}

	@Test
	void listPublishedCoursesQueriesTheRepositoryViaTheTenantScopedSpecificationPageableFinder() {
		Course published = courseFixture("Public Course", "public-course", CourseStatus.PUBLIC);
		Pageable pageable = PageRequest.of(0, 20);
		when(courseRepository.findAll(any(Specification.class), any(Pageable.class)))
			.thenReturn(new PageImpl<>(List.of(published), pageable, 1));

		PageResponse<PublicCourseView> page = coursePublicService.listPublishedCourses(pageable);

		verify(courseRepository).findAll(any(Specification.class), any(Pageable.class));
		assertThat(page.content()).hasSize(1);
		assertThat(page.content().get(0).slug()).isEqualTo("public-course");
		assertThat(page.totalElements()).isEqualTo(1);
	}

	@Test
	void listPublishedCoursesReturnsExactlyWhatTheRepositoryReturnsNeverMore() {
		// The service performs no additional in-memory filtering of its own -
		// if the repository (correctly, per its own PUBLIC-only Specification)
		// returns nothing, the service returns nothing. This is the "never a
		// post-fetch filter" half of the plan §15(d) requirement, verified at
		// the unit level.
		Pageable pageable = PageRequest.of(0, 20);
		when(courseRepository.findAll(any(Specification.class), any(Pageable.class)))
			.thenReturn(new PageImpl<>(List.of(), pageable, 0));

		PageResponse<PublicCourseView> page = coursePublicService.listPublishedCourses(pageable);

		assertThat(page.content()).isEmpty();
		assertThat(page.totalElements()).isZero();
	}

	@Test
	void publicProjectionNeverIncludesTeacherId() {
		Course published = courseFixture("Public Course", "public-course", CourseStatus.PUBLIC);
		Pageable pageable = PageRequest.of(0, 20);
		when(courseRepository.findAll(any(Specification.class), any(Pageable.class)))
			.thenReturn(new PageImpl<>(List.of(published), pageable, 1));

		PublicCourseView view = coursePublicService.listPublishedCourses(pageable).content().get(0);

		// PublicCourseView has no teacherId component at all - this is a
		// compile-time guarantee, not a runtime check, but asserting the
		// projected fields here documents that guarantee alongside the rest
		// of this test class rather than leaving it implicit.
		assertThat(view.name()).isEqualTo("Public Course");
		assertThat(view.slug()).isEqualTo("public-course");
	}

	@Test
	void listPublishedCoursesClampsAnOversizedRequestedPageSizeToTheServerSideMaximum() {
		Pageable oversized = PageRequest.of(0, 999_999);
		when(courseRepository.findAll(any(Specification.class), any(Pageable.class)))
			.thenAnswer(invocation -> {
				Pageable used = invocation.getArgument(1);
				return new PageImpl<Course>(List.of(), used, 0);
			});

		coursePublicService.listPublishedCourses(oversized);

		org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
		verify(courseRepository).findAll(any(Specification.class), captor.capture());
		assertThat(captor.getValue().getPageSize()).isEqualTo(100);
	}

	@Test
	void getPublishedCourseBySlugQueriesTheRepositoryScopedToPublicStatusOnly() {
		Course published = courseFixture("Public Course", "public-course", CourseStatus.PUBLIC);
		when(courseRepository.findBySlugAndStatus("public-course", CourseStatus.PUBLIC))
			.thenReturn(Optional.of(published));

		PublicCourseView view = coursePublicService.getPublishedCourseBySlug("public-course");

		verify(courseRepository).findBySlugAndStatus("public-course", CourseStatus.PUBLIC);
		assertThat(view.slug()).isEqualTo("public-course");
	}

	@Test
	void getPublishedCourseBySlugThrowsGenericNotFoundWhenTheRepositoryFindsNothing() {
		// Covers both a truly nonexistent slug AND a real DRAFT/PRIVATE slug
		// in the same tenant identically - findBySlugAndStatus's own
		// PUBLIC-scoped Specification means either case surfaces here as an
		// empty Optional, so this service never has the information needed
		// to distinguish them even if it wanted to (plan §13/§15(d)'s
		// anti-enumeration guarantee, enforced by construction).
		when(courseRepository.findBySlugAndStatus("draft-or-missing", CourseStatus.PUBLIC))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> coursePublicService.getPublishedCourseBySlug("draft-or-missing"))
			.isInstanceOf(NotFoundException.class);
	}

	private static Course courseFixture(String name, String slug, CourseStatus status) {
		return new Course(TENANT_ID, UUID.randomUUID(), name, slug, "Math", null, null, null, null, null,
				new BigDecimal("10.00"), null, null, status);
	}

}
