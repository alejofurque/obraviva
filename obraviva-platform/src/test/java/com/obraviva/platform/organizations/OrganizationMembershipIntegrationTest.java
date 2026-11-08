package com.obraviva.platform.organizations;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integración real: Postgres en contenedor + migraciones Flyway.
 * Verifica que esquema, entidades y restricciones coinciden.
 */
@DataJpaTest
@Testcontainers
class OrganizationMembershipIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository memberRepository;

    @Test
    void persistsAndReadsMembership() {
        User user = userRepository.save(
                new User("dev@obraviva.test", "hash-placeholder", "Desarrollista Uno"));
        Organization organization = organizationRepository.save(
                new Organization("Desarrollos Córdoba SA"));

        memberRepository.save(
                new OrganizationMember(organization, user.getId(), OrganizationRole.OWNER));

        List<OrganizationMember> members =
                memberRepository.findByOrganizationId(organization.getId());

        assertThat(members).hasSize(1);
        assertThat(members.getFirst().getUserId()).isEqualTo(user.getId());
        assertThat(members.getFirst().getRole()).isEqualTo(OrganizationRole.OWNER);
    }

    @Test
    void rejectsDuplicateMembershipInSameOrganization() {
        User user = userRepository.save(
                new User("dup@obraviva.test", "hash-placeholder", "Usuario Duplicado"));
        Organization organization = organizationRepository.save(
                new Organization("Otra Constructora SRL"));

        memberRepository.save(
                new OrganizationMember(organization, user.getId(), OrganizationRole.MEMBER));

        assertThatThrownBy(() -> memberRepository.saveAndFlush(
                new OrganizationMember(organization, user.getId(), OrganizationRole.OWNER)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}