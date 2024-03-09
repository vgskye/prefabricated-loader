/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.impl.launch.knot;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.ServiceLoader;
import java.util.Stack;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.Gson;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.entrypoint.EntrypointStorage;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.launch.FabricMixinBootstrap;
import net.fabricmc.loader.impl.util.FileSystemUtil;
import net.fabricmc.loader.impl.util.LoaderUtil;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.UrlUtil;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter;
import net.fabricmc.mappingio.tree.MappingTree;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.ISyntheticClassInfo;

public final class Knot extends FabricLauncherBase {
	private static final boolean IS_DEVELOPMENT = Boolean.parseBoolean(System.getProperty(SystemProperties.DEVELOPMENT, "false"));

	protected Map<String, Object> properties = new HashMap<>();

	private KnotClassLoaderInterface classLoader;
	private EnvType envType;
	private final List<Path> classPath = new ArrayList<>();
	private GameProvider provider;
	private boolean unlocked;
	private final ArrayList<Path> toShadow = new ArrayList<>();
	private static final Gson gson = new Gson();
	private static class Entrypoint {
		String mod;
		String adapter;
		String value;

		private Entrypoint(String mod, String adapter, String value) {
			this.mod = mod;
			this.adapter = adapter;
			this.value = value;
		}
	}
	private static class FrozenMeta {
		Map<String, List<Entrypoint>> entrypoints;
		Map<String, String> languageAdapters;
		List<String> mods;
		String envType;
		String entrypoint;

		private FrozenMeta(
				Map<String, List<Entrypoint>> entrypoints,
				Map<String, String> languageAdapters,
				List<String> mods,
				String envType,
				String entrypoint
		) {
			this.entrypoints = entrypoints;
			this.languageAdapters = languageAdapters;
			this.mods = mods;
			this.envType = envType;
			this.entrypoint = entrypoint;
		}
	}

	public static void launch(String[] args, EnvType type) {
		setupUncaughtExceptionHandler();

		try {
			Knot knot = new Knot(type);
			ClassLoader cl = knot.init(args);

			if (knot.provider == null) {
				throw new IllegalStateException("Game provider was not initialized! (Knot#init(String[]))");
			}
			try {
				FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
				Path base = loader.getGameDir().resolve("mod_assets");
				List<String> mods = new ArrayList<>(loader.getAllMods().size());
				for (ModContainer mod : loader.getAllMods()) {
					if (mod.getMetadata().getType().equals("builtin")) {
						continue;
					}
					mods.add(mod.getMetadata().getId());
					Path forMod = base.resolve(mod.getMetadata().getId());
					System.err.println("Extracting the assets for " + mod.getMetadata().getName());
					Files.createDirectories(forMod);
					for (Path path : mod.getRootPaths()) {
						Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
							@Override
							public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
								if (!file.toString().endsWith(".class")) {
									byte[] contents = Files.readAllBytes(file);
									Files.createDirectories(forMod.resolve(path.relativize(file).toString()).getParent());
									Files.write(forMod.resolve(path.relativize(file).toString()), contents);
								}
								return FileVisitResult.CONTINUE;
							}
						});
					}
				}
				FileSystemUtil.FileSystemDelegate uber = FileSystemUtil.getJarFileSystem(Paths.get("uber.jar"), true);
				Path uberRoot = uber.get().getRootDirectories().iterator().next();
				for (Path path: knot.toShadow) {
					System.err.println("Shadowing " + path.getFileName());
					FileSystemUtil.FileSystemDelegate delegate = FileSystemUtil.getJarFileSystem(path, false);
					for (Path directory : delegate.get().getRootDirectories()) {
						Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
							@Override
							public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
								if (file.toString().endsWith(".SF")) {
									return FileVisitResult.CONTINUE;
								}
								if (file.toString().endsWith(".class")) {
									String fileName = directory.relativize(file).toString();
									String rawClassName = fileName.replace(delegate.get().getSeparator(), ".");
									String className = rawClassName.substring(0, rawClassName.length() - 6);
									try {
										byte[] clazz = knot.getMixinedClassByteArray(className);
										Files.createDirectories(uberRoot.resolve(directory.relativize(file).toString()).getParent());
										Files.write(uberRoot.resolve(directory.relativize(file).toString()), clazz);
									} catch (Exception e) {
										try {
											byte[] clazz = Files.readAllBytes(file);
											Files.createDirectories(uberRoot.resolve(directory.relativize(file).toString()).getParent());
											Files.write(uberRoot.resolve(directory.relativize(file).toString()), clazz);
											System.err.println(className + " did not exist after mixin!");
										} catch (Exception ex) {
											System.err.println("Failed to shadow " + className);
										}
									}
								} else {
									byte[] contents = Files.readAllBytes(file);
									Files.createDirectories(uberRoot.resolve(directory.relativize(file).toString()).getParent());
									Files.write(uberRoot.resolve(directory.relativize(file).toString()), contents);
								}
								return FileVisitResult.CONTINUE;
							}
						});
					}
					delegate.close();
				}
				System.err.println("Injecting Mixin synthetics");
				IMixinTransformer transformer = knot.classLoader.getMixinTransformer();
				// Gross Reflection Hacks
				Class<?> transformerImpl = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer");
				Field syntheticClassRegistry = transformerImpl.getDeclaredField("syntheticClassRegistry");
				syntheticClassRegistry.setAccessible(true);
				Object registryInstance = syntheticClassRegistry.get(transformer);
				Class<?> syntheticClassRegistryClass = Class.forName("org.spongepowered.asm.mixin.transformer.SyntheticClassRegistry");
				Field classes = syntheticClassRegistryClass.getDeclaredField("classes");
				classes.setAccessible(true);
				Map<String, ISyntheticClassInfo> classesField = (Map<String, ISyntheticClassInfo>) classes.get(registryInstance);
				for (String rawName : classesField.keySet()) {
					String className = rawName.replace("/", ".");
					try {
						byte[] clazz = transformer.transformClassBytes(className, className, null);
						String path = className.replace(".", uber.get().getSeparator()) + ".class";
						Files.createDirectories(uberRoot.resolve(path).getParent());
						Files.write(uberRoot.resolve(path), clazz);
					} catch (Exception e) {
						System.err.println("Failed to add synthetic class " + className);
					}
				}
				System.err.println("Injecting MixinExtras synthetics");
				// Gross Reflection Hacks, Part 2
				Class<?> classGenUtils = Class.forName("com.llamalad7.mixinextras.utils.ClassGenUtils", true, cl);
				Field definitionsField = classGenUtils.getDeclaredField("DEFINITIONS");
				definitionsField.setAccessible(true);
				Map<String, byte[]> definitions = (Map<String, byte[]>) definitionsField.get(null);
				for (Map.Entry<String, byte[]> entry : definitions.entrySet()) {
					String className = entry.getKey();
					try {
						byte[] clazz = entry.getValue();
						String path = className.replace(".", uber.get().getSeparator()) + ".class";
						Files.createDirectories(uberRoot.resolve(path).getParent());
						Files.write(uberRoot.resolve(path), clazz);
					} catch (Exception e) {
						System.err.println("Failed to add MixinExtras synthetic class " + className);
						e.printStackTrace();
					}
				}
				System.err.println("Adding runtime");
				FileSystemUtil.FileSystemDelegate runtime = FileSystemUtil.getJarFileSystem(Paths.get("runtime.jar"), false);
				for (Path directory : runtime.get().getRootDirectories()) {
					Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
						@Override
						public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
							byte[] contents = Files.readAllBytes(file);
							Files.createDirectories(uberRoot.resolve(directory.relativize(file).toString()).getParent());
							Files.write(uberRoot.resolve(directory.relativize(file).toString()), contents);
							return FileVisitResult.CONTINUE;
						}
					});
				}
				runtime.close();
				uber.close();
				System.err.println("Writing mappings");
				MappingTree mapping = knot.getMappingConfiguration().getMappings();
				BufferedWriter mappingsWriter = Files.newBufferedWriter(loader.getGameDir().resolve("mappings.tiny"));
				Tiny2FileWriter tinyWriter = new Tiny2FileWriter(mappingsWriter, false);
				mapping.accept(tinyWriter);
				tinyWriter.close();
				mappingsWriter.close();
				System.err.println("Writing frozen loader state");
				Map<String, List<Entrypoint>> entrypoints = new HashMap<>(loader.entrypointStorage.entryMap.size());
				for (Map.Entry<String, List<EntrypointStorage.Entry>> entry : loader.entrypointStorage.entryMap.entrySet()) {
					List<Entrypoint> entrypointList = new ArrayList<>(entry.getValue().size());
					for (EntrypointStorage.Entry entrypoint : entry.getValue()) {
						Entrypoint toFreeze = new Entrypoint(
								entrypoint.getModContainer().getMetadata().getId(),
								entrypoint.getLanguageAdapter(),
								entrypoint.getValue()
						);
						entrypointList.add(toFreeze);
					}
					entrypoints.put(entry.getKey(), entrypointList);
				}
				Map<String, String> languageAdapters = new HashMap<>(loader.adapterMap.size());
				for (Map.Entry<String, LanguageAdapter> entry : loader.adapterMap.entrySet()) {
					languageAdapters.put(entry.getKey(), entry.getValue().getClass().getName());
				}
				String entrypoint = knot.getEntrypoint();
				String envType = "";
				switch (knot.envType) {
					case CLIENT:
						envType = "client";
						break;
					case SERVER:
						envType = "server";
						break;
				}
				FrozenMeta toFreeze = new FrozenMeta(entrypoints, languageAdapters, mods, envType, entrypoint);
				BufferedWriter writer = Files.newBufferedWriter(loader.getGameDir().resolve("prefabricated_frozen.json"), StandardCharsets.UTF_8);
				gson.toJson(toFreeze, writer);
				writer.close();
				System.err.println("Current launch args: " + String.join(" ", loader.getLaunchArguments(false)));
				BufferedWriter argsWriter = Files.newBufferedWriter(loader.getGameDir().resolve("current_args.txt"), StandardCharsets.UTF_8);
				argsWriter.write(String.join(" ", loader.getLaunchArguments(false)));
				argsWriter.close();
			} catch (Throwable e) {
				throw new RuntimeException(e);
			}
		} catch (FormattedException e) {
			handleFormattedException(e);
		}
	}

	public Knot(EnvType type) {
		this.envType = type;
	}

	public ClassLoader init(String[] args) {
		setProperties(properties);

		// configure fabric vars
		if (envType == null) {
			String side = System.getProperty(SystemProperties.SIDE);
			if (side == null) throw new RuntimeException("Please specify side or use a dedicated Knot!");

			switch (side.toLowerCase(Locale.ROOT)) {
			case "client":
				envType = EnvType.CLIENT;
				break;
			case "server":
				envType = EnvType.SERVER;
				break;
			default:
				throw new RuntimeException("Invalid side provided: must be \"client\" or \"server\"!");
			}
		}

		classPath.clear();

		List<String> missing = null;
		List<String> unsupported = null;

		for (String cpEntry : System.getProperty("java.class.path").split(File.pathSeparator)) {
			if (cpEntry.equals("*") || cpEntry.endsWith(File.separator + "*")) {
				if (unsupported == null) unsupported = new ArrayList<>();
				unsupported.add(cpEntry);
				continue;
			}

			Path path = Paths.get(cpEntry);

			if (!Files.exists(path)) {
				if (missing == null) missing = new ArrayList<>();
				missing.add(cpEntry);
				continue;
			}

			classPath.add(LoaderUtil.normalizeExistingPath(path));
		}

		if (unsupported != null) Log.warn(LogCategory.KNOT, "Knot does not support wildcard class path entries: %s - the game may not load properly!", String.join(", ", unsupported));
		if (missing != null) Log.warn(LogCategory.KNOT, "Class path entries reference missing files: %s - the game may not load properly!", String.join(", ", missing));

		provider = createGameProvider(args);
		Log.finishBuiltinConfig();
		Log.info(LogCategory.GAME_PROVIDER, "Loading %s %s with Fabric Loader %s", provider.getGameName(), provider.getRawGameVersion(), FabricLoaderImpl.VERSION);

		// Setup classloader
		// TODO: Provide KnotCompatibilityClassLoader in non-exclusive-Fabric pre-1.13 environments?
		boolean useCompatibility = provider.requiresUrlClassLoader() || Boolean.parseBoolean(System.getProperty("fabric.loader.useCompatibilityClassLoader", "false"));
		classLoader = KnotClassLoaderInterface.create(useCompatibility, isDevelopment(), envType, provider);
		ClassLoader cl = classLoader.getClassLoader();

		provider.initialize(this);

		Thread.currentThread().setContextClassLoader(cl);

		FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
		loader.setGameProvider(provider);
		loader.load();
		loader.freeze();

		FabricLoaderImpl.INSTANCE.loadAccessWideners();

		FabricMixinBootstrap.init(getEnvironmentType(), loader);
		FabricLauncherBase.finishMixinBootstrapping();

		classLoader.initializeTransformers();

		provider.unlockClassPath(this);
		unlocked = true;

		return cl;
	}

	private GameProvider createGameProvider(String[] args) {
		// fast path with direct lookup

		GameProvider embeddedGameProvider = findEmbedddedGameProvider();

		if (embeddedGameProvider != null
				&& embeddedGameProvider.isEnabled()
				&& embeddedGameProvider.locateGame(this, args)) {
			return embeddedGameProvider;
		}

		// slow path with service loader

		List<GameProvider> failedProviders = new ArrayList<>();

		for (GameProvider provider : ServiceLoader.load(GameProvider.class)) {
			if (!provider.isEnabled()) continue; // don't attempt disabled providers and don't include them in the error report

			if (provider != embeddedGameProvider // don't retry already failed provider
					&& provider.locateGame(this, args)) {
				return provider;
			}

			failedProviders.add(provider);
		}

		// nothing found

		String msg;

		if (failedProviders.isEmpty()) {
			msg = "No game providers present on the class path!";
		} else if (failedProviders.size() == 1) {
			msg = String.format("%s game provider couldn't locate the game! "
					+ "The game may be absent from the class path, lacks some expected files, suffers from jar "
					+ "corruption or is of an unsupported variety/version.",
					failedProviders.get(0).getGameName());
		} else {
			msg = String.format("None of the game providers (%s) were able to locate their game!",
					failedProviders.stream().map(GameProvider::getGameName).collect(Collectors.joining(", ")));
		}

		Log.error(LogCategory.GAME_PROVIDER, msg);

		throw new RuntimeException(msg);
	}

	/**
	 * Find game provider embedded into the Fabric Loader jar, best effort.
	 *
	 * <p>This is faster than going through service loader because it only looks at a single jar.
	 */
	private static GameProvider findEmbedddedGameProvider() {
		try {
			Path flPath = UrlUtil.getCodeSource(Knot.class);
			if (flPath == null || !flPath.getFileName().toString().endsWith(".jar")) return null; // not a jar

			try (ZipFile zf = new ZipFile(flPath.toFile())) {
				ZipEntry entry = zf.getEntry("META-INF/services/net.fabricmc.loader.impl.game.GameProvider"); // same file as used by service loader
				if (entry == null) return null;

				try (InputStream is = zf.getInputStream(entry)) {
					byte[] buffer = new byte[100];
					int offset = 0;
					int len;

					while ((len = is.read(buffer, offset, buffer.length - offset)) >= 0) {
						offset += len;
						if (offset == buffer.length) buffer = Arrays.copyOf(buffer, buffer.length * 2);
					}

					String content = new String(buffer, 0, offset, StandardCharsets.UTF_8).trim();
					if (content.indexOf('\n') >= 0) return null; // potentially more than one entry -> bail out

					int pos = content.indexOf('#');
					if (pos >= 0) content = content.substring(0, pos).trim();

					if (!content.isEmpty()) {
						return (GameProvider) Class.forName(content).getConstructor().newInstance();
					}
				}
			}

			return null;
		} catch (IOException | ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public String getTargetNamespace() {
		// TODO: Won't work outside of Yarn
		return IS_DEVELOPMENT ? "named" : "intermediary";
	}

	@Override
	public List<Path> getClassPath() {
		return classPath;
	}

	@Override
	public void addToClassPath(Path path, String... allowedPrefixes) {
		Log.debug(LogCategory.KNOT, "Adding " + path + " to classpath.");

		classLoader.setAllowedPrefixes(path, allowedPrefixes);
		classLoader.addCodeSource(path);

		toShadow.add(path);
	}

	@Override
	public void setAllowedPrefixes(Path path, String... prefixes) {
		classLoader.setAllowedPrefixes(path, prefixes);
	}

	@Override
	public void setValidParentClassPath(Collection<Path> paths) {
		classLoader.setValidParentClassPath(paths);
	}

	@Override
	public EnvType getEnvironmentType() {
		return envType;
	}

	@Override
	public boolean isClassLoaded(String name) {
		return classLoader.isClassLoaded(name);
	}

	@Override
	public Class<?> loadIntoTarget(String name) throws ClassNotFoundException {
		return classLoader.loadIntoTarget(name);
	}

	@Override
	public InputStream getResourceAsStream(String name) {
		return classLoader.getClassLoader().getResourceAsStream(name);
	}

	@Override
	public ClassLoader getTargetClassLoader() {
		KnotClassLoaderInterface classLoader = this.classLoader;

		return classLoader != null ? classLoader.getClassLoader() : null;
	}

	@Override
	public byte[] getClassByteArray(String name, boolean runTransformers) throws IOException {
		if (!unlocked) throw new IllegalStateException("early getClassByteArray access");

		if (runTransformers) {
			return classLoader.getPreMixinClassBytes(name);
		} else {
			return classLoader.getRawClassBytes(name);
		}
	}

	public byte[] getMixinedClassByteArray(String name) {
		if (!unlocked) throw new IllegalStateException("early getClassByteArray access");
		return classLoader.getPostMixinClassBytes(name);
	}

	@Override
	public Manifest getManifest(Path originPath) {
		return classLoader.getManifest(originPath);
	}

	@Override
	public boolean isDevelopment() {
		return IS_DEVELOPMENT;
	}

	@Override
	public String getEntrypoint() {
		return provider.getEntrypoint();
	}

	public static void main(String[] args) {
		new Knot(null).init(args);
	}

	static {
		LoaderUtil.verifyNotInTargetCl(Knot.class);

		if (IS_DEVELOPMENT) {
			LoaderUtil.verifyClasspath();
		}
	}
}
