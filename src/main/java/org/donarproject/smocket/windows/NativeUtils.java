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

import java.io.IOException;
import java.nio.CharBuffer;

class NativeUtils {
    static void throwIOException(final String prefix, int errorCode) throws IOException {
        CharBuffer buffer = CharBuffer.allocate(512);
        int numChar = Kernel32.INSTANCE.FormatMessage(WinBase.FORMAT_MESSAGE_FROM_SYSTEM, null, errorCode, 0, buffer, 512, null);
        String msg;
        if (numChar > 0) {
            msg = buffer.toString().trim();
        } else {
            msg = "<Unknown>";
        }
        throw new IOException(prefix + " (GetLastError = " + errorCode + " : " + msg + ")");
    }
}
