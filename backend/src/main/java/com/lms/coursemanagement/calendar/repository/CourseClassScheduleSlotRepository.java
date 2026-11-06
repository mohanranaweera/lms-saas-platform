package com.lms.coursemanagement.calendar.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.coursemanagement.calendar.domain.CourseClassScheduleSlot;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/** Tenant-scoped, append-only (see {@link CourseClassScheduleRevisionRepository}). */
public interface CourseClassScheduleSlotRepository
		extends AppendOnlyTenantAwareRepository<CourseClassScheduleSlot, UUID> {

	default List<CourseClassScheduleSlot> findByRevisionIdIn(Collection<UUID> revisionIds) {
		if (revisionIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("revisionId").in(revisionIds),
				Sort.by("revisionId", "dayOfWeek", "startTime", "endTime", "id"));
	}

}
