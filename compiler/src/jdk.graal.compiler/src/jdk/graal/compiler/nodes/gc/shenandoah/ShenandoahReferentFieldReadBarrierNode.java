package jdk.graal.compiler.nodes.gc.shenandoah;

import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.lir.gen.LIRGeneratorTool;
import jdk.graal.compiler.lir.gen.ShenandoahBarrierSetLIRGeneratorTool;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.gc.ObjectWriteBarrierNode;
import jdk.graal.compiler.nodes.memory.address.AddressNode;
import jdk.graal.compiler.nodes.spi.LIRLowerable;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_64;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_64;

@NodeInfo(cycles = CYCLES_64, size = SIZE_64)
public class ShenandoahReferentFieldReadBarrierNode extends ObjectWriteBarrierNode implements LIRLowerable {
    public static final NodeClass<ShenandoahReferentFieldReadBarrierNode> TYPE = NodeClass.create(ShenandoahReferentFieldReadBarrierNode.class);

    public ShenandoahReferentFieldReadBarrierNode(AddressNode address, ValueNode expectedObject) {
        super(TYPE, address, expectedObject, true);
    }

    public ValueNode getExpectedObject() {
        return getValue();
    }

    @Override
    public Kind getKind() {
        return Kind.PRE_BARRIER;
    }

    @Override
    public void generate(NodeLIRBuilderTool generator) {
        LIRGeneratorTool lirGen = generator.getLIRGeneratorTool();
        ShenandoahBarrierSetLIRGeneratorTool tool = (ShenandoahBarrierSetLIRGeneratorTool) generator.getLIRGeneratorTool().getBarrierSet();
        tool.emitPreWriteBarrier(lirGen, generator.operand(getAddress()), lirGen.asAllocatable(generator.operand(getExpectedObject())), false);
    }
}
