/**
 * User: hadoop
 * Date: 2015/4/17 0017
 * Time: 19:25
 */

import com.eepoch.RotatingMap;

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

