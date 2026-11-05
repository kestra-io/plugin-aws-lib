package io.kestra.plugin.aws.shared;

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.utils.SdkAutoCloseable;

/**
 * Closes the {@link StsClient} along with the STS credentials provider, which only closes its session cache.
 */
final class StsClientClosingCredentialsProvider implements AwsCredentialsProvider, SdkAutoCloseable {
    private final AwsCredentialsProvider delegate;
    private final StsClient stsClient;

    StsClientClosingCredentialsProvider(final AwsCredentialsProvider delegate, final StsClient stsClient) {
        this.delegate = delegate;
        this.stsClient = stsClient;
    }

    @Override
    public AwsCredentials resolveCredentials() {
        return delegate.resolveCredentials();
    }

    AwsCredentialsProvider delegate() {
        return delegate;
    }

    @Override
    public void close() {
        if (delegate instanceof SdkAutoCloseable closeable) {
            closeable.close();
        }
        stsClient.close();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
