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
package com.pamirs.pradar.interceptor;


import com.pamirs.pradar.scope.ScopeFactory;
import com.shulie.instrument.simulator.api.listener.ext.Advice;
import com.shulie.instrument.simulator.api.scope.InterceptorScope;
import com.shulie.instrument.simulator.api.scope.InterceptorScopeInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by xiaobin on 2017/1/19.
 */
public class ScopedResultInterceptor extends ResultInterceptor {
    private final Logger logger = LoggerFactory.getLogger(ScopedResultInterceptor.class.getName());

    private final ResultInterceptor interceptor;
    private final InterceptorScope scope;
    private final int policy;

    public ScopedResultInterceptor(ResultInterceptor interceptor, int policy) {
        this(interceptor, null, policy);
    }

    public ScopedResultInterceptor(ResultInterceptor interceptor, InterceptorScope scope, int policy) {
        if (interceptor == null) {
            throw new NullPointerException("interceptor must not be null");
        }
        if (scope == null) {
            throw new NullPointerException("scope must not be null");
        }
        this.interceptor = interceptor;
        this.scope = scope;
        super.setInterceptorScope(scope);
        this.policy = policy;
    }

    private InterceptorScope getScope(Advice advice) {
        if (scope != null) {
            return scope;
        }
        return ScopeFactory.getScope(interceptor.getClass().getName() + "#" + advice.getTargetClass().getName() + "_" + advice.getBehavior().getName());
    }

    @Override
    public Object getResult(Advice advice) throws Throwable {
        final InterceptorScopeInvocation transaction = getScope(advice).getCurrentInvocation();

        Object result = advice.getReturnObj();
        try {
            final boolean success = transaction.tryEnter(policy);
            if (success) {
                return interceptor.getResult(advice);
            } else {
                if (logger.isDebugEnabled()) {
                    logger.debug("tryAfter() returns false: interceptorScopeTransaction: {}, executionPoint: {}. Skip interceptor {}", transaction, policy, interceptor.getClass());
                }
            }
        } finally {
            if (transaction.canLeave(policy)) {
                transaction.leave(policy);
            }
        }
        return result;
    }

}

