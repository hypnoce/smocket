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

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

public class PipeClientInputStream extends InputStream {
    private final RandomAccessFile pipe;

    public PipeClientInputStream(RandomAccessFile pipe) {
        this.pipe = pipe;
    }

    @Override
    public int read() throws IOException {
        return pipe.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return pipe.read(b, off, len);
    }

    @Override
    public void close() throws IOException {
        pipe.close();
        super.close();
    }
}
