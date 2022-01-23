organization := "io.kontainers"

name := "micrometer-akka"

ThisBuild / scalaVersion := "2.13.8"

ThisBuild / crossScalaVersions := Seq("2.12.15", "2.13.8", "3.1.1")

scalacOptions += "-target:jvm-1.8"

def sysPropOrDefault(propName: String, default: String): String = Option(System.getProperty(propName)) match {
  case Some(propVal) if !propVal.trim.isEmpty => propVal.trim
  case _ => default
}

val akkaVersion = "2.6.18"
val aspectjweaverVersion = "1.9.7"
val micrometerVersion = "1.8.2"

update / checksums := Nil

libraryDependencies ++= Seq(
  "org.slf4j" % "slf4j-api" % "1.7.33",
  "io.micrometer" % "micrometer-core" % micrometerVersion,
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.typesafe" % "config" % "1.4.1",
  "org.aspectj" % "aspectjweaver" % aspectjweaverVersion,
  "com.typesafe.akka" %% "akka-cluster" % akkaVersion % Test,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
  "org.scalatest" %% "scalatest" % "3.2.10" % Test,
  "ch.qos.logback" % "logback-classic" % "1.2.10" % Test
)
val scalaReleaseVersion = SettingKey[Int]("scalaReleaseVersion")
scalaReleaseVersion := {
  val v = scalaVersion.value
  CrossVersion.partialVersion(v).map(_._1.toInt).getOrElse {
    throw new RuntimeException(s"could not get Scala release version from $v")
  }
}

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

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

Test / publishArtifact := false

pomIncludeRepository := { _ => false }

homepage := Some(url("https://github.com/kontainers/micrometer-akka"))

licenses := Seq("The Apache Software License, Version 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))

releasePublishArtifactsAction := PgpKeys.publishSigned.value

pomExtra := (
  <scm>
    <url>git@github.com:kontainers/micrometer-akka.git</url>
    <connection>scm:git:git@github.com:kontainers/micrometer-akka.git</connection>
  </scm>
  <developers>
    <developer>
      <id>pjfanning</id>
      <name>PJ Fanning</name>
      <url>https://github.com/pjfanning</url>
    </developer>
  </developers>
)
