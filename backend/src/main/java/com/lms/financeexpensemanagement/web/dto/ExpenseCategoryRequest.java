package com.lms.financeexpensemanagement.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST/PUT /api/v1/finance/expense-categories}. */
public record ExpenseCategoryRequest(@NotBlank @Size(max = 100) String name, @Size(max = 500) String description) {

}
