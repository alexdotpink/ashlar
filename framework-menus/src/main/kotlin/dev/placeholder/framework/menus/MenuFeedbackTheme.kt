package dev.placeholder.framework.menus

import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component

/** Native presentation selected for one semantic feedback value. */
public data class MenuFeedbackPresentation(
    public val actionBar: Component? = null,
    public val sound: Sound? = null,
    public val emphasizeTarget: Boolean = false,
)

/** Scoped policy mapping semantic feedback to player-visible transports. */
public fun interface MenuFeedbackTheme {
    /** Maps [feedback] without changing its domain meaning. */
    public fun present(feedback: MenuFeedback): MenuFeedbackPresentation
}

/** Restrained default feedback mapping used unless a subtree provides another theme. */
public val DefaultMenuFeedbackTheme: MenuFeedbackTheme = MenuFeedbackTheme { feedback ->
    val sound = when (feedback.severity) {
        MenuFeedbackSeverity.INFO -> null
        MenuFeedbackSeverity.SUCCESS -> "entity.experience_orb.pickup"
        MenuFeedbackSeverity.WARNING -> "block.note_block.bass"
        MenuFeedbackSeverity.REJECTION -> "entity.villager.no"
    }?.let { key -> Sound.sound(Key.key(key), Sound.Source.PLAYER, 0.65f, 1f) }
    MenuFeedbackPresentation(
        actionBar = feedback.message,
        sound = sound,
        emphasizeTarget = feedback.targetSlot != null,
    )
}

/** Presentation local controlling feedback beneath the current render subtree. */
public val MenuFeedbackThemeLocal: MenuLocal<MenuFeedbackTheme> = menuLocal("framework.feedback-theme") {
    DefaultMenuFeedbackTheme
}
