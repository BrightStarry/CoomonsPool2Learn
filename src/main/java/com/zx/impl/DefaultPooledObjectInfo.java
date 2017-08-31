package com.zx.impl;

import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObjectInfoMBean;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;

/**
 * 默认池对象信息
 * 对象的实现，用于通过JMX提供pooled对象的信息
 *
 * @since 2.0
 */
public class DefaultPooledObjectInfo implements DefaultPooledObjectInfoMBean {
    //池对象引用
    private final PooledObject<?> pooledObject;

    /**
     * 创建一个新实例使用给定的池引用
     *
     * @param pooledObject The pooled object that this instance will represent
     */
    public DefaultPooledObjectInfo(PooledObject<?> pooledObject) {
        this.pooledObject = pooledObject;
    }

    @Override
    public long getCreateTime() {
        return pooledObject.getCreateTime();
    }

    @Override
    public String getCreateTimeFormatted() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");
        return sdf.format(Long.valueOf(pooledObject.getCreateTime()));
    }

    @Override
    public long getLastBorrowTime() {
        return pooledObject.getLastBorrowTime();
    }

    @Override
    public String getLastBorrowTimeFormatted() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");
        return sdf.format(Long.valueOf(pooledObject.getLastBorrowTime()));
    }

    @Override
    public String getLastBorrowTrace() {
        StringWriter sw = new StringWriter();
        pooledObject.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    @Override
    public long getLastReturnTime() {
        return pooledObject.getLastReturnTime();
    }

    @Override
    public String getLastReturnTimeFormatted() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");
        return sdf.format(Long.valueOf(pooledObject.getLastReturnTime()));
    }

    @Override
    public String getPooledObjectType() {
        return pooledObject.getObject().getClass().getName();
    }

    @Override
    public String getPooledObjectToString() {
        return pooledObject.getObject().toString();
    }

    @Override
    public long getBorrowedCount() {
        // TODO 化简这一次，将getBorrowedCount添加到PooledObject中
        if (pooledObject instanceof DefaultPooledObject) {
            return ((DefaultPooledObject<?>) pooledObject).getBorrowedCount();
        } else {
            return -1;
        }
    }
}
