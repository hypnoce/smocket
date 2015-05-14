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

package org.donarproject.smocket.windows;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import org.donarproject.smocket.PeriodicBufferedOutputStream;

import java.io.IOException;
import java.util.Random;

public class TestServer {
    public static void main(String[] args) throws IOException, InterruptedException {
        Random rnd = new Random(System.nanoTime());
        int size = 50000 / 4;
        final byte[][] values = new byte[size][];
        for (int i = 0; i < size; ++i) {
            values[i] = new byte[354 * 2];
            rnd.nextBytes(values[i]);
        }
        WinNT.HANDLE handel = SMKernel32.INSTANCE.CreateNamedPipe("\\\\.\\pipe\\testpipe", SMKernel32.PIPE_ACCESS_DUPLEX,
                SMKernel32.PIPE_WAIT,
                SMKernel32.PIPE_UNLIMITED_INSTANCES, 1 << 24, 1 << 24,
                0, null);
        if (WinBase.INVALID_HANDLE_VALUE.equals(handel)) {
            NativeUtils.throwIOException("Cannot create named pipe", Kernel32.INSTANCE.GetLastError());
        } else {
            boolean success = SMKernel32.INSTANCE.ConnectNamedPipe(handel, null);
            if (!success) {
                SMKernel32.INSTANCE.DisconnectNamedPipe(handel);
                Kernel32.INSTANCE.CloseHandle(handel);
                NativeUtils.throwIOException("Wait for client pipe to connect failed", Kernel32.INSTANCE.GetLastError());
            }


            //        BufferedReader bufferRead = new BufferedReader(new InputStreamReader(System.in));
            System.out.println("Ready");
            long bytesSent = 0;
            long time = 0;
            try (PeriodicBufferedOutputStream os = new PeriodicBufferedOutputStream(new PipeServerOutputStream(handel), 8192 * 16)) {

                //        byte[] toto = bufferRead.readLine().concat("\n").getBytes();
                //        bytesSent += toto.length;
                //        os.write(toto);

                time = System.nanoTime();
                for (int i = 0; i < 600; ++i)
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
                System.out.println((bytesSent) + "B in " + (time / 1000000) + " ms");
                System.out.println("Throughput : " + (bytesSent / 1000000.) / (time / 1000000000.) + " MB/s");
            }
        }
    }
}
