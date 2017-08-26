#### Commons-pool2学习
使用Commons-pool2.jar构建一个线程池;

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