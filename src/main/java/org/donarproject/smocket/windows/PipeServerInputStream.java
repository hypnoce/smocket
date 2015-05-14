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
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static com.sun.jna.platform.win32.WinNT.HANDLE;

public class PipeServerInputStream extends InputStream {
    private final HANDLE pipeHandle;
    private final IntByReference readCount = new IntByReference();

    private boolean closed;
    private final Object closeLock = new Object();

    public PipeServerInputStream(HANDLE pipeHandle) {
        this.pipeHandle = pipeHandle;
    }

    @Override
    public int read() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocateDirect(1);
        int read = 0;
        while (read != 1) {
            boolean success = Kernel32.INSTANCE.ReadFile(pipeHandle, buffer, 1, readCount, null);
            if (!success) {
                NativeUtils.throwIOException("", Kernel32.INSTANCE.GetLastError());
            }
            read += readCount.getValue();
            readCount.setValue(0);
        }
        return buffer.get();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocateDirect(len);
        boolean success = Kernel32.INSTANCE.ReadFile(pipeHandle, buffer, len, readCount, null);
        if (!success) {
            NativeUtils.throwIOException("", Kernel32.INSTANCE.GetLastError());
        }
        readCount.setValue(0);
        buffer.get(b, off, len);
        return readCount.getValue();
    }

    @Override
    public void close() throws IOException {
        synchronized (closeLock) {
            if (isClosed()) {
                return;
            }
            closed = true;
            SMKernel32.INSTANCE.FlushFileBuffers(pipeHandle);
            SMKernel32.INSTANCE.DisconnectNamedPipe(pipeHandle);
            Kernel32.INSTANCE.CloseHandle(pipeHandle);
            super.close();
        }
    }

    public boolean isClosed() {
        synchronized (closeLock) {
            return closed;
        }
    }
}
