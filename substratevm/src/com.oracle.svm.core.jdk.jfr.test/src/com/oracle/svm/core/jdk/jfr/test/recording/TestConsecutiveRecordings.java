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

package com.oracle.svm.core.jdk.jfr.test.recording;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.oracle.svm.core.jdk.jfr.test.utils.events.StringEvent;

import jdk.jfr.Recording;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TestConsecutiveRecordings {
    @Test
    public void test() throws IOException {
        String name = "One";
        Recording r = new Recording();
        Path destination1 = File.createTempFile(name, ".jfr").toPath();
        r.setDestination(destination1);

        r.start();
        for (int i = 0; i < 2; i++) {
            StringEvent event = new StringEvent();
            event.message = "Event has been generated!";
            event.commit();
        }
        r.stop();
        r.close();

        name = "Two";
        r = new Recording();
        r.start();
        for (int i = 0; i < 2; i++) {
            StringEvent event = new StringEvent();
            event.message = "Event has been generated!";
            event.commit();
        }
        r.stop();
        Path destination2 = File.createTempFile(name, ".jfr").toPath();
        r.dump(destination2);
        r.close();

        try (RecordingFile recordingFile = new RecordingFile(destination1)) {
            long numEvents = 0;
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent recordedEvent = recordingFile.readEvent();
                if ("com.oracle.svm.core.jdk.jfr.test.utils.events.StringEvent".equals(recordedEvent.getEventType().getName())) {
                    numEvents++;
                    assertEquals("Event has been generated!", recordedEvent.getValue("message"));
                }
            }
            assertEquals(2, numEvents);
        } finally {
            Files.deleteIfExists(destination1);
        }
        try (RecordingFile recordingFile = new RecordingFile(destination2)) {
            long numEvents = 0;
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent recordedEvent = recordingFile.readEvent();
                if ("com.oracle.svm.core.jdk.jfr.test.utils.events.StringEvent".equals(recordedEvent.getEventType().getName())) {
                    numEvents++;
                    assertEquals("Event has been generated!", recordedEvent.getValue("message"));
                }
            }
            assertEquals(2, numEvents);
        } finally {
            Files.deleteIfExists(destination2);
        }
    }
}
