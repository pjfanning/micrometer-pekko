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

import scala.collection.immutable

import org.apache.pekko.actor.StashSupport
import org.apache.pekko.dispatch.Envelope
import org.aspectj.lang.annotation.{AfterReturning, Aspect, Pointcut}

/**
 * An unbounded stash that is never drained is a common way to run out of memory, and nothing else the
 * library records shows it: a stashed message has already left the mailbox and has not been handled yet.
 *
 * `StashSupport.theStash` is plain `private`, so the size cannot be read back and is instead followed
 * through the operations that change it. Every advice is `@AfterReturning` so that a `stash()` rejected
 * with a StashOverflowException is not counted.
 *
 * The advice is on the static `stash$` style methods rather than on the interface default methods. Both
 * are emitted, but a class mixing the trait in gets a forwarder that calls the static directly, so the
 * default method never runs and advising it matches nothing. Both the Scala 2 and the Scala 3 builds of
 * pekko use that encoding, so unlike `stop` and `handleInvokeFailure` this needs no per-version variant.
 */
@Aspect
class StashInstrumentation {

  @Pointcut("execution(* org.apache.pekko.actor.StashSupport.stash$(..)) && args(stash)")
  def stashOne(stash: StashSupport): Unit = {}

  @AfterReturning("stashOne(stash)")
  def afterStashOne(stash: StashSupport): Unit = withMonitor(stash)(_.processStashed(1))

  @Pointcut("execution(* org.apache.pekko.actor.StashSupport.prepend$(..)) && args(stash, envelopes)")
  def prependToStash(stash: StashSupport, envelopes: immutable.Seq[Envelope]): Unit = {}

  @AfterReturning("prependToStash(stash, envelopes)")
  def afterPrependToStash(stash: StashSupport, envelopes: immutable.Seq[Envelope]): Unit =
    withMonitor(stash)(_.processStashed(envelopes.size))

  @Pointcut("execution(* org.apache.pekko.actor.StashSupport.unstash$(..)) && args(stash)")
  def unstashOne(stash: StashSupport): Unit = {}

  // unstash() on an empty stash is a no-op, which the monitor handles by never going below zero
  @AfterReturning("unstashOne(stash)")
  def afterUnstashOne(stash: StashSupport): Unit = withMonitor(stash)(_.processUnstashed())

  // The no-arg unstashAll delegates to the filtering one, so both fire; clearing twice is idempotent.
  // args(stash, ..) covers unstashAll$ in both its arities.
  @Pointcut("(execution(* org.apache.pekko.actor.StashSupport.unstashAll$(..)) || " +
    "execution(* org.apache.pekko.actor.StashSupport.clearStash$(..))) && args(stash, ..)")
  def emptyStash(stash: StashSupport): Unit = {}

  @AfterReturning("emptyStash(stash)")
  def afterEmptyStash(stash: StashSupport): Unit = withMonitor(stash)(_.processStashCleared())

  private def withMonitor(stash: StashSupport)(action: ActorMonitor => Unit): Unit = {
    stash.context match {
      case aware: ActorInstrumentationAware =>
        val monitor = aware.actorInstrumentation
        if (monitor != null) action(monitor)
      case _ =>
    }
  }
}
