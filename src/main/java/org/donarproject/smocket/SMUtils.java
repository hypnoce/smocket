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
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

enum SMUtils {
    instance;

    WatchService watchService;

    SMUtils() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    Path waitForFileCreation(Path directory, String waitingFor) throws IOException, InterruptedException, TimeoutException {
        return waitForFileEvent(directory, waitingFor, StandardWatchEventKinds.ENTRY_CREATE, -1, null);
    }

    Path waitForFileCreation(Path directory, String waitingFor, long timeout, TimeUnit unit) throws IOException, InterruptedException, TimeoutException {
        return waitForFileEvent(directory, waitingFor, StandardWatchEventKinds.ENTRY_CREATE, timeout, unit);
    }

    Path waitForFileDeletion(Path directory, String waitingFor) throws IOException, InterruptedException, TimeoutException {
        return waitForFileEvent(directory, waitingFor, StandardWatchEventKinds.ENTRY_DELETE, -1, null);
    }

    Path waitForFileDeletion(Path directory, String waitingFor, long timeout, TimeUnit unit) throws IOException, InterruptedException, TimeoutException {
        return waitForFileEvent(directory, waitingFor, StandardWatchEventKinds.ENTRY_DELETE, timeout, unit);
    }

    synchronized Path waitForFileEvent(Path directory, String waitingFor, WatchEvent.Kind<?> eventKind, long timeout, TimeUnit unit) throws IOException, InterruptedException, TimeoutException {
        WatchKey watchKey = directory.register(watchService, eventKind);
        for (; ; ) {
            WatchKey key;
            if (eventKind == StandardWatchEventKinds.ENTRY_CREATE && Files.exists(directory.resolve(waitingFor))) {
                watchKey.cancel();
                return directory.resolve(waitingFor);
            } else if (eventKind == StandardWatchEventKinds.ENTRY_DELETE && !Files.exists(directory.resolve(waitingFor))) {
                watchKey.cancel();
                return directory.resolve(waitingFor);
            }
            if (timeout != -1) {
                key = watchService.poll(timeout, unit);
            } else {
                key = watchService.take();
            }

            if(key == null)
                throw new TimeoutException("Timeout exceed while waiting for " + directory.resolve(waitingFor).toString());
            if(key != watchKey) {
                continue;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                if (kind == eventKind) {
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path filename = ev.context();
                    /* Ensure file name is relative */
                    if (filename.isAbsolute()) {
                        filename = filename.getFileName();
                    }
                    String name = filename.toString();

                    if (waitingFor.equals(name)) {
                        key.cancel();
                        return directory.resolve(name);
                    }
                }

            }
            boolean valid = key.reset();
            if (!valid) {
                key.cancel();
                break;
            }
        }
        watchKey.cancel();
        return null;
    }

    static FileChannel createDeleteOnExitFile(Path path) throws IOException {
        Set<StandardOpenOption> options = new HashSet<>();
        options.add(StandardOpenOption.CREATE);
        options.add(StandardOpenOption.DELETE_ON_CLOSE);
        options.add(StandardOpenOption.WRITE);
        return FileSystems.getDefault().provider().newFileChannel(path, options);
    }

    static FileChannel createExchangeFile(Path path) throws IOException {
        Set<StandardOpenOption> options = new HashSet<>();
        options.add(StandardOpenOption.CREATE);
        options.add(StandardOpenOption.DELETE_ON_CLOSE);
        options.add(StandardOpenOption.WRITE);
        options.add(StandardOpenOption.READ);
        return FileSystems.getDefault().provider().newFileChannel(path, options);
    }
}
