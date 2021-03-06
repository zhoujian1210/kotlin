/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.gradle.internal

import com.android.build.gradle.BaseExtension
import com.android.build.gradle.api.AndroidSourceSet
import com.android.builder.model.SourceProvider
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskDependency
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.tasks.CompilerPluginOptions
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin.Companion.getKaptGeneratedSourcesDir
import org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin.Companion.getKaptGeneratedClassesDir
import org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin.Companion.getKaptGeneratedKotlinSourcesDir
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.util.*

// apply plugin: 'kotlin-kapt'
class Kapt3GradleSubplugin : Plugin<Project> {
    companion object {
        fun isEnabled(project: Project) = project.plugins.findPlugin(Kapt3GradleSubplugin::class.java) != null

        @JvmStatic
        fun getKaptGeneratedClassesDir(project: Project, sourceSetName: String) =
                File(project.project.buildDir, "tmp/kapt3/classes/$sourceSetName")

        @JvmStatic
        fun getKaptGeneratedSourcesDir(project: Project, sourceSetName: String) =
                File(project.project.buildDir, "generated/source/kapt/$sourceSetName")

        @JvmStatic
        fun getKaptGeneratedKotlinSourcesDir(project: Project, sourceSetName: String) =
                File(project.project.buildDir, "generated/source/kaptKotlin/$sourceSetName")
    }

    override fun apply(project: Project) {}
}

abstract class KaptVariantData<T>(val variantData: T) {
    abstract val name: String
    abstract val sourceProviders: Iterable<SourceProvider>
    abstract fun addJavaSourceFoldersToModel(generatedFilesDir: File)
    abstract val annotationProcessorOptions: Map<String, String>?
    abstract fun registerGeneratedJavaSource(
            project: Project,
            kaptTask: KaptTask,
            javaTask: AbstractCompile)
}

// Subplugin for the Kotlin Gradle plugin
class Kapt3KotlinGradleSubplugin : KotlinGradleSubplugin<KotlinCompile> {
    companion object {
        private val VERBOSE_OPTION_NAME = "kapt.verbose"

        val MAIN_KAPT_CONFIGURATION_NAME = "kapt"

        val KAPT_GROUP_NAME = "org.jetbrains.kotlin"
        val KAPT_ARTIFACT_NAME = "kotlin-annotation-processing-gradle"

        fun getKaptConfigurationName(sourceSetName: String): String {
            return if (sourceSetName != "main")
                "$MAIN_KAPT_CONFIGURATION_NAME${sourceSetName.capitalize()}"
            else
                MAIN_KAPT_CONFIGURATION_NAME
        }

        fun Project.findKaptConfiguration(sourceSetName: String): Configuration? {
            return project.configurations.findByName(getKaptConfigurationName(sourceSetName))
        }

        fun findMainKaptConfiguration(project: Project) = project.findKaptConfiguration(MAIN_KAPT_CONFIGURATION_NAME)
    }

    private val kotlinToKaptGenerateStubsTasksMap = mutableMapOf<KotlinCompile, KaptGenerateStubsTask>()

    override fun isApplicable(project: Project, task: AbstractCompile) = task is KotlinCompile && Kapt3GradleSubplugin.isEnabled(project)

    private fun Kapt3SubpluginContext.getKaptStubsDir() = createAndReturnTemporaryKaptDirectory("stubs")

    private fun Kapt3SubpluginContext.getKaptIncrementalDataDir() = createAndReturnTemporaryKaptDirectory("incrementalData")

    private fun Kapt3SubpluginContext.createAndReturnTemporaryKaptDirectory(name: String): File {
        val dir = File(project.buildDir, "tmp/kapt3/$name/$sourceSetName")
        dir.mkdirs()
        return dir
    }

    private inner class Kapt3SubpluginContext(
            val project: Project,
            val kotlinCompile: KotlinCompile,
            val javaCompile: AbstractCompile,
            val kaptVariantData: KaptVariantData<*>?,
            val sourceSetName: String,
            val javaSourceSet: SourceSet?,
            val kaptExtension: KaptExtension,
            val kaptClasspath: MutableList<File>) {
        val sourcesOutputDir = getKaptGeneratedSourcesDir(project, sourceSetName)
        val kotlinSourcesOutputDir = getKaptGeneratedKotlinSourcesDir(project, sourceSetName)
        val classesOutputDir = getKaptGeneratedClassesDir(project, sourceSetName)

        val kaptClasspathArtifacts = project
                .resolveSubpluginArtifacts(listOf(this@Kapt3KotlinGradleSubplugin))
                .flatMap { it.value }
    }

    override fun apply(
            project: Project,
            kotlinCompile: KotlinCompile,
            javaCompile: AbstractCompile,
            variantData: Any?,
            androidProjectHandler: Any?,
            javaSourceSet: SourceSet?
    ): List<SubpluginOption> {
        assert((variantData != null) xor (javaSourceSet != null))

        val kaptClasspath = arrayListOf<File>()
        val buildDependencies = arrayListOf<TaskDependency>()
        val kaptConfigurations = arrayListOf<Configuration>()

        fun handleSourceSet(sourceSetName: String) {
            val kaptConfiguration = project.findKaptConfiguration(sourceSetName)
            val filteredDependencies = kaptConfiguration?.dependencies?.filter {
                it.group != getGroupName() || it.name != getArtifactName()
            } ?: emptyList()

            if (kaptConfiguration != null) {
                kaptConfigurations += kaptConfiguration
                buildDependencies += kaptConfiguration.buildDependencies

                if (filteredDependencies.isNotEmpty()) {
                    kaptClasspath.addAll(kaptConfiguration.resolve())
                }
            }
        }

        val kaptVariantData = variantData as? KaptVariantData<*>

        val sourceSetName = if (kaptVariantData != null) {
            for (provider in kaptVariantData.sourceProviders) {
                handleSourceSet((provider as AndroidSourceSet).name)
            }

            kaptVariantData.name
        }
        else {
            if (javaSourceSet == null) error("Java source set should not be null")

            handleSourceSet(javaSourceSet.name)
            javaSourceSet.name
        }

        val kaptExtension = project.extensions.getByType(KaptExtension::class.java)

        val context = Kapt3SubpluginContext(project, kotlinCompile, javaCompile,
                kaptVariantData, sourceSetName, javaSourceSet, kaptExtension, kaptClasspath)

        val kaptGenerateStubsTask = context.createKaptGenerateStubsTask()
        val kaptTask = context.createKaptKotlinTask()

        kaptGenerateStubsTask.source(*kaptConfigurations.toTypedArray())

        kaptGenerateStubsTask.dependsOn(*buildDependencies.toTypedArray())
        kaptGenerateStubsTask.dependsOn(*kotlinCompile.dependsOn.toTypedArray())

        kaptTask.dependsOn(kaptGenerateStubsTask)
        kotlinCompile.dependsOn(kaptTask)

        /** Plugin options are applied to kapt*Compile inside [createKaptKotlinTask] */
        return emptyList()
    }

    override fun getSubpluginKotlinTasks(project: Project, kotlinCompile: KotlinCompile): List<AbstractCompile> {
        val kaptGenerateStubsTask = kotlinToKaptGenerateStubsTasksMap[kotlinCompile]
        return if (kaptGenerateStubsTask == null) emptyList() else listOf(kaptGenerateStubsTask)
    }

    // This method should be called no more than once for each Kapt3SubpluginContext
    private fun Kapt3SubpluginContext.buildOptions(aptMode: String): List<SubpluginOption> {
        val pluginOptions = mutableListOf<SubpluginOption>()

        val generatedFilesDir = getKaptGeneratedSourcesDir(project, sourceSetName)
        kaptVariantData?.addJavaSourceFoldersToModel(generatedFilesDir)

        pluginOptions += SubpluginOption("aptMode", aptMode)
        disableAnnotationProcessingInJavaTask()

        kaptClasspath.forEach { pluginOptions += FilesSubpluginOption("apclasspath", listOf(it)) }

        javaCompile.source(generatedFilesDir)

        pluginOptions += FilesSubpluginOption("sources", listOf(generatedFilesDir))
        pluginOptions += FilesSubpluginOption("classes", listOf(getKaptGeneratedClassesDir(project, sourceSetName)))

        pluginOptions += FilesSubpluginOption("incrementalData", listOf(getKaptIncrementalDataDir()))

        val annotationProcessors = kaptExtension.processors
        if (annotationProcessors.isNotEmpty()) {
            pluginOptions += SubpluginOption("processors", annotationProcessors)
        }

        val androidPlugin = kaptVariantData?.let {
            project.extensions.findByName("android") as? BaseExtension
        }

        val androidOptions = kaptVariantData?.annotationProcessorOptions ?: emptyMap()

        kotlinSourcesOutputDir.mkdirs()

        val apOptions =
                (kaptExtension.getAdditionalArguments(project, kaptVariantData?.variantData, androidPlugin) + androidOptions)
                        .map { SubpluginOption(it.key, it.value) } +
                FilesSubpluginOption("kapt.kotlin.generated", listOf(kotlinSourcesOutputDir))

        pluginOptions += CompositeSubpluginOption("apoptions", encodeList(apOptions.associate { it.key to it.value }), apOptions)

        pluginOptions += SubpluginOption("javacArguments", encodeList(kaptExtension.getJavacOptions()))

        addMiscOptions(pluginOptions)

        return pluginOptions
    }

    private fun Kapt3SubpluginContext.buildAndAddOptionsTo(task: Task, container: CompilerPluginOptions, aptMode: String) {
        val compilerPluginId = getCompilerPluginId()
        val kaptSubpluginOptions = wrapPluginOptions(buildOptions(aptMode), "configuration")
        task.registerSubpluginOptionsAsInputs(compilerPluginId, kaptSubpluginOptions)

        for (option in kaptSubpluginOptions) {
            container.addPluginArgument(compilerPluginId, option)
        }

        // Also register all the subplugin options from the Kotlin task:
        project.afterEvaluate {
            kotlinCompile.pluginOptions.subpluginOptionsByPluginId.forEach { (pluginId, options) ->
                task.registerSubpluginOptionsAsInputs("kotlinCompile.$pluginId", options)
            }
        }
    }

    private fun encodeList(options: Map<String, String>): String {
        val os = ByteArrayOutputStream()
        val oos = ObjectOutputStream(os)

        oos.writeInt(options.size)
        for ((key, value) in options.entries) {
            oos.writeUTF(key)
            oos.writeUTF(value)
        }

        oos.flush()
        return Base64.getEncoder().encodeToString(os.toByteArray())
    }

    private fun Kapt3SubpluginContext.addMiscOptions(pluginOptions: MutableList<SubpluginOption>) {
        if (kaptExtension.generateStubs) {
            project.logger.warn("'kapt.generateStubs' is not used by the 'kotlin-kapt' plugin")
        }

        pluginOptions += SubpluginOption("useLightAnalysis", "${kaptExtension.useLightAnalysis}")
        pluginOptions += SubpluginOption("correctErrorTypes", "${kaptExtension.correctErrorTypes}")
        pluginOptions += FilesSubpluginOption("stubs", listOf(getKaptStubsDir()))

        if (project.hasProperty(VERBOSE_OPTION_NAME) && project.property(VERBOSE_OPTION_NAME) == "true") {
            pluginOptions += SubpluginOption("verbose", "true")
        }
    }

    private fun Kapt3SubpluginContext.createKaptKotlinTask(): KaptTask {
        val kaptTask = project.tasks.create(
                getKaptTaskName("kapt"),
                KaptTask::class.java)

        kaptTask.useBuildCache = kaptExtension.useBuildCache

        kaptTask.kotlinCompileTask = kotlinCompile

        kaptClasspathArtifacts.forEach { kaptTask.pluginOptions.addClasspathEntry(it) }

        kaptTask.stubsDir = getKaptStubsDir()
        kaptTask.destinationDir = sourcesOutputDir
        kaptTask.kotlinSourcesDestinationDir = kotlinSourcesOutputDir
        kaptTask.classesDir = classesOutputDir

        javaSourceSet?.output?.apply {
            if (tryAddClassesDir { project.files(classesOutputDir).builtBy(kaptTask) }) {
                kotlinCompile.attachClassesDir { classesOutputDir }
            }
        }

        kotlinCompile.source(sourcesOutputDir, kotlinSourcesOutputDir)

        if (kaptVariantData != null) {
            kaptVariantData.registerGeneratedJavaSource(project, kaptTask, javaCompile)
        }
        else {
            registerGeneratedJavaSource(kaptTask, javaCompile)
        }

        kaptTask.kaptClasspath = kaptClasspath

        buildAndAddOptionsTo(kaptTask, kaptTask.pluginOptions, aptMode = "apt")

        return kaptTask
    }

    private fun Kapt3SubpluginContext.createKaptGenerateStubsTask(): KaptGenerateStubsTask {
        val kaptTask = project.tasks.create(
                getKaptTaskName("kaptGenerateStubs"),
                KaptGenerateStubsTask::class.java)

        kaptTask.sourceSetName = sourceSetName
        kaptTask.kotlinCompileTask = kotlinCompile
        kotlinToKaptGenerateStubsTasksMap[kotlinCompile] = kaptTask

        kaptClasspathArtifacts.forEach { kaptTask.pluginOptions.addClasspathEntry(it) }

        kaptTask.stubsDir = getKaptStubsDir()
        kaptTask.destinationDir = getKaptIncrementalDataDir()
        kaptTask.mapClasspath { kotlinCompile.classpath }
        kaptTask.generatedSourcesDir = sourcesOutputDir
        mapKotlinTaskProperties(project, kaptTask)

        kaptTask.kaptClasspath = kaptClasspath
        buildAndAddOptionsTo(kaptTask, kaptTask.pluginOptions, aptMode = "stubs")

        return kaptTask
    }

    private fun Kapt3SubpluginContext.getKaptTaskName(prefix: String): String {
        // Replace compile*Kotlin to kapt*Kotlin
        val baseName = kotlinCompile.name
        assert(baseName.startsWith("compile"))
        return baseName.replaceFirst("compile", prefix)
    }

    private fun Kapt3SubpluginContext.disableAnnotationProcessingInJavaTask() {
        (javaCompile as? JavaCompile)?.let { javaCompile ->
            val options = javaCompile.options
            // 'android-apt' (com.neenbedankt) adds a File instance to compilerArgs (List<String>).
            // Although it's not our problem, we need to handle this case properly.
            val oldCompilerArgs: List<Any> = options.compilerArgs
            val newCompilerArgs = oldCompilerArgs.filterTo(mutableListOf()) {
                it !is CharSequence || !it.toString().startsWith("-proc:")
            }
            newCompilerArgs.add("-proc:none")
            @Suppress("UNCHECKED_CAST")
            options.compilerArgs = newCompilerArgs as List<String>
        }
    }

    override fun getCompilerPluginId() = "org.jetbrains.kotlin.kapt3"
    override fun getGroupName() = KAPT_GROUP_NAME
    override fun getArtifactName() = KAPT_ARTIFACT_NAME
}

internal fun registerGeneratedJavaSource(kaptTask: KaptTask, javaTask: AbstractCompile) {
    javaTask.source(kaptTask.destinationDir)
}

internal fun Configuration.getNamedDependencies(): List<Dependency> = allDependencies.filter { it.group != null && it.name != null }

internal fun checkAndroidAnnotationProcessorDependencyUsage(project: Project) {
    val isKapt3Enabled = Kapt3GradleSubplugin.isEnabled(project)

    val androidAPDependencies = project.configurations
            .filter { it.name == "annotationProcessor"
                    || (it.name.endsWith("AnnotationProcessor") && !it.name.startsWith("_")) }
            .flatMap { it.getNamedDependencies() }

    if (androidAPDependencies.isNotEmpty()) {
        val artifactsRendered = androidAPDependencies.joinToString { "'${it.group}:${it.name}:${it.version}'" }
        val andApplyKapt = if (isKapt3Enabled) "" else " and apply the kapt plugin: \"apply plugin: 'kotlin-kapt'\""
        project.logger.warn("${project.name}: " +
                "'annotationProcessor' dependencies won't be recognized as kapt annotation processors. " +
                "Please change the configuration name to 'kapt' for these artifacts: $artifactsRendered$andApplyKapt.")
    }
}
