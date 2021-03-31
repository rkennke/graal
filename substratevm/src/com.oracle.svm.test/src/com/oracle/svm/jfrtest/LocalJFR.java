/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020, 2021, Red Hat Inc. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.oracle.svm.jfrtest;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

public class LocalJFR implements JFR {

    @Override
    public Recording startRecording(String recordingName) throws Exception {
        return startRecording(new Recording(), recordingName);
    }

    @Override
    public Recording startRecording(String recordingName, String configName) throws Exception {
        Configuration c = Configuration.getConfiguration(configName);
        return startRecording(new Recording(c), recordingName);
    }

    private static Recording startRecording(Recording recording, String name) throws Exception {
        long id = recording.getId();

        Path destination = File.createTempFile(name + "-" + id, ".jfr").toPath();
        recording.setDestination(destination);

        recording.start();
        return recording;
    }

    @Override
    public void endRecording(Recording recording) {
        recording.stop();
        recording.close();
    }

    @Override
    public void cleanupRecording(Recording recording) throws IOException {
        String debugRecording = System.getenv("DEBUG_RECORDING");
        if (debugRecording != null && !"false".equals(debugRecording)) {
            // Checkstyle: stop
            System.out.println("Recording: " + recording.getDestination());
            // Checkstyle: resume
        } else {
            Files.deleteIfExists(recording.getDestination());
        }
    }

    @Override
    public long readRawLong(RandomAccessFile input) throws IOException {
        return input.readLong();
    }

    @Override
    public byte readByte(RandomAccessFile input) throws IOException {
        return input.readByte();
    }

    @Override
    public short readShort(RandomAccessFile input) throws IOException {
        return (short) readLong(input);
    }

    @Override
    public int readInt(RandomAccessFile input) throws IOException {
        return (int) readLong(input);
    }

    @Override
    public long readLong(RandomAccessFile input) throws IOException {
        byte b0 = readByte(input);
        long ret = (b0 & 0x7FL);
        if (b0 >= 0) {
            return ret;
        }

        int b1 = readByte(input);
        ret += (b1 & 0x7FL) << 7;
        if (b1 >= 0) {
            return ret;
        }

        int b2 = readByte(input);
        ret += (b2 & 0x7FL) << 14;
        if (b2 >= 0) {
            return ret;
        }

        int b3 = readByte(input);
        ret += (b3 & 0x7FL) << 21;
        if (b3 >= 0) {
            return ret;
        }

        int b4 = readByte(input);
        ret += (b4 & 0x7FL) << 28;
        if (b4 >= 0) {
            return ret;
        }

        int b5 = readByte(input);
        ret += (b5 & 0x7FL) << 35;
        if (b5 >= 0) {
            return ret;
        }

        int b6 = readByte(input);
        ret += (b6 & 0x7FL) << 42;
        if (b6 >= 0) {
            return ret;
        }

        int b7 = readByte(input);
        ret += (b7 & 0x7FL) << 49;
        if (b7 >= 0) {
            return ret;

        }

        int b8 = readByte(input); // read last byte raw
        return ret + (((long) (b8 & 0XFF)) << 56);
    }

}
