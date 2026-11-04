package dev.r2hack.main;

import android.util.Log;

/**
 * Runs one action at a time on its own thread. Starting an action cancels the one in progress,
 * and a cancelled action always ends with stop, so "stop" really stops. Routines (RoutineRunner)
 * run on it too, so the same rules hold for them.
 */
final class Sequencer {
    interface Job {
        void run() throws Exception;
    }

    private static final String TAG = "R2Seq";
    private final Runnable stop;
    private volatile Thread worker;
    private volatile String running = "idle";
    private volatile Runnable onChange;

    Sequencer(Runnable stop) {
        this.stop = stop;
    }

    /** Called (on any thread) whenever what's running changes, e.g. to report it to the agent. */
    void setOnChange(Runnable r) {
        onChange = r;
    }

    private void changed() {
        Runnable r = onChange;
        if (r != null) r.run();
    }

    String running() {
        return running;
    }

    synchronized boolean isRunning(String name) {
        return worker != null && worker.isAlive() && name.equals(running);
    }

    /** Starts job as name, cancelling (and stopping) whatever was running. */
    synchronized void start(final String name, final Job job) {
        cancel();
        running = name;
        changed();
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    job.run();
                } catch (InterruptedException cancelled) {
                    // cancel() sends stop
                } catch (Exception e) {
                    Log.w(TAG, "action " + name + " failed", e);
                    stop.run();
                } finally {
                    // No lock here: cancel() holds it while waiting for this thread to end.
                    if (worker == Thread.currentThread()) {
                        running = "idle";
                        changed();
                    }
                }
            }
        }, "action-" + name);
        worker.setDaemon(true);
        worker.start();
    }

    /** Cancels the running action, if any, and stops the robot. */
    synchronized void cancel() {
        Thread w = worker;
        worker = null;
        if (w != null && w.isAlive()) {
            w.interrupt();
            try {
                w.join(1000);
            } catch (InterruptedException ignored) {
            }
            stop.run();
        }
        boolean was = !"idle".equals(running);
        running = "idle";
        if (was) changed();
    }

    /** Pause inside a job; throws when the job is cancelled. */
    static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }
}
