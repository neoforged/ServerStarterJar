package net.neoforged.serverstarterjar;

import java.lang.instrument.Instrumentation;

class Agent {
    static Instrumentation instrumentation;

    // Used by -javaagent dev testing
    public static void premain(String args, Instrumentation instrumentation) {
        agentmain(args, instrumentation);
    }

    // Used by the Launcher-Agent-Class manifest entry
    public static void agentmain(String args, Instrumentation instrumentation) {
        Agent.instrumentation = instrumentation;
    }
}
