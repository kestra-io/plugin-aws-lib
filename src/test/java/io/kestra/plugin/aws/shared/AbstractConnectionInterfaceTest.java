package io.kestra.plugin.aws.shared;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kestra.core.exceptions.KestraRuntimeException;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;

import jakarta.inject.Inject;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class AbstractConnectionInterfaceTest {
    private static final String ROLE_ARN = "arn:aws:iam::123456789012:role/demo";

    @Inject
    private RunContextFactory runContextFactory;

    @SuperBuilder
    @NoArgsConstructor
    public static class TestConnection extends AbstractConnection {
    }

    @Test
    void awsClientConfig_shouldRenderTheTokenAgainOnEveryStsRequest() throws Exception {
        // Deserialized like a flow, so the property caches its rendered value unless skipCache() is used.
        var connection = JacksonMapper.ofJson().convertValue(
            Map.of("id", "test", "type", TestConnection.class.getName(), "stsRoleArn", ROLE_ARN, "webIdentityToken", "{{ uuid() }}"),
            TestConnection.class
        );

        var config = connection.awsClientConfig(runContextFactory.of());

        // Rendering is left to the supplier, so building a config does not mint a token.
        assertThat(config.webIdentityToken(), nullValue());
        var first = ConnectionUtils.webIdentityRequest(config, "session").webIdentityToken();
        var second = ConnectionUtils.webIdentityRequest(config, "session").webIdentityToken();
        assertThat(first, not(emptyOrNullString()));
        assertThat(second, not(emptyOrNullString()));
        assertThat(first, not(second));
    }

    @Test
    void awsClientConfig_shouldFailWhenTheTokenRendersBlank() throws Exception {
        var connection = TestConnection.builder()
            .id("test")
            .type(TestConnection.class.getName())
            .stsRoleArn(Property.ofValue(ROLE_ARN))
            .webIdentityToken(Property.ofExpression("{{ '  ' }}"))
            .build();

        var config = connection.awsClientConfig(runContextFactory.of());

        var exception = assertThrows(KestraRuntimeException.class, () -> ConnectionUtils.webIdentityRequest(config, "session"));
        assertThat(exception.getMessage(), containsString("oidcToken(...)"));
    }

    @Test
    void awsClientConfig_shouldHaveNoSupplierWithoutToken() throws Exception {
        var connection = TestConnection.builder()
            .id("test")
            .type(TestConnection.class.getName())
            .stsRoleArn(Property.ofValue(ROLE_ARN))
            .build();

        var config = connection.awsClientConfig(runContextFactory.of());

        assertThat(config.webIdentityTokenSupplier(), nullValue());
    }
}
