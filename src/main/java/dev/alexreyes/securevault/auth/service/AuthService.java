package dev.alexreyes.securevault.auth.service;

import dev.alexreyes.securevault.audit.AuditAction;
import dev.alexreyes.securevault.audit.AuditOutcome;
import dev.alexreyes.securevault.audit.AuditService;
import dev.alexreyes.securevault.auth.dto.AuthResponse;
import dev.alexreyes.securevault.auth.dto.LoginRequest;
import dev.alexreyes.securevault.auth.dto.RegisterRequest;
import dev.alexreyes.securevault.exception.DuplicateEmailException;
import dev.alexreyes.securevault.security.JwtService;
import dev.alexreyes.securevault.user.dto.UserResponse;
import dev.alexreyes.securevault.user.entity.AppUser;
import dev.alexreyes.securevault.user.repository.AppUserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private static final String DEFAULT_ROLE = "USER";
    private static final String DUPLICATE_EMAIL_MESSAGE = "An account with this email already exists";
    private static final String USER_RESOURCE = "USER";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final AuditService auditService;

    public AuthService(
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.auditService = auditService;
    }

    @Transactional
    public UserResponse register(RegisterRequest request, String clientIp) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw duplicateEmail(clientIp);
        }

        AppUser user = new AppUser(email, passwordEncoder.encode(request.password()), DEFAULT_ROLE);
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // A concurrent registration for the same email won the race
            // between existsByEmail and the insert; the unique constraint
            // is the real guarantee.
            throw duplicateEmail(clientIp);
        }
        auditService.record(AuditAction.REGISTER, AuditOutcome.SUCCESS, user.getId(),
                USER_RESOURCE, user.getId(), clientIp);
        return UserResponse.from(user);
    }

    /**
     * @throws org.springframework.security.core.AuthenticationException on
     *     unknown email or wrong password - deliberately indistinguishable.
     */
    public AuthResponse login(LoginRequest request, String clientIp) {
        String email = normalizeEmail(request.email());
        try {
            authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, request.password()));
        } catch (AuthenticationException e) {
            // Record which account was targeted (if it exists) as the
            // resource; the actor is unknown. The client still gets the
            // same generic 401 either way.
            UUID targetUserId = userRepository.findByEmail(email).map(AppUser::getId).orElse(null);
            auditService.record(AuditAction.LOGIN, AuditOutcome.FAILURE, null,
                    targetUserId == null ? null : USER_RESOURCE, targetUserId, clientIp);
            throw e;
        }

        AppUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Bad credentials"));
        auditService.record(AuditAction.LOGIN, AuditOutcome.SUCCESS, user.getId(),
                USER_RESOURCE, user.getId(), clientIp);
        return AuthResponse.bearer(jwtService.generateToken(user.getId()), jwtService.expirationSeconds());
    }

    private DuplicateEmailException duplicateEmail(String clientIp) {
        // REQUIRES_NEW: this record survives the rollback of register().
        auditService.record(AuditAction.REGISTER, AuditOutcome.FAILURE, null, null, null, clientIp);
        return new DuplicateEmailException(DUPLICATE_EMAIL_MESSAGE);
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
