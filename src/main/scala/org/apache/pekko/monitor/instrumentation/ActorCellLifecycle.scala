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

import org.apache.pekko.actor.{ActorCell, ActorInitializationException}
import org.apache.pekko.routing.RoutedActorCell

/**
 * Shared by the Scala 2 and Scala 3 variants of `ActorCellLifecycleInstrumentation`.
 */
private[instrumentation] object ActorCellLifecycle {

  def cleanup(cell: ActorCell): Unit = {
    cell.asInstanceOf[ActorInstrumentationAware].actorInstrumentation.cleanup()

    // The Stop can't be captured from the RoutedActorCell so we need to put this piece of cleanup here.
    if (cell.isInstanceOf[RoutedActorCell]) {
      cell.asInstanceOf[RouterInstrumentationAware].routerInstrumentation.cleanup()
    }
  }

  // TypedActorInstrumentation counts a typed actor's failure when its typed supervisor handles it. A
  // supervisor that stops the actor may then rethrow it to the cell, wrapped as a TypedActorFailedException,
  // which would count the one failure twice.
  def processFailure(cell: ActorCell, failure: Throwable): Unit =
    if (!isRethrownTypedFailure(failure))
      cell.asInstanceOf[ActorInstrumentationAware].actorInstrumentation.processFailure(failure)

  // by name, so that pekko-actor-typed is not needed at runtime
  private val TypedActorFailedExceptionClassName =
    "org.apache.pekko.actor.typed.internal.adapter.ActorAdapter$TypedActorFailedException"

  private def isRethrownTypedFailure(failure: Throwable): Boolean = failure match {
    case e: ActorInitializationException if e.getCause != null => isRethrownTypedFailure(e.getCause)
    case e => e.getClass.getName == TypedActorFailedExceptionClassName
  }

  def processRestart(cell: ActorCell): Unit =
    cell.asInstanceOf[ActorInstrumentationAware].actorInstrumentation.processRestart()
}
