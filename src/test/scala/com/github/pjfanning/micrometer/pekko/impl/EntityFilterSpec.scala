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

import com.github.pjfanning.micrometer.pekko.BaseSpec

class EntityFilterSpec extends BaseSpec {

  "EntityFilter" should {
    "accept a name that matches an include and no exclude" in {
      val filter = EntityFilter(List(GlobPathFilter("**")), List(GlobPathFilter("*/system/**")))
      filter.accept("system1/user/actor1") should be (true)
    }

    "reject a name that matches an exclude even when it also matches an include" in {
      val filter = EntityFilter(List(GlobPathFilter("**")), List(GlobPathFilter("*/system/**")))
      filter.accept("system1/system/actor1") should be (false)
    }

    "reject a name that matches no include" in {
      val filter = EntityFilter(List(GlobPathFilter("*/user/tracked-**")), Nil)
      filter.accept("system1/user/other") should be (false)
    }

    // reference.conf ships categories with `includes = []`, which has to mean "track nothing"
    "reject everything when there are no includes" in {
      EntityFilter(Nil, Nil).accept("system1/user/actor1") should be (false)
    }

    "accept a name matching any one of several includes" in {
      val filter = EntityFilter(
        List(GlobPathFilter("*/user/tracked-**"), GlobPathFilter("*/user/measuring-**")), Nil)
      filter.accept("system1/user/tracked-actor") should be (true)
      filter.accept("system1/user/measuring-actor") should be (true)
      filter.accept("system1/user/other-actor") should be (false)
    }

    "work with regex filters" in {
      val filter = EntityFilter(List(RegexPathFilter(".*/user/.*")), List(RegexPathFilter(".*-excluded")))
      filter.accept("system1/user/actor1") should be (true)
      filter.accept("system1/user/actor1-excluded") should be (false)
    }
  }
}
