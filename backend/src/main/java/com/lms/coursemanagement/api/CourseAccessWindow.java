package com.lms.coursemanagement.api;

/**
 * Wraps {@code course.access_duration_days} for {@link CourseLookupApi#getAccessDurationDays(java.util.UUID)}.
 *
 * <p>This exists only to give the caller a way to distinguish three states that a bare
 * {@code Optional<Integer>} cannot express (an {@code Optional} cannot itself hold a
 * present-but-null value):
 * <ul>
 * <li>the course does not exist in the caller's tenant -&gt; {@link java.util.Optional#empty()}
 * is returned by {@link CourseLookupApi#getAccessDurationDays(java.util.UUID)} itself, this
 * record is never constructed;</li>
 * <li>the course exists and grants lifetime access -&gt; a present {@code
 * Optional<CourseAccessWindow>} whose {@link #accessDurationDays()} is {@code null}, mirroring
 * {@code course.access_duration_days}'s own "{@code NULL} = unlimited access" convention
 * (V11);</li>
 * <li>the course exists and grants time-limited access -&gt; a present {@code
 * Optional<CourseAccessWindow>} whose {@link #accessDurationDays()} is a positive day count.</li>
 * </ul>
 *
 * @param accessDurationDays the course's configured access window in days, or {@code null} for
 * lifetime access - mirrors {@code course.access_duration_days} exactly, including its
 * nullability.
 */
public record CourseAccessWindow(Integer accessDurationDays) {

}
