package io.kestra.plugin.aws.shared;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectionUtilsTest {

    private static AbstractConnection.AwsClientConfig config(
        String accessKeyId,
        String secretKeyId,
        String sessionToken,
        String stsRoleArn
    ) {
        return new AbstractConnection.AwsClientConfig(
            accessKeyId,
            secretKeyId,
            sessionToken,
            stsRoleArn,
            null,
            null,
            null,
            Duration.ofSeconds(900),
            "us-east-1",
            null
        );
    }

    // --- credentialsProvider selection ---

    @Test
    void credentialsProvider_shouldUseStsAssumeRoleWhenRoleArnIsSet() {
        var provider = ConnectionUtils.credentialsProvider(
            config("ak", "sk", null, "arn:aws:iam::123456789012:role/demo")
        );

        assertThat(provider, instanceOf(StsAssumeRoleCredentialsProvider.class));
    }

    @Test
    void credentialsProvider_shouldUseStaticBasicCredentialsWhenKeysAreSet() {
        var provider = ConnectionUtils.credentialsProvider(config("ak", "sk", null, null));

        assertThat(provider, instanceOf(StaticCredentialsProvider.class));
        assertThat(provider.resolveCredentials(), instanceOf(AwsBasicCredentials.class));
    }

    @Test
    void credentialsProvider_shouldUseSessionCredentialsWhenSessionTokenIsSet() {
        var provider = ConnectionUtils.credentialsProvider(config("ak", "sk", "token", null));

        assertThat(provider, instanceOf(StaticCredentialsProvider.class));
        assertThat(provider.resolveCredentials(), instanceOf(AwsSessionCredentials.class));
    }

    @Test
    void credentialsProvider_shouldFallBackToDefaultWhenNothingIsSet() {
        assertThat(
            ConnectionUtils.credentialsProvider(config(null, null, null, null)),
            instanceOf(DefaultCredentialsProvider.class)
        );
    }

    @Test
    void credentialsProvider_shouldFallBackToDefaultWhenOnlyAccessKeyIsSet() {
        assertThat(
            ConnectionUtils.credentialsProvider(config("ak", null, null, null)),
            instanceOf(DefaultCredentialsProvider.class)
        );
    }

    // --- rejectLinkLocalMetadataHost (SSRF guard) ---

    @Test
    void rejectLinkLocalMetadataHost_shouldRejectImdsAddress() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ConnectionUtils.rejectLinkLocalMetadataHost(URI.create("http://169.254.169.254/latest/meta-data/"))
        );
    }

    @Test
    void rejectLinkLocalMetadataHost_shouldRejectAnyLinkLocalAddress() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ConnectionUtils.rejectLinkLocalMetadataHost(URI.create("https://169.254.0.1"))
        );
    }

    @Test
    void rejectLinkLocalMetadataHost_shouldAllowLoopbackAndPrivateAddresses() {
        assertDoesNotThrow(() -> ConnectionUtils.rejectLinkLocalMetadataHost(URI.create("http://127.0.0.1:9000")));
        assertDoesNotThrow(() -> ConnectionUtils.rejectLinkLocalMetadataHost(URI.create("https://192.168.1.10")));
        assertDoesNotThrow(() -> ConnectionUtils.rejectLinkLocalMetadataHost(URI.create("https://10.0.0.1")));
    }

    @Test
    void rejectLinkLocalMetadataHost_shouldIgnoreUnresolvableHost() {
        // RFC 2606 reserves .invalid so it never resolves; the SDK surfaces the connection error later.
        assertDoesNotThrow(
            () -> ConnectionUtils.rejectLinkLocalMetadataHost(URI.create("https://this-host-does-not-exist.invalid"))
        );
    }

    @Test
    void rejectLinkLocalMetadataHost_shouldIgnoreUriWithoutHost() {
        assertDoesNotThrow(() -> ConnectionUtils.rejectLinkLocalMetadataHost(URI.create("/no/host")));
    }

    // --- configureClient ---

    @Test
    void configureClient_shouldIgnoreBlankEndpointOverride() {
        AbstractConnection.AwsClientConfig config = new AbstractConnection.AwsClientConfig(
            null, null, null, null, null, null, null, Duration.ofSeconds(900), "us-east-1", ""
        );

        assertDoesNotThrow(() -> ConnectionUtils.configureClient(config, StsClient.builder()));
    }
}
