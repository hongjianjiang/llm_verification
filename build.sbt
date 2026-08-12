ThisBuild / scalaVersion := "3.5.1"
ThisBuild / organization := "brasp"

lazy val root = (project in file("."))
  .settings(
    name := "brasp-verification",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.2" % Test,
    testFrameworks += new TestFramework("munit.Framework"),
    Compile / mainClass := Some("brasp.Translator"),
    // Translator.main calls System.exit with the CLI's exit code; fork the
    // run so that doesn't kill the sbt shell itself.
    Compile / run / fork := true,
    assembly / mainClass := Some("brasp.Translator"),
    assembly / assemblyJarName := "brasp-verification.jar",
  )
