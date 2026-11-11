package com.coinspay.mc.core;

/** Platform logger: JUL on Bukkit and BungeeCord, SLF4J on Velocity. */
public interface Log {
    void info(String message);

    void warn(String message);

    void error(String message, Throwable error);
}
