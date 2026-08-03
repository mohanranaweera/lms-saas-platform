package com.lms.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

	@Test
	void successResponseCarriesDataAndNoError() {
		ApiResponse<String> response = ApiResponse.success("ok");

		assertThat(response.success()).isTrue();
		assertThat(response.data()).isEqualTo("ok");
		assertThat(response.error()).isNull();
		assertThat(response.timestamp()).isNotNull();
	}

	@Test
	void errorResponseCarriesErrorAndNoData() {
		ApiError error = ApiError.of(ApiErrorCodes.NOT_FOUND, "missing");

		ApiResponse<Object> response = ApiResponse.error(error);

		assertThat(response.success()).isFalse();
		assertThat(response.data()).isNull();
		assertThat(response.error()).isEqualTo(error);
	}

	@Test
	void apiErrorDefaultsFieldErrorsToEmptyListNotNull() {
		ApiError error = new ApiError("CODE", "message", null);

		assertThat(error.fieldErrors()).isEqualTo(List.of());
	}

	@Test
	void validationFactoryUsesValidationErrorCode() {
		ApiError error = ApiError.validation("bad input", List.of(new FieldError("name", "must not be blank")));

		assertThat(error.code()).isEqualTo(ApiErrorCodes.VALIDATION_ERROR);
		assertThat(error.fieldErrors()).hasSize(1);
	}

}
