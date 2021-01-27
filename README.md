LiveDataBus已经是一个老生常谈的话题了，但是我们今天搞点不一样(噱)的(头)。废话不多说，先上地址：[https://github.com/cyixlq/LiveEventBus](https://github.com/cyixlq/LiveEventBus)

先来说一说LiveDataBus的一些老生常谈的优势：
- 不需要像EventBus那样注册反注册，可以自动注册解注册，避免了忘记反注册导致内存泄漏
- 事件发送不是通过反射执行，但现在EventBus通过APT也可以实现
- 其它，还有吗？我暂时没想到

接着，我们看一看将LiveData打造成一款事件总线类型的框架首先要克服的一些问题：
- 在组件从非活跃状态变成活跃状态时，会将observe之前的value发送过来。（这个问题怎么说呢，你说它是问题，但是有的业务场景确实需要(sticky模式，但是并不需要每次从onStop之后恢复过来都发送一次)，你说他不是问题，但是大部分场景我们确实只需要在订阅事件之后的数据。
- LiveData数据丢失的问题。LiveData怎么判断一个组件是否在活跃状态？可以通过代码`mOwner.getLifecycle().getCurrentState().isAtLeast(STARTED)`知道，至少是STARTED状态的才是活跃状态。那么执行过哪些生命周期回调才算是STARTED状态呢？我们通过查看LifecycleRegistry类的getStateAfter方法可以知道，在onStart和onPause之间均是STARTED状态（这里为了我的排版我就不贴代码了，感兴趣的可以去自行查看）。因此，组件在onCreate/onStop的时候是收不到数据的，更不用说onDestroy。😁但是其实这也不算问题，因为LiveData认为看不到界面的时候更新界面是毫无意义的，并且LiveData本身就不是设计用来传送事件的，而是用来更新UI的，你要强行把它打造成事件总线框架那`谷歌`能有什么办法。`谷歌内心OS：你们这不是强人锁男吗？`。另外postValue的时候通过阅读代码逻辑可以发现(如果你不想读，那么你可以直接看postValue的注释文档)，如果你在短时间内多次postValue，那么最终只有最新的value才能发送出去。
- LiveData的粘性事件有点不合逻辑。其实这也不算...（打住，别说了，我知道了，这也不算问题）

接着我们来看一看以往我们为了将LiveData打造成一款简易LiveDataBus是怎么将这些问题克服的，上个链接，先看看简易版LiveDataBus[点我前往](https://tech.meituan.com/2018/07/26/android-livedatabus.html)：
- 在observe LiveData的时候反射修改对应ObserverWrapper中mLastVersion的值，让它和LiveData中的mVersion保持一致，这样在生命周期状态发生改变分发value的时候，不会因为订阅时的版本小于LiveData中的版本而被认为其数据需要更新。（那有的同学就会说了：啊啊啊~，那你用了反射会不会影响我做的响应时间要6，7s的APP的运行速度啊，毕竟大家都说反射性能都很低的！| 别急，我们接着往下看）
- 其它问题的解决与拓展相应实现起来就有点棘手，因为我们无法修改LiveData的源码，所以才有了我们今天的文章。我反手就是抄代码，一个Ctrl + c和一个Ctrl + v。谷歌，你的代码就是我的了！嘿嘿，想不到吧！

好了，现在是开始我们大展(抄)身(代)手(码)的时候了。LiveData其实最关键的就两个类，这更给了我们大展(抄)身(代)手(码)一个好机会。这两个类分别是：LiveData和SafeIterableMap。

抄LiveData无可厚非，可是这个SafeIterableMap是个什么东西？这个是谷歌团队使用链表的数据结构仿的一个Map，可以在遍历的时候安全地移除删除元素，这个LiveData所有的订阅者都是存在这个里面。有感兴趣的小伙伴可以[点我](https://blog.csdn.net/c6E5UlI1N/article/details/79608996)前往查看它的一个原理实现解析。

那又有同学要问了，为啥这个我还要抄呢？我一个import不行吗？也不是不行，但是这个类添加了一个`@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)`的注解，这个注解啥意思[点我前往查看](https://developer.android.google.cn/reference/androidx/annotation/RestrictTo.Scope?hl=en)。大意就是限制这个类只能在包名前缀相同的类中使用。如果你想强制使用也可以，直接import使用虽然会代码飘红，但是能编译通过，强迫症受不了还可以在对应的成员变量上或者方法上加上这个注解`@SuppressLint("RestrictedApi")`。但是这样做不累吗？我一个Ctrl+c和一个Ctrl+v然后修改一下把这个限制解除不香吗？另外指不定谷歌日后会对这个注解进行一些其他限制，比如直接崩溃来限制调用，又比如无法通过编译。

至此，我们可以开始定制我们的LiveDataBus，但是为了把谷歌的这个东西彻底变成我们的，索性我们名字也改一个，LiveData改成LiveEvent，打造的事件Bus我们就叫LiveEventBus。`ps:确实也不该叫LiveData了，因为我们定制化不少了`

1. 首先将SafeIterableMap的源码拷贝到项目中，去除`@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)`，不去也可以，因为LiveEvent类到时候和这个类包名前缀肯定是一样的，但是为了方便对外使用，去掉不香吗？
2. 将LiveData源码拷贝到项目中，并且更名为LiveEvent，修改构造方法名。发现还有ArchTaskExecutor和GenericLifecycleObserver两个类同样加了限制注解，没关系，将相应代码直接改成这个类中对应的代码：
    ```
	// 1.源代码
    class LifecycleBoundObserver extends ObserverWrapper implements GenericLifecycleObserver
    // 1.发现GenericLifecycleObserver中啥也没有，直接继承的LifecycleEventObserver，那我们直接改就好了
    class LifecycleBoundObserver extends ObserverWrapper implements LifecycleEventObserver

    // 2.源代码
    if (!ArchTaskExecutor.getInstance().isMainThread())
    // 2.直接看对应方法源码，发现由DefaultTaskExecutor类实现，直接用其实现进行替换
    if (Looper.getMainLooper().getThread() != Thread.currentThread())
    ```
3. 改造postValue方法，解决同一时间多次postValue只会发送最新的值，同时将修饰改成public。通过源码发现postValue通过Handler将任务post到主线程最终调用setValue，我们改造就是每一次执行postValue就判断当前调用线程是不是主线程，是主线程就可以直接调用setValue，否则使用Handler post到主线程执行setValue。同时去除没用的变量：`mDataLock`，`mPendingData`，`mPostValueRunnable`。再添加一个成员变量Handler，用于切换到主线程。为了将改(盗)造(版)进行到底，同时为了更符合语义，我们可以将postValue方法改名为postEvent：
    ```
    // 用于切换到主线程的Handler
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public void postEvent(T value) {
        // 如果在主线程
        if (Looper.getMainLooper().getThread() == Thread.currentThread())
            setValue(value);
        else
            mHandler.post(() -> setValue(value));
    }
    ```
4. 改造setValue方法，解决setValue只在onStart和onPause生命周期之间才能接收到value的问题。只需将上文提到的约束扩大到CREATED范围就行（onCreate和onStop之间），改成这个范围之后也能顺带解决Activity在onStop之后恢复又会重新发送一次事件的问题。修改LifecycleBoundObserver类中shouldBeActive方法，代码如下。同时外部其实用不到setValue方法，都可以直接通过postEvent来间接调用，所以可以修饰成private。
	```
    @Override
    boolean shouldBeActive() {
        return mOwner.getLifecycle().getCurrentState().isAtLeast(CREATED);
    }
    ```
5. 为了解决组件从非活跃状态切换到活跃状态会将observe之前的value发送过来，同时又为了拓展需要这种需求的情况，那我们直接打造一个可以控制是否需要sticky的模式吧。我们在ObserverWrapper中添加isStickyMode成员变量，同时为其添加构造方法，代码如下：
	````
    final boolean isStickyMode;

    ObserverWrapper(Observer<? super T> observer, final boolean isStickyMode) {
        mObserver = observer;
        this.isStickyMode = isStickyMode;
    }

    // 其它调用了ObserverWrapper构造方法的地方进行同理改造
    AlwaysActiveObserver(Observer<? super T> observer, boolean isStickyMode) {
        super(observer, isStickyMode);
    }
    LifecycleBoundObserver(@NonNull LifecycleOwner owner, Observer<? super T> observer, boolean isStickyMode) {
        super(observer, isStickyMode);
        mOwner = owner;
    }
    ````
    另外还需要添加一个observeSticky方法代表在以sticky模式观察，observe方法也需要改造，代表不以sticky模式观察。observeForever同理也进行改造。代码如下：
    ```
    @MainThread
    public void observe(@NonNull LifecycleOwner owner, @NonNull Observer<? super T> observer) {
        assertMainThread("observe");
        realObserve(owner, observer, false);
    }

    @MainThread
    public void observeSticky(@NonNull LifecycleOwner owner, @NonNull Observer<? super T> observer) {
        assertMainThread("observeSticky");
        realObserve(owner, observer, true);
    }

    @MainThread
    private void realObserve(@NonNull LifecycleOwner owner, @NonNull Observer<? super T> observer, boolean isStickyMode) {
        if (owner.getLifecycle().getCurrentState() == DESTROYED) {
            // ignore
            return;
        }
        LifecycleBoundObserver wrapper = new LifecycleBoundObserver(owner, observer, isStickyMode);
        ObserverWrapper existing = mObservers.putIfAbsent(observer, wrapper);
        if (existing != null && !existing.isAttachedTo(owner)) {
            throw new IllegalArgumentException("Cannot add the same observer"
                    + " with different lifecycles");
        }
        if (existing != null) {
            return;
        }
        owner.getLifecycle().addObserver(wrapper);
    }

     @MainThread
    public void observeForever(@NonNull Observer<? super T> observer) {
        assertMainThread("observeForever");
        realObserveForever(observer, false);
    }

    @MainThread
    public void observeForeverSticky(@NonNull Observer<? super T> observer) {
        assertMainThread("observeForeverSticky");
        realObserveForever(observer, true);
    }

    @MainThread
    private void realObserveForever(@NonNull Observer<? super T> observer, boolean isStickyMode) {
        AlwaysActiveObserver wrapper = new AlwaysActiveObserver(observer, isStickyMode);
        ObserverWrapper existing = mObservers.putIfAbsent(observer, wrapper);
        if (existing != null && existing instanceof LiveEvent.LifecycleBoundObserver) {
            throw new IllegalArgumentException("Cannot add the same observer"
                    + " with different lifecycles");
        }
        if (existing != null) {
            return;
        }
        wrapper.activeStateChanged(true);
    }
    ```
6. 有了isStickyMode这个变量之后，version的对比显得就不必要了，之前LiveData原有逻辑是组件从非活跃变为活跃状态就会调用dispatchingValue方法，而dispatchingValue方法又会调用considerNotify方法，considerNotify方法中对比Observer和LiveData中的版本，如果Observer中的mLastVersion小于LiveData中的mVersion说明Observer没有接收到最新的数据，那么便进行一次分发。现在我们有了isStickyMode变量控制，加上活跃状态的范围提升到onCreate到onStop之间(这个范围之外的生命周期只剩onDestroy，但是到了这个状态LiveData会自动将这个Observer给移除)，版本的对比就起不了什么作用，可以将相关变量与方法精简掉(还能防止发送事件次数过多，mVersion超过了int所能容纳的最大值导致的异常，虽然你的App也不可能有发送了20多亿次事件用户还没把你App关掉的情况)。considerNotify方法中通过判断版本是否忽略分发的代码逻辑可以改成value是否被赋值过来判断是否忽略分发，代码如下：
	```
    if (mData == NOT_SET) {
        // 从没发送过事件直接忽视分发事件
        return;
    }
    ```
7. 最后我们编写一个工具类，将使用方法封装起来：
	````
    public class LiveEventBus {

        private static final class SingleHolder {
            private static final LiveEventBus INSTANCE = new LiveEventBus();
        }

        public static LiveEventBus get() {
            return SingleHolder.INSTANCE;
        }

        private final ConcurrentHashMap<Object, LiveEvent<Object>> mEventMap;

        private LiveEventBus() {
            mEventMap = new ConcurrentHashMap<>();
        }

        public <T> LiveEvent<T> with(@NonNull final String key, @NonNull final Class<T> clazz) {
            return realWith(key, clazz);
        }

        public <T> LiveEvent<T> with(@NonNull final Class<T> clazz) {
            return realWith(null, clazz);
        }

        @SuppressWarnings("unchecked")
        private <T> LiveEvent<T> realWith(final String key, final Class<T> clazz) {
            final Object objectKey;
            if (key != null) {
                objectKey = key;
            } else if (clazz != null) {
                objectKey = clazz;
            } else {
                throw new IllegalArgumentException("key and clazz, one of which must not be null");
            }
            LiveEvent<Object> result = mEventMap.get(objectKey);
            if (result != null) return (LiveEvent<T>) result;
            synchronized (mEventMap) {
                result = mEventMap.get(objectKey);
                if (result == null) {
                    result = new LiveEvent<>();
                    mEventMap.put(objectKey, result);
                }
            }
            return (LiveEvent<T>) result;
        }
    }
    ````

最后，我们就用这几百行代码打造了一款非常牛(噱)逼(头)的事件总线框架，没有反射，不会影响你响应时间需要6，7s的APP的运行速度。你甚至还可以删除LiveEvent一些不需要的对外方法以及不再使用到的成员变量。为了进一步压缩代码行数，你甚至可以把注释也给删除了！我就这么干了，最后的LiveEvent只有两百多行代码。

当然，这还没完，我们还可以进一步优化一下，比如我们每次创建LiveEvent对象就会有一个Handler也被随之创建，我们完全可以共用一个Handler来将任务post到主线程，然后还有线程的判断这些方法我们也可以提取到一个公共类中，那么这个类我们不如叫它DefaultTaskExecutor吧！咦，这么巧，androidx包里面就有这个诶，那我们直接把它复制过来当工具类用吧。后面还有一个继承的父类也有限制注解？算了，不要它也不是不能用，那直接去了吧。多余的override注解也给去了，里面还有个用于切换到io线程的方法，emmm留着吧，万一以后要呢，只是线程名给它改一个我们自己想定义的名字(将盗版进行到底)...为了使用方便，把它改成单例吧，再把LiveEvent类中可以用到这个类方法的地方替换一下，完美收官！

最后讲讲LiveEventBus用法，那是相当简单，参照[MainActivity](https://github.com/cyixlq/LiveEventBus/blob/main/app/src/main/java/top/cyixlq/sample/MainActivity.kt)和[SecondActivity](https://github.com/cyixlq/LiveEventBus/blob/main/app/src/main/java/top/cyixlq/sample/SecondActivity.kt)

声明：本文可能会随着项目代码的改动而导致更新不及时，请以项目中代码为准！
