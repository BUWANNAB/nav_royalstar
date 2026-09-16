package com.ant.robot.model.domain;

import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class PriorityMessageQueue {
    private final BlockingQueue<PriorityMessage> queue;
    private static final int DEFAULT_CAPACITY = 100;

    public PriorityMessageQueue() {
        this.queue = new PriorityBlockingQueue<>(DEFAULT_CAPACITY);
    }

    public void putBinaryMessage(byte[] message, int priority) throws InterruptedException {
        queue.put(new PriorityMessage(message, priority));
    }

    public void putJsonMessage(JSONObject message, int priority) throws InterruptedException {
        queue.put(new PriorityMessage(message, priority));
    }

    public PriorityMessage takeMessage() throws InterruptedException {
        return queue.take(); // 会阻塞直到有元素
    }

    public Optional<PriorityMessage> pollMessage() {
        PriorityMessage msg = queue.poll();
        return Optional.ofNullable(msg);
    }

    public Optional<PriorityMessage> pollMessage(long timeout, TimeUnit unit)
            throws InterruptedException {
        PriorityMessage msg = queue.poll(timeout, unit);
        return Optional.ofNullable(msg);
    }



    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int size() {
        return queue.size();
    }
}