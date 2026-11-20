package com.lms.exammanagement.config;

import com.lms.exammanagement.service.exam.ExamAttemptSweepService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the Klass attempt deadline sweep every minute (D2). Disabled in the test profile
 * ({@code exam.attempt-sweep.scheduler-enabled=false}), where tests drive {@link
 * ExamAttemptSweepService#sweep()} directly with a fixed clock.
 */
@Component
@ConditionalOnProperty(prefix = "exam.attempt-sweep", name = "scheduler-enabled", havingValue = "true",
		matchIfMissing = true)
public class ExamAttemptSweepScheduler {

	private final ExamAttemptSweepService sweepService;

	public ExamAttemptSweepScheduler(ExamAttemptSweepService sweepService) {
		this.sweepService = sweepService;
	}

	@Scheduled(fixedDelayString = "${exam.attempt-sweep.delay-ms:60000}",
			initialDelayString = "${exam.attempt-sweep.initial-delay-ms:60000}")
	public void sweep() {
		sweepService.sweep();
	}

}
