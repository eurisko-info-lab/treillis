ThisBuild / scalaVersion := "3.3.8"
ThisBuild / organization := "org.trellis"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(
    name := "trellis-bootstrap",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Wunused:all"),
    Test / fork := true
  )
