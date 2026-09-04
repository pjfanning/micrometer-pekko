import sbtghactions.JavaSpec.Distribution.Temurin

organization := "com.github.pjfanning"

name := "micrometer-pekko"

ThisBuild / scalaVersion := "2.13.18"

ThisBuild / crossScalaVersions := Seq("2.12.21", "2.13.18", "3.9.0")

scalacOptions += "-target:jvm-1.8"

val scalaReleaseVersion = SettingKey[Int]("scalaReleaseVersion")
scalaReleaseVersion := {
  val v = scalaVersion.value
  CrossVersion.partialVersion(v).map(_._1.toInt).getOrElse {
    throw new RuntimeException(s"could not get Scala release version from $v")
  }
}

def sysPropOrDefault(propName: String, default: String): String = Option(System.getProperty(propName)) match {
  case Some(propVal) if !propVal.trim.isEmpty => propVal.trim
  case _ => default
}

val pekkoVersion = "1.7.0"
val aspectjweaverVersion = "1.9.25.1"
val micrometerVersion = "1.17.1"

update / checksums := Nil

libraryDependencies ++= Seq(
  "org.slf4j" % "slf4j-api" % "2.0.18",
  "io.micrometer" % "micrometer-core" % micrometerVersion,
  "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
  "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
  "com.typesafe" % "config" % "1.4.9",
  "org.aspectj" % "aspectjweaver" % aspectjweaverVersion,
  // Only needed by ClusterMetrics, which nothing else references, so users without a cluster never load it
  "org.apache.pekko" %% "pekko-cluster" % pekkoVersion % Provided,
  // Only needed by the persistence instrumentation, which is inert unless a PersistentActor exists
  "org.apache.pekko" %% "pekko-persistence" % pekkoVersion % Provided,
  "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion % Test,
  // pekko-actor-typed is on the test classpath, and a cluster provider swaps its local receptionist for
  // the cluster one, which lives here
  "org.apache.pekko" %% "pekko-cluster-typed" % pekkoVersion % Test,
  "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
  "org.scalatest" %% "scalatest" % "3.2.20" % Test,
  "ch.qos.logback" % "logback-classic" % "1.3.16" % Test
)

Compile / unmanagedSourceDirectories ++= {
  if (scalaReleaseVersion.value > 2) {
    Seq(
      (LocalRootProject / baseDirectory).value / "src" / "main" / "scala-3"
    )
  } else {
    Seq(
      (LocalRootProject / baseDirectory).value / "src" / "main" / "scala-2"
    )
  }
}

enablePlugins(JavaAgent)
javaAgents += "org.aspectj" % "aspectjweaver" % aspectjweaverVersion % Test

Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oD")

Test / parallelExecution := false
logBuffered := false

Test / javaOptions += s"""-Dconfig.resource=${sysPropOrDefault("config.resource", "application.conf")}"""

publishMavenStyle := true

Test / publishArtifact := false

pomIncludeRepository := { _ => false }

homepage := Some(url("https://github.com/pjfanning/micrometer-pekko"))

licenses := Seq("The Apache Software License, Version 2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt"))

pomExtra := (
  <developers>
    <developer>
      <id>pjfanning</id>
      <name>PJ Fanning</name>
      <url>https://github.com/pjfanning</url>
    </developer>
    <developer>
      <id>ivantopo</id>
      <name>Ivan Topolnjak</name>
      <url>https://twitter.com/ivantopo</url>
    </developer>
    <developer>
      <id>dpsoft</id>
      <name>Diego Parra</name>
      <url>https://twitter.com/diegolparra</url>
    </developer>
  </developers>
)

ThisBuild / githubWorkflowJavaVersions := Seq(
  JavaSpec(Temurin, "8"), JavaSpec(Temurin, "17"))
ThisBuild / githubWorkflowTargetTags ++= Seq("v*")
ThisBuild / githubWorkflowPublishTargetBranches := Seq(
  RefPredicate.Equals(Ref.Branch("main")),
  RefPredicate.StartsWith(Ref.Tag("v"))
)

ThisBuild / githubWorkflowPublish := Seq(
  WorkflowStep.Sbt(
    List("ci-release"),
    env = Map(
      "PGP_PASSPHRASE" -> "${{ secrets.PGP_PASSPHRASE }}",
      "PGP_SECRET" -> "${{ secrets.PGP_SECRET }}",
      "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}",
      "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}",
      "CI_SNAPSHOT_RELEASE" -> "+publishSigned"
    )
  )
)
