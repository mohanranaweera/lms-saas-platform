package com.lms.attendancemanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.attendancemanagement.domain.AttendanceStatus;
import com.lms.attendancemanagement.web.dto.AttendanceMarkResultResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.liveclassmanagement.web.dto.ClassSessionResponse;
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
 * Wave 8 duplicate-sheet race: two genuinely concurrent FIRST marks of the
 * same class session (no sheet exists yet) must both succeed and leave
 * exactly one {@code attendance_sheet} and one record - the partial unique
 * index + {@code INSERT ... ON CONFLICT DO NOTHING} + re-read in {@code
 * AttendanceSheetService}. Same CyclicBarrier structure as {@link
 * AttendanceMarkingConcurrencyIntegrationTest}.
 */
class AttendanceClassSessionConcurrencyIntegrationTest extends AttendanceClassSessionTestSupport {

	@Test
	void concurrentFirstMarksOfTheSameSessionCreateExactlyOneSheetAndOneRecord() throws Exception {
		AttendanceFixture fixture = seedAttendanceFixture("att-cs-race");
		ClassSessionResponse session = scheduleStartedSession(fixture, "Race");

		int concurrency = 2;
		CyclicBarrier barrier = new CyclicBarrier(concurrency);
		ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		AttendanceStatus[] statuses = { AttendanceStatus.PRESENT, AttendanceStatus.LATE };
		String[] tokens = { fixture.teacherToken(), fixture.adminToken() };
		List<Callable<HttpResult<List<AttendanceMarkResultResponse>>>> tasks = new ArrayList<>();
		for (int i = 0; i < concurrency; i++) {
			AttendanceStatus status = statuses[i];
			String token = tokens[i];
			tasks.add(() -> {
				barrier.await();
				return markClassSessionOne(fixture.host(), token, session.id(), fixture.student().getId(), status);
			});
		}

		List<HttpResult<List<AttendanceMarkResultResponse>>> results = new ArrayList<>();
		try {
			for (Future<HttpResult<List<AttendanceMarkResultResponse>>> future : executor.invokeAll(tasks)) {
				results.add(future.get(15, TimeUnit.SECONDS));
			}
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(results).extracting(HttpResult::getStatusCode).containsExactly(HttpStatus.OK, HttpStatus.OK);
		assertThat(results).allSatisfy(result -> assertThat(result.getBody().data().get(0).success()).isTrue());
		assertThat(countSheetsForSession(session.id())).isEqualTo(1L);
		assertThat(countRecordsForSession(session.id())).isEqualTo(1L);
	}

}
