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
package com.pamirs.attach.plugin.feign.interceptor;

import java.lang.reflect.Method;

import com.pamirs.attach.plugin.feign.FeignConstants;
import com.pamirs.pradar.Pradar;
import com.pamirs.pradar.ResultCode;
import com.pamirs.pradar.interceptor.SpanRecord;
import com.pamirs.pradar.interceptor.TraceInterceptorAdaptor;
import com.pamirs.pradar.internal.config.MatchConfig;
import com.pamirs.pradar.pressurement.ClusterTestUtils;
import com.shulie.instrument.simulator.api.ProcessControlException;
import com.shulie.instrument.simulator.api.listener.ext.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @Author <a href="tangyuhan@shulie.io">yuhan.tang</a>
 * @package: com.pamirs.attach.plugin.feign.interceptor
 * @Date 2021/6/7 2:44 下午
 */
public class FeignMockInterceptor extends TraceInterceptorAdaptor {

    private final static Logger logger = LoggerFactory.getLogger(FeignMockInterceptor.class);
    private final static Logger mockLogger = LoggerFactory.getLogger("FEIGN-MOCK-LOGGER");

    @Override
    public void beforeLast(Advice advice) throws ProcessControlException {
        if (Pradar.isClusterTest()) {
            Object[] parameterArray = advice.getParameterArray();
            Method method = (Method) parameterArray[1];
            String className = method.getDeclaringClass().getName();
            final String methodName = method.getName();
            MatchConfig config = ClusterTestUtils.rpcClusterTest(className, methodName);
            config.addArgs("args", advice.getParameterArray());
            config.addArgs("mockLogger", mockLogger);
            config.addArgs("url", className.concat("#").concat(methodName));
            config.addArgs("isInterface", Boolean.TRUE);
            config.addArgs("class", className);
            config.addArgs("method", methodName);
            config.getStrategy().processBlock(advice.getClassLoader(), config);
        }

    }

    @Override
    public SpanRecord beforeTrace(Advice advice) {
        Object[] args = advice.getParameterArray();
        Method method = (Method) args[1];
        if (method == null) {
            return null;
        }
        Object [] arg = (Object[]) args[2];
        SpanRecord record = new SpanRecord();
        record.setService(method.getDeclaringClass().getName());
        record.setMethod(method.getName() + getParameterTypesString(method.getParameterTypes()));
        if (arg != null) {
            record.setRequestSize(arg.length);
        }
        return record;
    }

    @Override
    public SpanRecord afterTrace(Advice advice) {
        Object[] args = advice.getParameterArray();
        Method method = (Method) args[1];
        if (method == null) {
            return null;
        }

        SpanRecord record = new SpanRecord();
        record.setResultCode(ResultCode.INVOKE_RESULT_SUCCESS);
        record.setService(method.getDeclaringClass().getName());
        record.setMethod(method.getName() + getParameterTypesString(method.getParameterTypes()));
        record.setResponse(advice.getReturnObj());
        return record;
    }

    static String getParameterTypesString(Class<?>[] classes) {
        if (classes == null || classes.length == 0) {
            return "()";
        }
        StringBuilder builder = new StringBuilder();
        builder.append('(');
        for (Class<?> clazz : classes) {
            if (clazz == null) {
                continue;
            }
            builder.append(clazz.getSimpleName()).append(',');
        }
        if (builder.length() > 1) {
            builder.deleteCharAt(builder.length() - 1);
        }
        builder.append(')');
        return builder.toString();
    }

    @Override
    public SpanRecord exceptionTrace(Advice advice) {
        Object[] args = advice.getParameterArray();
        Method method = (Method) args[1];
        if (method == null) {
            return null;
        }
        Object [] arg = (Object[]) args[2];
        SpanRecord record = new SpanRecord();
        record.setService(method.getDeclaringClass().getName());
        record.setMethod(method.getName() + getParameterTypesString(method.getParameterTypes()));
        record.setRequest(arg);
        record.setResultCode(ResultCode.INVOKE_RESULT_FAILED);
        record.setResponse(advice.getThrowable());
        return record;
    }

    @Override
    public String getPluginName() {
        return FeignConstants.PLUGIN_NAME;
    }

    @Override
    public int getPluginType() {
        return FeignConstants.PLUGIN_TYPE;
    }
}
