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

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PeriodicBufferedOutputStream extends FilterOutputStream implements Runnable {

    protected byte buf[];

    protected int count;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public PeriodicBufferedOutputStream(OutputStream out) {
        this(out, 8192);
    }

    public PeriodicBufferedOutputStream(OutputStream out, int size) {
        this(out, size, 500l);
    }

    public PeriodicBufferedOutputStream(OutputStream out, int size, long period) {
        super(out);
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }
        buf = new byte[size];
        scheduler.scheduleWithFixedDelay(this, period, period, TimeUnit.MILLISECONDS);
    }

    private void flushBuffer() throws IOException {
        try {
            lock.writeLock().lock();
            if (count > 0) {
                out.write(buf, 0, count);
                count = 0;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void write(int b) throws IOException {
        try {
            lock.readLock().lock();
            if (count >= buf.length) {
                flushBuffer();
            }
            buf[count++] = (byte) b;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void write(byte b[], int off, int len) throws IOException {
        if (len >= buf.length) {
            flushBuffer();
            try {
                lock.writeLock().lock();
                out.write(b, off, len);
            } finally {
                lock.writeLock().unlock();
            }
            return;
        }
        if (len > buf.length - count) {
            flushBuffer();
        }
        try {
            lock.readLock().lock();
            System.arraycopy(b, off, buf, count, len);
            count += len;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void flush() throws IOException {
        flushBuffer();
        out.flush();
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
    public void close() throws IOException {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        super.close();
    }
}
