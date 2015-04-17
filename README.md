TimeCacheMap RotatingMap源码分析
===
从 `TimeCacheMap` 和 `TimeCacheMap`分析如何设计一个Map，可以保存那些最近活跃的对象，并且可以自动删除那些已经过期的对象。

## 从 TimeCacheMap 分析设计

### 核心的数据结构
* `LinkedList<HashMap<K, V>> _buckets`:  创建了*numBuckets* 个`HashMap`来存储数据
* `Thread _cleaner` : 利用一个 **daemon** 线程来清理过期的数据
* `ExpiredCallback _callback` : 对于过期的数据的回调的方法，可以对过期的数据保存在HBase或者File等存储，也可以传入`null`，直接扔掉过期的数据。

### 辅助数据结构
* `static final int DEFAULT_NUM_BUCKETS = 3;` :  默认创建3个`HashMap`
* `final Object _lock = new Object();`: 同步锁，用于多线程安全


### 方法
除了对象初始化和`cleanup()`方法，都是对`_buckets`的同步操作，逻辑很简单，详细看源码。
* 对象初始化：初始化`_bucket`, 启动**daemon**线程来清理过期对象。
*  `boolean containsKey(K key)`: 在3个桶中寻找
*  `V get(K key)`：在3个桶中寻找
*  `void put(K key, V value)`：放入第1个桶中，移除其他2个桶的`key`数据
*  `Object remove(K key)`：在3个桶中寻找
*  `int size()`：把3个桶中的size都加起来
*  `void cleanup()`：中断清理线程中的sleep，`_cleaner`线程会抛出异常，然后`_cleaner`线程死掉，不再清理过期数据了

### 清理过期对象的线程
TimeCacheMap的初始化code
``` java
public TimeCacheMap(int expirationSecs, int numBuckets, ExpiredCallback<K, V> callback) {
        if(numBuckets<2) {
            throw new IllegalArgumentException("numBuckets must be >= 2");
        }
        //构造函数中，按照桶的数量，初始桶
        _buckets = new LinkedList<HashMap<K, V>>();
        for(int i=0; i<numBuckets; i++) {
            _buckets.add(new HashMap<K, V>());
        }

        _callback = callback;
        final long expirationMillis = expirationSecs * 1000L;
        final long sleepTime = expirationMillis / (numBuckets-1);
        _cleaner = new Thread(new Runnable() {
            public void run() {
                try {
                    while(true) {
                        Map<K, V> dead = null;
                        Time.sleep(sleepTime);
                        synchronized(_lock) {
	                        //删掉最后一个桶，在头补充一个新的桶，最后一个桶的数据是最旧的
                            dead = _buckets.removeLast();
                            _buckets.addFirst(new HashMap<K, V>());
                        }
                        if(_callback!=null) {
                            for(Entry<K, V> entry: dead.entrySet()) {
                                _callback.expire(entry.getKey(), entry.getValue());
                            }
                        }
                    }
                } catch (InterruptedException ex) {
                }
            }
        });
        //作为守护线程运行，一旦主线程不在，这个线程自动结束
        _cleaner.setDaemon(true);
        _cleaner.start();
    }
```
### 性能分析
* 方法**get, put, remove, containsKey, and size** 时间复杂度：**O(numBuckets) **.
* The advantage of this design is that the **expiration thread** only locks the object for **O(1)** time。（**O(1) **的时间清理过期的对象，即删除最后一个桶，在`LinkedList`头上插入一个新的桶）
* 算法会判断 between expirationSecs and  expirationSecs * (1 + 1 / (numBuckets-1)) 是过期的对象。这种是如何发生的呢？
 >  * 假设_cleaner线程刚刚清理数据，put函数调用发生将key放入桶中，那么一条数据的超时时间为：
 >  expirationSecs / (numBuckets-1) * numBuckets = expirationSecs * (1 + 1 / (numBuckets-1))
* 然而，假设put函数调用刚刚执行结束，_cleaner线程就开始清理数据，那么一条数据的超时时间为：
expirationSecs / (numBuckets-1) * numBuckets - expirationSecs / (numBuckets-1) = expirationSecs


## RotatingMap
> **RotatingMap** 对于 **TimeCacheMap** 的改进：
> * 去掉了自动清理的线程，让用户自己去控制清理过期的数据，控制清理数据用`rotate()`方法。
> * `get`, `put`等方法都不加锁了，需要用户自己控制锁。（提供了更大的自由度，让开发者去控制这个数据结构。）
> * **TimeCacheMap**是线程安全的，**RotatingMap** 是线程不安全的。
> * **Storm** 弃用 **TimeCacheMap** ，支持 non-threaded **RotatingMap**.

rotate() 方法：
``` java
public Map<K, V> rotate() {
        Map<K, V> dead = _buckets.removeLast();
        _buckets.addFirst(new HashMap<K, V>());
        if(_callback!=null) {
            for(Entry<K, V> entry: dead.entrySet()) {
                _callback.expire(entry.getKey(), entry.getValue());
            }
        }
        return dead;
    }
```

## 编程示例
利用这两个`Map`实现在多线程实现：
- 线程A ：放数据
- 线程B：取数据
- 线程C：清理过期数据

### 利用`TimeCacheMap`实现
``` java
public class TimeCacheMapMain {
	TimeCacheMap<String, Integer> tcmap = new TimeCacheMap<String, Integer>(60, new CallBack());

	static class CallBack implements TimeCacheMap.ExpiredCallback<String, Integer> {
		@Override
		public void expire(String key, Integer val) {
			// null
		}
	}
	
	public void main(String[] args) {
		// 启动2个Thread
		// Thread A: 直接往Map里面放数据
		// Thread B: 读取Map的数据
		// Thread C: 不需要了，TimeCacheMap 自带清理线程。
	}
}
```

### 利用`RotatingMap`实现
``` java
public class RotatingMapMain {
	static RotatingMap<String, Integer> rmap = new RotatingMap<String, Integer>(3, new CallBack());
	static class CallBack implements RotatingMap.ExpiredCallback<String, Integer> {
		@Override
		public void expire(String key, Integer val) {
			// null
		}
	}

	public static void main(String[] args) throws InterruptedException {
		final Object lock = new Object();
		// 启动3个Thread
		// Thread A: 获取lock，往Map里面放数据
		// Thread B: 获取lock,读取Map的数据
		// Thread cleaner : 获取lock,调用rotate()方法清理数据。这里是自己控制处理时间。
		Thread cleaner = new Thread(new Runnable() {
			final long expirationMillis = 60 * 1000L;
			final long sleepTime = expirationMillis / 2;
			@Override
			public void run() {
				try {
					while (true) {
						Thread.sleep(sleepTime);
						synchronized (lock) {
							rmap.rotate();
						}
					}
				} catch (InterruptedException ex) {
				}
			}
		});
		cleaner.setDaemon(true);
		cleaner.start();
		Thread.sleep(120 * 1000);
	}
}
```

Reference: [hulu校友](http://www.cnblogs.com/yanghuahui/p/3677117.html)

 