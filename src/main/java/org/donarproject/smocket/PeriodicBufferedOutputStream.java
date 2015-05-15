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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PeriodicBufferedOutputStream extends FilterOutputStream implements Runnable {

    private final byte buf[];

    protected volatile int writeCursor = 0;
    protected volatile int flushCursor = 0;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

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
        int _flush = flushCursor;
        int _write = writeCursor;
        while (_flush < _write) {
            out.write(buf, _flush, _write - _flush);
            _flush = _write;
            _write = writeCursor;
        }
        flushCursor = 0;
    }

    public void write(int b) throws IOException {
        if (writeCursor + 1 > buf.length){
            flush();
        }
        final int _count = writeCursor;
        buf[_count] = (byte)b;
        ++writeCursor;
    }

    public void write(byte b[], int off, int len) throws IOException {
        if (len >= buf.length) {
            int i = 0;
            for(; i < len - buf.length ; i += buf.length) {
                addToBuffer(b, i, buf.length);
            }
            addToBuffer(b, i, len % buf.length);
        } else {
            addToBuffer(b, off, len);
        }
    }

    private void addToBuffer(byte b[], int off, int len) throws IOException {
        if (writeCursor + len > buf.length){
            flush();
        }
        final int _count = writeCursor;
        System.arraycopy(b, off, buf, _count, len);
        writeCursor += len;
    }

    public void flush() throws IOException {
        while (flushCursor > 0){}
        writeCursor = 0;
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
