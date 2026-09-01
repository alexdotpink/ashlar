package pink.alex.ashlar.benchmarks.internal;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class JmhScenarioBenchmark {
    @Benchmark
    public Object measure(ScenarioState state) {
        return state.bridge.measure();
    }

    @State(Scope.Benchmark)
    public static class ScenarioState {
        private JmhScenarioBridge bridge;

        @Setup(Level.Trial)
        public void create() {
            bridge = JmhScenarioBridge.fromSystemProperties();
        }

        @Setup(Level.Invocation)
        public void setupInvocation() {
            bridge.setupInvocation();
        }

        @TearDown(Level.Invocation)
        public void verifyInvocation() {
            bridge.verifyInvocation();
        }

        @TearDown(Level.Trial)
        public void close() {
            bridge.close();
        }
    }
}
