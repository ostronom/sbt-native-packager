package com.typesafe.sbt
package packager
package archetypes

import com.typesafe.sbt.packager.archetypes.JavaAppPackaging.autoImport._
import sbt._
import sbt.Keys.{ dependencyClasspath, mappings }
import SbtNativePackager.Universal

object JavaExternalDepsPackaging extends AutoPlugin with JarsOps with DependenciesOps {

  object autoImport extends JavaExternalDepsKeys

  import autoImport._
  import JavaAppPackaging.autoImport.{scriptClasspathOrdering, scriptClasspath}
  import JavaLibPackaging.autoImport.javaLibraryPath

  override def requires: Plugins = JavaAppPackaging && JavaLibPackaging

  override def trigger = allRequirements

//  scriptClasspathOrdering - The order of the classpath used at runtime for the bat/bash scripts.
//  projectDependencyArtifacts - The set of exported artifacts from our dependent projects

  // scriptClasspath consists of:
  // 1. this project artifacts(relative path)
  // 2. artifacts of dependsOn projects, that does not have JavaLibPackaging plugin(replative path)
  // 3. artifacts of dependsOn projects, that have JavaLibPackaging plugin(absolute path)

  // mappings consists of:
  // 1. this project artifacts √
  // 2. artifacts of dependsOn projects, that does not have JavaLibPackaging plugin √

  override def projectSettings = Seq(
    scriptClasspathOrdering := Nil,

    // mapping for current artifact
    // something like: lib/im.actor.server.actor-core-1.0.151.jar
    scriptClasspathOrdering <+= (Keys.packageBin in Compile, Keys.projectID, Keys.artifact in Compile in Keys.packageBin) map { (jar, id, art) =>
      jar -> ("lib/" + makeJarName(id.organization, id.name, id.revision, art.name, art.classifier))
    },
    // find artifacts produced by this project
    thisProjectArtifacts <<= findThisProjectArtifacts,

    //^^^^^^this will go to mapping for sure

    //find ONLY dependencies artifacts without JavaLibPackaging plugin enabled
    projectDependencyArtifacts <<= findProjectDependenciesArtifacts,

    // тут из всех зависимостей делается общий список, который потом используется в mappings.
    // для JavaExternalDepsPackaging нужно сделать:
    // - чтобы в mappings попадали только завимости этого билда(и может быть зависимости других проектов, если они не JavaLibPackaging)
    // - чтобы в scriptClasspath попадали все зависимости, с абсолютным путем(кроме собственных)
    scriptClasspathOrdering <++= (Keys.dependencyClasspath in Runtime, projectDependencyArtifacts) map universalDepMappings,

    //unique only
    scriptClasspathOrdering <<= (scriptClasspathOrdering) map { _.distinct },

    // mappings consists of:
    // 1. this project artifacts
    // 2. artifacts of dependsOn projects, that does not have JavaLibPackaging plugin
    mappings in Universal <++= scriptClasspathOrdering,


    //and make relative classpath name. not sure that it will work for us
    //значит тут из foo.jar будет сделано ../foo.jar
    //а из lib/bar.jar будет сделано bar.jar

    // scriptClasspath consists of:
    // 1. this project artifacts(relative path) √
    // 2. artifacts of dependsOn projects, that does not have JavaLibPackaging plugin(relative path) √
    // 3. artifacts of dependsOn projects, that have JavaLibPackaging plugin(absolute path)
    scriptClasspath <<= scriptClasspathOrdering map makeRelativeClasspathNames,

    scriptClasspath <<= (javaLibsAbsolutePath) map { v =>
      v.value
    },


    bashScriptDefines <<= (Keys.mainClass in (Compile, bashScriptDefines), scriptClasspath in bashScriptDefines, bashScriptExtraDefines, bashScriptConfigLocation) map { (mainClass, cp, extras, config) =>
      val hasMain =
        for {
          cn <- mainClass
        } yield JavaAppBashScript.makeJavaLibDefines(cn, appClasspath = cp, extras = extras, configFile = config)
      hasMain getOrElse Nil
    },

    Keys.printWarnings <<= (scriptClasspath) map { cp =>
      println(s"======================= cp: ${cp}")
    }
  )
}
