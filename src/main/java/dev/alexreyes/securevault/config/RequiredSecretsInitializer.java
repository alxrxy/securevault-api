package dev.alexreyes.securevault.config;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Makes startup fail immediately, with a message naming the missing
 * setting, when a secret has no value - before any bean (DataSource,
 * Flyway) is created. Without this, Spring Boot's binder keeps an
 * unresolved {@code ${DB_PASSWORD}} as the literal password and the app
 * dies later with a misleading "password authentication failed".
 *
 * <p>Registered in {@code META-INF/spring.factories} so it applies to every
 * way the app starts, including tests.
 */
public class RequiredSecretsInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String[] REQUIRED = {"spring.datasource.password", "app.jwt.secret"};

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        // Checked by Spring at the start of refresh (validateRequiredProperties).
        context.getEnvironment().setRequiredProperties(REQUIRED);
    }
}
