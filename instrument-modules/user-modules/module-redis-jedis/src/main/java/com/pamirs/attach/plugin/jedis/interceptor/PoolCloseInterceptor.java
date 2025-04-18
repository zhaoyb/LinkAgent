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
package com.pamirs.attach.plugin.jedis.interceptor;

import com.pamirs.attach.plugin.jedis.RedisConstants;
import com.pamirs.attach.plugin.jedis.destroy.JedisDestroyed;
import com.pamirs.attach.plugin.jedis.shadowserver.JedisClusterFactory;
import com.pamirs.attach.plugin.jedis.shadowserver.JedisFactory;
import com.pamirs.attach.plugin.jedis.shadowserver.JedisSentinelFactory;
import com.pamirs.pradar.interceptor.AroundInterceptor;
import com.shulie.instrument.simulator.api.annotation.Destroyable;
import com.shulie.instrument.simulator.api.listener.ext.Advice;

/**
 * @author xiaobin.zfb|xiaobin@shulie.io
 * @since 2021/3/1 5:22 下午
 */
@Destroyable(JedisDestroyed.class)
public class PoolCloseInterceptor extends AroundInterceptor {
    @Override
    public void doAfter(Advice advice) throws Throwable {
        Object target = advice.getTarget();
        JedisClusterFactory.getFactory().shutdown(target);
        JedisFactory.getFactory().shutdown(target);
        JedisSentinelFactory.getFactory().shutdown(target);
        RedisConstants.jedisInstance.remove(target);
    }
}
