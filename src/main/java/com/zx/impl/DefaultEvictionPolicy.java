package com.zx.impl;

import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.*;
import org.apache.commons.pool2.impl.EvictionPolicy;

/**
 * 驱逐策略默认实现类
 * {@link org.apache.commons.pool2.impl.EvictionPolicy}接口的默认实现;
 * 如符合下列条件（或），将被驱逐:
 * 1. 该对象是空闲的，且空闲时长超过 {@link GenericObjectPool#getMinEvictableIdleTimeMillis()}/{@link GenericKeyedObjectPool#getMinEvictableIdleTimeMillis()}
 * 2. 空闲对象数大于{@link GenericObjectPool#getMinIdle()} / {@link GenericKeyedObjectPoolConfig#getMinIdlePerKey()}，
 *      且空闲时长超过{@link GenericObjectPool#getSoftMinEvictableIdleTimeMillis()} / {@link GenericKeyedObjectPool#getSoftMinEvictableIdleTimeMillis()}
 * 这个类是不可变的，也是线程安全的
 *
 * Provides the default implementation of {@link org.apache.commons.pool2.impl.EvictionPolicy} used by the
 * pools. Objects will be evicted if the following conditions are met:
 * <ul>
 * <li>the object has been idle longer than
 *     {@link GenericObjectPool#getMinEvictableIdleTimeMillis()} /
 *     {@link GenericKeyedObjectPool#getMinEvictableIdleTimeMillis()}</li>
 * <li>there are more than {@link GenericObjectPool#getMinIdle()} /
 *     {@link GenericKeyedObjectPoolConfig#getMinIdlePerKey()} idle objects in
 *     the pool and the object has been idle for longer than
 *     {@link GenericObjectPool#getSoftMinEvictableIdleTimeMillis()} /
 *     {@link GenericKeyedObjectPool#getSoftMinEvictableIdleTimeMillis()}
 * </ul>
 * This class is immutable and thread-safe.
 *
 * @param <T> 池中的对象类型
 */
public class DefaultEvictionPolicy<T> implements EvictionPolicy<T> {

    /**
     * 驱逐方法，实现接口方法
     */
    @Override
    public boolean evict(EvictionConfig config, PooledObject<T> underTest,
            int idleCount) {
        //就是类上面的注释写的条件
        //1. 对象空闲时间超过了SoftMinEvictableIdleTimeMillis，并且池中空闲数量 超过 最小空闲数量
        //2.或， 对象空闲时间 大于  MinEvictableIdleTimeMillis
        if ((config.getIdleSoftEvictTime() < underTest.getIdleTimeMillis() &&
                config.getMinIdle() < idleCount) ||
                config.getIdleEvictTime() < underTest.getIdleTimeMillis()) {
            return true;
        }
        return false;
    }
}
