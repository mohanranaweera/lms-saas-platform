package com.lms.attendancemanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.attendancemanagement.domain.AttendanceStatus;
import com.lms.attendancemanagement.web.dto.AttendanceMarkResultResponse;
import com.lms.identityaccessservice.HttpResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Genuine concurrent-Testcontainers coverage for the TOCTOU-race fix
 * documented on {@code AttendanceRecordRepository#upsertRecord}'s javadoc:
 * "two genuinely concurrent first-time marks of the same (tenant, session,
 * student) could both observe 'no existing row' and both attempt an INSERT,
 * one of which would then violate {@code
 * uq_attendance_record_tenant_session_student} (V25) and surface as an
 * unhandled {@code DataIntegrityViolationException}" - replaced by a single
 * atomic native {@code INSERT ... ON CONFLICT (tenant_id, session_id,
 * student_id) DO UPDATE}. Mirrors {@code
 * SlipApprovalConcurrencyIntegrationTest}'s established
 * CyclicBarrier-plus-ExecutorService structure for forcing genuine overlap
 * (not just sequential calls that happen to run close together).
 *
 * <p>Both concurrent requests are FIRST-TIME marks (no prior row exists for
 * this (session, student) pair before either request starts) - this is
 * exactly the race window the javadoc calls out, as opposed to {@code
 * AttendanceMarkingIntegrationTest#reMarkingTheSameSessionStudentUpdatesInPlaceRowCountStaysOne},
 * which proves the SEQUENTIAL re-mark case only.
 */
class AttendanceMarkingConcurrencyIntegrationTest extends AttendanceManagementTestSupport {

	@Test
	void concurrentFirstTimeMarksOfTheSameSessionStudentBothSucceedAndLeaveExactlyOneRow() throws Exception {
		AttendanceFixture fixture = seedAttendanceFixture("att-mark-race");

		int concurrency = 2;
		CyclicBarrier barrier = new CyclicBarrier(concurrency);
		ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		AttendanceStatus[] statusesToSubmit = { AttendanceStatus.PRESENT, AttendanceStatus.ABSENT };
		List<Callable<HttpResult<List<AttendanceMarkResultResponse>>>> tasks = new ArrayList<>();
		for (int i = 0; i < concurrency; i++) {
			AttendanceStatus status = statusesToSubmit[i];
			tasks.add(() -> {
				barrier.await();
				return markOneStudent(fixture.host(), fixture.teacherToken(), fixture.lessonId(),
						fixture.student().getId(), status);
			});
		}

		List<HttpResult<List<AttendanceMarkResultResponse>>> results = new ArrayList<>();
		try {
			List<Future<HttpResult<List<AttendanceMarkResultResponse>>>> futures = executor.invokeAll(tasks);
			for (Future<HttpResult<List<AttendanceMarkResultResponse>>> future : futures) {
				// Every delivery must complete cleanly with a successful mark
				// outcome - never an unhandled exception/500 from the
				// duplicate-INSERT race the atomic upsert is meant to close.
				results.add(future.get(15, TimeUnit.SECONDS));
			}
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(results).extracting(HttpResult::getStatusCode)
			.containsExactly(HttpStatus.OK, HttpStatus.OK);
		for (HttpResult<List<AttendanceMarkResultResponse>> result : results) {
			assertThat(result.getBody().data().get(0).success()).isTrue();
		}

		Long rowCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM attendance_record WHERE session_id = ? AND student_id = ?", Long.class,
				fixture.lessonId(), fixture.student().getId());
		assertThat(rowCount).isEqualTo(1L);

		// The surviving row's status must be ONE of the two submitted values -
		// whichever write the DB serialized last - never anything else, and
		// never left in a half-written state.
		String finalStatus = jdbcTemplate.queryForObject(
				"SELECT status FROM attendance_record WHERE session_id = ? AND student_id = ?", String.class,
				fixture.lessonId(), fixture.student().getId());
		assertThat(finalStatus).isIn("PRESENT", "ABSENT");
	}

}
