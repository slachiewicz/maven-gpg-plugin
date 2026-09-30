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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.Type;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Parent;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.ArtifactDeployer;
import org.apache.maven.api.services.ArtifactDeployerException;
import org.apache.maven.api.services.ArtifactDeployerRequest;
import org.apache.maven.api.services.ArtifactManager;
import org.apache.maven.api.services.TypeRegistry;
import org.apache.maven.api.services.xml.ModelXmlFactory;
import org.apache.maven.api.services.xml.XmlReaderException;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;

/**
 * Signs artifacts and deploys the artifacts and signatures in the remote repository.
 *
 * @author Daniel Kulp
 * @since 1.0-beta-4
 */
@Mojo(name = "sign-and-deploy-file", projectRequired = false)
public class SignAndDeployFileMojo extends AbstractGpgMojo {

    /**
     * The directory where to store signature files.
     */
    @Parameter(property = "gpg.ascDirectory")
    private File ascDirectory;

    /**
     * GroupId of the artifact to be deployed. Retrieved from POM file if specified.
     */
    @Parameter(property = "groupId")
    private String groupId;

    /**
     * ArtifactId of the artifact to be deployed. Retrieved from POM file if specified.
     */
    @Parameter(property = "artifactId")
    private String artifactId;

    /**
     * Version of the artifact to be deployed. Retrieved from POM file if specified.
     */
    @Parameter(property = "version")
    private String version;

    /**
     * Type of the artifact to be deployed. Retrieved from POM file if specified.
     * Defaults to file extension if not specified via command line or POM.
     */
    @Parameter(property = "packaging")
    private String packaging;

    /**
     * Add classifier to the artifact
     */
    @Parameter(property = "classifier")
    private String classifier;

    /**
     * Description passed to a generated POM file (in case of generatePom=true).
     */
    @Parameter(property = "generatePom.description")
    private String description;

    /**
     * File to be deployed.
     */
    @Parameter(property = "file", required = true)
    private File file;

    /**
     * Location of an existing POM file to be deployed alongside the main artifact, given by the ${file} parameter.
     */
    @Parameter(property = "pomFile")
    private File pomFile;

    /**
     * Upload a POM for this artifact. Will generate a default POM if none is supplied with the pomFile argument.
     */
    @Parameter(property = "generatePom", defaultValue = "true")
    private boolean generatePom;

    /**
     * URL where the artifact will be deployed. <br/>
     * ie ( file:///C:/m2-repo or https://host.com/path/to/repo )
     */
    @Parameter(property = "url", required = true)
    private String url;

    /**
     * Server Id to map on the &lt;id&gt; under &lt;server&gt; section of <code>settings.xml</code>. In most cases, this
     * parameter will be required for authentication.
     */
    @Parameter(property = "repositoryId", defaultValue = "remote-repository", required = true)
    private String repositoryId;

    /**
     * The bundled API docs for the artifact.
     *
     * @since 1.3
     */
    @Parameter(property = "javadoc")
    private File javadoc;

    /**
     * The bundled sources for the artifact.
     *
     * @since 1.3
     */
    @Parameter(property = "sources")
    private File sources;

    /**
     * Parameter used to control how many times a failed deployment will be retried before giving up and failing.
     * If a value outside the range 1-10 is specified it will be pulled to the nearest value within the range 1-10.
     *
     * @since 1.3
     */
    @Parameter(property = "retryFailedDeploymentCount", defaultValue = "1")
    private int retryFailedDeploymentCount;

    /**
     * A comma separated list of types for each of the extra side artifacts to deploy. If there is a mismatch in
     * the number of entries in {@link #files} or {@link #classifiers}, then an error will be raised.
     */
    @Parameter(property = "types")
    private String types;

    /**
     * A comma separated list of classifiers for each of the extra side artifacts to deploy. If there is a mismatch in
     * the number of entries in {@link #files} or {@link #types}, then an error will be raised.
     */
    @Parameter(property = "classifiers")
    private String classifiers;

    /**
     * A comma separated list of files for each of the extra side artifacts to deploy. If there is a mismatch in
     * the number of entries in {@link #types} or {@link #classifiers}, then an error will be raised.
     */
    @Parameter(property = "files")
    private String files;

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_\\-.]+");

    private static final Pattern VALID_VERSION = Pattern.compile("[^/\\\\:<>\"|?*\\s]+");

    private void initProperties() throws MojoException {
        // Process the supplied POM (if there is one)
        if (pomFile != null) {
            generatePom = false;

            Model model = readModel(pomFile);

            processModel(model);
        }

        if (packaging == null && file != null) {
            packaging = FileUtils.getExtension(file.getName());
        }
    }

    @Override
    @SuppressWarnings("checkstyle:MethodLength")
    protected void doExecute() throws MojoException {
        if (session.getSettings().isOffline()) {
            throw new MojoException("Cannot deploy artifacts when Maven is in offline mode");
        }

        initProperties();

        validateArtifactInformation();

        if (!file.exists()) {
            throw new MojoException(file.getPath() + " not found.");
        }

        // create artifacts
        ArtifactManager artifactManager = session.getService(ArtifactManager.class);
        List<ProducedArtifact> artifacts = new ArrayList<>();

        // main artifact
        Optional<Type> handler = session.getService(TypeRegistry.class).lookup(packaging);
        String mainClassifier = classifier == null || classifier.trim().isEmpty()
                ? handler.map(Type::getClassifier).orElse(null)
                : classifier;
        ProducedArtifact main = session.createProducedArtifact(
                groupId,
                artifactId,
                version,
                mainClassifier,
                handler.map(Type::getExtension).orElse(packaging),
                null);

        File localRepoFile = session.getPathForLocalArtifact(main).toFile();
        if (file.equals(localRepoFile)) {
            throw new MojoException("Cannot deploy artifact from the local repository: " + file);
        }
        artifactManager.setPath(main, file.toPath());
        artifacts.add(main);

        if (!"pom".equals(packaging)) {
            if (pomFile == null && generatePom) {
                pomFile = generatePomFile();
            }
            if (pomFile != null) {
                ProducedArtifact pom = session.createProducedArtifact(groupId, artifactId, version, null, "pom", null);
                artifactManager.setPath(pom, pomFile.toPath());
                artifacts.add(pom);
            }
        }

        if (sources != null) {
            ProducedArtifact a = session.createProducedArtifact(groupId, artifactId, version, "sources", "jar", null);
            artifactManager.setPath(a, sources.toPath());
            artifacts.add(a);
        }

        if (javadoc != null) {
            ProducedArtifact a = session.createProducedArtifact(groupId, artifactId, version, "javadoc", "jar", null);
            artifactManager.setPath(a, javadoc.toPath());
            artifacts.add(a);
        }

        if (files != null) {
            if (types == null) {
                throw new MojoException("You must specify 'types' if you specify 'files'");
            }
            if (classifiers == null) {
                throw new MojoException("You must specify 'classifiers' if you specify 'files'");
            }
            String[] files = this.files.split(",", -1);
            String[] types = this.types.split(",", -1);
            String[] classifiers = this.classifiers.split(",", -1);
            if (types.length != files.length) {
                throw new MojoException("You must specify the same number of entries in 'files' and "
                        + "'types' (respectively " + files.length + " and " + types.length + " entries )");
            }
            if (classifiers.length != files.length) {
                throw new MojoException("You must specify the same number of entries in 'files' and "
                        + "'classifiers' (respectively " + files.length + " and " + classifiers.length + " entries )");
            }
            for (int i = 0; i < files.length; i++) {
                File file = new File(files[i]);
                if (!file.isFile()) {
                    // try relative to the current directory just in case
                    file = new File("")
                            .getAbsoluteFile()
                            .toPath()
                            .resolve(files[i])
                            .toFile();
                }
                if (file.isFile()) {
                    String ext = session.getService(TypeRegistry.class)
                            .lookup(types[i])
                            .map(Type::getExtension)
                            .orElse(types[i]);
                    ProducedArtifact artifact = session.createProducedArtifact(
                            groupId,
                            artifactId,
                            version,
                            StringUtils.isWhitespace(classifiers[i]) ? null : classifiers[i],
                            ext,
                            null);
                    artifactManager.setPath(artifact, file.toPath());
                    artifacts.add(artifact);
                } else {
                    throw new MojoException("Specified side artifact " + file + " does not exist");
                }
            }
        } else {
            if (types != null) {
                throw new MojoException("You must specify 'files' if you specify 'types'");
            }
            if (classifiers != null) {
                throw new MojoException("You must specify 'files' if you specify 'classifiers'");
            }
        }

        // sign all
        AbstractGpgSigner signer = newSigner(null);
        signer.setOutputDirectory(ascDirectory);
        signer.setBaseDirectory(new File("").getAbsoluteFile());

        getLog().info("Signer '" + signer.signerName() + "' is signing " + artifacts.size() + " file"
                + ((artifacts.size() > 1) ? "s" : "") + " with key " + signer.getKeyInfo());

        List<ProducedArtifact> signatures = new ArrayList<>();
        for (ProducedArtifact a : artifacts) {
            ProducedArtifact signature = session.createProducedArtifact(
                    a.getGroupId(),
                    a.getArtifactId(),
                    a.getVersion().toString(),
                    a.getClassifier(),
                    a.getExtension() + AbstractGpgSigner.SIGNATURE_EXTENSION,
                    null);
            artifactManager.setPath(
                    signature,
                    signer.generateSignatureForArtifact(
                                    artifactManager.getPath(a).orElseThrow().toFile())
                            .toPath());
            signatures.add(signature);
        }
        artifacts.addAll(signatures);

        // deploy all
        RemoteRepository deploymentRepository = session.createRemoteRepository(repositoryId, url);
        try {
            session.getService(ArtifactDeployer.class)
                    .deploy(ArtifactDeployerRequest.builder()
                            .session(session)
                            .repository(deploymentRepository)
                            .artifacts(artifacts)
                            .retryFailedDeploymentCount(Math.max(1, Math.min(10, this.retryFailedDeploymentCount)))
                            .build());
        } catch (ArtifactDeployerException e) {
            throw new MojoException("Error deploying attached artifacts " + artifacts + ": " + e.getMessage(), e);
        }
    }

    /**
     * Process the supplied pomFile to get groupId, artifactId, version, and packaging
     *
     * @param model The POM to extract missing artifact coordinates from, must not be <code>null</code>.
     */
    private void processModel(Model model) {
        Parent parent = model.getParent();

        if (this.groupId == null) {
            this.groupId = model.getGroupId();
            if (this.groupId == null && parent != null) {
                this.groupId = parent.getGroupId();
            }
        }
        if (this.artifactId == null) {
            this.artifactId = model.getArtifactId();
        }
        if (this.version == null) {
            this.version = model.getVersion();
            if (this.version == null && parent != null) {
                this.version = parent.getVersion();
            }
        }
        if (this.packaging == null) {
            this.packaging = model.getPackaging();
        }
    }

    /**
     * Extract the model from the specified POM file.
     *
     * @param pomFile The path of the POM file to parse, must not be <code>null</code>.
     * @return The model from the POM file, never <code>null</code>.
     * @throws MojoException If the file doesn't exist of cannot be read.
     */
    private Model readModel(File pomFile) throws MojoException {
        try (InputStream inputStream = Files.newInputStream(pomFile.toPath())) {
            return session.getService(ModelXmlFactory.class).read(inputStream);
        } catch (IOException e) {
            throw new MojoException("Error reading POM " + pomFile, e);
        } catch (XmlReaderException e) {
            throw new MojoException("Error parsing POM " + pomFile, e);
        }
    }

    /**
     * Generates a minimal POM from the user-supplied artifact information.
     *
     * @return The path to the generated POM file, never <code>null</code>.
     * @throws MojoException If the generation failed.
     */
    private File generatePomFile() throws MojoException {
        Model model = generateModel();

        try {
            File tempFile = Files.createTempFile("mvndeploy", ".pom").toFile();
            tempFile.deleteOnExit();

            try (Writer writer = Files.newBufferedWriter(tempFile.toPath())) {
                session.getService(ModelXmlFactory.class).write(model, writer);
            }

            return tempFile;
        } catch (IOException e) {
            throw new MojoException("Error writing temporary pom file: " + e.getMessage(), e);
        }
    }

    /**
     * Validates the user-supplied artifact information.
     *
     * @throws MojoException If any artifact coordinate is invalid.
     */
    private void validateArtifactInformation() throws MojoException {
        List<String> result = new ArrayList<>();

        if (groupId == null || !VALID_ID.matcher(groupId).matches()) {
            result.add("'groupId' is missing or uses invalid characters: " + groupId);
        }
        if (artifactId == null || !VALID_ID.matcher(artifactId).matches()) {
            result.add("'artifactId' is missing or uses invalid characters: " + artifactId);
        }
        if (version == null || !VALID_VERSION.matcher(version).matches()) {
            result.add("'version' is missing or uses invalid characters: " + version);
        }
        if (packaging == null || packaging.trim().isEmpty()) {
            result.add("'packaging' is missing");
        }

        if (!result.isEmpty()) {
            StringBuilder msg = new StringBuilder("The artifact information is incomplete or not valid:\n");
            for (String e : result) {
                msg.append(" - " + e + '\n');
            }
            throw new MojoException(msg.toString());
        }
    }

    /**
     * Generates a minimal model from the user-supplied artifact information.
     *
     * @return The generated model, never <code>null</code>.
     */
    private Model generateModel() {
        return Model.newBuilder()
                .modelVersion("4.0.0")
                .groupId(groupId)
                .artifactId(artifactId)
                .version(version)
                .packaging(packaging)
                .description(description)
                .build();
    }
}
