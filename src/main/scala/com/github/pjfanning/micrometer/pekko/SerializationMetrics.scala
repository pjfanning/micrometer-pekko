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

import io.micrometer.core.instrument.{DistributionSummary, ImmutableTag, Tag}

object SerializationMetrics {

  val SerializerName = "serializer"
  val Direction = "direction"

  val Serialize = "serialize"
  val Deserialize = "deserialize"

  private[pekko] val TimeMetricName = "pekko_serialization_time"
  private[pekko] val PayloadSizeMetricName = "pekko_serialization_payload_size_bytes"

  import PekkoMetricRegistry._

  def time(serializer: String, direction: String): TimerWrapper =
    timer(TimeMetricName, tagSeq(serializer, direction))

  def payloadSize(serializer: String, direction: String): DistributionSummary =
    summary(PayloadSizeMetricName, tagSeq(serializer, direction), "bytes")

  private def tagSeq(serializer: String, direction: String): Iterable[Tag] =
    Seq(new ImmutableTag(SerializerName, serializer), new ImmutableTag(Direction, direction))
}
