/**
 * User: hadoop
 * Date: 2015/4/17 0017
 * Time: 19:21
 */
import com.eepoch.*;
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
