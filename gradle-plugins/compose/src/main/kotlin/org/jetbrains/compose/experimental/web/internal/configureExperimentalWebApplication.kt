/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.experimental.web.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.UnresolvedDependency
import org.gradle.api.provider.Provider
import org.jetbrains.compose.ComposeBuildConfig
import org.jetbrains.compose.experimental.dsl.ExperimentalWebApplication
import org.jetbrains.compose.experimental.web.tasks.ExperimentalUnpackSkikoWasmRuntimeTask
import org.jetbrains.compose.internal.utils.*
import org.jetbrains.compose.internal.utils.registerTask
import org.jetbrains.kotlin.gradle.tasks.IncrementalSyncTask

internal fun configureExperimentalWebApplication(
    project: Project,
    app: ExperimentalWebApplication
) {
    val skikoJsWasmRuntimeConfiguration = project.configurations.create("COMPOSE_SKIKO_JS_WASM_RUNTIME")
    val skikoJsWasmRuntimeDependency = skikoVersionProvider(project).map { skikoVersion ->
        project.dependencies.create("org.jetbrains.skiko:skiko-js-wasm-runtime:$skikoVersion")
    }
    skikoJsWasmRuntimeConfiguration.defaultDependencies {
        it.addLater(skikoJsWasmRuntimeDependency)
    }
    val unpackedRuntimeDir = project.layout.buildDirectory.dir("compose/skiko-wasm/")
    val taskName = "unpackSkikoWasmRuntime"

    val unpackRuntime = project.registerTask<ExperimentalUnpackSkikoWasmRuntimeTask>(taskName) {
        skikoRuntimeFiles = skikoJsWasmRuntimeConfiguration
        outputDir.set(unpackedRuntimeDir)
    }
    // According to https://youtrack.jetbrains.com/issue/COMPOSE-1114
    project.tasks.withType(IncrementalSyncTask::class.java) {
        it.dependsOn(unpackRuntime)
        it.from.from(unpackedRuntimeDir)
    }
}

private const val SKIKO_GROUP = "org.jetbrains.skiko"

private fun skikoVersionProvider(project: Project): Provider<String> {
    val composeVersion = ComposeBuildConfig.composeVersion
    val configurationWithSkiko = project.detachedComposeDependency(
        artifactId = "ui-graphics",
        groupId = "org.jetbrains.compose.ui"
    )
    return project.provider {
        val skikoDependency = configurationWithSkiko.allDependenciesDescriptors.firstOrNull(::isSkikoDependency)
        skikoDependency?.version
            ?: error("Cannot determine the version of Skiko for Compose '$composeVersion'")
    }
}

private fun isSkikoDependency(dep: DependencyDescriptor): Boolean =
    dep.group == SKIKO_GROUP && dep.version != null

private val Configuration.allDependenciesDescriptors: Sequence<DependencyDescriptor>
    get() = with (resolvedConfiguration.lenientConfiguration) {
        allModuleDependencies.asSequence().map { ResolvedDependencyDescriptor(it) } +
                unresolvedModuleDependencies.asSequence().map { UnresolvedDependencyDescriptor(it) }
    }

private abstract class DependencyDescriptor {
    abstract val group: String?
    abstract val name: String?
    abstract val version: String?
}

private class ResolvedDependencyDescriptor(private val dependency: ResolvedDependency) : DependencyDescriptor() {
    override val group: String?
        get() = dependency.moduleGroup

    override val name: String?
        get() = dependency.moduleName

    override val version: String?
        get() = dependency.moduleVersion
}

private class UnresolvedDependencyDescriptor(private val dependency: UnresolvedDependency) : DependencyDescriptor() {
    override val group: String?
        get() = dependency.selector.group

    override val name: String?
        get() = dependency.selector.name

    override val version: String?
        get() = dependency.selector.version
}