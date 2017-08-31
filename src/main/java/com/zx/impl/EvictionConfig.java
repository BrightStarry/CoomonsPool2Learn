package com.zx.impl;

import org.apache.commons.pool2.impl.BaseGenericObjectPool;
import org.apache.commons.pool2.impl.EvictionPolicy;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPool;

/**
 * 这个类被池是使用，以将配置信息传递给{@link org.apache.commons.pool2.impl.EvictionConfig}实例。
 * {@link EvictionPolicy}也可以有它自己的特定配置属性.
 * <p>
 * 这个类是不可变的，也是线程安全的
 */
public class EvictionConfig {
    //空闲驱逐时间
    private final long idleEvictTime;
    //空闲柔和驱逐时间
    private final long idleSoftEvictTime;
    //最小空闲数
    private final int minIdle;


    /**
     * 创建一个新的驱逐配置使用指定参数。
     * 实例是不可变的。
     *
     * @param poolIdleEvictTime 预计将提供
     *        {@link org.apache.commons.pool2.impl.BaseGenericObjectPool#getMinEvictableIdleTimeMillis()}
     * @param poolIdleSoftEvictTime 预计将提供
     *        {@link BaseGenericObjectPool#getSoftMinEvictableIdleTimeMillis()}
     * @param minIdle 预计将提供
     *        {@link GenericObjectPool#getMinIdle()} or
     *        {@link GenericKeyedObjectPool#getMinIdlePerKey()}
     */
    public EvictionConfig(long poolIdleEvictTime, long poolIdleSoftEvictTime,
                          int minIdle) {
        //小于0时，默认为最大值
        if (poolIdleEvictTime > 0) {
            idleEvictTime = poolIdleEvictTime;
        } else {
            idleEvictTime = Long.MAX_VALUE;
        }
        //小于0时默认为最大值
        if (poolIdleSoftEvictTime > 0) {
            idleSoftEvictTime = poolIdleSoftEvictTime;
        } else {
            idleSoftEvictTime  = Long.MAX_VALUE;
        }

        this.minIdle = minIdle;
    }

    /**
     * Obtain the {@code idleEvictTime} for this eviction configuration
     * instance.
     * <p>
     * How the evictor behaves based on this value will be determined by the
     * configured {@link EvictionPolicy}.
     *
     * @return The {@code idleEvictTime} in milliseconds
     */
    public long getIdleEvictTime() {
        return idleEvictTime;
    }

    /**
     * Obtain the {@code idleSoftEvictTime} for this eviction configuration
     * instance.
     * <p>
     * How the evictor behaves based on this value will be determined by the
     * configured {@link EvictionPolicy}.
     *
     * @return The (@code idleSoftEvictTime} in milliseconds
     */
    public long getIdleSoftEvictTime() {
        return idleSoftEvictTime;
    }

    /**
     * Obtain the {@code minIdle} for this eviction configuration instance.
     * <p>
     * How the evictor behaves based on this value will be determined by the
     * configured {@link EvictionPolicy}.
     *
     * @return The {@code minIdle}
     */
    public int getMinIdle() {
        return minIdle;
    }
}
