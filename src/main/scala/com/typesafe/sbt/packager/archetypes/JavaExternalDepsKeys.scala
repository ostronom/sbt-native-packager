package com.typesafe.sbt.packager.archetypes

import sbt._

/**
 * Available settings/tasks for the [[com.typesafe.sbt.packager.archetypes.JavaExternalDepsPackaging]]
 * and all depending archetypes.
 */
trait JavaExternalDepsKeys extends JavaProjectKeys {
  //todo: check this shit also
  val resolvedLibrariesLocation = TaskKey[Seq[String]]("javaLibraryPath", "The location of libraries, provided by submodules(?) marked as JavaLibPackaging.")
  val thisProjectArtifacts = TaskKey[Seq[Attributed[File]]]("thisProjectArtifacts", "The set of runtime artifacts produced by this project.")
}