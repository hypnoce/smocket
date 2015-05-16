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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SMSocket implements Closeable {
    private final static Logger logger = Logger.getLogger(SMSocket.class.getName());
    private final Object closeLock = new Object();
    private final SMInputStream inputStream;
    private final SMOutputStream outputStream;

    private volatile boolean closed = false;

    private final Path host;

    /**
     * Handles a lock that will be released when the other end point is closed
     */
    private final FileChannel closeLocker;

    /**
     * Handles the lock that will be released when this socket is closed.
     */
    private final FileLock remoteCloseLocker;


    private final Set<FileChannel> fcs = new HashSet<>();

    public SMSocket(final String hostname, final String port) throws IOException, InterruptedException, TimeoutException {
        Path serverPath = Paths.get(hostname, port);
        if (!Files.exists(serverPath)) {
            throw new IOException("Cannot connect to " + hostname + ":" + port);
        }
        String sessionId = UUID.randomUUID().toString();
        host = Paths.get(hostname);
        final String suffix = port + "_" + sessionId;
        Set<StandardOpenOption> options = new HashSet<>();
        options.add(StandardOpenOption.WRITE);
        options.add(StandardOpenOption.READ);
        /* Contention point to ensure correct JVM-local concurrent access to a non shared resource. It ensures the server side is ready to receive new connections */
        synchronized (SMSocket.class) {
            try (FileChannel fc = serverPath.getFileSystem().provider().newFileChannel(serverPath, options)) {
                fc.lock();
            }
        }
        Path file = host.resolve(suffix);
        fcs.add(SMUtils.createDeleteOnExitFile(file));
        ConcurrentSMUtils utils = ConcurrentSMUtils.getInstance(host, port);
        Path out = utils.waitForFileCreation(host, suffix + "_out", 5, TimeUnit.SECONDS);

        outputStream = new SMOutputStream(out);

        Path client_lock = host.resolve(suffix + "_client.lock");
        FileChannel lockChannel = SMUtils.createDeleteOnExitFile(client_lock);
        remoteCloseLocker = lockChannel.lock(0, 1, false);
        fcs.add(lockChannel);

        Path rack = host.resolve(suffix + "_client_ack");
        FileChannel rackChannel = SMUtils.createDeleteOnExitFile(rack);
        fcs.add(rackChannel);

        Path in = utils.waitForFileCreation(host, suffix + "_in", 5, TimeUnit.SECONDS);
        closeLocker = in.getFileSystem().provider().newFileChannel(host.resolve(suffix + "_server.lock"), new HashSet<OpenOption>() {{
            add(StandardOpenOption.READ);
            add(StandardOpenOption.WRITE);
        }});
        inputStream = new SMInputStream(in, closeLocker);
    }

    SMSocket(final Path host, final String port, final String sessionId, final Path in, final Path out) throws IOException, InterruptedException, TimeoutException {
        try {
            ConcurrentSMUtils utils = ConcurrentSMUtils.getInstance(host, port);
            this.host = host;
            FileChannel outFC = SMUtils.createExchangeFile(out);
            fcs.add(outFC);
            utils.waitForFileCreation(out.getParent(), out.getFileName().toString());


            outputStream = new SMOutputStream(outFC);
            Path serverLock = host.resolve(sessionId + "_server.lock");
            FileChannel lockChannel = SMUtils.createDeleteOnExitFile(serverLock);
            remoteCloseLocker = lockChannel.lock(0, 1, false);
            fcs.add(lockChannel);

            FileChannel inFC = SMUtils.createExchangeFile(in);
            fcs.add(inFC);
            utils.waitForFileCreation(in.getParent(), in.getFileName().toString());

            Path rack = utils.waitForFileCreation(host, sessionId + "_client_ack", 50, TimeUnit.SECONDS);
            closeLocker = rack.getFileSystem().provider().newFileChannel(host.resolve(sessionId + "_client.lock"), new HashSet<OpenOption>() {{
                add(StandardOpenOption.READ);
                add(StandardOpenOption.WRITE);
            }});
            inputStream = new SMInputStream(inFC, closeLocker);
        } catch (Throwable t) {
            t.printStackTrace();
            close();
            throw t;
        }
    }

    public void close() throws IOException {
        synchronized (closeLock) {
            if (!isClosed()) {
                if (remoteCloseLocker != null && remoteCloseLocker.isValid() && remoteCloseLocker.channel().isOpen())
                    remoteCloseLocker.release();
                if (closeLocker != null && closeLocker.isOpen())
                    closeLocker.close();
                closed = true;
                if (inputStream != null) {
                    inputStream.close();
                }
                if (outputStream != null) {
                    outputStream.close();
                }
                for (FileChannel fc : fcs) {
                    fc.close();
                }
                fcs.clear();
            }
        }
    }

    public boolean isClosed() {
        synchronized (closeLock) {
            return closed;
        }
    }

    public InputStream getInputStream() throws IOException {
        if (isClosed()) {
            throw new IOException("Socket is closed");
        }
        return inputStream;
    }

    public OutputStream getOutputStream() throws IOException {
        if (isClosed()) {
            throw new IOException("Socket is closed");
        }
        return outputStream;
    }

    public static void main(String[] args) throws InterruptedException, IOException, TimeoutException {
        long totalTime = System.nanoTime();
        for (int i = 0; i < 60; ++i) {
            _do(i);
        }
        logger.log(Level.INFO, (System.nanoTime() - totalTime) / 1000000. + " ms");
    }

    public static void main2(String[] args) throws InterruptedException, IOException, TimeoutException {
        long totalTime = System.nanoTime();
        ExecutorService service = Executors.newFixedThreadPool(16);
        Set<Future> futures = new HashSet<>();
        for (int i = 0; i < 10; ++i) {
            final int j = i;
            Future<Object> future = service.submit(new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    _do(j);
                    return null;
                }
            });
            futures.add(future);
        }
        for (Future future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
        }
        service.shutdown();
        service.awaitTermination(1000, TimeUnit.SECONDS);
        logger.log(Level.INFO, (System.nanoTime() - totalTime) / 1000000. + " ms");
    }

    private static void _do(int id) throws IOException, TimeoutException, InterruptedException {
        try (SMSocket socket = new SMSocket("E:\\smocket_tmp", "7777")) {
            InputStream smi = socket.getInputStream();
            long bytesReceived = 0;
            final int bufferSize = 8192 * 16;
            long time = 0;
            try {
                byte[] buffer = new byte[bufferSize];
                bytesReceived += smi.read(buffer, 0, bufferSize);
                int read;
                time = System.nanoTime();
                while ((read = smi.read(buffer, 0, bufferSize)) != -1) {
                    bytesReceived += read;
                }
                time = System.nanoTime() - time;
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                logger.log(Level.INFO, "Socket " + id + " : " + (bytesReceived) + "B in " + (time / 1000000.) + " ms");
            }
        } catch (InterruptedException t) {
            Thread.currentThread().interrupt();
            throw t;
        }
    }
}
