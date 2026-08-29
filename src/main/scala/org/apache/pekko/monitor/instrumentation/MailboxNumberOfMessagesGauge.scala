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
package org.apache.pekko.monitor.instrumentation

import java.util.function.ToDoubleFunction

import com.github.pjfanning.micrometer.pekko.{ActorMetrics, MetricsConfig, PekkoMetricRegistry}
import io.micrometer.core.instrument.{Gauge, Meter}
import org.apache.pekko.actor.ActorCell

/**
 * The mailbox size that `ActorMetrics` maintains is derived: incremented when a message is handed to the
 * cell and decremented once it has been handled. That misses anything already queued before the actor was
 * instrumented, and counts nothing for system messages. This reads `Mailbox.numberOfMessages` instead.
 *
 * It is off by default. The gauge is read on every scrape, and the default unbounded mailbox is backed by
 * a `ConcurrentLinkedQueue`, whose `size()` walks the queue.
 */
private[instrumentation] object MailboxNumberOfMessagesGauge {

  private val numberOfMessages = new ToDoubleFunction[ActorCell] {
    override def applyAsDouble(cell: ActorCell): Double = cell.mailbox.numberOfMessages.toDouble
  }

  def register(metrics: ActorMetrics, cell: ActorCell): Option[Meter.Id] = {
    if (MetricsConfig.mailboxNumberOfMessagesEnabled) {
      // micrometer holds the cell weakly, so a stopped actor does not keep its cell alive
      val gauge = Gauge.builder(metrics.mailboxNumberOfMessagesName, cell, numberOfMessages)
        .register(PekkoMetricRegistry.getRegistry)
      Some(gauge.getId)
    } else {
      None
    }
  }

  def remove(id: Meter.Id): Unit = PekkoMetricRegistry.getRegistry.remove(id)
}
