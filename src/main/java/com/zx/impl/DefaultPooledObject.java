package com.zx.impl;

import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectState;
import org.apache.commons.pool2.TrackedUse;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Deque;

/**
 * 这个包装器用于跟踪额外的信息，比如状态，用于汇集对象。
 * <p>
 * 该类是线程安全的
 *
 * @param <T> 池对象的类型
 *
 * @version $Revision: $
 *
 * @since 2.0
 */
public class DefaultPooledObject<T> implements PooledObject<T> {

    //原始对象
    private final T object;
    //对象状态
    private PooledObjectState state = PooledObjectState.IDLE; // @GuardedBy("this") to ensure transitions are valid
    //创建时间
    private final long createTime = System.currentTimeMillis();
    //最后借用时间
    private volatile long lastBorrowTime = createTime;
    //最后使用时间
    private volatile long lastUseTime = createTime;
    //最后返回时间
    private volatile long lastReturnTime = createTime;
    //记录被遗弃的
    private volatile boolean logAbandoned = false;
    //借用的异常
    private volatile Exception borrowedBy = null;
    //使用的异常
    private volatile Exception usedBy = null;
    //借用总次数
    private volatile long borrowedCount = 0;

    /**
     * 构造函数
     *
     * @param object The object to wrap
     */
    public DefaultPooledObject(T object) {
        this.object = object;
    }

    @Override
    public T getObject() {
        return object;
    }

    @Override
    public long getCreateTime() {
        return createTime;
    }

    //获取活动时间
    @Override
    public long getActiveTimeMillis() {
        // 采用复制以避免线程问题
        long rTime = lastReturnTime;
        long bTime = lastBorrowTime;

        //如果 最后返回时间 > 最后借用时间
        if (rTime > bTime) {
            // 取时间差
            return rTime - bTime;
        } else {
            //否则，取当前时间 - 借用时间，也就是实时的活动时间
            return System.currentTimeMillis() - bTime;
        }
    }

    //获取空闲时间
    @Override
    public long getIdleTimeMillis() {
        //当前时间 - 最后借用时间 = 空闲时间
        final long elapsed = System.currentTimeMillis() - lastReturnTime;
     // 结果可能是负数，如果
     // - 另一个线程在计算窗口中更新lastReturnTime
     // - System.currenttimemillis()不是单调的(例如系统时间被设置回来)
     return elapsed >= 0 ? elapsed : 0;
    }

    @Override
    public long getLastBorrowTime() {
        return lastBorrowTime;
    }

    @Override
    public long getLastReturnTime() {
        return lastReturnTime;
    }

    /**
     * Get the number of times this object has been borrowed.
     * @return The number of times this object has been borrowed.
     * @since 2.1
     */
    public long getBorrowedCount() {
        return borrowedCount;
    }

    /**
     * 返回上次使用该对象的估计时间。
     * 如果对象类实现了{@link TrackedUse}接口，返回{@link TrackedUse#getLastUsed()}和{@link #getLastBorrowTime()}中最大的；
     * 否则该方法直接返回{@link #getLastBorrowTime()}
     */
    @Override
    public long getLastUsedTime() {
        if (object instanceof TrackedUse) {
            return Math.max(((TrackedUse) object).getLastUsed(), lastUseTime);
        } else {
            return lastUseTime;
        }
    }

    //比较
    @Override
    public int compareTo(PooledObject<T> other) {
        // 该对象的最后返回时间 - 比较对象的最后返回时间 的差值
        final long lastActiveDiff = this.getLastReturnTime() - other.getLastReturnTime();
        //如果是同时返回的
        if (lastActiveDiff == 0) {
            //确保自然顺序与equals是一致的
            //如果不同的对象具有相同的标识哈希代码，这将会崩溃。
            // see java.lang.Comparable Javadocs
            //code相同时，返回0，表示是同一个
            return System.identityHashCode(this) - System.identityHashCode(other);
        }
        //处理整数溢出,防止间隔时间过长，
        //返回不为0，都表示不是同一对象
        return (int)Math.min(Math.max(lastActiveDiff, Integer.MIN_VALUE), Integer.MAX_VALUE);
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        result.append("Object: ");
        result.append(object.toString());
        result.append(", State: ");
        synchronized (this) {
            result.append(state.toString());
        }
        return result.toString();
        // TODO add other attributes
    }

    //开始驱逐校验
    @Override
    public synchronized boolean startEvictionTest() {
        //如果状态为空闲，将状态改为驱逐校验中
        if (state == PooledObjectState.IDLE) {
            state = PooledObjectState.EVICTION;
            return true;
        }

        return false;
    }

    //结束驱逐校验
    @Override
    public synchronized boolean endEvictionTest(
            Deque<PooledObject<T>> idleQueue) {
        //如果状态为驱逐校验，状态改为空闲中
        if (state == PooledObjectState.EVICTION) {
            state = PooledObjectState.IDLE;
            return true;
        } else if (state == PooledObjectState.EVICTION_RETURN_TO_HEAD) {
            //如果状态是驱逐校验中（不在队列中，正在测试这个对象）
            //就将这个对象状态改为空闲，结束校验
            state = PooledObjectState.IDLE;
            //将这个对象放回队列，应该不会失败
            if (!idleQueue.offerFirst(this)) {
                // TODO - 不应该发生
            }
        }

        return false;
    }

    /**
     * 分配对象
     *
     * @return {@code true} 如果原始状态是 {@link PooledObjectState#IDLE IDLE}
     */
    @Override
    public synchronized boolean allocate() {
        //如果状态是空闲
        if (state == PooledObjectState.IDLE) {
            //状态改为被分配的（使用中）
            state = PooledObjectState.ALLOCATED;
            //最后借用时间为当前时间
            lastBorrowTime = System.currentTimeMillis();
            //最后使用时间等于 最后借用时间
            lastUseTime = lastBorrowTime;
            //借用次数累加
            borrowedCount++;
            if (logAbandoned) {
                //创建异常类，是这个类中的静态内部类
                borrowedBy = new AbandonedObjectCreatedException();
            }
            return true;
        } else if (state == PooledObjectState.EVICTION) {
            //如果状态是驱逐，修改为驱逐测试中
            // TODO 无论如何都要分配并忽略驱逐测试
            state = PooledObjectState.EVICTION_RETURN_TO_HEAD;
            return false;
        }
        // TODO if validating and testOnBorrow == true then pre-allocate for
        //如果是校验中，并且testOnBorrow == true ，那么预分配以提高性能
        return false;
    }

    /**
     * 解除分配对象，并设置状态为 {@link PooledObjectState#IDLE IDLE}
     * 如果它是目前是 {@link PooledObjectState#ALLOCATED ALLOCATED}.
     *
     * @return {@code true}如果状态是 {@link PooledObjectState#ALLOCATED ALLOCATED}
     */
    @Override
    public synchronized boolean deallocate() {
        //如果状态是分配的，或者是 返回到池中
        if (state == PooledObjectState.ALLOCATED ||
                state == PooledObjectState.RETURNING) {
            //修改为空闲状态
            state = PooledObjectState.IDLE;
            //修改最后返回时间
            lastReturnTime = System.currentTimeMillis();
            //修改借用异常
            borrowedBy = null;
            return true;
        }

        return false;
    }

    /**
     * 设置状态为无效{@link PooledObjectState#INVALID INVALID}
     */
    @Override
    public synchronized void invalidate() {
        state = PooledObjectState.INVALID;
    }

    //使用
    @Override
    public void use() {
        //设置最后使用时间
        lastUseTime = System.currentTimeMillis();
        //增加使用异常,使用该对象的最后一个代码是:
        usedBy = new Exception("The last code to use this object was:");
    }

    //打印堆栈追踪
    @Override
    public void printStackTrace(PrintWriter writer) {
        boolean written = false;
        Exception borrowedByCopy = this.borrowedBy;
        if (borrowedByCopy != null) {
            borrowedByCopy.printStackTrace(writer);
            written = true;
        }
        Exception usedByCopy = this.usedBy;
        if (usedByCopy != null) {
            usedByCopy.printStackTrace(writer);
            written = true;
        }
        if (written) {
            writer.flush();
        }
    }

    /**
     * Returns the state of this object.
     * @return state
     */
    @Override
    public synchronized PooledObjectState getState() {
        return state;
    }

    /**
     * Marks the pooled object as abandoned.
     */
    @Override
    public synchronized void markAbandoned() {
        state = PooledObjectState.ABANDONED;
    }

    /**
     * Marks the object as returning to the pool.
     */
    @Override
    public synchronized void markReturning() {
        state = PooledObjectState.RETURNING;
    }

    @Override
    public void setLogAbandoned(boolean logAbandoned) {
        this.logAbandoned = logAbandoned;
    }

    /**
     * 被遗弃对象创建的异常类
     * 静态内部类
     * 当这个对象被借用的时候,用于跟踪从池中获取对象的方式 (异常的堆栈跟踪将显示哪些代码借用了对象)
     */
    static class AbandonedObjectCreatedException extends Exception {

        private static final long serialVersionUID = 7398692158058772916L;

        /** 日期格式化 */
        //@GuardedBy("format")
        private static final SimpleDateFormat format = new SimpleDateFormat
            ("'Pooled object created' yyyy-MM-dd HH:mm:ss Z " +
             "'by the following code has not been returned to the pool:'");

        //创建时间
        private final long _createdTime;

        /**
         * 创建新实例
         * <p>
         * @see Exception#Exception()
         */
        public AbandonedObjectCreatedException() {
            super();
            _createdTime = System.currentTimeMillis();
        }

        // 重写getMessage以避免创建对象和格式化日期，除非实际使用日志消息。
        @Override
        public String getMessage() {
            String msg;
            synchronized(format) {
                msg = format.format(new Date(_createdTime));
            }
            return msg;
        }
    }
}
