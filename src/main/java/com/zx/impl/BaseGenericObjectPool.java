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
import org.apache.commons.pool2.SwallowedExceptionListener;
import org.apache.commons.pool2.impl.*;
import org.apache.commons.pool2.impl.EvictionPolicy;

import javax.management.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.util.Deque;
import java.util.Iterator;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基础通用对象池(线程安全的)：基础类，提供了GenericObjectPool接口和GenericKeyedObjectPool接口的通用方法；主要是为了减少这两个连接池间的代码重复;
 */
public abstract class BaseGenericObjectPool<T> {

    // 常量
    /**
     * 用于存储某些属性的历史数据的缓存的大小
     * so that rolling means may be calculated.所以滚动的意思可计算的
     */
    public static final int MEAN_TIMING_STATS_CACHE_SIZE = 100;

    // 配置属性-大部分属性都引用自BaseObjectPoolConfig类
    //最大对象数量
    private volatile int maxTotal =
            GenericKeyedObjectPoolConfig.DEFAULT_MAX_TOTAL;
    //池对象耗尽时是否阻塞
    private volatile boolean blockWhenExhausted =
            BaseObjectPoolConfig.DEFAULT_BLOCK_WHEN_EXHAUSTED;
    //最大等待时间
    private volatile long maxWaitMillis =
            BaseObjectPoolConfig.DEFAULT_MAX_WAIT_MILLIS;
    //是否后进先出
    private volatile boolean lifo = BaseObjectPoolConfig.DEFAULT_LIFO;
    //是否公平（公平好像使用FIFI方式）
    private final boolean fairness;
    //是否创建时测试可用性
    private volatile boolean testOnCreate =
            BaseObjectPoolConfig.DEFAULT_TEST_ON_CREATE;
    //是否借用时测试可用性
    private volatile boolean testOnBorrow =
            BaseObjectPoolConfig.DEFAULT_TEST_ON_BORROW;
    //是否返回时测试可用性
    private volatile boolean testOnReturn =
            BaseObjectPoolConfig.DEFAULT_TEST_ON_RETURN;
    //是否空闲时测试可用性
    private volatile boolean testWhileIdle =
            BaseObjectPoolConfig.DEFAULT_TEST_WHILE_IDLE;
    //多久进行一次驱逐-为非正时，没有空闲对象时会驱逐
    private volatile long timeBetweenEvictionRunsMillis =
            BaseObjectPoolConfig.DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS;
    //测试可用性时每次检查几个对象
    private volatile int numTestsPerEvictionRun =
            BaseObjectPoolConfig.DEFAULT_NUM_TESTS_PER_EVICTION_RUN;
    //至少空闲多久才能被驱逐
    private volatile long minEvictableIdleTimeMillis =
            BaseObjectPoolConfig.DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS;
    //空闲后多久被驱逐
    private volatile long softMinEvictableIdleTimeMillis =
            BaseObjectPoolConfig.DEFAULT_SOFT_MIN_EVICTABLE_IDLE_TIME_MILLIS;
    //驱逐策略实现类
    private volatile org.apache.commons.pool2.impl.EvictionPolicy<T> evictionPolicy;


    // Internal (primarily state) attributes 内部（主要状态）属性
    //关闭锁对象
    final Object closeLock = new Object();
    //是否关闭
    volatile boolean closed = false;
    //驱逐锁
    final Object evictionLock = new Object();
    //内部类、驱逐线程
    private Evictor evictor = null; // @GuardedBy("evictionLock")
    //内部类，驱逐迭代器
    EvictionIterator evictionIterator = null; // @GuardedBy("evictionLock")
    /*
     * Class loader for evictor thread to use since, in a JavaEE or similar
     * environment, the context class loader for the evictor thread may not have
     * visibility of the correct factory. See POOL-161. Uses a weak reference to
     * avoid potential memory leaks if the Pool is discarded rather than closed.
     * 工厂类加载器,弱引用
     */
    private final WeakReference<ClassLoader> factoryClassLoader;


    // Monitoring (primarily JMX) attributes 监控(主要JMX)属性
    //对象名
    private final ObjectName oname;
    //创建时堆栈轨迹
    private final String creationStackTrace;
    //借用总数
    private final AtomicLong borrowedCount = new AtomicLong(0);
    //返回总数
    private final AtomicLong returnedCount = new AtomicLong(0);
    //创建总数
    final AtomicLong createdCount = new AtomicLong(0);
    //销毁总数
    final AtomicLong destroyedCount = new AtomicLong(0);
    //由于驱逐被销毁的对象总数
    final AtomicLong destroyedByEvictorCount = new AtomicLong(0);
    //由于失败被销毁的对象总数
    final AtomicLong destroyedByBorrowValidationCount = new AtomicLong(0);
    //活动时间
    private final StatsStore activeTimes = new StatsStore(MEAN_TIMING_STATS_CACHE_SIZE);
    //空闲时间
    private final StatsStore idleTimes = new StatsStore(MEAN_TIMING_STATS_CACHE_SIZE);
    //等待时间
    private final StatsStore waitTimes = new StatsStore(MEAN_TIMING_STATS_CACHE_SIZE);
    //最大借用等待时间
    private final AtomicLong maxBorrowWaitTimeMillis = new AtomicLong(0L);
    //忍耐异常监听器
    private volatile SwallowedExceptionListener swallowedExceptionListener = null;


    /**
     * 处理JMX注册(如果需要)和所需的监控初始化。
     *
     * @param config        池配置
     * @param jmxNameBase   新池的默认基本JMX名称，除非 被配置覆盖
     * @param jmxNamePrefix 用于新池的JMX名称的前缀
     */
    public BaseGenericObjectPool(BaseObjectPoolConfig config,
                                 String jmxNameBase, String jmxNamePrefix) {
        //是否启用jmx
        if (config.getJmxEnabled()) {
            //注册该类到JMXBean服务，返回ObjectName(JMX的对象),
            this.oname = jmxRegister(config, jmxNameBase, jmxNamePrefix);
        } else {
            //否则为空
            this.oname = null;
        }

        // 填充创建时异常堆栈追踪字符串
        this.creationStackTrace = getStackTrace(new Exception());

        // 保存当前的TCCL(如果有的话)稍后被逐出的线程使用-上下文类加载器
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        //如果为空，类加载器为空
        if (cl == null) {
            factoryClassLoader = null;
        } else {
            //否则使用弱引用存储
            factoryClassLoader = new WeakReference<ClassLoader>(cl);
        }
        //获取配置中的是否公平属性
        fairness = config.getFairness();
    }


    /**
     * 最大对象数，为负数时，无限制
     *
     * @return the cap on the total number of object instances managed by the
     *         pool.
     *
     * @see #setMaxTotal
     */
    public final int getMaxTotal() {
        return maxTotal;
    }

    /**
     * Sets the cap on the number of objects that can be allocated by the pool
     * (checked out to clients, or idle awaiting checkout) at a given time. Use
     * a negative value for no limit.
     *
     * @param maxTotal  The cap on the total number of object instances managed
     *                  by the pool. Negative values mean that there is no limit
     *                  to the number of objects allocated by the pool.
     *
     * @see #getMaxTotal
     */
    public final void setMaxTotal(int maxTotal) {
        this.maxTotal = maxTotal;
    }

    /**
     * Returns whether to block when the <code>borrowObject()</code> method is
     * invoked when the pool is exhausted (the maximum number of "active"
     * objects has been reached).
     *
     * @return <code>true</code> if <code>borrowObject()</code> should block
     *         when the pool is exhausted
     *
     * @see #setBlockWhenExhausted
     */
    public final boolean getBlockWhenExhausted() {
        return blockWhenExhausted;
    }

    /**
     * Sets whether to block when the <code>borrowObject()</code> method is
     * invoked when the pool is exhausted (the maximum number of "active"
     * objects has been reached).
     *
     * @param blockWhenExhausted    <code>true</code> if
     *                              <code>borrowObject()</code> should block
     *                              when the pool is exhausted
     *
     * @see #getBlockWhenExhausted
     */
    public final void setBlockWhenExhausted(boolean blockWhenExhausted) {
        this.blockWhenExhausted = blockWhenExhausted;
    }

    /**
     * 最大等待时间，当小于0时，会无限期阻塞
     *
     * @return the maximum number of milliseconds <code>borrowObject()</code>
     *         will block.
     *
     * @see #setMaxWaitMillis
     * @see #setBlockWhenExhausted
     */
    public final long getMaxWaitMillis() {
        return maxWaitMillis;
    }

    /**
     * Sets the maximum amount of time (in milliseconds) the
     * <code>borrowObject()</code> method should block before throwing an
     * exception when the pool is exhausted and
     * {@link #getBlockWhenExhausted} is true. When less than 0, the
     * <code>borrowObject()</code> method may block indefinitely.
     *
     * @param maxWaitMillis the maximum number of milliseconds
     *                      <code>borrowObject()</code> will block or negative
     *                      for indefinitely.
     *
     * @see #getMaxWaitMillis
     * @see #setBlockWhenExhausted
     */
    public final void setMaxWaitMillis(long maxWaitMillis) {
        this.maxWaitMillis = maxWaitMillis;
    }

    /**
     * Returns whether the pool has LIFO (last in, first out) behaviour with
     * respect to idle objects - always returning the most recently used object
     * from the pool, or as a FIFO (first in, first out) queue, where the pool
     * always returns the oldest object in the idle object pool.
     *
     * @return <code>true</code> if the pool is configured with LIFO behaviour
     *         or <code>false</code> if the pool is configured with FIFO
     *         behaviour
     *
     * @see #setLifo
     */
    public final boolean getLifo() {
        return lifo;
    }

    /**
     * Returns whether or not the pool serves threads waiting to borrow objects fairly.
     * True means that waiting threads are served as if waiting in a FIFO queue.
     *
     * @return <code>true</code> if waiting threads are to be served
     *             by the pool in arrival order
     */
    public final boolean getFairness() {
        return fairness;
    }

    /**
     * Sets whether the pool has LIFO (last in, first out) behaviour with
     * respect to idle objects - always returning the most recently used object
     * from the pool, or as a FIFO (first in, first out) queue, where the pool
     * always returns the oldest object in the idle object pool.
     *
     * @param lifo  <code>true</code> if the pool is to be configured with LIFO
     *              behaviour or <code>false</code> if the pool is to be
     *              configured with FIFO behaviour
     *
     * @see #getLifo()
     */
    public final void setLifo(boolean lifo) {
        this.lifo = lifo;
    }

    /**
     * Returns whether objects created for the pool will be validated before
     * being returned from the <code>borrowObject()</code> method. Validation is
     * performed by the <code>validateObject()</code> method of the factory
     * associated with the pool. If the object fails to validate, then
     * <code>borrowObject()</code> will fail.
     *
     * @return <code>true</code> if newly created objects are validated before
     *         being returned from the <code>borrowObject()</code> method
     *
     * @see #setTestOnCreate
     *
     * @since 2.2
     */
    public final boolean getTestOnCreate() {
        return testOnCreate;
    }

    /**
     * Sets whether objects created for the pool will be validated before
     * being returned from the <code>borrowObject()</code> method. Validation is
     * performed by the <code>validateObject()</code> method of the factory
     * associated with the pool. If the object fails to validate, then
     * <code>borrowObject()</code> will fail.
     *
     * @param testOnCreate  <code>true</code> if newly created objects should be
     *                      validated before being returned from the
     *                      <code>borrowObject()</code> method
     *
     * @see #getTestOnCreate
     *
     * @since 2.2
     */
    public final void setTestOnCreate(boolean testOnCreate) {
        this.testOnCreate = testOnCreate;
    }

    /**
     * Returns whether objects borrowed from the pool will be validated before
     * being returned from the <code>borrowObject()</code> method. Validation is
     * performed by the <code>validateObject()</code> method of the factory
     * associated with the pool. If the object fails to validate, it will be
     * removed from the pool and destroyed, and a new attempt will be made to
     * borrow an object from the pool.
     *
     * @return <code>true</code> if objects are validated before being returned
     *         from the <code>borrowObject()</code> method
     *
     * @see #setTestOnBorrow
     */
    public final boolean getTestOnBorrow() {
        return testOnBorrow;
    }

    /**
     * Sets whether objects borrowed from the pool will be validated before
     * being returned from the <code>borrowObject()</code> method. Validation is
     * performed by the <code>validateObject()</code> method of the factory
     * associated with the pool. If the object fails to validate, it will be
     * removed from the pool and destroyed, and a new attempt will be made to
     * borrow an object from the pool.
     *
     * @param testOnBorrow  <code>true</code> if objects should be validated
     *                      before being returned from the
     *                      <code>borrowObject()</code> method
     *
     * @see #getTestOnBorrow
     */
    public final void setTestOnBorrow(boolean testOnBorrow) {
        this.testOnBorrow = testOnBorrow;
    }

    /**
     * Returns whether objects borrowed from the pool will be validated when
     * they are returned to the pool via the <code>returnObject()</code> method.
     * Validation is performed by the <code>validateObject()</code> method of
     * the factory associated with the pool. Returning objects that fail validation
     * are destroyed rather then being returned the pool.
     *
     * @return <code>true</code> if objects are validated on return to
     *         the pool via the <code>returnObject()</code> method
     *
     * @see #setTestOnReturn
     */
    public final boolean getTestOnReturn() {
        return testOnReturn;
    }

    /**
     * Sets whether objects borrowed from the pool will be validated when
     * they are returned to the pool via the <code>returnObject()</code> method.
     * Validation is performed by the <code>validateObject()</code> method of
     * the factory associated with the pool. Returning objects that fail validation
     * are destroyed rather then being returned the pool.
     *
     * @param testOnReturn <code>true</code> if objects are validated on
     *                     return to the pool via the
     *                     <code>returnObject()</code> method
     *
     * @see #getTestOnReturn
     */
    public final void setTestOnReturn(boolean testOnReturn) {
        this.testOnReturn = testOnReturn;
    }

    /**
     * Returns whether objects sitting idle in the pool will be validated by the
     * idle object evictor (if any - see
     * {@link #setTimeBetweenEvictionRunsMillis(long)}). Validation is performed
     * by the <code>validateObject()</code> method of the factory associated
     * with the pool. If the object fails to validate, it will be removed from
     * the pool and destroyed.
     *
     * @return <code>true</code> if objects will be validated by the evictor
     *
     * @see #setTestWhileIdle
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public final boolean getTestWhileIdle() {
        return testWhileIdle;
    }

    /**
     * Returns whether objects sitting idle in the pool will be validated by the
     * idle object evictor (if any - see
     * {@link #setTimeBetweenEvictionRunsMillis(long)}). Validation is performed
     * by the <code>validateObject()</code> method of the factory associated
     * with the pool. If the object fails to validate, it will be removed from
     * the pool and destroyed.  Note that setting this property has no effect
     * unless the idle object evictor is enabled by setting
     * <code>timeBetweenEvictionRunsMillis</code> to a positive value.
     *
     * @param testWhileIdle
     *            <code>true</code> so objects will be validated by the evictor
     *
     * @see #getTestWhileIdle
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public final void setTestWhileIdle(boolean testWhileIdle) {
        this.testWhileIdle = testWhileIdle;
    }

    /**
     * Returns the number of milliseconds to sleep between runs of the idle
     * object evictor thread. When non-positive, no idle object evictor thread
     * will be run.
     *
     * @return number of milliseconds to sleep between evictor runs
     *
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public final long getTimeBetweenEvictionRunsMillis() {
        return timeBetweenEvictionRunsMillis;
    }

    /**
     * Sets the number of milliseconds to sleep between runs of the idle
     * object evictor thread. When non-positive, no idle object evictor thread
     * will be run.
     *
     * @param timeBetweenEvictionRunsMillis
     *            number of milliseconds to sleep between evictor runs
     *
     * @see #getTimeBetweenEvictionRunsMillis
     */
    public final void setTimeBetweenEvictionRunsMillis(
            long timeBetweenEvictionRunsMillis) {
        this.timeBetweenEvictionRunsMillis = timeBetweenEvictionRunsMillis;
        startEvictor(timeBetweenEvictionRunsMillis);
    }

    /**
     * Returns the maximum number of objects to examine during each run (if any)
     * of the idle object evictor thread. When positive, the number of tests
     * performed for a run will be the minimum of the configured value and the
     * number of idle instances in the pool. When negative, the number of tests
     * performed will be <code>ceil({@link #getNumIdle}/
     * abs({@link #getNumTestsPerEvictionRun}))</code> which means that when the
     * value is <code>-n</code> roughly one nth of the idle objects will be
     * tested per run.
     *
     * @return max number of objects to examine during each evictor run
     *
     * @see #setNumTestsPerEvictionRun
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public final int getNumTestsPerEvictionRun() {
        return numTestsPerEvictionRun;
    }

    /**
     * Sets the maximum number of objects to examine during each run (if any)
     * of the idle object evictor thread. When positive, the number of tests
     * performed for a run will be the minimum of the configured value and the
     * number of idle instances in the pool. When negative, the number of tests
     * performed will be <code>ceil({@link #getNumIdle}/
     * abs({@link #getNumTestsPerEvictionRun}))</code> which means that when the
     * value is <code>-n</code> roughly one nth of the idle objects will be
     * tested per run.
     *
     * @param numTestsPerEvictionRun
     *            max number of objects to examine during each evictor run
     *
     * @see #getNumTestsPerEvictionRun
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public final void setNumTestsPerEvictionRun(int numTestsPerEvictionRun) {
        this.numTestsPerEvictionRun = numTestsPerEvictionRun;
    }

    /**
     * Returns the minimum amount of time an object may sit idle in the pool
     * before it is eligible for eviction by the idle object evictor (if any -
     * see {@link #setTimeBetweenEvictionRunsMillis(long)}). When non-positive,
     * no objects will be evicted from the pool due to idle time alone.
     *
     * @return minimum amount of time an object may sit idle in the pool before
     *         it is eligible for eviction
     *
     * @see #setMinEvictableIdleTimeMillis
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public final long getMinEvictableIdleTimeMillis() {
        return minEvictableIdleTimeMillis;
    }

    /**
     * Sets the minimum amount of time an object may sit idle in the pool
     * before it is eligible for eviction by the idle object evictor (if any -
     * see {@link #setTimeBetweenEvictionRunsMillis(long)}). When non-positive,
     * no objects will be evicted from the pool due to idle time alone.
     *
     * @param minEvictableIdleTimeMillis
     *            minimum amount of time an object may sit idle in the pool
     *            before it is eligible for eviction
     *
     * @see #getMinEvictableIdleTimeMillis
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public final void setMinEvictableIdleTimeMillis(
            long minEvictableIdleTimeMillis) {
        this.minEvictableIdleTimeMillis = minEvictableIdleTimeMillis;
    }

    /**
     * Returns the minimum amount of time an object may sit idle in the pool
     * before it is eligible for eviction by the idle object evictor (if any -
     * see {@link #setTimeBetweenEvictionRunsMillis(long)}),
     * with the extra condition that at least <code>minIdle</code> object
     * instances remain in the pool. This setting is overridden by
     * {@link #getMinEvictableIdleTimeMillis} (that is, if
     * {@link #getMinEvictableIdleTimeMillis} is positive, then
     * {@link #getSoftMinEvictableIdleTimeMillis} is ignored).
     *
     * @return minimum amount of time an object may sit idle in the pool before
     *         it is eligible for eviction if minIdle instances are available
     *
     * @see #setSoftMinEvictableIdleTimeMillis
     */
    public final long getSoftMinEvictableIdleTimeMillis() {
        return softMinEvictableIdleTimeMillis;
    }

    /**
     * Sets the minimum amount of time an object may sit idle in the pool
     * before it is eligible for eviction by the idle object evictor (if any -
     * see {@link #setTimeBetweenEvictionRunsMillis(long)}),
     * with the extra condition that at least <code>minIdle</code> object
     * instances remain in the pool. This setting is overridden by
     * {@link #getMinEvictableIdleTimeMillis} (that is, if
     * {@link #getMinEvictableIdleTimeMillis} is positive, then
     * {@link #getSoftMinEvictableIdleTimeMillis} is ignored).
     *
     * @param softMinEvictableIdleTimeMillis
     *            minimum amount of time an object may sit idle in the pool
     *            before it is eligible for eviction if minIdle instances are
     *            available
     *
     * @see #getSoftMinEvictableIdleTimeMillis
     */
    public final void setSoftMinEvictableIdleTimeMillis(
            long softMinEvictableIdleTimeMillis) {
        this.softMinEvictableIdleTimeMillis = softMinEvictableIdleTimeMillis;
    }

    /**
     * Returns the name of the {@link org.apache.commons.pool2.impl.EvictionPolicy} implementation that is
     * used by this pool.
     *
     * @return  The fully qualified class name of the {@link org.apache.commons.pool2.impl.EvictionPolicy}
     *
     * @see #setEvictionPolicyClassName(String)
     */
    public final String getEvictionPolicyClassName() {
        return evictionPolicy.getClass().getName();
    }

    /**
     * 设置这个池使用的{@link org.apache.commons.pool2.impl.EvictionPolicy}接口的实现类的名字，
     * 池将试图使用线程上下文类加载器加载该类。如果失败了，池将试图使用这个类的类加载器加载该类。
     *
     * @param evictionPolicyClassName   the fully qualified class name of the
     *                                  new eviction policy
     *
     * @see #getEvictionPolicyClassName()
     */
    public final void setEvictionPolicyClassName(
            String evictionPolicyClassName) {
        try {
            Class<?> clazz;
            try {
                //反射加载类对象
                clazz = Class.forName(evictionPolicyClassName, true,
                        Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException e) {
                //如果失败，换一种重载方法加载类对象
                clazz = Class.forName(evictionPolicyClassName);
            }
            //创建实例
            Object policy = clazz.newInstance();
            //如果是该接口实现类
            if (policy instanceof org.apache.commons.pool2.impl.EvictionPolicy<?>) {
                @SuppressWarnings("unchecked") // 安全，因为我们刚刚检查了这个类
                        //强转
                        org.apache.commons.pool2.impl.EvictionPolicy<T> evicPolicy = (org.apache.commons.pool2.impl.EvictionPolicy<T>) policy;
                //赋值
                this.evictionPolicy = evicPolicy;
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(
                    "Unable to create EvictionPolicy instance of type " +
                    evictionPolicyClassName, e);
        } catch (InstantiationException e) {
            throw new IllegalArgumentException(
                    "Unable to create EvictionPolicy instance of type " +
                    evictionPolicyClassName, e);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(
                    "Unable to create EvictionPolicy instance of type " +
                    evictionPolicyClassName, e);
        }
    }


    /**
     * 关闭该池，销毁剩下的空闲对象，如果该类注册到了JMX，取消注册
     * Closes the pool, destroys the remaining idle objects and, if registered
     * in JMX, deregisters it.
     */
    public abstract void close();

    /**
     * 该池是否关闭
     * @return <code>true</code> when this pool has been closed.
     */
    public final boolean isClosed() {
        return closed;
    }

    /**
     * 驱逐
     * <p>Perform <code>numTests</code> idle object eviction tests, evicting
     * examined objects that meet the criteria for eviction. If
     * <code>testWhileIdle</code> is true, examined objects are validated
     * when visited (and removed if invalid); otherwise only objects that
     * have been idle for more than <code>minEvicableIdleTimeMillis</code>
     * are removed.</p>
     *
     * @throws Exception when there is a problem evicting idle objects.
     */
    public abstract void evict() throws Exception;

    /**
     * Returns the {@link org.apache.commons.pool2.impl.EvictionPolicy} defined for this pool.
     *
     * @return the eviction policy
     * @since 2.4
     */
    protected EvictionPolicy<T> getEvictionPolicy() {
        return evictionPolicy;
    }

    /**
     * 验证该池是否打开，如果该池是关闭的，抛出非法状态异常
     * @throws IllegalStateException if the pool is closed.
     */
    final void assertOpen() throws IllegalStateException {
        if (isClosed()) {
            throw new IllegalStateException("Pool not open");
        }
    }

    /**
     * <p>Starts the evictor with the given delay. If there is an evictor
     * running when this method is called, it is stopped and replaced with a
     * new evictor with the specified delay.</p>
     * 用给定的延迟启动驱逐程序。如果有驱逐程序运行时调用此方法，它将停止并替换为新驱逐程序的指定延迟。
     * <p>这个方法需要是fina的，因为它在构造函数中被调用,See POOL-195.</p>
     *
     * @param delay 在开始和驱逐之间之前的毫秒数
     */
    final void startEvictor(long delay) {
        //驱逐锁
        synchronized (evictionLock) {
            //如果已经有驱逐线程，表示驱逐程序正在运行
            if (null != evictor) {
                //取消运行该线程
                EvictionTimer.cancel(evictor);
                //释放引用
                evictor = null;
                evictionIterator = null;
            }
            //启动
            if (delay > 0) {
                //创建新线程
                evictor = new Evictor();
                //定时启动该线程
                EvictionTimer.schedule(evictor, delay, delay);
            }
        }
    }

    /**
     * 尝试确保在池中可以使用配置的最少空闲实例数。
     * Tries to ensure that the configured minimum number of idle instances are available in the pool.
     * @throws Exception if an error occurs creating idle instances
     */
    abstract void ensureMinIdle() throws Exception;


    // 监控(主要是JMX)相关的方法

    /**
     * Provides the name under which the pool has been registered with the
     * platform MBean server or <code>null</code> if the pool has not been
     * registered.
     * @return the JMX name
     */
    public final ObjectName getJmxName() {
        return oname;
    }

    /**
     * Provides the stack trace for the call that created this pool. JMX
     * registration may trigger a memory leak so it is important that pools are
     * deregistered when no longer used by calling the {@link #close()} method.
     * This method is provided to assist with identifying code that creates but
     * does not close it thereby creating a memory leak.
     * @return pool creation stack trace
     */
    public final String getCreationStackTrace() {
        return creationStackTrace;
    }

    /**
     * The total number of objects successfully borrowed from this pool over the
     * lifetime of the pool.
     * @return the borrowed object count
     */
    public final long getBorrowedCount() {
        return borrowedCount.get();
    }

    /**
     * The total number of objects returned to this pool over the lifetime of
     * the pool. This excludes attempts to return the same object multiple
     * times.
     * @return the returned object count
     */
    public final long getReturnedCount() {
        return returnedCount.get();
    }

    /**
     * The total number of objects created for this pool over the lifetime of
     * the pool.
     * @return the created object count
     */
    public final long getCreatedCount() {
        return createdCount.get();
    }

    /**
     * The total number of objects destroyed by this pool over the lifetime of
     * the pool.
     * @return the destroyed object count
     */
    public final long getDestroyedCount() {
        return destroyedCount.get();
    }

    /**
     * The total number of objects destroyed by the evictor associated with this
     * pool over the lifetime of the pool.
     * @return the evictor destroyed object count
     */
    public final long getDestroyedByEvictorCount() {
        return destroyedByEvictorCount.get();
    }

    /**
     * The total number of objects destroyed by this pool as a result of failing
     * validation during <code>borrowObject()</code> over the lifetime of the
     * pool.
     * @return validation destroyed object count
     */
    public final long getDestroyedByBorrowValidationCount() {
        return destroyedByBorrowValidationCount.get();
    }

    /**
     * The mean time objects are active for based on the last {@link
     * #MEAN_TIMING_STATS_CACHE_SIZE} objects returned to the pool.
     * @return mean time an object has been checked out from the pool among
     * recently returned objects
     */
    public final long getMeanActiveTimeMillis() {
        return activeTimes.getMean();
    }

    /**
     * The mean time objects are idle for based on the last {@link
     * #MEAN_TIMING_STATS_CACHE_SIZE} objects borrowed from the pool.
     * @return mean time an object has been idle in the pool among recently
     * borrowed objects
     */
    public final long getMeanIdleTimeMillis() {
        return idleTimes.getMean();
    }

    /**
     * The mean time threads wait to borrow an object based on the last {@link
     * #MEAN_TIMING_STATS_CACHE_SIZE} objects borrowed from the pool.
     * @return mean time in milliseconds that a recently served thread has had
     * to wait to borrow an object from the pool
     */
    public final long getMeanBorrowWaitTimeMillis() {
        return waitTimes.getMean();
    }

    /**
     * The maximum time a thread has waited to borrow objects from the pool.
     * @return maximum wait time in milliseconds since the pool was created
     */
    public final long getMaxBorrowWaitTimeMillis() {
        return maxBorrowWaitTimeMillis.get();
    }

    /**
     * The number of instances currently idle in this pool.
     * @return count of instances available for checkout from the pool
     */
    public abstract int getNumIdle();

    /**
     * The listener used (if any) to receive notifications of exceptions
     * unavoidably swallowed by the pool.
     *
     * @return The listener or <code>null</code> for no listener
     */
    public final SwallowedExceptionListener getSwallowedExceptionListener() {
        return swallowedExceptionListener;
    }

    /**
     * The listener used (if any) to receive notifications of exceptions unavoidably swallowed by the pool.
     *
     * @param swallowedExceptionListener    The listener or <code>null</code>
     *                                      for no listener
     */
    public final void setSwallowedExceptionListener(
            SwallowedExceptionListener swallowedExceptionListener) {
        this.swallowedExceptionListener = swallowedExceptionListener;
    }

    /**
     * 忍受一个异常并通知配置监听器的忍受异常队列
     * Swallows an exception and notifies the configured listener for swallowed exceptions queue.
     *
     * @param e exception to be swallowed
     */
    final void swallowException(Exception e) {
        //获取忍受异常监听器
        SwallowedExceptionListener listener = getSwallowedExceptionListener();
        //如果为空，退出该方法
        if (listener == null) {
            return;
        }

        try {
            //忍受异常时调用的方法
            listener.onSwallowException(e);
        } catch (OutOfMemoryError oome) {
            throw oome;
        } catch (VirtualMachineError vme) {
            throw vme;
        } catch (Throwable t) {
            //忽略。。。。基本就炸了
        }
    }

    /**
     * 在从池中借用对象之后更新统计数据。
     * @param p 从池中借用的对象
     * @param waitTime 时间(以毫秒为单位)，借用线程等待的时间
     */
    final void updateStatsBorrow(PooledObject<T> p, long waitTime) {
        //借用总数增加
        borrowedCount.incrementAndGet();
        //空闲时间累加，该对象的空闲时间
        idleTimes.add(p.getIdleTimeMillis());
        //等待时间累加，借用该对象等待的时间
        waitTimes.add(waitTime);

        // 无锁 乐观锁 最大等待时间
        long currentMax;
        do {
            //获取最大借用等待时间
            currentMax = maxBorrowWaitTimeMillis.get();
            if (currentMax >= waitTime) {
                //如果等待时间小于最大借用等待时间，跳出循环
                break;
            }
            //如果该时间大于最大借用等待时间才会执行这句话，将这个最大时间CAS为本次的等待时间，如果成功了，退出循环
        } while (!maxBorrowWaitTimeMillis.compareAndSet(currentMax, waitTime));
    }

    /**
     * 在对象返回池后更新统计数据
     * @param activeTime 返回对象被检出的时间(以毫秒计)
     */
    final void updateStatsReturn(long activeTime) {
        //返回次数+1
        returnedCount.incrementAndGet();
        //活动时间累加
        activeTimes.add(activeTime);
    }

    /**
     * 注销这池的MBean。
     */
    final void jmxUnregister() {
        //如果已经注册
        if (oname != null) {
            try {
                //注销
                ManagementFactory.getPlatformMBeanServer().unregisterMBean(
                        oname);
            } catch (MBeanRegistrationException e) {
                swallowException(e);
            } catch (InstanceNotFoundException e) {
                swallowException(e);
            }
        }
    }

    /**
     * 注册池到MBean服务
     * Registers the pool with the platform MBean server.
     * 这个注册名将是 jmxNameBase + jmsNamePrefix + i,i最小是大于或等于1的整数，注册失败返回null
     *
     * @param config Pool configuration
     * @param jmxNameBase default base JMX name for this pool
     * @param jmxNamePrefix name prefix
     * @return registered ObjectName, null if registration fails
     */
    private ObjectName jmxRegister(BaseObjectPoolConfig config,
            String jmxNameBase, String jmxNamePrefix) {
        ObjectName objectName = null;
        //获取平台MBean服务
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        //id
        int i = 1;
        boolean registered = false;
        //获取配置中的jmx名
        String base = config.getJmxNameBase();
        if (base == null) {
            //如果为空，则为参数
            base = jmxNameBase;
        }
        //没注册一直循环
        while (!registered) {
            try {
                ObjectName objName;
                // 跳过第一个池的数字后缀（也就是后缀为1时，没有后缀），以防只有一个，这样名称更干净。
                if (i == 1) {
                    objName = new ObjectName(base + jmxNamePrefix);
                } else {
                    objName = new ObjectName(base + jmxNamePrefix + i);
                }
                //注册该类和名字到MBean服务
                mbs.registerMBean(this, objName);
                //对象名
                objectName = objName;
                //已注册状态
                registered = true;
            } catch (MalformedObjectNameException e) {
                //如果 默认前缀和参数前缀相同，并且 参数名和配置中的名字相同，应该不会发生命名错误
                if (BaseObjectPoolConfig.DEFAULT_JMX_NAME_PREFIX.equals(
                        jmxNamePrefix) && jmxNameBase.equals(base)) {
                    //不应该发生的。如果是的话，跳过注册。
                    registered = true;
                } else {
                    // 必须是一个无效的名称。使用默认设置。
                    jmxNamePrefix =
                            BaseObjectPoolConfig.DEFAULT_JMX_NAME_PREFIX;
                    base = jmxNameBase;
                }
            } catch (InstanceAlreadyExistsException e) {
                // 增加索引，再试一次
                i++;
            } catch (MBeanRegistrationException e) {
                // 不应该发生的。如果是的话，跳过注册
                registered = true;
            } catch (NotCompliantMBeanException e) {
                // 不应该发生的。如果是的话，跳过注册
                registered = true;
            }
        }
        //返回
        return objectName;
    }

    /**
     * 获取异常的堆栈跟踪的字符串
     * @param e exception to trace
     * @return exception stack trace as a string
     */
    private String getStackTrace(Exception e) {
        // Need the exception in string form to prevent the retention of references to classes in the stack trace that could trigger a memory leak in a container environment.
        //在字符串格式中需要异常，以防止在堆栈跟踪中保留对类的引用，从而在容器环境中触发内存泄漏。
        //构建输出流
        Writer w = new StringWriter();
        PrintWriter pw = new PrintWriter(w);
        //输出堆栈追踪到该流
        e.printStackTrace(pw);
        //获取该流中的String格式的堆栈追踪返回
        return w.toString();
    }

    //内部类

    /**
     * 空闲对象驱逐器,
     * 继承自TimeTask,可定时一次性，或多次执行的线程
     *
     * @see GenericKeyedObjectPool#setTimeBetweenEvictionRunsMillis
     */
    class Evictor extends TimerTask {
        /**
         * Run pool maintenance.  Evict objects qualifying for eviction and then
         * ensure that the minimum number of idle instances are available.
         * Since the Timer that invokes Evictors is shared for all Pools but
         * pools may exist in different class loaders, the Evictor ensures that
         * any actions taken are under the class loader of the factory
         * associated with the pool.
         * 池维护运行。驱逐被驱逐的对象，然后 *确保空闲实例的最小数目是可用的。
         * 自调用调用者的计时器被共享给所有的池，但是 *池可能存在于不同的类装入器中，
         * 被逐出者确保 采取的任何行动都在工厂的类装载机下 与游泳池有关
         */
        @Override
        public void run() {
            //获取线程上下文类加载器,只是保存用，在下面的finally中恢复
            ClassLoader savedClassLoader =
                    Thread.currentThread().getContextClassLoader();
            try {
                //本身的工厂类加载器不为空
                if (factoryClassLoader != null) {
                    // 获取该类加载器，因为本身被包含在弱引用中，所以通过get方法取出
                    ClassLoader cl = factoryClassLoader.get();
                    if (cl == null) {
                        //池已被取消引用，类加载器GC将被取消。取消这个计时器，这样池也可以是GC。
                        cancel();
                        return;
                    }
                    //不为空，将该类加载器设置为到线程上下文类加载器
                    Thread.currentThread().setContextClassLoader(cl);
                }

                // 从池中驱逐
                try {
                    //池的驱逐方法
                    evict();
                } catch(Exception e) {
                    //忍受异常
                    swallowException(e);
                } catch(OutOfMemoryError oome) {
                    // 日志问题，但给驱逐者线程一个在错误是可恢复的情况下继续的机会
                    oome.printStackTrace(System.err);
                }
                // 重新创建空闲实例
                try {
                    //确保池中空闲实例个数
                    ensureMinIdle();
                } catch (Exception e) {
                    //忍受异常
                    swallowException(e);
                }
            } finally {
                //恢复之前的类加载器 CCL
                Thread.currentThread().setContextClassLoader(savedClassLoader);
            }
        }
    }

    /**
     * 维护一个关于唯一指标的缓存，并报告缓存值的统计信息
     */
    private class StatsStore {
        //原子值数组
        private final AtomicLong values[];
        //大小
        private final int size;
        //索引
        private int index;

        /**
         * 使用给定的缓存大小创建该类
         *
         * @param size number of values to maintain in the cache.
         */
        public StatsStore(int size) {
            this.size = size;
            values = new AtomicLong[size];
            for (int i = 0; i < size; i++) {
                //初始值都为-1
                values[i] = new AtomicLong(-1);
            }
        }

        /**
         * 增加一个值到这个缓存.如果这个缓存满了，现有的一个值将被新值替换,
         *
         * 每次设置值时累加索引，如果索引等于缓存大小后，置为0
         * @param value new value to add to the cache.
         */
        public synchronized void add(long value) {
            values[index].set(value);
            index++;
            if (index == size) {
                index = 0;
            }
        }

        /**
         * 返回缓存的平均值
         *
         * @return the mean of the cache, truncated to long
         */
        public long getMean() {
            //返回的平均值
            double result = 0;
            //计数器
            int counter = 0;
            //遍历
            for (int i = 0; i < size; i++) {
                //获取缓存值
                long value = values[i].get();
                //如果不为-1，也就是非默认值
                if (value != -1) {
                    //计数器累加
                    counter++;
                    //这个计算公式值得记一下，可以依次计算平均值，而不是一直累加后/数量
                    //不过不清楚这种计算方式和普通方式的异同
                    result = result * ((counter - 1) / (double) counter) +
                            value/(double) counter;
                }
            }
            return (long) result;
        }
    }

    /**
     * 空闲对象驱逐迭代器。保存对空闲对象的引用。
     */
    class EvictionIterator implements Iterator<PooledObject<T>> {
        //双向队列
        private final Deque<PooledObject<T>> idleObjects;
        //迭代器
        private final Iterator<PooledObject<T>> idleObjectIterator;

        /**
         * 构造方法
         * 通过提供的空闲实例双向队列创建一个驱逐迭代器
         * @param idleObjects underlying deque
         */
        EvictionIterator(final Deque<PooledObject<T>> idleObjects) {
            this.idleObjects = idleObjects;
            //如果是后进先出
            if (getLifo()) {
                //返回一个倒序排序（也就是后进先出）的迭代器
                idleObjectIterator = idleObjects.descendingIterator();
            } else {
                //返回一个迭代器
                idleObjectIterator = idleObjects.iterator();
            }
        }

        /**
         * 返回该迭代器引用的空闲对象双向队列
         * @return the idle object deque
         */
        public Deque<PooledObject<T>> getIdleObjects() {
            return idleObjects;
        }

        /** {@inheritDoc} 是否有下一元素*/
        @Override
        public boolean hasNext() {
            return idleObjectIterator.hasNext();
        }

        /** {@inheritDoc} 下一元素*/
        @Override
        public PooledObject<T> next() {
            return idleObjectIterator.next();
        }

        /** {@inheritDoc} 删除该元素*/
        @Override
        public void remove() {
            idleObjectIterator.remove();
        }

    }
    
    /**
     * 在池中管理对象的包装。
     *
     * GenericObjectPool和GenericKeyedObjectPool维护对所有对象的引用 ，使用map对对象进行管理。这个包装器类确保对象可以用作散列键（hashCode）。
     *
     * @param <T> type of objects in the pool
     */
    static class IdentityWrapper<T> {
        /** 被包装的对象实例 */
        private final T instance;
        
        /**
         * 通过对象创建一个该类实例.
         *
         * @param instance object to wrap
         */
        public IdentityWrapper(T instance) {
            this.instance = instance;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(instance);
        }

        @Override
        @SuppressWarnings("rawtypes")
        public boolean equals(Object other) {
            return ((IdentityWrapper) other).instance == instance;
        }
        
        /**
         * 获取这个包装对象
         * @return the wrapped object
         */
        public T getObject() {
            return instance;
        }
    }

}
