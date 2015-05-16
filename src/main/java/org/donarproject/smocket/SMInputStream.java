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
import java.io.InputStream;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

public class SMInputStream extends InputStream implements SMStream {
    private final static int REDUCED_CACHE_LINE = CACHE_LINE - HEADER_SIZE;
    private final static int DOUBLE_REDUCED_CACHE_LINE = 2 * CACHE_LINE - HEADER_SIZE;

    private final FileChannel fc;
    private int available = 0;
    private int padding = 0;
    private FileLock fileLock;
    private MappedByteBuffer mbb;

    private boolean closed = false;
    private final Object closeLock = new Object();
    private final FileChannel closeGuard;

    public SMInputStream(Path address) throws IOException {
        this(address, null);
    }

    public SMInputStream(Path address, FileChannel closeGuard) throws IOException {
        Set<StandardOpenOption> options = new HashSet<>();
        options.add(StandardOpenOption.READ);
        options.add(StandardOpenOption.WRITE);
        options.add(StandardOpenOption.DELETE_ON_CLOSE);
        fc = FileSystems.getDefault().provider().newFileChannel(address, options);
        this.closeGuard = closeGuard;
        prepareBuffer();
    }

    public SMInputStream(FileChannel fc, FileChannel closeGuard) throws IOException {
        this.fc = fc;
        this.closeGuard = closeGuard;
        prepareBuffer();
    }

    private void prepareBuffer() throws IOException {
        mbb = fc.map(FileChannel.MapMode.READ_ONLY, 0, MAPPED_SIZE);
        mbb.order(ByteOrder.nativeOrder());
    }

    @Override
    public int read() throws IOException {
        if (!checkAvailable())
            return -1;
        --available;
        return mbb.get();
    }

    private boolean checkAvailable() throws IOException {
        if (available == 0) {
            readSize();
            if (available == 0) {
                return false;
            } else if(closeGuard != null) {
                try (FileLock lock = closeGuard.tryLock(0, 1, true)){
                    if(lock != null)
                        return false;
                }
            }
        }
        return true;
    }

    private void readSize() throws IOException {
        final MappedByteBuffer _mbb = mbb;
        int position = _mbb.position() + padding;
        if (position == MAPPED_SIZE)
            position = 0;
        _mbb.position(position);
        final FileLock _fileLock = fc.lock(position, HEADER_SIZE, true);
        if (fileLock != null && fileLock.isValid() && fc.isOpen()) {
            fileLock.release();
        }
        fileLock = _fileLock;
        available = _mbb.getInt();
        padding = getPadding(available);
    }

    private static int getPadding(int len) {
        if (len == 0)
            return 0;
        if (len <= REDUCED_CACHE_LINE) {
            return REDUCED_CACHE_LINE - len;
        } else if (len <= DOUBLE_REDUCED_CACHE_LINE) {
            return DOUBLE_REDUCED_CACHE_LINE - len;
        } else {
            final int reste = (len - REDUCED_CACHE_LINE) % CACHE_LINE;
            if (reste == 0) {
                return 0;
            }
            return CACHE_LINE - reste;
        }
    }

    @Override
    public int read(byte[] b) throws IOException {
        return super.read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return 0;
        }
        if (!checkAvailable())
            return -1;
        if (available < len) {
            len = available;
            available = 0;
        } else {
            available -= len;
        }
        mbb.get(b, off, len);
        return len;
    }

    @Override
    public long skip(long n) throws IOException {
        return super.skip(n);
    }

    @Override
    public int available() throws IOException {
        return available;
    }

    @Override
    public void close() throws IOException {
        synchronized (closeLock) {
            if (isClosed())
                return;
            closed = true;
            mbb = null;
            assert fileLock == null || fileLock.channel() == fc;
            if (fileLock != null && fileLock.isValid() && fc.isOpen()) {
                fileLock.release();
            }
            fc.close();
        }
    }

    boolean isClosed() {
        synchronized (closeLock) {
            return closed;
        }
    }

    @Override
    public synchronized void mark(int readlimit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public synchronized void reset() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    public static void main(String[] args) throws IOException {
        SMInputStream smi = new SMInputStream(Paths.get("E:\\exchange_file"));
        long bytesReceived = 0;
        final int bufferSize = 8192 * 16;
        try {
            byte[] buffer = new byte[bufferSize];
            bytesReceived += smi.read(buffer, 0, bufferSize);
            int read;
            long time = System.nanoTime();
            while ((read = smi.read(buffer, 0, bufferSize)) != -1) {
                bytesReceived += read;
            }
            time = System.nanoTime() - time;
            smi.close();
            System.out.println((bytesReceived) + "B in " + (time / 1000000.) + " ms");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}

