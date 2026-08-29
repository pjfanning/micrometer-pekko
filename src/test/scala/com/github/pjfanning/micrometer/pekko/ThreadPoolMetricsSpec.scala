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

import java.util.concurrent.{LinkedBlockingQueue, ThreadPoolExecutor, TimeUnit}

import scala.concurrent.duration.DurationInt

import org.scalatest.concurrent.Eventually

class ThreadPoolMetricsSpec extends BaseSpec with Eventually {

  private val CorePoolSize = 2
  private val MaxPoolSize = 4

  private val ExpectedGauges = Set(
    "pekko_dispatcher_threadpoolexecutor_active_thread_count",
    "pekko_dispatcher_threadpoolexecutor_core_pool_size",
    "pekko_dispatcher_threadpoolexecutor_current_pool_size",
    "pekko_dispatcher_threadpoolexecutor_largest_pool_size",
    "pekko_dispatcher_threadpoolexecutor_max_pool_size",
    "pekko_dispatcher_threadpoolexecutor_completed_task_count",
    "pekko_dispatcher_threadpoolexecutor_total_task_count"
  )

  "ThreadPoolMetrics" should {
    "register the full set of gauges" in {
      withExecutor("ThreadPoolMetricsSpec-gauge-set") { (_, metrics) =>
        metrics.keySet should be (ExpectedGauges)
      }
    }

    // CorePoolSize and MaxPoolSize are distinct and fixed by the executor's construction, so a gauge
    // reading the wrong pool method - the bug fixed in #257 for the ForkJoinPool gauges - fails here.
    "report the configured pool sizes" in {
      withExecutor("ThreadPoolMetricsSpec-pool-sizes") { (_, metrics) =>
        metrics("pekko_dispatcher_threadpoolexecutor_core_pool_size") should be (CorePoolSize.toDouble)
        metrics("pekko_dispatcher_threadpoolexecutor_max_pool_size") should be (MaxPoolSize.toDouble)
      }
    }

    // The remaining gauges track live pool state, so they are asserted broadly on purpose.
    "report non-negative values for the live pool gauges" in {
      withExecutor("ThreadPoolMetricsSpec-live-gauges") { (_, metrics) =>
        metrics.foreach { case (name, value) =>
          withClue(s"$name: ") { value should be >= 0.0 }
        }
      }
    }

    "count submitted tasks" in {
      withExecutor("ThreadPoolMetricsSpec-task-count") { (executor, _) =>
        // getTaskCount sums the workers' own counters, which the worker thread updates after the
        // submitted task has already handed its result back, so the count is read under `eventually`.
        executor.submit(new Runnable { def run(): Unit = () }).get(5, TimeUnit.SECONDS)
        eventually(timeout(5.seconds)) {
          val metrics = findMetrics("ThreadPoolMetricsSpec-task-count")
          metrics("pekko_dispatcher_threadpoolexecutor_total_task_count") should be >= 1.0
          metrics("pekko_dispatcher_threadpoolexecutor_completed_task_count") should be >= 1.0
        }
      }
    }
  }

  private def findMetrics(name: String): Map[String, Double] =
    DispatcherMetricsSpec.findDispatcherRecorder(name, "ThreadPoolExecutor", false)

  private def withExecutor(name: String)(test: (ThreadPoolExecutor, Map[String, Double]) => Any): Unit = {
    val executor = new ThreadPoolExecutor(CorePoolSize, MaxPoolSize, 60L, TimeUnit.SECONDS,
      new LinkedBlockingQueue[Runnable])
    try {
      ThreadPoolMetrics.add(name, executor)
      test(executor, findMetrics(name))
      // micrometer gauges hold the executor weakly, so keep it alive until the assertions are done
      executor.getCorePoolSize should be (CorePoolSize)
    } finally {
      executor.shutdownNow()
    }
  }
}
