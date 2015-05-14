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

import com.google.common.cache.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

class ConcurrentSMUtils implements Runnable {

    private static LoadingCache<Path, ConcurrentSMUtils> instances = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterAccess(10, TimeUnit.SECONDS)
            .removalListener(
                    new RemovalListener<Path, ConcurrentSMUtils>() {
                        @Override
                        public void onRemoval(RemovalNotification<Path, ConcurrentSMUtils> toRemove) {
                            ConcurrentSMUtils remove = toRemove.getValue();
                            if (remove != null) {
                                remove.watchThread.interrupt();
                                remove.created.clear();
                                remove.deleted.clear();
                                try {
                                    remove.watchService.close();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    })
            .build(
                    new CacheLoader<Path, ConcurrentSMUtils>() {
                        public ConcurrentSMUtils load(Path key) throws IOException {
                            Path port = key.getFileName();
                            return new ConcurrentSMUtils(key.getParent(), port.toString());
                        }
                    });

    private final static ScheduledExecutorService cleanupScheduler = Executors.newScheduledThreadPool(1, new ThreadFactory() {
        private ThreadGroup group;
        {
            SecurityManager s = System.getSecurityManager();
            group = (s != null) ? s.getThreadGroup() :
                    Thread.currentThread().getThreadGroup();
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r, "SMUtils clean up");
            t.setDaemon(true);
            return t;
        }
    });

    private static ScheduledFuture currentCleanupTask = null;
    private static Runnable cleanupThread = new Runnable() {
        @Override
        public void run() {
            synchronized (ConcurrentSMUtils.class) {
                instances.cleanUp();
            }
        }
    };

    public static ConcurrentSMUtils getInstance(Path path, String port) throws IOException {
        synchronized (ConcurrentSMUtils.class) {
            ConcurrentSMUtils result;
            if (currentCleanupTask == null) {
                currentCleanupTask = cleanupScheduler.scheduleWithFixedDelay(cleanupThread, 10, 10, TimeUnit.SECONDS);
            }
            try {
                result = instances.get(path.resolve(port));
            } catch (ExecutionException e) {
                throw new IOException(e);
            }
            return result;
        }
    }

    public static ConcurrentSMUtils getInstance(String path, String port) throws IOException {
        return getInstance(Paths.get(path), port);
    }

    private final Set<Path> created;
    private final Set<Path> deleted;
    private final Thread watchThread;
    private final Path watchingPath;
    private final String port;
    private final WatchService watchService;

    private ConcurrentSMUtils(Path host, String port) throws IOException {
        this.created = new HashSet<>();
        this.deleted = new HashSet<>();
        watchingPath = host;
        this.port = port;
        this.watchService = FileSystems.getDefault().newWatchService();
        watchingPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);
        this.watchThread = new Thread(this, "Watching " + watchingPath.toString());
        watchThread.setDaemon(true);
        watchThread.start();
    }

    @Override
    public void run() {
        for (; ; ) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException x) {
                return;
            } catch (ClosedWatchServiceException c) {
                return;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path filename = ev.context();
                /* Ensure file name is relative */
                if (filename.isAbsolute()) {
                    filename = filename.getFileName();
                }
                String name = filename.toString();
                if (name.startsWith(port)) {
                    Path child = watchingPath.resolve(filename);
                    synchronized (watchingPath) {
                        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                            created.add(child);
                        } else {
                            deleted.add(child);
                        }
                        watchingPath.notifyAll();
                    }
                }
            }
            boolean valid = key.reset();
            if (!valid) {
                break;
            }
        }
    }

    Path waitForFileCreation(Path directory, String waitingFor) throws IOException, InterruptedException, TimeoutException {
        return waitForFileCreation(directory, waitingFor, 0, TimeUnit.MILLISECONDS);
    }

    Path waitForFileCreation(Path directory, String waitingFor, long timeout, TimeUnit unit) throws IOException, InterruptedException, TimeoutException {
        return waitForFileEvent(directory, waitingFor, StandardWatchEventKinds.ENTRY_CREATE, timeout, unit);
    }

    Path waitForFileDeletion(Path directory, String waitingFor) throws IOException, InterruptedException, TimeoutException {
        return waitForFileDeletion(directory, waitingFor, 0, TimeUnit.MILLISECONDS);
    }

    Path waitForFileDeletion(Path directory, String waitingFor, long timeout, TimeUnit unit) throws IOException, InterruptedException, TimeoutException {
        return waitForFileEvent(directory, waitingFor, StandardWatchEventKinds.ENTRY_DELETE, timeout, unit);
    }

    Path waitForFileEvent(final Path directory, final String waitingFor, final WatchEvent.Kind<?> eventKind, final long timeout, TimeUnit unit) throws IOException, InterruptedException, TimeoutException {
        Path path = directory.resolve(waitingFor);
        if (eventKind == StandardWatchEventKinds.ENTRY_CREATE && (created.remove(path) || Files.exists(path))) {
            return path;
        } else if (eventKind == StandardWatchEventKinds.ENTRY_DELETE && (deleted.remove(path) || !Files.exists(path))) {
            return path;
        }
        final long timeOutMilli = unit.toMillis(timeout);
        synchronized (watchingPath) {
            long time = System.nanoTime();
            long timeOffset = 0;
            while (!(eventKind == StandardWatchEventKinds.ENTRY_CREATE ? created.remove(path) : deleted.remove(path))) {
                watchingPath.wait(timeOutMilli - timeOffset);
                if (timeOutMilli != 0) {
                    if ((timeOffset = (((System.nanoTime() - time)) / 1000000l)) >= timeOutMilli) {
                        boolean successful = (eventKind == StandardWatchEventKinds.ENTRY_CREATE ? created.remove(path) : deleted.remove(path));
                        if (!successful) {
                            throw new TimeoutException();
                        } else {
                            break;
                        }
                    }
                }
            }
        }
        return path;
    }
}
