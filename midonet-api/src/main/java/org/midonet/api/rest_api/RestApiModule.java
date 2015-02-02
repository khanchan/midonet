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
package org.midonet.api.rest_api;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.assistedinject.FactoryModuleBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.brain.ClusterNode;
import org.midonet.brain.services.vxgw.Vtep;
import org.midonet.brain.services.vxgw.VxLanGatewayServiceBase;
import org.midonet.brain.services.vxgw.VxlanGatewayHA;
import org.midonet.brain.southbound.vtep.VtepDataClientFactory;
import org.midonet.cluster.DataClient;
import org.midonet.config.ConfigProvider;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;

/**
 * Guice module for REST API.
 */
public class RestApiModule extends AbstractModule {

    private static final Logger log = LoggerFactory.getLogger(
            RestApiModule.class);

    @Override
    protected void configure() {
        log.debug("configure: entered.");

        requireBinding(ConfigProvider.class);

        bind(WebApplicationExceptionMapper.class).asEagerSingleton();

        bindVtepDataClientFactory(); // allow mocking

        bind(ApplicationResource.class);
        install(new FactoryModuleBuilder().build(ResourceFactory.class));

        bind(RestApiService.class).asEagerSingleton();
        bind(VxLanGatewayServiceBase.class)
            .toProvider(VxGwProvider.class)
            .asEagerSingleton();

        log.debug("configure: exiting.");
    }

    protected void bindVtepDataClientFactory() {
        bind(VtepDataClientFactory.class).asEagerSingleton();
    }

    static class VxGwProvider implements Provider<VxLanGatewayServiceBase> {

        @Inject
        ClusterNode.Context nodeCtx;

        @Inject
        DataClient dataClient;

        @Inject
        ZookeeperConnectionWatcher zkConnWatcher;

        @Inject
        VtepDataClientFactory vtepDataClientFactory;

        @Override
        public VxLanGatewayServiceBase get() {
            return new VxlanGatewayHA(nodeCtx, dataClient, zkConnWatcher,
                                      vtepDataClientFactory);
        }
    }

    @Provides
    RestApiConfig provideRestApiConfig(ConfigProvider provider) {
        log.debug("provideRestApiConfig: entered.");
        return provider.getConfig(RestApiConfig.class);
    }

}
