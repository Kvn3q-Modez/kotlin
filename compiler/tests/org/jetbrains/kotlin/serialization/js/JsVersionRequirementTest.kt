/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.serialization.js

import org.jetbrains.kotlin.cli.common.output.outputUtils.writeAllTo
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.context.ContextForNewModule
import org.jetbrains.kotlin.context.MutableModuleContext
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.js.analyze.TopDownAnalyzerFacadeForJS
import org.jetbrains.kotlin.js.config.JSConfigurationKeys
import org.jetbrains.kotlin.js.config.JsConfig
import org.jetbrains.kotlin.js.facade.K2JSTranslator
import org.jetbrains.kotlin.js.facade.MainCallParameters
import org.jetbrains.kotlin.js.facade.TranslationResult
import org.jetbrains.kotlin.js.resolve.JsPlatform
import org.jetbrains.kotlin.metadata.ProtoBuf.VersionRequirement.VersionKind.LANGUAGE_VERSION
import org.jetbrains.kotlin.metadata.deserialization.VersionRequirement
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.BindingTraceContext
import org.jetbrains.kotlin.serialization.AbstractVersionRequirementTest
import org.jetbrains.kotlin.test.ConfigurationKind
import org.jetbrains.kotlin.test.KotlinTestUtils
import org.jetbrains.kotlin.test.TestJdkKind
import java.io.File

class JsVersionRequirementTest : AbstractVersionRequirementTest() {
    override fun compileFiles(files: List<File>, outputDirectory: File, languageVersion: LanguageVersion) {
        val environment = createEnvironment()
        val ktFiles = files.map { file -> KotlinTestUtils.createFile(file.name, file.readText(), environment.project) }
        val trace = BindingTraceContext()
        val analysisResult = TopDownAnalyzerFacadeForJS.analyzeFilesWithGivenTrace(
            ktFiles, trace, createModule(environment), environment.configuration
        )

        // There are INVISIBLE_REFERENCE errors on RequireKotlin and K2JSTranslator refuses to translate the code otherwise
        trace.clearDiagnostics()

        val result = K2JSTranslator(JsConfig(environment.project, environment.configuration)).translate(
            object : JsConfig.Reporter() {}, ktFiles, MainCallParameters.noCall(), analysisResult
        ) as TranslationResult.Success
        result.getOutputFiles(File(outputDirectory, "lib.js"), null, null).writeAllTo(outputDirectory)
    }

    override fun loadModule(directory: File): ModuleDescriptor {
        val environment = createEnvironment(File(directory, "lib.meta.js"))
        return TopDownAnalyzerFacadeForJS.analyzeFilesWithGivenTrace(
            emptyList(), BindingTraceContext(), createModule(environment), environment.configuration
        ).moduleDescriptor
    }

    private fun createEnvironment(vararg extraDependencies: File): KotlinCoreEnvironment =
        KotlinCoreEnvironment.createForTests(
            testRootDisposable,
            KotlinTestUtils.newConfiguration(ConfigurationKind.ALL, TestJdkKind.MOCK_JDK).apply {
                put(JSConfigurationKeys.LIBRARIES, extraDependencies.map(File::getPath) + JsConfig.JS_STDLIB)
                put(JSConfigurationKeys.META_INFO, true)
            },
            EnvironmentConfigFiles.JS_CONFIG_FILES
        )

    private fun createModule(environment: KotlinCoreEnvironment): MutableModuleContext {
        val config = JsConfig(environment.project, environment.configuration)
        return ContextForNewModule(ProjectContext(environment.project), Name.special("<test>"), JsPlatform.builtIns, null).apply {
            setDependencies(listOf(module) + config.moduleDescriptors + module.builtIns.builtInsModule)
        }
    }

    fun testNestedClassMembers() {
        doTest(
            VersionRequirement.Version(1, 1), DeprecationLevel.ERROR, null, LANGUAGE_VERSION, null,
            fqNames = listOf(
                "test.Outer.Inner.Deep",
                "test.Outer.Inner.Deep.<init>",
                "test.Outer.Inner.Deep.f",
                "test.Outer.Inner.Deep.x",
                "test.Outer.Inner.Deep.s",
                "test.Outer.Companion"
            )
        )
    }
}
