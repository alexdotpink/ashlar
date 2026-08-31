package dev.placeholder.framework.di

import kotlin.reflect.KClass

/** Requests a generated direct constructor factory for this class or constructor. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.CONSTRUCTOR)
@Retention(AnnotationRetention.BINARY)
public annotation class Inject

/** Adds an injected implementation to the plug-in's generated contribution index. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Contributes

/** Marks a typed annotation as a dependency qualifier. */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class DependencyQualifier

/** Binds an injected implementation to additional dependency types. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Binds(vararg val types: KClass<*>)

/** Caches one dependency instance for the lifetime of its plug-in graph. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class PluginScoped

/** Creates one dependency instance for each command invocation graph. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class InvocationScoped

/** Creates a new dependency instance at every injection point. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Factory
