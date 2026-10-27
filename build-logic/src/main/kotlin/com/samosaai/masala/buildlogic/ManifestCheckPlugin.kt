package com.samosaai.masala.buildlogic

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction

/**
 * Applied to `:app`. For each variant, `check<Variant>ManifestPolicy` fails the build on restricted entries
 * outside `:pro` and writes `build/reports/play-readiness-<variant>.md` at the root. `checkProImports`
 * keeps `:app` to `ProTools`. Both run before `assemble<Variant>` and with `check`.
 */
class ManifestCheckPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.application") { configure(project) }
    }

    private fun configure(project: Project) {
        val proImports = project.tasks.register("checkProImports", ProImportsTask::class.java) {
            sources.from(project.fileTree("src") { include("**/*.kt", "**/*.java") })
            baseDir.set(project.layout.projectDirectory)
        }
        project.tasks.named("check") { dependsOn(proImports) }

        val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        components.onVariants { variant ->
            val name = variant.name.replaceFirstChar(Char::uppercase)
            val check = project.tasks.register("check${name}ManifestPolicy", ManifestCheckTask::class.java) {
                variantName.set(variant.name)
                mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                // Not a public artifact; it sits next to the merged manifest and is written by the same task.
                blameReport.set(
                    project.layout.buildDirectory.file(
                        "intermediates/manifest_merge_blame_file/${variant.name}/process${name}MainManifest/" +
                            "manifest-merger-blame-${variant.name}-report.txt",
                    ),
                )
                allowlist.set(project.file("manifest-allowlist.txt"))
                proNotes.set(project.rootProject.file("pro/play-readiness.txt"))
                report.set(project.rootProject.layout.buildDirectory.file("reports/play-readiness-${variant.name}.md"))
            }
            project.tasks.named("check") { dependsOn(check) }
            project.tasks.matching { it.name == "assemble$name" }.configureEach { dependsOn(check, proImports) }
        }
    }
}

@CacheableTask
abstract class ManifestCheckTask : DefaultTask() {
    @get:Input abstract val variantName: Property<String>

    // Only a dependency on the merge; the blame report below has the same content plus sources.
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val mergedManifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val blameReport: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val allowlist: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val proNotes: RegularFileProperty
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun run() {
        val entries = parseBlameReport(blameReport.get().asFile.readText())
        val allowlistFile = allowlist.get().asFile
        val list = Allowlist(parseList(allowlistFile.readText(), allowlistFile.name))
        val notesFile = proNotes.get().asFile
        val notes = parseList(notesFile.readText(), notesFile.name)

        // Written before failing, so a failing build still shows the whole picture.
        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(readinessReport(variantName.get(), entries, notes, list))

        val bad = violations(entries, list)
        if (bad.isNotEmpty()) throw GradleException(violationMessage(bad, "app/${allowlistFile.name}"))
        logger.lifecycle("Manifest check passed; Play readiness report: ${reportFile.toURI()}")
    }
}

abstract class ProImportsTask : DefaultTask() {
    @get:InputFiles @get:SkipWhenEmpty @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal abstract val baseDir: DirectoryProperty

    @TaskAction
    fun run() {
        val root = baseDir.get().asFile
        val bad = forbiddenProImports(sources.files.associate { it.relativeTo(root).path to it.readText() })
        if (bad.isNotEmpty()) {
            throw GradleException(
                "Only ProTools may be used from :pro (plan 0.3):\n" + bad.joinToString("\n") { "  $it" },
            )
        }
    }
}
