package com.diamondq.maven;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugins.dependency.utils.DependencySilentLog;
import org.apache.maven.plugins.dependency.utils.DependencyStatusSets;
import org.apache.maven.plugins.dependency.utils.DependencyUtil;
import org.apache.maven.plugins.dependency.utils.filters.ResolveFileFilter;
import org.apache.maven.plugins.dependency.utils.markers.SourcesFileMarkerHandler;
import org.apache.maven.plugins.dependency.utils.translators.ArtifactTranslator;
import org.apache.maven.plugins.dependency.utils.translators.ClassifierTypeTranslator;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.artifact.ArtifactCoordinate;
import org.apache.maven.shared.artifact.filter.collection.ArtifactFilterException;
import org.apache.maven.shared.artifact.filter.collection.ArtifactIdFilter;
import org.apache.maven.shared.artifact.filter.collection.ArtifactsFilter;
import org.apache.maven.shared.artifact.filter.collection.ClassifierFilter;
import org.apache.maven.shared.artifact.filter.collection.FilterArtifacts;
import org.apache.maven.shared.artifact.filter.collection.GroupIdFilter;
import org.apache.maven.shared.artifact.filter.collection.ProjectTransitivityFilter;
import org.apache.maven.shared.artifact.filter.collection.ScopeFilter;
import org.apache.maven.shared.artifact.filter.collection.TypeFilter;
import org.apache.maven.shared.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.artifact.resolve.ArtifactResult;
import org.apache.maven.shared.dependencies.DependableCoordinate;
import org.apache.maven.shared.dependencies.resolve.DependencyResolver;
import org.apache.maven.shared.dependencies.resolve.DependencyResolverException;
import org.apache.maven.shared.repository.RepositoryManager;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
import org.codehaus.plexus.archiver.zip.ZipUnArchiver;
import org.codehaus.plexus.components.io.fileselectors.IncludeExcludeFileSelector;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.ReflectionUtils;
import org.codehaus.plexus.util.StringUtils;

/**
 * Goal builds an PDE target file from dependencies
 */
@Mojo(name = "build-target", requiresDependencyResolution = ResolutionScope.TEST,
	defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class BuildTarget extends AbstractMojo {

	/**
	 * To look up Archiver/UnArchiver implementations
	 */
	@Component
	private ArchiverManager				archiverManager;

	/**
	 * <p>
	 * will use the jvm chmod, this is available for user and all level group level will be ignored
	 * </p>
	 * <b>since 2.6 is on by default</b>
	 * 
	 * @since 2.5.1
	 */
	@Parameter(property = "dependency.useJvmChmod", defaultValue = "true")
	private boolean						useJvmChmod	= true;

	/**
	 * ignore to set file permissions when unpacking a dependency
	 * 
	 * @since 2.7
	 */
	@Parameter(property = "dependency.ignorePermissions", defaultValue = "false")
	private boolean						ignorePermissions;

	/**
	 * POM
	 */
	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private MavenProject				project;

	/**
	 * Remote repositories which will be searched for artifacts.
	 */
	@Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true, required = true)
	private List<ArtifactRepository>	remoteRepositories;

	/**
	 * Contains the full list of projects in the reactor.
	 */
	@Parameter(defaultValue = "${reactorProjects}", readonly = true)
	protected List<MavenProject>		reactorProjects;

	/**
	 * The Maven session
	 */
	@Parameter(defaultValue = "${session}", readonly = true, required = true)
	protected MavenSession				session;

	/**
	 * If the plugin should be silent.
	 *
	 * @since 2.0
	 */
	@Parameter(property = "silent", defaultValue = "false")
	private boolean						silent;

	/**
	 * Output absolute filename for resolved artifacts
	 *
	 * @since 2.0
	 */
	@Parameter(property = "outputAbsoluteArtifactFilename", defaultValue = "false")
	protected boolean					outputAbsoluteArtifactFilename;

	/**
	 * Skip plugin execution completely.
	 *
	 * @since 2.7
	 */
	@Parameter(property = "mdep.skip", defaultValue = "false")
	private boolean						skip;

	@Parameter(property = "targetName", defaultValue = "Maven Generated Target")
	protected String					targetName;

	// Mojo methods -----------------------------------------------------------

	/*
	 * @see org.apache.maven.plugin.Mojo#execute()
	 */
	@Override
	public final void execute() throws MojoExecutionException, MojoFailureException {
		if (isSkip()) {
			getLog().info("Skipping plugin execution");
			return;
		}

		doExecute();
	}

	/**
	 * @return Returns the archiverManager.
	 */
	public ArchiverManager getArchiverManager() {
		return this.archiverManager;
	}

	/**
	 * Does the actual copy of the file and logging.
	 *
	 * @param artifact represents the file to copy.
	 * @param destFile file name of destination file.
	 * @throws MojoExecutionException with a message if an error occurs.
	 */
	protected void copyFile(File artifact, File destFile) throws MojoExecutionException {
		try {
			getLog().info(
				"Copying " + (this.outputAbsoluteArtifactFilename ? artifact.getAbsolutePath() : artifact.getName())
					+ " to " + destFile);

			if (artifact.isDirectory()) {
				// usual case is a future jar packaging, but there are special cases: classifier and other packaging
				throw new MojoExecutionException("Artifact has not been packaged yet. When used on reactor artifact, "
					+ "copy should be executed after packaging: see MDEP-187.");
			}

			FileUtils.copyFile(artifact, destFile);
		}
		catch (IOException e) {
			throw new MojoExecutionException("Error copying artifact from " + artifact + " to " + destFile, e);
		}
	}

	/**
	 * @param artifact {@link Artifact}
	 * @param location The location.
	 * @param encoding The encoding.
	 * @throws MojoExecutionException in case of an error.
	 */
	protected void unpack(Artifact artifact, File location, String encoding) throws MojoExecutionException {
		unpack(artifact, location, null, null, encoding);
	}

	/**
	 * Unpacks the archive file.
	 *
	 * @param artifact File to be unpacked.
	 * @param location Location where to put the unpacked files.
	 * @param includes Comma separated list of file patterns to include i.e. <code>**&#47;.xml,
	 *                 **&#47;*.properties</code>
	 * @param excludes Comma separated list of file patterns to exclude i.e. <code>**&#47;*.xml,
	 *                 **&#47;*.properties</code>
	 * @param encoding Encoding of artifact. Set {@code null} for default encoding.
	 * @throws MojoExecutionException In case of errors.
	 */
	protected void unpack(Artifact artifact, File location, String includes, String excludes, String encoding)
		throws MojoExecutionException {
		unpack(artifact, ((ClassifierTypeTranslator) artifact).getType(), location, includes, excludes, encoding);
	}

	/**
	 * @param artifact {@link Artifact}
	 * @param type The type.
	 * @param location The location.
	 * @param includes includes list.
	 * @param excludes excludes list.
	 * @param encoding the encoding.
	 * @throws MojoExecutionException in case of an error.
	 */
	protected void unpack(Artifact artifact, String type, File location, String includes, String excludes,
		String encoding) throws MojoExecutionException {
		File file = artifact.getFile();
		try {
			logUnpack(file, location, includes, excludes);

			location.mkdirs();
			if (!location.exists()) {
				throw new MojoExecutionException(
					"Location to write unpacked files to could not be created: " + location);
			}

			if (file.isDirectory()) {
				// usual case is a future jar packaging, but there are special cases: classifier and other packaging
				throw new MojoExecutionException("Artifact has not been packaged yet. When used on reactor artifact, "
					+ "unpack should be executed after packaging: see MDEP-98.");
			}

			UnArchiver unArchiver;

			try {
				unArchiver = archiverManager.getUnArchiver(type);
				getLog().debug("Found unArchiver by type: " + unArchiver);
			}
			catch (NoSuchArchiverException e) {
				unArchiver = archiverManager.getUnArchiver(file);
				getLog().debug("Found unArchiver by extension: " + unArchiver);
			}

			if (encoding != null && unArchiver instanceof ZipUnArchiver) {
				((ZipUnArchiver) unArchiver).setEncoding(encoding);
				getLog().info("Unpacks '" + type + "' with encoding '" + encoding + "'.");
			}

			unArchiver.setUseJvmChmod(useJvmChmod);

			unArchiver.setIgnorePermissions(ignorePermissions);

			unArchiver.setSourceFile(file);

			unArchiver.setDestDirectory(location);

			if (StringUtils.isNotEmpty(excludes) || StringUtils.isNotEmpty(includes)) {
				// Create the selectors that will filter
				// based on include/exclude parameters
				// MDEP-47
				IncludeExcludeFileSelector[] selectors =
					new IncludeExcludeFileSelector[] {new IncludeExcludeFileSelector()};

				if (StringUtils.isNotEmpty(excludes)) {
					selectors[0].setExcludes(excludes.split(","));
				}

				if (StringUtils.isNotEmpty(includes)) {
					selectors[0].setIncludes(includes.split(","));
				}

				unArchiver.setFileSelectors(selectors);
			}
			if (this.silent) {
				silenceUnarchiver(unArchiver);
			}

			unArchiver.extract();
		}
		catch (NoSuchArchiverException e) {
			throw new MojoExecutionException("Unknown archiver type", e);
		}
		catch (ArchiverException e) {
			throw new MojoExecutionException(
				"Error unpacking file: " + file + " to: " + location + "\r\n" + e.toString(), e);
		}
	}

	private void silenceUnarchiver(UnArchiver unArchiver) {
		// dangerous but handle any errors. It's the only way to silence the unArchiver.
		try {
			Field field = ReflectionUtils.getFieldByNameIncludingSuperclasses("logger", unArchiver.getClass());

			field.setAccessible(true);

			field.set(unArchiver, this.getLog());
		}
		catch (Exception e) {
			// was a nice try. Don't bother logging because the log is silent.
		}
	}

	/**
	 * @return Returns a new ProjectBuildingRequest populated from the current session and the current project remote
	 *         repositories, used to resolve artifacts.
	 */
	public ProjectBuildingRequest newResolveArtifactProjectBuildingRequest() {
		ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());

		buildingRequest.setRemoteRepositories(remoteRepositories);

		return buildingRequest;
	}

	/**
	 * @return Returns the project.
	 */
	public MavenProject getProject() {
		return this.project;
	}

	/**
	 * @param archiverManager The archiverManager to set.
	 */
	public void setArchiverManager(ArchiverManager archiverManager) {
		this.archiverManager = archiverManager;
	}

	/**
	 * @return {@link #useJvmChmod}
	 */
	public boolean isUseJvmChmod() {
		return useJvmChmod;
	}

	/**
	 * @param useJvmChmod {@link #useJvmChmod}
	 */
	public void setUseJvmChmod(boolean useJvmChmod) {
		this.useJvmChmod = useJvmChmod;
	}

	/**
	 * @return {@link #skip}
	 */
	public boolean isSkip() {
		return skip;
	}

	/**
	 * @param skip {@link #skip}
	 */
	public void setSkip(boolean skip) {
		this.skip = skip;
	}

	/**
	 * @return {@link #silent}
	 */
	protected final boolean isSilent() {
		return silent;
	}

	/**
	 * @param silent {@link #silent}
	 */
	public void setSilent(boolean silent) {
		this.silent = silent;
		if (silent) {
			setLog(new DependencySilentLog());
		}
	}

	private void logUnpack(File file, File location, String includes, String excludes) {
		if (!getLog().isInfoEnabled()) {
			return;
		}

		StringBuilder msg = new StringBuilder();
		msg.append("Unpacking ");
		msg.append(file);
		msg.append(" to ");
		msg.append(location);

		if (includes != null && excludes != null) {
			msg.append(" with includes \"");
			msg.append(includes);
			msg.append("\" and excludes \"");
			msg.append(excludes);
			msg.append("\"");
		}
		else if (includes != null) {
			msg.append(" with includes \"");
			msg.append(includes);
			msg.append("\"");
		}
		else if (excludes != null) {
			msg.append(" with excludes \"");
			msg.append(excludes);
			msg.append("\"");
		}

		getLog().info(msg.toString());
	}

	@Component
	private ArtifactResolver		artifactResolver;

	@Component
	private DependencyResolver		dependencyResolver;

	@Component
	private RepositoryManager		repositoryManager;

	/**
	 * Overwrite release artifacts
	 *
	 * @since 1.0
	 */
	@Parameter(property = "overWriteReleases", defaultValue = "false")
	protected boolean				overWriteReleases;

	/**
	 * Overwrite snapshot artifacts
	 *
	 * @since 1.0
	 */
	@Parameter(property = "overWriteSnapshots", defaultValue = "false")
	protected boolean				overWriteSnapshots;

	/**
	 * Overwrite artifacts that don't exist or are older than the source.
	 *
	 * @since 2.0
	 */
	@Parameter(property = "overWriteIfNewer", defaultValue = "true")
	protected boolean				overWriteIfNewer;

	/**
	 * If we should exclude transitive dependencies
	 *
	 * @since 2.0
	 */
	@Parameter(property = "excludeTransitive", defaultValue = "false")
	protected boolean				excludeTransitive;

	/**
	 * Comma Separated list of Types to include. Empty String indicates include everything (default).
	 *
	 * @since 2.0
	 */
	@Parameter(property = "includeTypes", defaultValue = "")
	protected String				includeTypes;

	/**
	 * Comma Separated list of Types to exclude. Empty String indicates don't exclude anything (default).
	 *
	 * @since 2.0
	 */
	@Parameter(property = "excludeTypes", defaultValue = "")
	protected String				excludeTypes;

	/**
	 * Scope to include. An Empty string indicates all scopes (default). The scopes being interpreted are the scopes as
	 * Maven sees them, not as specified in the pom. In summary:
	 * <ul>
	 * <li><code>runtime</code> scope gives runtime and compile dependencies,</li>
	 * <li><code>compile</code> scope gives compile, provided, and system dependencies,</li>
	 * <li><code>test</code> (default) scope gives all dependencies,</li>
	 * <li><code>provided</code> scope just gives provided dependencies,</li>
	 * <li><code>system</code> scope just gives system dependencies.</li>
	 * </ul>
	 *
	 * @since 2.0
	 */
	@Parameter(property = "includeScope", defaultValue = "")
	protected String				includeScope;

	/**
	 * Scope to exclude. An Empty string indicates no scopes (default).
	 *
	 * @since 2.0
	 */
	@Parameter(property = "excludeScope", defaultValue = "")
	protected String				excludeScope;

	/**
	 * Comma Separated list of Classifiers to include. Empty String indicates include everything (default).
	 *
	 * @since 2.0
	 */
	@Parameter(property = "includeClassifiers", defaultValue = "")
	protected String				includeClassifiers;

	/**
	 * Comma Separated list of Classifiers to exclude. Empty String indicates don't exclude anything (default).
	 *
	 * @since 2.0
	 */
	@Parameter(property = "excludeClassifiers", defaultValue = "")
	protected String				excludeClassifiers;

	/**
	 * Specify classifier to look for. Example: sources
	 *
	 * @since 2.0
	 */
	@Parameter(property = "classifier", defaultValue = "")
	protected String				classifier;

	/**
	 * Specify type to look for when constructing artifact based on classifier. Example: java-source,jar,war
	 *
	 * @since 2.0
	 */
	@Parameter(property = "type", defaultValue = "")
	protected String				type;

	/**
	 * Comma separated list of Artifact names to exclude.
	 *
	 * @since 2.0
	 */
	@Parameter(property = "excludeArtifactIds", defaultValue = "")
	protected String				excludeArtifactIds;

	/**
	 * Comma separated list of Artifact names to include. Empty String indicates include everything (default).
	 *
	 * @since 2.0
	 */
	@Parameter(property = "includeArtifactIds", defaultValue = "")
	protected String				includeArtifactIds;

	/**
	 * Comma separated list of GroupId Names to exclude.
	 *
	 * @since 2.0
	 */
	@Parameter(property = "excludeGroupIds", defaultValue = "")
	protected String				excludeGroupIds;

	/**
	 * Comma separated list of GroupIds to include. Empty String indicates include everything (default).
	 *
	 * @since 2.0
	 */
	@Parameter(property = "includeGroupIds", defaultValue = "")
	protected String				includeGroupIds;

	/**
	 * Directory to store flag files
	 *
	 * @since 2.0
	 */
	// CHECKSTYLE_OFF: LineLength
	@Parameter(property = "markersDirectory",
		defaultValue = "${project.build.directory}/dependency-maven-plugin-markers")
	// CHECKSTYLE_ON: LineLength
	protected File					markersDirectory;

	/**
	 * Prepend the groupId during copy.
	 *
	 * @since 2.2
	 */
	@Parameter(property = "mdep.prependGroupId", defaultValue = "false")
	protected boolean				prependGroupId	= false;

	@Component
	private ProjectBuilder			projectBuilder;

	@Component
	private ArtifactHandlerManager	artifactHandlerManager;

	/**
	 * Retrieves dependencies, either direct only or all including transitive.
	 *
	 * @param stopOnFailure true to fail if resolution does not work or false not to fail.
	 * @return A set of artifacts
	 * @throws MojoExecutionException in case of errors.
	 */
	protected Set<Artifact> getResolvedDependencies(boolean stopOnFailure) throws MojoExecutionException

	{
		DependencyStatusSets status = getDependencySets(stopOnFailure);

		return status.getResolvedDependencies();
	}

	/**
	 * @param stopOnFailure true/false.
	 * @return {@link DependencyStatusSets}
	 * @throws MojoExecutionException in case of an error.
	 */
	protected DependencyStatusSets getDependencySets(boolean stopOnFailure) throws MojoExecutionException {
		return getDependencySets(stopOnFailure, false);
	}

	/**
	 * Method creates filters and filters the projects dependencies. This method also transforms the dependencies if
	 * classifier is set. The dependencies are filtered in least specific to most specific order
	 *
	 * @param stopOnFailure true to fail if artifacts can't be resolved false otherwise.
	 * @param includeParents <code>true</code> if parents should be included or not <code>false</code>.
	 * @return DependencyStatusSets - Bean of TreeSets that contains information on the projects dependencies
	 * @throws MojoExecutionException in case of errors.
	 */
	protected DependencyStatusSets getDependencySets(boolean stopOnFailure, boolean includeParents)
		throws MojoExecutionException {
		// add filters in well known order, least specific to most specific
		FilterArtifacts filter = new FilterArtifacts();

		filter.addFilter(new ProjectTransitivityFilter(getProject().getDependencyArtifacts(), this.excludeTransitive));

		filter.addFilter(new ScopeFilter(DependencyUtil.cleanToBeTokenizedString(this.includeScope),
			DependencyUtil.cleanToBeTokenizedString(this.excludeScope)));

		filter.addFilter(new TypeFilter(DependencyUtil.cleanToBeTokenizedString(this.includeTypes),
			DependencyUtil.cleanToBeTokenizedString(this.excludeTypes)));

		filter.addFilter(new ClassifierFilter(DependencyUtil.cleanToBeTokenizedString(this.includeClassifiers),
			DependencyUtil.cleanToBeTokenizedString(this.excludeClassifiers)));

		filter.addFilter(new GroupIdFilter(DependencyUtil.cleanToBeTokenizedString(this.includeGroupIds),
			DependencyUtil.cleanToBeTokenizedString(this.excludeGroupIds)));

		filter.addFilter(new ArtifactIdFilter(DependencyUtil.cleanToBeTokenizedString(this.includeArtifactIds),
			DependencyUtil.cleanToBeTokenizedString(this.excludeArtifactIds)));

		// start with all artifacts.
		Set<Artifact> artifacts = getProject().getArtifacts();

		if (includeParents) {
			// add dependencies parents
			for (Artifact dep : new ArrayList<Artifact>(artifacts)) {
				addParentArtifacts(buildProjectFromArtifact(dep), artifacts);
			}

			// add current project parent
			addParentArtifacts(getProject(), artifacts);
		}

		// perform filtering
		try {
			artifacts = filter.filter(artifacts);
		}
		catch (ArtifactFilterException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}

		// transform artifacts if classifier is set
		DependencyStatusSets status;
		if (StringUtils.isNotEmpty(classifier)) {
			status = getClassifierTranslatedDependencies(artifacts, stopOnFailure);
		}
		else {
			status = filterMarkedDependencies(artifacts);
		}

		return status;
	}

	private MavenProject buildProjectFromArtifact(Artifact artifact) throws MojoExecutionException {
		try {
			return projectBuilder.build(artifact, session.getProjectBuildingRequest()).getProject();
		}
		catch (ProjectBuildingException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	private void addParentArtifacts(MavenProject project, Set<Artifact> artifacts) throws MojoExecutionException {
		while (project.hasParent()) {
			project = project.getParent();

			if (artifacts.contains(project.getArtifact())) {
				// artifact already in the set
				break;
			}
			try {
				ProjectBuildingRequest buildingRequest = newResolveArtifactProjectBuildingRequest();

				Artifact resolvedArtifact =
					artifactResolver.resolveArtifact(buildingRequest, project.getArtifact()).getArtifact();

				artifacts.add(resolvedArtifact);
			}
			catch (ArtifactResolverException e) {
				throw new MojoExecutionException(e.getMessage(), e);
			}
		}
	}

	/**
	 * Transform artifacts
	 *
	 * @param artifacts set of artifacts {@link Artifact}.
	 * @param stopOnFailure true/false.
	 * @return DependencyStatusSets - Bean of TreeSets that contains information on the projects dependencies
	 * @throws MojoExecutionException in case of an error.
	 */
	protected DependencyStatusSets getClassifierTranslatedDependencies(Set<Artifact> artifacts, boolean stopOnFailure)
		throws MojoExecutionException {
		Set<Artifact> unResolvedArtifacts = new LinkedHashSet<Artifact>();
		Set<Artifact> resolvedArtifacts = artifacts;
		DependencyStatusSets status = new DependencyStatusSets();

		// possibly translate artifacts into a new set of artifacts based on the
		// classifier and type
		// if this did something, we need to resolve the new artifacts
		if (StringUtils.isNotEmpty(classifier)) {
			ArtifactTranslator translator =
				new ClassifierTypeTranslator(artifactHandlerManager, this.classifier, this.type);
			Collection<ArtifactCoordinate> coordinates = translator.translate(artifacts, getLog());

			status = filterMarkedDependencies(artifacts);

			// the unskipped artifacts are in the resolved set.
			artifacts = status.getResolvedDependencies();

			// resolve the rest of the artifacts
			resolvedArtifacts = resolve(new LinkedHashSet<ArtifactCoordinate>(coordinates), stopOnFailure);

			// calculate the artifacts not resolved.
			unResolvedArtifacts.addAll(artifacts);
			unResolvedArtifacts.removeAll(resolvedArtifacts);
		}

		// return a bean of all 3 sets.
		status.setResolvedDependencies(resolvedArtifacts);
		status.setUnResolvedDependencies(unResolvedArtifacts);

		return status;
	}

	/**
	 * Filter the marked dependencies
	 *
	 * @param artifacts The artifacts set {@link Artifact}.
	 * @return status set {@link DependencyStatusSets}.
	 * @throws MojoExecutionException in case of an error.
	 */
	protected DependencyStatusSets filterMarkedDependencies(Set<Artifact> artifacts) throws MojoExecutionException {
		// remove files that have markers already
		FilterArtifacts filter = new FilterArtifacts();
		filter.clearFilters();
		filter.addFilter(getMarkedArtifactFilter());

		Set<Artifact> unMarkedArtifacts;
		try {
			unMarkedArtifacts = filter.filter(artifacts);
		}
		catch (ArtifactFilterException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}

		// calculate the skipped artifacts
		Set<Artifact> skippedArtifacts = new LinkedHashSet<Artifact>();
		skippedArtifacts.addAll(artifacts);
		skippedArtifacts.removeAll(unMarkedArtifacts);

		return new DependencyStatusSets(unMarkedArtifacts, null, skippedArtifacts);
	}

	/**
	 * @param coordinates The set of artifact coordinates{@link ArtifactCoordinate}.
	 * @param stopOnFailure <code>true</code> if we should fail with exception if an artifact couldn't be resolved
	 *            <code>false</code> otherwise.
	 * @return the resolved artifacts. {@link Artifact}.
	 * @throws MojoExecutionException in case of error.
	 */
	protected Set<Artifact> resolve(Set<ArtifactCoordinate> coordinates, boolean stopOnFailure)
		throws MojoExecutionException {
		ProjectBuildingRequest buildingRequest = newResolveArtifactProjectBuildingRequest();

		Set<Artifact> resolvedArtifacts = new LinkedHashSet<Artifact>();
		for (ArtifactCoordinate coordinate : coordinates) {
			try {
				Artifact artifact = artifactResolver.resolveArtifact(buildingRequest, coordinate).getArtifact();
				resolvedArtifacts.add(artifact);
			}
			catch (ArtifactResolverException ex) {
				// an error occurred during resolution, log it an continue
				getLog().debug("error resolving: " + coordinate);
				getLog().debug(ex);
				if (stopOnFailure) {
					throw new MojoExecutionException("error resolving: " + coordinate, ex);
				}
			}
		}
		return resolvedArtifacts;
	}

	/**
	 * @return Returns the markersDirectory.
	 */
	public File getMarkersDirectory() {
		return this.markersDirectory;
	}

	/**
	 * @param theMarkersDirectory The markersDirectory to set.
	 */
	public void setMarkersDirectory(File theMarkersDirectory) {
		this.markersDirectory = theMarkersDirectory;
	}

	// TODO: Set marker files.

	/**
	 * @return true, if the groupId should be prepended to the filename.
	 */
	public boolean isPrependGroupId() {
		return prependGroupId;
	}

	/**
	 * @param prependGroupId - true if the groupId must be prepended during the copy.
	 */
	public void setPrependGroupId(boolean prependGroupId) {
		this.prependGroupId = prependGroupId;
	}

	/**
	 * @return {@link #artifactResolver}
	 */
	protected final ArtifactResolver getArtifactResolver() {
		return artifactResolver;
	}

	/**
	 * @return {@link #dependencyResolver}
	 */
	protected final DependencyResolver getDependencyResolver() {
		return dependencyResolver;
	}

	/**
	 * @return {@link #repositoryManager}
	 */
	protected final RepositoryManager getRepositoryManager() {
		return repositoryManager;
	}

	/**
	 * If specified, this parameter will cause the dependencies to be written to the path specified, instead of writing
	 * to the console.
	 *
	 * @since 2.0
	 */
	@Parameter(property = "outputFile")
	protected File		outputFile;

	/**
	 * This method resolves the dependency artifacts from the project.
	 *
	 * @param theProject The POM.
	 * @return resolved set of dependency artifacts.
	 * @throws ArtifactResolutionException
	 * @throws ArtifactNotFoundException
	 * @throws InvalidDependencyVersionException
	 */

	/**
	 * Whether to append outputs into the output file or overwrite it.
	 *
	 * @since 2.2
	 */
	@Parameter(property = "appendOutput", defaultValue = "false")
	protected boolean	appendOutput;

	/**
	 * Don't resolve plugins that are in the current reactor. Only works for plugins at the moment.
	 *
	 * @since 2.7
	 */
	@Parameter(property = "excludeReactor", defaultValue = "true")
	protected boolean	excludeReactor;

	/**
	 * @return {@link FilterArtifacts}
	 */
	protected FilterArtifacts getPluginArtifactsFilter() {
		if (excludeReactor) {
			final StringBuilder exAids = new StringBuilder();
			if (this.excludeArtifactIds != null) {
				exAids.append(this.excludeArtifactIds);
			}

			for (final MavenProject rp : reactorProjects) {
				if (!"maven-plugin".equals(rp.getPackaging())) {
					continue;
				}

				if (exAids.length() > 0) {
					exAids.append(",");
				}

				exAids.append(rp.getArtifactId());
			}

			this.excludeArtifactIds = exAids.toString();
		}

		final FilterArtifacts filter = new FilterArtifacts();

		// CHECKSTYLE_OFF: LineLength
		filter.addFilter(new org.apache.maven.shared.artifact.filter.collection.ScopeFilter(
			DependencyUtil.cleanToBeTokenizedString(this.includeScope),
			DependencyUtil.cleanToBeTokenizedString(this.excludeScope)));
		// CHECKSTYLE_ON: LineLength

		filter.addFilter(new TypeFilter(DependencyUtil.cleanToBeTokenizedString(this.includeTypes),
			DependencyUtil.cleanToBeTokenizedString(this.excludeTypes)));

		filter.addFilter(new ClassifierFilter(DependencyUtil.cleanToBeTokenizedString(this.includeClassifiers),
			DependencyUtil.cleanToBeTokenizedString(this.excludeClassifiers)));

		filter.addFilter(new GroupIdFilter(DependencyUtil.cleanToBeTokenizedString(this.includeGroupIds),
			DependencyUtil.cleanToBeTokenizedString(this.excludeGroupIds)));

		filter.addFilter(new ArtifactIdFilter(DependencyUtil.cleanToBeTokenizedString(this.includeArtifactIds),
			DependencyUtil.cleanToBeTokenizedString(this.excludeArtifactIds)));

		return filter;
	}

	/**
	 * This method resolves all transitive dependencies of an artifact.
	 *
	 * @param artifact the artifact used to retrieve dependencies
	 * @return resolved set of dependencies
	 * @throws DependencyResolverException in case of error while resolving artifacts.
	 */
	protected Set<Artifact> resolveArtifactDependencies(final DependableCoordinate artifact)
		throws DependencyResolverException {
		ProjectBuildingRequest buildingRequest = newResolveArtifactProjectBuildingRequest();

		Iterable<ArtifactResult> artifactResults =
			getDependencyResolver().resolveDependencies(buildingRequest, artifact, null);

		Set<Artifact> artifacts = new LinkedHashSet<Artifact>();

		for (final ArtifactResult artifactResult : artifactResults) {
			artifacts.add(artifactResult.getArtifact());
		}

		return artifacts;

	}

	protected void doExecute() throws MojoExecutionException, MojoFailureException {
		StringBuilder sb = new StringBuilder();

		DependencyStatusSets results;
		try {
			results = this.getDependencySets(false, false);
		}
		catch (MojoExecutionException ex) {
			throw new RuntimeException(ex);
		}

		sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n");
		sb.append("<target name=\"");
		sb.append(targetName);
		sb.append("\" sequenceNumber=\"1\">\n");
		sb.append("<locations>\n");
		Set<Artifact> dependencies = results.getResolvedDependencies();
		for (Artifact dependency : dependencies) {
			File file = dependency.getFile();

			sb.append("\t<location path=\"");
			sb.append(file.getParentFile().getAbsolutePath());
			sb.append("\" type=\"Directory\"/>\n");
		}
		sb.append("</locations>\n");
		sb.append("</target>\n");

		String output = sb.toString();

		try {
			if (outputFile == null) {
				DependencyUtil.log(output, getLog());
			}
			else {
				DependencyUtil.write(output, outputFile, appendOutput, getLog());
			}
		}
		catch (IOException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	protected ArtifactsFilter getMarkedArtifactFilter() {
		return new ResolveFileFilter(new SourcesFileMarkerHandler(this.markersDirectory));
	}
}
