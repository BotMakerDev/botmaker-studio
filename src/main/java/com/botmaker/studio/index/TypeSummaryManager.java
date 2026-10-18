package com.botmaker.studio.index;

import com.botmaker.shared.config.CacheDirs;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.util.ClassPathManager;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.AnnotationInfoList;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.HasName;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/**
 * Per-jar index of external library types, read off the class files by ClassGraph.
 *
 * <p>Each jar is scanned once into {@link LibraryType} records, and they are cached to disk as JSON, one
 * {@code .json} file per jar. The in-memory index hands out those records, which
 * {@link com.botmaker.studio.suggestions.ProjectAnalyzer} consumes directly. It handed out ClassGraph's own
 * {@code ClassInfo} and cached ClassGraph's JSON until ClassGraph 6 removed it (2026-10-18).
 *
 * <p>Beside each cache sits the list of its packages ({@code .packages}), and a jar is read from its JSON
 * only once one of them is under the allowed prefixes, or once it is asked for by name: an open reads the
 * one or two jars it offers from, not all of them.
 */
public class TypeSummaryManager {

    /**
     * Package prefixes whose classes are exposed to the user as "allowed" library types. Everything indexed
     * outside these prefixes (a plugin's own {@code internal} package and all transitive dependencies —
     * opencv, jackson, eclipse, ddmlib…) is still scanned for resolution-completeness but hidden from the
     * type and expression menus, keeping the bot-builder surface curated.
     *
     * <p><b>It was the literal {@code "com.botmaker.sdk.api"} until 2026-09-02</b>, which made this class
     * the last place in the editor holding one plugin's package name. It asks the loaded plugins now —
     * {@link PluginHost#cataloguedPackages()} — so a plugin's API is offerable because that plugin
     * catalogued it, not because the editor was told where to look.
     */
    public static Set<String> defaultAllowedPackagePrefixes() {
        return PluginHost.cataloguedPackages();
    }

    /**
     * Asked on every cache rebuild, never captured — which is what makes a plugin installed into an open
     * project visible without reopening it (2026-09-19).
     *
     * <p>It was a {@code final Set} taken once, at the moment {@code BotProject} built this manager. A blank
     * project binds no plugin, so that set was empty and stayed empty: {@code LibraryService.rebind()} bound
     * the new plugin, re-scanned its jar and published {@code LibrariesChangedEvent}, and every class in it
     * was still filtered out of {@link #getAllTypes()}. The statement menus enumerate through
     * {@code ProjectAnalyzer} and so listed nothing, which is why adding the SDK to a project needed a
     * reopen — and why <i>Reload Plugins</i> did not help, since it runs the same rebind.
     */
    private final Supplier<Set<String>> allowedPackagePrefixes;

    /** The prefixes {@link #ensureCaches()} last built with, so a changed answer rebuilds them. */
    private Set<String> prefixesInUse = Set.of();

    /**
     * Whether {@link #getAllTypes()} hands back everything indexed, ignoring {@link #allowedPackagePrefixes}
     * entirely. Only {@link #overEverything()} sets it.
     */
    private final boolean everything;

    /** jar path → its (non-anonymous) classes, as its cache file holds them. */
    private final Map<String, List<LibraryType>> index = new HashMap<>();

    /** Normalised jar path → the names of its indexed classes: which jar a class came from, for {@link #isIn}. */
    private final Map<Path, Set<String>> namesByJar = new HashMap<>();

    /**
     * jar path → its packages, for a jar with a fresh cache that nothing has asked for yet (2026-10-18).
     *
     * <p>The profiler found a project open deserializing all 74 of its jars' JSON — opencv, openblas,
     * bcprov, each a ClassGraph pool of its own — to offer 94 types out of one or two of them. A jar here is
     * known but not loaded: {@link #ensureCaches()} loads it once one of its packages is under the prefixes,
     * and {@link #getTypesForJar} when asked for it by name.
     */
    private final Map<String, Set<String>> deferred = new HashMap<>();

    // Derived caches so reads are O(1) instead of re-flattening/scanning every jar on each lookup (the hot
    // path for menu population). Marked dirty by store() and rebuilt lazily on first read, so loading N jars
    // rebuilds once, not N times.
    private boolean cachesDirty = true;
    private List<LibraryType> allTypesCache = List.of();
    private final Map<String, LibraryType> bySimpleName = new HashMap<>();
    private final Map<String, LibraryType> byQualifiedName = new HashMap<>();
    private List<LibraryType> staticUtilityCache = List.of();

    public TypeSummaryManager() {
        this(TypeSummaryManager::defaultAllowedPackagePrefixes, false);
    }

    /** A manager over a fixed set of prefixes — a test's, or a caller that knows the answer already. */
    public TypeSummaryManager(Set<String> allowedPackagePrefixes) {
        this(fixed(allowedPackagePrefixes), false);
    }

    /** A manager whose prefixes are asked for on every rebuild — the shape production uses, for a test. */
    TypeSummaryManager(Supplier<Set<String>> allowedPackagePrefixes) {
        this(allowedPackagePrefixes, false);
    }

    private TypeSummaryManager(Supplier<Set<String>> allowedPackagePrefixes, boolean everything) {
        this.allowedPackagePrefixes = allowedPackagePrefixes;
        this.everything = everything;
    }

    private static Supplier<Set<String>> fixed(Set<String> prefixes) {
        Set<String> copy = Set.copyOf(prefixes);
        return () -> copy;
    }

    /**
     * An index over whatever it is given, with no package filter at all — for a reader asking what a jar
     * <em>contains</em> rather than what a user should be offered.
     *
     * <p>The distinction is the whole reason this exists. {@link #defaultAllowedPackagePrefixes()} answers
     * <i>which packages do the plugins bound right now catalogue</i>, which is the right question for a menu
     * and the wrong one for a compatibility scan: the jar being read may belong to a plugin that is not
     * bound, has never been installed, or failed to load, and every one of those answers the empty set. A
     * scan filtered to nothing reads as <i>this jar has no public API</i> — which is what
     * {@code upgrade/ApiModel.snapshot} reported for two weeks in every headless caller, since with no project
     * open nothing is bound.
     *
     * <p>What a jar contains is a property of the jar, so nothing here needs a project, a binding or a list.
     */
    public static TypeSummaryManager overEverything() {
        return new TypeSummaryManager(Set::of, true);
    }

    /** True when {@code ci}'s package is under one of {@code prefixes}. */
    private boolean isAllowed(LibraryType type, Set<String> prefixes) {
        return everything || isUnder(type.packageName(), prefixes);
    }

    private static boolean isUnder(String pkg, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (pkg.equals(prefix) || pkg.startsWith(prefix + ".")) return true;
        }
        return false;
    }

    /** True when a deferred jar holding {@code packages} has a class {@link #getAllTypes()} would offer. */
    private boolean isWanted(Set<String> packages, Set<String> prefixes) {
        if (everything) return true;
        for (String pkg : packages) {
            if (isUnder(pkg, prefixes)) return true;
        }
        return false;
    }

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /**
     * Loads cached classes for each jar (one .json file per jar). Indexes any jars that don't have a
     * cache file yet, then saves them.
     *
     * @param allJars list of jar paths to ensure are indexed
     */
    public static TypeSummaryManager buildOrLoad(List<String> allJars) {
        TypeSummaryManager manager = new TypeSummaryManager();
        sweepStaleCaches();

        List<String> missingJars = new ArrayList<>();

        for (String jar : allJars) {
            if (ClassPathManager.isSystemJar(jar)) continue;
            if (!manager.fromCache(jar, getCacheFileForJar(jar))) missingJars.add(jar);
        }

        System.out.println("Index loaded from cache — " + manager.totalTypes() + " types across "
                + manager.index.size() + " jars (" + manager.deferred.size() + " more not needed yet)");

        if (missingJars.isEmpty()) {
            return manager;
        }

        System.out.println("Indexing " + missingJars.size() + " new jars...");
        for (String jar : missingJars) {
            manager.indexJar(jar);
            manager.saveJar(jar); // save immediately after indexing each jar
        }

        return manager;
    }

    /**
     * Loads cached classes for a specific list of jars only.
     * Jars not present in cache are indexed on demand and saved.
     *
     * @param jars subset of jars to load/index
     */
    public static TypeSummaryManager load(List<String> jars) {
        return buildOrLoad(jars);
    }

    /**
     * Incrementally indexes any of {@code jars} not already present in this manager (skipping system
     * jars), saving each new jar's scan to its cache file. Already-indexed and system jars are ignored,
     * so this is cheap to call after a classpath change.
     *
     * <p>It ends by loading the jars the prefixes now want, so the deferred jars a plugin bound just before
     * this call brings into view are read here, off the FX thread, not by the first menu that opens.
     */
    public void refresh(List<String> jars) {
        for (String jar : jars) {
            if (ClassPathManager.isSystemJar(jar)) continue;

            Path cacheFile = getCacheFileForJar(jar);
            // Already indexed in memory: keep it only if the on-disk jar hasn't changed since. A reused
            // SNAPSHOT path (botmaker-sdk-1.3.1-SNAPSHOT.jar) keeps the same key across rebuilds, so without
            // this a live session would never pick up a freshly reinstalled SDK.
            if (isKnown(jar)) {
                if (isCacheFresh(jar, cacheFile)) continue;
                forget(jar);
            }

            if (fromCache(jar, cacheFile)) continue;
            indexJar(jar);
            saveJar(jar);
        }
        ensureCaches();
    }

    // The readers hold the lock the rebuild does: refresh() rebuilds on LibraryService's thread while a menu
    // reads on FX, and a map read mid-clear() misses a type a block then draws as unresolved.
    public synchronized List<LibraryType> getAllTypes() {
        ensureCaches();
        return allTypesCache;
    }

    /** What {@code jarPath} holds, read from its cache now if nothing had asked for it yet. */
    public synchronized List<LibraryType> getTypesForJar(String jarPath) {
        if (deferred.containsKey(jarPath)) loadDeferred(jarPath);
        return index.getOrDefault(jarPath, List.of());
    }

    /** Whether {@code jarPath}'s classes are read into memory, not only known by their packages. For a test. */
    synchronized boolean isLoaded(String jarPath) {
        return index.containsKey(jarPath);
    }

    public synchronized Optional<LibraryType> findBySimpleName(String simpleName) {
        ensureCaches();
        return Optional.ofNullable(bySimpleName.get(simpleName));
    }

    public synchronized Optional<LibraryType> findByQualifiedName(String qualifiedName) {
        ensureCaches();
        return Optional.ofNullable(byQualifiedName.get(qualifiedName));
    }

    public List<LibraryType> findEnums() {
        return getAllTypes().stream()
                .filter(LibraryType::isEnum)
                .toList();
    }

    /**
     * Whether {@code type} was indexed out of one of {@code jars} (absolute, normalised paths), answered from
     * this index's own record of which jar each class came from. It asked ClassGraph's
     * {@code getClasspathElementFile} once, which a class re-hydrated from the old JSON cache could not answer
     * (2026-10-01).
     */
    public synchronized boolean isIn(LibraryType type, Set<Path> jars) {
        if (jars.isEmpty()) return false;
        for (Path jar : jars) {
            if (namesByJar.getOrDefault(jar, Set.of()).contains(type.name())) return true;
        }
        return false;
    }

    /** Library classes exposing at least one public static method returning a value (Call-Function targets). */
    public synchronized List<LibraryType> getStaticUtilityTypes() {
        ensureCaches();
        return staticUtilityCache;
    }

    public int totalTypes() {
        return getAllTypes().size();
    }

    /**
     * Eagerly builds the derived caches (name maps + static-utility list) so the first menu interaction
     * doesn't pay the cost on the UI thread. Safe to call from a background thread — {@link #ensureCaches()}
     * is synchronized and idempotent. Intended to be invoked once right after the index is loaded.
     */
    public void warmCaches() {
        ensureCaches();
    }

    // =========================================================================
    // INDEXING
    // =========================================================================

    private void indexJar(String jar) {
        // Annotation info is what makes @Deprecated readable (it is a RUNTIME-retained annotation, so it is in
        // the class file — but ClassGraph does not record annotations unless asked).
        // ProjectAnalyzer.isMemberDeprecated reads it to strike deprecated calls through; without this the flag
        // is silently always false.
        try (ScanResult scan = new ClassGraph()
                .enableClasspathEntries(jar)
                .enableClassInfo()
                .enableMethodInfo()
                .enableFieldInfo()
                .enableAnnotationInfo()
                .scan()) {
            store(jar, scan.getAllClasses().stream()
                    .filter(ci -> !ci.isAnonymousClass())
                    .map(TypeSummaryManager::typeOf)
                    .toList());
            System.out.println("Indexed: " + Path.of(jar).getFileName()
                    + " → " + getTypesForJar(jar).size() + " types");
        } catch (Exception e) {
            System.err.println("Failed to index " + jar + ": " + e.getMessage());
        }
    }

    private static LibraryType typeOf(ClassInfo ci) {
        int typeParameters = ci.getTypeSignature() == null ? 0 : ci.getTypeSignature().getTypeParameters().size();
        return new LibraryType(ci.getName(), ci.getSimpleName(), ci.getModifiers(), typeParameters,
                names(ci.getAllSuperclasses()), names(ci.getAllSuperinterfaces()),
                ci.getMethodInfo().stream().map(TypeSummaryManager::methodOf).toList(),
                ci.getConstructorInfo().stream().map(TypeSummaryManager::methodOf).toList(),
                ci.getFieldInfo().stream().map(TypeSummaryManager::fieldOf).toList(),
                ci.hasAnnotation(DEPRECATED), since(ci.getAllAnnotationInfo()));
    }

    private static LibraryType.Method methodOf(MethodInfo mi) {
        List<LibraryType.Parameter> parameters = mi.getParameterInfo().stream()
                .map(p -> new LibraryType.Parameter(p.getName(), p.getTypeSignatureOrTypeDescriptor().toString(),
                        p.getTypeDescriptor().toString()))
                .toList();
        return new LibraryType.Method(mi.getName(), mi.getModifiers(), parameters,
                mi.getTypeSignatureOrTypeDescriptor().getResultType().toString(), mi.hasAnnotation(DEPRECATED),
                since(mi.getAllAnnotationInfo()));
    }

    private static LibraryType.Field fieldOf(FieldInfo fi) {
        return new LibraryType.Field(fi.getName(), fi.getModifiers(), fi.getTypeSignatureOrTypeDescriptor().toString(),
                fi.getTypeDescriptor().toString(), fi.hasAnnotation(DEPRECATED), since(fi.getAllAnnotationInfo()));
    }

    private static final String DEPRECATED = Deprecated.class.getName();

    /** The {@code @Since} value written on an element itself, {@code ""} when it carries none. */
    private static String since(AnnotationInfoList annotations) {
        AnnotationInfo annotation = annotations.directOnly().get(LibraryType.SINCE);
        if (annotation == null) return "";
        Object value = annotation.getParameterValues().getValue("value");
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static List<String> names(List<? extends HasName> infos) {
        return infos.stream().map(HasName::getName).toList();
    }

    /** Records a jar's classes, then marks the derived caches for a rebuild. */
    private synchronized void store(String jar, List<LibraryType> classes) {
        index.put(jar, classes);
        namesByJar.put(Path.of(jar).toAbsolutePath().normalize(),
                classes.stream().map(LibraryType::name).collect(java.util.stream.Collectors.toSet()));
        cachesDirty = true;
    }

    private synchronized boolean isKnown(String jar) {
        return index.containsKey(jar) || deferred.containsKey(jar);
    }

    private synchronized void forget(String jar) {
        index.remove(jar);
        deferred.remove(jar);
        namesByJar.remove(Path.of(jar).toAbsolutePath().normalize());
        cachesDirty = true;
    }

    private synchronized void defer(String jar, Set<String> packages) {
        deferred.put(jar, packages);
        cachesDirty = true;
    }

    /**
     * Takes {@code jar} from a fresh cache: deferred when its package list says what it holds, read whole
     * (and the list written) when there is none yet. False when there is no cache it can use.
     */
    private boolean fromCache(String jar, Path cacheFile) {
        if (!isCacheFresh(jar, cacheFile)) return false;
        Set<String> packages = readPackages(cacheFile);
        if (packages != null) {
            defer(jar, packages);
            return true;
        }
        List<LibraryType> cached = loadFromFile(cacheFile);
        if (cached == null) return false;
        store(jar, cached);
        savePackages(jar);
        return true;
    }

    /** Reads a deferred jar's cache into the index, scanning the jar again if the cache went bad meanwhile. */
    private synchronized void loadDeferred(String jar) {
        deferred.remove(jar);
        List<LibraryType> cached = loadFromFile(getCacheFileForJar(jar));
        if (cached != null) {
            store(jar, cached);
        } else {
            indexJar(jar);
            saveJar(jar);
        }
    }

    private synchronized void ensureCaches() {
        // The prefixes are asked for every time, not captured: a plugin bound into the open project widens
        // them, and the caches built before it was installed hide everything it brought.
        Set<String> prefixes = everything ? Set.of() : Set.copyOf(allowedPackagePrefixes.get());
        if (!cachesDirty && prefixes.equals(prefixesInUse)) return;
        for (String jar : List.copyOf(deferred.keySet())) {
            if (isWanted(deferred.get(jar), prefixes)) loadDeferred(jar);
        }
        cachesDirty = false;
        prefixesInUse = prefixes;
        // Only allowed-package classes are surfaced to the user; transitive deps and the SDK's internal
        // package stay indexed per-jar (for any future resolution use) but never reach the menus.
        allTypesCache = index.values().stream().flatMap(List::stream).filter(ci -> isAllowed(ci, prefixes)).toList();
        bySimpleName.clear();
        byQualifiedName.clear();
        for (LibraryType type : allTypesCache) {
            bySimpleName.putIfAbsent(type.simpleName(), type); // first wins, matching old findFirst()
            byQualifiedName.putIfAbsent(type.name(), type);
        }
        staticUtilityCache = allTypesCache.stream()
                .filter(type -> type.methods().stream().anyMatch(m ->
                        m.isPublic() && m.isStatic() && !m.returnType().equals("void")))
                .toList();
    }

    // =========================================================================
    // SERIALIZATION — per-jar files (JSON of LibraryType)
    // =========================================================================

    /**
     * Bumped whenever what a cache file holds changes, so the change actually reaches an existing
     * installation. Freshness is decided by mtime ({@link #isCacheFresh}), and a cache written before the
     * change is <em>newer</em> than the unchanged jar — so it would be loaded forever, missing whatever the
     * change records. {@code v2} = annotation info enabled (for {@code @Deprecated}); {@code v3} = Studio's
     * own {@link LibraryType} records in place of ClassGraph's JSON (2026-10-18).
     */
    private static final String CACHE_FORMAT = "v3";

    /**
     * The record components, never the {@code isPublic()} a record answers from them: those are derived and
     * would read back as properties no record has.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * Derives a stable cache file path for a given jar.
     * e.g. ~/.cache/botmaker/jars/somelib-1.0.0.jar.v3.json
     */
    public static Path getCacheFileForJar(String jarPath) {
        String jarFileName = Path.of(jarPath).getFileName().toString();
        return jarsDir().resolve(jarFileName + "." + CACHE_FORMAT + ".json");
    }

    private static Path jarsDir() {
        return CacheDirs.cacheRoot().resolve("jars");
    }

    /** The packages a cache holds, one per line, beside it: {@code somelib-1.0.0.jar.v3.packages}. */
    static Path packagesFileFor(Path cacheFile) {
        String name = cacheFile.getFileName().toString();
        return cacheFile.resolveSibling(name.substring(0, name.length() - ".json".length()) + ".packages");
    }

    /**
     * The packages {@code cacheFile} holds, or null when no list was written since it was: a cache an older
     * Studio wrote, or one rewritten by one after this list, is read whole once and the list written then.
     */
    private static Set<String> readPackages(Path cacheFile) {
        Path list = packagesFileFor(cacheFile);
        try {
            if (!Files.exists(list)
                    || Files.getLastModifiedTime(list).compareTo(Files.getLastModifiedTime(cacheFile)) < 0) {
                return null;
            }
            return Set.copyOf(Files.readAllLines(list));
        } catch (IOException e) {
            return null;
        }
    }

    /** Writes the package list of a jar in memory beside its cache. Called after the cache itself is written. */
    private void savePackages(String jarPath) {
        Path list = packagesFileFor(getCacheFileForJar(jarPath));
        try {
            Files.write(list, getTypesForJar(jarPath).stream().map(LibraryType::packageName)
                    .distinct().sorted().toList());
        } catch (IOException e) {
            System.err.println("Failed to save the package list for " + jarPath + ": " + e.getMessage());
        }
    }

    /**
     * Deletes the files in the cache directory that are not this {@link #CACHE_FORMAT}'s: older {@code .json}
     * and {@code .ser} copies no Studio of this format reads, 350 of 550 files on the machine the profiler ran
     * on.
     */
    static void sweepStaleCaches() {
        Path dir = jarsDir();
        if (!Files.isDirectory(dir)) return;
        String json = "." + CACHE_FORMAT + ".json";
        String packages = "." + CACHE_FORMAT + ".packages";
        try (var stream = Files.list(dir)) {
            stream.filter(p -> {
                        String name = p.getFileName().toString();
                        return Files.isRegularFile(p) && !name.endsWith(json) && !name.endsWith(packages);
                    })
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException e) { /* best effort */ }
                    });
        } catch (IOException e) {
            System.err.println("Failed to sweep old jar caches: " + e.getMessage());
        }
    }

    /**
     * A cached scan is valid only if it exists and is at least as new as the jar it was built from. The
     * cache file is keyed by jar file <em>name</em> ({@link #getCacheFileForJar}); a reused SNAPSHOT jar
     * (e.g. {@code botmaker-sdk-1.3.1-SNAPSHOT.jar}) is overwritten in place on every local rebuild while
     * keeping the same name, so without this mtime check its cache would never be regenerated and the
     * editor would show a stale SDK API. On any stat failure we treat the cache as stale and re-index.
     */
    private static boolean isCacheFresh(String jarPath, Path cacheFile) {
        try {
            if (!Files.exists(cacheFile)) return false;
            return Files.getLastModifiedTime(cacheFile).toMillis()
                    >= Files.getLastModifiedTime(Path.of(jarPath)).toMillis();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Saves the indexed classes of a single jar to its dedicated .json file.
     */
    public void saveJar(String jarPath) {
        List<LibraryType> types;
        synchronized (this) {
            types = index.get(jarPath);
        }
        if (types == null) return;

        Path cacheFile = getCacheFileForJar(jarPath);
        try {
            Files.createDirectories(cacheFile.getParent());
            MAPPER.writeValue(cacheFile.toFile(), types);
            savePackages(jarPath);
            System.out.println("Saved cache: " + cacheFile.getFileName()
                    + " (" + getTypesForJar(jarPath).size() + " types)");
        } catch (IOException e) {
            System.err.println("Failed to save cache for " + jarPath + ": " + e.getMessage());
        }
    }

    /**
     * Saves all currently indexed jars to their individual cache files.
     */
    public void saveAll() {
        for (String jarPath : index.keySet()) {
            saveJar(jarPath);
        }
    }

    /**
     * Loads the classes of a single cache file. Returns null if the file is missing, corrupt, or incompatible.
     */
    private static List<LibraryType> loadFromFile(Path cacheFile) {
        try {
            return MAPPER.readValue(cacheFile.toFile(), new TypeReference<List<LibraryType>>() {});
        } catch (Exception e) {
            System.err.println("Failed to load cache " + cacheFile.getFileName()
                    + ", will re-index: " + e.getMessage());
            return null;
        }
    }

    /**
     * Deletes the cache file for a specific jar (forces re-indexing on next load).
     */
    public static void invalidateJar(String jarPath) {
        Path cacheFile = getCacheFileForJar(jarPath);
        try {
            Files.deleteIfExists(cacheFile);
            Files.deleteIfExists(packagesFileFor(cacheFile));
            System.out.println("Invalidated cache for: " + Path.of(jarPath).getFileName());
        } catch (IOException e) {
            System.err.println("Failed to invalidate cache for " + jarPath + ": " + e.getMessage());
        }
    }

    /**
     * Deletes all per-jar cache files under the jars/ subdirectory.
     */
    public static void clearAllCaches() {
        Path jarsDir = jarsDir();
        if (!Files.exists(jarsDir)) return;
        try (var stream = Files.walk(jarsDir)) {
            stream.filter(p -> p.toString().endsWith(".json") || p.toString().endsWith(".packages"))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException e) { /* best effort */ }
                    });
            System.out.println("Cleared all jar caches.");
        } catch (IOException e) {
            System.err.println("Failed to clear caches: " + e.getMessage());
        }
    }
}
