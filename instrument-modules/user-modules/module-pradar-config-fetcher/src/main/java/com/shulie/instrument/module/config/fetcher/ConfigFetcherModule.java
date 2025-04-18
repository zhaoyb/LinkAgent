/**
 * Copyright 2021 Shulie Technology, Co.Ltd
 * Email: shulie@shulie.io
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.shulie.instrument.module.config.fetcher;

import com.pamirs.pradar.internal.PradarInternalService;
import com.shulie.instrument.module.config.fetcher.config.ConfigManager;
import com.shulie.instrument.module.config.fetcher.config.DefaultConfigFetcher;
import com.shulie.instrument.module.config.fetcher.config.event.model.*;
import com.shulie.instrument.module.config.fetcher.config.resolver.zk.ZookeeperOptions;
import com.shulie.instrument.module.config.fetcher.interval.ISamplingRateConfigFetcher;
import com.shulie.instrument.module.config.fetcher.interval.SamplingRateConfigFetcher;
import com.shulie.instrument.simulator.api.ExtensionModule;
import com.shulie.instrument.simulator.api.ModuleInfo;
import com.shulie.instrument.simulator.api.ModuleLifecycleAdapter;
import com.shulie.instrument.simulator.api.executors.ExecutorServiceFactory;
import com.shulie.instrument.simulator.api.guard.SimulatorGuard;
import com.shulie.instrument.simulator.api.resource.SimulatorConfig;
import com.shulie.instrument.simulator.api.resource.SwitcherManager;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.MetaInfServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author xiaobin.zfb|xiaobin@shulie.io
 * @since 2020/10/1 12:45 上午
 */
@MetaInfServices(ExtensionModule.class)
@ModuleInfo(id = ConfigFetcherConstants.MODULE_NAME, version = "1.0.0", author = "xiaobin@shulie.io", description = "配置拉取模块,定时1分钟拉取一次配置", switchAuto = false)
public class ConfigFetcherModule extends ModuleLifecycleAdapter implements ExtensionModule {
    private final static Logger logger = LoggerFactory.getLogger(ConfigFetcherModule.class);
    private volatile boolean isActive;

    @Resource
    private SimulatorConfig simulatorConfig;

    @Resource
    private SwitcherManager switcherManager;

    private ScheduledFuture future;

    private ISamplingRateConfigFetcher samplingRateConfigFetcher;
    private ConfigManager configManager;

    private ZookeeperOptions buildZookeeperOptions() {
        ZookeeperOptions zookeeperOptions = new ZookeeperOptions();
        zookeeperOptions.setName("zookeeper");
        zookeeperOptions.setZkServers(simulatorConfig.getZkServers());
        zookeeperOptions.setConnectionTimeoutMillis(simulatorConfig.getZkConnectionTimeout());
        zookeeperOptions.setSessionTimeoutMillis(simulatorConfig.getZkSessionTimeout());
        return zookeeperOptions;
    }

    @Override
    public void onActive() throws Throwable {
        isActive = true;
        final String configFetchType = simulatorConfig.getProperty("pradar.config.fetch.type", "http");
        this.future = ExecutorServiceFactory.getFactory().schedule(new Runnable() {
            @Override
            public void run() {
                if (!isActive) {
                    return;
                }
                try {
                    if (StringUtils.equalsIgnoreCase(configFetchType, "zookeeper")) {
                        configManager = ConfigManager.getInstance(switcherManager, buildZookeeperOptions());
                        configManager.initAll();
                    } else {
                        int interval = simulatorConfig.getIntProperty("pradar.config.fetch.interval", 60);
                        String unit = simulatorConfig.getProperty("pradar.config.fetch.unit", "SECONDS");
                        TimeUnit timeUnit = TimeUnit.valueOf(unit);
                        configManager = ConfigManager.getInstance(switcherManager, interval, timeUnit);
                        configManager.initAll();
                    }
                    // 采样率配置拉取
                    samplingRateConfigFetcher = SimulatorGuard.getInstance().doGuard(ISamplingRateConfigFetcher.class, new SamplingRateConfigFetcher(buildZookeeperOptions(), simulatorConfig));
                    samplingRateConfigFetcher.start();
                } catch (Throwable e) {
                    logger.warn("SIMULATOR: Config Fetch module start failed. log data can't push to the server.", e);
                    future = ExecutorServiceFactory.getFactory().schedule(this, 5, TimeUnit.SECONDS);
                }
            }
        }, 0, TimeUnit.SECONDS);

        PradarInternalService.registerConfigFetcher(new DefaultConfigFetcher());
    }

    @Override
    public void onFrozen() throws Throwable {
        isActive = false;
        if (samplingRateConfigFetcher != null) {
            samplingRateConfigFetcher.stop();
        }
        if (configManager != null) {
            configManager.destroy();
        }
        if (this.future != null && !this.future.isDone() && !this.future.isCancelled()) {
            this.future.cancel(true);
        }
    }

    @Override
    public void onUnload() throws Throwable {
        PradarInternalService.registerConfigFetcher(null);
        CacheKeyAllowList.release();
        ContextPathBlockList.release();
        EsShadowServerConfig.release();
        GlobalSwitch.release();
        MaxRedisExpireTime.release();
        MockConfigChanger.release();
        MQWhiteList.release();
        RedisShadowServerConfig.release();
        RpcAllowList.release();
        SearchKeyWhiteList.release();
        ShadowDatabaseConfigs.release();
        ShadowHbaseConfigs.release();
        ShadowJobConfig.release();
        UrlWhiteList.release();
        WhiteListSwitch.release();
    }
}
