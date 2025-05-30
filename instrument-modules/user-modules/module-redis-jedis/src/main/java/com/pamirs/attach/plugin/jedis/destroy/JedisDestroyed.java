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
package com.pamirs.attach.plugin.jedis.destroy;

import com.pamirs.attach.plugin.jedis.RedisConstants;
import com.pamirs.attach.plugin.jedis.shadowserver.JedisClusterFactory;
import com.pamirs.attach.plugin.jedis.shadowserver.JedisFactory;
import com.pamirs.attach.plugin.jedis.shadowserver.JedisSentinelFactory;
import com.shulie.instrument.simulator.api.listener.Destroyed;

/**
 * @author xiaobin.zfb|xiaobin@shulie.io
 * @since 2021/6/16 11:39 上午
 */
public class JedisDestroyed implements Destroyed {
    @Override
    public void destroy() {
        JedisFactory.release();
        JedisClusterFactory.release();
        JedisSentinelFactory.release();
        RedisConstants.jedisInstance.clear();
        RedisConstants.registerShadowNodes.clear();
    }
}
