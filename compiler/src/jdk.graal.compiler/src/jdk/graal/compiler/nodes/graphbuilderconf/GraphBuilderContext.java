/*
 * Copyright (c) 2015, 2024, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.nodes.graphbuilderconf;

import static jdk.graal.compiler.core.common.GraalOptions.StrictDeoptInsertionChecks;
import static jdk.graal.compiler.core.common.type.StampFactory.objectNonNull;
import static jdk.vm.ci.meta.DeoptimizationAction.InvalidateReprofile;

import java.util.List;

import jdk.graal.compiler.bytecode.Bytecode;
import jdk.graal.compiler.core.common.type.AbstractPointerStamp;
import jdk.graal.compiler.core.common.type.IntegerStamp;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.StampPair;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.nodes.AbstractMergeNode;
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.CallTargetNode.InvokeKind;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.DeoptimizeNode;
import jdk.graal.compiler.nodes.DynamicPiNode;
import jdk.graal.compiler.nodes.EndNode;
import jdk.graal.compiler.nodes.FixedGuardNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.Invokable;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.MergeNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.PiNode;
import jdk.graal.compiler.nodes.PluginReplacementNode;
import jdk.graal.compiler.nodes.PluginReplacementWithExceptionNode;
import jdk.graal.compiler.nodes.ProfileData.BranchProbabilityData;
import jdk.graal.compiler.nodes.StateSplit;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.IntegerEqualsNode;
import jdk.graal.compiler.nodes.calc.IntegerLessThanNode;
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.graal.compiler.nodes.calc.NarrowNode;
import jdk.graal.compiler.nodes.calc.SignExtendNode;
import jdk.graal.compiler.nodes.calc.ZeroExtendNode;
import jdk.graal.compiler.nodes.extended.BranchProbabilityNode;
import jdk.graal.compiler.nodes.extended.BytecodeExceptionNode;
import jdk.graal.compiler.nodes.extended.GuardingNode;
import jdk.graal.compiler.nodes.java.InstanceOfDynamicNode;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.internal.misc.ScopedMemoryAccess;
import jdk.vm.ci.code.BailoutException;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Used by a {@link GraphBuilderPlugin} to interface with an object that parses the bytecode of a
 * single {@linkplain #getMethod() method} as part of building a {@linkplain #getGraph() graph} .
 */
public interface GraphBuilderContext extends GraphBuilderTool {

    /**
     * Pushes a given value to the frame state stack using an explicit kind. This should be used
     * when {@code value.getJavaKind()} is different from the kind that the bytecode instruction
     * currently being parsed pushes to the stack.
     *
     * @param kind the kind to use when type checking this operation
     * @param value the value to push to the stack. The value must already have been
     *            {@linkplain #append(Node) appended}.
     */
    void push(JavaKind kind, ValueNode value);

    /**
     * Pops a value from the frame state stack using an explicit kind.
     *
     * @param slotKind the kind to use when type checking this operation
     * @return the value on the top of the stack
     */
    default ValueNode pop(JavaKind slotKind) {
        throw GraalError.unimplementedParent(); // ExcludeFromJacocoGeneratedReport
    }

    @SuppressWarnings("unused")
    default ValueNode[] popArguments(int argSize) {
        throw GraalError.unimplementedParent(); // ExcludeFromJacocoGeneratedReport
    }

    /**
     * Adds a node and all its inputs to the graph. If the node is in the graph, returns
     * immediately. If the node is a {@link StateSplit} with a null
     * {@linkplain StateSplit#stateAfter() frame state}, the frame state is initialized. A
     * {@link StateSplit} that will be pushed to the stack using
     * {@link #addPush(JavaKind, ValueNode)} should <em>not</em> be added using this method,
     * otherwise its frame state will be initialized with an incorrect stack effect.
     *
     * @param value the value to add to the graph. The {@code value.getJavaKind()} kind is used when
     *            type checking this operation.
     * @return a node equivalent to {@code value} in the graph
     */
    default <T extends Node> T add(T value) {
        if (value.graph() != null) {
            assert !(value instanceof StateSplit) || ((StateSplit) value).stateAfter() != null;
            return value;
        }
        return setStateAfterIfNecessary(this, append(value));
    }

    /**
     * Maybe performs canonicalization on the provided node. Either the result of the
     * canonicalization, or the original node if canonicalization is not possible, is added to the
     * graph and returned. Note that the return value can be null when canonicalization determines
     * that the node can be deleted.
     */
    default Node canonicalizeAndAdd(Node value) {
        return add(value);
    }

    default ValueNode addNonNullCast(ValueNode value) {
        return addNonNullCast(value, DeoptimizationAction.None);
    }

    default ValueNode addNonNullCast(ValueNode value, DeoptimizationAction action) {
        AbstractPointerStamp valueStamp = (AbstractPointerStamp) value.stamp(NodeView.DEFAULT);
        if (valueStamp.nonNull()) {
            return value;
        } else {
            LogicNode isNull = add(IsNullNode.create(value));
            FixedGuardNode fixedGuard = add(new FixedGuardNode(isNull, DeoptimizationReason.NullCheckException, action, true));
            Stamp newStamp = valueStamp.improveWith(StampFactory.objectNonNull());
            return add(PiNode.create(value, newStamp, fixedGuard));
        }
    }

    /**
     * Adds a node with a non-void kind to the graph, pushes it to the stack. If the returned node
     * is a {@link StateSplit} with a null {@linkplain StateSplit#stateAfter() frame state}, the
     * frame state is initialized. A {@link StateSplit} added using this method should <em>not</em>
     * be added using {@link #add(Node)} beforehand, otherwise its frame state will be initialized
     * with an incorrect stack effect.
     *
     * @param kind the kind to use when type checking this operation
     * @param value the value to add to the graph and push to the stack
     * @return a node equivalent to {@code value} in the graph
     */
    default <T extends ValueNode> T addPush(JavaKind kind, T value) {
        T equivalentValue = value.graph() != null ? value : append(value);
        push(kind, equivalentValue);
        return setStateAfterIfNecessary(this, equivalentValue);
    }

    /**
     * Handles an invocation that a plugin determines can replace the original invocation (i.e., the
     * one for which the plugin was applied). This applies all standard graph builder processing to
     * the replaced invocation including applying any relevant plugins.
     *
     * @param invokeKind the kind of the replacement invocation
     * @param targetMethod the target of the replacement invocation
     * @param args the arguments to the replacement invocation
     * @param forceInlineEverything specifies if all invocations encountered in the scope of
     *            handling the replaced invoke are to be force inlined
     */
    Invokable handleReplacedInvoke(InvokeKind invokeKind, ResolvedJavaMethod targetMethod, ValueNode[] args, boolean forceInlineEverything);

    void handleReplacedInvoke(CallTargetNode callTarget, JavaKind resultType);

    /**
     * Creates a snap shot of the current frame state with the BCI of the instruction after the one
     * currently being parsed and assigns it to a given {@linkplain StateSplit#hasSideEffect() side
     * effect} node.
     *
     * @param sideEffect a side effect node just appended to the graph
     */
    void setStateAfter(StateSplit sideEffect);

    /**
     * Like {@link #setStateAfter(StateSplit)}, creates a frame state and assigns it to the given
     * {@linkplain StateSplit#hasSideEffect() side effect} node. Unlike
     * {@link #setStateAfter(StateSplit)}, this variant may skip extra verification of the state
     * that would normally be performed. This provides an escape hatch for special cases where a
     * placeholder state is formally required, but no valid state can be built. For example, the
     * state after a call that never returns might not conform to the current bytecode's expected
     * stack effect.
     *
     * @param sideEffect a side effect node just appended to the graph
     */
    default void setStateAfterSkipVerification(StateSplit sideEffect) {
        setStateAfter(sideEffect);
    }

    /**
     * Gets the parsing context for the method that inlines the method being parsed by this context.
     */
    GraphBuilderContext getParent();

    /**
     * Gets the first ancestor parsing context that is not parsing a {@linkplain #parsingIntrinsic()
     * intrinsic}.
     */
    default GraphBuilderContext getNonIntrinsicAncestor() {
        GraphBuilderContext ancestor = getParent();
        while (ancestor != null && ancestor.parsingIntrinsic()) {
            ancestor = ancestor.getParent();
        }
        return ancestor;
    }

    /**
     * Gets the code being parsed.
     */
    Bytecode getCode();

    /**
     * Gets the method being parsed by this context.
     */
    ResolvedJavaMethod getMethod();

    /**
     * Gets the index of the bytecode instruction currently being parsed.
     */
    int bci();

    default boolean bciCanBeDuplicated() {
        return false;
    }

    /**
     * Gets the kind of invocation currently being parsed.
     */
    InvokeKind getInvokeKind();

    /**
     * Gets the return type of the invocation currently being parsed.
     */
    JavaType getInvokeReturnType();

    default StampPair getInvokeReturnStamp(Assumptions assumptions) {
        JavaType returnType = getInvokeReturnType();
        return StampFactory.forDeclaredType(assumptions, returnType, false);
    }

    /**
     * Gets the inline depth of this context. A return value of 0 implies that this is the context
     * for the parse root.
     */
    default int getDepth() {
        GraphBuilderContext parent = getParent();
        int result = 0;
        while (parent != null) {
            result++;
            parent = parent.getParent();
        }
        return result;
    }

    /**
     * Computes the recursive inlining depth of the provided method, i.e., counts how often the
     * provided method is already in the {@link #getParent()} chain starting at this context.
     */
    default int recursiveInliningDepth(ResolvedJavaMethod method) {
        int result = 0;
        for (GraphBuilderContext cur = this; cur != null; cur = cur.getParent()) {
            if (method.equals(cur.getMethod())) {
                result++;
            }
        }
        return result;
    }

    /**
     * Determines if this parsing context is within the bytecode of an intrinsic or a method inlined
     * by an intrinsic.
     */
    @Override
    default boolean parsingIntrinsic() {
        return getIntrinsic() != null;
    }

    /**
     * Returns true if control flow has terminated in some fashion, such as a deoptimization.
     */
    default boolean hasParseTerminated() {
        throw GraalError.unimplementedParent(); // ExcludeFromJacocoGeneratedReport
    }

    /**
     * Determines if a graph builder plugin is enabled under current context.
     */
    default boolean isPluginEnabled(GraphBuilderPlugin plugin) {
        return parsingIntrinsic() || !(plugin instanceof GeneratedInvocationPlugin && ((GeneratedInvocationPlugin) plugin).isGeneratedFromFoldOrNodeIntrinsic());
    }

    /**
     * Gets the intrinsic of the current parsing context or {@code null} if not
     * {@link #parsingIntrinsic() parsing an intrinsic}.
     */
    IntrinsicContext getIntrinsic();

    boolean isParsingInvocationPlugin();

    BailoutException bailout(String string);

    /**
     * Gets a version of a given value that has a non-null stamp. Emits a guard or an explicit
     * exception check which is triggered if the value is null. Thus, <b> use only for values where
     * the underlying bytecode can throw a {@link NullPointerException}! </b>
     */
    default ValueNode nullCheckedValue(ValueNode value) {
        return nullCheckedValue(value, InvalidateReprofile);
    }

    /**
     * Emit a range check for an intrinsic. This is different from a normal bytecode range check
     * since it might be checking a range of indexes for an operation on an array body.
     */
    default GuardingNode intrinsicRangeCheck(LogicNode condition, boolean negated) {
        assert isParsingInvocationPlugin();
        if (needsExplicitException()) {
            return emitBytecodeExceptionCheck(condition, negated, BytecodeExceptionNode.BytecodeExceptionKind.INTRINSIC_OUT_OF_BOUNDS);
        } else {
            return add(new FixedGuardNode(condition, DeoptimizationReason.BoundsCheckException, DeoptimizationAction.None, !negated));
        }
    }

    /**
     * Gets a version of a given value that has a non-null stamp. Emits a guard or an explicit
     * exception check which is triggered if the value is null. Thus, <b> use only for values where
     * the underlying bytecode can throw a {@link NullPointerException}! </b>
     */
    default ValueNode nullCheckedValue(ValueNode value, DeoptimizationAction action) {
        if (!StampTool.isPointerNonNull(value)) {
            LogicNode condition = getGraph().unique(IsNullNode.create(value));
            GuardingNode guardingNode;
            if (needsExplicitException()) {
                guardingNode = emitBytecodeExceptionCheck(condition, false, BytecodeExceptionNode.BytecodeExceptionKind.NULL_POINTER);
            } else {
                guardingNode = append(new FixedGuardNode(condition, DeoptimizationReason.NullCheckException, action, true));
            }
            return getGraph().addOrUniqueWithInputs(PiNode.create(value, objectNonNull(), guardingNode.asNode()));
        }
        return value;
    }

    /**
     * When {@link #needsExplicitException} is true, the method returns a node with a stamp that is
     * always positive and emits code that throws the provided exceptionKind for a negative length.
     */
    default ValueNode maybeEmitExplicitNegativeArraySizeCheck(ValueNode arrayLength, BytecodeExceptionNode.BytecodeExceptionKind exceptionKind) {
        if (!needsExplicitException() || ((IntegerStamp) arrayLength.stamp(NodeView.DEFAULT)).isPositive()) {
            return arrayLength;
        }
        ConstantNode zero = ConstantNode.defaultForKind(arrayLength.getStackKind());
        LogicNode condition = append(IntegerLessThanNode.create(getConstantReflection(), getMetaAccess(), getOptions(), null, arrayLength, zero, NodeView.DEFAULT));
        ValueNode[] arguments = exceptionKind.getNumArguments() == 1 ? new ValueNode[]{arrayLength} : ValueNode.EMPTY_ARRAY;
        GuardingNode guardingNode = emitBytecodeExceptionCheck(condition, false, exceptionKind, arguments);
        if (guardingNode == null) {
            return arrayLength;
        }
        return append(PiNode.create(arrayLength, StampFactory.positiveInt(), guardingNode.asNode()));
    }

    default ValueNode maybeEmitExplicitNegativeArraySizeCheck(ValueNode arrayLength) {
        return maybeEmitExplicitNegativeArraySizeCheck(arrayLength, BytecodeExceptionNode.BytecodeExceptionKind.NEGATIVE_ARRAY_SIZE);
    }

    default GuardingNode maybeEmitExplicitDivisionByZeroCheck(ValueNode divisor) {
        if (!needsExplicitException() || !((IntegerStamp) divisor.stamp(NodeView.DEFAULT)).contains(0)) {
            return null;
        }
        ConstantNode zero = add(ConstantNode.defaultForKind(divisor.getStackKind()));
        LogicNode condition = add(IntegerEqualsNode.create(getConstantReflection(), getMetaAccess(), getOptions(), null, divisor, zero, NodeView.DEFAULT));
        return emitBytecodeExceptionCheck(condition, false, BytecodeExceptionNode.BytecodeExceptionKind.DIVISION_BY_ZERO);
    }

    default AbstractBeginNode emitBytecodeExceptionCheck(LogicNode condition, boolean passingOnTrue, BytecodeExceptionNode.BytecodeExceptionKind exceptionKind, ValueNode... arguments) {
        if (passingOnTrue ? condition.isTautology() : condition.isContradiction()) {
            return null;
        }

        AbstractBeginNode exceptionPath = genExplicitExceptionEdge(exceptionKind, arguments);

        AbstractBeginNode trueSuccessor = passingOnTrue ? null : exceptionPath;
        AbstractBeginNode falseSuccessor = passingOnTrue ? exceptionPath : null;
        boolean negate = !passingOnTrue;
        BranchProbabilityData probability = BranchProbabilityData.injected(BranchProbabilityNode.EXTREMELY_FAST_PATH_PROBABILITY, negate);
        IfNode ifNode = append(new IfNode(condition, trueSuccessor, falseSuccessor, probability));

        BeginNode passingSuccessor = append(new BeginNode());
        if (passingOnTrue) {
            ifNode.setTrueSuccessor(passingSuccessor);
        } else {
            ifNode.setFalseSuccessor(passingSuccessor);
        }
        return passingSuccessor;
    }

    default void genCheckcastDynamic(ValueNode object, ValueNode javaClass) {
        LogicNode condition = InstanceOfDynamicNode.create(getAssumptions(), getConstantReflection(), javaClass, object, true);
        if (condition.isTautology()) {
            addPush(JavaKind.Object, object);
        } else {
            append(condition);
            GuardingNode guardingNode;
            if (needsExplicitException()) {
                guardingNode = emitBytecodeExceptionCheck(condition, true, BytecodeExceptionNode.BytecodeExceptionKind.CLASS_CAST, object, javaClass);
            } else {
                guardingNode = add(new FixedGuardNode(condition, DeoptimizationReason.ClassCastException, DeoptimizationAction.InvalidateReprofile, false));
            }
            addPush(JavaKind.Object, DynamicPiNode.create(getAssumptions(), getConstantReflection(), object, guardingNode, javaClass));
        }
    }

    /**
     * Some {@link InvocationPlugin InvocationPlugins} have to build a {@link MergeNode} to handle
     * multiple return paths but not all contexts can do this.
     *
     * @return false if {@link #getInvocationPluginReturnState(JavaKind, ValueNode)} cannot be
     *         called (i.e. it unconditionally raises an error)
     */
    default boolean canMergeIntrinsicReturns() {
        assert isParsingInvocationPlugin();
        return false;
    }

    /**
     * Build a FrameState that represents the return from an intrinsic with {@code returnValue} on
     * the top of stack. Usually this will be a state in the caller after the call site.
     */
    @SuppressWarnings("unused")
    default FrameState getInvocationPluginReturnState(JavaKind returnKind, ValueNode returnValue) {
        throw new GraalError("Cannot be called on a " + getClass().getName() + " object");
    }

    /**
     * Build a FrameState that represents the represents the state before an intrinsic was invoked.
     */
    @SuppressWarnings("all")
    default FrameState getInvocationPluginBeforeState() {
        throw new GraalError("Cannot be called on a " + getClass().getName() + " object");
    }

    /**
     * When this returns false, the parser will report an error if an {@link InvocationPlugin}
     * inserts a {@link DeoptimizeNode} or {@link FixedGuardNode}.
     */
    default boolean allowDeoptInPlugins() {
        return !StrictDeoptInsertionChecks.getValue(getOptions());
    }

    /**
     * Returns {@code true} if {@link #invokeFallback} can be called without throwing an
     * unconditional error.
     */
    default boolean canInvokeFallback() {
        return false;
    }

    @SuppressWarnings("all")
    default Invoke invokeFallback(FixedWithNextNode predecessor, EndNode end) {
        throw new GraalError("Cannot be called on a " + getClass().getName() + " object");
    }

    /**
     * Interface whose instances hold inlining information about the current context, in a wider
     * sense. The wider sense in this case concerns graph building approaches that don't necessarily
     * keep a chain of {@link GraphBuilderContext} instances normally available through
     * {@linkplain #getParent()}. Examples of such approaches are partial evaluation and incremental
     * inlining.
     */
    interface ExternalInliningContext {
        int getInlinedDepth();
    }

    default ExternalInliningContext getExternalInliningContext() {
        return null;
    }

    /**
     * Adds masking to a given subword value according to a given {@link JavaKind}, such that the
     * masked value falls in the range of the given kind. In the cases where the given kind is not a
     * subword kind, the input value is returned immediately.
     *
     * @param value the value to be masked
     * @param kind the kind that specifies the range of the masked value
     * @return the masked value
     */
    default ValueNode maskSubWordValue(ValueNode value, JavaKind kind) {
        if (kind == kind.getStackKind()) {
            return value;
        }
        // Subword value
        ValueNode narrow = append(NarrowNode.create(value, kind.getBitCount(), NodeView.DEFAULT));
        if (kind.isUnsigned()) {
            return append(ZeroExtendNode.create(narrow, 32, NodeView.DEFAULT));
        } else {
            return append(SignExtendNode.create(narrow, 32, NodeView.DEFAULT));
        }
    }

    /**
     * @return true if an explicit exception check should be emitted.
     */
    default boolean needsExplicitException() {
        return false;
    }

    /**
     * Generates an exception edge for the current bytecode. When {@link #needsExplicitException()}
     * returns true, this method should return non-null begin nodes.
     *
     * @param exceptionKind the type of exception to be created.
     * @param exceptionArguments the arguments for the exception.
     * @return a begin node that precedes the actual exception instantiation code.
     */
    default AbstractBeginNode genExplicitExceptionEdge(BytecodeExceptionNode.BytecodeExceptionKind exceptionKind, ValueNode... exceptionArguments) {
        throw GraalError.unimplementedParent(); // ExcludeFromJacocoGeneratedReport
    }

    /**
     * Replaces an invocation of a given method by inserting a {@link PluginReplacementNode} that
     * {@linkplain GraphBuilderContext#shouldDeferPlugin defers} the application of an
     * {@link InvocationPlugin}.
     *
     * @param plugin the {@link InvocationPlugin} that is deferred
     * @param targetMethod the target of the replacement invocation
     * @param args the arguments to the replacement invocation
     * @param replacementFunction the replacement function for deferred application of the
     *            {@code plugin}
     */
    default void replacePlugin(GeneratedInvocationPlugin plugin, ResolvedJavaMethod targetMethod, ValueNode[] args, PluginReplacementNode.ReplacementFunction replacementFunction) {
        throw GraalError.unimplementedParent(); // ExcludeFromJacocoGeneratedReport
    }

    /**
     * Replaces an invocation of a given method by inserting a
     * {@link PluginReplacementWithExceptionNode} that
     * {@linkplain GraphBuilderContext#shouldDeferPlugin defers} the application of an
     * {@link InvocationPlugin}.
     *
     * @param plugin the {@link InvocationPlugin} that is deferred
     * @param targetMethod the target of the replacement invocation
     * @param args the arguments to the replacement invocation
     * @param replacementFunction the replacement function for deferred application of the
     *            {@code plugin}
     */
    default void replacePluginWithException(GeneratedInvocationPlugin plugin, ResolvedJavaMethod targetMethod, ValueNode[] args,
                    PluginReplacementWithExceptionNode.ReplacementWithExceptionFunction replacementFunction) {
        throw GraalError.unimplementedParent(); // ExcludeFromJacocoGeneratedReport
    }

    static <T extends Node> T setStateAfterIfNecessary(GraphBuilderContext b, T value) {
        if (value instanceof StateSplit) {
            StateSplit stateSplit = (StateSplit) value;
            FrameState oldState = stateSplit.stateAfter();
            if (stateSplit.stateAfter() == null && (stateSplit.hasSideEffect() || stateSplit instanceof AbstractMergeNode)) {
                b.setStateAfter(stateSplit);
            }
            FrameState newState = stateSplit.stateAfter();
            GraalError.guarantee(oldState == null || oldState.equals(newState),
                            "graph builder changed existing state on %s from %s to %s, this indicates multiple calls to add() or addPush() for one node",
                            stateSplit, oldState, newState);
        }
        return value;
    }

    /**
     * Determine if the given basic block is inside a {@code try} block of an exception handler
     * catching {@link OutOfMemoryError} exceptions.
     */
    default boolean currentBlockCatchesOOME() {
        return false;
    }

    /**
     * Iff this parsing context is processing a method that is annotated with
     * {@link ScopedMemoryAccess} saves the associated session object.
     *
     * @param scopedMemorySession the currently parsed session of this context
     */
    default void setIsParsingScopedMemoryMethod(ValueNode scopedMemorySession) {
        // nothing to do
    }

    /**
     * Determines if the current parsing context has set any scoped memory access that needs to be
     * handled.
     */
    default List<ValueNode> getScopedMemorySessions() {
        return null;
    }
}
