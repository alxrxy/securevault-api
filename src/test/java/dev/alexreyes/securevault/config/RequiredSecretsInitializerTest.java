package dev.alexreyes.securevault.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Uses a bare context with only the given properties (no OS environment),
 * so results don't depend on what happens to be set on the machine.
 */
class RequiredSecretsInitializerTest {

    @Test
    void refreshFailsNamingDbPasswordWhenUnset() {
        GenericApplicationContext context = contextWith(Map.of(
                "spring.datasource.password", "${DB_PASSWORD}",
                "app.jwt.secret", "c2VjcmV0"));

        assertThatThrownBy(context::refresh).hasMessageContaining("DB_PASSWORD");
    }

    @Test
    void refreshFailsWhenJwtSecretIsMissingEntirely() {
        GenericApplicationContext context = contextWith(Map.of("spring.datasource.password", "pw"));

        assertThatThrownBy(context::refresh).hasMessageContaining("app.jwt.secret");
    }

    @Test
    void refreshSucceedsWhenBothAreSet() {
        GenericApplicationContext context = contextWith(Map.of(
                "spring.datasource.password", "pw",
                "app.jwt.secret", "c2VjcmV0"));

        assertThatCode(context::refresh).doesNotThrowAnyException();
        context.close();
    }

    @Test
    void isRegisteredForEveryStartup() throws Exception {
        try (var in = getClass().getResourceAsStream("/META-INF/spring.factories")) {
            assertThat(new String(in.readAllBytes()))
                    .contains("ApplicationContextInitializer=" + RequiredSecretsInitializer.class.getName());
        }
    }

    private static GenericApplicationContext contextWith(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(environment);
        new RequiredSecretsInitializer().initialize(context);
        return context;
    }
}
