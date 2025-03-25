/*
 * Copyright Amazon.com Inc. or its affiliates. All Rights Reserved.
 * Copyright (c) 2012, 2025, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.replacements.gc.shenandoah;

import jdk.graal.compiler.api.directives.GraalDirectives;
import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.core.common.memory.BarrierType;
import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.nodes.NamedLocationIdentity;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.nodes.gc.shenandoah.ShenandoahArrayRangePreWriteBarrierNode;
import jdk.graal.compiler.nodes.memory.address.AddressNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.replacements.SnippetCounter;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.Snippets;
import jdk.graal.compiler.replacements.gc.WriteBarrierSnippets;
import jdk.graal.compiler.word.Word;
import org.graalvm.word.LocationIdentity;

import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.FREQUENT_PROBABILITY;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.NOT_FREQUENT_PROBABILITY;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.probability;

public abstract class ShenandoahBarrierSnippets extends WriteBarrierSnippets implements Snippets {
    public static final LocationIdentity SATB_QUEUE_MARKING_ACTIVE_LOCATION = NamedLocationIdentity.mutable("GC-SATB-Marking-Active");
    public static final LocationIdentity SATB_QUEUE_BUFFER_LOCATION = NamedLocationIdentity.mutable("GC-SATB-Queue-Buffer");
    public static final LocationIdentity SATB_QUEUE_LOG_LOCATION = NamedLocationIdentity.mutable("GC-SATB-Queue-Log");
    public static final LocationIdentity SATB_QUEUE_INDEX_LOCATION = NamedLocationIdentity.mutable("GC-SATB-Queue-Index");

    protected static final LocationIdentity[] KILLED_PRE_WRITE_BARRIER_STUB_LOCATIONS = new LocationIdentity[]{SATB_QUEUE_INDEX_LOCATION, SATB_QUEUE_BUFFER_LOCATION, SATB_QUEUE_LOG_LOCATION};

    @Snippet
    public void shenandoahArrayRangePreWriteBarrier(AddressNode.Address address, long length, @Snippet.ConstantParameter int elementStride) {
        Word thread = getThread();
        byte markingValue = thread.readByte(satbQueueMarkingActiveOffset(), SATB_QUEUE_MARKING_ACTIVE_LOCATION);
        // If the concurrent marker is not enabled or the vector length is zero, return.
        if (probability(FREQUENT_PROBABILITY, markingValue == (byte) 0) || probability(NOT_FREQUENT_PROBABILITY, length == 0)) {
            return;
        }

        Word bufferAddress = thread.readWord(satbQueueBufferOffset(), SATB_QUEUE_BUFFER_LOCATION);
        Word indexAddress = thread.add(satbQueueIndexOffset());
        long indexValue = indexAddress.readWord(0, SATB_QUEUE_INDEX_LOCATION).rawValue();
        long scale = objectArrayIndexScale();
        Word start = getPointerToFirstArrayElement(Word.fromAddress(address), length, elementStride);

        for (int i = 0; GraalDirectives.injectIterationCount(10, i < length); i++) {
            Word arrElemPtr = start.add(Word.unsigned(i * scale));
            Object previousObject = arrElemPtr.readObject(0, BarrierType.NONE, LocationIdentity.any());
            verifyOop(previousObject);
            if (probability(FREQUENT_PROBABILITY, previousObject != null)) {
                if (probability(FREQUENT_PROBABILITY, indexValue != 0)) {
                    indexValue = indexValue - wordSize();
                    Word logAddress = bufferAddress.add(Word.unsigned(indexValue));
                    // Log the object to be marked and update the SATB's buffer next index.
                    logAddress.writeWord(0, Word.objectToTrackedPointer(previousObject), SATB_QUEUE_LOG_LOCATION);
                    indexAddress.writeWord(0, Word.unsigned(indexValue), SATB_QUEUE_INDEX_LOCATION);
                } else {
                    shenandoahPreBarrierStub(previousObject);
                }
            }
        }
    }

    protected abstract Word getThread();

    protected abstract int wordSize();

    protected abstract long objectArrayIndexScale();

    protected abstract int satbQueueMarkingActiveOffset();

    protected abstract int satbQueueBufferOffset();

    protected abstract int satbQueueIndexOffset();

    protected abstract ForeignCallDescriptor preWriteBarrierCallDescriptor();

    protected abstract boolean verifyOops();

    protected abstract ForeignCallDescriptor verifyOopCallDescriptor();

    private void verifyOop(Object object) {
        if (verifyOops()) {
            verifyOopStub(verifyOopCallDescriptor(), object);
        }
    }

    private void shenandoahPreBarrierStub(Object previousObject) {
        shenandoahPreBarrierStub(preWriteBarrierCallDescriptor(), previousObject);
    }

    @Node.NodeIntrinsic(ForeignCallNode.class)
    private static native Object verifyOopStub(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, Object object);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    private static native void shenandoahPreBarrierStub(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, Object object);

    public abstract static class ShenandoahBarrierLowerer {

        public ShenandoahBarrierLowerer(SnippetCounter.Group.Factory factory) {
        }

        public void lower(SnippetTemplate.AbstractTemplates templates, SnippetTemplate.SnippetInfo snippet, ShenandoahArrayRangePreWriteBarrierNode barrier, LoweringTool tool) {
            SnippetTemplate.Arguments args = new SnippetTemplate.Arguments(snippet, barrier.graph().getGuardsStage(), tool.getLoweringStage());
            args.add("address", barrier.getAddress());
            args.add("length", barrier.getLengthAsLong());
            args.add("elementStride", barrier.getElementStride());

            templates.template(tool, barrier, args).instantiate(tool.getMetaAccess(), barrier, SnippetTemplate.DEFAULT_REPLACER, args);
        }
    }
}