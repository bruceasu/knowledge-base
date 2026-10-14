package me.asu.http;


import me.asu.log.Log;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Suk.
 * @since 2018/8/13
 */

public class NamedThreadFactory implements ThreadFactory {

    private final AtomicInteger id;
    private final String name;
    private final boolean daemon;
    private final int priority;
    private final ThreadGroup group;

    public NamedThreadFactory(String name) {
        this(name, false, 5);
    }

    public NamedThreadFactory(String name, boolean daemon) {
        this(name, daemon, 5);
    }

    public NamedThreadFactory(String name, int priority) {
        this(name, false, priority);
    }

    public NamedThreadFactory(String name, boolean daemon, int priority) {
        this.id = new AtomicInteger();
        this.name = name + " #";
        this.daemon = daemon;
        this.priority = priority;
        SecurityManager s = System.getSecurityManager();
        this.group = s == null ? Thread.currentThread().getThreadGroup() : s.getThreadGroup();
    }

    @Override
    public Thread newThread(Runnable r) {
        String newName = this.name + this.id.getAndIncrement();
        Thread t = new Thread(this.group, r, newName);

        try {
            if (t.isDaemon() != this.daemon) {
                t.setDaemon(this.daemon);
            }

            if (t.getPriority() != this.priority) {
                t.setPriority(this.priority);
            }
        } catch (Exception ignore) {
            ;
        }

        Log.debug("Create new thread: " + t.getName());
        return t;
    }

    public ThreadGroup getThreadGroup() {
        return this.group;
    }
}
