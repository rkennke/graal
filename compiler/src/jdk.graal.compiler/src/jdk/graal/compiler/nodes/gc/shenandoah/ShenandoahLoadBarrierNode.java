package jdk.graal.compiler.nodes.gc.shenandoah;

import jdk.graal.compiler.core.common.memory.BarrierType;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.lir.gen.ShenandoahBarrierSetLIRGeneratorTool;
import jdk.graal.compiler.nodeinfo.InputType;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodeinfo.StructuralInput;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.UnaryNode;
import jdk.graal.compiler.nodes.memory.address.AddressNode;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.LIRLowerable;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_64;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_16;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_64;

@NodeInfo(cycles = CYCLES_64, size = SIZE_64)
public final class ShenandoahLoadBarrierNode extends ValueNode implements LIRLowerable {
    public static final NodeClass<ShenandoahLoadBarrierNode> TYPE = NodeClass.create(ShenandoahLoadBarrierNode.class);

    @Input
    private ValueNode value;

    public enum ReferenceStrength {
        STRONG, WEAK, PHANTOM;
    };

    @Input(InputType.Association)
    private AddressNode address;

    private final ReferenceStrength strength;
    private final boolean narrow;

    private static ReferenceStrength getReferenceStrength(BarrierType barrierType) {
        return switch (barrierType) {
            case READ, FIELD, ARRAY, NONE -> ReferenceStrength.STRONG;
            case REFERENCE_GET, WEAK_REFERS_TO -> ReferenceStrength.WEAK;
            case PHANTOM_REFERS_TO -> ReferenceStrength.PHANTOM;
            case UNKNOWN, POST_INIT_WRITE, AS_NO_KEEPALIVE_WRITE -> throw GraalError.shouldNotReachHere("Unexpected barrier type: " + barrierType);
        };
    }

    public ShenandoahLoadBarrierNode(ValueNode value, AddressNode address, BarrierType barrierType, boolean narrow) {
        super(TYPE, value.stamp(NodeView.DEFAULT));
        this.value = value;
        this.address = address;
        this.strength = getReferenceStrength(barrierType);
        this.narrow = narrow;
        //System.out.println("New ShenandoahLoadBarrierNode");
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
//        System.out.println("generate LRB for input: " + value);
//        System.out.println("usages:");
//        for (Node usage : usages()) {
//            System.out.println("usage: " + usage);
//        }
        ShenandoahBarrierSetLIRGeneratorTool tool = (ShenandoahBarrierSetLIRGeneratorTool) gen.getLIRGeneratorTool().getBarrierSet();
        gen.setResult(this, tool.emitLoadReferenceBarrier(gen.getLIRGeneratorTool(), gen.operand(value), gen.operand(address), strength, narrow));
    }
}
