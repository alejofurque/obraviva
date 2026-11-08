package com.obraviva.platform.auth;

import com.obraviva.platform.auth.UserDirectoryService.UserSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * API pública del módulo auth para dar de alta cuentas desde otros módulos.
 *
 * <p>ObraViva no tiene registro público: las cuentas de encargados y compradores las
 * crea el dueño del desarrollo al sumarlos. Si el correo ya tiene cuenta, se usa esa y
 * su contraseña no se toca.
 *
 * <p>La contraseña inicial la puede elegir el dueño; si no la elige, la genera el sistema y
 * se devuelve una sola vez, mismo criterio que el token de un enlace compartido: sólo se
 * guarda su hash. La elegida nunca vuelve en una respuesta ni se registra en el log.
 */
@Service
public class AccountProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(AccountProvisioningService.class);

    /** Sin i, l, o, 0 ni 1: la contraseña se dicta por teléfono o se copia a mano. */
    private static final char[] ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final int GROUPS = 3;
    private static final int GROUP_LENGTH = 4;
    /** Mínimo para una contraseña elegida por el dueño. */
    public static final int MIN_PASSWORD_LENGTH = 8;
    /** bcrypt sólo usa los primeros 72 bytes: más largo, el resto no protege nada. */
    private static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final SecureRandom random = new SecureRandom();

    public AccountProvisioningService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * Resultado del alta. {@code initialPassword} sólo existe si la cuenta se creó en esta
     * llamada con una contraseña generada: la que eligió el dueño no se devuelve.
     */
    public record ProvisionedAccount(UserSummary user, boolean created, String initialPassword) {
    }

    /**
     * Valida una contraseña elegida por el dueño: al menos ocho caracteres y no más de lo
     * que bcrypt protege. 400 con un mensaje que se puede mostrar tal cual.
     */
    public static void requireValidPassword(String password) {
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "La contraseña tiene que tener al menos " + MIN_PASSWORD_LENGTH + " caracteres.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "La contraseña es demasiado larga: puede tener hasta 72 caracteres.");
        }
    }

    /** Forma canónica de un correo: sin espacios alrededor y en minúsculas. */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Devuelve la cuenta del correo, creándola si no existe.
     *
     * <p>Vacío si la cuenta no existe y no hay nombre con qué crearla: quien llama
     * decide el mensaje, porque depende de a qué se estaba sumando la persona.
     */
    @Transactional
    public Optional<ProvisionedAccount> findOrCreate(String email, String fullName) {
        return findOrCreate(email, fullName, null);
    }

    /**
     * Igual que {@link #findOrCreate(String, String)}, con la contraseña que eligió el dueño.
     * Nula, se genera una. Si el correo ya tiene cuenta, se ignora y no se cambia nada:
     * cambiarla es otra operación, con sus propios controles.
     */
    @Transactional
    public Optional<ProvisionedAccount> findOrCreate(String email, String fullName, String chosenPassword) {
        String normalized = normalizeEmail(email);
        Optional<User> existing = userRepository.findByEmail(normalized);
        if (existing.isPresent()) {
            return Optional.of(new ProvisionedAccount(toSummary(existing.get()), false, null));
        }
        if (fullName == null || fullName.isBlank()) {
            return Optional.empty();
        }
        if (chosenPassword != null) {
            requireValidPassword(chosenPassword);
        }

        String password = chosenPassword != null ? chosenPassword : generatePassword();
        try {
            User user = userRepository.saveAndFlush(new User(
                    normalized, passwordEncoder.encode(password), fullName.trim()));
            log.info("Cuenta creada para {}", normalized);
            return Optional.of(new ProvisionedAccount(
                    toSummary(user), true, chosenPassword != null ? null : password));
        } catch (DataIntegrityViolationException e) {
            // Dos altas simultáneas del mismo correo: la otra ganó. Reintentar encuentra
            // la cuenta ya creada y la vincula.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "La cuenta se acaba de crear desde otra sesión. Volvé a intentar.");
        }
    }

    /**
     * Cuenta con una contraseña conocida, para los datos de demostración de la instalación.
     * Si el correo ya tiene cuenta, se usa esa y su contraseña no se toca.
     *
     * <p>No se expone por HTTP: las cuentas reales las crea el dueño y reciben una
     * contraseña generada.
     */
    @Transactional
    public UserSummary ensureAccount(String email, String fullName, String rawPassword) {
        String normalized = normalizeEmail(email);
        return userRepository.findByEmail(normalized)
                .map(AccountProvisioningService::toSummary)
                .orElseGet(() -> toSummary(userRepository.save(
                        new User(normalized, passwordEncoder.encode(rawPassword), fullName))));
    }

    /**
     * Reemplaza la contraseña por una inicial nueva y cierra todas las sesiones de la
     * cuenta. Devuelve la contraseña en claro una sola vez.
     *
     * <p>No decide quién puede hacerlo: eso depende de a qué organizaciones pertenece la
     * cuenta, que este módulo no conoce. Quien llama lo verifica antes.
     */
    @Transactional
    public String resetPassword(UUID userId) {
        return changePassword(userId, null);
    }

    /**
     * Reemplaza la contraseña por la que eligió el dueño o, si es nula, por una generada, y
     * cierra todas las sesiones de la cuenta revocando sus tokens de refresco. Devuelve la
     * generada en claro una sola vez; con una elegida devuelve nulo.
     *
     * <p>Igual que {@link #resetPassword(UUID)}, no decide quién puede hacerlo.
     */
    @Transactional
    public String changePassword(UUID userId, String chosenPassword) {
        if (chosenPassword != null) {
            requireValidPassword(chosenPassword);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cuenta no encontrada"));
        String password = chosenPassword != null ? chosenPassword : generatePassword();
        user.changePasswordHash(passwordEncoder.encode(password));
        refreshTokenService.revokeAllFor(userId);
        log.info("Contraseña {} para la cuenta {}", chosenPassword != null ? "elegida por el dueño" : "reiniciada", userId);
        return chosenPassword != null ? null : password;
    }

    private String generatePassword() {
        StringBuilder password = new StringBuilder(GROUPS * (GROUP_LENGTH + 1));
        for (int group = 0; group < GROUPS; group++) {
            if (group > 0) {
                password.append('-');
            }
            for (int i = 0; i < GROUP_LENGTH; i++) {
                password.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            }
        }
        return password.toString();
    }

    private static UserSummary toSummary(User user) {
        return new UserSummary(user.getId(), user.getEmail(), user.getFullName());
    }
}
