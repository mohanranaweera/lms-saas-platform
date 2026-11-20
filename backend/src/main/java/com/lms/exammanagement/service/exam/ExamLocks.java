package com.lms.exammanagement.service.exam;

import java.util.UUID;

/**
 * Advisory-lock keys of the exam module. The exam configuration lock ({@code
 * ExamRepository#acquireExamConfigLock}) serializes configuration writes, attempt starts,
 * enrollment removals and refund handling of one exam.
 */
public final class ExamLocks {

	private ExamLocks() {
	}

	public static String configKey(UUID tenantId, UUID examId) {
		return "exam-config:" + tenantId + ":" + examId;
	}

}
