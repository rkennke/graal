/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021, 2021, Red Hat Inc. All rights reserved.
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

import com.oracle.svm.core.thread.VMOperation;
import com.oracle.svm.jfr.traceid.JfrTraceId;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class JfrClassLoaderRepository implements JfrRepository {

    private final Map<ClassLoader, Long> classLoaders = new HashMap<>();
    private long classLoaderId = 0;

    boolean addClassLoader(ClassLoader classLoader) {
        if (!classLoaders.containsKey(classLoader)) {
            classLoaders.put(classLoader, ++classLoaderId);
            return true;
        } else {
            return false;
        }
    }

    long getClassLoaderId(ClassLoader classLoader) {
        if (classLoader == null) {
            return 0;
        }
        return classLoaders.get(classLoader);
    }

    @Override
    public void write(JfrChunkWriter writer) throws IOException {
        assert VMOperation.isInProgressAtSafepoint();
        writer.writeCompressedLong(JfrTypes.getTypeId("jdk.types.ClassLoader"));
        writer.writeCompressedInt(classLoaders.size());

        for (Map.Entry<ClassLoader, Long> clInfo : classLoaders.entrySet()) {
            writeClassLoader(writer, clInfo.getKey(), clInfo.getValue());
        }
    }

    private void writeClassLoader(JfrChunkWriter writer, ClassLoader cl, long id) throws IOException {
        JfrSymbolRepository symbolRepo = SubstrateJVM.getSymbolRepository();
        writer.writeCompressedLong(id);
        writer.writeCompressedLong(JfrTraceId.getTraceId(cl.getClass()));
        writer.writeCompressedLong(symbolRepo.getSymbolId(cl.getName()));
    }

    @Override
    public boolean hasItems() {
        return !classLoaders.isEmpty();
    }
}
