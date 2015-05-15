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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PeriodicBufferedOutputStream extends FilterOutputStream implements Runnable {

    private final byte buf[];
    private final byte buf2[];

    protected volatile int writeCursor = 0;
    protected volatile int flushCursor = 0;

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
        buf2 = new byte[size];
        scheduler.scheduleWithFixedDelay(this, period, period, TimeUnit.MILLISECONDS);
    }

    private void flushBuffer() throws IOException {
        try {
//            lock.writeLock().lock();
            int _flush = flushCursor;
            int _write = writeCursor;
            while (_flush < _write) {
                out.write(buf, _flush, _write - _flush);
                _flush = _write;
                _write = writeCursor;
            }
            flushCursor = 0;
//            int _count = count;
//            if (_count > 0) {
//                out.write(buf, 0, _count);
//                while (count != _count) {
//                    out.write(buf, _count, count - _count);
//                    _count = count;
//                }
//                count = 0;
//            }
        } finally {
//            lock.writeLock().unlock();
        }
    }

    public void write(int b) throws IOException {
        throw new RuntimeException();
//        try {
//            lock.readLock().lock();
//            if (count >= buf.length) {
//                flushBuffer();
//            }
//            buf[count++] = (byte) b;
//        } finally {
//            lock.readLock().unlock();
//        }
    }

    public void write(byte b[], int off, int len) throws IOException {
        if (len >= buf.length) {
//            flushBuffer();
            int i = 0;
            for(; i < len - buf.length ; i += buf.length) {
                addToBuffer(b, i, buf.length);
            }
            addToBuffer(b, i, len % buf.length);
//            try {
//                lock.writeLock().lock();
//                out.write(b, off, len);
//            } finally {
//                lock.writeLock().unlock();
//            }
            return;
        }
//        final int _count = writeCursor;
//        if (len > buf.length - _count) {
//            flush();
////            flushBuffer();
//        }
        try {
            addToBuffer(b, off, len);
//            lock.readLock().lock();
//            System.arraycopy(b, off, buf, _count, len);
//            count += len;
        } finally {
//            lock.readLock().unlock();
        }
    }

    private void addToBuffer(byte b[], int off, int len) throws IOException {
        if (writeCursor + len > buf.length){
            flush();
            writeCursor = 0;
        }
        final int _count = writeCursor;
        System.arraycopy(b, off, buf, _count, len);
        writeCursor += len;
//        buf[_count]
    }

    public void flush() throws IOException {
        while (flushCursor > 0){}
//        flushBuffer();
//        out.flush();
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
        flush();
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        super.close();
    }
}
