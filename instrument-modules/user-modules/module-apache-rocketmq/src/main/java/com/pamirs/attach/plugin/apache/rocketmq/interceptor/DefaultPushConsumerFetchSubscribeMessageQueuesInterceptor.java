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
package com.pamirs.attach.plugin.apache.rocketmq.interceptor;

import com.pamirs.attach.plugin.apache.rocketmq.common.ConsumerRegistry;
import com.pamirs.attach.plugin.apache.rocketmq.destroy.MqDestroy;
import com.pamirs.pradar.CutOffResult;
import com.pamirs.pradar.exception.PressureMeasureError;
import com.pamirs.pradar.interceptor.CutoffInterceptorAdaptor;
import com.shulie.instrument.simulator.api.annotation.Destroyable;
import com.shulie.instrument.simulator.api.listener.ext.Advice;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author xiaobin.zfb|xiaobin@shulie.io
 * @since 2020/11/30 4:20 下午
 */
@Destroyable(MqDestroy.class)
public class DefaultPushConsumerFetchSubscribeMessageQueuesInterceptor extends CutoffInterceptorAdaptor {
    protected final static Logger logger = LoggerFactory.getLogger(DefaultPushConsumerFetchSubscribeMessageQueuesInterceptor.class);

    @Override
    public CutOffResult cutoff0(Advice advice) {
        Object[] args = advice.getParameterArray();
        Object target = advice.getTarget();

        /**
         * 主要负责Consumer 注册，每一批的消息消费都会经过此方法
         * 如果是已经注册过的，则忽略
         */
        DefaultMQPushConsumer defaultMQPushConsumer = (DefaultMQPushConsumer) target;
        if (ConsumerRegistry.hasRegistered(defaultMQPushConsumer)) {
            return CutOffResult.passed();
        }

        /**
         * 如果影子消费者，也忽略
         */
        if (ConsumerRegistry.isShadowConsumer(defaultMQPushConsumer)) {
            return CutOffResult.passed();
        }

        DefaultMQPushConsumer consumer = ConsumerRegistry.getConsumer((DefaultMQPushConsumer) target);
        try {
            return CutOffResult.cutoff(consumer.fetchSubscribeMessageQueues((String) args[0]));
        } catch (Throwable e) {
            logger.error("Apache-RocketMQ: fetchSubscribeMessageQueues err, topic: {}", args[0], e);
            throw new PressureMeasureError(e);
        }
    }
}
