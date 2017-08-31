package com.zx;

import org.apache.commons.pool2.KeyedPooledObjectFactory;
import org.apache.commons.pool2.ObjectPool;

import java.util.NoSuchElementException;

/**
 * 一个 ‘有键的’池接口
 * <p>
 * 有键的池，为每个key维护了一个value实例池
 * A keyed pool maintains a pool of instances for each key value.
 * <p>
 * 使用例子:（也就是从这个键池中取出对应key的一个实例来使用，如果失败就让这个实例无效，最后都将该实例返回对应key 的池中）
 * <pre style="border:solid thin; padding: 1ex;"
 * > Object obj = <code style="color:#00C">null</code>;
 * Object key = <code style="color:#C00">"Key"</code>;
 *
 * <code style="color:#00C">try</code> {
 *     obj = pool.borrowObject(key);
 *     <code style="color:#0C0">//...use the object...</code>
 * } <code style="color:#00C">catch</code>(Exception e) {
 *     <code style="color:#0C0">// invalidate the object</code>
 *     pool.invalidateObject(key, obj);
 *     <code style="color:#0C0">// do not return the object to the pool twice</code>
 *     obj = <code style="color:#00C">null</code>;
 * } <code style="color:#00C">finally</code> {
 *     <code style="color:#0C0">// make sure the object is returned to the pool</code>
 *     <code style="color:#00C">if</code>(<code style="color:#00C">null</code> != obj) {
 *         pool.returnObject(key, obj);
 *     }
 * }</pre>
 * <p>
 * 该类实现类可以选择每个键最多存储一个实例，或者每个键维护一个键池（本质上创建一个由 {@link ObjectPool pools}组成的{@link java.util.Map Map}）
 * <p>
 * See {@link org.apache.commons.pool2.impl.GenericKeyedObjectPool
 * GenericKeyedObjectPool} 一个实现类
 *
 * @param <K> 键的类型
 * @param <V> value的类型
 *
 * @see KeyedPooledObjectFactory
 * @see ObjectPool
 * @see org.apache.commons.pool2.impl.GenericKeyedObjectPool GenericKeyedObjectPool
 */
public interface KeyedObjectPool<K,V> {
    /**
     * 从池中根据指定key获取一个实例
     * <p>
     * 从这个方法返回的对象，是使用{@link KeyedPooledObjectFactory#makeObject }方法新建的，
     * 或者是一个空闲对象被{@link KeyedPooledObjectFactory#activateObject }方法激活，
     * 然后(可选)使用{@link KeyedPooledObjectFactory#validateObject }方法验证该对象可用性
     * <p>
     * 使用者必须使用{@link #returnObject }或{@link #invalidateObject }方法返还借用的对象，或在实现或子接口中定义的相关方法；
     *
     * using a <code>key</code> that is {@link Object#equals equivalent} to the one used to borrow the instance in the first place.
     * <p>
     * 当池被耗尽时，此方法的行为并没有严格指定(尽管它可以由实现指定)
     *
     * @param key 用于获取对象的键
     *
     * @return an instance from this pool.
     *
     * @throws IllegalStateException
     *              after {@link #close close} has been called on this pool
     * @throws Exception
     *              when {@link KeyedPooledObjectFactory#makeObject
     *              makeObject} throws an exception
     * @throws NoSuchElementException
     *              when the pool is exhausted and cannot or will not return
     *              another instance
     */
    V borrowObject(K key) throws Exception, NoSuchElementException, IllegalStateException;

    /**
     * 返回一个实例到池中。
     * 对象必须使用了{@link #borrowObject }方法，或使用在实现或子接口中定义的相关方法，这相当于第一个地方借用实例的值
     *
     * @param key the key used to obtain the object
     * @param obj a {@link #borrowObject borrowed} instance to be returned.
     *
     * @throws IllegalStateException
     *              如果试图返回一个对象到池中 除分配(即借用)外，其他任何状态。 试图多次返回对象或尝试 返回从未从池中借用的对象 ，将触发此异常。
     *
     * @throws Exception if an instance cannot be returned to the pool
     */
    void returnObject(K key, V obj) throws Exception;

    /**
     * 使池中的一个对象无效
     * <p>
     * 对象必须是使用{@link #borrowObject }方法或子类实现的相关方法使用一个key获得的，这相当于第一个地方借用实例的值
     * <p>
     * 当一个已经被借用的对象(由于一个异常或其他问题)被确定为无效时，这个方法应该被使用。
     *
     * @param key the key used to obtain the object
     * @param obj a {@link #borrowObject borrowed} instance to be returned.
     *
     * @throws Exception if the instance cannot be invalidated
     */
    void invalidateObject(K key, V obj) throws Exception;

    /**
     * 创建一个对象，使用{@link KeyedPooledObjectFactory }工厂类或其他实现，passivate（钝化）它；
     * 然后把它放到空闲对象池中；这个方法对于“预加载”一个具有空闲对象(可选操作)的池是有用的
     * @param key the key a new instance should be added to
     *
     * @throws Exception
     *              when {@link KeyedPooledObjectFactory#makeObject} fails.
     * @throws IllegalStateException
     *              after {@link #close} has been called on this pool.
     * @throws UnsupportedOperationException
     *              when this pool cannot add new idle objects.
     */
    void addObject(K key) throws Exception, IllegalStateException,
            UnsupportedOperationException;

    /**
     * 返回池中对应key当前的空闲对象数，
     * 如果该信息不可用，返回负数
     *
     * @param key the key to query
     * @return the number of instances corresponding to the given
     * <code>key</code> currently idle in this pool.
     */
    int getNumIdle(K key);

    /**
     * 返回池中对应key当前的活动对象数
     * 如果该信息不可用，返回负数
     *
     * @param key the key to query
     * @return the number of instances currently borrowed from but not yet
     * returned to the pool corresponding to the given <code>key</code>.
=     */
    int getNumActive(K key);

    /**
     * 返回池中当前空闲对象总数;
     * 如果这个信息不可用，返回负数
     * @return the total number of instances currently idle in this pool.
 =    */
    int getNumIdle();

    /**
     * * 返回池中当前活动对象总数;
     * 如果这个信息不可用，返回负数
     * @return the total number of instances current borrowed from this pool but
     * not yet returned.
     */
    int getNumActive();

    /**
     * 清空池，删除所有池的实例（可选操作）。
     * Clears the pool, removing all pooled instances (optional operation).
     *
     * @throws UnsupportedOperationException 如果实现类不支持该操作
     *
     * @throws Exception 如果池不能清除
     */
    void clear() throws Exception, UnsupportedOperationException;

    /**
     * 清楚指定的池，删除给定key池中所有的实例（可选操作）
     *
     * @param key the key to clear
     *
     * @throws UnsupportedOperationException 如果实现类不支持该操作
     *
     * @throws Exception if the key cannot be cleared
     */
    void clear(K key) throws Exception, UnsupportedOperationException;

    /**
     * 关闭此池，并释放与之相关的任何资源
     * <p>
     * 在调用该方法后，调用{@link #addObject }或{@link #borrowObject }方法将抛出{@link IllegalStateException}异常
     * <p>
     * 如果不是所有的资源都可以被释放，那么实现应该悄无声息地失败。
     */
    void close();
}
