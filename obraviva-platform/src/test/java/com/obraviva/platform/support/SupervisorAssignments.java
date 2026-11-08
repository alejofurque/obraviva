package com.obraviva.platform.support;

import com.obraviva.platform.developments.DevelopmentMember;
import com.obraviva.platform.developments.DevelopmentMemberRepository;
import com.obraviva.platform.organizations.OrganizationMemberRepository;
import com.obraviva.platform.organizations.OrganizationRole;

import java.util.UUID;

/**
 * Armado de tests: asigna a una obra a todos los encargados de su organización.
 *
 * <p>Desde V23 un encargado sólo ve las obras asignadas. Los tests que ejercitan lo que
 * el encargado puede hacer en su obra lo asignan con esto; los que prueban la asignación
 * en sí usan la API del equipo de obra.
 */
public final class SupervisorAssignments {

    private SupervisorAssignments() {
    }

    public static void assignAllSupervisors(
            OrganizationMemberRepository members,
            DevelopmentMemberRepository assignments,
            UUID organizationId,
            UUID... developmentIds) {
        members.findByOrganizationIdOrderByCreatedAtAsc(organizationId).stream()
                .filter(member -> member.getRole() == OrganizationRole.MEMBER)
                .forEach(member -> {
                    for (UUID developmentId : developmentIds) {
                        if (!assignments.existsByDevelopmentIdAndUserId(developmentId, member.getUserId())) {
                            assignments.save(new DevelopmentMember(organizationId, developmentId, member.getUserId(), null));
                        }
                    }
                });
    }
}
