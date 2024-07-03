package net.neoforged.serverstarterjar;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.module.FindException;
import java.lang.module.InvalidModuleDescriptorException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Main {
    private static final char ESCAPE = (char) 92; // \\
    private static final char SPACE = ' ';
    private static final char QUOTES = '"';
    private static final char SINGLE_QUOTES = '\'';
    private static final OperatingSystem OS = System.getProperty("os.name").startsWith("Windows") ? OperatingSystem.WINDOWS : OperatingSystem.NIX;
    private static final MethodHandle loadModule;
    private static final MethodHandle appendClassPath;
    private static final MethodHandle SET_bootLayer;
    private static final MethodHandle SET_installedProviders;
    private static final MethodHandle loadInstalledProviders;

    static {
        // Open the needed packages below to ourselves
        open(ModuleLayer.boot().findModule("java.base").orElseThrow(), "java.lang", Main.class.getModule());
        open(ModuleLayer.boot().findModule("java.base").orElseThrow(), "jdk.internal.loader", Main.class.getModule());
        export(ModuleLayer.boot().findModule("java.base").orElseThrow(), "jdk.internal.loader", Main.class.getModule());
        open(ModuleLayer.boot().findModule("java.base").orElseThrow(), "java.nio.file.spi", Main.class.getModule());

        var lookup = MethodHandles.lookup();
        try {
            var builtinCL = lookup.findClass("jdk.internal.loader.BuiltinClassLoader");
            loadModule = lookup.findVirtual(builtinCL, "loadModule", MethodType.methodType(void.class, ModuleReference.class));
            appendClassPath = MethodHandles.privateLookupIn(builtinCL, MethodHandles.lookup()).findVirtual(builtinCL, "appendClassPath", MethodType.methodType(void.class, String.class));
            SET_bootLayer = MethodHandles.privateLookupIn(System.class, lookup).unreflectSetter(System.class.getDeclaredField("bootLayer"));

            SET_installedProviders = MethodHandles.privateLookupIn(FileSystemProvider.class, lookup).findStaticSetter(FileSystemProvider.class, "installedProviders", List.class);
            loadInstalledProviders = MethodHandles.privateLookupIn(FileSystemProvider.class, lookup).findStatic(FileSystemProvider.class, "loadInstalledProviders", MethodType.methodType(List.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws Throwable {
        try {
            mainLaunch(args);
        } catch (FindException findEx) {
            if (findEx.getCause() instanceof InvalidModuleDescriptorException err) {
                throw handleInvalidJava(err);
            }
            throw findEx;
        } catch (InvalidModuleDescriptorException error) {
            // We catch Java version errors and rethrow them with a more friendly message
            throw handleInvalidJava(error);
        }
    }

    private static InvalidModuleDescriptorException handleInvalidJava(InvalidModuleDescriptorException error) {
        if (error.getMessage() != null) {
            var matcher = Pattern.compile("Unsupported major\\.minor version (?<version>\\d+)\\.\\d+").matcher(error.getMessage());
            if (matcher.find()) {
                int expected = Integer.parseInt(matcher.group(1));

                // Java 8 is 52
                var ex = new InvalidModuleDescriptorException("A minimum Java version of " + (8 + (expected - 52)) + " is required, but the current Java version is " + Main8.JAVA_VERSION);
                ex.setStackTrace(new StackTraceElement[0]);
                ex.addSuppressed(error);
                return ex;
            }
        }

        return error;
    }

    public static void mainLaunch(String[] starterArgs) throws Throwable {
        var startArgs = new ArrayList<>(Arrays.asList(starterArgs));

        URL installerUrl = null;
        if (startArgs.contains("--installer")) {
            var installer = startArgs.get(startArgs.indexOf("--installer") + 1);
            startArgs.remove("--installer");
            startArgs.remove(installer);

            if (installer.startsWith("https://")) {
                installerUrl = URI.create(installer).toURL();
            } else {
                installerUrl = URI.create("https://maven.neoforged.net/releases/net/neoforged/neoforge/" + installer + "/neoforge-" + installer + "-installer.jar").toURL();
            }
        }

        // Attempt to locate the run.bat/run.sh file
        final var runPath = Path.of(OS.runFile);
        if (Files.notExists(runPath)) {
            // If it doesn't exist, attempt to find a file whose name ends in "installer.jar" and run it as an installer
            System.err.println("Failed to find run file at " + runPath + ", attempting to run installer");
            if (!runInstaller(installerUrl)) {
                System.exit(1);
            }
        }

        final var args = getStartupArguments(runPath);
        if (args == null) {
            System.err.println("Failed to find startup arguments using run script path " + runPath);
            System.exit(1);
        }

        // If we're able to find a jar in the invocation, load that jar on the boot CP as an automatic module, and invoke it
        // Note: the correct way of loading it would have been as an unnamed module, but I have no idea how to do that
        var jar = findValue(args, "-jar");
        if (jar != null) {
            var jarFile = new File(jar);
            System.out.println("Launching in jar mode, using jar: " + jarFile.getAbsolutePath());

            var cp = readClasspathAttribute(jarFile);
            cp.add(0, jarFile.toPath());

            // Update the java.class.path sys prop with the jar and its Class-Path
            var cpProperty = new StringBuilder(System.getProperty("java.class.path"));

            var systemCl = ClassLoader.getSystemClassLoader();
            for (Path path : cp) {
                var absolute = path.toAbsolutePath().toString();
                cpProperty.append(File.pathSeparatorChar).append(absolute);
                appendClassPath.invoke(systemCl, absolute);
            }

            System.setProperty("java.class.path", cpProperty.toString());
        }

        // Otherwise, go back to trying to find the module path
        else {
            final var pathArg = findValue(args, "-p");
            if (pathArg == null) {
                System.err.println("Could not find module path (specified by -p)");
                System.exit(1);
                return;
            }

            final var bootPath = installModulePath(getModulePath(pathArg));

            // The add-opens/add-exports will only work with a module path anyway
            findValues(args, "--add-opens").stream()
                    .map(arg -> arg.split("="))
                    .forEach(toOpen -> open(
                            bootPath.layer().findModule(toOpen[0].split("/")[0]).orElseThrow(),
                            toOpen[0].split("/")[1],
                            bootPath.layer().findModule(toOpen[1]).orElseThrow()
                    ));
            findValues(args, "--add-exports").stream()
                    .map(arg -> arg.split("="))
                    .forEach(toExport -> export(
                            bootPath.layer().findModule(toExport[0].split("/")[0]).orElseThrow(),
                            toExport[0].split("/")[1],
                            (bootPath.layer().findModule(toExport[1]).orElseThrow())
                    ));

            // The args file specifies "--add-modules ALL-MODULE-PATH" which is completely useless now, so we ignore it
            findValue(args, "--add-modules");

            // Update the boot path
            SET_bootLayer.invokeExact(bootPath.layer());
        }

        // Clear installed providers so the JiJ provider can be found
        {
            @SuppressWarnings("unchecked")
            final List<FileSystemProvider> newProviders = (List<FileSystemProvider>) loadInstalledProviders.invokeExact();
            // Insert default provider at the start of the list
            newProviders.add(0, FileSystems.getDefault().provider());
            // Update the installed providers
            SET_installedProviders.invokeExact(newProviders);
        }

        // Parse the system properties
        var sysProps = args.stream().filter(arg -> arg.startsWith("-D")).toList();
        sysProps.forEach(args::remove);
        sysProps.stream()
                .map(arg -> arg.substring("-D".length()).split("=", 2))
                .forEach(arg -> System.setProperty(arg[0], arg[1]));

        final String mainName;

        // If we're starting a jar, the main class is specified in the manifest
        if (jar != null) {
            mainName = getMain(new File(jar));
            if (mainName == null) {
                System.err.println("Startup jar " + jar + " doesn't specify a Main-Class");
                System.exit(1);
                return;
            }
        }

        // Otherwise it's the next argument
        else {
            mainName = args.remove(0);
        }

        final Method main;
        try {
            main = Class.forName(mainName).getDeclaredMethod("main", String[].class);
        } catch (Exception e) {
            throw new Exception("Failed to find main class \"" + mainName + "\"", e);
        }

        // Pass any args specified to the start jar to MC
        args.addAll(startArgs);

        // If the main class isn't exported, export it so that we can access it
        if (!main.getDeclaringClass().getModule().isExported(main.getDeclaringClass().getPackageName())) {
            export(main.getDeclaringClass().getModule(), main.getDeclaringClass().getPackageName(), Main.class.getModule());
        }

        try {
            main.invoke(null, new Object[] { args.toArray(String[]::new) });
        } catch (InvocationTargetException exception) {
            // The reflection will cause all exceptions to be wrapped in an InvocationTargetException
            throw exception.getCause();
        }
    }

    private static void export(Module module, String pkg, Module to) {
        Agent.instrumentation.redefineModule(
                module,
                Set.of(),
                Map.of(pkg, Set.of(to)),
                Map.of(),
                Set.of(),
                Map.of()
        );
    }

    private static void open(Module module, String pkg, Module to) {
        Agent.instrumentation.redefineModule(
                module,
                Set.of(),
                Map.of(),
                Map.of(pkg, Set.of(to)),
                Set.of(),
                Map.of()
        );
    }

    private static boolean runInstaller(@Nullable URL installerUrl) throws Throwable {
        Path installer = null;

        if (installerUrl != null) {
            var onSlash = installerUrl.getPath().split("/");
            installer = Path.of(onSlash[onSlash.length - 1]);
            System.err.println("Downloading installer from " + installerUrl + " to " + installer.toAbsolutePath());
            try (var stream = installerUrl.openStream()) {
                Files.copy(stream, installer);
            }
        } else {
            try (final var stream = Files.find(Path.of("."), 1, (path, basicFileAttributes) -> path.getFileName().toString().endsWith("installer.jar"))) {
                var inst = stream.findFirst();
                if (inst.isPresent()) {
                    installer = inst.get();
                }
            }
        }

        if (installer != null) {
            System.err.println("Found installer " + installer.toAbsolutePath());

            var mainName = getMain(installer.toFile());
            if (mainName == null) {
                System.err.println("Installer file doesn't specify Main-Class");
                return false;
            }

            var classLoader = new URLClassLoader(new URL[]{ installer.toUri().toURL() });

            var mainClass = classLoader.loadClass(mainName);
            System.err.println("Running installer...");

            mainClass.getDeclaredMethod("main", String[].class).invoke(null, (Object) new String[] { "--installServer" });

            System.err.println("Installer finished");
            classLoader.close();
            return true;
        }

        return false;
    }

    @Nullable
    private static String getMain(File file) throws IOException {
        try (var jar = new JarFile(file)) {
            var manifest = jar.getManifest();
            return manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
        }
    }

    private static List<Path> readClasspathAttribute(File file) throws IOException {
        var paths = new ArrayList<Path>();
        try (var jar = new JarFile(file)) {
            var manifest = jar.getManifest();
            var value = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (value != null) {
                StringTokenizer st = new StringTokenizer(value);
                while (st.hasMoreTokens()) {
                    paths.add(Path.of(st.nextToken()));
                }
            }
        }
        return paths;
    }

    private static List<String> findValues(List<String> args, String argument) {
        var lst = new ArrayList<String>();
        String val;
        while ((val = findValue(args, argument)) != null) lst.add(val);
        return lst;
    }

    @Nullable
    private static String findValue(List<String> args, String argument) {
        int idx = args.indexOf(argument);
        if (idx >= 0) {
            args.remove(idx);
            return args.remove(idx);
        }
        return null;
    }

    private static ModuleLayer.Controller installModulePath(Path[] path) throws Throwable {
        final var systemCl = ClassLoader.getSystemClassLoader();
        final var finder = ModuleFinder.of(path);
        final var allModules = finder.findAll();
        for (ModuleReference module : allModules) {
            loadModule.invoke(systemCl, module);
        }
        return ModuleLayer.defineModules(
                ModuleLayer.boot().configuration().resolve(
                        finder, ModuleFinder.of(), allModules
                                .stream().map(mr -> mr.descriptor().name())
                                .collect(Collectors.toSet())
                ),
                List.of(ModuleLayer.boot()),
                s -> ClassLoader.getSystemClassLoader()
        );
    }

    private static Path[] getModulePath(String path) {
        return Arrays.stream(path.split(File.pathSeparator))
                .map(Path::of)
                .toArray(Path[]::new);
    }

    @Nullable
    private static List<String> getStartupArguments(Path runPath) throws IOException {
        var command = getCommand(runPath);
        if (command == null) return null;

        var startupArgs = new ArrayList<>(command);
        // Remove the java invocation
        startupArgs.remove(0);

        // Remove the special arguments used to pass the script args to the java invocation
        startupArgs.remove(OS.passthroughArg);

        for (String part : command) {
            if (part.startsWith("@")) {
                var idx = startupArgs.indexOf(part);
                // Remove the file reference from the args
                startupArgs.remove(idx);

                // And add its contents instead
                var itr = Files.readAllLines(Path.of(part.substring(1)))
                        .stream().filter(str -> !str.startsWith("#")) // Ignore comments
                        .flatMap(arg -> toArgs(arg).stream()).iterator();
                while (itr.hasNext()) {
                    startupArgs.add(idx++, itr.next());
                }
            }
        }

        // Remove any -X arguments since we can't set them as the JVM is already initialised
        startupArgs.removeIf(arg -> arg.startsWith("-X"));
        return startupArgs;
    }

    @Nullable
    private static List<String> getCommand(Path path) {
        try {
            final var contents = Files.readAllLines(path);
            for (String content : contents) {
                if (content.isBlank() || OS.comment.test(content)) continue;
                var command = toArgs(content);
                if (OS.relevantCommand.test(command)) return command;
            }
            System.err.println("Failed to find start command in file " + path);
            return null;
        } catch (IOException e) {
            System.err.println("Failed to read contents of " + OS.runFile + " file at " + path);
            return null;
        }
    }

    private static List<String> toArgs(String str) {
        final List<String> args = new ArrayList<>();
        StringBuilder current = null;
        char enclosing = 0;

        final char[] chars = str.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            final boolean isEscaped = i > 0 && chars[i - 1] == ESCAPE;
            final char ch = chars[i];
            if (ch == SPACE && enclosing == 0 && current != null) {
                args.add(current.toString());
                current = null;
                continue;
            }

            if (!isEscaped) {
                if (ch == enclosing) {
                    args.add(current.toString());
                    enclosing = 0;
                    current = null;
                    continue;
                } else if ((ch == QUOTES || ch == SINGLE_QUOTES) && (current == null || current.toString().isBlank())) {
                    current = new StringBuilder();
                    enclosing = ch;
                    continue;
                }
            }

            if (ch != ESCAPE) {
                if (current == null) current = new StringBuilder();
                current.append(ch);
            }
        }

        if (current != null && enclosing == 0) {
            args.add(current.toString());
        }

        return args;
    }

    private enum OperatingSystem {
        // On windows we're interested in the normal "java" invocation, or if explicit, in any invocation of the java exes
        WINDOWS("run.bat", c -> c.startsWith("@") || c.startsWith("REM "), s -> s.get(0).equals("java") || s.get(0).endsWith("javaw.exe") || s.get(0).endsWith("java.exe"), "%*"),
        NIX("run.sh", c -> c.startsWith("#"), s -> s.get(0).endsWith("java"), "$@");

        public final String runFile;
        public final Predicate<String> comment;
        public final Predicate<List<String>> relevantCommand;
        public final String passthroughArg;

        OperatingSystem(String runFile, Predicate<String> comment, Predicate<List<String>> relevantCommand, String passthroughArg) {
            this.runFile = runFile;
            this.comment = comment;
            // But we're not interested in Forge's only-java check
            this.relevantCommand = relevantCommand.and(Predicate.not(line -> line.contains("--onlyCheckJava")));
            this.passthroughArg = passthroughArg;
        }
    }
}
