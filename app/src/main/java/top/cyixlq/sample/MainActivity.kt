package top.cyixlq.sample

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import top.cyixlq.liveeventbus.LiveEventBus
import java.lang.Thread.sleep
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private var times = 0
    private var observer: Observer<Int>

    init {
        LiveEventBus.get().with(Int::class.java).postEvent(-1)
        LiveEventBus.get().with(Int::class.java).observe(this) {
            Log.d(TAG, "MainActivity onCreate observe接收到的值：$it，state：${lifecycle.currentState}")
        }
        LiveEventBus.get().with(Int::class.java).observeSticky(this) {
            Log.d(TAG, "MainActivity onCreate observeSticky接收到的值：$it，state：${lifecycle.currentState}")
        }
        observer = Observer<Int> {
            Log.d(TAG, "MainActivity onCreate observeForever接收到的值：$it，state：${lifecycle.currentState}")
        }
        LiveEventBus.get().with(Int::class.java).observeForever(observer)
        LiveEventBus.get().with("EVENT_KEY", String::class.java).observe(this) {
            Log.d(TAG, "MainActivity onCreate 通过String作为key订阅接收到的值：$it")
        }
        LiveEventBus.get().with(Int::class.java).observeCustom(this, Lifecycle.State.RESUMED) {
            Log.d(TAG, "MainActivity onResume observe接收到的值：$it，state：${lifecycle.currentState}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        thread {
            while (Thread.currentThread().isAlive) {
                // 直接通过数据类型来发送事件
                LiveEventBus.get().with(Int::class.java).postEvent(times++)
                sleep(5000)
            }
        }
    }

    fun onTvClick(v: View) {
        LiveEventBus.get().with(Int::class.java).postEvent(times++)
        // 通过key发送事件
        LiveEventBus.get().with("EVENT_KEY", String::class.java).postEvent("event")
    }

    fun jump2Second(v: View) {
        startActivity(Intent(this, SecondActivity::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        LiveEventBus.get().with(Int::class.java).removeObserver(observer)
    }

    companion object {
        const val TAG = "TAG"
    }
}