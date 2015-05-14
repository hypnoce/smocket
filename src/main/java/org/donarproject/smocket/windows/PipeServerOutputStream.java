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
import java.io.OutputStream;

import static com.sun.jna.platform.win32.WinNT.HANDLE;

public class PipeServerOutputStream extends OutputStream {
    private final HANDLE pipeHandle;
    private final IntByReference writeCountBuf = new IntByReference();

    private boolean closed;
    private final Object closeLock = new Object();

    public PipeServerOutputStream(HANDLE pipeHandle) {
        this.pipeHandle = pipeHandle;
    }

    @Override
    public void write(int b) throws IOException {
        int written = 0;
        byte[] buf = new byte[]{(byte) b};
        while (written != 1) {
            boolean success = Kernel32.INSTANCE.WriteFile(pipeHandle, buf, 1, writeCountBuf, null);
            if (!success) {
                NativeUtils.throwIOException("", Kernel32.INSTANCE.GetLastError());
            }
            written += writeCountBuf.getValue();
        }
    }

    @Override
    public void write(final byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if ((off < 0) || (off > b.length) || (len < 0) ||
                ((off + len) > b.length) || ((off + len) < 0)) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return;
        }
        int written = 0;
        byte[] buf = b;
        if (off != 0) {
            buf = new byte[len];
            System.arraycopy(b, off, buf, 0, len);
        }
        while (written != len) {
            boolean success = Kernel32.INSTANCE.WriteFile(pipeHandle, buf, len, writeCountBuf, null);
            if (!success) {
                NativeUtils.throwIOException("", Kernel32.INSTANCE.GetLastError());
            }
            written += writeCountBuf.getValue();
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (closeLock) {
            if(isClosed()) {
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
