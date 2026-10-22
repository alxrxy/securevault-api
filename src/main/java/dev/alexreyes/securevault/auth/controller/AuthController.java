package dev.alexreyes.securevault.auth.controller;

import dev.alexreyes.securevault.auth.dto.AuthResponse;
import dev.alexreyes.securevault.auth.dto.LoginRequest;
import dev.alexreyes.securevault.auth.dto.RegisterRequest;
import dev.alexreyes.securevault.auth.service.AuthService;
import dev.alexreyes.securevault.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        // getRemoteAddr(), never X-Forwarded-For - see AuthRateLimitFilter.
        return authService.register(request, httpRequest.getRemoteAddr());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authService.login(request, httpRequest.getRemoteAddr());
    }
}
