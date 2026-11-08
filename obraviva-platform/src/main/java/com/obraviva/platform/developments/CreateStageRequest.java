package com.obraviva.platform.developments;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateStageRequest(
        @NotBlank @Size(max = 200) String name,
        @Min(0) int orderIndex) {
}