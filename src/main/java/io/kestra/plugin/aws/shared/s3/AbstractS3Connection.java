package io.kestra.plugin.aws.shared.s3;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.aws.shared.AbstractConnection;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * S3-specific connection base: the generic {@link AbstractConnection} kernel plus the two S3-transport
 * concepts ({@code compatibilityMode} and {@code forcePathStyle}). These properties are only meaningful
 * for S3 object storage, so they live here rather than on {@link AbstractConnection} (which every AWS
 * task extends). S3 tasks should extend this class and implement {@link AbstractS3}.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
public abstract class AbstractS3Connection extends AbstractConnection implements AbstractS3 {
    protected Property<Boolean> compatibilityMode;
    protected Property<Boolean> forcePathStyle;
}
