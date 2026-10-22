package dev.alexreyes.securevault;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiDocsIntegrationTest extends IntegrationTestBase {

    @Test
    void openApiSpecDocumentsBearerAuthAndAllEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                .andExpect(jsonPath("$.security[0].bearerAuth").exists())
                // Public endpoints explicitly opt out of the global requirement.
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.security", hasSize(0)))
                .andExpect(jsonPath("$.paths['/api/auth/register'].post.security", hasSize(0)))
                .andExpect(jsonPath("$.paths['/api/users/me'].get").exists())
                .andExpect(jsonPath("$.paths['/api/documents'].get").exists())
                .andExpect(jsonPath("$.paths['/api/documents'].post.requestBody.content['multipart/form-data']").exists())
                .andExpect(jsonPath("$.paths['/api/documents/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/documents/{id}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/documents/{id}/download'].get").exists());
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("swagger-ui")));
    }
}
