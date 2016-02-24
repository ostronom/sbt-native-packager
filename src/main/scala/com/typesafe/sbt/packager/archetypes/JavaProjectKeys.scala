package com.typesafe.sbt.packager.archetypes

import sbt._

trait JavaProjectKeys {
  val projectDependencyArtifacts = TaskKey[Seq[Attributed[File]]]("projectDependencyArtifacts", "The set of exported artifacts from our dependent projects.")
}