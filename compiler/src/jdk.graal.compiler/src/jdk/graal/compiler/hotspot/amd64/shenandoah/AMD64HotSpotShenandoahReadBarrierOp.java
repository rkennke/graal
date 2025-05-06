/*
 * Copyright Amazon.com Inc. or its affiliates. All Rights Reserved.
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.hotspot.amd64.shenandoah;

import jdk.graal.compiler.asm.Label;
import jdk.graal.compiler.asm.amd64.AMD64Address;
import jdk.graal.compiler.asm.amd64.AMD64Assembler;
import jdk.graal.compiler.asm.amd64.AMD64BaseAssembler;
import jdk.graal.compiler.asm.amd64.AMD64MacroAssembler;
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.hotspot.GraalHotSpotVMConfig;
import jdk.graal.compiler.hotspot.meta.HotSpotProviders;
import jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil;
import jdk.graal.compiler.lir.LIRInstructionClass;
import jdk.graal.compiler.lir.amd64.AMD64AddressValue;
import jdk.graal.compiler.lir.amd64.AMD64Call;
import jdk.graal.compiler.lir.amd64.AMD64LIRInstruction;
import jdk.graal.compiler.lir.asm.CompilationResultBuilder;
import jdk.graal.compiler.nodes.gc.shenandoah.ShenandoahLoadBarrierNode;

import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.AllocatableValue;

import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.COMPOSITE;
import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.REG;
import static jdk.vm.ci.code.ValueUtil.asRegister;

public class AMD64HotSpotShenandoahReadBarrierOp extends AMD64LIRInstruction {
    public static final LIRInstructionClass<AMD64HotSpotShenandoahReadBarrierOp> TYPE = LIRInstructionClass.create(AMD64HotSpotShenandoahReadBarrierOp.class);

    enum GCStateBitPos {
        // Heap has forwarded objects: needs LRB barriers.
        HAS_FORWARDED_BITPOS(0),

        // Heap is under marking: needs SATB barriers.
        // For generational mode, it means either young or old marking, or both.
        MARKING_BITPOS(1),

        // Heap is under evacuation: needs LRB barriers. (Set together with HAS_FORWARDED)
        EVACUATION_BITPOS(2),

        // Heap is under updating: needs no additional barriers.
        UPDATE_REFS_BITPOS(3),

        // Heap is under weak-reference/roots processing: needs weak-LRB barriers.
        WEAK_ROOTS_BITPOS(4),

        // Young regions are under marking, need SATB barriers.
        YOUNG_MARKING_BITPOS(5),

        // Old regions are under marking, need SATB barriers.
        OLD_MARKING_BITPOS(6);

        private final int value;

        GCStateBitPos(int val) {
            this.value = val;
        }

        public int getValue() {
            return this.value;
        }
    }
    enum GCState {
        HAS_FORWARDED(1 << GCStateBitPos.HAS_FORWARDED_BITPOS.value),
        MARKING(1 << GCStateBitPos.MARKING_BITPOS.value),
        EVACUATION(1 << GCStateBitPos.EVACUATION_BITPOS.value),
        UPDATE_REFS(1 << GCStateBitPos.UPDATE_REFS_BITPOS.value),
        WEAK_ROOTS(1 << GCStateBitPos.WEAK_ROOTS_BITPOS.value),
        YOUNG_MARKING(1 << GCStateBitPos.YOUNG_MARKING_BITPOS.value),
        OLD_MARKING(1 << GCStateBitPos.OLD_MARKING_BITPOS.value);

        private final int value;
        GCState(int val) { this.value = val; }
        public int getValue() { return this.value; }
    }
    private final HotSpotProviders providers;
    private final GraalHotSpotVMConfig config;

    @Temp({REG}) protected AllocatableValue tmp;
    @Temp({REG}) protected AllocatableValue tmp2;
    @Temp({REG}) protected AllocatableValue tmp3;
    @Def({REG}) protected AllocatableValue result;
    @Use({REG}) protected AllocatableValue object;
    @Alive({COMPOSITE}) protected AMD64AddressValue loadAddress;

    protected final ForeignCallLinkage callTarget;

    ShenandoahLoadBarrierNode.ReferenceStrength strength;
    boolean notNull;

    public AMD64HotSpotShenandoahReadBarrierOp(GraalHotSpotVMConfig config, HotSpotProviders providers,
                                                 AllocatableValue result, AllocatableValue object, AMD64AddressValue loadAddress,
                                                 ForeignCallLinkage callTarget,
                                                 ShenandoahLoadBarrierNode.ReferenceStrength strength,
                                                 AllocatableValue tmp, AllocatableValue tmp2, AllocatableValue tmp3,
                                                 boolean notNull) {
        super(TYPE);
        this.providers = providers;
        this.config = config;
        this.result = result;
        this.object = object;
        this.loadAddress = loadAddress;
        this.callTarget = callTarget;
        this.strength = strength;
        this.notNull = notNull;
        this.tmp = tmp;
        this.tmp2 = tmp2;
        this.tmp3 = tmp3;
        System.out.println("ReadBarrierOp");
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        Register rscratch1 = asRegister(tmp);
        Register rscratch2 = asRegister(tmp2);

        Register objectRegister = asRegister(object);
        AMD64Address loadAddr = loadAddress.toAddress(masm);
        Register resultRegister = asRegister(result);

        Register thread = providers.getRegisters().getThreadRegister();

        Label done = new Label();
        Label cset_check = new Label();
        Label slow_path = new Label();

        // Move object to result, in case the heap is stable and no barrier needs to be called.
        masm.movq(resultRegister, objectRegister);

        if (!notNull) {
            // Check for object being null.
            masm.testAndJcc(AMD64BaseAssembler.OperandSize.QWORD, resultRegister, resultRegister, AMD64Assembler.ConditionFlag.Zero, done, true);
            //masm.cmpq(resultRegister, 0);
            //masm.jcc(AMD64Assembler.ConditionFlag.Zero, done);
        }

        // Check for heap stability
        masm.movb(rscratch1, new AMD64Address(thread, HotSpotReplacementsUtil.shenandoahGCStateOffset(config)));
        if (strength != ShenandoahLoadBarrierNode.ReferenceStrength.STRONG) {
            // This is needed because in a short-cut cycle we may get a trailing
            // weak-roots phase but no evacuation/update-refs phase, and during that,
            // we need to take the LRB to report null for unreachable weak-refs.
            // This is true even for non-cset objects.
            // Two tests because HAS_FORWARDED | WEAK_ROOTS currently is not representable
            // as a single immediate.
            masm.testlAndJcc(rscratch1, GCState.HAS_FORWARDED.getValue(), AMD64Assembler.ConditionFlag.Zero, slow_path, true);
            masm.testlAndJcc(rscratch1, GCState.WEAK_ROOTS.getValue(), AMD64Assembler.ConditionFlag.Zero, slow_path, true);
        } else {
            masm.testlAndJcc(rscratch1, GCState.HAS_FORWARDED.getValue(), AMD64Assembler.ConditionFlag.Zero, cset_check, true);
        }
        masm.bind(done);

        // Check for object in collection set in an out-of-line mid-path.
        if (strength == ShenandoahLoadBarrierNode.ReferenceStrength.STRONG) {
            crb.getLIR().addSlowPath(this, () -> {
                masm.bind(cset_check);

                masm.movq(rscratch1, HotSpotReplacementsUtil.shenandoahGCCSetFastTestAddr(config));
                masm.movq(rscratch2, objectRegister);
                masm.shrq(rscratch2, HotSpotReplacementsUtil.shenandoahGCRegionSizeBytesShift(config));

                masm.addq(rscratch2, rscratch1);
                masm.cmpb(new AMD64Address(rscratch2), 0);
                masm.jcc(AMD64Assembler.ConditionFlag.NotZero, slow_path);

                masm.jmp(done);
            });
        }

        // Call runtime slow-path LRB in out-of-line slow-path.
        crb.getLIR().addSlowPath(this, () -> {
            masm.bind(slow_path);
            CallingConvention cc = callTarget.getOutgoingCallingConvention();
            assert cc.getArgumentCount() == 2 : "Expecting callTarget to have only 2 parameters. It has " + cc.getArgumentCount();

            AMD64Address cArg0 = (AMD64Address) crb.asAddress(cc.getArgument(0));
            AMD64Address cArg1 = (AMD64Address) crb.asAddress(cc.getArgument(1));

            // Store first argument
            masm.movq(cArg0, objectRegister);

            // Store second argument
            masm.leaq(rscratch1, loadAddr);
            masm.movq(cArg1, rscratch1);

            // Make the call
            AMD64Call.directCall(crb, masm, callTarget, null, false, null);

            // Retrieve result and move to the result register.
            AMD64Address cRet = (AMD64Address) crb.asAddress(cc.getReturn());
            masm.movq(resultRegister, cRet);
            masm.jmp(done);
        });
    }
}
