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
package com.pamirs.attach.plugin.rabbitmq;

import com.pamirs.attach.plugin.rabbitmq.interceptor.*;
import com.pamirs.pradar.interceptor.Interceptors;
import com.shulie.instrument.simulator.api.ExtensionModule;
import com.shulie.instrument.simulator.api.ModuleInfo;
import com.shulie.instrument.simulator.api.ModuleLifecycleAdapter;
import com.shulie.instrument.simulator.api.instrument.EnhanceCallback;
import com.shulie.instrument.simulator.api.instrument.InstrumentClass;
import com.shulie.instrument.simulator.api.instrument.InstrumentMethod;
import com.shulie.instrument.simulator.api.listener.Listeners;
import com.shulie.instrument.simulator.api.scope.ExecutionPolicy;
import org.kohsuke.MetaInfServices;

/**
 * @author guohz
 */
@MetaInfServices(ExtensionModule.class)
@ModuleInfo(id = RabbitmqConstants.MODULE_NAME, version = "1.0.0", author = "xiaobin@shulie.io", description = "rabbitmq消息中间件")
public class RabbitMQPlugin extends ModuleLifecycleAdapter implements ExtensionModule {


    @Override
    public void onActive() throws Throwable {
        addHookRegisterInterceptor();
    }

    private void addHookRegisterInterceptor() {

        if (simulatorConfig.getBooleanProperty("auto.create.queue.rabbitmq", false)) {
            this.enhanceTemplate.enhance(this,
                    "org.springframework.amqp.rabbit.core.RabbitAdmin", new EnhanceCallback() {
                        @Override
                        public void doEnhance(InstrumentClass target) {
                            InstrumentMethod handle = target.getDeclaredMethod("declareQueue", "org.springframework.amqp.core.Queue");
                            handle.addInterceptor(Listeners.of(SpringRabbitRabbitAdminDeclareQueueInterceptor.class));
                        }
                    });
        }

        //增强channel
        this.enhanceTemplate.enhance(this,
                "com.rabbitmq.client.impl.ChannelN", new EnhanceCallback() {
                    @Override
                    public void doEnhance(InstrumentClass target) {
                        final InstrumentMethod basicPublishMethod = target.getDeclaredMethod("basicPublish", "java.lang.String", "java.lang.String", "boolean", "boolean", "com.rabbitmq.client.AMQP$BasicProperties", "byte[]");
                        basicPublishMethod
                                .addInterceptor(Listeners.of(ChannelNBasicPublishInterceptor.class));

                        final InstrumentMethod basicGetMethod = target.getDeclaredMethod("basicGet", "java.lang.String", "boolean");
                        basicGetMethod
                                .addInterceptor(Listeners.of(ChannelNBasicGetInterceptor.class));

                        final InstrumentMethod basicConsumeMethod = target.getDeclaredMethod("basicConsume", "java.lang.String", "boolean", "java.lang.String", "boolean", "boolean", "java.util.Map", "com.rabbitmq.client.Consumer");
                        basicConsumeMethod
                                .addInterceptor(Listeners.of(ChannelNBasicConsumeInterceptor.class));

                        final InstrumentMethod processDeliveryMethod = target.getDeclaredMethod("processDelivery", "com.rabbitmq.client.Command", "com.rabbitmq.client.impl.AMQImpl$Basic$Deliver");
                        processDeliveryMethod
                                .addInterceptor(Listeners.of(ChannelNProcessDeliveryInterceptor.class, new Object[]{simulatorConfig}));

                        final InstrumentMethod basicCancelMethod = target.getDeclaredMethod("basicCancel", "java.lang.String");
                        basicCancelMethod
                                .addInterceptor(Listeners.of(ChannelNBasicCancelInterceptor.class));

                        addAckInterceptor(target);
                    }
                });

        this.enhanceTemplate.enhance(this, "com.rabbitmq.client.impl.recovery.RecoveryAwareChannelN", new EnhanceCallback() {
            @Override
            public void doEnhance(InstrumentClass target) {
                addAckInterceptor(target);
            }
        });
        this.enhanceTemplate.enhance(this, "com.rabbitmq.client.impl.ChannelN", new EnhanceCallback() {
            @Override
            public void doEnhance(InstrumentClass target) {
                addAckInterceptor(target);
            }
        });

        this.enhanceTemplate.enhance(this,
                "org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer", new EnhanceCallback() {
                    @Override
                    public void doEnhance(InstrumentClass target) {
                        InstrumentMethod method = target.getDeclaredMethod("executeListener", "com.rabbitmq.client.Channel", "org.springframework.amqp.core.Message");
                        method.addInterceptor(Listeners.of(SpringBlockingQueueConsumerDeliveryInterceptor.class));
                        InstrumentMethod method1 = target.getDeclaredMethod("executeListener", "com.rabbitmq.client.Channel", "java.lang.Object");
                        method1.addInterceptor(Listeners.of(SpringBlockingQueueConsumerDeliveryInterceptor.class));
                    }
                });


        this.enhanceTemplate.enhance(this,
                "com.rabbitmq.client.QueueingConsumer", new EnhanceCallback() {
                    @Override
                    public void doEnhance(InstrumentClass target) {
                        InstrumentMethod handle = target.getDeclaredMethod("handle", "com.rabbitmq.client.QueueingConsumer$Delivery");
                        handle.addInterceptor(Listeners.of(QueueingConsumerHandleInterceptor.class));
                    }
                });

        this.enhanceTemplate.enhance(this, "org.springframework.amqp.rabbit.support.PublisherCallbackChannelImpl", new EnhanceCallback() {
            @Override
            public void doEnhance(InstrumentClass target) {
                InstrumentMethod handle = target.getDeclaredMethod("basicConsume", "java.lang.Boolean", "com.rabbitmq.client.DefaultConsumer");
                handle.addInterceptor(Listeners.of(ChannelNBasicConsumeInterceptor.class));
            }
        });

        /**
         * 先把 spring 的先支持掉，如果使用原生的 rabbitmq 可能还是会有一些问题
         */
        this.enhanceTemplate.enhance(this, "org.springframework.amqp.rabbit.listener.BlockingQueueConsumer$InternalConsumer", new EnhanceCallback() {
            @Override
            public void doEnhance(InstrumentClass target) {
                InstrumentMethod method = target.getDeclaredMethods("handleDelivery", "java.lang.String", "com.rabbitmq.client.Envelope", "com.rabbitmq.client.AMQP$BasicProperties", "byte[]");
                method.addInterceptor(Listeners.of(DefaultConsumerHandleDeliveryInterceptor.class, "Handle_Delivery", ExecutionPolicy.BOUNDARY, Interceptors.SCOPE_CALLBACK));

            }
        });

        this.enhanceTemplate.enhance(this, "org.springframework.amqp.rabbit.listener.BlockingQueueConsumer", new EnhanceCallback() {
            @Override
            public void doEnhance(InstrumentClass target) {
                final InstrumentMethod instrumentMethod = target.getDeclaredMethod("handle", "org.springframework.amqp.rabbit.support.Delivery");
                instrumentMethod.addInterceptor(Listeners.of(BlockingQueueConsumerConsumeFromQueueInterceptor.class));

            }
        });
    }

    private void addAckInterceptor(InstrumentClass target) {
        target.getDeclaredMethods("basicAck", "long", "boolean")
                .addInterceptor(Listeners.of(ChannelNAckInterceptor.class));

        target.getDeclaredMethods("basicAck", "long", "boolean", "boolean")
                .addInterceptor(Listeners.of(ChannelNAckInterceptor.class));

        target.getDeclaredMethods("basicNack", "long", "boolean")
                .addInterceptor(Listeners.of(ChannelNAckInterceptor.class));

        target.getDeclaredMethods("basicNack", "long", "boolean", "boolean")
                .addInterceptor(Listeners.of(ChannelNAckInterceptor.class));

        target.getDeclaredMethods("basicReject", "long", "boolean")
                .addInterceptor(Listeners.of(ChannelNAckInterceptor.class));
    }

}
