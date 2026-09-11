package io.kestra.plugin.aws.shared.s3;

import java.net.URI;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.aws.shared.AbstractConnection;
import io.kestra.plugin.aws.shared.AbstractConnectionInterface;
import io.kestra.plugin.aws.shared.ConnectionUtils;

import io.swagger.v3.oas.annotations.media.Schema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3CrtAsyncClientBuilder;

public interface AbstractS3 extends AbstractConnectionInterface {
    @Schema(
        title = "Enable compatibility mode",
        description = "Use it to connect to S3 bucket with S3 compatible services that don't support the new transport client."
    )
    default Property<Boolean> getCompatibilityMode() {
        return Property.ofValue(false);
    }

    @Schema(
        title = "Force path style access",
        description = "Must only be used when `compatibilityMode` is enabled."
    )
    default Property<Boolean> getForcePathStyle() {
        return Property.ofValue(false);
    }

    default S3Client client(final RunContext runContext) throws IllegalVariableEvaluationException {
        final AbstractConnection.AwsClientConfig clientConfig = awsClientConfig(runContext);
        return ConnectionUtils.configureSyncClient(clientConfig, S3Client.builder())
            .forcePathStyle(runContext.render(this.getForcePathStyle()).as(Boolean.class).orElse(false))
            .build();
    }

    default S3AsyncClient asyncClient(final RunContext runContext) throws IllegalVariableEvaluationException {
        final AbstractConnection.AwsClientConfig clientConfig = awsClientConfig(runContext);
        if (runContext.render(this.getCompatibilityMode()).as(Boolean.class).orElse(false)) {
            return ConnectionUtils.configureAsyncClient(clientConfig, S3AsyncClient.builder())
                .forcePathStyle(runContext.render(this.getForcePathStyle()).as(Boolean.class).orElse(false))
                .build();
        } else {
            if (runContext.render(this.getForcePathStyle()).as(Boolean.class).orElse(false)) {
                throw new IllegalArgumentException("'forcePathStyle' must be used in conjunction with 'compatibilityMode'");
            }

            S3CrtAsyncClientBuilder s3ClientBuilder = S3AsyncClient.crtBuilder()
                .credentialsProvider(ConnectionUtils.credentialsProvider(clientConfig));

            if (clientConfig.region() != null) {
                s3ClientBuilder.region(Region.of(clientConfig.region()));
            }
            if (clientConfig.endpointOverride() != null) {
                URI endpointOverride = URI.create(clientConfig.endpointOverride());
                ConnectionUtils.rejectLinkLocalMetadataHost(endpointOverride);
                s3ClientBuilder.endpointOverride(endpointOverride);
            }
            return s3ClientBuilder.build();
        }

    }
}
