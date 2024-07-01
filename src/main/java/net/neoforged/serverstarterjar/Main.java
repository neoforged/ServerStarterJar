package net.neoforged.serverstarterjar;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public class Main {
    public static final char ESCAPE = (char) 92; // \\
    public static final char SPACE = ' ';
    public static final char QUOTES = '"';
    public static final char SINGLE_QUOTES = '\'';
    public static final OperatingSystem OS = detectOs();
    public static final MethodHandle LOAD_MODULE;
    public static final MethodHandle SET_BOOT_LAYER;

    static {
        // Open the needed packages below to ourselves
        open(ModuleLayer.boot().findModule("java.base").orElseThrow(), "java.lang", Main.class.getModule());
        export(ModuleLayer.boot().findModule("java.base").orElseThrow(), "jdk.internal.loader", Main.class.getModule());

        var lookup = MethodHandles.lookup();
        try {
            LOAD_MODULE = lookup.unreflect(lookup.findClass("jdk.internal.loader.BuiltinClassLoader").getDeclaredMethod("loadModule", ModuleReference.class));
            SET_BOOT_LAYER = MethodHandles.privateLookupIn(System.class, lookup).unreflectSetter(System.class.getDeclaredField("bootLayer"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] starterArgs) throws Throwable {
        // Attempt to locate the run.bat/run.sh file
        final var runPath = Path.of(OS.runFile);
        if (Files.notExists(runPath)) {
            // If it doesn't exist, attempt to find a file whose name ends in "installer.jar" and run it as an installer
            System.err.println("Failed to find run file at " + runPath + ", attempting to run installer");
            if (!runInstaller()) {
                System.exit(1);
            }
        }

        final var argsFile = getArgsFile(runPath);
        if (argsFile == null) {
            System.exit(1);
        }

        final var args = Files.readAllLines(argsFile).stream()
                .flatMap(arg -> toArgs(arg).stream()).collect(Collectors.toCollection(ArrayList::new));

        final var pathArg = findValue(args, "-p");
        if (pathArg == null) {
            System.err.println("Could not find module path (specified by -p)");
            System.exit(1);
            return;
        }

        final var bootPath = installModulePath(getModulePath(pathArg));

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

        SET_BOOT_LAYER.invokeExact(bootPath.layer());

        var sysProps = args.stream().filter(arg -> arg.startsWith("-D")).toList();
        sysProps.forEach(args::remove);
        sysProps.stream()
                .map(arg -> arg.substring("-D".length()).split("=", 2))
                .forEach(arg -> System.setProperty(arg[0], arg[1]));

        // The args file specifies "--add-modules ALL-MODULE-PATH" which is completely useless now, so we ignore it
        findValue(args, "--add-modules");

        final var mainName = args.remove(0);

        final Method main;
        try {
            main = Class.forName(mainName).getDeclaredMethod("main", String[].class);
        } catch (Exception e) {
            throw new Exception("Failed to find main class \"" + mainName + "\"", e);
        }

        // Pass any args specified to the start jar to MC
        args.addAll(Arrays.asList(starterArgs));

        // If the main class isn't exported, export it so that we can access it
        if (!main.getDeclaringClass().getModule().isExported(main.getDeclaringClass().getPackageName())) {
            export(main.getDeclaringClass().getModule(), main.getDeclaringClass().getPackageName(), Main.class.getModule());
        }

        main.invoke(null, new Object[] { args.toArray(String[]::new) });
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

    private static boolean runInstaller() throws Throwable {
        try (final var stream = Files.find(Path.of("."), 1, (path, basicFileAttributes) -> path.getFileName().toString().endsWith("installer.jar"))) {
            var inst = stream.findFirst();
            if (inst.isPresent()) {
                var installer = inst.get();
                System.err.println("Found installer " + installer.toAbsolutePath());

                var installerJar = new JarFile(installer.toFile());
                var manifest = installerJar.getManifest();
                installerJar.close();
                var mainName = manifest.getMainAttributes().getValue("Main-Class");
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
        }

        return false;
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
        final var finder = ModuleFinder.of(path);
        final var allModules = finder.findAll();
        for (ModuleReference module : allModules) {
            LOAD_MODULE.invoke(ClassLoader.getSystemClassLoader(), module);
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
    private static Path getArgsFile(Path runPath) {
        var cmd = getCommand(runPath);
        if (cmd == null) return null;
        var command = toArgs(cmd);
        for (String part : command) {
            if (part.startsWith("@") && part.endsWith("/" + OS.argsFile)) {
                return Path.of(part.substring(1));
            }
        }
        System.err.println("Failed to find argument file in command " + command);
        return null;
    }

    @Nullable
    private static String getCommand(Path path) {
        try {
            final var contents = Files.readAllLines(path);
            for (String content : contents) {
                if (content.isBlank() || OS.irrelevantCommand.test(content)) continue;
                return content;
            }
            System.err.println("Failed to find start command in file " + path);
            return null;
        } catch (IOException e) {
            System.err.println("Failed to read contents of " + OS.runFile + " file at " + path);
            return null;
        }
    }

    public static List<String> toArgs(String str) {
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

    public static OperatingSystem detectOs() {
        if (System.getProperty("os.name").startsWith("Windows")) {
            return OperatingSystem.WINDOWS;
        }
        return OperatingSystem.NIX;
    }

    public enum OperatingSystem {
        WINDOWS("run.bat", s -> s.startsWith("@") || s.startsWith("REM "), "win_args.txt"),
        NIX("run.sh", s -> s.startsWith("#"), "unix_args.txt");

        public final String runFile;
        public final Predicate<String> irrelevantCommand;
        public final String argsFile;

        OperatingSystem(String runFile, Predicate<String> irrelevantCommand, String argsFile) {
            this.runFile = runFile;
            this.irrelevantCommand = irrelevantCommand;
            this.argsFile = argsFile;
        }
    }
}
