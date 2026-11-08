package com.obraviva.platform.progress;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public record UpdateProgressEntryRequest(
        UUID stageId,
        @NotNull LocalDate entryDate,
        @NotBlank @Size(max = 2000) String description) {
}