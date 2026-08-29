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

import java.util.concurrent.TimeUnit
import java.util.{Collections, WeakHashMap => JWeakHashMap}

import scala.collection.immutable
import scala.util.control.NonFatal

import com.github.pjfanning.micrometer.pekko.{MetricsConfig, PersistenceMetrics}
import org.apache.pekko.actor.Actor
import org.aspectj.lang.annotation.{AfterReturning, Aspect, Before, Pointcut}

/**
 * Recovery is the slow part of a persistent actor's life and, until it finishes, the actor is not serving
 * anything - none of the actor metrics show that. A rejected or failed persist is worse still: the event
 * the actor thought it had recorded is not there.
 *
 * The advice is on the static `onReplaySuccess$` style methods, for the same reason as the stash
 * instrumentation: everything mixing `Eventsourced` in is a user actor class, outside the woven packages,
 * and its forwarder calls the static directly rather than the interface default method.
 *
 * `Eventsourced` is `private[persistence]`, so it cannot be named as a parameter type here. The pointcuts
 * name it and the advice takes `AnyRef`, which is all that is needed to reach the actor's system.
 */
@Aspect
class PersistenceInstrumentation {

  import PersistenceInstrumentation._

  // startRecovery is private to the trait, so a class mixing Eventsourced in gets no forwarder for it and
  // the interface's own method is what runs. That is the opposite of the public members below, where the
  // forwarder calls the static and the interface method never runs.
  @Pointcut("execution(* org.apache.pekko.persistence.Eventsourced.org$apache$pekko$persistence$Eventsourced$$startRecovery(..)) && this(actor)")
  def startRecovery(actor: AnyRef): Unit = {}

  @Before("startRecovery(actor)")
  def beforeStartRecovery(actor: AnyRef): Unit =
    if (MetricsConfig.persistenceEnabled) recoveryStarts.put(actor, java.lang.Long.valueOf(System.nanoTime()))

  @Pointcut("execution(* org.apache.pekko.persistence.Eventsourced.onReplaySuccess$(..)) && args(actor)")
  def replaySuccess(actor: AnyRef): Unit = {}

  @AfterReturning("replaySuccess(actor)")
  def afterReplaySuccess(actor: AnyRef): Unit = {
    if (MetricsConfig.persistenceEnabled) {
      val started = recoveryStarts.remove(actor)
      if (started != null) {
        systemName(actor).foreach { system =>
          PersistenceMetrics.recoveryTime(system).timer
            .record(System.nanoTime() - started.longValue(), TimeUnit.NANOSECONDS)
        }
      }
    }
  }

  @Pointcut("execution(* org.apache.pekko.persistence.Eventsourced.onRecoveryFailure$(..)) && args(actor, ..)")
  def recoveryFailure(actor: AnyRef): Unit = {}

  @AfterReturning("recoveryFailure(actor)")
  def afterRecoveryFailure(actor: AnyRef): Unit = {
    if (MetricsConfig.persistenceEnabled) {
      recoveryStarts.remove(actor)
      systemName(actor).foreach(PersistenceMetrics.recoveryFailures(_).increment())
    }
  }

  // internalPersist and internalPersistAsync take one event, the All variants take a Seq of them
  @Pointcut("(execution(* org.apache.pekko.persistence.Eventsourced.internalPersist$(..)) || " +
    "execution(* org.apache.pekko.persistence.Eventsourced.internalPersistAsync$(..))) && args(actor, ..)")
  def persistOne(actor: AnyRef): Unit = {}

  @AfterReturning("persistOne(actor)")
  def afterPersistOne(actor: AnyRef): Unit = countPersists(actor, 1)

  @Pointcut("(execution(* org.apache.pekko.persistence.Eventsourced.internalPersistAll$(..)) || " +
    "execution(* org.apache.pekko.persistence.Eventsourced.internalPersistAllAsync$(..))) && args(actor, events, ..)")
  def persistAll(actor: AnyRef, events: immutable.Seq[Any]): Unit = {}

  @AfterReturning("persistAll(actor, events)")
  def afterPersistAll(actor: AnyRef, events: immutable.Seq[Any]): Unit = countPersists(actor, events.size)

  @Pointcut("execution(* org.apache.pekko.persistence.Eventsourced.onPersistFailure$(..)) && args(actor, ..)")
  def persistFailure(actor: AnyRef): Unit = {}

  @AfterReturning("persistFailure(actor)")
  def afterPersistFailure(actor: AnyRef): Unit =
    if (MetricsConfig.persistenceEnabled) systemName(actor).foreach(PersistenceMetrics.persistFailures(_).increment())

  @Pointcut("execution(* org.apache.pekko.persistence.Eventsourced.onPersistRejected$(..)) && args(actor, ..)")
  def persistRejected(actor: AnyRef): Unit = {}

  @AfterReturning("persistRejected(actor)")
  def afterPersistRejected(actor: AnyRef): Unit =
    if (MetricsConfig.persistenceEnabled) systemName(actor).foreach(PersistenceMetrics.persistRejections(_).increment())

  private def countPersists(actor: AnyRef, count: Int): Unit =
    if (MetricsConfig.persistenceEnabled) {
      systemName(actor).foreach(PersistenceMetrics.persists(_).increment(count.toDouble))
    }
}

private[instrumentation] object PersistenceInstrumentation {

  // Weak keys, so an actor that dies part way through recovery does not hold an entry open. Recovery
  // starts and finishes rarely enough that the synchronization does not matter.
  private val recoveryStarts: java.util.Map[AnyRef, java.lang.Long] =
    Collections.synchronizedMap(new JWeakHashMap[AnyRef, java.lang.Long]())

  private def systemName(actor: AnyRef): Option[String] = {
    try {
      actor match {
        case a: Actor => Some(a.context.system.name)
        case _        => None
      }
    } catch {
      // context throws if it is read outside the actor's lifecycle
      case NonFatal(_) => None
    }
  }
}
