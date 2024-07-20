# ServerStarterJar
ServerStarterJar is a same-process wrapper around the Forge (1.17+) and NeoForge server run scripts.  

This project brings back the `server.jar` file in old versions, that can be used when a server host does not support custom launch scripts (such as the `run.sh` file).

## Installation
To use the starter, simply download the `server.jar` file of [the latest release](https://github.com/NeoForged/serverstarterjar/releases/latest/download/server.jar)
and place it in the folder the `run.sh`/`run.bat` scripts are located (you may need to run the NeoForge installer first with the `--install-server` argument; check the [installer section](#running-the-installer) for more information).  
Afterwards, you may run the jar as an executable (i.e. `java -jar server.jar`).

> [!IMPORTANT]  
> Any JVM arguments (such as `-Xmx`) placed in the `user_jvm_args.txt` file will *not* be picked up by default. You'll need to run `java @user_jvm_args.txt -jar server.jar`.

## Compatible Versions
This starter is compatible with all [MinecraftForge](https://minecraftforge.net) versions since 1.17, and with all [NeoForge](https://neoforged.net) versions.

## Running the installer
If the starter cannot find the run scripts, it will attempt to run an installer.  
It will first try to run the first file ending with `-installer.jar` from the folder.  

You may specify an installer to download instead using the `--installer` option (i.e. `java -jar server.jar --installer 21.0.46-beta`).  
The installer specified can either be a link to an installer (i.e. `https://maven.neoforged.net/releases/net/neoforged/neoforge/21.0.46-beta/neoforge-21.0.46-beta-installer.jar`)
or a **NeoForge version** to download the installer for (i.e. `21.0.46-beta`).

> [!NOTE]
The installer will only be run if the starter cannot find the run scripts. You may force it to run if the installer version and the installed version differ using `--installer-force`.

## How it works
Below you will find the steps the start goes through to launch a modular NeoForge environment:
1. search the folder the starter was invoked in for the `run.sh` (*nix) / `run.bat` (Windows) file
2. find the `java` invocation in the run script
3. extract and parse any argument file references (`@[...]`)
4. if a `-jar` argument is found:
    - put it on the system classpath alongside all entries specified in the `Class-Path` manifest entry
    - update the `java.class.path` system property with the new entries
5. otherwise
   - parse the module path (`-p`) argument, and load the jars on a new module layer, which will replace the boot layer
   - parse the `--add-exports`/`--add-opens` arguments and replicate them using Java instrumentation module redefinition
   - ignore the `--add-modules` arguments as they're irrelevant since the module path is fully loaded as the boot layer
6. set the system properties (`-D<name>=<value>`) specified by the args file
7. consider the `Main-Class` attribute of the jar / the first remaining argument the main class, load it, and retrieve its `main(String[])` method
8. append any arguments specified to the starter executable to the remaining arguments 9
9. launch Minecraft with the remaining arguments
