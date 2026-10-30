package com.lms.contentmanagement.library.domain;

/**
 * Purpose ("file area") of a stored file, fixed at upload. Only {@link #COURSE_LIBRARY}
 * files appear in the Media Library and can be attached to lessons; site files such as
 * teacher profile photos are managed where they are used. {@link #EXAM_QUESTION} images
 * (V109) are referenced only from sanitized exam question HTML and served through the exam
 * module's own authorization ({@code ExamQuestionMediaApi}). {@link #EXAM_QUESTION_PAPER} /
 * {@link #EXAM_MARKING_SCHEME} documents (V111) are attached to written exams and served
 * the same way ({@code ExamDocumentMediaApi}).
 */
public enum MediaFileUsage {
	COURSE_LIBRARY, TEACHER_PROFILE, EXAM_QUESTION, EXAM_QUESTION_PAPER, EXAM_MARKING_SCHEME
}
