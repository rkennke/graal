package jdk.graal.compiler.nodes.gc.shenandoah;

import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.gc.ArrayRangeWriteBarrierNode;
import jdk.graal.compiler.nodes.memory.address.AddressNode;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_64;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_64;

@NodeInfo(cycles = CYCLES_64, size = SIZE_64)
public final class ShenandoahArrayRangePreWriteBarrierNode extends ArrayRangeWriteBarrierNode {
    public static final NodeClass<jdk.graal.compiler.nodes.gc.G1ArrayRangePreWriteBarrierNode> TYPE = NodeClass.create(jdk.graal.compiler.nodes.gc.G1ArrayRangePreWriteBarrierNode.class);

    public ShenandoahArrayRangePreWriteBarrierNode(AddressNode address, ValueNode length, int elementStride) {
        super(TYPE, address, length, elementStride);
    }

    @Override
    public Kind getKind() {
        return Kind.PRE_BARRIER;
    }
}
