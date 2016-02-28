package com.typesafe.sbt.packager.archetypes

import sbt._
import sbt.Keys.{ Classpath, dependencyClasspath }

trait DependenciesOps extends JarsOps {

  def findProjectDependenciesArtifacts: Def.Initialize[Task[Seq[Attributed[File]]]] =
    (sbt.Keys.buildDependencies, sbt.Keys.thisProjectRef, sbt.Keys.state) apply { (build: BuildDependencies, thisProject: ProjectRef, stateTask: Task[State]) =>
      //refs are dependencies of this project
      val refs: Seq[ProjectRef] = dependencyProjectRefs(build, thisProject)
      println(s"======================= refs: ${refs}")

      //    excludeDependenciesWithAppLibPlugin(refs, stateTask).map(x => x.filter(isRuntimeArtifact))
      excludeDependenciesWithAppLibPlugin(refs, stateTask).map(x => x.filter(isRuntimeArtifact))
    }

  private def normalizeLocation(location: String) = if (location.endsWith("/")) location else location + "/"

  def javaLibsProjects(build: BuildDependencies, thisProject: ProjectRef, extState: Extracted): Seq[ProjectRef] = {
    dependencyProjectRefs(build, thisProject) filter (ref => projectHaveEnabledAppLibPlugin(extState, ref))
  }

  //  val javaLibProjectsAbsoluteClassPath: Def.Initialize[Task[Seq[String]]] = depProjects.foldLeft(zero) { (acc, ref) =>
  //    for {
  //      include <- Def.value(projectHaveEnabledAppLibPlugin(stateTask, ref))
  //      a <- acc
  //      b <- if (include) javaLibAbsoluteClassPath(ref) else Def.task(Seq.empty[String])
  //    } yield a :+ b
  //  }
  //  javaLibProjectsAbsoluteClassPath

  //  def foo(ref: ProjectRef) = {
  //    import JavaLibPackaging.autoImport._
  //    (javaLibraryPath in ref, (dependencyClasspath in Runtime) in ref) apply { (libLocation, runtimeDeps) =>
  //      val location = normalizeLocation(libLocation.get) //todo: remove unsafe get
  //    val absolutePath = for {
  //        deps <- runtimeDeps
  //      } yield deps map (dep => location + getJarFullFilename(dep))
  //      absolutePath
  //    }
  //  }
  //      import JavaLibPackaging.autoImport._
  //
  //      javaLibProjectsOnly map { refs =>
  //
  //
  //        val x: Def.Initialize[Task[Seq[String]]] = refs.foldLeft[Def.Initialize[Task[Seq[String]]]](Def.task(Nil)) { (acc, el) =>
  //          val result: Def.Initialize[Task[Seq[String]]] = for {
  //            libLocations <- (javaLibraryPath in el)
  //            location = normalizeLocation(libLocations.get)
  //            runtimeDeps <- ((dependencyClasspath in Runtime) in el)
  //            absolutePath = runtimeDeps map (dep => location + getJarFullFilename(dep))
  //          } yield absolutePath
  //          for {
  //            a <- acc
  //            r <- result
  //          } yield a ++ r
  //        }
  //        x
  //      }

  def javaLibAbsoluteClassPath(libLocation: Option[String], runtimeDeps: Classpath): Seq[String] = {
    val location = normalizeLocation(libLocation.get) //todo: remove unsafe get
    runtimeDeps map (dep => location + getJarFullFilename(dep))
  }

  // get artifacts produced by this project. only jars; NO sources, NO docs, NO pom
  def findThisProjectArtifacts: Def.Initialize[Task[Seq[Attributed[File]]]] =
    (sbt.Keys.thisProjectRef, sbt.Keys.state) apply { (thisProject, stateTask) =>
      val thisProjectArts = extractArtifacts(stateTask, thisProject)
      thisProjectArts map {
        _ filter isRuntimeArtifact
      }
    }

  // Converts a managed classpath into a set of lib mappings.
  def universalDepMappings(deps: Seq[Attributed[File]], projectArts: Seq[Attributed[File]]): Seq[(File, String)] =
    for {
      dep <- deps
      realDep <- findRealDep(dep, projectArts)
    } yield realDep.data -> ("lib/" + getJarFullFilename(realDep))

  private def excludeDependenciesWithAppLibPlugin(refs: Seq[ProjectRef], stateTask: Task[State]): Task[Seq[Attributed[File]]] = {
    refs.foldLeft[Task[Seq[Attributed[File]]]](task(Nil)) { (acc, ref) =>
      for {
        exclude <- projectHaveEnabledAppLibPluginOld(stateTask, ref)
        result <- if (exclude) task(Seq.empty[Attributed[File]]) else extractArtifacts(stateTask, ref)
      } yield result
    }
  }

  private def projectHaveEnabledAppLibPlugin(extState: Extracted, ref: ProjectRef): Boolean = {
    val units = extState.structure.units.values
    units.exists(u => enabledAppLibPlugin(u, ref.project))
  }

  private def projectHaveEnabledAppLibPluginOld(stateTask: Task[State], ref: ProjectRef): Task[Boolean] = {
    for {
      state <- stateTask
      _ = println(s"====================== state in excludeDependenciesWithAppLibPlugin is : ${state}")
      extracted = Project.extract(state)
      units = extracted.structure.units.values
      enabled = units.exists(u => enabledAppLibPlugin(u, ref.project))
    } yield enabled
  }

  private def enabledAppLibPlugin(build: LoadedBuildUnit, project: String): Boolean = {
    println(s"======================== project is: ${project}")
    val refProject = build.defined.get(project)
    println(s"============================ ref project: ${refProject}")
    refProject exists (_.autoPlugins.contains(JavaLibPackaging))
  }

  //  // Here we grab the dependencies...
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

  private def isRuntimeArtifact(dep: Attributed[File]): Boolean =
    dep.get(sbt.Keys.artifact.key).map(_.`type` == "jar").getOrElse {
      val name = dep.data.getName
      !(name.endsWith(".jar") || name.endsWith("-sources.jar") || name.endsWith("-javadoc.jar"))
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

}
