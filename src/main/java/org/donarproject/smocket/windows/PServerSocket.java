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
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;

import static com.sun.jna.platform.win32.WinNT.HANDLE;

/**
 * A server pipe must be created using the CreateNamedPipe functions on Win32 platforms.
 */
public class PServerSocket {

    private final HANDLE acceptHandle;
    private final HANDLE acceptMutex;
    private final HANDLE acceptMutex2;

    public PServerSocket(String host, String port) throws IOException {
        final String pipeName = host + ":" + port;
        acceptHandle = createPipe(pipeName, -1, 16);
        acceptMutex = SMKernel32.INSTANCE.CreateMutex(null, false, pipeName);
        acceptMutex2 = SMKernel32.INSTANCE.CreateMutex(null, false, pipeName + "2");
    }

    public PSocket accept() throws IOException {
        SMKernel32.INSTANCE.ReleaseMutex(acceptMutex2);
        boolean success = SMKernel32.INSTANCE.ConnectNamedPipe(acceptHandle, null);
        if (!success) {
            int lastError = Kernel32.INSTANCE.GetLastError();
            SMKernel32.INSTANCE.DisconnectNamedPipe(acceptHandle);
            NativeUtils.throwIOException("Wait for client pipe to connect failed", lastError);
        }
        SMKernel32.INSTANCE.WaitForSingleObject(acceptMutex2, WinNT.INFINITE);
        UUID sessionId = UUID.randomUUID();
        HANDLE handle = createPipe(sessionId.toString(), 1, 8192);
        PSocket result = new PSocket(handle);
        byte[] buf = ByteBuffer.allocate(16).putLong(sessionId.getMostSignificantBits()).putLong(sessionId.getLeastSignificantBits()).array();
        IntByReference writeCountPointer = new IntByReference(0);
        int written = 0;
        do {
            success = Kernel32.INSTANCE.WriteFile(acceptHandle, buf, 16, writeCountPointer, null);
            if (!success) {
                int lastError = Kernel32.INSTANCE.GetLastError();
                SMKernel32.INSTANCE.DisconnectNamedPipe(acceptHandle);
                SMKernel32.INSTANCE.ReleaseMutex(acceptMutex2);
                NativeUtils.throwIOException("Error writing to pipe", lastError);
            }
            written += writeCountPointer.getValue();
        } while (written != 16);
        success = SMKernel32.INSTANCE.ConnectNamedPipe(handle, null);
        int lastError = Kernel32.INSTANCE.GetLastError();
        if (!success && lastError != WinError.ERROR_PIPE_CONNECTED) {
            SMKernel32.INSTANCE.DisconnectNamedPipe(handle);
            Kernel32.INSTANCE.CloseHandle(handle);
            SMKernel32.INSTANCE.ReleaseMutex(acceptMutex2);
            NativeUtils.throwIOException("Cannot connect to client pipe", lastError);
        }
        SMKernel32.INSTANCE.DisconnectNamedPipe(acceptHandle);
        return result;
    }

    HANDLE createPipe(String pipeName, int instanceCount, int bufferSize) throws IOException {
        HANDLE result = SMKernel32.INSTANCE.CreateNamedPipe("\\\\.\\pipe\\" + pipeName, SMKernel32.PIPE_ACCESS_DUPLEX,
                SMKernel32.PIPE_WAIT,
                instanceCount == -1 ? SMKernel32.PIPE_UNLIMITED_INSTANCES : instanceCount, bufferSize, bufferSize,
                0, null);
        if (WinBase.INVALID_HANDLE_VALUE.equals(result))
            NativeUtils.throwIOException("Cannot create pipe \"" + pipeName + "\"", Kernel32.INSTANCE.GetLastError());
        return result;
    }

    public void close() {
        if (acceptHandle != null) {
            Kernel32.INSTANCE.CloseHandle(acceptHandle);
        }
        if (acceptMutex != null) {
            SMKernel32.INSTANCE.ReleaseMutex(acceptMutex);
            Kernel32.INSTANCE.CloseHandle(acceptMutex);
        }
        if (acceptMutex2 != null) {
            SMKernel32.INSTANCE.ReleaseMutex(acceptMutex2);
            Kernel32.INSTANCE.CloseHandle(acceptMutex2);
        }
    }
}
