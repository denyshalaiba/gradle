/*
 * Copyright 2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.jvm.toolchain.internal.install;

import org.gradle.api.GradleException;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.authentication.Authentication;
import org.gradle.cache.FileLock;
import org.gradle.internal.deprecation.Documentation;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.CallableBuildOperation;
import org.gradle.internal.resource.ExternalResource;
import org.gradle.internal.resource.ResourceExceptions;
import org.gradle.internal.resource.metadata.ExternalResourceMetaData;
import org.gradle.jvm.toolchain.JavaToolchainDownload;
import org.gradle.jvm.toolchain.JavaToolchainResolver;
import org.gradle.jvm.toolchain.JavaToolchainResolverRegistry;
import org.gradle.jvm.toolchain.JavaToolchainSpec;
import org.gradle.jvm.toolchain.internal.DefaultJavaToolchainRequest;
import org.gradle.jvm.toolchain.internal.JavaToolchainResolverRegistryInternal;
import org.gradle.jvm.toolchain.internal.RealizedJavaToolchainRepository;
import org.gradle.jvm.toolchain.internal.ToolchainDownloadFailedException;
import org.gradle.platform.BuildPlatform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

public class DefaultJavaToolchainProvisioningService implements JavaToolchainProvisioningService {

    public static final String AUTO_DOWNLOAD = "org.gradle.java.installations.auto-download";

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultJavaToolchainProvisioningService.class);

    private static class MissingToolchainException extends GradleException {

        public MissingToolchainException(JavaToolchainSpec spec, URI uri, @Nullable Throwable cause) {
            super("Unable to download toolchain matching the requirements (" + spec.getDisplayName() + ") from '" + uri + "'" + (cause != null ? ", due to: " + cause.getMessage() : "."));
        }

    }

    private static final Object PROVISIONING_PROCESS_LOCK = new Object();

    private final JavaToolchainResolverRegistryInternal toolchainResolverRegistry;
    private final SecureFileDownloader downloader;
    private final JdkCacheDirectory cacheDirProvider;
    private final Provider<Boolean> downloadEnabled;
    private final BuildOperationExecutor buildOperationExecutor;
    private final BuildPlatform buildPlatform;

    @Inject
    public DefaultJavaToolchainProvisioningService(
        JavaToolchainResolverRegistry toolchainResolverRegistry,
        SecureFileDownloader downloader,
        JdkCacheDirectory cacheDirProvider,
        ProviderFactory factory,
        BuildOperationExecutor executor,
        BuildPlatform buildPlatform
    ) {
        this.toolchainResolverRegistry = (JavaToolchainResolverRegistryInternal) toolchainResolverRegistry;
        this.downloader = downloader;
        this.cacheDirProvider = cacheDirProvider;
        this.downloadEnabled = factory.gradleProperty(AUTO_DOWNLOAD).map(Boolean::parseBoolean);
        this.buildOperationExecutor = executor;
        this.buildPlatform = buildPlatform;
    }

    @Override
    public boolean isAutoDownloadEnabled() {
        return downloadEnabled.getOrElse(true);
    }

    @Override
    public boolean hasConfiguredToolchainRepositories() {
        return !toolchainResolverRegistry.requestedRepositories().isEmpty();
    }

    @Override
    public File tryInstall(JavaToolchainSpec spec) {
        if (!isAutoDownloadEnabled()) {
            throw new ToolchainDownloadFailedException("No locally installed toolchains match and toolchain auto-provisioning is not enabled.",
                "Learn more about toolchain auto-detection at " + Documentation.userManual("toolchains", "sec:auto_detection").getUrl() + ".");
        }

        List<? extends RealizedJavaToolchainRepository> repositories = toolchainResolverRegistry.requestedRepositories();
        if (repositories.isEmpty()) {
            throw new ToolchainDownloadFailedException("No locally installed toolchains match and toolchain download repositories have not been configured.",
                "Learn more about toolchain auto-detection at " + Documentation.userManual("toolchains", "sec:auto_detection").getUrl() + ".",
                "Learn more about toolchain repositories at " + Documentation.userManual("toolchains", "sub:download_repositories").getUrl() + ".");
        }

        Map<String, Exception> resolveFailures = new TreeMap<>();
        Map<String, Exception> provisioningFailures = new TreeMap<>();
        File successfulProvisioning = null;
        for (RealizedJavaToolchainRepository repository : repositories) {
            JavaToolchainResolver resolver = repository.getResolver();
            Optional<JavaToolchainDownload> download;
            try {
                download = resolver.resolve(new DefaultJavaToolchainRequest(spec, buildPlatform));
            } catch (Exception e) {
                resolveFailures.put(repository.getRepositoryName(), e);
                continue;
            }
            try {
                if (download.isPresent()) {
                    Collection<Authentication> authentications = repository.getAuthentications(download.get().getUri());
                    successfulProvisioning = provisionInstallation(spec, download.get().getUri(), authentications);
                    break;
                }
            } catch (Exception e) {
                provisioningFailures.put(repository.getRepositoryName(), e);
                // continue
            }
        }

        if (successfulProvisioning == null) {
            String message = "No locally installed toolchains match and the configured toolchain download repositories aren't able to provide a match either." +
                (hasFailures(resolveFailures, provisioningFailures) ? " " + formatFailures(resolveFailures, provisioningFailures) : "");
            throw new ToolchainDownloadFailedException(message,
                "Learn more about toolchain auto-detection at " + Documentation.userManual("toolchains", "sec:auto_detection").getUrl() + ".",
                "Learn more about toolchain repositories at " + Documentation.userManual("toolchains", "sub:download_repositories").getUrl() + ".");
        } else {
            if (hasFailures(resolveFailures, provisioningFailures)) {
                LOGGER.warn(formatFailures(resolveFailures, provisioningFailures));
            }
            return successfulProvisioning;
        }
    }

    private static boolean hasFailures(Map<String, Exception> resolveFailures, Map<String, Exception> provisioningFailures) {
        return !resolveFailures.isEmpty() || !provisioningFailures.isEmpty();
    }

    private static String formatFailures(Map<String, Exception> failedResolutions, Map<String, Exception> provisioningFailures) {
        StringBuilder sb = new StringBuilder();
        if (!failedResolutions.isEmpty()) {
            sb.append("Some toolchain resolvers had internal failures: ")
                .append(formatFailures(failedResolutions))
                .append(".");
        }
        if (!provisioningFailures.isEmpty()) {
            sb.append(failedResolutions.isEmpty() ? "" : " ");
            sb.append("Some toolchain resolvers had provisioning failures: ")
                .append(formatFailures(provisioningFailures))
                .append(".");
        }
        return sb.toString();
    }

    private static String formatFailures(Map<String, Exception> failures) {
        return failures.entrySet().stream().map(e -> e.getKey() + " (" + e.getValue().getMessage() + ")").collect(Collectors.joining(", "));
    }

    private File provisionInstallation(JavaToolchainSpec spec, URI uri, Collection<Authentication> authentications) {
        synchronized (PROVISIONING_PROCESS_LOCK) {
            try {
                File downloadFolder = cacheDirProvider.getDownloadLocation();
                ExternalResource resource = wrapInOperation("Examining toolchain URI " + uri, () -> downloader.getResourceFor(uri, authentications));
                File archiveFile = new File(downloadFolder, getFileName(uri, resource));
                final FileLock fileLock = cacheDirProvider.acquireWriteLock(archiveFile, "Downloading toolchain");
                try {
                    if (!archiveFile.exists()) {
                        wrapInOperation("Downloading toolchain from URI " + uri, () -> {
                            downloader.download(uri, archiveFile, resource);
                            return null;
                        });
                    }
                    return wrapInOperation("Unpacking toolchain archive " + archiveFile.getName(), () -> cacheDirProvider.provisionFromArchive(spec, archiveFile, uri));
                } finally {
                    fileLock.close();
                }
            } catch (Exception e) {
                throw new MissingToolchainException(spec, uri, e);
            }
        }
    }

    private String getFileName(URI uri, ExternalResource resource) {
        ExternalResourceMetaData metaData = resource.getMetaData();
        if (metaData == null) {
            throw ResourceExceptions.getMissing(uri);
        }
        String fileName = metaData.getFilename();
        if (fileName == null) {
            throw new GradleException("Can't determine filename for resource located at: " + uri);
        }
        return fileName;
    }

    private <T> T wrapInOperation(String displayName, Callable<T> provisioningStep) {
        return buildOperationExecutor.call(new ToolchainProvisioningBuildOperation<>(displayName, provisioningStep));
    }

    private static class ToolchainProvisioningBuildOperation<T> implements CallableBuildOperation<T> {
        private final String displayName;
        private final Callable<T> provisioningStep;

        public ToolchainProvisioningBuildOperation(String displayName, Callable<T> provisioningStep) {
            this.displayName = displayName;
            this.provisioningStep = provisioningStep;
        }

        @Override
        public T call(BuildOperationContext context) throws Exception {
            return provisioningStep.call();
        }

        @Override
        public BuildOperationDescriptor.Builder description() {
            return BuildOperationDescriptor
                .displayName(displayName)
                .progressDisplayName(displayName);
        }
    }
}
