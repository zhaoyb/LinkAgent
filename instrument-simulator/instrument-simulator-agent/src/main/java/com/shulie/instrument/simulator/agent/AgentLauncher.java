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
package com.shulie.instrument.simulator.agent;

import java.io.File;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.shulie.instrument.simulator.agent.utils.ModuleUtils;

import static java.lang.String.format;

/**
 * Simulator Agent启动器
 * <ul>
 * <li>这个类的所有静态属性都必须和版本、环境无关</li>
 * <li>这个类删除、修改方法时必须考虑多版本情况下，兼容性问题!</li>
 * </ul>
 */
public class AgentLauncher {
    private final static BootLogger LOGGER = BootLogger.getLogger(AgentLauncher.class.getName());

    // Simulator默认主目录
    private static final String DEFAULT_SIMULATOR_HOME
        = new File(AgentLauncher.class.getProtectionDomain().getCodeSource().getLocation().getFile())
        .getParent();

    private static final String SIMULATOR_USER_MODULE_PATH
        = DEFAULT_SIMULATOR_HOME
        + File.separator + "modules";

    private static final String SIMULATOR_CLASSLOADER_JAR_PATH = DEFAULT_SIMULATOR_HOME
        + File.separator + "biz-classloader-jars";

    // 启动模式: agent方式加载
    private static final String LAUNCH_MODE_AGENT = "agent";

    // 启动模式: attach方式加载
    private static final String LAUNCH_MODE_ATTACH = "attach";

    // 启动默认
    private static String LAUNCH_MODE;

    // agentmain上来的结果输出到文件${HOME}/.simulator.token
    private static final String RESULT_FILE_PATH = System.getProperties().getProperty("user.home")
        + File.separator + "%s" + File.separator + "%s" + File.separator + ".simulator.token";

    // 全局持有ClassLoader用于隔离Simulator实现
    private static volatile Map<String/*NAMESPACE*/, SimulatorClassLoader> simulatorClassLoaderMap
        = new ConcurrentHashMap<String, SimulatorClassLoader>();

    private static final Pattern SIMULATOR_HOME_PATTERN = Pattern.compile("(?i)^[/\\\\]([a-z])[/\\\\]");

    private static final String CLASS_OF_CORE_CONFIGURE = "com.shulie.instrument.simulator.core.CoreConfigure";
    private static final String CLASS_OF_PROXY_CORE_SERVER = "com.shulie.instrument.simulator.core.server.ProxyCoreServer";

    // ----------------------------------------------- 以下代码用于配置解析 -----------------------------------------------

    private static final String EMPTY_STRING = "";

    private static final String KEY_SIMULATOR_HOME = "home";

    private static final String KEY_NAMESPACE = "namespace";
    private static final String DEFAULT_NAMESPACE = "default";

    private static final String KEY_SERVER_IP = "server.ip";
    private static final String DEFAULT_IP = "0.0.0.0";

    private static final String KEY_SERVER_PORT = "server.port";
    private static final String DEFAULT_PORT = "0";

    private static final String KEY_TOKEN = "token";
    private static final String DEFAULT_TOKEN = EMPTY_STRING;

    private static final String KEY_LOG_PATH = "logPath";
    private static final String DEFAULT_LOG_PATH = EMPTY_STRING;

    private static final String KEY_LOG_LEVEL = "logLevel";
    private static final String DEFAULT_LOG_LEVEL = "info";

    private static final String KEY_ZK_SERVERS = "zkServers";
    private static final String DEFAULT_ZK_SERVERS = "localhost:2181";

    private static final String KEY_REGISTER_PATH = "registerPath";
    private static final String DEFAULT_REGISTER_PATH = "/config/log/pradar/client";

    private static final String KEY_ZK_CONNECTION_TIMEOUT = "zkConnectionTimeout";
    private static final String DEFAULT_ZK_CONNECTION_TIMEOUT = "30000";

    private static final String KEY_ZK_SESSION_TIMEOUT = "zkSessionTimeout";
    private static final String DEFAULT_ZK_SESSION_TIMEOUT = "60000";

    private static final String KEY_AGENT_VERSION = "agentVersion";
    private static final String DEFAULT_AGENT_VERSION = "1.0.0.1";

    private static final String KEY_USER_APP_KEY = "userAppKey";
    private static final String DEFAULT_USER_APP_KEY = "";

    private static final String KEY_USER_ID = "userId";
    private static final String DEFAULT_USER_ID = "";

    private static final String KEY_TRO_WEB_URL = "troWebUrl";
    private static final String DEFAULT_TRO_WEB_URL = "";

    private static final String KEY_APP_NAME = "app.name";
    private static final String DEFAULT_APP_NAME = "";

    private static final String KEY_AGENT_ID = "agentId";
    private static final String DEFAULT_AGENT_ID = "";

    private static final String KEY_MODULE_REPOSITORY_MODE = "module.repository.mode";
    private static final String DEFAULT_MODULE_REPOSITORY_MODE = "local";

    private static final String KEY_MODULE_REMOTE_REPOSITORY_ADDR = "module.remote.repository.addr";
    private static final String DEFAULT_MODULE_REPOSITORY_ADDR = "http://127.0.0.1:9888";

    private static final String KEY_PROPERTIES_FILE_PATH = "prop";

    private static String getSimulatorConfigPath(String simulatorHome) {
        return simulatorHome + File.separatorChar + "config";
    }

    private static String getSimulatorModulePath(String simulatorHome) {
        return simulatorHome + File.separatorChar + "system";
    }

    private static String getSimulatorCoreJarPath(String simulatorHome) {
        return simulatorHome + File.separatorChar + "lib" + File.separator + "instrument-simulator-core.jar";
    }

    private static List<File> getSimulatorBootstrapJars(String simulatorHome) {
        File file = new File(simulatorHome, "bootstrap");
        return Arrays.asList(file.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".jar");
            }
        }));
    }

    private static String getSimulatorPropertiesPath(String simulatorHome) {
        return getSimulatorConfigPath(simulatorHome) + File.separator + "simulator.properties";
    }

    private static String getSimulatorProviderPath(String simulatorHome) {
        return simulatorHome + File.separatorChar + "provider";
    }

    private static void addBootResource(ClassLoader classLoader) {
        if (ModuleUtils.isModuleSupported()) {
            return;
        }
        try {
            Class moduleBootLoaderOfClass = classLoader.loadClass(
                "com.shulie.instrument.simulator.agent.utils.BootResourceLoader");
            Method loadModuleSupportOfMethod = moduleBootLoaderOfClass.getDeclaredMethod("addResource",
                java.util.List.class);
            loadModuleSupportOfMethod.invoke(null, Collections.emptyList());
        } catch (Throwable e) {
            LOGGER.error("SIMULATOR: add resource to boot class path err...", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * start with start opts
     *
     * @param featureString start params
     *                      [namespace,prop]
     * @param inst          inst
     */
    public static void premain(String featureString, Instrumentation inst) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("SIMULATOR: agent starting with agent mode. args=" + (featureString == null ? "" : featureString));
        }
        LAUNCH_MODE = LAUNCH_MODE_AGENT;
        try {
            final Map<String, String> featureMap = toFeatureMap(featureString);
            String appName = featureMap.get(KEY_APP_NAME);
            writeAttachResult(
                appName,
                getNamespace(featureMap),
                getToken(featureMap),
                install(featureMap, inst)
            );
        } catch (Throwable e) {
            System.out.println("========" + e.getMessage());
            e.printStackTrace();
            LOGGER.error("SIMULATOR: premain execute error!", e);
        }
    }

    /**
     * 动态加载 启动入口
     * attach agent
     *
     * @param featureString start params
     *                      [namespace,token,ip,port,prop]
     * @param inst          inst
     */
    public static void agentmain(String featureString, Instrumentation inst) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("SIMULATOR: agent starting with attach mode. args=" + (featureString == null ? "" : featureString));
        }
        try {
            LAUNCH_MODE = LAUNCH_MODE_ATTACH;
            final Map<String, String> featureMap = toFeatureMap(featureString);
            String appName = featureMap.get(KEY_APP_NAME);
            writeAttachResult(
                appName,
                getNamespace(featureMap),
                getToken(featureMap),
                install(featureMap, inst)
            );
        } catch (Throwable e) {
            e.printStackTrace();
            LOGGER.error("SIMULATOR: agentmain execute error!", e);
        }
    }

    /**
     * write start result file
     * <p>
     * NAMESPACE;TOKEN;IP;PORT
     * </p>
     *
     * @param appName
     * @param namespace   namespace
     * @param token       token
     * @param installInfo listen server[IP:PORT]
     */
    private static synchronized void writeAttachResult(String appName, final String namespace,
        final String token,
        final InstallInfo installInfo) {
        String path = String.format(RESULT_FILE_PATH, namespace, appName);
        final File file = new File(path);
        if (file.exists()
            && (!file.isFile()
            || !file.canWrite())) {

            throw new RuntimeException("write to result file : " + file + " failed.");
        } else {
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            FileWriter fw = null;
            try {
                fw = new FileWriter(file, false);
                fw.append(
                    format("%s;%s;%s;%s;%s\n",
                        namespace,
                        token,
                        installInfo.inetSocketAddress.getHostName(),
                        installInfo.inetSocketAddress.getPort(),
                        installInfo.installVersion
                    )
                );
                fw.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                if (null != fw) {
                    try {
                        fw.close();
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }
        }
        file.deleteOnExit();
    }

    private static synchronized ClassLoader loadOrDefineClassLoader(final String namespace,
        final String coreJar) throws Throwable {

        final SimulatorClassLoader classLoader;
        // 如果已经被启动则返回之前启动的ClassLoader
        if (simulatorClassLoaderMap.containsKey(namespace)
            && null != simulatorClassLoaderMap.get(namespace)) {
            classLoader = simulatorClassLoaderMap.get(namespace);
        }

        // 如果未启动则重新加载
        else {
            classLoader = new SimulatorClassLoader(namespace, coreJar);
            simulatorClassLoaderMap.put(namespace, classLoader);
        }

        return classLoader;
    }

    /**
     * delete simulator with namespace
     *
     * @param namespace namespace
     * @throws Throwable throws Throwable when uninstall failed.
     */
    @SuppressWarnings("unused")
    public static synchronized void uninstall(final String namespace) throws Throwable {
        final SimulatorClassLoader simulatorClassLoader = simulatorClassLoaderMap.get(namespace);
        if (null == simulatorClassLoader) {
            return;
        }

        // 关闭服务器
        final Class<?> classOfProxyServer = simulatorClassLoader.loadClass(CLASS_OF_PROXY_CORE_SERVER);
        classOfProxyServer.getMethod("destroy")
            .invoke(classOfProxyServer.getMethod("getInstance").invoke(null));

        // 关闭SimulatorClassLoader
        simulatorClassLoader.closeIfPossible();
        simulatorClassLoaderMap.remove(namespace);

        /**
         * 删除结果文件
         */
        String path = String.format(RESULT_FILE_PATH, namespace);
        final File file = new File(path);
        if (file.exists()) {
            file.delete();
        }
    }

    /**
     * install simulator with current VM
     *
     * @param featureMap start params
     * @param inst       inst
     * @return serverIP :PORT
     */
    private static synchronized InstallInfo install(final Map<String, String> featureMap,
        final Instrumentation inst) {

        final String namespace = getNamespace(featureMap);
        final String propertiesFilePath = getPropertiesFilePath(featureMap);
        final String coreFeatureString = toFeatureString(featureMap);

        try {
            final String home = getSimulatorHome(featureMap);
            // 将bootstrap下所有的jar注入到BootstrapClassLoader
            List<File> bootstrapFiles = getSimulatorBootstrapJars(home);
            for (File file : bootstrapFiles) {
                if (file.isHidden()) {
                    LOGGER.warn(
                        "prepare to append bootstrap file " + file.getAbsolutePath() + " but found a hidden file. skip it.");
                    continue;
                }
                if (!file.isFile()) {
                    LOGGER.warn("prepare to append bootstrap file " + file.getAbsolutePath()
                        + " but found a directory file. skip it.");
                    continue;
                }
                LOGGER.info("append bootstrap file=" + file.getAbsolutePath());
                inst.appendToBootstrapClassLoaderSearch(new JarFile(file));
            }

            // 构造自定义的类加载器，尽量减少Simulator对现有工程的侵蚀
            final ClassLoader simulatorClassLoader = loadOrDefineClassLoader(
                namespace,
                getSimulatorCoreJarPath(home)
                // SIMULATOR_CORE_JAR_PATH
            );

            /**
             * 如果jdk9以下版本
             */
            if (!ModuleUtils.isModuleSupported()) {
                /**
                 * 将 bootstrap 资源添加到 bootstrap classpath 下面
                 * 因为有一些实现加载 bootstrap 下的类时使用 getResource 方式加载
                 * appendToBootstrapClassLoaderSearch方法将不能使用 getResource 能搜索到
                 * 资源，所以需要再处理一下
                 */
                addBootResource(AgentLauncher.class.getClassLoader());
            }

            ClassLoader currentClassLoader = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(simulatorClassLoader);
                // CoreConfigure类定义
                final Class<?> classOfConfigure = simulatorClassLoader.loadClass(CLASS_OF_CORE_CONFIGURE);

                // 反序列化成CoreConfigure类实例
                final Object objectOfCoreConfigure = classOfConfigure.getMethod("toConfigure", Class.class, String.class,
                        String.class, Instrumentation.class)
                    .invoke(null, AgentLauncher.class, coreFeatureString, propertiesFilePath, inst);

                // CoreServer类定义
                final Class<?> classOfProxyServer = simulatorClassLoader.loadClass(CLASS_OF_PROXY_CORE_SERVER);

                // 获取CoreServer单例
                final Object objectOfProxyServer = classOfProxyServer
                    .getMethod("getInstance")
                    .invoke(null);

                // CoreServer.isBind()
                final boolean isBind = (Boolean)classOfProxyServer.getMethod("isBind").invoke(objectOfProxyServer);

                // 如果未绑定,则需要绑定一个地址
                if (!isBind) {
                    try {
                        classOfProxyServer
                            .getMethod("bind", classOfConfigure, Instrumentation.class)
                            .invoke(objectOfProxyServer, objectOfCoreConfigure, inst);
                    } catch (Throwable t) {
                        LOGGER.error("AGENT: agent bind error {}", t);
                        classOfProxyServer.getMethod("destroy").invoke(objectOfProxyServer);
                        throw t;
                    }

                } else {
                    LOGGER.warn("AGENT: agent start already. skip it. " + namespace);
                }

                // 返回服务器绑定的地址
                InetSocketAddress inetSocketAddress = (InetSocketAddress)classOfProxyServer
                    .getMethod("getLocal")
                    .invoke(objectOfProxyServer);
                String version = classOfConfigure.getMethod("getSimulatorVersion").invoke(objectOfCoreConfigure).toString();
                return new InstallInfo(inetSocketAddress, version);
            } finally {
                Thread.currentThread().setContextClassLoader(currentClassLoader);
            }

        } catch (Throwable cause) {
            //如果是agent模式则不阻塞应用正常启动,但是会将错误打印出来,
            //如果是attach模式则直接将异常抛至上层调用方
            try {
                uninstall(namespace);
            } catch (Throwable ignore) {
            }
            throw new RuntimeException("simulator attach failed.", cause);
        }

    }

    private static boolean isNotBlank(final String string) {
        return null != string
            && string.length() > 0
            && !string.matches("^\\s*$");
    }

    private static boolean isBlank(final String string) {
        return !isNotBlank(string);
    }

    private static String getDefault(final String string, final String defaultString) {
        return isNotBlank(string)
            ? string
            : defaultString;
    }

    private static Map<String, String> toFeatureMap(final String featureString) {
        final Map<String, String> featureMap = new LinkedHashMap<String, String>();

        // 不对空字符串进行解析
        if (isBlank(featureString)) {
            return featureMap;
        }

        // KV对片段数组
        final String[] kvPairSegmentArray = featureString.split(";");
        if (kvPairSegmentArray.length <= 0) {
            return featureMap;
        }

        for (String kvPairSegmentString : kvPairSegmentArray) {
            if (isBlank(kvPairSegmentString)) {
                continue;
            }
            final String[] kvSegmentArray = kvPairSegmentString.split("=");
            if (kvSegmentArray.length != 2
                || isBlank(kvSegmentArray[0])
                || isBlank(kvSegmentArray[1])) {
                continue;
            }
            featureMap.put(decode(kvSegmentArray[0]), decode(kvSegmentArray[1]));
        }

        return featureMap;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    private static String getDefault(final Map<String, String> map, final String key, final String defaultValue) {
        return null != map
            && !map.isEmpty()
            ? getDefault(map.get(key), defaultValue)
            : defaultValue;
    }

    private static String OS = System.getProperty("os.name").toLowerCase();

    private static boolean isWindows() {
        return OS.contains("win");
    }

    // 获取主目录
    private static String getSimulatorHome(final Map<String, String> featureMap) {
        String home = getDefault(featureMap, KEY_SIMULATOR_HOME, DEFAULT_SIMULATOR_HOME);
        if (isWindows()) {
            Matcher m = SIMULATOR_HOME_PATTERN.matcher(home);
            if (m.find()) {
                home = m.replaceFirst("$1:/");
            }
        }
        return home;
    }

    // 获取命名空间
    private static String getNamespace(final Map<String, String> featureMap) {
        return getDefault(featureMap, KEY_NAMESPACE, DEFAULT_NAMESPACE);
    }

    // 获取TOKEN
    private static String getToken(final Map<String, String> featureMap) {
        return getDefault(featureMap, KEY_TOKEN, DEFAULT_TOKEN);
    }

    //获取 log path
    private static String getLogPath(final Map<String, String> featureMap) {
        return getDefault(featureMap, KEY_LOG_PATH, DEFAULT_LOG_PATH);
    }

    //获取 log level
    private static String getLogLevel(final Map<String, String> featureMap) {
        return getDefault(featureMap, KEY_LOG_LEVEL, DEFAULT_LOG_LEVEL);
    }

    private static String getZkServers(final Map<String, String> featureMap) {
        return getDefault(featureMap, KEY_ZK_SERVERS, DEFAULT_ZK_SERVERS);
    }

    private static String getRegisterPath(final Map<String, String> featureMap) {
        return getDefault(featureMap, KEY_REGISTER_PATH, DEFAULT_REGISTER_PATH);
    }

    private static String getZkConnectionTimeout(final Map<String, String> featureMap) {
        return getDefault(featureMap, KEY_ZK_CONNECTION_TIMEOUT, DEFAULT_ZK_CONNECTION_TIMEOUT);
    }

    private static String getZkSessionTimeout(final Map<String, String> featureMap) {
        return getDefault(featureMap, KEY_ZK_SESSION_TIMEOUT, DEFAULT_ZK_SESSION_TIMEOUT);
    }

    private static String getAgentVersion(final Map<String, String> featureMap) {
        return getDefault(featureMap, KEY_AGENT_VERSION, DEFAULT_AGENT_VERSION);
    }

    private static String getUserAppKey(Map<String, String> featureMap) {
        return getDefault(featureMap, KEY_USER_APP_KEY, DEFAULT_USER_APP_KEY);
    }

    private static String getUserId(Map<String, String> featureMap) {
        return getDefault(featureMap, KEY_USER_ID, DEFAULT_USER_ID);
    }

    private static String getTroWebUrl(Map<String, String> featureMap) {
        return getDefault(featureMap, KEY_TRO_WEB_URL, DEFAULT_TRO_WEB_URL);
    }

    private static String getAppName(final Map<String, String> featureMap) {
        return getDefault(featureMap, KEY_APP_NAME, DEFAULT_APP_NAME);
    }

    private static String getAgentId(final Map<String, String> featureMap) {
        return getDefault(featureMap, KEY_AGENT_ID, DEFAULT_AGENT_ID);
    }

    private static String getRepositoryMode(final Map<String, String> featureMap) {
        return getDefault(featureMap, KEY_MODULE_REPOSITORY_MODE, DEFAULT_MODULE_REPOSITORY_MODE);
    }

    private static String getRemoteRepositoryAddr(final Map<String, String> featureMap) {
        return getDefault(featureMap, KEY_MODULE_REMOTE_REPOSITORY_ADDR, DEFAULT_MODULE_REPOSITORY_ADDR);
    }

    // 获取容器配置文件路径
    private static String getPropertiesFilePath(final Map<String, String> featureMap) {
        return getDefault(
            featureMap,
            KEY_PROPERTIES_FILE_PATH,
            getSimulatorPropertiesPath(getSimulatorHome(featureMap))
            // SIMULATOR_PROPERTIES_PATH
        );
    }

    // 如果featureMap中有对应的key值，则将featureMap中的[K,V]对合并到builder中
    private static void appendFromFeatureMap(final StringBuilder builder,
        final Map<String, String> featureMap,
        final String key,
        final String defaultValue) {
        if (featureMap.containsKey(key)) {
            builder.append(format("%s=%s;", key, getDefault(featureMap, key, defaultValue)));
        }
    }

    // 将featureMap中的[K,V]对转换为featureString
    private static String toFeatureString(final Map<String, String> featureMap) {
        final String simulatorHome = getSimulatorHome(featureMap);
        final StringBuilder builder = new StringBuilder(
            format(
                ";app_name=%s;agentId=%s;config=%s;system_module=%s;mode=%s;simulator_home=%s;user_module=%s;"
                    + "classloader_jars=%s;provider=%s;namespace=%s;module_repository_mode=%s;module_repository_addr=%s;"
                    + "log_path=%s;log_level=%s;zk_servers=%s;register_path=%s;zk_connection_timeout=%s;"
                    + "zk_session_timeout=%s;agent_version=%s;user.app.key=%s;pradar.user.id=%s;tro.web.url=%s",
                getAppName(featureMap),
                getAgentId(featureMap),
                getSimulatorConfigPath(simulatorHome),
                // SIMULATOR_CONFIG_PATH,
                getSimulatorModulePath(simulatorHome),
                // SIMULATOR_MODULE_PATH,
                LAUNCH_MODE,
                simulatorHome,
                // SIMULATOR_HOME,
                SIMULATOR_USER_MODULE_PATH,
                SIMULATOR_CLASSLOADER_JAR_PATH,
                getSimulatorProviderPath(simulatorHome),
                // SIMULATOR_PROVIDER_LIB_PATH,
                getNamespace(featureMap),
                // REPOSITORY MODE (local/remote)
                getRepositoryMode(featureMap),
                // REPOSITORY REMOTE ADDR
                getRemoteRepositoryAddr(featureMap),
                // LOG PATH
                getLogPath(featureMap),
                //LOG LEVEL
                getLogLevel(featureMap),
                // ZK SERVERS
                getZkServers(featureMap),
                // REGISTER PATH
                getRegisterPath(featureMap),
                // ZK CONNECTION TIMEOUT
                getZkConnectionTimeout(featureMap),
                // ZK SESSION TIMEOUT
                getZkSessionTimeout(featureMap),
                // AGENT VERSION
                getAgentVersion(featureMap),
                //User APP KEY
                getUserAppKey(featureMap),
                //USE ID
                getUserId(featureMap),
                //TRO WEB
                getTroWebUrl(featureMap)
            )
        );

        // 合并IP(如有)
        appendFromFeatureMap(builder, featureMap, KEY_SERVER_IP, DEFAULT_IP);

        // 合并PORT(如有)
        appendFromFeatureMap(builder, featureMap, KEY_SERVER_PORT, DEFAULT_PORT);

        return builder.toString();
    }

    private static class InstallInfo {

        private final InetSocketAddress inetSocketAddress;

        private final String installVersion;

        private InstallInfo(InetSocketAddress inetSocketAddress, String installVersion) {
            this.inetSocketAddress = inetSocketAddress;
            this.installVersion = installVersion;
        }
    }

}
