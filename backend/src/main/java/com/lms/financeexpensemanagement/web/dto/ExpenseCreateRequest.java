package com.lms.financeexpensemanagement.web.dto;

import com.lms.financeexpensemanagement.domain.ExpenseMethod;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Multipart form fields of {@code POST /api/v1/finance/expenses} (the optional
 * receipt travels as a separate {@code attachment} part). There is no
 * {@code tenantId}, {@code currency} or {@code createdBy} field - all three
 * are resolved server-side from the trusted authenticated context.
 */
public record ExpenseCreateRequest(@NotNull UUID categoryId,
		@NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expenseDate,
		@NotBlank @Size(max = 500) String description,
		@NotNull @DecimalMin(value = "0.01") @DecimalMax(value = "9999999999.99") @Digits(integer = 10,
				fraction = 2) BigDecimal amount,
		@NotNull ExpenseMethod method, @Size(max = 255) String reference) {

}
