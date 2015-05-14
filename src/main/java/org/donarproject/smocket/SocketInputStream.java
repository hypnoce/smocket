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
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SocketInputStream {
    private final static Logger logger = Logger.getLogger(SocketInputStream.class.getName());

    public static void main(String[] args) throws IOException {
        long totalTime = System.nanoTime();
        for (int i = 0; i < 60; ++i) {
            long time = 0;
            long byteReceived = 0;
            try (Socket socket = new Socket("127.0.0.1", 8888);
                 InputStream in = socket.getInputStream()) {
                final int bufferSize = 8192 * 16;
                byte[] buffer = new byte[bufferSize];
                int read;
                time = System.nanoTime();
                while ((read = in.read(buffer, 0, bufferSize)) != -1) {
                    byteReceived += read;
                }
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                logger.log(Level.INFO, "Socket " + i + " : " + (byteReceived) + "B in " + ((System.nanoTime() - time) / 1000000.) + " ms");
            }
        }
        System.out.println((System.nanoTime() - totalTime) / 1000000. + " ms");
    }
}
