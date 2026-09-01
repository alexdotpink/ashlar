package pink.alex.ashlar.sample

import pink.alex.ashlar.ComponentContext
import pink.alex.ashlar.AshlarPlugin
import pink.alex.ashlar.commands.ExcludeCommandContributions
import pink.alex.ashlar.events.ExcludeEventContributions

/** Runnable framework showcase for Paper and Folia. */
@ExcludeCommandContributions(ExcludedShowcaseCommands::class)
@ExcludeEventContributions(ExcludedSampleEvents::class)
public class SamplePlugin : AshlarPlugin() {
    override fun ComponentContext.enable() {
        logger.info("Ashlar showcase enabled; use /showcase, /events, /input, /menus, or /config")
    }

    override fun ComponentContext.disable() {
        logger.info("Ashlar showcase stopped")
    }
}
