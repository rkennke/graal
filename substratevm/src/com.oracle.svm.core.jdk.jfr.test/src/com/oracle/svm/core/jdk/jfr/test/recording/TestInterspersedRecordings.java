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
import java.nio.file.Path;

import com.oracle.svm.core.jdk.jfr.test.utils.events.StringEvent;

import jdk.jfr.Recording;

import org.junit.Test;

public class TestInterspersedRecordings {
    @Test
    public void test() throws IOException {
        long s0 = System.currentTimeMillis();
        String nameOne = "One";
        Recording r1 = new Recording();
        Path destination1 = File.createTempFile(nameOne, ".jfr").toPath();
        r1.setDestination(destination1);

        String nameTwo = "Two";
        Recording r2 = new Recording();

        r1.start();
        r2.start();

        for (int i = 0; i < 2; i++) {
            StringEvent event = new StringEvent();
            event.message = "Event has been generated!";
            event.commit();
        }

        r1.stop();
        r1.close();

        for (int i = 0; i < 2; i++) {
            StringEvent event = new StringEvent();
            event.message = "Event has been generated!";
            event.commit();
        }

        r2.stop();
        Path destination2 = File.createTempFile(nameTwo, ".jfr").toPath();
        r2.dump(destination2);
        r2.close();

        long d0 = System.currentTimeMillis() - s0;
//        System.out.println("elapsed:" + d0);
//        System.err.println("jfr recording: " + destination1);
//        System.err.println("jfr recording: " + destination2);
    }
}
