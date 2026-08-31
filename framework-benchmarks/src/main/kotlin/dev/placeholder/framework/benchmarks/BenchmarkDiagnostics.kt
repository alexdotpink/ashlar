package dev.placeholder.framework.benchmarks

import java.nio.file.Files
import java.nio.file.Path
import jdk.jfr.Recording

/** Runs a separate diagnostic pass so profiling never changes gate samples. */
public object BenchmarkDiagnostics {
    /** Records allocation, GC, lock, and execution samples while [block] runs. */
    public fun <T> record(destination: Path, block: () -> T): T {
        destination.parent?.let(Files::createDirectories)
        return Recording().use { recording ->
            recording.name = "framework-benchmark-diagnostic"
            recording.enable("jdk.ExecutionSample")
            recording.enable("jdk.ObjectAllocationInNewTLAB")
            recording.enable("jdk.ObjectAllocationOutsideTLAB")
            recording.enable("jdk.GarbageCollection")
            recording.enable("jdk.JavaMonitorEnter")
            recording.start()
            try {
                block()
            } finally {
                recording.stop()
                recording.dump(destination)
            }
        }
    }
}
