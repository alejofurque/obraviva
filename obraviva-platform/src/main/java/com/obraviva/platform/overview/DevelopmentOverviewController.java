package com.obraviva.platform.overview;

import com.obraviva.platform.overview.OverviewDtos.DevelopmentOverviewResponse;
import com.obraviva.platform.overview.OverviewDtos.UnitOverviewResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.obraviva.platform.auth.CurrentUser.userId;

@RestController
@RequestMapping("/api")
public class DevelopmentOverviewController {

    private final DevelopmentOverviewService overviewService;

    public DevelopmentOverviewController(DevelopmentOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/developments/{developmentId}/overview")
    public DevelopmentOverviewResponse overview(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID developmentId) {
        return overviewService.overview(userId(jwt), developmentId);
    }

    @GetMapping("/units/{unitId}/overview")
    public UnitOverviewResponse unitOverview(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID unitId) {
        return overviewService.unitOverview(userId(jwt), unitId);
    }
}
