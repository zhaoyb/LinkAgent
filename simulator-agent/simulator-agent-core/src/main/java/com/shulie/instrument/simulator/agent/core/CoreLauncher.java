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
package com.shulie.instrument.simulator.agent.core;

import com.shulie.instrument.simulator.agent.api.ExternalAPI;
import com.shulie.instrument.simulator.agent.api.model.CommandPacket;
import com.shulie.instrument.simulator.agent.core.classloader.ProviderClassLoader;
import com.shulie.instrument.simulator.agent.core.config.AgentConfigImpl;
import com.shulie.instrument.simulator.agent.core.config.CoreConfig;
import com.shulie.instrument.simulator.agent.core.config.ExternalAPIImpl;
import com.shulie.instrument.simulator.agent.core.exception.AgentDownloadException;
import com.shulie.instrument.simulator.agent.core.register.Register;
import com.shulie.instrument.simulator.agent.core.register.RegisterFactory;
import com.shulie.instrument.simulator.agent.core.register.RegisterOptions;
import com.shulie.instrument.simulator.agent.core.uploader.ApplicationUploader;
import com.shulie.instrument.simulator.agent.core.uploader.HttpApplicationUploader;
import com.shulie.instrument.simulator.agent.core.util.JarUtils;
import com.shulie.instrument.simulator.agent.core.util.LogbackUtils;
import com.shulie.instrument.simulator.agent.spi.AgentScheduler;
import com.shulie.instrument.simulator.agent.spi.CommandExecutor;
import com.shulie.instrument.simulator.agent.spi.command.Command;
import com.shulie.instrument.simulator.agent.spi.command.impl.*;
import com.shulie.instrument.simulator.agent.spi.config.AgentConfig;
import com.shulie.instrument.simulator.agent.spi.config.SchedulerArgs;
import com.shulie.instrument.simulator.agent.spi.impl.HttpAgentScheduler;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.reflect.FieldUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import java.io.File;
import java.io.FileFilter;
import java.lang.annotation.Annotation;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * @author xiaobin.zfb|xiaobin@shulie.io
 * @since 2020/11/16 8:50 下午
 */
public class CoreLauncher {
    private final Logger LOGGER = LoggerFactory.getLogger(getClass());
    private AgentLauncher launcher;
    private AgentScheduler agentScheduler;
    private final CoreConfig coreConfig;
    private final AgentConfig agentConfig;
    private final ExternalAPI externalAPI;
    private final Instrumentation instrumentation;
    private final ClassLoader classLoader;
    private final String tagName;

    /**
     * agent 默认延迟时间设置5分钟
     */
    private int delay = 300;
    private TimeUnit unit = TimeUnit.SECONDS;

    private final ScheduledExecutorService startService;

    public CoreLauncher(final String agentHome) {
        this(agentHome, -1L, null, null, null, null);
    }

    /**
     * 构造函数， 由com.shulie.instrument.simulator.agent.instrument.InstrumentLauncher 通过反射调用
     *
     * @param agentHome agent的home路径
     * @param attachId
     * @param attachName
     * @param tagName
     * @param instrumentation
     * @param classLoader
     */
    public CoreLauncher(String agentHome, long attachId, String attachName, String tagName, Instrumentation instrumentation, ClassLoader classLoader) {
        this.coreConfig = new CoreConfig(agentHome);
        this.instrumentation = instrumentation;
        this.classLoader = classLoader;
        this.tagName = tagName;
        // 进程ID
        this.coreConfig.setAttachId(attachId);
        // 进程名
        this.coreConfig.setAttachName(attachName);
        this.agentConfig = new AgentConfigImpl(this.coreConfig);
        System.setProperty("SIMULATOR_LOG_PATH", this.agentConfig.getLogPath());
        System.setProperty("SIMULATOR_LOG_LEVEL", this.agentConfig.getLogLevel());
        LogbackUtils.init(this.agentConfig.getLogConfigFile());
        this.launcher = new AgentLauncher(this.agentConfig, instrumentation, classLoader);
        this.externalAPI = new ExternalAPIImpl(this.agentConfig);
        initAgentLoader();
        this.startService = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread t = new Thread(runnable, "Agent-Start-Instrument-Service");
                t.setDaemon(true);
                return t;
            }
        });
    }


    /**
     * 初始化 Agent Loader
     */
    private void initAgentLoader() {
        List<File> files = JarUtils.readFiles(new File(this.coreConfig.getProviderFilePath()), new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isFile() && file.exists() && file.canRead();
            }
        });
        if (files != null && !files.isEmpty()) {
            URL[] urls = JarUtils.toURLs(files);
            ProviderClassLoader classLoader = new ProviderClassLoader(urls, CoreLauncher.class.getClassLoader());
            ServiceLoader<AgentScheduler> serviceLoader = ServiceLoader.load(AgentScheduler.class, classLoader);
            for (AgentScheduler agentScheduler : serviceLoader) {
                this.agentScheduler = agentScheduler;
                break;
            }
        }

        if (this.agentScheduler == null) {
            this.agentScheduler = new HttpAgentScheduler();
        }

        try {
            inject(this.agentScheduler);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 注入资源
     *
     * @param object 目标对象
     * @throws IllegalAccessException
     */
    private void inject(Object object) throws IllegalAccessException {
        // 获取打有@Resource的字段
        final Field[] resourceFieldArray = getFieldsWithAnnotation(object.getClass(), Resource.class);
        if (ArrayUtils.isEmpty(resourceFieldArray)) {
            return;
        }
        for (final Field resourceField : resourceFieldArray) {
            // 获取字段类型
            final Class<?> fieldType = resourceField.getType();
            // ConfigProvider 注入    如果字段是ExternalAPI类型
            if (ExternalAPI.class.isAssignableFrom(fieldType)) {
                // 注入
                FieldUtils.writeField(resourceField, object, this.externalAPI, true);
            }
        }
    }

    private static Field[] getFieldsWithAnnotation(final Class<?> cls, final Class<? extends Annotation> annotationCls) {
        final List<Field> annotatedFieldsList = getFieldsListWithAnnotation(cls, annotationCls);
        return annotatedFieldsList.toArray(new Field[0]);
    }

    private static List<Field> getFieldsListWithAnnotation(final Class<?> cls, final Class<? extends Annotation> annotationCls) {
        final List<Field> allFields = getAllFieldsList(cls);
        final List<Field> annotatedFields = new ArrayList<Field>();
        for (final Field field : allFields) {
            if (field.getAnnotation(annotationCls) != null) {
                annotatedFields.add(field);
            }
        }
        return annotatedFields;
    }

    private static List<Field> getAllFieldsList(final Class<?> cls) {
        final List<Field> allFields = new ArrayList<Field>();
        Class<?> currentClass = cls;
        while (currentClass != null) {
            final Field[] declaredFields = currentClass.getDeclaredFields();
            Collections.addAll(allFields, declaredFields);
            currentClass = currentClass.getSuperclass();
        }
        return allFields;
    }

    /**
     * 启动   由com.shulie.instrument.simulator.agent.instrument.InstrumentLauncher 反射调用
     *
     * @throws Throwable
     */
    public void start() throws Throwable {
        // 启动， 如果配置有延迟时间，则延迟运行
        this.startService.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    //delay了之后再启动，防止一些zk等的类加载问题
                    RegisterFactory.init(agentConfig);

                    ApplicationUploader applicationUploader = new HttpApplicationUploader(agentConfig);
                    // 检查并上报应用
                    applicationUploader.checkAndGenerateApp();

                    // 获取注册实例
                    Register register = RegisterFactory.getRegister(agentConfig.getProperty("register.name", "zookeeper"));
                    // 注册配置
                    RegisterOptions registerOptions = buildRegisterOptions(agentConfig);
                    // 注册初始化
                    register.init(registerOptions);
                    // 注册启动
                    register.start();

                    agentScheduler.setAgentConfig(agentConfig);
                    agentScheduler.setCommandExecutor(new CommandExecutor() {
                        @Override
                        public void execute(Command command) throws Throwable {
                            if (command instanceof StartCommand) {
                                launcher.startup(((StartCommand) command));
                            } else if (command instanceof StopCommand) {
                                launcher.shutdown((StopCommand) command);
                            } else if (command instanceof LoadModuleCommand) {
                                launcher.loadModule(((LoadModuleCommand) command));
                            } else if (command instanceof UnloadModuleCommand) {
                                launcher.unloadModule(((UnloadModuleCommand) command));
                            } else if (command instanceof ReloadModuleCommand) {
                                launcher.reloadModule(((ReloadModuleCommand) command));
                            }
                        }

                        @Override
                        public boolean isInstalled() {
                            return launcher.isInstalled();
                        }

                        @Override
                        public boolean isModuleInstalled(String moduleId) {
                            return launcher.isModuleInstalled(moduleId);
                        }
                    });

                    SchedulerArgs schedulerArgs = new SchedulerArgs();
                    agentScheduler.init(schedulerArgs);
                    agentScheduler.start();


                } catch (AgentDownloadException e) {
                    LOGGER.error("SIMULATOR: download agent occur exception. ", e);
                    startService.schedule(this, 10, TimeUnit.SECONDS);
                } catch (Throwable t) {
                    LOGGER.error("SIMULATOR: agent start occur exception. ", t);
                    startService.schedule(this, 10, TimeUnit.SECONDS);
                }
            }
        }, delay, unit);




        if (LOGGER.isInfoEnabled()) {
            if (tagName != null) {
                LOGGER.info("SIMULATOR: current load tag file name {}.", tagName);
            } else {
                LOGGER.warn("SIMULATOR: current can't found tag name. may be agent file is incomplete.");
            }
            LOGGER.info("SIMULATOR: agent will start {} {} later... please wait for a while moment.", delay, unit.toString());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    launcher.shutdown(new StopCommand<CommandPacket>(null));
                } catch (Throwable e) {
                    LOGGER.error("SIMULATOR: execute shutdown hook error.", e);
                }
            }
        }));
    }

    private RegisterOptions buildRegisterOptions(AgentConfig agentConfig) {
        RegisterOptions registerOptions = new RegisterOptions();
        registerOptions.setAppName(agentConfig.getAppName());
        registerOptions.setRegisterBasePath(agentConfig.getProperty("agent.status.zk.path", "/config/log/pradar/status"));
        registerOptions.setRegisterName(agentConfig.getProperty("simulator.hearbeat.register.name", "zookeeper"));
        registerOptions.setZkServers(agentConfig.getProperty("simulator.zk.servers", "localhost:2181"));
        registerOptions.setConnectionTimeoutMillis(agentConfig.getIntProperty("simulator.zk.connection.timeout.ms", 30000));
        registerOptions.setSessionTimeoutMillis(agentConfig.getIntProperty("simulator.zk.session.timeout.ms", 60000));
        return registerOptions;
    }

    public void setDelay(int delay) {
        this.delay = delay;
    }

    public void setUnit(TimeUnit unit) {
        if (this.unit != null) {
            this.unit = unit;
        }
    }
}
