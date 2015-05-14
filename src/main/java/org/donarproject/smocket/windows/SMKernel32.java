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

import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.W32APIOptions;

interface SMKernel32 extends WinNT {

    final int PIPE_ACCESS_INBOUND = 0x00000001; //From client to server pipe
    final int PIPE_ACCESS_OUTBOUND = 0x00000002; //From server to client pipe
    final int PIPE_ACCESS_DUPLEX = 0x00000003; //Bi-directional pipe

    final int FILE_FLAG_FIRST_PIPE_INSTANCE = 0x00080000;
    final int FILE_FLAG_WRITE_THROUGH = 0x80000000;
    final int FILE_FLAG_OVERLAPPED = 0x40000000;

    final long WRITE_DAC = 0x00040000L;
    final long WRITE_OWNER = 0x00080000L;
    final long ACCESS_SYSTEM_SECURITY = 0x01000000L;

    final int PIPE_TYPE_BYTE = 0x00000000;
    final int PIPE_TYPE_MESSAGE = 0x00000004;

    final int PIPE_READMODE_BYTE = 0x00000000;
    final int PIPE_READMODE_MESSAGE = 0x00000002;

    final int PIPE_WAIT = 0x00000000;
    final int PIPE_NOWAIT = 0x00000001;

    final int PIPE_ACCEPT_REMOTE_CLIENTS = 0x00000000;
    final int PIPE_REJECT_REMOTE_CLIENTS = 0x00000008;

    final int PIPE_UNLIMITED_INSTANCES = 0x000000FF;

    SMKernel32 INSTANCE = (SMKernel32) Native.loadLibrary("kernel32",
            SMKernel32.class, W32APIOptions.UNICODE_OPTIONS);

    HANDLE CreateNamedPipe(final String lpName,
                           int dwOpenMode,
                           int dwPipeMode,
                           int nMaxInstances,
                           int nOutBufferSize,
                           int nInBufferSize,
                           int nDefaultTimeOut,
                           SECURITY_ATTRIBUTES lpSecurityAttributes);

    boolean ConnectNamedPipe(HANDLE hNamedPipe,
                             OVERLAPPED lpOverlapped);

    boolean DisconnectNamedPipe(HANDLE hNamedPipe);

    boolean FlushFileBuffers(HANDLE hFile);

    final int NMPWAIT_USE_DEFAULT_WAIT = 0x00000000;
    final int NMPWAIT_WAIT_FOREVER = 0xffffffff;

    boolean WaitNamedPipe(String pipeName, int timeout);

    HANDLE CreateMutex(SECURITY_ATTRIBUTES lpSecurityAttributes,
                       boolean bInitialOwner,
                       String lpNam
    );

    HANDLE OpenMutex(int dwDesiredAccess,
                     boolean bInheritHandle,
                     String lpName);

    boolean ReleaseMutex(HANDLE hMutex);

    int WaitForSingleObject(HANDLE hHandle,
                            int dwMilliseconds);
}
