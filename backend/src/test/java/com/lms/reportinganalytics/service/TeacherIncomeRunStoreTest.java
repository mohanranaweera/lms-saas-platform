package com.lms.reportinganalytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.error.ApplicationException;
import com.lms.common.error.ServiceUnavailableException;
import com.lms.reportinganalytics.config.TeacherIncomeReportProperties;
import com.lms.reportinganalytics.service.TeacherIncomeReport.Filters;
import com.lms.reportinganalytics.service.TeacherIncomeReport.Institute;
import com.lms.reportinganalytics.service.TeacherIncomeReport.Reconciliation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

/** RPT-01 run snapshot store: Redis outage -> 503, oversized snapshot -> 422, never stored. */
class TeacherIncomeRunStoreTest {

	private final UUID tenant = UUID.randomUUID();

	private final UUID user = UUID.randomUUID();

	private ValueOperations<String, String> values;

	private TeacherIncomeReportProperties properties;

	private TeacherIncomeRunStore store;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		StringRedisTemplate template = mock(StringRedisTemplate.class);
		values = mock(ValueOperations.class);
		when(template.opsForValue()).thenReturn(values);
		properties = new TeacherIncomeReportProperties();
		store = new TeacherIncomeRunStore(template, JsonMapper.builder().build(), properties);
	}

	private static TeacherIncomeReport report() {
		return new TeacherIncomeReport(UUID.randomUUID(), Instant.parse("2026-10-22T10:15:00Z"),
				Instant.parse("2026-10-22T10:00:00Z"), "UTC", new Institute("Institute"),
				new Filters(List.of(), null, "MONTHS", List.of("2025-03"), null, null, "Mar 2025"), List.of(),
				new Reconciliation(List.of(), List.of()));
	}

	@Test
	void redisOutageOnSaveOrReadIs503() {
		doThrow(new RedisConnectionFailureException("down")).when(values)
			.set(anyString(), anyString(), any(Duration.class));
		when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
		TeacherIncomeReport report = report();
		assertThatThrownBy(() -> store.save(tenant, user, report)).isInstanceOf(ServiceUnavailableException.class)
			.satisfies(ex -> assertThat(((ApplicationException) ex).getHttpStatus())
				.isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
		assertThatThrownBy(() -> store.find(tenant, user, report.runId()))
			.isInstanceOf(ServiceUnavailableException.class);
	}

	@Test
	void oversizedSnapshotIs422AndNotStored() {
		properties.setMaxRunBytes(100);
		assertThatThrownBy(() -> store.save(tenant, user, report())).isInstanceOf(ReportValidationException.class)
			.satisfies(ex -> assertThat(((ReportValidationException) ex).getErrorCode()).isEqualTo("REPORT_TOO_LARGE"));
		verify(values, never()).set(anyString(), anyString(), any(Duration.class));
	}

}
