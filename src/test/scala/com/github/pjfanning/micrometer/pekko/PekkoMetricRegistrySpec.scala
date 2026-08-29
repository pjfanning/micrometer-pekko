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

import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

class PekkoMetricRegistrySpec extends BaseSpec {

  private def tags(value: String): Seq[Tag] = Seq(Tag.of("pekkoMetricRegistrySpec", value))

  "PekkoMetricRegistry" should {
    "fall back to the built in registry when none has been set" in {
      PekkoMetricRegistry.getRegistry should not be null
    }

    "use a registry once one has been set" in {
      val original = PekkoMetricRegistry.getRegistry
      val replacement = new SimpleMeterRegistry
      try {
        PekkoMetricRegistry.setRegistry(replacement)
        PekkoMetricRegistry.getRegistry should be theSameInstanceAs replacement
      } finally {
        // a null registry restores the built in fallback
        PekkoMetricRegistry.setRegistry(null)
        replacement.close()
      }
      PekkoMetricRegistry.getRegistry should be theSameInstanceAs original
    }

    // A second GaugeWrapper for the same name and tags would register a second DoubleAdder that
    // micrometer never reads, silently freezing the gauge, so the cached instance has to be returned.
    "return the same gauge for the same name and tags" in {
      val first = PekkoMetricRegistry.gauge("pekko_metric_registry_spec_cached", tags("cached"))
      val second = PekkoMetricRegistry.gauge("pekko_metric_registry_spec_cached", tags("cached"))
      second should be theSameInstanceAs first
    }

    "track gauge increments and decrements" in {
      val gauge = PekkoMetricRegistry.gauge("pekko_metric_registry_spec_gauge", tags("gauge"))
      gauge.increment()
      gauge.increment()
      gauge.decrement()
      PekkoMetricRegistry.metricsForTags(tags("gauge")) should be (
        Map("pekko_metric_registry_spec_gauge" -> 1.0))
    }

    "track counter increments" in {
      val counter = PekkoMetricRegistry.counter("pekko_metric_registry_spec_counter", tags("counter"))
      counter.increment()
      counter.increment()
      counter.count() should be (2.0)
    }

    "record timings" in {
      val timer = PekkoMetricRegistry.timer("pekko_metric_registry_spec_timer", tags("timer"))
      val observation = timer.startTimer()
      observation.close()
      timer.timer.count() should be (1L)
      timer.timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) should be >= 0.0
    }

    "only match meters whose tags match exactly" in {
      val extraTags = tags("exact") :+ Tag.of("extra", "tag")
      PekkoMetricRegistry.counter("pekko_metric_registry_spec_exact", tags("exact")).increment()
      PekkoMetricRegistry.counter("pekko_metric_registry_spec_extra", extraTags).increment()

      PekkoMetricRegistry.metricsForTags(tags("exact")).keySet should be (
        Set("pekko_metric_registry_spec_exact"))
      PekkoMetricRegistry.metricsForTags(extraTags).keySet should be (
        Set("pekko_metric_registry_spec_extra"))
    }

    "return no metrics for tags that match nothing" in {
      PekkoMetricRegistry.metricsForTags(tags("no-such-value")) should be (empty)
    }
  }
}
