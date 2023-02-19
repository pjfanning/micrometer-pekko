import org.typelevel.sbt.gha.JavaSpec.Distribution.Zulu

organization := "com.github.pjfanning"

name := "micrometer-akka"

ThisBuild / scalaVersion := "2.13.10"

ThisBuild / crossScalaVersions := Seq("2.12.17", "2.13.10", "3.2.2")

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

val pekkoVersion = "0.0.0+26544-4c021960-SNAPSHOT"
val aspectjweaverVersion = "1.9.19"
val micrometerVersion = "1.10.3"

update / checksums := Nil

resolvers += "Apache Snapshots" at "https://repository.apache.org/content/groups/snapshots"

libraryDependencies ++= Seq(
  "org.slf4j" % "slf4j-api" % "2.0.6",
  "io.micrometer" % "micrometer-core" % micrometerVersion,
  "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
  "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
  "com.typesafe" % "config" % "1.4.2",
  "org.aspectj" % "aspectjweaver" % aspectjweaverVersion,
  "org.apache.pekko" %% "pekko-cluster" % pekkoVersion % Test,
  "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
  "org.scalatest" %% "scalatest" % "3.2.15" % Test,
  "ch.qos.logback" % "logback-classic" % "1.3.5" % Test
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

Test / unmanagedSourceDirectories ++= {
  if (scalaReleaseVersion.value > 2) {
    Seq(
      (LocalRootProject / baseDirectory).value / "src" / "test" / "scala-3"
    )
  } else {
    Seq(
      (LocalRootProject / baseDirectory).value / "src" / "test" / "scala-2"
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

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

Test / publishArtifact := false

pomIncludeRepository := { _ => false }

homepage := Some(url("https://github.com/pjfanning/micrometer-akka"))

licenses := Seq("The Apache Software License, Version 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))

releasePublishArtifactsAction := PgpKeys.publishSigned.value

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

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec(Zulu, "8"))
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
      "SONATYPE_PASSWORD" -> "${{ secrets.CI_DEPLOY_PASSWORD }}",
      "SONATYPE_USERNAME" -> "${{ secrets.CI_DEPLOY_USERNAME }}",
      "CI_SNAPSHOT_RELEASE" -> "+publishSigned"
    )
  )
)
