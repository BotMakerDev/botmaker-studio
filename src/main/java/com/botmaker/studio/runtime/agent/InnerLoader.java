package com.botmaker.studio.runtime.agent;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * The loader of the run agent's inner half: its own jar first, then the bot's classpath. Child first, so the wire,
 * the log writer, Jackson and the plugin-pom reader are always the ones this Studio was built with, whatever
 * versions the bot carries; what the inner jar does not hold (the contract, the bootstrap classes, the JDK) comes
 * from the bot, so a channel handed to a plugin is of the plugin's own {@code RunChannel}.
 */
final class InnerLoader extends URLClassLoader {

    static {
        registerAsParallelCapable();
    }

    InnerLoader(URL jar, ClassLoader parent) {
        super("botmaker-run-agent", new URL[]{jar}, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> found = findLoadedClass(name);
            if (found == null) {
                try {
                    found = findClass(name);
                } catch (ClassNotFoundException notOurs) {
                    found = getParent().loadClass(name);
                }
            }
            if (resolve) resolveClass(found);
            return found;
        }
    }

    @Override
    public URL getResource(String name) {
        URL own = findResource(name);
        return own != null ? own : super.getResource(name);
    }
}
