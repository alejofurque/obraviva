package com.obraviva.platform.installation;

import com.obraviva.platform.auth.AccountProvisioningService;
import com.obraviva.platform.auth.UserDirectoryService.UserSummary;
import com.obraviva.platform.organizations.CreateOrganizationRequest;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import com.obraviva.platform.organizations.OrganizationResponse;
import com.obraviva.platform.organizations.OrganizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Seeder de DESARROLLO de la instalación: el responsable del desarrollo y su empresa.
 *
 * <p>ObraViva no es un servicio con registro abierto: se instala para un desarrollista,
 * que es dueño de una organización, y desde ahí suma encargados y comparte cada obra con
 * sus compradores. Por eso la empresa nace con la instalación y la interfaz no ofrece
 * crear organizaciones. Se desactiva con {@code obraviva.dev-seed.enabled=false}.
 */
@Component
@Order(10)
@ConditionalOnProperty(name = "obraviva.dev-seed.enabled", havingValue = "true")
public class InstallationSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InstallationSeeder.class);

    static final String ADMIN_FULL_NAME = "Responsable del desarrollo";

    private final AccountProvisioningService accountProvisioning;
    private final OrganizationService organizationService;
    private final OrganizationMembershipService membershipService;
    private final String adminEmail;
    private final String adminPassword;
    private final String organizationName;

    public InstallationSeeder(
            AccountProvisioningService accountProvisioning,
            OrganizationService organizationService,
            OrganizationMembershipService membershipService,
            @Value("${obraviva.dev-seed.admin-email}") String adminEmail,
            @Value("${obraviva.dev-seed.admin-password}") String adminPassword,
            @Value("${obraviva.dev-seed.organization-name}") String organizationName) {
        this.accountProvisioning = accountProvisioning;
        this.organizationService = organizationService;
        this.membershipService = membershipService;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.organizationName = organizationName;
    }

    @Override
    public void run(ApplicationArguments args) {
        UserSummary admin = ensureAdmin();
        ensureOrganization(admin.id());
    }

    UserSummary ensureAdmin() {
        return accountProvisioning.ensureAccount(adminEmail, ADMIN_FULL_NAME, adminPassword);
    }

    /**
     * La empresa de la instalación: la organización con ese nombre de la que el
     * responsable es dueño. Si ya es dueño de otras, no se tocan.
     */
    UUID ensureOrganization(UUID adminId) {
        return organizationService.listForUser(adminId).stream()
                .filter(organization -> organization.name().equalsIgnoreCase(organizationName))
                .map(OrganizationResponse::id)
                .filter(id -> membershipService.isOwnerOf(adminId, id))
                .findFirst()
                .orElseGet(() -> {
                    UUID id = organizationService
                            .create(adminId, new CreateOrganizationRequest(organizationName))
                            .id();
                    log.info("Organización de la instalación creada: {}", organizationName);
                    return id;
                });
    }
}
