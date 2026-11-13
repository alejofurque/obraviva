package com.obraviva.platform.developments;

import com.obraviva.platform.developments.CaptureSectorService.ReplaceSectorsRequest;
import com.obraviva.platform.developments.CaptureSectorService.SectorView;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.obraviva.platform.auth.CurrentUser.userId;

@RestController
@RequestMapping("/api/units/{unitId}/sectors")
public class CaptureSectorController {

    private final CaptureSectorService sectorService;

    public CaptureSectorController(CaptureSectorService sectorService) {
        this.sectorService = sectorService;
    }

    @GetMapping
    public List<String> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID unitId) {
        return sectorService.list(userId(jwt), unitId);
    }

    @PutMapping
    public List<String> replace(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID unitId,
            @Valid @RequestBody ReplaceSectorsRequest request) {
        return sectorService.replace(userId(jwt), unitId, request);
    }

    @GetMapping("/detail")
    public List<SectorView> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID unitId) {
        return sectorService.detail(userId(jwt), unitId);
    }
}
