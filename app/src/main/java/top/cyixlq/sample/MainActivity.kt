package top.cyixlq.sample

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import top.cyixlq.liveeventbus.LiveEventBus
import java.lang.Thread.sleep
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private var times = 0
    private lateinit var observer: Observer<Int>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        LiveEventBus.get().with(Int::class.java).observe(this) {
            Log.d("TAG", "MainActivity observe接收到的值：$it")
        }
        observer = Observer<Int> {
            Log.d("TAG", "MainActivity observeForever接收到的值：$it")
        }
        LiveEventBus.get().with(Int::class.java).observeForever(observer)
        LiveEventBus.get().with("EVENT_KEY", String::class.java).observe(this) {
            Log.d("TAG", "MainActivity 通过String作为key订阅接收到的值：$it")
        }
        thread {
            while (Thread.currentThread().isAlive) {
                sleep(5000)
                LiveEventBus.get().with(Int::class.java).postEvent(times++)
            }
        }
    }

    fun onTvClick(v: View) {
        LiveEventBus.get().with(Int::class.java).postEvent(times++)
        LiveEventBus.get().with("EVENT_KEY", String::class.java).postEvent("event")
    }

    fun jump2Second(v: View) {
        startActivity(Intent(this, SecondActivity::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        LiveEventBus.get().with(Int::class.java).removeObserver(observer)
    }
}