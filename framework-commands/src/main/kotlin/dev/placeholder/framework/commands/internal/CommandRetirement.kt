package dev.placeholder.framework.commands.internal

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job

internal class CommandRetirement {
    private val jobs: MutableMap<UUID, MutableSet<Job>> = ConcurrentHashMap()

    fun track(executor: UUID, job: Job) {
        jobs.computeIfAbsent(executor) { ConcurrentHashMap.newKeySet() }.add(job)
        job.invokeOnCompletion {
            jobs[executor]?.let { running ->
                running.remove(job)
                if (running.isEmpty()) jobs.remove(executor, running)
            }
        }
    }

    fun retire(executor: UUID) {
        jobs.remove(executor)?.forEach { job -> job.cancel() }
    }
}
