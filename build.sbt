// Project information
name := "pcie-audio"
version := "1.0.0"
scalaVersion := "2.12.16"

// Common settings for all projects
lazy val commonSettings = Seq(
  organization := "com.audiophile",
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-language:reflectiveCalls",
    "-language:postfixOps",
    "-language:implicitConversions",
    "-language:existentials",
    "-Xcheckinit",
    "-Xfatal-warnings",
    "-Xlint:_"
  ),
  libraryDependencies ++= Seq(
    // SpinalHDL dependencies
    "com.github.spinalhdl" %% "spinalhdl-core" % "1.9.4",
    "com.github.spinalhdl" %% "spinalhdl-lib" % "1.9.4",
    compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % "1.9.4"),
    
    // Test dependencies
    "org.scalatest" %% "scalatest" % "3.2.15" % "test",
    "org.scalacheck" %% "scalacheck" % "1.17.0" % "test"
  ),
  fork := true,
  
  // Test settings
  Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
  Test / fork := true,
  Test / javaOptions ++= Seq("-Xmx2G"),
  
  // Coverage settings
  coverageEnabled := true,
  coverageMinimumStmtTotal := 80,
  coverageFailOnMinimum := true,
  
  // Scalafmt settings
  scalafmtOnCompile := true
)

// Hardware project containing SpinalHDL code
lazy val hardware = (project in file("hardware"))
  .settings(
    commonSettings,
    name := "pcie-audio-hardware",
    
    // Additional hardware-specific settings
    Compile / resourceDirectory := baseDirectory.value / "src" / "main" / "resources",
    
    // VHDL/Verilog generation settings
    target := baseDirectory.value / "generated",
    
    // Additional dependencies specific to hardware
    libraryDependencies ++= Seq(
      "org.json4s" %% "json4s-native" % "4.0.6"
    )
  )

// Simulation project
lazy val simulation = (project in file("simulation"))
  .settings(
    commonSettings,
    name := "pcie-audio-simulation",
    
    // Additional simulation-specific settings
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.15" % "test",
      "com.github.spinalhdl" %% "spinalhdl-sim" % "1.9.4"
    )
  )
  .dependsOn(hardware)

// Root project
lazy val root = (project in file("."))
  .aggregate(hardware, simulation)
  .settings(
    commonSettings,
    
    // Don't publish root project
    publish := {},
    publishLocal := {},
    
    // Custom tasks
    commands ++= Seq(
      Command.command("generateVerilog") { state =>
        "hardware/runMain audio.AudioPCIeTopVerilog" :: state
      },
      Command.command("runTests") { state =>
        "simulation/test" :: state
      },
      Command.command("runCoverage") { state =>
        "clean" ::
        "coverage" ::
        "test" ::
        "coverageReport" ::
        state
      }
    )
  )

// Release settings
import ReleaseTransformations._
releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  setNextVersion,
  commitNextVersion,
  pushChanges
)

// Assembly settings
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}

// Scalastyle settings
scalastyleConfig := baseDirectory.value / "project" / "scalastyle-config.xml"

// Additional custom configurations
val SystemVerilog = config("systemverilog")
val VHDL = config("vhdl")

// Custom tasks for different HDL generations
val generateSystemVerilog = taskKey[Unit]("Generate SystemVerilog code")
val generateVHDL = taskKey[Unit]("Generate VHDL code")

// Task implementations
generateSystemVerilog := {
  (hardware / Compile / runMain).toTask(" audio.AudioPCIeTopVerilog --systemverilog").value
}

generateVHDL := {
  (hardware / Compile / runMain).toTask(" audio.AudioPCIeTopVerilog --vhdl").value
}