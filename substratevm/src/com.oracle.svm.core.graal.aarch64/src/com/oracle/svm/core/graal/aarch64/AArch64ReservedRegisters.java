/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.graal.aarch64;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.ReservedRegisters;

import jdk.vm.ci.aarch64.AArch64;
import jdk.vm.ci.code.Register;

public final class AArch64ReservedRegisters extends ReservedRegisters {

    public static final Register THREAD_REGISTER = AArch64.r28;
    public static final Register HEAP_BASE_REGISTER = AArch64.r27;
    public static final Register CODE_BASE_REGISTER_CANDIDATE = AArch64.r26;

    @Platforms(Platform.HOSTED_ONLY.class)
    AArch64ReservedRegisters() {
        super(AArch64.sp, THREAD_REGISTER, HEAP_BASE_REGISTER, CODE_BASE_REGISTER_CANDIDATE);
    }
}
