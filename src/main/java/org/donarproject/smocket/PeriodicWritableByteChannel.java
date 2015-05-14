/*
 * Copyright (C) 2014 The Donar Project Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.donarproject.smocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Created by hypnoce on 12/05/2014.
 */
public class PeriodicWritableByteChannel implements Runnable, WritableByteChannel {
    protected ByteBuffer buf;

    protected WritableByteChannel out;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public PeriodicWritableByteChannel(WritableByteChannel out) {
        this(out, 8192);
    }

    public PeriodicWritableByteChannel(WritableByteChannel out, int size) {
        this(out, size, 500l);
    }

    public PeriodicWritableByteChannel(WritableByteChannel out, int size, long period) {
        this.out = out;
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }
        buf = ByteBuffer.allocateDirect(size);
        scheduler.scheduleWithFixedDelay(this, period, period, TimeUnit.MILLISECONDS);
    }

    private void flushBuffer() throws IOException {
        try {
            lock.writeLock().lock();
            if (buf.position() > 0) {
                buf.flip();
                out.write(buf);
            }
        } finally {
            buf.clear();
            lock.writeLock().unlock();
        }
    }

    public void flush() throws IOException {
        flushBuffer();
    }

    @Override
    public void run() {
        try {
            flushBuffer();
        } catch (IOException e) {
            try {
                close();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
    }

    @Override
    public boolean isOpen() {
        return out.isOpen();
    }

    @Override
    public void close() throws IOException {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        out.close();
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        int size = src.remaining();
        if (size >= buf.capacity()) {
            flushBuffer();
            try {
                lock.writeLock().lock();
                out.write(src);
            } finally {
                lock.writeLock().unlock();
            }
            return size;
        }
        if (size > buf.remaining()) {
            flushBuffer();
        }
        try {
            lock.readLock().lock();
            buf.put(src);
        } finally {
            lock.readLock().unlock();
        }
        return size;
    }
}
