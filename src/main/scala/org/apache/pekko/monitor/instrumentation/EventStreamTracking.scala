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

import scala.util.control.NonFatal

import com.github.pjfanning.micrometer.pekko.{ActorSystemMetrics, MetricsConfig}
import org.apache.pekko.actor.{Dropped, DeadLetter, SuppressedDeadLetter, UnhandledMessage}
import org.apache.pekko.event.{EventStream, Logging}
import org.aspectj.lang.annotation.{Aspect, DeclareMixin}
import org.slf4j.LoggerFactory

/**
 * Shared by the Scala 2 and Scala 3 variants of `DeadLettersInstrumentation`, which have to declare their
 * `EventStream.publish` pointcut differently but track the same events.
 */
private[instrumentation] object EventStreamTracking {

  private val logger = LoggerFactory.getLogger(EventStreamTracking.getClass)
  private val Unknown = "unknown"

  def trackEvent(stream: EventStream, event: Object): Unit = {
    if (MetricsConfig.matchEvents) {
      event match {
        case dl: DeadLetter =>
          ActorSystemMetrics.deadLetterCount(dl.sender.path.address.system).increment()
        case um: UnhandledMessage =>
          ActorSystemMetrics.unhandledMessageCount(um.sender.path.address.system).increment()
        // Dropped and SuppressedDeadLetter are siblings of DeadLetter under AllDeadLetters rather than
        // subtypes of it, so neither is counted by the DeadLetter case above. Both are read off the
        // recipient: `Dropped(message, reason, recipient)` leaves the sender as noSender, which is null.
        case d: Dropped =>
          ActorSystemMetrics.droppedMessageCount(d.recipient.path.address.system).increment()
        case sdl: SuppressedDeadLetter =>
          ActorSystemMetrics.suppressedDeadLetterCount(sdl.recipient.path.address.system).increment()
        case le: Logging.LogEvent =>
          ActorSystemMetrics.logEventCount(systemNameOf(stream), levelNameOf(le)).increment()
        case _ =>
      }
    }
  }

  private def levelNameOf(event: Logging.LogEvent): String = event match {
    case _: Logging.Error   => "error"
    case _: Logging.Warning => "warning"
    case _: Logging.Info    => "info"
    case _: Logging.Debug   => "debug"
    case _                  => Unknown
  }

  /**
   * A log event carries no actor reference, so unlike the dead letter events its actor system has to come
   * from the stream that published it. `EventStream.sys` has no accessor, so it is read reflectively once
   * and then cached on the stream itself by [[ActorSystemIntoEventStreamMixin]].
   */
  private def systemNameOf(stream: EventStream): String = {
    val aware = stream.asInstanceOf[ActorSystemAware]
    if (aware.actorSystem == null) {
      aware.actorSystem = EventStreamTracking.actorSystemField
        .map(_.get(stream).asInstanceOf[org.apache.pekko.actor.ActorSystem])
        .orNull
    }
    val system = aware.actorSystem
    if (system == null) Unknown else system.name
  }

  private lazy val actorSystemField: Option[java.lang.reflect.Field] = {
    try {
      val field = classOf[EventStream].getDeclaredField("sys")
      field.setAccessible(true)
      Some(field)
    } catch {
      case NonFatal(t) =>
        logger.warn("could not read the actor system from EventStream, log events will be untagged", t)
        None
    }
  }
}

@Aspect
class ActorSystemIntoEventStreamMixin {

  @DeclareMixin("org.apache.pekko.event.EventStream")
  def mixinActorSystemAwareToEventStream: ActorSystemAware = ActorSystemAware()
}
