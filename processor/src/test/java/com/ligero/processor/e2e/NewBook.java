package com.ligero.processor.e2e;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record NewBook(@NotBlank String title, @NotNull Genre genre) {
}
