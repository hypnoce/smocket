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

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.util.UUID;

import static com.sun.jna.platform.win32.WinNT.HANDLE;

/**
 * A client can use RandomAccessFile to connect to a named pipe.
 */
public class PSocket {
    private HANDLE handle;
    private RandomAccessFile sessionPipe;

    private InputStream inputStream;
    private OutputStream outputStream;

    private boolean closed = false;
    private final Object closeLock = new Object();

    public PSocket(String host, String port) throws IOException {
        final String pipeName = host + ":" + port;

        HANDLE mutex = waitFor(pipeName);
        final String pipeAddress = "\\\\.\\pipe\\" + pipeName;
        try {
            /* Connect to PServerSocket and retrieve sessionId */
            boolean success = SMKernel32.INSTANCE.WaitNamedPipe(pipeAddress, WinNT.INFINITE);
            if (!success) {
                NativeUtils.throwIOException("Error waiting for pipe \"" + pipeName + "\"", Kernel32.INSTANCE.GetLastError());
            }

            HANDLE handle = Kernel32.INSTANCE.CreateFile(pipeAddress, WinNT.GENERIC_READ | WinNT.GENERIC_WRITE, 0, null, WinNT.OPEN_EXISTING, 0, null);
            if (WinBase.INVALID_HANDLE_VALUE.equals(handle))
                NativeUtils.throwIOException("Cannot open pipe \"" + pipeName + "\"", Kernel32.INSTANCE.GetLastError());
            ByteBuffer buffer = ByteBuffer.allocateDirect(16);
            IntByReference readCount = new IntByReference();
            int read = 0;
            do {
                success = Kernel32.INSTANCE.ReadFile(handle, buffer, 16, readCount, null);
                if (!success) {
                    int lastError = Kernel32.INSTANCE.GetLastError();
                    Kernel32.INSTANCE.CloseHandle(handle);
                    NativeUtils.throwIOException("Cannot read from pipe \"" + pipeName + "\"", lastError);
                }
                read += readCount.getValue();
            } while (read != 16);
            Kernel32.INSTANCE.CloseHandle(handle);

            final long mostSigBits = buffer.getLong();
            final long leastSigBits = buffer.getLong();

            /* Given the sessionId, connect to the pipe create by the server */
            UUID sessionId = new UUID(mostSigBits, leastSigBits);
            final String sessionAddress = "\\\\.\\pipe\\" + sessionId.toString();
            success = SMKernel32.INSTANCE.WaitNamedPipe(sessionAddress, 2000);
            if (!success) {
                NativeUtils.throwIOException("Error waiting for pipe " + sessionAddress, Kernel32.INSTANCE.GetLastError());
            }

            sessionPipe = new RandomAccessFile(sessionAddress, "rw");

            HANDLE mutex2 = waitFor(pipeName + "2");
            SMKernel32.INSTANCE.ReleaseMutex(mutex2);
        } catch (FileNotFoundException e) {
            throw new IOException("Cannot connect to " + pipeName, e);
        } finally {
            SMKernel32.INSTANCE.ReleaseMutex(mutex);
        }
    }

    private static HANDLE waitFor(String mutexName) throws IOException {
        HANDLE mutex = SMKernel32.INSTANCE.OpenMutex(WinNT.SYNCHRONIZE, true, mutexName);
        if (WinBase.INVALID_HANDLE_VALUE.equals(mutex))
            NativeUtils.throwIOException("Cannot open mutex \"" + mutexName + "\"", Kernel32.INSTANCE.GetLastError());
        int result = SMKernel32.INSTANCE.WaitForSingleObject(mutex, 5000);
        if (result == WinBase.WAIT_FAILED) {
            SMKernel32.INSTANCE.ReleaseMutex(mutex);
            NativeUtils.throwIOException("Failed waiting on mutex " + mutexName, result);
        } else if (result == WinBase.WAIT_ABANDONED || result == WinError.WAIT_TIMEOUT) {
            SMKernel32.INSTANCE.ReleaseMutex(mutex);
            NativeUtils.throwIOException("Failed waiting on mutex " + mutexName, result);
        }
        return mutex;
    }

    public PSocket(HANDLE handle) {
        this.handle = handle;
        this.inputStream = new PipeServerInputStream(handle);
        this.outputStream = new PipeServerOutputStream(handle);
    }

    public InputStream getInputStream() throws IOException {
        if (isClosed()) {
            throw new IOException("Socket is closed");
        }
        if(inputStream == null) {
            if(sessionPipe != null) {
                inputStream = new PipeClientInputStream(sessionPipe);
            } else {
                assert handle != null;
                inputStream = new PipeServerInputStream(handle);
            }
        }
        return inputStream;
    }

    public OutputStream getOutputStream() throws IOException {
        if (isClosed()) {
            throw new IOException("Socket is closed");
        }
        if(outputStream == null) {
            if(sessionPipe != null) {
                outputStream = new PipeClientOutputStream(sessionPipe);
            } else {
                assert handle != null;
                outputStream = new PipeServerOutputStream(handle);
            }
        }
        return outputStream;
    }

    public ByteChannel getWritableByteChannel() {

       return sessionPipe.getChannel();
    }

    public boolean isClosed() {
        synchronized (closeLock) {
            return closed;
        }
    }

    public void close() throws IOException {
        synchronized (closeLock) {
            if (isClosed())
                return;
            closed = true;
            if (inputStream != null) {
                inputStream.close();
            }
            if (outputStream != null) {
                outputStream.close();
            }
            if(sessionPipe != null) {
                sessionPipe.close();
            }
            if (handle != null) {
                SMKernel32.INSTANCE.FlushFileBuffers(handle);
                SMKernel32.INSTANCE.DisconnectNamedPipe(handle);
                Kernel32.INSTANCE.CloseHandle(handle);
            }
        }
    }
}
