package com.typesafe.sbt
package packager
package archetypes

import sbt._
import sbt.Keys.{ mappings, target, name, mainClass, sourceDirectory, javaOptions, streams }
import packager.Keys.{ packageName, executableScriptName }
import linux.{ LinuxFileMetaData, LinuxPackageMapping }
import linux.LinuxPlugin.autoImport.{ linuxPackageMappings, defaultLinuxInstallLocation }
import SbtNativePackager.{ Universal, Debian }

/**
  * == Java Application ==
  *
  * This class contains the default settings for creating and deploying an archetypical Java application.
  * A Java application archetype is defined as a project that has a main method and is run by placing
  * all of its JAR files on the classpath and calling that main method.
  *
  * == Configuration ==
  *
  * This plugin adds new settings to configure your packaged application.
  * The keys are defined in [[com.typesafe.sbt.packager.archetypes.JavaAppKeys]]
  *
  * @example Enable this plugin in your `build.sbt` with
  *
  * {{{
  *  enablePlugins(JavaAppPackaging)
  * }}}
  */
object JavaLibPackaging extends AutoPlugin {

  object autoImport extends JavaLibKeys with MaintainerScriptHelper

  import JavaLibPackaging.autoImport._

  override def requires = debian.DebianPlugin && rpm.RpmPlugin && docker.DockerPlugin && windows.WindowsPlugin

  override def projectSettings = Seq(
    javaOptions in Universal := Nil,
    projectDependencyArtifacts <<= findProjectDependencyArtifacts,
    mappings in Universal <++= (Keys.dependencyClasspath in Runtime, projectDependencyArtifacts) map universalDepMappings map (_.distinct),
    linuxPackageMappings in Debian <+= (packageName in Debian, defaultLinuxInstallLocation, target in Debian) map {
      (name, installLocation, target) =>
        // create empty var/log directory
        val d = target / installLocation
        d.mkdirs()
        LinuxPackageMapping(Seq(d -> (installLocation + "/" + name)), LinuxFileMetaData())
    }

  )

  /**
    * Constructs a jar name from components...(ModuleID/Artifact)
    */
  def makeJarName(org: String, name: String, revision: String, artifactName: String, artifactClassifier: Option[String]): String =
    org + "." +
      name + "-" +
      Option(artifactName.replace(name, "")).filterNot(_.isEmpty).map(_ + "-").getOrElse("") +
      revision +
      artifactClassifier.filterNot(_.isEmpty).map("-" + _).getOrElse("") +
      ".jar"

  // Determines a nicer filename for an attributed jar file, using the
  // ivy metadata if available.
  private def getJarFullFilename(dep: Attributed[File]): String = {
    val filename: Option[String] = for {
      module <- dep.metadata.get(AttributeKey[ModuleID]("module-id"))
      artifact <- dep.metadata.get(AttributeKey[Artifact]("artifact"))
    } yield makeJarName(module.organization, module.name, module.revision, artifact.name, artifact.classifier)
    filename.getOrElse(dep.data.getName)
  }

  // Here we grab the dependencies...
  private def dependencyProjectRefs(build: sbt.BuildDependencies, thisProject: ProjectRef): Seq[ProjectRef] =
    build.classpathTransitive.get(thisProject).getOrElse(Nil)

  private def extractArtifacts(stateTask: Task[State], ref: ProjectRef): Task[Seq[Attributed[File]]] =
    stateTask flatMap { state =>
      val extracted = Project extract state
      // TODO - Is this correct?
      val module = extracted.get(sbt.Keys.projectID in ref)
      val artifactTask = extracted get (sbt.Keys.packagedArtifacts in ref)
      for {
        arts <- artifactTask
      } yield {
        for {
          (art, file) <- arts.toSeq // TODO -Filter!
        } yield {
          sbt.Attributed.blank(file).
            put(sbt.Keys.moduleID.key, module).
            put(sbt.Keys.artifact.key, art)
        }
      }
    }

  // TODO - Should we pull in more than just JARs?  How do native packages come in?
  private def isRuntimeArtifact(dep: Attributed[File]): Boolean =
    dep.get(sbt.Keys.artifact.key).map(_.`type` == "jar").getOrElse {
      val name = dep.data.getName
      !(name.endsWith(".jar") || name.endsWith("-sources.jar") || name.endsWith("-javadoc.jar"))
    }

  private def findProjectDependencyArtifacts: Def.Initialize[Task[Seq[Attributed[File]]]] =
    (sbt.Keys.buildDependencies, sbt.Keys.thisProjectRef, sbt.Keys.state) apply { (build, thisProject, stateTask) =>
      val refs = thisProject +: dependencyProjectRefs(build, thisProject)
      // Dynamic lookup of dependencies...
      val artTasks = (refs) map { ref => extractArtifacts(stateTask, ref) }
      val allArtifactsTask: Task[Seq[Attributed[File]]] =
        artTasks.fold[Task[Seq[Attributed[File]]]](task(Nil)) { (previous, next) =>
          for {
            p <- previous
            n <- next
          } yield (p ++ n.filter(isRuntimeArtifact))
        }
      allArtifactsTask
    }

  private def findRealDep(dep: Attributed[File], projectArts: Seq[Attributed[File]]): Option[Attributed[File]] = {
    if (dep.data.isFile) Some(dep)
    else {
      projectArts.find { art =>
        // TODO - Why is the module not showing up for project deps?
        //(art.get(sbt.Keys.moduleID.key) ==  dep.get(sbt.Keys.moduleID.key)) &&
        ((art.get(sbt.Keys.artifact.key), dep.get(sbt.Keys.artifact.key))) match {
          case (Some(l), Some(r)) =>
            // TODO - extra attributes and stuff for comparison?
            // seems to break stuff if we do...
            l.name == r.name && l.classifier == r.classifier
          case _ => false
        }
      }
    }
  }

  // Converts a managed classpath into a set of lib mappings.
  private def universalDepMappings(deps: Seq[Attributed[File]], projectArts: Seq[Attributed[File]]): Seq[(File, String)] =
    for {
      dep <- deps
      realDep <- findRealDep(dep, projectArts)
    } yield realDep.data -> ("lib/" + getJarFullFilename(realDep))

  /**
    * Currently unused.
    * TODO figure out a proper way to ship default `application.ini` if necessary
    */
  protected def applicationIniTemplateSource: java.net.URL = getClass.getResource("application.ini-template")
}
