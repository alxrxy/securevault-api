package dev.alexreyes.securevault.security;

import dev.alexreyes.securevault.user.entity.AppUser;
import dev.alexreyes.securevault.user.repository.AppUserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads a user by email for Spring Security's authentication machinery
 * (used during login). The {@code username} in Spring Security terms is
 * this application's email address.
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final AppUserRepository userRepository;

    public CustomUserDetailsService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        AppUser user = userRepository.findByEmail(email)
                // Intentionally the same exception/message shape regardless
                // of whether the email exists - the controller layer turns
                // this into a single generic "invalid email or password".
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        return User.builder()
                .username(user.getEmail())
                .password(user.getPasswordHash())
                .authorities("ROLE_" + user.getRole())
                .build();
    }
}
