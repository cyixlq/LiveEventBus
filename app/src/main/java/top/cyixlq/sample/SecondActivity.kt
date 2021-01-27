package top.cyixlq.sample

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.Observer
import top.cyixlq.liveeventbus.LiveEventBus

class SecondActivity : AppCompatActivity() {

    private lateinit var observer: Observer<Int>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_second)
        LiveEventBus.get().with(Int::class.java).observeSticky(this) {
            Log.d("TAG", "SecondActivity observeSticky接收到的值：$it")
        }
        observer = Observer<Int> {
            Log.d("TAG", "SecondActivity observeForeverSticky接收到的值：$it")
        }
        LiveEventBus.get().with(Int::class.java).observeForeverSticky(observer)
    }

    override fun onDestroy() {
        super.onDestroy()
        LiveEventBus.get().with(Int::class.java).removeObserver(observer)
    }
}