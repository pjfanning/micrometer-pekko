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

class MetricNameSpec extends BaseSpec {

  "sanitizeMetricName" should {
    "leave an already valid name alone" in {
      sanitizeMetricName("pekko_actor_message_count") should be ("pekko_actor_message_count")
      sanitizeMetricName("_leading_underscore") should be ("_leading_underscore")
    }
    "replace characters that are not valid in a metric name" in {
      sanitizeMetricName("a.b-c d") should be ("a_b_c_d")
      sanitizeMetricName("a/b") should be ("a_b")
    }
    "replace a leading character that cannot start a metric name" in {
      sanitizeMetricName("1abc") should be ("_abc")
      sanitizeMetricName("-abc") should be ("_abc")
    }
    "keep digits that are not in the leading position" in {
      sanitizeMetricName("actor1") should be ("actor1")
    }
  }

  "metricFriendlyActorName" should {
    "lower case the path and turn separators into underscores" in {
      metricFriendlyActorName("ActorMetricsSpec/user/tracked-actor") should be ("actormetricsspec_user_tracked_actor")
    }
    "trim leading slashes" in {
      metricFriendlyActorName("//System/user/a") should be ("system_user_a")
    }
    "sanitize a leading character that cannot start a metric name" in {
      metricFriendlyActorName("1system/user/a") should be ("_system_user_a")
    }
  }
}
