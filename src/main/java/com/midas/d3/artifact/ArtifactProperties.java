package com.midas.d3.artifact;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typed configuration for the durable artifact store.
 *
 * <p>REST-delivered pipeline result ZIPs are written under {@link #dir} and referenced from the
 * database by their <em>file name only</em> (a stable key), never a node-local absolute path. In
 * production {@code MIDAS_ARTIFACT_DIR} is mapped to a mounted volume (see {@code docker-compose.yml}
 * → {@code midas_artifacts}) so archives survive container restarts — a client that reaches
 * {@code COMPLETED} must always be able to download its artifact.
 *
 * <p>Storing a key rather than an absolute path also lets the reader
 * ({@link com.midas.d3.api.PipelineArtifactService}) contain every download to this directory,
 * closing the path-traversal surface on {@code GET /artifacts}.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "midas.artifact")
public class ArtifactProperties {

    /**
     * Directory holding delivered artifact ZIPs. Defaults to a repo-local {@code ./midas-artifacts}
     * for local development; override with {@code MIDAS_ARTIFACT_DIR} to point at a mounted volume.
     */
    private String dir = "./midas-artifacts";
}
