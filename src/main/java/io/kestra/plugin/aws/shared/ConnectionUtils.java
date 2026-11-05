package io.kestra.plugin.aws.shared;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;

import org.apache.commons.lang3.StringUtils;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;

import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.awscore.AwsClient;
import software.amazon.awssdk.awscore.client.builder.AwsAsyncClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleWithWebIdentityCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityRequest;

public class ConnectionUtils {
    /**
     * Factory method for constructing a new {@link AwsCredentialsProvider} for the given AWS Client config.
     *
     * @param awsClientConfig The AwsClientConfig.
     * @return a new {@link AwsCredentialsProvider} instance.
     */
    public static AwsCredentialsProvider credentialsProvider(final AbstractConnection.AwsClientConfig awsClientConfig) {

        if (awsClientConfig.webIdentityTokenSupplier() != null) {
            requireUsableWebIdentity(awsClientConfig);
            var stsClient = stsClientBuilder(awsClientConfig).credentialsProvider(AnonymousCredentialsProvider.create()).build();
            return new StsClientClosingCredentialsProvider(stsAssumeRoleWithWebIdentityCredentialsProvider(awsClientConfig, stsClient), stsClient);
        }

        if (StringUtils.isNotEmpty(awsClientConfig.stsRoleArn())) {
            var stsClient = stsClient(awsClientConfig);
            return new StsClientClosingCredentialsProvider(stsAssumeRoleCredentialsProvider(awsClientConfig, stsClient), stsClient);
        }

        // StaticCredentialsProvider
        if (
            StringUtils.isNotEmpty(awsClientConfig.accessKeyId()) &&
                StringUtils.isNotEmpty(awsClientConfig.secretKeyId())
        ) {
            return staticCredentialsProvider(awsClientConfig);
        }

        // Otherwise, use DefaultCredentialsProvider
        return DefaultCredentialsProvider.builder().build();
    }

    public static StaticCredentialsProvider staticCredentialsProvider(final AbstractConnection.AwsClientConfig awsClientConfig) {
        final AwsCredentials credentials;
        if (StringUtils.isNotEmpty(awsClientConfig.sessionToken())) {
            credentials = AwsSessionCredentials.create(
                awsClientConfig.accessKeyId(),
                awsClientConfig.secretKeyId(),
                awsClientConfig.sessionToken()
            );
        } else {
            credentials = AwsBasicCredentials.create(
                awsClientConfig.accessKeyId(),
                awsClientConfig.secretKeyId()
            );
        }
        return StaticCredentialsProvider.create(credentials);
    }

    // Fail instead of falling back to the worker's own identity when keyless auth is configured but unusable.
    private static void requireUsableWebIdentity(final AbstractConnection.AwsClientConfig awsClientConfig) {
        if (StringUtils.isEmpty(awsClientConfig.stsRoleArn())) {
            throw new IllegalArgumentException("webIdentityToken is set but stsRoleArn is empty: set stsRoleArn to the ARN of the role to assume with the token.");
        }
        if (awsClientConfig.webIdentityToken() != null && awsClientConfig.webIdentityToken().isBlank()) {
            throw new IllegalArgumentException("webIdentityToken is blank: check that the webIdentityToken expression, e.g. `oidcToken(...)`, returns a token on this instance.");
        }
    }

    /**
     * Assumes the role with an OIDC token through an unsigned STS call, re-reading the token on every refresh.
     * The returned provider does not close its {@link StsClient}: prefer {@link #credentialsProvider}.
     */
    public static StsAssumeRoleWithWebIdentityCredentialsProvider stsAssumeRoleWithWebIdentityCredentialsProvider(final AbstractConnection.AwsClientConfig awsClientConfig) {
        return stsAssumeRoleWithWebIdentityCredentialsProvider(
            awsClientConfig,
            stsClientBuilder(awsClientConfig).credentialsProvider(AnonymousCredentialsProvider.create()).build()
        );
    }

    private static StsAssumeRoleWithWebIdentityCredentialsProvider stsAssumeRoleWithWebIdentityCredentialsProvider(
        final AbstractConnection.AwsClientConfig awsClientConfig, final StsClient stsClient) {
        var roleSessionName = roleSessionName(awsClientConfig);

        return StsAssumeRoleWithWebIdentityCredentialsProvider.builder()
            .stsClient(stsClient)
            .refreshRequest(() -> webIdentityRequest(awsClientConfig, roleSessionName))
            .build();
    }

    static AssumeRoleWithWebIdentityRequest webIdentityRequest(final AbstractConnection.AwsClientConfig awsClientConfig, final String roleSessionName) {
        return AssumeRoleWithWebIdentityRequest.builder()
            .roleArn(awsClientConfig.stsRoleArn())
            .roleSessionName(roleSessionName)
            .durationSeconds((int) awsClientConfig.stsRoleSessionDuration().toSeconds())
            .webIdentityToken(awsClientConfig.webIdentityTokenSupplier().get())
            .build();
    }

    /**
     * The returned provider does not close its {@link StsClient}: prefer {@link #credentialsProvider}.
     */
    public static StsAssumeRoleCredentialsProvider stsAssumeRoleCredentialsProvider(final AbstractConnection.AwsClientConfig awsClientConfig) {
        return stsAssumeRoleCredentialsProvider(awsClientConfig, stsClient(awsClientConfig));
    }

    private static StsAssumeRoleCredentialsProvider stsAssumeRoleCredentialsProvider(
        final AbstractConnection.AwsClientConfig awsClientConfig, final StsClient stsClient) {
        var roleSessionName = roleSessionName(awsClientConfig);

        var assumeRoleRequest = AssumeRoleRequest.builder()
            .roleArn(awsClientConfig.stsRoleArn())
            .roleSessionName(roleSessionName)
            .durationSeconds((int) awsClientConfig.stsRoleSessionDuration().toSeconds())
            .externalId(awsClientConfig.stsRoleExternalId())
            .build();

        return StsAssumeRoleCredentialsProvider.builder()
            .stsClient(stsClient)
            .refreshRequest(assumeRoleRequest)
            .build();
    }

    private static String roleSessionName(final AbstractConnection.AwsClientConfig awsClientConfig) {
        var roleSessionName = awsClientConfig.stsRoleSessionName();
        return roleSessionName != null ? roleSessionName : "kestra-plugin-s3-" + System.currentTimeMillis();
    }

    public static StsClient stsClient(final AbstractConnection.AwsClientConfig awsClientConfig) {
        return stsClientBuilder(awsClientConfig).build();
    }

    private static StsClientBuilder stsClientBuilder(final AbstractConnection.AwsClientConfig awsClientConfig) {
        StsClientBuilder builder = StsClient.builder();

        final String stsEndpointOverride = awsClientConfig.stsEndpointOverride();
        if (StringUtils.isNotBlank(stsEndpointOverride)) {
            URI stsEndpointUri = URI.create(stsEndpointOverride.trim());
            rejectLinkLocalMetadataHost(stsEndpointUri);
            builder.applyMutation(stsClientBuilder -> stsClientBuilder.endpointOverride(stsEndpointUri));
        }

        final String regionString = awsClientConfig.region();
        if (regionString != null) {
            builder.applyMutation(stsClientBuilder -> stsClientBuilder.region(Region.of(regionString)));
        }
        return builder;
    }

    /**
     * Configures and returns the given {@link AwsSyncClientBuilder}.
     */
    public static <C extends AwsClient, B extends AwsClientBuilder<B, C> & AwsSyncClientBuilder<B, C>> B configureSyncClient(
        final AbstractConnection.AwsClientConfig clientConfig, final B builder) throws IllegalVariableEvaluationException {

        builder
            // Use the httpClientBuilder to delegate the lifecycle management of the HTTP client to the AWS SDK
            .httpClientBuilder(
                serviceDefaults -> ApacheHttpClient.builder()
                    // 100 balances burst headroom against file-descriptor exhaustion under concurrent executions.
                    .maxConnections(100)
                    .connectionTimeout(Duration.ofSeconds(10))
                    .build()
            );

        return configureClient(clientConfig, builder);
    }

    /**
     * Configures and returns the given {@link AwsAsyncClientBuilder}.
     */
    public static <C extends AwsClient, B extends AwsClientBuilder<B, C> & AwsAsyncClientBuilder<B, C>> B configureAsyncClient(
        final AbstractConnection.AwsClientConfig clientConfig, final B builder) {

        builder
            // Use the httpClientBuilder to delegate the lifecycle management of the HTTP client to the AWS SDK
            .httpClientBuilder(serviceDefaults -> NettyNioAsyncHttpClient.builder().build());
        return configureClient(clientConfig, builder);
    }

    /**
     * Configures and returns the given {@link AwsAsyncClientBuilder}.
     */
    public static <C extends AwsClient, B extends AwsClientBuilder<B, C> & AwsAsyncClientBuilder<B, C>> B configureAsyncClient(
        final int maxConcurrency,
        final Duration connectionAcquisitionTimeout,
        final AbstractConnection.AwsClientConfig clientConfig, final B builder) {

        builder
            // Use the httpClientBuilder to delegate the lifecycle management of the HTTP client to the AWS SDK
            .httpClientBuilder(
                serviceDefaults -> NettyNioAsyncHttpClient
                    .builder()
                    .maxConcurrency(maxConcurrency)
                    .connectionAcquisitionTimeout(connectionAcquisitionTimeout)
                    .build()
            );
        return configureClient(clientConfig, builder);
    }

    /**
     * Configures and returns the given {@link AwsClientBuilder}.
     */
    public static <C extends AwsClient, B extends AwsClientBuilder<B, C>> B configureClient(
        final AbstractConnection.AwsClientConfig clientConfig, final B builder) {

        builder.credentialsProvider(ConnectionUtils.credentialsProvider(clientConfig));

        if (clientConfig.region() != null) {
            builder.region(Region.of(clientConfig.region()));
        }

        if (StringUtils.isNotBlank(clientConfig.endpointOverride())) {
            URI endpointOverride = URI.create(clientConfig.endpointOverride().trim());
            rejectLinkLocalMetadataHost(endpointOverride);
            builder.endpointOverride(endpointOverride);
        }
        return builder;
    }

    /**
     * Rejects endpoint overrides that resolve to the 169.254.0.0/16 link-local range, which hosts
     * cloud provider instance-metadata services (e.g. AWS IMDS at 169.254.169.254). Blocking only this
     * range prevents SSRF-based credential exfiltration while still allowing legitimate self-hosted
     * S3-compatible endpoints on RFC-1918 private addresses (e.g. on-prem MinIO).
     */
    public static void rejectLinkLocalMetadataHost(final URI endpointOverride) {
        String host = endpointOverride.getHost();
        if (host == null) {
            return;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            byte[] octets = address.getAddress();
            if (octets.length == 4 && (octets[0] & 0xFF) == 169 && (octets[1] & 0xFF) == 254) {
                throw new IllegalArgumentException(
                    "Endpoint override '" + endpointOverride + "' resolves to a link-local address (169.254.0.0/16), " +
                        "which is used by cloud instance-metadata services and is not allowed for security reasons."
                );
            }
        } catch (UnknownHostException e) {
            // Host doesn't resolve; let the AWS SDK surface the connection error downstream.
        }
    }
}
