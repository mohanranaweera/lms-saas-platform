package com.lms.exammanagement.service.exam;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.exammanagement.repository.ExamAttemptRepository;
import com.lms.exammanagement.repository.ExamAttemptWrites.OverdueAttempt;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * D2 deadline sweep: finalizes (and grades) every open Klass attempt whose deadline has passed,
 * across tenants, one attempt per transaction with that attempt's own tenant set on the thread
 * (never an ambient value). Idempotent with lazy finalization and submit ({@link
 * ExamAttemptFinalizer}). Also prunes exam password failures older than a day.
 */
@Service
public class ExamAttemptSweepService {

	private static final Logger log = LoggerFactory.getLogger(ExamAttemptSweepService.class);

	static final int BATCH_SIZE = 200;

	static final int MAX_BATCHES = 50;

	private static final Duration PASSWORD_FAILURE_RETENTION = Duration.ofDays(1);

	private final ExamAttemptRepository attemptRepository;

	private final ExamAttemptFinalizer finalizer;

	private final Clock clock;

	public ExamAttemptSweepService(ExamAttemptRepository attemptRepository, ExamAttemptFinalizer finalizer,
			Clock clock) {
		this.attemptRepository = attemptRepository;
		this.finalizer = finalizer;
		this.clock = clock;
	}

	/** @return the number of attempts finalized by this run. */
	public int sweep() {
		Instant now = clock.instant();
		if (now == null) {
			return 0;
		}
		int finalized = 0;
		for (int batch = 0; batch < MAX_BATCHES; batch++) {
			List<OverdueAttempt> overdue = attemptRepository.findOverdueKlassAttemptsAcrossTenants(now, BATCH_SIZE);
			int before = finalized;
			for (OverdueAttempt attempt : overdue) {
				if (finalizeOne(attempt.tenantId(), attempt.attemptId(), now)) {
					finalized++;
				}
			}
			if (overdue.size() < BATCH_SIZE || finalized == before) {
				break;
			}
		}
		try {
			finalizer.prunePasswordFailures(now.minus(PASSWORD_FAILURE_RETENTION));
		}
		catch (RuntimeException ex) {
			log.atWarn().setMessage("exam.password_failure.prune_failed").setCause(ex).log();
		}
		return finalized;
	}

	private boolean finalizeOne(UUID tenantId, UUID attemptId, Instant now) {
		UUID previous = TenantContextHolder.isSet() ? new TenantContextHolder().getTenantId() : null;
		try {
			TenantContextHolder.set(tenantId);
			return finalizer.sweepOne(attemptId, now);
		}
		catch (RuntimeException ex) {
			log.atError()
				.setMessage("exam.attempt.sweep_failed")
				.addKeyValue("examTenantId", tenantId)
				.addKeyValue("attemptId", attemptId)
				.setCause(ex)
				.log();
			return false;
		}
		finally {
			if (previous == null) {
				TenantContextHolder.clear();
			}
			else {
				TenantContextHolder.set(previous);
			}
		}
	}

}
