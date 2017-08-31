package com.zx.impl;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Timer;
import java.util.TimerTask;

/**
 * 为所有池提供一个共享的闲置对象清除计时器。 这个类包装了标准{@link Timer}，并记录了使用它的池的数量。
 * 如果没有池使用这个计时器, 它是取消的.这个可以防止线程被运行,在应用程序服务器环境，可能导致内存引导和/或防止应用程序关闭或重新加载。
 * <p>
 * 这个类有一个包范围，可以防止它包含在池公共API中。
 * 下面的类声明应该不会被更改为public。
 * <p>
 * 这个类是线程安全的。
 * 该类组合了Timer.
 *
 */
class EvictionTimer {

    /** Timer 实例*/
    private static Timer _timer; //@GuardedBy("EvictionTimer.class")

    /** 静态使用计数跟踪器 ,使用统计 */
    private static int _usageCount; //@GuardedBy("EvictionTimer.class")

    /** 防止实例化-私有化构造方法 */
    private EvictionTimer() {
        // Hide the default constructor
    }

    /**
     * 增加指定的驱逐任务到这个定时器。
     * 在调用此方法时增加的任务 必须 回调 {@link #cancel(TimerTask)}来取消任务，防止应用服务器环境中的内存和/或线程泄漏。
     * @param task      定时任务
     * @param delay     在执行任务之前延迟几毫秒
     * @param period    执行之间的毫秒数
     */
    static synchronized void schedule(TimerTask task, long delay, long period) {
        //如果定时器为空
        if (null == _timer) {
            // 强制使用上下文类创建新的计时器线程
            // 设置加载这个lib的类加载器为加载器

            /**
             * AccessController.doPrivileged()方法：
             * 一个调用者在调用doPrivileged方法时，可被标识为 "特权"。
             * 在做访问控制决策时，如果checkPermission方法遇到一个通过doPrivileged调用而被表示为 "特权"的调用者，
             * 并且没有上下文自变量，checkPermission方法则将终止检查。如果那个调用者的域具有特定的许可，
             * 则不做进一步检查，checkPermission安静地返回，表示那个访问请求是被允许的；
             * 如果那个域没有特定的许可，则象通常一样，一个异常被抛出。
             */
            //获取TCCL（线程上下文类加载器）
            ClassLoader ccl = AccessController.doPrivileged(
                    new PrivilegedGetTccl());
            try {
                AccessController.doPrivileged(new PrivilegedSetTccl(
                        EvictionTimer.class.getClassLoader()));
                _timer = AccessController.doPrivileged(new PrivilegedNewEvictionTimer());
            } finally {
                AccessController.doPrivileged(new PrivilegedSetTccl(ccl));
            }
        }
        //使用次数累加
        _usageCount++;
        //开启定时器
        _timer.schedule(task, delay, period);
    }

    /**
     * 从计时器中删除指定的驱逐任务。
     * @param task      要调度的任务
     */
    static synchronized void cancel(TimerTask task) {
        task.cancel();
        //使用数递减
        _usageCount--;
        //如果使用数为0了，关闭定时器
        if (_usageCount == 0) {
            _timer.cancel();
            _timer = null;
        }
    }

    /**
     * 静态内部类
     * {@link PrivilegedAction} 用于获取ContextClassLoader
     */
    private static class PrivilegedGetTccl implements PrivilegedAction<ClassLoader> {

        /**
         * 获取当前线程上下文类加载器
         * {@inheritDoc}
         */
        @Override
        public ClassLoader run() {
            return Thread.currentThread().getContextClassLoader();
        }
    }

    /**
     * 静态内部类
     * {@link PrivilegedAction} 用于设置ContextClassLoader
     */
    private static class PrivilegedSetTccl implements PrivilegedAction<Void> {

        /** 类加载器 */
        private final ClassLoader cl;

        /**
         * 创建新的该类，使用给定的类加载器
         * @param cl ClassLoader to use
         */
        PrivilegedSetTccl(ClassLoader cl) {
            this.cl = cl;
        }

        /**
         * 将当前线程上下文类加载器设置为给定的类加载器
         * {@inheritDoc}
         */
        @Override
        public Void run() {
            Thread.currentThread().setContextClassLoader(cl);
            return null;
        }
    }

    /**
     * {@link PrivilegedAction}用于创建一个新的计时器. 使用特权操作创建计时器意味着关联的线程不继承当前的访问控制上下文。.
     * 在容器环境中，继承当前访问控制上下文可能会导致对线程上下文类加载器的引用，这将是内存泄漏。
     */
    private static class PrivilegedNewEvictionTimer implements PrivilegedAction<Timer> {

        /**
         * 创建一个定时器
         * {@inheritDoc}
         */
        @Override
        public Timer run() {
            return new Timer("commons-pool-EvictionTimer", true);
        }
    }
}
