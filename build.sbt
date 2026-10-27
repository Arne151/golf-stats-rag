ThisBuild / scalaVersion := "3.3.6"
ThisBuild / version      := "0.1.0"
ThisBuild / organization := "golfrag"

lazy val root = (project in file("."))
  .settings(
    name := "golf-stats-rag",
    libraryDependencies ++= Seq(
      "org.apache.pdfbox" % "pdfbox"  % "3.0.5",  // PDF text extraction + sample PDF generation
      "com.lihaoyi"      %% "upickle" % "4.4.3",  // JSON (ujson) for profile files and LLM HTTP APIs
      "org.scalameta"    %% "munit"   % "1.1.0" % Test
    ),
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    Compile / mainClass := Some("golfrag.Main"),
    // Let `sbt run` read from the terminal for the interactive chat.
    run / fork         := true,
    run / connectInput := true,
    run / outputStrategy := Some(StdoutOutput)
  )
