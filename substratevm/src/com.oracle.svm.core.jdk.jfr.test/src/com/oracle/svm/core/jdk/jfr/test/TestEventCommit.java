/*
 * Copyright (c) 2021, Red Hat Inc. All rights reserved.
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

package com.oracle.svm.core.jdk.jfr.test;

import java.nio.file.Files;
import java.nio.file.Path;

import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import static org.junit.Assert.*;
import org.junit.Test;

public class TestEventCommit {

    @Label("Basic Event")
    @Description("An event with just a message as payload")
    static class BasicEvent extends Event {
        @Label("Message")
        public String message;
    }

    @Test
    public void test() throws Exception {
        Configuration c = Configuration.getConfiguration("default");
        Recording r = new Recording(c);
        r.start();

        BasicEvent event = new BasicEvent();
        event.message = "BasicEvent with a long string!";
        event.commit();

        r.stop();
        Path tmpfile = Files.createTempFile("jfr-test-recording", ".jfr");
        r.dump(tmpfile);
        r.close();

        try (RecordingFile recordingFile = new RecordingFile(tmpfile)) {
            boolean foundEvent = false;
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent recordedEvent = recordingFile.readEvent();
                if ("com.oracle.svm.core.jdk.jfr.test.TestEventCommit$BasicEvent".equals(recordedEvent.getEventType().getName())) {
                    foundEvent = true;
                    assertEquals("BasicEvent with a long string!", recordedEvent.getValue("message"));
                }
            }
            assertTrue(foundEvent);
        } finally {
            Files.deleteIfExists(tmpfile);
        }
    }
}
