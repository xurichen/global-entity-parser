ThisBuild / scalaVersion := "2.13.15"
ThisBuild / organization := "com.entityparser"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(
    name := "global-entity-parser",
    scalacOptions ++= Seq(
      "-encoding", "UTF-8",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xlint:unused"
    ),
    // Pure Scala 2.13 stdlib + JDK 11+ java.net.http.HttpClient:
    // Zero PyTorch, zero heavyweight NLP/ML frameworks, zero external runtime dependencies.
    libraryDependencies := Seq.empty,
    Compile / mainClass := Some("com.entityparser.eval.HardCaseEvaluation")
  )
