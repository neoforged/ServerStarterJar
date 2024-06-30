# ServerStarterJar
ServerStarterJar is a same-process wrapper around the Forge (1.17-1.20.1) and NeoForge server run scripts.  

This project brings back the `server.jar` file in old versions, that can be used when a server host does not support custom launch scripts (such as the `run.sh` file).

## Installation
To use the starter, simply download the `server.jar` file of [the latest release](https://github.com/NeoForged/serverstarterjar/releases/latest/download/server.jar)
and place it in the folder the `run.sh`/`run.bat` scripts are located (you need to run the NeoForge installer first with the `--install-server` argument).  
Afterwards, you may run the jar as an executable (i.e. `java -jar server.jar`).

> [!IMPORTANT]  
> Any JVM arguments (such as `-Xmx`) placed in the `user_jvm_args.txt` file will *not* be picked up by default. You'll need to run `java @user_jvm_args.txt -jar server.jar`.
