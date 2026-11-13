package com.obraviva.platform.organizations;

import com.obraviva.platform.organizations.OrganizationMemberDtos.AddedOrganizationMemberResponse;
import com.obraviva.platform.organizations.OrganizationMemberDtos.OrganizationMemberResponse;
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

import static com.obraviva.platform.auth.CurrentUser.userId;

@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {

    private final OrganizationService organizationService;
    private final OrganizationMemberService memberService;

    public OrganizationController(
            OrganizationService organizationService, OrganizationMemberService memberService) {
        this.organizationService = organizationService;
        this.memberService = memberService;
    }

    @GetMapping("/{organizationId}/members")
    public List<OrganizationMemberResponse> listMembers(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID organizationId) {
        return memberService.list(userId(jwt), organizationId);
    }

    @PostMapping("/{organizationId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public AddedOrganizationMemberResponse addMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID organizationId,
            @Valid @RequestBody AddOrganizationMemberRequest request) {
        return memberService.add(userId(jwt), organizationId, request);
    }

    @DeleteMapping("/{organizationId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID organizationId,
            @PathVariable UUID memberId) {
        memberService.remove(userId(jwt), organizationId, memberId);
    }

    @GetMapping
    public List<OrganizationResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return organizationService.listForUser(userId(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateOrganizationRequest request) {
        return organizationService.create(userId(jwt), request);
    }
}