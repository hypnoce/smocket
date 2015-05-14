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
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SocketOutputStream {
    private final static Logger logger = Logger.getLogger(SocketOutputStream.class.getName());

    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(8888);
        Random rnd = new Random(System.nanoTime());
        int size = 500000 / 4;
        final byte[][] values = new byte[size][];
        for (int i = 0; i < size; ++i) {
            values[i] = new byte[354 * 2];
            rnd.nextBytes(values[i]);
        }

        logger.log(Level.INFO, "Ready");
        for (; ; ) {
            final Socket _socket = serverSocket.accept();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    long bytesSent = 0;
                    long time = 0;
                    try (Socket socket = _socket;
                         PeriodicBufferedOutputStream os = new PeriodicBufferedOutputStream(socket.getOutputStream(), 8192 * 16)){
                        time = System.nanoTime();
                        for (int i = 0; i < 100; ++i)
                            for (byte[] s : values) {
                                final int length = s.length;
                                bytesSent += length;
                                os.write(s, 0, length);
                            }
                        os.flush();
                    } catch (Throwable t) {
                        t.printStackTrace();
                    } finally {
                        time = System.nanoTime() - time;
                        logger.log(Level.INFO, (bytesSent) + "B in " + (time / 1000000) + " ms");
                        logger.log(Level.INFO, "Throughput : " + (bytesSent / 1000000.) / (time / 1000000000.) + " MB/s");
                    }
                }
            }).start();
        }
    }
}
