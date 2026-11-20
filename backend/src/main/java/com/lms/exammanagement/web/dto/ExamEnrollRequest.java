package com.lms.exammanagement.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/exams/{id}/enrollments}: student user ids (as returned by the candidates
 * endpoint). Each is re-validated server-side as a participant of the exam's course.
 */
public record ExamEnrollRequest(List<UUID> studentIds) {

}
