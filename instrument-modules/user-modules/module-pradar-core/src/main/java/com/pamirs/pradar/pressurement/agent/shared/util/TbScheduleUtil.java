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
package com.pamirs.pradar.pressurement.agent.shared.util;

/**
 * @author guohz
 * @since 2020/7/30 3:55 下午
 */
public class TbScheduleUtil {

    private static Object factory;

    public static void setInstance(Object factory) {
        TbScheduleUtil.factory = factory;
    }

    public static Object getFactory() {
        return factory;
    }

    public static void release() {
        factory = null;
    }

}
