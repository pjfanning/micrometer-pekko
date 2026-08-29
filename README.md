![Build Status](https://github.com/pjfanning/micrometer-pekko/actions/workflows/ci.yml/badge.svg?branch=main)
[![Maven Central](https://maven-badges.sml.io/sonatype-central/com.github.pjfanning/micrometer-pekko_2.13/badge.svg)](https://maven-badges.sml.io/sonatype-central/com.github.pjfanning/micrometer-pekko_2.13)
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
"com.github.pjfanning" %% "micrometer-pekko" % "0.20.0"
```

There is a sample project at https://github.com/pjfanning/micrometer-pekko-sample.

## Usage

To enable monitoring, include the appropriate jar as a dependency and include the following Java runtime flag in your Java startup command (aspectjweaver is a transitive dependency of micrometer-pekko):

-javaagent:/path/to/aspectjweaver-1.9.25.1.jar

You will also need to set up the Micrometer Meter Registry.

com.github.pjfanning.micrometer.pekko.PekkoMetricRegistry#setRegistry ([example](https://github.com/pjfanning/micrometer-pekko-sample/blob/main/src/main/scala/com/example/pekko/Main.scala))

## Configuration

The metrics are configured using [application.conf](https://github.com/typesafehub/config) files. There is a default [reference.conf](https://github.com/pjfanning/micrometer-pekko/blob/main/src/main/resources/reference.conf) that enables only some metrics.

### Metrics

#### Dispatcher

- differs a little between ForkJoin dispatchers and ThreadPool dispatchers
- ForkJoin: parallelism, activeThreadCount, runningThreadCount, queuedSubmissionCount, queuedTaskCountGauge stealCount
- ThreadPool: activeThreadCount, corePoolSize, currentPoolSize, largestPoolSize, maxPoolSize, completedTaskCount, totalTaskCount, rejectedTaskCount
- inhabitants (how many actors are attached to the dispatcher), for both executor types
- timeInMailbox, aggregated over every actor on the dispatcher rather than only the tracked ones, which answers which dispatcher is starved. Off by default: it is a timer recording on every message. Enable with `micrometer.pekko.dispatcher.time-in-mailbox.enabled = true`

The `rejectedTaskCount` counter wraps the pool's own `RejectedExecutionHandler` and delegates to it, so the
rejection policy is unchanged. It is only registered for the default `executor-service.style = "internal"`.

#### Cluster

Opt in, and the only metrics here that are not driven by the java agent. Add `pekko-cluster` to your own
build and call this once, after the actor system is up:

```scala
com.github.pjfanning.micrometer.pekko.ClusterMetrics.monitor(system)
```

- memberCount, tagged by `status` (`joining`, `weaklyup`, `up`, `leaving`, `exiting`, `down`, `removed`).
  Statuses nobody is in are reported as zero rather than left unregistered
- unreachableMemberCount
- isLeader (1 when this node is the cluster leader)

These are gathered by subscribing to the cluster event stream rather than by weaving. `aop.xml` excludes
`org.apache.pekko.cluster..*` because of
[issue 84](https://github.com/kontainers/micrometer-akka/issues/84), so an aspect based approach is closed
off; `Cluster.subscribe` is public API and needs none. It cannot start itself either, because there is no
safe moment for the agent to call `Cluster(system)` on an actor system that may not be a cluster at all.

The `pekko-cluster` dependency is `Provided`: nothing else in the library references `ClusterMetrics`, so
if you do not call it the class is never loaded and the dependency is not needed at runtime.

#### Serialization

- One metric per serializer, tagged by `serializer` (the serializer's class name) and `direction` (`serialize` or `deserialize`)
- time, payloadSizeBytes

Serialization happens between one actor sending a message and another receiving it, so its cost lands in
neither processingTime nor timeInMailbox. `Serializer.toBinary` and `fromBinary` are measured rather than
the `Serialization` API, because pekko-remote looks the serializer up and calls `toBinary` directly. Only
serializers whose classes are inside `org.apache.pekko` are woven, so serializers pekko ships are covered
but custom ones are not. Disable with `micrometer.pekko.serialization.enabled = false`.

#### Actor System

- Actor Count
- Unhandled Message Count
- Dead Letter Count
- Dropped Message Count (`org.apache.pekko.actor.Dropped`, published when a message is discarded rather than delivered, eg. on mailbox overflow or typed/stream backpressure)
- Suppressed Dead Letter Count (`org.apache.pekko.actor.SuppressedDeadLetter`)
- Log Event Count, tagged by `level` (`error`, `warning`, `info`, `debug`)

The event based metrics in this section are all derived from the actor system's event stream and are only
collected when `micrometer.pekko.match.events` is enabled (it is on by default).

#### Actor

- One metric per actor instance
- mailboxSize (current size, derived from messages handed to the actor and handled by it), stashSize (current size), processingTime, timeInMailbox, message count, system message count, error count, restart count
- mailboxNumberOfMessages, read directly from the actor's mailbox rather than derived. Off by default: it is evaluated on every scrape and the default unbounded mailbox is backed by a `ConcurrentLinkedQueue`, whose `size()` walks the queue. Enable with `micrometer.pekko.mailbox.number-of-messages.enabled = true`

#### Actor Router

- One metric per router instance, summed across all routee actors
- routeeCount (current active routees), stashSize (current size), routingTime, timeInMailbox, message count, system message count, error count, restart count

#### Actor Group

- Each actor group has its own include/exclude rules and you can define many groups with individual actors being allowed to be included in many groups - the metrics are summed across all actors in the group
- actorCount (current active actors), mailboxSize (current size), stashSize (current size), processingTime, timeInMailbox, message count, system message count, error count, restart count, lifetime (recorded when each actor stops)

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
