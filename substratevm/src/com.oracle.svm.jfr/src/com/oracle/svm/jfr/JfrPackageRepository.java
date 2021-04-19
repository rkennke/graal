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

public class JfrPackageRepository implements JfrRepository {

    private static class PackageInfo {
        private final long id;
        private final Module module;
        PackageInfo(long id, Module module) {
            this.id = id;
            this.module = module;
        }
    }

    // Note: Package doesn't implement equals in a correct way. Use package name as workaround.
    // See: https://github.com/oracle/graal/issues/2989
    private final Map<String, PackageInfo> packages = new HashMap<>();
    private long packageId = 0;

    boolean addPackage(Package pkg, Module module) {
        if (!packages.containsKey(pkg.getName())) {
            packages.put(pkg.getName(), new PackageInfo(++packageId, module));
            return true;
        } else {
            assert module == packages.get(pkg.getName()).module;
            return false;
        }
    }

    long getPackageId(Package pkg) {
        if (pkg == null) {
            return 0;
        }
        return packages.get(pkg.getName()).id;
    }

    @Override
    public void write(JfrChunkWriter writer) throws IOException {
        assert VMOperation.isInProgressAtSafepoint();
        writer.writeCompressedLong(JfrTypes.getTypeId("jdk.types.Package"));
        writer.writeCompressedInt(packages.size());

        for (Map.Entry<String, PackageInfo> pkgInfo : packages.entrySet()) {
            writePackage(writer, pkgInfo.getKey(), pkgInfo.getValue());
        }
    }

    private void writePackage(JfrChunkWriter writer, String pkgName, PackageInfo pkgInfo) throws IOException {
        JfrSymbolRepository symbolRepo = SubstrateJVM.getSymbolRepository();
        JfrModuleRepository moduleRepo = SubstrateJVM.getModuleRepository();
        writer.writeCompressedLong(pkgInfo.id);  // id
        writer.writeCompressedLong(symbolRepo.getSymbolId(pkgName, true));
        writer.writeCompressedLong(moduleRepo.getModuleId(pkgInfo.module));
        writer.writeBoolean(false); // what's this?
    }

    @Override
    public boolean hasItems() {
        return !packages.isEmpty();
    }
}
