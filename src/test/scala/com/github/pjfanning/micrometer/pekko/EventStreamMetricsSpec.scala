/*
 * =========================================================================================
 * Copyright © 2017,2018 Workday, Inc.
 * Copyright © 2013-2017 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */
package com.github.pjfanning.micrometer.pekko

import scala.concurrent.duration.DurationInt

import org.apache.pekko.actor.{DeadLetterSuppression, Dropped, SuppressedDeadLetter}
import org.apache.pekko.event.Logging
import org.apache.pekko.testkit.TestProbe
import com.github.pjfanning.micrometer.pekko.ActorSystemMetrics._
import io.micrometer.core.instrument.ImmutableTag
import org.scalatest.concurrent.Eventually

object EventStreamMetricsSpec {
  case object Suppressed extends DeadLetterSuppression
}

class EventStreamMetricsSpec extends TestKitBaseSpec("EventStreamMetricsSpec") with Eventually {

  import EventStreamMetricsSpec.Suppressed

  "the event stream metrics" should {
    "count dropped messages" in {
      val probe = TestProbe()
      val before = systemMetric(DroppedMessageCountMetricName)

      // Dropped(message, reason, recipient) leaves the sender as noSender, so the recipient is the only
      // reference the count can be attributed with.
      system.eventStream.publish(Dropped("dropped", "mailbox full", probe.ref))

      eventually(timeout(5.seconds)) {
        systemMetric(DroppedMessageCountMetricName) should be > before
      }
    }

    "count suppressed dead letters" in {
      val probe = TestProbe()
      val before = systemMetric(SuppressedDeadLetterCountMetricName)

      system.eventStream.publish(SuppressedDeadLetter(Suppressed, probe.ref, probe.ref))

      eventually(timeout(5.seconds)) {
        systemMetric(SuppressedDeadLetterCountMetricName) should be > before
      }
    }

    // A log event carries no actor reference, so this also covers the actor system being resolved from
    // the publishing EventStream rather than from the event.
    "count log events by level" in {
      val before = Map(
        "error" -> logEventMetric("error"),
        "warning" -> logEventMetric("warning"),
        "info" -> logEventMetric("info"),
        "debug" -> logEventMetric("debug"))

      system.eventStream.publish(
        Logging.Error(new RuntimeException("boom"), "source", classOf[EventStreamMetricsSpec], "error message"))
      system.eventStream.publish(Logging.Warning("source", classOf[EventStreamMetricsSpec], "warning message"))
      system.eventStream.publish(Logging.Info("source", classOf[EventStreamMetricsSpec], "info message"))
      system.eventStream.publish(Logging.Debug("source", classOf[EventStreamMetricsSpec], "debug message"))

      eventually(timeout(5.seconds)) {
        before.foreach { case (level, count) =>
          withClue(s"$level: ") { logEventMetric(level) should be > count }
        }
      }
    }
  }

  private def systemMetric(name: String): Double =
    PekkoMetricRegistry.metricsForTags(Seq(new ImmutableTag(ActorSystem, system.name))).getOrElse(name, 0.0)

  private def logEventMetric(level: String): Double =
    PekkoMetricRegistry.metricsForTags(
      Seq(new ImmutableTag(ActorSystem, system.name), new ImmutableTag(LogLevel, level)))
      .getOrElse(LogEventCountMetricName, 0.0)
}
