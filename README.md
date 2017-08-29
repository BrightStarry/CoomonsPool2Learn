#### Commons-pool2学习
使用Commons-pool2.jar构建一个线程池;


#### 使用教程
1. 导入依赖
~~~
<dependency>
      <groupId>org.apache.commons</groupId>
      <artifactId>commons-pool2</artifactId>
      <version>2.4.2</version>
</dependency>
~~~

2. PooledObjectFactory接口，表示了池中对象的生命周期，定义了如下若干方法
    * 生成对象:makeObject() 创建一个实例，该实例可以由池提供，并将其包装在PoolObject对象中由池管理
    * 销毁对象:destroyObject() 销毁池中不再需要的实例
    * 验证对象:validateObject() 确保实例是安全的，可由池提供
    * 激活对象:activateObject() 重新初始化线程池返回的实例
    * 卸载对象:passivateObject() 取消初始化，将一个实例返回到空闲对象池

3. BasePooledObjectFactory抽象类，实现了PooledObjectFactory接口，并在makeObject()方法中使用了模版方法设计模式，
需要自行实现create()方法创建对象实例，并实现warp()方法将对象实例包装成PoolObject对象；

4. 那么要实现一个线程池，可以选择自定义一个类实现PooledObjectFactory接口，或者继承BasePooledObjectFactory；
这两者真的没什么大差别

5. 

#### JMX(Java Management Extensions)java管理扩展:简单说两个
MBean（Managed Bean，托管的实例）：就是一个Java Bean，可以通过反射或自省获取属性值。不过MBean更复杂一点，通过公共方法和遵守特定的设计模式  
封装了属性和操作，以便管理程序进行管理；只支持基本类型和简单引用类型，如String；MXBean则可以使用复杂的引用类型
MBeanServer（管理实例服务）：管理MBean，实现了一种注册机制，在外界可以通过名字来获取MBean实例


#### 源码分析
* DefaultPoolObjectInfoBean接口-默认池对象信息bean:这个接口定义了关于通过JMX池化对象的信息,应该是池对象都需要实现该接口
    * 获取对象的创建时间/格式化后时间池
    * 获取池对象最后一次被借用的时间/格式化后时间
    * 获取池对象最后一次被借用的stack trace(堆栈追踪，可以显示是哪些代码最后使用了池对象)
    * 获取池对象最后一次被返回(返回池中)的时间/格式化后日期
    * 获取池对象的类名
    * 获取池对象的toString结果
    * 获取池对象已被借用的总次数
* EvictionPolicy接口-驱逐策略:提供自定义的驱逐策略，有一个默认的实现类(DefaultEvictionPolicy类),也可自定义实现类
    * evict()方法：使用该方法确认池中空闲对象是否应该被驱逐,传入EvictionConfig、PooledObject<T>、空闲对象数（包括该对象）,返回boolean
* GenericKeyedObjectPoolMXBean接口：定义JMX提供的方法
    * 暴露getter方法给配置设置
    * 暴露getter方法给监视属性
* GenericObjectPoolMXBean接口:定义JMX提供的方法 
    * 暴露getter方法给配置设置
    * 暴露getter方法给监视属性
* BaseGenericObjectPool类-基础通用对象池(线程安全的)：基础类，提供了GenericObjectPool接口和GenericKeyedObjectPool接口的通用方法；主要是为了减少这两个连接池间的代码重复;
    * 常量：历史数据缓存大小
    * 配置属性：最大对象数量、对象耗尽时是否阻塞、最大等待时间、是否后进先出、是否公平、是否创建时测试可用性、是否借用时测试可用性、
        是否返回时测试可用性、是否空闲时测试可用性、多久进行一次驱逐、测试可用性时每次检查几个对象、至少空闲多久才能被驱逐、空闲后多久被驱逐
        evictionPolicy（驱逐策略实现类）
    * 内部（主要状态）属性：closeLock（关闭锁对象）、closed（是否关闭）、evictionLock(驱逐锁)、
        evictor（内部类、驱逐线程）、evictionIterator（内部类，驱逐迭代器）、factoryClassLoader（工厂类加载器,弱引用）
    * 监控(主要JMX)属性：对象名、创建时堆栈轨迹、借用总数、返回总数、创建总数、销毁总数、销毁的驱逐总数、销毁的借用校验总数（？？）、
        活动时间、空闲时间、等待时间、最大借用等待时间、忍耐异常监听器
    
