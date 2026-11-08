package com.obraviva.platform.organizations;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;

    public OrganizationService(
            OrganizationRepository organizationRepository,
            OrganizationMemberRepository memberRepository) {
        this.organizationRepository = organizationRepository;
        this.memberRepository = memberRepository;
    }

    /**
     * Crea la organización y deja al creador como OWNER en la misma
     * transacción: una organización sin miembros sería inaccesible.
     */
    @Transactional
    public OrganizationResponse create(UUID userId, CreateOrganizationRequest request) {
        Organization organization = organizationRepository.save(new Organization(request.name()));
        memberRepository.save(new OrganizationMember(organization, userId, OrganizationRole.OWNER));
        return OrganizationResponse.from(organization);
    }

    @Transactional(readOnly = true)
    public List<OrganizationResponse> listForUser(UUID userId) {
        return memberRepository.findByUserId(userId).stream()
                .map(member -> OrganizationResponse.from(member.getOrganization()))
                .toList();
    }
}