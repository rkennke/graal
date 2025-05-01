#!/bin/bash

SPEC_PATH="/wf/jmh-specjvm2016.jar"
RENAISSANCE_PATH="/wf/benchmarks/renaissance-gpl-0.16.0.jar"
renaissance_benchs=(scrabble page-rank future-genetic akka-uct movie-lens scala-doku chi-square fj-kmeans rx-scrabble db-shootout neo4j-analytics finagle-http reactors dec-tree scala-stm-bench7 naive-bayes als par-mnemonics scala-kmeans philosophers log-regression gauss-mix mnemonics dotty finagle-chirper)


for rb in ${renaissance_benchs[@]} ; do
    #mx vm -Xcomp -Djdk.graal.CompilationFailureAction=Print -XX:+UseShenandoahGC -Xms1g -Xmx2g -jar ${RENAISSANCE_PATH} ${rb}
    mx vm -XX:+UseCompressedOops -Djdk.graal.CompilationFailureAction=Print -XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational -Xms8g -Xmx8g -jar ${RENAISSANCE_PATH} ${rb}
done

#mx vm -XX:-UseCompressedOops -XX:+UnlockDiagnosticVMOptions -XX:+AbortVMOnCompilationFailure -XX:+UseShenandoahGC -XX:+UnlockExperimentalVMOptions -Xlog:gc -Xmx8g -Xms8g -jar ${SPEC_PATH} -f 0 Compiler.compiler
#mx vm -XX:+UseCompressedOops -XX:+UnlockDiagnosticVMOptions -XX:+AbortVMOnCompilationFailure -XX:+UseShenandoahGC -XX:+UnlockExperimentalVMOptions -Xlog:gc -Xmx8g -Xms8g -jar ${SPEC_PATH} -f 0 Compiler.compiler
#mx vm -XX:+UseCompressedOops -XX:+UnlockDiagnosticVMOptions -XX:+AbortVMOnCompilationFailure -XX:+UseShenandoahGC -XX:+UnlockExperimentalVMOptions -XX:ShenandoahGCMode=generational -Xlog:gc -Xmx8g -Xms8g -jar ${SPEC_PATH} -f 0 Compiler.compiler



#mx vm -Xcomp -Djdk.graal.CompilationFailureAction=Print -Xlog:gc* -XX:+UseShenandoahGC -Xms1g -Xmx2g -jar /wf/benchmarks/renaissance-gpl-0.16.0.jar all

#mx vm -Xcomp -Djdk.graal.CompilationFailureAction=Print -Xlog:gc* -XX:+UseShenandoahGC -Xms1g -Xmx2g -jar /wf/benchmarks/renaissance-gpl-0.16.0.jar all


#mx -v vm -Xcomp -XX:+UseShenandoahGC -XX:+UseCondCardMark -XX:LockingMode=1 -XX:-UseCompressedOops -Xms1g -Xmx2g -jar /wf/benchmarks/renaissance-gpl-0.16.0.jar all
#mx vm -XX:+UseShenandoahGC -Xms1g -Xmx2g -jar /wf/benchmarks/renaissance-gpl-0.16.0.jar all

#mx vm -Xcomp -XX:CompileCommand="compileonly,*Hello::test" -XX:CompileCommand="dontinline,*Hello::*" -XX:CompileCommand="dontinline,*Picture::*" -XX:CompileCommand="dontinline,*Point::*" -XX:+UseShenandoahGC -Xms128m -Xmx128m Hello
#
#


