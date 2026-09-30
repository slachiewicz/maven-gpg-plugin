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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.services.ArtifactManager;
import org.apache.maven.api.services.ProjectManager;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.SelectorUtils;

import static java.util.Objects.requireNonNull;

/**
 * Collects project artifact, the POM, and attached artifacts to be signed.
 *
 * @since 3.1.0
 */
public class FilesCollector {
    private final Session session;

    private final Project project;

    private static final String[] DEFAULT_EXCLUDES = new String[] {
        "**/*.md5", "**/*.sha1", "**/*.sha256", "**/*.sha512", "**/*.asc", "**/.sigstore", "**/*.sigstore.json"
    };

    private final String[] excludes;

    private final Log log;

    public FilesCollector(Session session, Project project, String[] excludes, Log log) {
        this.session = session;
        this.project = project;
        this.log = log;
        if (excludes == null || excludes.length == 0) {
            this.excludes = DEFAULT_EXCLUDES;
            return;
        }
        String[] newExcludes = new String[excludes.length];
        for (int i = 0; i < excludes.length; i++) {
            String pattern;
            pattern = excludes[i].trim().replace('/', File.separatorChar).replace('\\', File.separatorChar);
            if (pattern.endsWith(File.separator)) {
                pattern += "**";
            }
            newExcludes[i] = pattern;
        }
        this.excludes = newExcludes;
    }

    public List<Item> collect() throws MojoException {
        List<Item> items = new ArrayList<>();
        ProjectManager projectManager = session.getService(ProjectManager.class);
        ArtifactManager artifactManager = session.getService(ArtifactManager.class);

        Collection<? extends Artifact> attached = projectManager.getAttachedArtifacts(project);

        if (!"pom".equals(project.getPackaging().id())) {
            // ----------------------------------------------------------------------------
            // Project artifact
            // ----------------------------------------------------------------------------

            Path file = projectManager.getPath(project).orElse(null);

            if (file != null && Files.isRegularFile(file)) {
                items.add(new Item(
                        file.toFile(),
                        null,
                        project.getMainArtifact().map(Artifact::getExtension).orElseGet(() -> extensionOf(file))));
            } else if (attached.isEmpty()) {
                throw new MojoException("The project artifact has not been assembled yet. "
                        + "Please do not invoke this goal before the lifecycle phase \"package\".");
            } else {
                log.debug("Main artifact not assembled, skipping signature generation");
            }
        }

        // ----------------------------------------------------------------------------
        // POM
        // ----------------------------------------------------------------------------

        File pomToSign =
                new File(project.getBuild().getDirectory(), project.getBuild().getFinalName() + ".pom");

        try {
            FileUtils.copyFile(project.getPomPath().toFile(), pomToSign);
        } catch (IOException e) {
            throw new MojoException("Error copying POM for signing.", e);
        }

        items.add(new Item(pomToSign, null, "pom"));

        // ----------------------------------------------------------------------------
        // Attached artifacts
        // ----------------------------------------------------------------------------

        for (Artifact artifact : attached) {
            Path file = artifactManager.getPath(artifact).orElse(null);
            if (file == null) {
                continue;
            }

            if (isExcluded(file)) {
                log.debug("Skipping generation of signature for excluded " + file);
                continue;
            }

            items.add(new Item(file.toFile(), artifact.getClassifier(), artifact.getExtension()));
        }

        return items;
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /**
     * Tests whether a name matches at least one exclude pattern.
     *
     * @param artifactPath the file of the artifact to match. Must not be <code>null</code>.
     * @return <code>true</code> when the name matches at least one exclude pattern, or <code>false</code>
     *         otherwise.
     */
    protected boolean isExcluded(Path artifactPath) {
        final Path projectBasePath = project.getBasedir();
        final String relativeArtifactPath =
                projectBasePath.relativize(artifactPath).toString();

        for (String exclude : excludes) {
            if (SelectorUtils.matchPath(exclude, relativeArtifactPath)) {
                return true;
            }
        }

        return false;
    }

    public static class Item {
        private final File file;
        private final String classifier;
        private final String extension;

        public Item(File file, String classifier, String extension) {
            this.file = requireNonNull(file);
            this.classifier = classifier == null || classifier.trim().isEmpty() ? null : classifier; // nullable
            this.extension = requireNonNull(extension);
        }

        /**
         * The artifact backing file, never {@code null}.
         */
        public File getFile() {
            return file;
        }

        /**
         * The classifier, if present, or {@code null}.
         */
        public String getClassifier() {
            return classifier;
        }

        /**
         * The file extension (without leading period), never {@code null}.
         */
        public String getExtension() {
            return extension;
        }
    }
}
