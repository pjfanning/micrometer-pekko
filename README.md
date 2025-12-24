![Build Status](https://github.com/pjfanning/micrometer-pekko/actions/workflows/ci.yml/badge.svg?branch=main)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.pjfanning/micrometer-pekko_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.pjfanning/micrometer-pekko_2.13)
<!---
[![codecov.io](https://codecov.io/gh/kontainers/micrometer-akka/coverage.svg?branch=main)](https://codecov.io/gh/kontainers/micrometer-akka/branch/main)
--->
# micrometer-pekko

This project is a fork of an early version of [Kamon-Akka](https://kamon.io/docs/latest/instrumentation/akka/). The Kamon team have done a great job and if you are just experimenting with metrics collection, then their tools and documentation are a great starting point. 
This fork produces metrics in [Micrometer](http://micrometer.io/) format.

These are 3 previous iterations of this library:
* [pjfanning/micrometer-akka](https://github.com/pjfanning/micrometer-akka)
* [Kontainers/micrometer-akka](https://github.com/Kontainers/micrometer-akka)
* [Prometheus-Akka](https://github.com/Workday/prometheus-akka)

```sbt
"com.github.pjfanning" %% "micrometer-pekko" % "0.18.0"
```

There is a sample project at https://github.com/pjfanning/micrometer-pekko-sample.

## Usage

To enable monitoring, include the appropriate jar as a dependency and include the following Java runtime flag in your Java startup command (aspectjweaver is a transitive dependency of micrometer-pekko):

-javaagent:/path/to/aspectjweaver-1.9.24.jar

You will also need to set up the Micrometer Meter Registry.

com.github.pjfanning.micrometer.pekko.PekkoMetricRegistry#setRegistry ([example](https://github.com/pjfanning/micrometer-pekko-sample/blob/main/src/main/scala/com/example/pekko/Main.scala))

## Configuration

The metrics are configured using [application.conf](https://github.com/typesafehub/config) files. There is a default [reference.conf](https://github.com/pjfanning/micrometer-pekko/blob/main/src/main/resources/reference.conf) that enables only some metrics.

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

## License

```
Copyright © 2017,2018 Workday, Inc.
Copyright © 2013-2017 the kamon project <http://kamon.io/>

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
