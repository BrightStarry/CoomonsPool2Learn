/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zx.impl;

import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultEvictionPolicy;
import org.apache.commons.pool2.impl.EvictionConfig;

/**
 * 驱逐策略:提供自定义的驱逐策略，有一个默认的实现类(DefaultEvictionPolicy类),也可自定义实现类
 */
public interface EvictionPolicy<T> {

    /**
     * 使用该方法确认池中空闲对象是否应该被驱逐,传入EvictionConfig、PooledObject<T>、空闲对象数（包括该对象）,返回boolean
     * @param config    The pool configuration settings related to eviction
     * @param underTest The pooled object being tested for eviction
     * @param idleCount The current number of idle objects in the pool including
     *                      the object under test
     * @return <code>true</code> if the object should be evicted, otherwise
     *             <code>false</code>
     */
    boolean evict(EvictionConfig config, PooledObject<T> underTest,
                  int idleCount);
}
