package io.cognotik.zipdiff.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * Gradle plugin for Zipdiff patch generation.
 *
 * Registers the 'zipdiff' extension, configures the 'generateZipdiffPatch' task,
 * and integrates patch generation into project assembly and build lifecycles.
 *
 * NOTE: this deliberately uses only the plain Gradle API (no `org.gradle.kotlin.dsl`
 * extensions) so the module does not need the `gradleKotlinDsl()` / `kotlin-dsl`
 * dependency on its compile classpath.
 */
class ZipdiffPatchPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("zipdiff", ZipdiffExtension::class.java)

        val patchTask =
            project.tasks.register("generateZipdiffPatch", ZipdiffPatchTask::class.java) { task ->
                task.group = LifecycleBasePlugin.BUILD_GROUP
                task.description =
                    "Generates a Zipdiff patch package between base and target archives."
                task.baseArchive.convention(extension.baseArchive)
                task.targetArchive.convention(extension.targetArchive)
                task.baseVersion.convention(extension.baseVersion)
                task.targetVersion.convention(extension.targetVersion)
                task.canonicalProfileVersion.convention(extension.canonicalProfileVersion)
                task.signatureScheme.convention(extension.signatureScheme)
                task.fallbackOnMissingBase.convention(extension.fallbackOnMissingBase)
                task.outputDirectory.convention(extension.outputDirectory)
            }

        project.afterEvaluate {
            if (extension.targetArchive.isPresent || extension.baseArchive.isPresent) {
                project.tasks
                    .matching { candidate ->
                        candidate.name == LifecycleBasePlugin.ASSEMBLE_TASK_NAME ||
                            candidate.name == LifecycleBasePlugin.BUILD_TASK_NAME
                    }
                    .configureEach { task -> task.dependsOn(patchTask) }
            }
        }
    }
}