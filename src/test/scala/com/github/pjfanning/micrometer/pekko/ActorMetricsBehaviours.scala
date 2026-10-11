package com.github.pjfanning.micrometer.pekko

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.DurationInt

import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.monitor.instrumentation.CellInfo
import io.micrometer.core.instrument.ImmutableTag
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
 * The per actor metrics, written once and run against both a classic and a typed actor by ActorMetricsSpec
 * and TypedActorMetricsSpec. The expected values are the same for both, which is the point.
 */
trait ActorMetricsBehaviours extends Eventually { this: AnyWordSpecLike with Matchers =>

  def system: ActorSystem

  def actorMetrics(flavour: ActorFlavour): Unit = {

    "respect the configured include and exclude filters" in {
      val trackedActor = flavour.create(system, "tracked-actor")
      val nonTrackedActor = flavour.create(system, "non-tracked-actor")
      val excludedTrackedActor = flavour.create(system, "tracked-explicitly-excluded-actor")

      actorMetricsRecorderOf(trackedActor) should not be empty
      actorMetricsRecorderOf(nonTrackedActor) shouldBe empty
      actorMetricsRecorderOf(excludedTrackedActor) shouldBe empty

      val metrics = actorMetricsRecorderOf(trackedActor).get
      metrics.actorName shouldEqual s"${system.name.toLowerCase}_user_tracked_actor"
      metrics.messages.count() shouldEqual 1.0
    }

    "count every message sent to a tracked actor" in {
      val trackedActor = flavour.create(system, "tracked-counting-actor")
      val metrics = actorMetricsRecorderOf(trackedActor).get

      (1 to 4).foreach(_ => flavour.ping(system, trackedActor))

      eventually(timeout(5.seconds)) {
        metrics.messages.count() shouldEqual 5.0
        metrics.processingTime.timer.count() shouldEqual 5L
        metrics.timeInMailbox.timer.count() shouldEqual 5L
        gaugeValue(metrics.mailboxSize) shouldEqual 0.0
      }
    }

    "count the errors thrown by a tracked actor" in {
      val trackedActor = flavour.create(system, "tracked-failing-actor")
      val metrics = actorMetricsRecorderOf(trackedActor).get
      val originalErrors = metrics.errors.count()

      flavour.fail(trackedActor)

      eventually(timeout(5.seconds)) {
        metrics.errors.count() shouldEqual (originalErrors + 1.0)
      }
    }

    // Both timers are closed in the `finally` of ActorMonitor.processMessage, which runs after the actor
    // has already replied, so the counts are read under `eventually`. The values themselves are left
    // broad on purpose - only that something was recorded is deterministic.
    "record processing time and time in mailbox for a tracked actor" in {
      val trackedActor = flavour.create(system, "tracked-timing-actor")
      val metrics = actorMetricsRecorderOf(trackedActor).get

      eventually(timeout(5.seconds)) {
        metrics.processingTime.timer.count() should be >= 1L
        metrics.timeInMailbox.timer.count() should be >= 1L
      }
      metrics.processingTime.timer.totalTime(TimeUnit.NANOSECONDS) should be >= 0.0
      metrics.timeInMailbox.timer.totalTime(TimeUnit.NANOSECONDS) should be >= 0.0
    }

    // The actor is supervised to restart on a plain Exception, so the one failure is counted both as an
    // error and as a restart. Asserted separately because a resumed or stopped actor would only move the
    // error counter. The actor has to keep working afterwards, and the message counter keeps counting.
    "count the restarts of a tracked actor" in {
      val trackedActor = flavour.create(system, "tracked-restarting-actor")
      val metrics = actorMetricsRecorderOf(trackedActor).get
      val originalErrors = metrics.errors.count()
      val originalRestarts = metrics.restarts.count()

      flavour.fail(trackedActor)

      eventually(timeout(5.seconds)) {
        metrics.errors.count() shouldEqual (originalErrors + 1.0)
        metrics.restarts.count() shouldEqual (originalRestarts + 1.0)
      }

      flavour.ping(system, trackedActor)
      eventually(timeout(5.seconds)) {
        metrics.messages.count() shouldEqual 3.0
      }
    }

    // Every actor is sent at least a Create system message, so the count is asserted broadly rather than
    // pinned to the exact set of system messages pekko happens to send.
    "count the system messages handled by a tracked actor" in {
      val trackedActor = flavour.create(system, "tracked-system-message-actor")
      val metrics = actorMetricsRecorderOf(trackedActor).get

      eventually(timeout(5.seconds)) {
        metrics.systemMessages.count() should be >= 1.0
      }
    }

    "read the mailbox depth off the mailbox of a tracked actor" in {
      val trackedActor = flavour.create(system, "tracked-mailbox-actor")
      val metrics = actorMetricsRecorderOf(trackedActor).get

      // an idle actor has drained its mailbox
      eventually(timeout(5.seconds)) {
        mailboxNumberOfMessages(metrics) should be (Some(0.0))
      }

      // and the gauge goes away with the cell, rather than being left to report NaN
      system.stop(trackedActor)
      eventually(timeout(5.seconds)) {
        mailboxNumberOfMessages(metrics) should be (None)
      }
    }

    // Read once the actor is running rather than before it is created: creating it also creates the
    // TestProbe that waits for its reply, which is an actor too and is never stopped.
    "count the actor in the actor system's actor count until it stops" in {
      val actor = flavour.create(system, "tracked-counted-actor")
      val countWhileRunning = actorSystemMetric(ActorSystemMetrics.ActorCountMetricName).get

      system.stop(actor)
      eventually(timeout(5.seconds)) {
        actorSystemMetric(ActorSystemMetrics.ActorCountMetricName) shouldEqual Some(countWhileRunning - 1.0)
      }
    }

    "count the unhandled messages of an actor" in {
      val actor = flavour.create(system, "tracked-unhandling-actor")
      val count = actorSystemMetric(ActorSystemMetrics.UnhandledMessageCountMetricName).getOrElse(0.0)

      flavour.sendUnhandled(actor)

      eventually(timeout(5.seconds)) {
        actorSystemMetric(ActorSystemMetrics.UnhandledMessageCountMetricName) shouldEqual Some(count + 1.0)
      }
    }

    "count the messages sent to a stopped actor as dead letters" in {
      val actor = flavour.create(system, "tracked-dead-actor")
      val count = actorSystemMetric(ActorSystemMetrics.DeadLetterCountMetricName).getOrElse(0.0)

      system.stop(actor)
      eventually(timeout(5.seconds)) {
        flavour.sendUnhandled(actor)
        actorSystemMetric(ActorSystemMetrics.DeadLetterCountMetricName).getOrElse(0.0) should be > count
      }
    }
  }

  def mailboxNumberOfMessages(metrics: ActorMetrics): Option[Double] = {
    Option(PekkoMetricRegistry.getRegistry.find(metrics.mailboxNumberOfMessagesName).gauge()).map(_.value())
  }

  def gaugeValue(gauge: GaugeWrapper): Double =
    PekkoMetricRegistry.getRegistry.find(gauge.name).gauge().value()

  def actorSystemMetric(metricName: String): Option[Double] =
    PekkoMetricRegistry.metricsForTags(Seq(new ImmutableTag(ActorSystemMetrics.ActorSystem, system.name)))
      .get(metricName)

  def actorMetricsRecorderOf(ref: ActorRef): Option[ActorMetrics] = {
    val name = CellInfo.cellName(system, ref)
    val entity = Entity(name, MetricsConfig.Actor)
    if (ActorMetrics.hasMetricsFor(entity)) {
      ActorMetrics.metricsFor(entity)
    } else {
      None
    }
  }
}
