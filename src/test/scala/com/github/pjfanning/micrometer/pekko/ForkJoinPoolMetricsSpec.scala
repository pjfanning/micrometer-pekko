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

import org.slf4j.LoggerFactory

class ForkJoinPoolMetricsSpec extends BaseSpec {

  val logger = LoggerFactory.getLogger(classOf[ForkJoinPoolMetricsSpec])

  override def beforeAll(): Unit = {
    super.beforeAll()
    PekkoMetricRegistry.clear()
  }

  "ForkJoinPoolMetrics" should {
    "support java forkjoinpool" in {
      val name = "ForkJoinPoolMetricsSpec-java-pool"
      val pool = new java.util.concurrent.ForkJoinPool
      try {
        ForkJoinPoolMetrics.add(name, pool.asInstanceOf[ForkJoinPoolLike])
        DispatcherMetricsSpec.findDispatcherRecorder(name, "ForkJoinPool", false) should not be(empty)
      } finally {
        pool.shutdownNow()
      }
    }

    "support scala forkjoinpool" in {
      try {
        val clazz = Class.forName("scala.concurrent.forkjoin.ForkJoinPool")
        val name = "ForkJoinPoolMetricsSpec-scala-pool"
        val pool = clazz.newInstance
        try {
          ForkJoinPoolMetrics.add(name, pool.asInstanceOf[ForkJoinPoolLike])
          DispatcherMetricsSpec.findDispatcherRecorder(name, "ForkJoinPool", false) should not be (empty)
        } finally {
          val method = clazz.getMethod("shutdownNow")
          method.invoke(pool)
        }
      } catch {
        case _: ClassNotFoundException => {
          logger.warn("skipping scala forkjoinpool test as class no longer supported")
        }
      }
    }

    "report each gauge from the matching pool method" in {
      val name = "ForkJoinPoolMetricsSpec-stub-pool"
      // every value is distinct, so a gauge wired to the wrong pool method fails here
      val pool = new StubForkJoinPool
      ForkJoinPoolMetrics.add(name, pool)
      DispatcherMetricsSpec.findDispatcherRecorder(name, "ForkJoinPool", false) should be (Map(
        "pekko_dispatcher_forkjoinpool_parallelism" -> 3.0,
        "pekko_dispatcher_forkjoinpool_pool_size" -> 7.0,
        "pekko_dispatcher_forkjoinpool_active_thread_count" -> 2.0,
        "pekko_dispatcher_forkjoinpool_running_thread_count" -> 1.0,
        "pekko_dispatcher_forkjoinpool_queued_submission_count" -> 5.0,
        "pekko_dispatcher_forkjoinpool_queued_task_count" -> 11.0,
        "pekko_dispatcher_forkjoinpool_steal_count" -> 23.0
      ))
      // micrometer gauges hold the pool weakly, so keep it alive until the assertions are done
      pool.getParallelism should be (3)
    }
  }
}

/** Stand-in for a ForkJoinPool that reports a distinct constant per method. */
class StubForkJoinPool {
  def getParallelism: Int = 3
  def getPoolSize: Int = 7
  def getActiveThreadCount: Int = 2
  def getRunningThreadCount: Int = 1
  def getQueuedSubmissionCount: Int = 5
  def getQueuedTaskCount: Long = 11L
  def getStealCount: Long = 23L
}
