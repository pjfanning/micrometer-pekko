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

import org.apache.pekko.actor.{ActorCell, ActorRef, ActorSystem, Cell, InternalActorRef, UnstartedCell}
import org.apache.pekko.dispatch.Envelope
import org.apache.pekko.dispatch.sysmsg.SystemMessage
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation._

import java.util.concurrent.locks.ReentrantLock

@Aspect
class ActorCellInstrumentation {

  private def actorInstrumentation(cell: Cell): ActorMonitor =
    cell.asInstanceOf[ActorInstrumentationAware].actorInstrumentation

  @Pointcut("execution(org.apache.pekko.actor.ActorCell.new(..)) && this(cell) && args(system, ref, *, *, parent)")
  def actorCellCreation(cell: Cell, system: ActorSystem, ref: ActorRef, parent: InternalActorRef): Unit = {}

  @Pointcut("execution(org.apache.pekko.actor.UnstartedCell.new(..)) && this(cell) && args(system, ref, *, parent)")
  def repointableActorRefCreation(cell: Cell, system: ActorSystem, ref: ActorRef, parent: InternalActorRef): Unit = {}

  @After("actorCellCreation(cell, system, ref, parent)")
  def afterCreation(cell: Cell, system: ActorSystem, ref: ActorRef, parent: ActorRef): Unit = {
    cell.asInstanceOf[ActorInstrumentationAware].setActorInstrumentation(
      ActorMonitor.createActorMonitor(cell, system, ref, parent, true))
  }

  @After("repointableActorRefCreation(cell, system, ref, parent)")
  def afterRepointableActorRefCreation(cell: Cell, system: ActorSystem, ref: ActorRef, parent: ActorRef): Unit = {
    cell.asInstanceOf[ActorInstrumentationAware].setActorInstrumentation(
      ActorMonitor.createActorMonitor(cell, system, ref, parent, false))
  }

  @Pointcut("execution(* org.apache.pekko.actor.ActorCell.invoke(*)) && this(cell) && args(envelope)")
  def invokingActorBehaviourAtActorCell(cell: ActorCell, envelope: Envelope) = {}

  @Around("invokingActorBehaviourAtActorCell(cell, envelope)")
  def aroundBehaviourInvoke(pjp: ProceedingJoinPoint, cell: ActorCell, envelope: Envelope): Any = {
    actorInstrumentation(cell).processMessage(pjp, envelope.asInstanceOf[InstrumentedEnvelope].envelopeContext())
  }

  @Pointcut("execution(* org.apache.pekko.dispatch.MessageDispatcher.dispatch(..)) && args(receiver, invocation)")
  def sendMessageInActorCell(receiver: ActorCell, invocation: Envelope): Unit = {}

  @Pointcut("execution(* org.apache.pekko.actor.UnstartedCell.sendMessage(*)) && this(cell) && args(envelope)")
  def sendMessageInUnstartedActorCell(cell: Cell, envelope: Envelope): Unit = {}


  @Before("sendMessageInActorCell(receiver, invocation)")
  def beforeSendMessageInActorCell(receiver: ActorCell, invocation: Envelope): Unit = {
    setEnvelopeContext(receiver, invocation)
  }

  @Before("sendMessageInUnstartedActorCell(cell, envelope)")
  def beforeSendMessageInUnstartedActorCell(cell: Cell, envelope: Envelope): Unit = {
    setEnvelopeContext(cell, envelope)
  }

  private def setEnvelopeContext(cell: Cell, envelope: Envelope): Unit = {
    envelope.asInstanceOf[InstrumentedEnvelope].setEnvelopeContext(
      actorInstrumentation(cell).captureEnvelopeContext())
  }

  @Pointcut("execution(* org.apache.pekko.actor.UnstartedCell.replaceWith(*)) && this(unStartedCell) && args(cell)")
  def replaceWithInRepointableActorRef(unStartedCell: UnstartedCell, cell: Cell): Unit = {}

  @Around("replaceWithInRepointableActorRef(unStartedCell, cell)")
  def aroundReplaceWithInRepointableActorRef(pjp: ProceedingJoinPoint, unStartedCell: UnstartedCell, cell: Cell): Unit = {
    import ActorCellInstrumentation._
    // TODO: Find a way to do this without resorting to reflection and, even better, without copy/pasting the Akka Code!
    val queue = unstartedCellQueueField.get(unStartedCell).asInstanceOf[java.util.LinkedList[_]]
    val lock = unstartedCellLockField.get(unStartedCell).asInstanceOf[ReentrantLock]

    def locked[T](body: => T): T = {
      lock.lock()
      try body finally lock.unlock()
    }

    locked {
      try {
        while (!queue.isEmpty) {
          queue.poll() match {
            case s: SystemMessage => cell.sendSystemMessage(s) // TODO: ============= CHECK SYSTEM MESSAGESSSSS =========
            case e: Envelope with InstrumentedEnvelope => cell.sendMessage(e)
            case e: Envelope => cell.sendMessage(e)
          }
        }
      } finally {
        unStartedCell.self.swapCell(cell)
      }
    }
  }

  // ActorCell.stop and ActorCell.handleInvokeFailure are advised by ActorCellLifecycleInstrumentation, which
  // has to be written differently for Scala 2 and Scala 3.
}

object ActorCellInstrumentation {
  private val (unstartedCellQueueField, unstartedCellLockField) = {
    val unstartedCellClass = classOf[UnstartedCell]
    val queueFieldName = "queue"

    val queueField = unstartedCellClass.getDeclaredField(queueFieldName)
    queueField.setAccessible(true)

    val lockField = unstartedCellClass.getDeclaredField("lock")
    lockField.setAccessible(true)

    (queueField, lockField)
  }

}
