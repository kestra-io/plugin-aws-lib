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
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleWithWebIdentityCredentialsProvider;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
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

    private static AbstractConnection.AwsClientConfig webIdentityConfig(String stsRoleArn, String webIdentityToken) {
        return new AbstractConnection.AwsClientConfig(
            null,
            null,
            null,
            stsRoleArn,
            null,
            null,
            null,
            Duration.ofSeconds(900),
            "us-east-1",
            null,
            webIdentityToken
        );
    }

    // --- credentialsProvider selection ---
    @Test
    void credentialsProvider_shouldUseWebIdentityWhenRoleArnAndTokenAreSet() {
        var provider = ConnectionUtils.credentialsProvider(
            webIdentityConfig("arn:aws:iam::123456789012:role/demo", "eyJhbGciOiJSUzI1NiJ9.e30.sig")
        );
        assertThat(provider, instanceOf(StsAssumeRoleWithWebIdentityCredentialsProvider.class));
    }

    @Test
    void webIdentityRequest_shouldAskTheSupplierForTheTokenOnEveryRequest() {
        // oidcToken() mints a short-lived token: replaying the first render on the SDK's session refresh would fail.
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var config = new AbstractConnection.AwsClientConfig(
            null, null, null, "arn:aws:iam::123456789012:role/demo", null, null, null,
            Duration.ofSeconds(900), "us-east-1", null, "first", () -> "token-" + calls.incrementAndGet()
        );

        var first = ConnectionUtils.webIdentityRequest(config, "session");
        var second = ConnectionUtils.webIdentityRequest(config, "session");

        assertThat(first.webIdentityToken(), is("token-1"));
        assertThat(second.webIdentityToken(), is("token-2"));
        assertThat(first.roleArn(), is("arn:aws:iam::123456789012:role/demo"));
        assertThat(first.durationSeconds(), is(900));
    }

    @Test
    void webIdentityRequest_shouldReuseTheRenderedTokenWithoutSupplier() {
        var request = ConnectionUtils.webIdentityRequest(webIdentityConfig("arn:aws:iam::123456789012:role/demo", "rendered-once"), "session");

        assertThat(request.webIdentityToken(), is("rendered-once"));
    }

    @Test
    void credentialsProvider_shouldIgnoreTokenWithoutRoleArn() {
        var provider = ConnectionUtils.credentialsProvider(webIdentityConfig(null, "eyJhbGciOiJSUzI1NiJ9.e30.sig"));
        assertThat(provider, instanceOf(DefaultCredentialsProvider.class));
    }


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
