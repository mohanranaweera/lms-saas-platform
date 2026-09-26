package com.lms.financeexpensemanagement.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/finance/expenses/{id}/void} - a reason is mandatory. */
public record ExpenseVoidRequest(@NotBlank @Size(max = 500) String reason) {

}
