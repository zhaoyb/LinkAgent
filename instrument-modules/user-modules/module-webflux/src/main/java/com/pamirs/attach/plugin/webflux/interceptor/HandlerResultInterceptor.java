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
package com.pamirs.attach.plugin.webflux.interceptor;


import com.pamirs.attach.plugin.webflux.common.Cache;
import com.shulie.instrument.simulator.api.listener.ext.Advice;
import org.springframework.web.server.ServerWebExchange;

/**
 * @Auther: vernon
 * @Date: 2021/1/11 14:29
 * @Description:
 */
public class HandlerResultInterceptor extends BaseHandlerInjector  {


    @Override
    public void beforeFirst(Advice advice) {

        doAfter((ServerWebExchange) advice.getParameterArray()[0], null, null);
        Cache.RequestHolder.remove();
    }

    @Override
    public void exceptionFirst(Advice advice) {
        doAfter((ServerWebExchange) advice.getParameterArray()[0], null, advice.getThrowable());
        Cache.RequestHolder.remove();

    }
}
