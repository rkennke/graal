package jdk.graal.compiler.nodes.gc.shenandoah;

import jdk.graal.compiler.core.common.type.ObjectStamp;
import jdk.graal.compiler.debug.Assertions;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.lir.gen.LIRGeneratorTool;
import jdk.graal.compiler.lir.gen.ShenandoahBarrierSetLIRGeneratorTool;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.gc.ObjectWriteBarrierNode;
import jdk.graal.compiler.nodes.memory.address.AddressNode;
import jdk.graal.compiler.nodes.spi.LIRLowerable;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Value;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_64;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_64;

@NodeInfo(cycles = CYCLES_64, size = SIZE_64)
public final class ShenandoahPreWriteBarrierNode extends ObjectWriteBarrierNode implements LIRLowerable {
    public static final NodeClass<ShenandoahPreWriteBarrierNode> TYPE = NodeClass.create(ShenandoahPreWriteBarrierNode.class);

    private final boolean doLoad;

    public ShenandoahPreWriteBarrierNode(AddressNode address, ValueNode expectedObject, boolean doLoad) {
        super(TYPE, address, expectedObject, true);
        assert doLoad == (expectedObject == null) : Assertions.errorMessageContext("adr", address, "expectedO", expectedObject, "doLoad", doLoad);
        this.doLoad = doLoad;
    }

    public ValueNode getExpectedObject() {
        return getValue();
    }

    public boolean doLoad() {
        return doLoad;
    }

    @Override
    public Kind getKind() {
        return Kind.PRE_BARRIER;
    }

    @Override
    public void generate(NodeLIRBuilderTool generator) {
        ValueNode expectedObject = getExpectedObject();
        if (expectedObject == null || !expectedObject.isJavaConstant() || !expectedObject.asJavaConstant().isNull()) {
            AllocatableValue operand = Value.ILLEGAL;
            boolean nonNull = false;
            LIRGeneratorTool lirGen = generator.getLIRGeneratorTool();
            if (expectedObject != null) {
                operand = lirGen.asAllocatable(generator.operand(expectedObject));
                nonNull = ((ObjectStamp) expectedObject.stamp(NodeView.DEFAULT)).nonNull();
                GraalError.guarantee(expectedObject.stamp(NodeView.DEFAULT) instanceof ObjectStamp, "expecting full size object");
            }
            ShenandoahBarrierSetLIRGeneratorTool tool = (ShenandoahBarrierSetLIRGeneratorTool) generator.getLIRGeneratorTool().getBarrierSet();
            tool.emitPreWriteBarrier(lirGen, generator.operand(getAddress()), operand, nonNull);
        }
    }
}
