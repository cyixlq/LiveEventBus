package top.cyixlq.liveeventbus;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;

import org.jetbrains.annotations.NotNull;

import java.util.Iterator;
import java.util.Map;

import static androidx.lifecycle.Lifecycle.State.CREATED;
import static androidx.lifecycle.Lifecycle.State.DESTROYED;

public class LiveEvent<T> {
    private static final Object NOT_SET = new Object();
    private final SafeIterableMap<Observer<? super T>, ObserverWrapper> mObservers = new SafeIterableMap<>();
    private volatile Object mData = NOT_SET;
    private boolean mDispatchingValue;
    final static int START_VERSION = -1;
    @SuppressWarnings("FieldCanBeLocal")
    private boolean mDispatchInvalidated;
    private int mVersion = START_VERSION;

    private void considerNotify(ObserverWrapper observer) {
        if (!observer.mActive) {
            return;
        }
        if (!observer.shouldBeActive()) {
            observer.activeStateChanged(false);
            return;
        }
        if (observer.mLastVersion >= mVersion) {
            return;
        }
        observer.mLastVersion = mVersion;
        //noinspection unchecked
        observer.mObserver.onChanged((T) mData);
    }

    private void dispatchingValue(@Nullable ObserverWrapper initiator) {
        if (mDispatchingValue) {
            mDispatchInvalidated = true;
            return;
        }
        mDispatchingValue = true;
        do {
            mDispatchInvalidated = false;
            if (initiator != null) {
                considerNotify(initiator);
                initiator = null;
            } else {
                for (Iterator<Map.Entry<Observer<? super T>, ObserverWrapper>> iterator =
                     mObservers.iteratorWithAdditions(); iterator.hasNext(); ) {
                    considerNotify(iterator.next().getValue());
                    if (mDispatchInvalidated) {
                        break;
                    }
                }
            }
        } while (mDispatchInvalidated);
        mDispatchingValue = false;
    }

    @MainThread
    public void observe(@NonNull LifecycleOwner owner, @NonNull Observer<? super T> observer) {
        assertMainThread("observe");
        realObserveCustom(owner, Lifecycle.State.CREATED, observer, false);
    }

    @MainThread
    public void observeSticky(@NonNull LifecycleOwner owner, @NonNull Observer<? super T> observer) {
        assertMainThread("observeSticky");
        realObserveCustom(owner, Lifecycle.State.CREATED, observer, true);
    }

    @MainThread
    public void observeCustom(@NonNull LifecycleOwner owner, @NonNull Lifecycle.State state, @NonNull Observer<? super T> observer) {
        assertMainThread("observerCustom");
        realObserveCustom(owner, state, observer, false);
    }

    @MainThread
    public void observeCustomSticky(@NonNull LifecycleOwner owner, @NonNull Lifecycle.State state, @NonNull Observer<? super T> observer) {
        assertMainThread("observerCustomSticky");
        realObserveCustom(owner, state, observer, true);
    }

    private void realObserveCustom(@NonNull LifecycleOwner owner, @NonNull Lifecycle.State state, @NonNull Observer<? super T> observer, boolean isStickyMode) {
        if (owner.getLifecycle().getCurrentState() == DESTROYED) {
            return;
        }
        CustomActiveObserver wrapper = new CustomActiveObserver(owner, state, observer, isStickyMode);
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
        if (existing != null && (existing instanceof LiveEvent.CustomActiveObserver)) {
            throw new IllegalArgumentException("Cannot add the same observer"
                    + " with different lifecycles");
        }
        if (existing != null) {
            return;
        }
        wrapper.activeStateChanged(true);
    }

    @MainThread
    public void removeObserver(@NonNull final Observer<? super T> observer) {
        assertMainThread("removeObserver");
        ObserverWrapper removed = mObservers.remove(observer);
        if (removed == null) {
            return;
        }
        removed.detachObserver();
        removed.activeStateChanged(false);
    }

    @SuppressWarnings("WeakerAccess")
    @MainThread
    public void removeObservers(@NonNull final LifecycleOwner owner) {
        assertMainThread("removeObservers");
        for (Map.Entry<Observer<? super T>, ObserverWrapper> entry : mObservers) {
            if (entry.getValue().isAttachedTo(owner)) {
                removeObserver(entry.getKey());
            }
        }
    }

    public void postEvent(T value) {
        if (DefaultTaskExecutor.getInstance().isMainThread())
            setValue(value);
        else
            DefaultTaskExecutor.getInstance().postToMainThread(() -> setValue(value));
    }

    @MainThread
    private void setValue(T value) {
        assertMainThread("setValue");
        mVersion++;
        mData = value;
        dispatchingValue(null);
    }

    private abstract class ObserverWrapper {
        final Observer<? super T> mObserver;
        boolean mActive;
        final boolean isStickyMode;
        int mLastVersion = START_VERSION;

        ObserverWrapper(Observer<? super T> observer, final boolean isStickyMode) {
            mObserver = observer;
            this.isStickyMode = isStickyMode;
        }

        abstract boolean shouldBeActive();

        boolean isAttachedTo(LifecycleOwner owner) {
            return false;
        }

        void detachObserver() {
        }

        void activeStateChanged(boolean newActive) {
            if (newActive == mActive) {
                return;
            }
            mActive = newActive;
            if (mActive && isStickyMode) {
                dispatchingValue(this);
            }
        }
    }

    private class CustomActiveObserver extends ObserverWrapper implements LifecycleEventObserver {

        @NonNull
        final Lifecycle.State mState;

        @NonNull
        final LifecycleOwner mOwner;

        CustomActiveObserver(@NonNull LifecycleOwner owner, @NonNull Lifecycle.State state, Observer<? super T> observer, boolean isStickyMode) {
            super(observer, isStickyMode);
            this.mOwner = owner;
            this.mState = state;
        }

        @Override
        boolean shouldBeActive() {
            return mOwner.getLifecycle().getCurrentState().isAtLeast(mState);
        }

        @Override
        public void onStateChanged(@NonNull LifecycleOwner source, @NonNull Lifecycle.Event event) {
            if (mOwner.getLifecycle().getCurrentState() == DESTROYED) {
                removeObserver(mObserver);
                return;
            }
            activeStateChanged(shouldBeActive());
        }

        @Override
        boolean isAttachedTo(LifecycleOwner owner) {
            return mOwner == owner;
        }

        @Override
        void detachObserver() {
            mOwner.getLifecycle().removeObserver(this);
        }
    }

    private class AlwaysActiveObserver extends ObserverWrapper {

        AlwaysActiveObserver(Observer<? super T> observer, boolean isStickyMode) {
            super(observer, isStickyMode);
        }

        @Override
        boolean shouldBeActive() {
            return true;
        }
    }

    private static void assertMainThread(String methodName) {
        if (!DefaultTaskExecutor.getInstance().isMainThread()) {
            throw new IllegalStateException("Cannot invoke " + methodName + " on a background"
                    + " thread");
        }
    }
}