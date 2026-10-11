package org.apache.pekko.monitor.instrumentation

import org.apache.pekko.actor.typed.TypedActorContext
import org.apache.pekko.actor.typed.internal.StashBufferImpl
import org.apache.pekko.actor.typed.internal.adapter.ActorContextAdapter
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.{AfterReturning, Around, Aspect, Before, Pointcut}

/**
 * A typed actor runs inside a classic ActorCell, so messages, timings and the actor's lifecycle are
 * already covered by ActorCellInstrumentation. Two things are not, because typed handles them inside the
 * behavior where the cell never sees them:
 *
 *  - Supervision. A typed supervisor catches the failure itself, so a resumed or restarted typed actor
 *    never reaches ActorCell.handleInvokeFailure or faultRecreate. A typed actor spawned by a classic
 *    parent is wrapped in a stop supervisor and is stopped rather than failed, so even its last failure
 *    is not seen. The failure is counted here instead, when the typed supervisor handles it, and
 *    ActorCellLifecycle leaves out the TypedActorFailedException that may then be rethrown to the cell.
 *  - Stashing. A typed actor stashes into a StashBuffer rather than the classic StashSupport.
 *
 * The join points compile to the same names under Scala 2.12, 2.13 and 3, so there is no per-version
 * variant. The aspect is only active when pekko-actor-typed is on the classpath, see aop.xml.
 */
@Aspect
class TypedActorInstrumentation {

  import TypedActorInstrumentation._

  // Every typed supervisor logs the failure it handles, once, through this method - whatever it then
  // does with the actor. Only the three argument form, since the two argument one delegates to it. The
  // method is still called when the strategy has logging turned off; the check is inside it.
  @Pointcut("execution(* org.apache.pekko.actor.typed.internal.AbstractSupervisor.log(..)) && args(ctx, failure, errorCount)")
  def supervisorSawFailure(ctx: TypedActorContext[_], failure: Throwable, errorCount: Int): Unit = {}

  @Before("supervisorSawFailure(ctx, failure, errorCount)")
  def beforeSupervisorLog(ctx: TypedActorContext[_], failure: Throwable, errorCount: Int): Unit =
    withMonitor(ctx)(_.processFailure(failure))

  // Restart and restart with backoff both go through prepareRestart. A restart that would exceed the
  // configured limit does not, it rethrows instead and the actor stops.
  //
  // A classic restart empties the stash, as preRestart unstashes everything. A typed restart starts the
  // behavior again, and a StashBuffer created inside it is replaced and its messages dropped, so the stash
  // is emptied here too. The buffer the supervisor itself stashes into while the restart is in progress
  // is filled after this.
  @Pointcut("execution(* org.apache.pekko.actor.typed.internal.RestartSupervisor.org$apache$pekko$actor$typed$internal$RestartSupervisor$$prepareRestart(..)) && args(ctx, reason)")
  def typedRestart(ctx: TypedActorContext[_], reason: Throwable): Unit = {}

  @Before("typedRestart(ctx, reason)")
  def beforeTypedRestart(ctx: TypedActorContext[_], reason: Throwable): Unit =
    withMonitor(ctx) { monitor =>
      monitor.processStashCleared()
      monitor.processRestart()
    }

  // StashBufferImpl has hooks marked `@InternalStableApi` (createNode, unstashed, stashCleared), but the
  // Scala 2.13 build of pekko inlines them, so advice on them never runs there. stash, clear and the
  // mangled dropHeadForUnstash are called rather than inlined in the 2.12, 2.13 and 3 builds alike, and
  // the actor's context is read off the buffer instead.
  //
  // @AfterReturning, so that a stash rejected with a StashOverflowException is not counted. The bridge
  // methods that stash(T) gets for the javadsl and scaladsl return types are not advised by AspectJ.
  @Pointcut("execution(* org.apache.pekko.actor.typed.internal.StashBufferImpl.stash(*)) && this(buffer)")
  def typedStash(buffer: StashBufferImpl[_]): Unit = {}

  @AfterReturning("typedStash(buffer)")
  def afterTypedStash(buffer: StashBufferImpl[_]): Unit =
    withMonitor(buffer)(_.processStashed(1))

  // called for each message taken off the buffer, by unstash and unstashAll alike
  @Pointcut("execution(* org.apache.pekko.actor.typed.internal.StashBufferImpl.org$apache$pekko$actor$typed$internal$StashBufferImpl$$dropHeadForUnstash()) && this(buffer)")
  def typedUnstash(buffer: StashBufferImpl[_]): Unit = {}

  @AfterReturning("typedUnstash(buffer)")
  def afterTypedUnstash(buffer: StashBufferImpl[_]): Unit =
    withMonitor(buffer)(_.processUnstashed())

  // clear() drops whatever the buffer holds without unstashing it. An actor can hold more than one
  // StashBuffer - typed restart supervision keeps its own while a restart is in progress - so the
  // buffer's own size is taken off rather than the actor's stash being zeroed.
  @Pointcut("execution(* org.apache.pekko.actor.typed.internal.StashBufferImpl.clear()) && this(buffer)")
  def typedClear(buffer: StashBufferImpl[_]): Unit = {}

  @Around("typedClear(buffer)")
  def aroundTypedClear(pjp: ProceedingJoinPoint, buffer: StashBufferImpl[_]): AnyRef = {
    val size = buffer.size
    val result = pjp.proceed()
    if (size > 0) withMonitor(buffer)(_.processUnstashed(size))
    result
  }
}

private[instrumentation] object TypedActorInstrumentation {

  // The context StashBufferImpl was created with is a private constructor parameter. Scala keeps it as a
  // public field under its mangled name, the same in the 2.12, 2.13 and 3 builds.
  private val stashBufferContextField = {
    val field = classOf[StashBufferImpl[_]].getDeclaredField(
      "org$apache$pekko$actor$typed$internal$StashBufferImpl$$ctx")
    field.setAccessible(true)
    field
  }

  private def withMonitor(buffer: StashBufferImpl[_])(action: ActorMonitor => Unit): Unit =
    stashBufferContextField.get(buffer) match {
      case ctx: TypedActorContext[_] => withMonitor(ctx)(action)
      case _                         =>
    }

  // A context that is not backed by an ActorCell, such as the BehaviorTestKit's, has nothing to record to.
  private def withMonitor(ctx: TypedActorContext[_])(action: ActorMonitor => Unit): Unit = {
    ctx match {
      case adapter: ActorContextAdapter[_] =>
        adapter.classicActorContext match {
          case aware: ActorInstrumentationAware =>
            val monitor = aware.actorInstrumentation
            if (monitor != null) action(monitor)
          case _ =>
        }
      case _ =>
    }
  }
}
