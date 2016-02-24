package com.typesafe.sbt.packager.archetypes

import sbt._

/**
 * Available settings/tasks for the [[com.typesafe.sbt.packager.archetypes.JavaLibPackaging]]
 * and all depending archetypes.
 */
trait JavaLibKeys extends JavaProjectKeys {
  val javaLibraryPath = SettingKey[Option[String]]("javaLibraryPath", "The path where library is stored")
}