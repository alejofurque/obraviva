package com.obraviva.platform.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * API pública del módulo auth hacia otros módulos: resuelve usuarios sin exponer la
 * entidad, que carga el hash de la contraseña.
 *
 * <p>Existe porque vincular un comprador a su unidad se hace por correo electrónico y
 * ese dato vive acá. Ningún módulo accede a {@code UserRepository} directamente.
 */
@Service
public class UserDirectoryService {

    private final UserRepository userRepository;

    public UserDirectoryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Usuario visto desde fuera del módulo. Nunca incluye credenciales. */
    public record UserSummary(UUID id, String email, String fullName) {
    }

    @Transactional(readOnly = true)
    public Optional<UserSummary> findByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByEmail(email.trim().toLowerCase()).map(UserDirectoryService::toSummary);
    }

    /**
     * Resuelve varios usuarios de una vez, para listados.
     *
     * <p>Devuelve un mapa y no una lista: quien llama tiene identificadores y necesita
     * nombres, y resolverlos de a uno dentro de un bucle produciría una consulta por
     * fila.
     */
    @Transactional(readOnly = true)
    public Map<UUID, UserSummary> findAllById(List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(userIds).stream()
                .map(UserDirectoryService::toSummary)
                .collect(Collectors.toMap(UserSummary::id, Function.identity()));
    }

    private static UserSummary toSummary(User user) {
        return new UserSummary(user.getId(), user.getEmail(), user.getFullName());
    }
}