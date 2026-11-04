package dev.r2hack.agent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/**
 * Asks the main app something over the bridge and waits for its answer: the request carries a
 * "seq" number, and the main app's reply carries the same one back.
 */
final class MainRequests {
    interface Sender {
        /** Sends msg to the main app; false if it isn't connected. */
        boolean toMain(JSONObject msg);
    }

    private final Sender sender;
    private final AtomicInteger nextSeq = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, BlockingQueue<JSONObject>> waiting = new ConcurrentHashMap<>();

    MainRequests(Sender sender) {
        this.sender = sender;
    }

    /** Sends msg and returns the reply, or null if the main app isn't connected or doesn't answer in time. */
    JSONObject ask(JSONObject msg, long timeoutMs) throws Exception {
        int seq = nextSeq.getAndIncrement();
        BlockingQueue<JSONObject> q = new ArrayBlockingQueue<>(1);
        waiting.put(seq, q);
        try {
            if (!sender.toMain(msg.put("seq", seq))) return null;
            return q.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            waiting.remove(seq);
        }
    }

    /** A reply from the main app; returns false if nobody was waiting for it (any more). */
    boolean onReply(JSONObject reply) {
        BlockingQueue<JSONObject> q = waiting.get(reply.optInt("seq", -1));
        return q != null && q.offer(reply);
    }
}
