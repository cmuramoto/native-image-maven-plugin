package com.nc.plugins.graal;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.ConfigurationContainer;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.ToolchainManager;
import org.apache.maven.toolchain.java.DefaultJavaToolChain;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.util.xml.Xpp3Dom;

@SuppressWarnings("deprecation")
@Mojo(name = "native-image", defaultPhase = LifecyclePhase.PACKAGE)
public class NativeImageMojo extends AbstractMojo {
	private static final String NATIVE_IMAGE_META_INF = "META-INF/native-image";

	private static final String NATIVE_IMAGE_PROPERTIES_FILENAME = "native-image.properties";

	static <K, V> Map.Entry<K, V> e(K k, V v) {
		return new AbstractMap.SimpleEntry<>(k, v);
	}

	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private MavenProject project;

	@Parameter(defaultValue = "${plugin}", readonly = true)
	private PluginDescriptor plugin;

	@Parameter(defaultValue = "${project.build.directory}", property = "outputDir", required = true)
	private File outputDirectory;

	@Parameter(property = "mainClass")
	private String mainClass;

	@Parameter(property = "imageName")
	private String imageName;

	@Parameter(property = "buildArgs")
	private List<String> buildArgs;

	@Parameter(property = "skip", defaultValue = "false")
	private boolean skip;

	@Parameter(defaultValue = "${session}", readonly = true)
	private MavenSession session;

	@Parameter
	private String dockerImage;

	@Parameter
	private String dockerEntryPoint;

	@Parameter
	private Properties volumes;

	@Parameter
	private boolean disableAutomaticVolumes;

	@Parameter(defaultValue = "${enforceUID}")
	private boolean enforceUID;

	@Parameter(defaultValue = "${settings.localRepository}")
	private String localRepository;

	@Parameter(defaultValue = "${mojoExecution}")
	private MojoExecution mojoExecution;

	@Component
	private ToolchainManager toolchainManager;

	private final List<Path> classpath = new ArrayList<>();

	private PluginParameterExpressionEvaluator evaluator;

	private final Pattern majorMinorPattern = Pattern.compile("(\\d+\\.\\d+)\\.");

	private void addClasspath(Artifact artifact) throws MojoExecutionException {
		if (!"jar".equals(artifact.getType())) {
			getLog().warn("Ignoring non-jar type ImageClasspath Entry " + artifact);
			return;
		}
		File artifactFile = artifact.getFile();
		if (artifactFile == null)
			throw new MojoExecutionException("Missing jar-file for " + artifact + ". Ensure " + this.plugin.getArtifactId() + " runs in package phase.");
		Path jarFilePath = artifactFile.toPath();
		getLog().info("ImageClasspath Entry: " + artifact + " (" + jarFilePath.toUri() + ")");
		URI jarFileURI = URI.create("jar:" + jarFilePath.toUri());
		try (FileSystem jarFS = FileSystems.newFileSystem(jarFileURI, Collections.emptyMap())) {
			Path nativeImageMetaInfBase = jarFS.getPath(NATIVE_IMAGE_META_INF);
			if (Files.isDirectory(nativeImageMetaInfBase)) {
				List<Path> nativeImageProperties = Files.walk(nativeImageMetaInfBase).filter(p -> p.endsWith(NATIVE_IMAGE_PROPERTIES_FILENAME)).collect(Collectors.toList());
				for (Path nativeImageProperty : nativeImageProperties) {
					Path relativeSubDir = nativeImageMetaInfBase.relativize(nativeImageProperty).getParent();
					boolean valid = (relativeSubDir != null && relativeSubDir.getNameCount() == 2);
					valid = (valid && relativeSubDir.getName(0).toString().equals(artifact.getGroupId()));
					valid = (valid && relativeSubDir.getName(1).toString().equals(artifact.getArtifactId()));
					if (!valid) {
						String example = "META-INF/native-image/${groupId}/${artifactId}/native-image.properties";
						getLog().warn(nativeImageProperty.toUri() + " does not match recommended " + example + " layout.");
					}
				}
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Artifact " + artifact + "cannot be added to image classpath", e);
		}
		this.classpath.add(jarFilePath);
	}

	private String attemptMajorMinor(String input) {
		Matcher matcher = this.majorMinorPattern.matcher(input);
		if (!matcher.find())
			return input;
		return matcher.group(1);
	}

	private String consumeConfigurationNodeValue(String pluginKey, String... nodeNames) {
		Plugin selectedPlugin = this.project.getPlugin(pluginKey);
		if (selectedPlugin == null)
			return null;
		return getConfigurationNodeValue(selectedPlugin, nodeNames);
	}

	private String consumeExecutionsNodeValue(String pluginKey, String... nodeNames) {
		Plugin selectedPlugin = this.project.getPlugin(pluginKey);
		if (selectedPlugin == null)
			return null;
		for (PluginExecution execution : selectedPlugin.getExecutions()) {
			String value = getConfigurationNodeValue(execution, nodeNames);
			if (value != null)
				return value;
		}
		return null;
	}

	String dockerImg() {
		String s = dockerImage;
		if (s == null) {
			s = "";
		} else {
			dockerImage = s = s.trim();
		}

		return s;
	}

	private String evaluateValue(String value) {
		if (value != null)
			try {
				Object evaluatedValue = this.evaluator.evaluate(value);
				if (evaluatedValue instanceof String)
					return (String) evaluatedValue;
			} catch (ExpressionEvaluationException expressionEvaluationException) {
			}
		return null;
	}

	private ProcessBuilder executable(String exe, String classpathStr) {
		String image = dockerImg();

		ProcessBuilder rv;

		if (image.isBlank()) {
			rv = new ProcessBuilder(exe);
		} else {
			String uid = uidAndGid();

			String ep = this.dockerEntryPoint;

			rv = new ProcessBuilder("docker", "container", "run", "--workdir", output());

			List<String> command = rv.command();

			if (uid != null) {
				command.add("--user");
				command.add(uid);
			}

			for (String v : volumes()) {
				command.add("-v");
				command.add(v);
			}

			command.add("--rm");

			if (ep != null && !(ep = ep.trim()).isBlank()) {
				command.add("--entrypoint");
				command.add(ep);
			}

			command.add(image);
		}

		rv.command().add("-cp");
		rv.command().add(classpathStr);

		return rv;
	}

	@Override
	public void execute() throws MojoExecutionException {
		if (this.skip) {
			getLog().info("Skipping native-image generation (parameter 'skip' is true).");
			return;
		}
		this.evaluator = new PluginParameterExpressionEvaluator(this.session, this.mojoExecution);
		this.classpath.clear();
		List<String> imageClasspathScopes = Arrays.asList("compile", "runtime");
		this.project.setArtifactFilter(artifact -> imageClasspathScopes.contains(artifact.getScope()));
		for (Artifact dependency : this.project.getArtifacts())
			addClasspath(dependency);
		addClasspath(this.project.getArtifact());
		String classpathStr = this.classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));

		Entry<String, String> config = version();

		if (dockerImg().isBlank() && !attemptMajorMinor(config.getKey()).equals(attemptMajorMinor(this.plugin.getVersion())))
			getLog().warn("Major.Minor version mismatch between " + this.plugin.getArtifactId() + " (" + this.plugin.getVersion() + ") and native-image executable (" + config.getKey() + ")");
		try {
			ProcessBuilder processBuilder = executable(config.getValue(), classpathStr);
			processBuilder.command().addAll(getBuildArgs());
			processBuilder.directory(getWorkingDirectory().toFile());
			processBuilder.inheritIO();
			String commandString = String.join(" ", processBuilder.command());
			getLog().info("Executing: " + commandString);
			Process imageBuildProcess = processBuilder.start();
			if (imageBuildProcess.waitFor() != 0)
				throw new MojoExecutionException("Execution of " + commandString + " returned non-zero result");
		} catch (IOException | InterruptedException e) {
			throw new MojoExecutionException("Building image with " + config.getValue() + " failed", e);
		}
	}

	boolean exists(String v) {
		if (v != null && !v.isBlank()) {
			try {
				boolean rv = Files.exists(Paths.get(v));

				if (!rv) {
					getLog().error("Local Volume <" + v + "> does not exist");
				}

				return rv;
			} catch (InvalidPathException e) {
				getLog().error("Invalid path " + v, e);
			}
		}

		return false;
	}

	private List<String> getBuildArgs() {
		maybeSetMainClassFromPlugin(this::consumeExecutionsNodeValue, "org.apache.maven.plugins:maven-shade-plugin", "transformers", "transformer", "mainClass");
		maybeSetMainClassFromPlugin(this::consumeConfigurationNodeValue, "org.apache.maven.plugins:maven-assembly-plugin", "archive", "manifest", "mainClass");
		maybeSetMainClassFromPlugin(this::consumeConfigurationNodeValue, "org.apache.maven.plugins:maven-jar-plugin", "archive", "manifest", "mainClass");
		List<String> list = new ArrayList<>();
		if (this.buildArgs != null && !this.buildArgs.isEmpty())
			for (String buildArg : this.buildArgs)
				list.addAll(Arrays.asList(buildArg.split("\\s+")));
		if (this.mainClass != null && !this.mainClass.equals("."))
			list.add("-H:Class=" + this.mainClass);
		if (this.imageName != null)
			list.add("-H:Name=" + this.imageName);
		return list;
	}

	private String getConfigurationNodeValue(ConfigurationContainer container, String... nodeNames) {
		if (container != null && container.getConfiguration() instanceof Xpp3Dom) {
			Xpp3Dom node = (Xpp3Dom) container.getConfiguration();
			for (String nodeName : nodeNames) {
				node = node.getChild(nodeName);
				if (node == null)
					return null;
			}
			String value = node.getValue();
			return evaluateValue(value);
		}
		return null;
	}

	private Path getMojoJavaHome() {
		return Paths.get(Optional.<ToolchainManager> ofNullable(this.toolchainManager).map(tm -> tm.getToolchainFromBuildContext("jdk", this.session)).filter(DefaultJavaToolChain.class::isInstance).map(DefaultJavaToolChain.class::cast).map(DefaultJavaToolChain::getJavaHome).orElse(System.getProperty("java.home")));
	}

	private Path getWorkingDirectory() {
		return this.outputDirectory.toPath();
	}

	private boolean isWindows() {
		return System.getProperty("os.name").contains("Windows");
	}

	private void maybeSetMainClassFromPlugin(BiFunction<String, String[], String> mainClassProvider, String pluginName, String... nodeNames) {

		if (this.mainClass == null) {
			this.mainClass = mainClassProvider.apply(pluginName, nodeNames);
			if (this.mainClass != null)
				getLog().info("Obtained main class from plugin " + pluginName + " with the following path: " + String.join(" -> ", nodeNames));
		}
	}

	String output() {
		return outputDirectory.toString();
	}

	String tryCast(Object o) {
		if (o instanceof CharSequence) {
			return ((CharSequence) o).toString();
		}

		if (o != null) {
			getLog().warn("Expected Charsequence, got " + o.getClass());
		}

		return "";
	}

	String uidAndGid() {
		if (isWindows()) {
			return null;
		}

		try {
			Path home = Paths.get(System.getProperty("user.home"));
			int uid = (Integer) Files.getAttribute(home, "unix:uid");
			int gid = (Integer) Files.getAttribute(home, "unix:uid");

			return uid + ":" + gid;
		} catch (Exception e) {
			if (enforceUID) {
				getLog().warn(e.getMessage(), e);
				throw new IllegalStateException("enforceUID is set to true and uid/gid could not be determined");
			}
			return null;
		}
	}

	Map.Entry<String, String> version() throws MojoExecutionException {
		String image = dockerImg();

		String nativeImageExecutableVersion = "Unknown";

		String executable;

		ProcessBuilder pb;

		if (image.isBlank()) {
			Path nativeImageExecutableRelPath = Paths.get("lib", "svm", "bin", "native-image" + (isWindows() ? ".exe" : ""));
			Path mojoJavaHome = getMojoJavaHome();
			Path nativeImageExecutable = mojoJavaHome.resolve(nativeImageExecutableRelPath);

			if (!Files.isExecutable(nativeImageExecutable)) {
				nativeImageExecutable = mojoJavaHome.resolve("jre").resolve(nativeImageExecutableRelPath);
				if (!Files.isExecutable(nativeImageExecutable))
					throw new MojoExecutionException("Could not find executable native-image in " + nativeImageExecutable);
			}

			pb = new ProcessBuilder(executable = nativeImageExecutable.toString(), "--version");
		} else {
			executable = "docker";

			getLog().info("Checking: " + image);
			String ep = this.dockerEntryPoint;

			if (ep != null && !(ep = ep.trim()).isBlank()) {
				pb = new ProcessBuilder("docker", "container", "run", "--rm", "--entrypoint", ep, image, "--version");
			} else {
				pb = new ProcessBuilder("docker", "container", "run", "--rm", image, "--version");
			}
		}

		Process versionCheckProcess = null;

		try {
			versionCheckProcess = pb.start();

			try (Scanner scanner = new Scanner(versionCheckProcess.getInputStream())) {
				while (true) {
					if (scanner.findInLine("GraalVM Version ") != null) {
						nativeImageExecutableVersion = scanner.next();
						break;
					}
					if (!scanner.hasNextLine())
						break;
					scanner.nextLine();
				}
				if (versionCheckProcess.waitFor() != 0)
					throw new MojoExecutionException("Execution of " + String.join(" ", pb.command()) + " returned non-zero result");
			}
		} catch (IOException | InterruptedException e) {
			throw new MojoExecutionException("Probing version info of native-image executable " + pb.command() + " failed", e);
		} finally {
			if (versionCheckProcess != null) {
				versionCheckProcess.destroyForcibly();
			}
		}

		return new AbstractMap.SimpleImmutableEntry<>(nativeImageExecutableVersion, executable);
	}

	Set<String> volumes() {
		Properties volumes = this.volumes;

		Set<Map.Entry<String, String>> rv = new TreeSet<>((l, r) -> l.getKey().compareTo(r.getKey()));

		String home = System.getProperty("user.home");

		if (!disableAutomaticVolumes) {
			String v = output();

			rv.add(e(home, home));

			if (exists(v) && !v.startsWith(home)) {
				rv.add(e(v, v));
			}

			v = localRepository;

			if (exists(v) && !v.startsWith(home)) {
				rv.add(e(v, v));
			}
		}

		if (volumes != null && !volumes.isEmpty()) {
			volumes.entrySet().forEach(e -> {
				String lv = tryCast(e.getKey());
				String iv = tryCast(e.getValue());

				if (exists(lv)) {
					if (!disableAutomaticVolumes && lv.startsWith(home)) {
						getLog().warn("Discarding volume map <" + lv + ":" + iv + "> since it is prefixed by $HOME");
					} else if (iv.isBlank()) {
						getLog().warn("Invalid image path " + iv);
					} else {
						rv.add(e(lv, iv));
					}
				}
			});

		}

		return rv.stream().map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.toCollection(TreeSet::new));
	}
}
