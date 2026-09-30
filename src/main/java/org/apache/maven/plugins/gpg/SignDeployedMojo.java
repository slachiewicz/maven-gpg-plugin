/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.gpg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.Session;
import org.apache.maven.api.annotations.Nullable;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.ArtifactDeployer;
import org.apache.maven.api.services.ArtifactDeployerException;
import org.apache.maven.api.services.ArtifactDeployerRequest;
import org.apache.maven.api.services.ArtifactManager;
import org.apache.maven.api.services.ArtifactResolverException;
import org.codehaus.plexus.util.FileUtils;

/**
 * Resolves given artifacts from a given remote repository, signs them, and deploys the signatures next to signed
 * artifacts, and cleans up afterward. This mojo will use "own" local repository for all the operations to not
 * "pollute" user local repository, and also to be able to fully clean up (delete) after job done.
 *
 * @since 3.2.3
 */
@Mojo(name = "sign-deployed", projectRequired = false)
public class SignDeployedMojo extends AbstractGpgMojo {

    /**
     * URL where the artifacts are deployed.
     */
    @Parameter(property = "url", required = true)
    private String url;

    /**
     * Server ID to map on the &lt;id&gt; under &lt;server&gt; section of <code>settings.xml</code>. In most cases, this
     * parameter will be required for authentication.
     */
    @Parameter(property = "repositoryId", required = true)
    private String repositoryId;

    /**
     * Should generate coordinates "javadoc" sub-artifacts?
     */
    @Parameter(property = "javadoc", defaultValue = "true", required = true)
    private boolean javadoc;

    /**
     * Should generate coordinates "sources" sub-artifacts?
     */
    @Parameter(property = "sources", defaultValue = "true", required = true)
    private boolean sources;

    /**
     * If no {@link ArtifactCollectorSPI} is added, this Mojo will fall back to this parameter to collect GAVs that are
     * deployed and needs signatures deployed next to them. This parameter can contain multiple things:
     * <ul>
     *     <li>A path to an existing file, that contains one GAV spec at a line. File may also contain empty lines or
     *     lines starting with {@code #} that will be ignored.</li>
     *     <li>A comma separated list of GAV specs.</li>
     * </ul>
     * <p>
     * Note: format of GAV entries must be {@code <groupId>:<artifactId>[:<extension>[:<classifier>]]:<version>}.
     */
    @Parameter(property = "artifacts")
    private String artifacts;

    @Inject
    @Nullable
    private Map<String, ArtifactCollectorSPI> artifactCollectors;

    @Override
    protected void doExecute() throws MojoException {
        if (session.getSettings().isOffline()) {
            throw new MojoException("Cannot deploy artifacts when Maven is in offline mode");
        }

        Path tempDirectory = null;
        Map<String, Artifact> artifacts = new LinkedHashMap<>();
        try {
            tempDirectory = Files.createTempDirectory("gpg-sign-deployed");
            getLog().debug("Using temp directory " + tempDirectory);

            // own local repository, to not "pollute" the user one
            Session signingSession = session.withLocalRepository(session.createLocalRepository(tempDirectory));

            // remote repo where deployed artifacts are, and where signatures need to be deployed
            RemoteRepository deploymentRepository = signingSession.createRemoteRepository(repositoryId, url);

            // get artifacts list
            getLog().debug("Collecting artifacts for signing...");
            for (Artifact a : collectArtifacts(signingSession, deploymentRepository)) {
                artifacts.put(a.key(), a);
            }
            getLog().info("Collected " + artifacts.size() + " artifact" + ((artifacts.size() > 1) ? "s" : "")
                    + " for signing");

            // create additional ones if needed
            if (sources || javadoc) {
                getLog().debug("Adding additional artifacts...");
                List<Artifact> additions = new ArrayList<>();
                for (Artifact artifact : artifacts.values()) {
                    if (artifact.getClassifier().isEmpty()) {
                        if (sources) {
                            additions.add(signingSession.createArtifact(
                                    artifact.getGroupId(),
                                    artifact.getArtifactId(),
                                    artifact.getVersion().toString(),
                                    "sources",
                                    "jar",
                                    null));
                        }
                        if (javadoc) {
                            additions.add(signingSession.createArtifact(
                                    artifact.getGroupId(),
                                    artifact.getArtifactId(),
                                    artifact.getVersion().toString(),
                                    "javadoc",
                                    "jar",
                                    null));
                        }
                    }
                }
                additions.forEach(a -> artifacts.put(a.key(), a));
            }

            // resolve them all
            getLog().info("Resolving " + artifacts.size() + " artifact" + ((artifacts.size() > 1) ? "s" : "")
                    + " artifacts for signing...");
            Collection<DownloadedArtifact> resolved = signingSession.resolveArtifacts(
                    artifacts.values().stream()
                            .map(signingSession::createArtifactCoordinates)
                            .collect(Collectors.toList()),
                    List.of(deploymentRepository));

            // sign all
            AbstractGpgSigner signer = newSigner(null);
            signer.setOutputDirectory(tempDirectory.toFile());
            getLog().info("Signer '" + signer.signerName() + "' is signing " + resolved.size() + " file"
                    + ((resolved.size() > 1) ? "s" : "") + " with key " + signer.getKeyInfo());

            ArtifactManager artifactManager = signingSession.getService(ArtifactManager.class);
            List<ProducedArtifact> signatures = new ArrayList<>();
            for (DownloadedArtifact a : resolved) {
                ProducedArtifact signature = signingSession.createProducedArtifact(
                        a.getGroupId(),
                        a.getArtifactId(),
                        a.getVersion().toString(),
                        a.getClassifier(),
                        a.getExtension() + AbstractGpgSigner.SIGNATURE_EXTENSION,
                        null);
                artifactManager.setPath(
                        signature,
                        signer.generateSignatureForArtifact(a.getPath().toFile())
                                .toPath());
                signatures.add(signature);
            }

            // deploy all signature
            getLog().info("Deploying artifact signatures...");
            signingSession
                    .getService(ArtifactDeployer.class)
                    .deploy(ArtifactDeployerRequest.builder()
                            .session(signingSession)
                            .repository(deploymentRepository)
                            .artifacts(signatures)
                            .build());
        } catch (IOException e) {
            throw new MojoException("IO error: " + e.getMessage(), e);
        } catch (ArtifactResolverException e) {
            throw new MojoException(
                    "Error resolving deployed artifacts " + artifacts.keySet() + ": " + e.getMessage(), e);
        } catch (ArtifactDeployerException e) {
            throw new MojoException("Error deploying signatures: " + e.getMessage(), e);
        } finally {
            if (tempDirectory != null) {
                getLog().info("Cleaning up...");
                try {
                    FileUtils.deleteDirectory(tempDirectory.toFile());
                } catch (IOException e) {
                    getLog().warn("Could not clean up temp directory " + tempDirectory);
                }
            }
        }
    }

    /**
     * Returns a collection of remotely deployed artifacts that needs to be signed and have signatures deployed
     * next to them.
     */
    protected Collection<Artifact> collectArtifacts(Session session, RemoteRepository remoteRepository)
            throws IOException {
        Collection<Artifact> result = null;
        if (artifactCollectors != null) {
            for (ArtifactCollectorSPI artifactCollector : artifactCollectors.values()) {
                result = artifactCollector.collectArtifacts(session, remoteRepository);
                if (result != null) {
                    break;
                }
            }
        }
        if (result == null) {
            if (artifacts != null) {
                try {
                    Path path = Paths.get(artifacts);
                    if (Files.isRegularFile(path)) {
                        try (Stream<String> lines = Files.lines(path)) {
                            result = lines.filter(l -> !l.isEmpty() && !l.startsWith("#"))
                                    .map(l -> toArtifact(session, l))
                                    .collect(Collectors.toList());
                        }
                    }
                } catch (InvalidPathException e) {
                    // ignore
                }
                if (result == null) {
                    result = Arrays.stream(artifacts.split(","))
                            .map(l -> toArtifact(session, l))
                            .collect(Collectors.toList());
                }
            }
        }
        if (result == null) {
            throw new IllegalStateException("No source to collect from (set -Dartifacts=g:a:v... or add collector)");
        }
        return result;
    }

    private static Artifact toArtifact(Session session, String spec) {
        org.apache.maven.api.ArtifactCoordinates c = session.createArtifactCoordinates(spec);
        return session.createArtifact(
                c.getGroupId(),
                c.getArtifactId(),
                c.getVersionConstraint().toString(),
                c.getClassifier(),
                c.getExtension(),
                null);
    }
}
