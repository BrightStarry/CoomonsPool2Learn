package com.zx.impl;

/**
 * JMX中的MBean实现的接口
 * 方法如下：
 * 获取对象的创建时间/格式化后时间池
 * 获取池对象最后一次被借用的时间/格式化后时间
 * 获取池对象最后一次被借用的stack trace(堆栈追踪，可以显示是哪些代码最后使用了池对象)
 * 获取池对象最后一次被返回(返回池中)的时间/格式化后日期
 * 获取池对象的类名
 * 获取池对象的toString结果
 * 获取池对象已被借用的总次数
 */
public interface DefaultPooledObjectInfoMBean {
    /**
     * Obtain the time (using the same basis as
     * {@link System#currentTimeMillis()}) that pooled object was created.
     *
     * @return The creation time for the pooled object
     */
    long getCreateTime();

    /**
     * Obtain the time that pooled object was created.
     *
     * @return The creation time for the pooled object formated as
     *         <code>yyyy-MM-dd HH:mm:ss Z</code>
     */
    String getCreateTimeFormatted();

    /**
     * Obtain the time (using the same basis as
     * {@link System#currentTimeMillis()}) the polled object was last borrowed.
     *
     * @return The time the pooled object was last borrowed
     */
    long getLastBorrowTime();

    /**
     * Obtain the time that pooled object was last borrowed.
     *
     * @return The last borrowed time for the pooled object formated as
     *         <code>yyyy-MM-dd HH:mm:ss Z</code>
     */
    String getLastBorrowTimeFormatted();

    /**
     * Obtain the stack trace recorded when the pooled object was last borrowed.
     *
     * @return The stack trace showing which code last borrowed the pooled
     *         object
     */
    String getLastBorrowTrace();


    /**
     * Obtain the time (using the same basis as
     * {@link System#currentTimeMillis()})the wrapped object was last returned.
     *
     * @return The time the object was last returned
     */
    long getLastReturnTime();

    /**
     * Obtain the time that pooled object was last returned.
     *
     * @return The last returned time for the pooled object formated as
     *         <code>yyyy-MM-dd HH:mm:ss Z</code>
     */
    String getLastReturnTimeFormatted();

    /**
     * Obtain the name of the class of the pooled object.
     *
     * @return The pooled object's class name
     *
     * @see Class#getName()
     */
    String getPooledObjectType();

    /**
     * Provides a String form of the wrapper for debug purposes. The format is
     * not fixed and may change at any time.
     *
     * @return A string representation of the pooled object
     *
     * @see Object#toString()
     */
    String getPooledObjectToString();

    /**
     * Get the number of times this object has been borrowed.
     * @return The number of times this object has been borrowed.
     * @since 2.1
     */
    long getBorrowedCount();
}
