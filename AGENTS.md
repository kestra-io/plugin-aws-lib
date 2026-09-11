# AGENTS.md

## What

This is a **library**, not an installable Kestra plugin. It produces no plugin jar (no shadow jar,
no `@Plugin` classes, no plugin doc/icons/metadata), and it is not indexed by the Kestra plugin
registry (`generate-shadowjar: false`, `skip-indexing: true` in CI).

- Provides the shared kernel under `io.kestra.plugin.aws.shared`, consumed by both `plugin-aws`
  (OSS) and `plugin-ee-aws` (EE).
- Includes: `AbstractConnection` (incl. nested `AwsClientConfig` record), `AbstractConnectionInterface`,
  `ConnectionUtils`, `s3.AbstractS3`.

## Why

- `plugin-aws` and `plugin-ee-aws` used to each carry their own copy of the AWS connection/auth and
  S3-client code. The copies drifted (see git history around kestra-ee#7113): OSS gained an SSRF
  fix (`ConnectionUtils.rejectLinkLocalMetadataHost`), `forcePathStyle`, HTTP client tuning, and
  secret-masking annotations that EE never received.
- Centralizing this code in one published artifact means both editions build on the same
  connection/auth/S3-client behavior going forward, instead of silently diverging again.

## How

### What belongs in the lib

Only code needed by **both** `plugin-aws` and `plugin-ee-aws`: AWS credential/connection wiring,
STS assume-role support, and the S3 client factory. It is deliberately minimal — this is not a
place to add new tasks, triggers, or service-specific clients.

### What does not belong here

- OSS-only tasks/triggers (athena, dynamodb, kinesis, lambda, sns, sqs, s3 tasks, etc.) — stay in
  `plugin-aws`.
- EE-only code (`batch.Run`, `runner.Batch`, `runner.Ec2`, `runner.Ec2CloudWatchLogTail`,
  `runner.S3StagingUtils`, `cloudwatch.LogExporter`, `s3.LogExporter`) — stays in `plugin-ee-aws`.
- Unit tests for the shared classes — they stay in the consumer repos (`plugin-aws`,
  `plugin-ee-aws`), not in this library.

### Release order

This library must be released (or at least published as a resolvable snapshot) **before** bumping
the version pinned by `plugin-aws` and `plugin-ee-aws`. Both consumers resolve
`io.kestra.plugin:plugin-aws-lib:<version>` from Maven Central (or `mavenLocal()` /
the Sonatype snapshot repo during development).

### Project Structure

```
plugin-aws-lib/
├── src/main/java/io/kestra/plugin/aws/shared/
│   ├── AbstractConnection.java
│   ├── AbstractConnectionInterface.java
│   ├── ConnectionUtils.java
│   └── s3/AbstractS3.java
├── build.gradle
└── README.md
```

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
