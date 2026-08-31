package dev.placeholder.framework.sample

import dev.placeholder.framework.ComponentContext
import dev.placeholder.framework.FrameworkPlugin
import dev.placeholder.framework.commands.ExcludeCommandContributions
import dev.placeholder.framework.events.ExcludeEventContributions

/** Runnable command and event showcase for Paper and Folia. */
@ExcludeCommandContributions(ExcludedShowcaseCommands::class)
@ExcludeEventContributions(ExcludedSampleEvents::class)
public class SamplePlugin : FrameworkPlugin() {
    override fun ComponentContext.enable() {
        logger.info("Framework showcase enabled; use /showcase, /events, or /input")
    }

    override fun ComponentContext.disable() {
        logger.info("Framework showcase stopped")
    }
}
