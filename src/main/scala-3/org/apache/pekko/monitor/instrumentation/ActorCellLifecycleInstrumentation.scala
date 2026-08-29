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

import org.apache.pekko.actor.{ActorCell, ActorRef}
import org.aspectj.lang.annotation.{After, Aspect, Before, Pointcut}

import scala.collection.immutable

/**
 * `stop` and `handleInvokeFailure` reach ActorCell from the `org.apache.pekko.actor.dungeon` traits, and how
 * that lands in the bytecode depends on the Scala version Pekko was built with, so this aspect has a
 * `scala-2` and a `scala-3` variant.
 *
 * Under Scala 3 the mixin forwarders that Pekko generates in ActorCell are ACC_BRIDGE/ACC_SYNTHETIC and
 * AspectJ never weaves those, so matching on ActorCell silently advises nothing. The trait's default method
 * is what actually runs, so that is the type to match on.
 */
@Aspect
class ActorCellLifecycleInstrumentation {

  @Pointcut("execution(* org.apache.pekko.actor.dungeon.Dispatch.stop()) && this(cell)")
  def actorStop(cell: ActorCell): Unit = {}

  @After("actorStop(cell)")
  def afterStop(cell: ActorCell): Unit =
    ActorCellLifecycle.cleanup(cell)

  @Pointcut("execution(* org.apache.pekko.actor.dungeon.FaultHandling.handleInvokeFailure(..)) && this(cell) && args(childrenNotToSuspend, failure)")
  def actorInvokeFailure(cell: ActorCell, childrenNotToSuspend: immutable.Iterable[ActorRef], failure: Throwable): Unit = {}

  @Before("actorInvokeFailure(cell, childrenNotToSuspend, failure)")
  def beforeInvokeFailure(cell: ActorCell, childrenNotToSuspend: immutable.Iterable[ActorRef], failure: Throwable): Unit =
    ActorCellLifecycle.processFailure(cell, failure)

  @Pointcut("execution(* org.apache.pekko.actor.dungeon.FaultHandling.faultRecreate(..)) && this(cell) && args(cause)")
  def actorRestart(cell: ActorCell, cause: Throwable): Unit = {}

  // faultRecreate is the restart itself, run after the supervisor has decided. handleInvokeFailure above
  // fires for every failure whatever the directive, so the two counters separate a restart storm from an
  // actor that keeps failing and resuming.
  @Before("actorRestart(cell, cause)")
  def beforeRestart(cell: ActorCell, cause: Throwable): Unit =
    ActorCellLifecycle.processRestart(cell)
}
