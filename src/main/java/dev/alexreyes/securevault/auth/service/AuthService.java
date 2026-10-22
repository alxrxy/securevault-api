package dev.alexreyes.securevault.auth.service;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AuthService {

    private static final String DEFAULT_ROLE = "USER";
    private static final String DUPLICATE_EMAIL_MESSAGE = "An account with this email already exists";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException(DUPLICATE_EMAIL_MESSAGE);
        }

        AppUser user = new AppUser(email, passwordEncoder.encode(request.password()), DEFAULT_ROLE);
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // A concurrent registration for the same email won the race
            // between existsByEmail and the insert; the unique constraint
            // is the real guarantee.
            throw new DuplicateEmailException(DUPLICATE_EMAIL_MESSAGE);
        }
        return UserResponse.from(user);
    }

    /**
     * @throws org.springframework.security.core.AuthenticationException on
     *     unknown email or wrong password - deliberately indistinguishable.
     */
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, request.password()));

        AppUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Bad credentials"));
        return AuthResponse.bearer(jwtService.generateToken(user.getId()), jwtService.expirationSeconds());
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
