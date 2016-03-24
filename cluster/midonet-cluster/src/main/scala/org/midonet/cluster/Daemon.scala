/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster

import java.util.UUID

import com.google.common.util.concurrent.AbstractService
import com.google.common.util.concurrent.Service.State
import com.google.inject.{AbstractModule, Module, Singleton}
import org.slf4j.LoggerFactory

import org.midonet.cluster.ClusterNode.{Context, MinionDef}

/** Define a sub-service that runs as part of the Midonet Cluster. This
  * should expose the necessary API to let the Daemon babysit its minions.
  *
  * @param nodeContext metadata about the node where this Minion is running
  */
abstract class ClusterMinion(nodeContext: Context) extends AbstractService

/** Base configuration mixin for a Cluster Minion. */
trait MinionConfig[+D <: ClusterMinion] {
    def isEnabled: Boolean
    def minionClass: String
}

/** Some utilities for Minion bootstrapping */
object MinionConfig {

    /** Extract the Minion class from the config, if present. */
    final def minionClass[M <: ClusterMinion](m: MinionConfig[M])
    : Option[Class[M]] = {
        Option.apply(m.minionClass) map { s =>
            Class.forName(s.replaceAll("\\.\\.", ".")).asInstanceOf[Class[M]]
        }
    }

    /** Provides a Guice Module that bootstraps the injection of a Minion
      * defined in a given MinionConfig. */
    final def module[M <: ClusterMinion](m: MinionConfig[M]): Module = {
        new AbstractModule() {
            override def configure(): Unit = {
                MinionConfig.minionClass(m) map { minionClass =>
                    bind(minionClass).in(classOf[Singleton])
                }
            }
        }
    }
}