package io.kestra.plugin.aws.shared;

import java.time.Duration;
import java.util.function.Supplier;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;

import jakarta.annotation.Nullable;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractConnection extends Task implements AbstractConnectionInterface {

    protected Property<String> region;
    protected Property<String> endpointOverride;

    // Configuration for StaticCredentialsProvider
    @PluginProperty(secret = true, group = "advanced")
    @ToString.Exclude
    protected Property<String> accessKeyId;
    @PluginProperty(secret = true, group = "advanced")
    @ToString.Exclude
    protected Property<String> secretKeyId;
    @PluginProperty(secret = true, group = "advanced")
    @ToString.Exclude
    protected Property<String> sessionToken;

    // Configuration for AWS STS AssumeRole
    protected Property<String> stsRoleArn;
    protected Property<String> stsRoleExternalId;
    protected Property<String> stsRoleSessionName;
    protected Property<String> stsEndpointOverride;
    @Builder.Default
    protected Property<Duration> stsRoleSessionDuration = Property.ofValue(AbstractConnectionInterface.AWS_MIN_STS_ROLE_SESSION_DURATION);

    @PluginProperty(secret = true, group = "advanced")
    @ToString.Exclude
    protected Property<String> webIdentityToken;

    /**
     * Common AWS Client configuration properties.
     */
    public record AwsClientConfig(
        @Nullable String accessKeyId,
        @Nullable String secretKeyId,
        @Nullable String sessionToken,
        @Nullable String stsRoleArn,
        @Nullable String stsRoleExternalId,
        @Nullable String stsRoleSessionName,
        @Nullable String stsEndpointOverride,
        Duration stsRoleSessionDuration,
        @Nullable String region,
        @Nullable String endpointOverride,
        @Nullable String webIdentityToken,
        @Nullable Supplier<String> webIdentityTokenSupplier) {

        // The supplier is asked for a token on every STS call, so a refresh never replays an expired one.
        public AwsClientConfig {
            if (webIdentityTokenSupplier == null && webIdentityToken != null) {
                webIdentityTokenSupplier = () -> webIdentityToken;
            }
        }

        public AwsClientConfig(
            @Nullable String accessKeyId,
            @Nullable String secretKeyId,
            @Nullable String sessionToken,
            @Nullable String stsRoleArn,
            @Nullable String stsRoleExternalId,
            @Nullable String stsRoleSessionName,
            @Nullable String stsEndpointOverride,
            Duration stsRoleSessionDuration,
            @Nullable String region,
            @Nullable String endpointOverride,
            @Nullable String webIdentityToken) {
            this(accessKeyId, secretKeyId, sessionToken, stsRoleArn, stsRoleExternalId, stsRoleSessionName, stsEndpointOverride, stsRoleSessionDuration, region, endpointOverride, webIdentityToken, null);
        }

        /**
         * @deprecated drops the web identity token; to copy a config with a different session duration use {@link #withStsRoleSessionDuration(Duration)}.
         */
        @Deprecated
        public AwsClientConfig(
            @Nullable String accessKeyId,
            @Nullable String secretKeyId,
            @Nullable String sessionToken,
            @Nullable String stsRoleArn,
            @Nullable String stsRoleExternalId,
            @Nullable String stsRoleSessionName,
            @Nullable String stsEndpointOverride,
            Duration stsRoleSessionDuration,
            @Nullable String region,
            @Nullable String endpointOverride) {
            this(accessKeyId, secretKeyId, sessionToken, stsRoleArn, stsRoleExternalId, stsRoleSessionName, stsEndpointOverride, stsRoleSessionDuration, region, endpointOverride, null, null);
        }

        public AwsClientConfig withStsRoleSessionDuration(final Duration stsRoleSessionDuration) {
            return new AwsClientConfig(accessKeyId, secretKeyId, sessionToken, stsRoleArn, stsRoleExternalId, stsRoleSessionName, stsEndpointOverride, stsRoleSessionDuration, region, endpointOverride, webIdentityToken, webIdentityTokenSupplier);
        }
    }
}
