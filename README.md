[![Build Status](https://travis-ci.org/kontainers/micrometer-akka.svg?branch=master)](https://travis-ci.org/kontainers/micrometer-akka)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.kontainers/micrometer-akka_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.kontainers/micrometer-akka_2.12)
[![codecov.io](https://codecov.io/gh/kontainers/micrometer-akka/coverage.svg?branch=master)](https://codecov.io/gh/kontainers/micrometer-akka/branch/master)

# micrometer-akka

This project is a fork of [Kamon-Akka](http://kamon.io/documentation/kamon-akka/0.6.6/overview/). The Kamon team have done a great job and if you are just experimenting with metrics collection, then their tools and documentation are a great starting point. 
This fork produces metrics in [Micrometer](http://micrometer.io/) format.
See also [Prometheus-Akka](https://github.com/Workday/prometheus-akka).

Differences from Kamon-Akka:
- we do not support Kamon TraceContexts, as we currently have no use case for them
- we support Scala 2.11, Scala 2.12 and Scala 2.13
- we only build with Akka 2.4 but we test the build with Akka 2.5 and Akka 2.6.0-M4 too
- records time in seconds as opposed to nanoseconds (the data is still a double)

```sbt
"io.kontainers" %% "micrometer-akka" % "0.10.2"
```

There is a sample project at https://github.com/pjfanning/micrometer-akka-sample

[Release Notes](https://github.com/kontainers/micrometer-akka/releases)

## Usage

To enable monitoring, include the appropriate jar as a dependency and include the following Java runtime flag in your Java startup command (aspectjweaver is a transitive dependency of micrometer-akka):

-javaagent:/path/to/aspectjweaver-1.9.4.jar

You will also need to set up the Micrometer Meter Registry.

io.kontainers.micrometer.akka.AkkaMetricRegistry#setRegistry ([example](https://github.com/pjfanning/micrometer-akka-sample/blob/master/src/main/scala/com/example/akka/Main.scala))

## Configuration

The metrics are configured using [application.conf](https://github.com/typesafehub/config) files. There is a default [reference.conf](https://github.com/kontainers/micrometer-akka/blob/master/src/main/resources/reference.conf) that enables only some metrics.

### Metrics

#### Dispatcher

- differs a little between ForkJoin dispatchers and ThreadPool dispatchers
- ForkJoin: parallelism, activeThreadCount, runningThreadCount, queuedSubmissionCount, queuedTaskCountGauge stealCount
- ThreadPool: activeThreadCount, corePoolSize, currentPoolSize, largestPoolSize, maxPoolSize, completedTaskCount, totalTaskCount

#### Actor System

- Actor Count
- Unhandled Message Count
- Dead Letter Count

#### Actor

- One metric per actor instance
- mailboxSize (current size), processingTime, timeInMailbox, message count, error count

#### Actor Router

- One metric per router instance, summed across all routee actors
- routingTime, timeInMailbox, message count, error count

#### Actor Group

- Each actor group has its own include/exclude rules and you can define many groups with individual actors being allowed to be included in many groups - the metrics are summed across all actors in the group
- actorCount (current active actors), mailboxSize (current size), processingTime, timeInMailbox, message count, error count
