package dev.placeholder.framework.commands.minecraft

/** Centers integral fine-position coordinates on their containing block. */
@Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class CenterIntegers

/** Sets the smallest accepted value for a Minecraft time argument. */
@Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class MinimumTicks(public val value: Int = 0)

/** Selects the registry used by a typed registry value or key argument. */
@Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class FromRegistry(public val value: String)
