package com.coinspay.mc.bukkit;

import com.coinspay.mc.core.Platform;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Bukkit scheduler on Spigot and Paper, region schedulers on Folia. The plugin compiles
 * against the 1.8.8 API, so the Folia calls go through reflection.
 */
abstract class Schedulers {

    abstract void async(Runnable task);

    abstract Platform.Cancellable repeatAsync(Runnable task, long periodSeconds);

    /** Where console commands may run: the main thread or the Folia global region. */
    abstract void global(Runnable task);

    /** On the thread that owns the entity: inventories and items of a player. */
    abstract void entity(Entity entity, Runnable task);

    static boolean folia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static Schedulers create(Plugin plugin) {
        return folia() ? new Folia(plugin) : new Classic(plugin);
    }

    static final class Classic extends Schedulers {
        private final Plugin plugin;

        Classic(Plugin plugin) {
            this.plugin = plugin;
        }

        @Override
        void async(Runnable task) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }

        @Override
        Platform.Cancellable repeatAsync(Runnable task, long periodSeconds) {
            final BukkitTask t = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, 40L, periodSeconds * 20L);
            return t::cancel;
        }

        @Override
        void global(Runnable task) {
            if (Bukkit.isPrimaryThread()) task.run();
            else Bukkit.getScheduler().runTask(plugin, task);
        }

        @Override
        void entity(Entity entity, Runnable task) {
            global(task);
        }
    }

    static final class Folia extends Schedulers {
        private final Plugin plugin;
        private final Object asyncScheduler;
        private final Object globalScheduler;
        private final Method runNow;
        private final Method runAtFixedRate;
        private final Method execute;
        private final Method cancel;
        private Method entityScheduler;
        private Method entityRun;

        Folia(Plugin plugin) {
            this.plugin = plugin;
            try {
                Object server = Bukkit.getServer();
                asyncScheduler = server.getClass().getMethod("getAsyncScheduler").invoke(server);
                globalScheduler = server.getClass().getMethod("getGlobalRegionScheduler").invoke(server);
                Class<?> asyncType = Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
                Class<?> globalType = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
                runNow = asyncType.getMethod("runNow", Plugin.class, Consumer.class);
                runAtFixedRate = asyncType.getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
                execute = globalType.getMethod("execute", Plugin.class, Runnable.class);
                cancel = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask").getMethod("cancel");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Folia schedulers are not available", e);
            }
        }

        @Override
        void async(Runnable task) {
            invoke(runNow, asyncScheduler, plugin, (Consumer<Object>) t -> task.run());
        }

        @Override
        Platform.Cancellable repeatAsync(Runnable task, long periodSeconds) {
            final Object t = invoke(runAtFixedRate, asyncScheduler, plugin, (Consumer<Object>) x -> task.run(), 2L, periodSeconds, TimeUnit.SECONDS);
            return () -> invoke(cancel, t);
        }

        @Override
        void global(Runnable task) {
            invoke(execute, globalScheduler, plugin, task);
        }

        @Override
        void entity(Entity entity, Runnable task) {
            try {
                if (entityScheduler == null) {
                    entityScheduler = entity.getClass().getMethod("getScheduler");
                    Class<?> type = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
                    entityRun = type.getMethod("run", Plugin.class, Consumer.class, Runnable.class);
                }
                Object scheduler = entityScheduler.invoke(entity);
                entityRun.invoke(scheduler, plugin, (Consumer<Object>) t -> task.run(), null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        private static Object invoke(Method m, Object target, Object... args) {
            try {
                return m.invoke(target, args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
