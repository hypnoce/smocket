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

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class SMServerSocket implements Closeable {
    private final static Logger logger = Logger.getLogger(SMServerSocket.class.getName());
    private final Object closeLock = new Object();
    private final WatchService watchService;

    final String port;
    final Path host;

    private boolean closed = false;

    FileChannel fc;
    FileLock fl;

    final Pattern pattern;

    public SMServerSocket(String host, String port) throws IOException {
        logger.fine("Starting SM server on : " + host + ":" + port);
        watchService = FileSystems.getDefault().newWatchService();
        this.port = port;
        this.host = Paths.get(host);
        Path f = this.host.resolve(port);
        this.pattern = Pattern.compile(port + "_[a-z0-9\\-]*");
        if (Files.exists(f, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Socket " + port + " is already registered in " + host);
        } else {
            fc = SMUtils.createDeleteOnExitFile(f);
            fl = fc.lock();
            this.host.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
        }
    }

    public Iterable<SMSocket> accept() throws IOException, InterruptedException {
        if (isClosed())
            throw new IOException("Server socket is closed");
        if (fl != null) {
            fl.release();
            fl = null;
        }
        Set<SMSocket> result = new HashSet<>();
        for (; ; ) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException x) {
                return null;
            }
            List<WatchEvent<?>> events = key.pollEvents();
            for (WatchEvent<?> event : events) {
                WatchEvent.Kind<?> kind = event.kind();

                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path filename = ev.context();

                    /* Ensure file name is relative */
                    if (filename.isAbsolute()) {
                        filename = filename.getFileName();
                    }
                    String name = filename.toString();

                    if (pattern.matcher(name).matches()) {
                        Path out = host.resolve(name + "_out");
                        Path in = host.resolve(name + "_in");
                        SMSocket socket = null;
                        try {
                            logger.fine("Accepted : " + name);
                            socket = new SMSocket(host, port, name, out, in);
                        } catch (TimeoutException t) {
                            key.reset();
                        }
                        if (socket != null) {
                            result.add(socket);
                        }
                    }
                }
            }

            if (result.size() > 0) {
                key.reset();
                return result;
            }

            boolean valid = key.reset();
            if (!valid) {
                break;
            }

        }
        return null;
    }

    public void close() {
        synchronized (closeLock) {
            if (!closed) {
                closed = true;
                try {
                    fc.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    watchService.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public boolean isClosed() {
        synchronized (closeLock) {
            return closed;
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException, TimeoutException {
        SMServerSocket serverSocket = new SMServerSocket("E:\\smocket_tmp", "7777");
        Random rnd = new Random(System.nanoTime());
        int size = 500000 / 4;
        final byte[][] values = new byte[size][];
        for (int i = 0; i < size; ++i) {
            values[i] = new byte[354 * 2];
            rnd.nextBytes(values[i]);
        }
        logger.log(Level.INFO, "Ready");
        for (; ; ) {
            Iterable<SMSocket> sockets = serverSocket.accept();
            for (final SMSocket _socket : sockets) {
                new Thread(Thread.currentThread().getThreadGroup(), () -> {
                    long bytesSent = 0;
                    long time = 0;
                    try (SMSocket socket = _socket;
                         OutputStream smo = socket.getOutputStream();
                         PeriodicBufferedOutputStream bufferedOutputStream = new PeriodicBufferedOutputStream(smo, 8192 * 16, 10)) {
                        time = System.nanoTime();
                        for (int i = 0; i < 100; ++i) {
                            for (byte[] bytes : values) {
                                final int length = bytes.length;
                                bytesSent += length;
                                bufferedOutputStream.write(bytes, 0, length);
                            }
                        }
                        bufferedOutputStream.flush();
                    } catch (Throwable t) {
                        t.printStackTrace();
                    } finally {
                        time = (System.nanoTime() - time);
                        logger.log(Level.INFO, (bytesSent) + "B in " + (time / 1000000.) + " ms");
                        logger.log(Level.INFO, "Throughput : " + (bytesSent / 1000000.) / (time / 1000000000.) + " MB/s");
                    }

                }).start();
            }
        }
    }
}
