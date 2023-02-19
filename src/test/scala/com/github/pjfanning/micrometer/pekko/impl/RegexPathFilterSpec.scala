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
package com.github.pjfanning.micrometer.pekko.impl

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class RegexPathFilterSpec extends AnyWordSpecLike with Matchers {
  "The RegexPathFilter" should {

    "match a single expression" in {
      val filter = new RegexPathFilter("/user/actor")

      filter.accept("/user/actor") shouldBe true

      filter.accept("/user/actor/something") shouldBe false
      filter.accept("/user/actor/somethingElse") shouldBe false
    }

    "match arbitrary expressions ending with wildcard" in {
      val filter = new RegexPathFilter("/user/.*")

      filter.accept("/user/actor") shouldBe true
      filter.accept("/user/otherActor") shouldBe true
      filter.accept("/user/something/actor") shouldBe true
      filter.accept("/user/something/otherActor") shouldBe true

      filter.accept("/otheruser/actor") shouldBe false
      filter.accept("/otheruser/otherActor") shouldBe false
      filter.accept("/otheruser/something/actor") shouldBe false
      filter.accept("/otheruser/something/otherActor") shouldBe false
    }

    "match numbers" in {
      val filter = new RegexPathFilter("/user/actor-\\d")

      filter.accept("/user/actor-1") shouldBe true
      filter.accept("/user/actor-2") shouldBe true
      filter.accept("/user/actor-3") shouldBe true

      filter.accept("/user/actor-one") shouldBe false
      filter.accept("/user/actor-two") shouldBe false
      filter.accept("/user/actor-tree") shouldBe false
    }
  }
}
