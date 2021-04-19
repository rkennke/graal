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

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class JfrModuleRepository implements JfrRepository {

    private final Map<Module, Long> modules = new HashMap<>();
    private long moduleId = 0;
    boolean addModule(Module module) {
        if (!modules.containsKey(module)) {
            modules.put(module, ++moduleId);
            return true;
        } else {
            return false;
        }
    }

    long getModuleId(Module module) {
        if (module == null) {
            return 0;
        }
        return modules.get(module);
    }

    @Override
    public void write(JfrChunkWriter writer) throws IOException {
        assert VMOperation.isInProgressAtSafepoint();
        writer.writeCompressedLong(JfrTypes.getTypeId("jdk.types.Module"));
        writer.writeCompressedInt(modules.size());

        for (Map.Entry<Module, Long> modInfo : modules.entrySet()) {
            writeModule(writer, modInfo.getKey(), modInfo.getValue());
        }
    }

    private void writeModule(JfrChunkWriter writer, Module module, long id) throws IOException {
        JfrSymbolRepository symbolRepo = SubstrateJVM.getSymbolRepository();
        JfrClassLoaderRepository classLoaderRepo = SubstrateJVM.getClassLoaderRepository();
        writer.writeCompressedLong(id);
        writer.writeCompressedLong(symbolRepo.getSymbolId(module.getName()));
        writer.writeCompressedLong(0); // Version?
        writer.writeCompressedLong(0); // Location?
        writer.writeCompressedLong(classLoaderRepo.getClassLoaderId(module.getClassLoader()));
    }

    @Override
    public boolean hasItems() {
        return !modules.isEmpty();
    }
}
