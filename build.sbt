ThisBuild / version := "1.0.0"

ThisBuild / scalaVersion := "2.12.18"

lazy val root = (project in file("."))
  .settings(
    name := "SigmaFinanceV2",
    idePackagePrefix := Some("org.lithosprotocol.sigmafi")
  )

libraryDependencies ++= Seq(
  "org.ergoplatform" %% "ergo-appkit" % "6.0.0",
  "org.slf4j" % "slf4j-jdk14" % "1.7.36",
  "javax.xml.bind" % "jaxb-api" % "2.4.0-b180830.0359"
)

// Offline contract specs. The appkit `tests` classifier ships FileMockedErgoClient and the
// mockwebserver/ node fixtures; mockwebserver itself is not transitive from a published
// artifact, and without it every spec dies with NoClassDefFoundError.
libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "3.2.14" % Test,
  "org.ergoplatform" %% "ergo-appkit" % "6.0.0" % Test classifier "tests",
  "com.squareup.okhttp3" % "mockwebserver" % "3.12.0" % Test
)

// The sigma compiler mutates _sourceContext on shared AST nodes, so compiling the same script
// from two suites at once throws "can be set only once". Specs should also cache compiled
// contracts per JVM for the same reason.
Test / parallelExecution := false

resolvers ++= Seq(
  "New Sonatype Releases" at "https://s01.oss.sonatype.org/content/repositories/releases/",
  "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/",
  "SonaType" at "https://oss.sonatype.org/content/groups/public",
  "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"
)

fork := true
javaOptions ++= Seq("-Xmx2g")

// The CLI is a shell that reads commands from stdin: a forked run needs its input connected, and its
// output written straight to the terminal rather than through sbt's logger.
Compile / run / connectInput := true
Compile / run / outputStrategy := Some(StdoutOutput)

// `sbt sigmafi` starts the shell; anything after it is passed on, e.g. `sbt "sigmafi --node <url>"`.
addCommandAlias("sigmafi", "runMain ksingh.sigmabonds.app.SigmaFiCli")
