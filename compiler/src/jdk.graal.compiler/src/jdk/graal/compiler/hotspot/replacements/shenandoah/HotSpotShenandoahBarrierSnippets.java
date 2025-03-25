/*
 * Copyright Amazon.com Inc. or its affiliates. All Rights Reserved.
 * Copyright (c) 2019, 2025, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.hotspot.replacements.shenandoah;

import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.hotspot.GraalHotSpotVMConfig;
import jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor;
import jdk.graal.compiler.hotspot.meta.HotSpotForeignCallsProviderImpl;
import jdk.graal.compiler.hotspot.meta.HotSpotProviders;
import jdk.graal.compiler.hotspot.meta.HotSpotRegistersProvider;
import jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil;
import jdk.graal.compiler.nodes.gc.shenandoah.ShenandoahArrayRangePreWriteBarrierNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.replacements.ReplacementsUtil;
import jdk.graal.compiler.replacements.SnippetCounter;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.gc.G1WriteBarrierSnippets;
import jdk.graal.compiler.replacements.gc.shenandoah.ShenandoahBarrierSnippets;
import jdk.graal.compiler.word.Word;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.JavaKind;

import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT;
import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_METAACCESS;
import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_VMCONFIG;
import static jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor.Transition.LEAF_NO_VZERO;

public class HotSpotShenandoahBarrierSnippets extends ShenandoahBarrierSnippets {
    public static final HotSpotForeignCallDescriptor SHENANDOAHWBPRECALL = new HotSpotForeignCallDescriptor(LEAF_NO_VZERO, NO_SIDE_EFFECT, KILLED_PRE_WRITE_BARRIER_STUB_LOCATIONS, "ShenandoahRuntime::pre_barrier",
            void.class, Object.class);
    private final Register threadRegister;

    public HotSpotShenandoahBarrierSnippets(HotSpotRegistersProvider registers) {
        this.threadRegister = registers.getThreadRegister();
    }

    @Override
    protected Word getThread() {
        return HotSpotReplacementsUtil.registerAsWord(threadRegister);
    }

    @Override
    protected int wordSize() {
        return HotSpotReplacementsUtil.wordSize();
    }

    @Override
    protected long objectArrayIndexScale() {
        return ReplacementsUtil.arrayIndexScale(INJECTED_METAACCESS, JavaKind.Object);
    }

    @Override
    protected int satbQueueMarkingActiveOffset() {
        return HotSpotReplacementsUtil.g1SATBQueueMarkingActiveOffset(INJECTED_VMCONFIG);
    }

    @Override
    protected int satbQueueBufferOffset() {
        return HotSpotReplacementsUtil.g1SATBQueueBufferOffset(INJECTED_VMCONFIG);
    }

    @Override
    protected int satbQueueIndexOffset() {
        return HotSpotReplacementsUtil.g1SATBQueueIndexOffset(INJECTED_VMCONFIG);
    }

    @Override
    protected ForeignCallDescriptor preWriteBarrierCallDescriptor() {
        return SHENANDOAHWBPRECALL;
    }

    @Override
    protected boolean verifyOops() {
        return HotSpotReplacementsUtil.verifyOops(INJECTED_VMCONFIG);
    }

    @Override
    protected ForeignCallDescriptor verifyOopCallDescriptor() {
        return HotSpotForeignCallsProviderImpl.VERIFY_OOP;
    }

    public static class Templates extends SnippetTemplate.AbstractTemplates {
        private final SnippetTemplate.SnippetInfo shenandoahArrayRangePreWriteBarrier;

        private final ShenandoahBarrierSnippets.ShenandoahBarrierLowerer lowerer;

        public Templates(OptionValues options, SnippetCounter.Group.Factory factory, HotSpotProviders providers, GraalHotSpotVMConfig config) {
            super(options, providers);
            this.lowerer = new HotSpotShenandoahBarrierSnippets.HotspotShenandoahBarrierLowerer(config, factory);

            HotSpotShenandoahBarrierSnippets receiver = new HotSpotShenandoahBarrierSnippets(providers.getRegisters());

            shenandoahArrayRangePreWriteBarrier = snippet(providers,
                    ShenandoahBarrierSnippets.class,
                    "shenandoahArrayRangePreWriteBarrier",
                    null,
                    receiver,
                    SATB_QUEUE_LOG_LOCATION,
                    SATB_QUEUE_MARKING_ACTIVE_LOCATION,
                    SATB_QUEUE_INDEX_LOCATION,
                    SATB_QUEUE_BUFFER_LOCATION);
        }

        public void lower(ShenandoahArrayRangePreWriteBarrierNode barrier, LoweringTool tool) {
            lowerer.lower(this, shenandoahArrayRangePreWriteBarrier, barrier, tool);
        }

    }

    static final class HotspotShenandoahBarrierLowerer extends ShenandoahBarrierSnippets.ShenandoahBarrierLowerer {
        HotspotShenandoahBarrierLowerer(GraalHotSpotVMConfig config, SnippetCounter.Group.Factory factory) {
            super(factory);
        }
    }
}
