package com.obraviva.platform.organizations;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateOrganizationRequest(@NotBlank @Size(max = 200) String name) {
}