/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.jfr;

import com.oracle.svm.core.annotate.Uninterruptible;
import jdk.jfr.EventType;
import jdk.jfr.internal.MetadataRepository;

import java.util.List;

/**
 * The event IDs depend on the metadata.xml and therefore vary between JDK versions.
 */
public enum JfrEvents {
    ThreadStartEvent("jdk.ThreadStart"),
    ThreadEndEvent("jdk.ThreadEnd"),
    DataLossEvent("jdk.DataLoss");

    private final long id;

    JfrEvents(String name) {
        this.id = getEventTypeId(name);
    }

    private static long getEventTypeId(String name) {
        MetadataRepository metadata = MetadataRepository.getInstance();
        List<EventType> eventTypes = metadata.getRegisteredEventTypes();
        for (EventType eventType : eventTypes) {
            if (name.equals(eventType.getName())) {
                return eventType.getId();
            }
        }
        return 0;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public long getId() {
        return id;
    }

    public static int getEventCount() {
        MetadataRepository metadata = MetadataRepository.getInstance();
        List<EventType> eventTypes = metadata.getRegisteredEventTypes();
        long maxEventId = 0;
        for (EventType eventType : eventTypes) {
            maxEventId = Math.max(maxEventId, eventType.getId());
        }
        assert maxEventId + 1 < Integer.MAX_VALUE;
        return (int) maxEventId + 1;
    }
}
