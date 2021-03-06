/*
 * This file is part of SpongeGradle, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.gradle.dev

import net.minecrell.gradle.licenser.LicenseExtension
import net.minecrell.gradle.licenser.Licenser
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.CheckstylePlugin
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.kotlin.dsl.*
import org.gradle.language.jvm.tasks.ProcessResources
import org.spongepowered.gradle.deploy.DeployImplementationExtension
import org.spongepowered.gradle.deploy.DeployImplementationPlugin
import org.spongepowered.gradle.sort.SpongeSortingPlugin
import org.spongepowered.gradle.util.Constants

open class SpongeDevExtension(val api: Project? = null) {
    var organization = "SpongePowered"
    var url: String = "https://www.spongepowered.org"
    var licenseProject: String = "SpongeAPI"
}

open class SpongeDevPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val devExtension =  project.extensions.let {
            it.findByType(SpongeDevExtension::class) ?: it.create(Constants.SPONGE_DEV_EXTENSION, SpongeDevExtension::class.java, project)
        }


        // Apply the BaseDevPlugin for sponge repo and Java configuration
        project.plugins.apply(BaseDevPlugin::class.java)
        // Apply Test dependencies
        project.dependencies.apply {
            add("testCompile", Constants.Dependencies.jUnit)
            add("testCompile", "org.hamcrest:hamcrest-library:1.3")
            add("testCompile", "org.mockito:mockito-core:2.8.47")
        }
        project.plugins.apply(JavaBasePlugin::class)

        // Configure Java compile
        //  - Add javadocJar
        //  - Add Specification info for jar manifests
        //  - Add LICENSE.txt to the processResources for jar inclusion
        configureJavaCompile(project)
        configureJavadocTask(project)
        val javadocJar: Jar = configureJavadocJarTask(project)

        configureJarTask(project, devExtension)

        val processResources = project.tasks.getting(ProcessResources::class) {
            from("LICENSE.txt")
        }

        configureGitCommitBranchManifests(project)

        configureLicenser(project, devExtension)
        configureCheckstyle(project, devExtension)

        // Add sorting
        project.plugins.apply(SpongeSortingPlugin::class.java)

//        configureDeploy(project, devExtension)
        val sourceJar: Jar = configureSourceJar(project, devExtension)

        configureSourceAndDevOutput(project, sourceJar, javadocJar, devExtension)

    }

    private fun configureJavadocJarTask(project: Project): Jar {
        val javadocJar: Jar = project.tasks.create("javadocJar", Jar::class) {
            group = "build"
            classifier = "javadoc"
            from(project.tasks["javadoc"])
        }
        return javadocJar
    }

    private fun configureSourceAndDevOutput(project: Project, sourceJar: Jar, javadocJar: Jar, devExtension: SpongeDevExtension) {
        project.extensions.findByType(PublishingExtension::class)?.apply {
            publications {
                val mavenJava = findByName("mavenJava") as? MavenPublication
                mavenJava?.let {
                    it.artifact(sourceJar)
                    it.artifact(javadocJar)
                }
            }
        }


        addSourceJarAndJavadocJarToArtifacts(project, sourceJar, javadocJar)


        project.configurations.register("devOutput")
        project.dependencies.apply {
            project.sourceSet("main")?.output?.let {
                add("devOutput", project.fileTree(it))
            }
            project.sourceSet("ap")?.let {
                this.add("devOutput", it.output)
            }
        }
        configureSourceOutputForProject(project)
    }

    private fun addSourceJarAndJavadocJarToArtifacts(project: Project, sourceJar: Jar, javadocJar: Jar) {
        project.afterEvaluate {
            project.extensions.findByType(PublishingExtension::class)?.publications {
                (findByName("spongeGradle") as? MavenPublication)?.apply {
                    artifact(sourceJar)
                    artifact(javadocJar)
                }
            }
        }
        project.artifacts {
            add("archives", sourceJar)
            add("archives", javadocJar)
        }
    }

    private fun configureSourceOutputForProject(project: Project) {
        project.afterEvaluate {
            project.dependencies.apply {
                project.sourceSet("main")?.allSource?.srcDirs?.forEach {
                    add("sourceOutput", project.files(it.relativeTo(project.projectDir).path))
                }
                project.sourceSet("ap")?.let {
                    it.java.sourceDirectories.forEach {
                        add("sourceOutput", project.files(it.relativeTo(project.projectDir).path))
                    }
                }
            }

        }
    }

    private fun configureSourceJar(project: Project, devExtension: SpongeDevExtension): Jar {
        val sourceOutputConf = project.configurations.register("sourceOutput")
        val sourceJar: Jar = project.tasks.create("sourceJar", Jar::class.java) {
            classifier = "sources"
            group = "build"
            from(sourceOutputConf)
            if (devExtension is CommonDevExtension) {
                devExtension.api?.afterEvaluate {
                    this@create.from(devExtension.api.configurations.named("sourceOutput"))
                }
            }
            if (devExtension is SpongeImpl) {
                devExtension.common.afterEvaluate {
                    this@create.from(devExtension.common.configurations.named("sourceOutput"))
                }
            }
        }
        return sourceJar
    }

    private fun configureDeploy(project: Project, devExtension: SpongeDevExtension) {
        // Set up the deploy aspect - after we've created the configurations for sources and dev jars.
        project.plugins.apply(DeployImplementationPlugin::class.java)
        project.extensions.configure(DeployImplementationExtension::class.java) {
            url = "https://github.com/${devExtension.organization}/${project.name}"
            git = "{$url}.git"
            scm = "scm:git:{$git}"
            dev = "scm:git:git@github.com:${devExtension.organization}.${project.name}.git"
            description = project.description
        }
    }

    private fun configureCheckstyle(project: Project, devExtension: SpongeDevExtension) {
        // Configure Checkstyle but make the task only run explicitly
        project.plugins.apply(CheckstylePlugin::class.java)
        project.extensions.configure(CheckstyleExtension::class.java) {
            toolVersion = "8.24"
            devExtension.api?.let {
                configFile = it.file("checkstyle.xml")

            }
            configProperties.apply {
                put("basedir", project.projectDir)
                put("suppressions", project.file("checkstyle-suppressions.xml"))
                put("severity", "warning")
            }
        }
    }

    private fun configureLicenser(project: Project, devExtension: SpongeDevExtension) {
        // Apply Licenser
        project.plugins.apply(Licenser::class.java)

        project.extensions.configure(LicenseExtension::class.java) {
            (this as ExtensionAware).extra.apply {
                this["name"] = devExtension.licenseProject
                this["organization"] = devExtension.organization
                this["url"] = devExtension.url
            }
            devExtension.api?.let {
                header = it.file("HEADER.txt")
            }
            include("**/*.java")
            newLine = false
        }
    }

    private fun configureGitCommitBranchManifests(project: Project) {
        // Add commit and branch information to jar manifest
        val commit: String? = project.properties["commit"] as String?
        val branch: String? = project.properties["branch"] as String?
        if (commit != null) {
            project.afterEvaluate {
                val jar = tasks.getting(Jar::class) {
                    manifest {
                        attributes["Git-Commit"] = commit
                        attributes["Git-Branch"] = branch
                    }
                }
            }
        }
    }

    private fun configureJarTask(project: Project, devExtension: SpongeDevExtension) {
        val jar = project.tasks.getting(Jar::class) {
            manifest {
                devExtension.api?.let {
                    attributes["Specification-Title"] = it.name
                    attributes["Specification-Version"] = it.version
                }
                attributes["Specification-Vendor"] = devExtension.organization
                attributes["Created-By"] = "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})"
            }

        }
    }

    private fun configureJavaCompile(project: Project) {
        val javaCompile = project.tasks.getting(JavaCompile::class) {
            options.apply {
                compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-path", "-parameters"))
                isDeprecation = false
                encoding = "UTF-8"
            }
        }
    }

    private fun configureJavadocTask(project: Project) {
        val javadoc = project.tasks.getting(Javadoc::class) {
            options {
                encoding = "UTF-8"
                charset("UTF-8")
                isFailOnError = false
                (this as StandardJavadocDocletOptions).apply {
                    links?.addAll(mutableListOf(
                            "http://www.slf4j.org/apidocs/",
                            "https://google.github.io/guava/releases/21.0/api/docs/",
                            "https://google.github.io/guice/api-docs/4.1/javadoc/",
                            "https://zml2008.github.io/configurate/configurate-core/apidocs/",
                            "https://zml2008.github.io/configurate/configurate-hocon/apidocs/",
                            "https://flow.github.io/math/",
                            "https://flow.github.io/noise/",
                            "http://asm.ow2.org/asm50/javadoc/user/",
                            "https://docs.oracle.com/javase/8/docs/api/"
                    ))
                    addStringOption("-Xdoclint:none", "-quiet")
                }
            }
        }
    }
}


fun Project.sourceSets(name: String): SourceSet = convention.getPlugin(JavaPluginConvention::class.java).sourceSets.getByName(name)
fun Project.sourceSet(name: String): SourceSet? = convention.findPlugin(JavaPluginConvention::class.java)?.sourceSets?.findByName(name)