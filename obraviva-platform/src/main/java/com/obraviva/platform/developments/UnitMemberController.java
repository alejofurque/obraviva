package com.obraviva.platform.developments;

import com.obraviva.platform.developments.UnitMemberDtos.AddUnitMemberRequest;
import com.obraviva.platform.developments.UnitMemberDtos.AddedUnitMemberResponse;
import com.obraviva.platform.developments.UnitMemberDtos.UnitMemberResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class UnitMemberController {

    private final UnitMemberService unitMemberService;

    public UnitMemberController(UnitMemberService unitMemberService) {
        this.unitMemberService = unitMemberService;
    }

    @PostMapping("/units/{unitId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public AddedUnitMemberResponse add(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID unitId,
            @Valid @RequestBody AddUnitMemberRequest request) {
        return unitMemberService.add(userId(jwt), unitId, request);
    }

    @GetMapping("/units/{unitId}/members")
    public List<UnitMemberResponse> listByUnit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID unitId) {
        return unitMemberService.listByUnit(userId(jwt), unitId);
    }

    @DeleteMapping("/unit-members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID memberId) {
        unitMemberService.remove(userId(jwt), memberId);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}