/*
 * Copyright (c) 2020, 2021, Red Hat, Inc.
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This file is part of the Red Hat GraalVM Testing Suite (the suite).
 *
 * The suite is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 * Foobar is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Foobar.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.oracle.svm.core.jdk.jfr.test.utils;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

public class LocalJFR implements JFR {
    private final Map<Long, Recording> recordings = new HashMap<>();

    @Override
    public long startRecording(String recordingName) throws Exception {
        return startRecording(new Recording(), recordingName);
    }

    @Override
    public long startRecording(String recordingName, String configName) throws Exception {
        Configuration c = Configuration.getConfiguration(configName);
        return startRecording(new Recording(c), recordingName);
    }

    public File endRecording(long id) {
        Recording recording = recordings.remove(id);
        recording.stop();
        recording.close();
        return recording.getDestination().toFile();
    }

    public long startRecording(Recording recording, String name) throws Exception {
        long id = recording.getId();

        Path destination = File.createTempFile(name + "-" + id, ".jfr").toPath();
        recording.setDestination(destination);

        recordings.put(id, recording);

        recording.start();
        return id;
    }
}
