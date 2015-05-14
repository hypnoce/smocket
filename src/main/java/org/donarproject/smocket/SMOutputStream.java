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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class SMOutputStream extends OutputStream implements SMStream {
    private final static int REDUCED_CACHE_LINE = CACHE_LINE - HEADER_SIZE;
    private final static int DOUBLE_REDUCED_CACHE_LINE = 2 * CACHE_LINE - HEADER_SIZE;
    private final FileChannel fc;
    private int cursor;
    private FileLock fileLock;
    private MappedByteBuffer mbb;
    private boolean closed = false;
    private final Object closeLock = new Object();

    public SMOutputStream(Path address) throws IOException {
        Set<StandardOpenOption> options = new HashSet<>();
        options.add(StandardOpenOption.DELETE_ON_CLOSE);
        options.add(StandardOpenOption.WRITE);
        options.add(StandardOpenOption.READ);
        fc = FileSystems.getDefault().provider().newFileChannel(address, options);
        prepareBuffer();
    }

    public SMOutputStream(FileChannel fc) throws IOException {
        this.fc = fc;
        prepareBuffer();
    }

    private void prepareBuffer() throws IOException {
        mbb = fc.map(FileChannel.MapMode.READ_WRITE, 0, MAPPED_SIZE);
        mbb.order(ByteOrder.nativeOrder());
        cursor = 0;
        fileLock = fc.lock(cursor, HEADER_SIZE, false);
        mbb.putInt(0);
        mbb.position(0);
    }

    @Override
    public void write(int b) throws IOException {
        final MappedByteBuffer _mbb = mbb;
        final FileLock _fileLock;
        final FileLock _fileLock2;
        _fileLock = fc.lock(cursor + HEADER_SIZE, REDUCED_CACHE_LINE, false);
        _mbb.putInt(1);
        _mbb.put((byte) b);
        cursor += CACHE_LINE;
        _fileLock2 = fc.lock(cursor, HEADER_SIZE, true);
        if (fileLock != null) {
            fileLock.release();
        }
        fileLock = _fileLock2;
        _mbb.position(cursor);
        _mbb.putInt(0);
        _mbb.position(cursor);
        _fileLock.release();

    }

    @Override
    public void write(byte[] b) throws IOException {
        super.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if ((off < 0) || (off > b.length) || (len < 0) ||
                ((off + len) > b.length) || ((off + len) < 0)) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return;
        }
        final int paddedLength = getPaddedLength(len);
        if (paddedLength + cursor + HEADER_SIZE > MAPPED_SIZE) {
            int _len1 = MAPPED_SIZE - cursor - HEADER_SIZE;
            _write(b, off, _len1, _len1);
            write(b, off + _len1, len - _len1);
        } else {
            _write(b, off, len, paddedLength);
        }
    }

    private void _write(byte[] b, int off, int len, final int paddedLength) throws IOException {
        assert (paddedLength + HEADER_SIZE) % 64 == 0;
        final MappedByteBuffer _mbb = mbb;
        final FileLock _fileLock;
        final FileLock _fileLock2;
        _fileLock = fc.lock(cursor + HEADER_SIZE, paddedLength, false);
        cursor += HEADER_SIZE + paddedLength;
        if (cursor == MAPPED_SIZE) {
            cursor = 0;
        }
        assert cursor % 64 == 0;
        final int _cursor = cursor;
        _mbb.putInt(len);
        _mbb.put(b, off, len);
        _fileLock2 = fc.lock(_cursor, HEADER_SIZE, false);
        if (fileLock != null) {
            fileLock.release();
        }
        fileLock = _fileLock2;
        _mbb.position(_cursor);
        _mbb.putInt(0);
        _mbb.position(_cursor);
        _fileLock.release();
    }

    private static int getPaddedLength(int len) {
        if (len <= REDUCED_CACHE_LINE) {
            return REDUCED_CACHE_LINE;
        } else if (len <= DOUBLE_REDUCED_CACHE_LINE) {
            return DOUBLE_REDUCED_CACHE_LINE;
        } else {
            final int reste = (len - REDUCED_CACHE_LINE) % CACHE_LINE;
            if (reste == 0) {
                return len;
            }
            return len + CACHE_LINE - reste;
        }
    }

    @Override
    public void flush() throws IOException {
        super.flush();
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

    public static void main(String[] args) throws IOException, InterruptedException {
        Path path = Paths.get("E:\\exchange_file");
        if (!Files.exists(path)) {
            Files.createFile(path);
        }
        try (SMOutputStream smo = new SMOutputStream(path);
             PeriodicBufferedOutputStream bufferedOutputStream = new PeriodicBufferedOutputStream(smo, 8192 * 16, 10)) {
            Random rnd = new Random(System.nanoTime());
            int size = 500000 / 4;
            byte[][] values = new byte[size][];
            for (int i = 0; i < size; ++i) {
                values[i] = new byte[354 * 2];
                rnd.nextBytes(values[i]);
            }

            BufferedReader bufferRead = new BufferedReader(new InputStreamReader(System.in));
            System.out.println("Ready. Press enter to start");
            long bytesSent = 0;
            String toto = bufferRead.readLine().concat("\n");
            byte[] first = toto.getBytes();
            bytesSent += first.length;
            bufferedOutputStream.write(first);

            long time = System.nanoTime();
            for (int i = 0; i < 240; ++i) {
                for (byte[] bytes : values) {
                    final int length = bytes.length;
                    bytesSent += length;
                    bufferedOutputStream.write(bytes, 0, length);
                }
            }
            bufferedOutputStream.flush();
            time = (System.nanoTime() - time);
            System.out.println((bytesSent) + "B in " + (time / 1000000.) + " ms");
            System.out.println("Throughput : " + (bytesSent / 1000000.) / (time / 1000000000.) + " MB/s");
        }
    }
}
