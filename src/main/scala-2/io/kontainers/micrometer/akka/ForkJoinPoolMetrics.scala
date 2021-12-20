package io.kontainers.micrometer.akka

import io.kontainers.micrometer.akka.impl.DoubleFunction
import io.micrometer.core.instrument.{ImmutableTag, Tag}

import scala.collection.JavaConverters._

object ForkJoinPoolMetrics {
  val DispatcherName = "dispatcherName"

  def add(dispatcherName: String, fjp: ForkJoinPoolLike): Unit = {
    import io.kontainers.micrometer.akka.AkkaMetricRegistry._
    val tags: Iterable[Tag] = Seq(new ImmutableTag(DispatcherName, dispatcherName))
    val jtags = tags.asJava
    val parellelismFn = new DoubleFunction[ForkJoinPoolLike](_.getParallelism)
    val poolSizeFn = new DoubleFunction[ForkJoinPoolLike](_.getParallelism)
    val activeThreadCountFn = new DoubleFunction[ForkJoinPoolLike](_.getActiveThreadCount)
    val runningThreadCountFn = new DoubleFunction[ForkJoinPoolLike](_.getRunningThreadCount)
    val queuedSubmissionCountFn = new DoubleFunction[ForkJoinPoolLike](_.getQueuedSubmissionCount)
    val queuedTaskCountFn = new DoubleFunction[ForkJoinPoolLike](_.getQueuedTaskCount)
    val stealCountFn = new DoubleFunction[ForkJoinPoolLike](_.getStealCount)
    getRegistry.gauge("akka_dispatcher_forkjoinpool_parellelism", jtags, fjp, parellelismFn)
    getRegistry.gauge("akka_dispatcher_forkjoinpool_pool_size", jtags, fjp, poolSizeFn)
    getRegistry.gauge("akka_dispatcher_forkjoinpool_active_thread_count", jtags, fjp, activeThreadCountFn)
    getRegistry.gauge("akka_dispatcher_forkjoinpool_running_thread_count", jtags, fjp, runningThreadCountFn)
    getRegistry.gauge("akka_dispatcher_forkjoinpool_queued_task_count", jtags, fjp, queuedSubmissionCountFn)
    getRegistry.gauge("akka_dispatcher_forkjoinpool_queued_submission_count", jtags, fjp, queuedTaskCountFn)
    getRegistry.gauge("akka_dispatcher_forkjoinpool_steal_count", jtags, fjp, stealCountFn)
  }
}
