/*
 * Copyright (c) 2011-2013 GoPivotal, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.rx;

import org.reactivestreams.Processor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Environment;
import reactor.event.dispatch.Dispatcher;
import reactor.event.dispatch.SynchronousDispatcher;
import reactor.function.Consumer;
import reactor.function.Supplier;
import reactor.rx.action.Action;
import reactor.rx.action.FinallyAction;
import reactor.rx.action.ForEachAction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A {@code Promise} is a stateful event container that accepts a single value or error. In addition to {@link #get()
 * getting} or {@link #await() awaiting} the value, consumers can be registered to the outbound {@link #stream()} or via
 * , consumers can be registered to be notified of {@link
 * #onError(Consumer) notified an error}, {@link #onSuccess(Consumer) a value}, or {@link #onComplete(Consumer) both}.
 * <p>
 * A promise also provides methods for composing actions with the future value much like a {@link reactor.rx.Stream}.
 * However, where
 * a {@link reactor.rx.Stream} can process many values, a {@code Promise} processes only one value or error.
 *
 * @param <O> the type of the value that will be made available
 * @author Jon Brisbin
 * @author Stephane Maldini
 * @see <a href="https://github.com/promises-aplus/promises-spec">Promises/A+ specification</a>
 */
public class Promise<O> implements Supplier<O>, Processor<O, O>, Consumer<O> {

	private final ReentrantLock lock = new ReentrantLock();

	private final long        defaultTimeout;
	private final Condition   pendingCondition;
	private final Environment environment;
	private final Dispatcher  dispatcher;

	Stream<O> outboundStream;

	Stream.State state = Stream.State.READY;
	private O            value;
	private Throwable    error;
	private boolean hasBlockers = false;

	protected Subscription subscription;

	/**
	 * Creates a new unfulfilled promise.
	 * <p>
	 * The {@code dispatcher} is used when notifying the Promise's consumers, determining the thread on which they are
	 * called. The given {@code env} is used to determine the default await timeout. The
	 * default await timeout will be 30 seconds. This Promise will consumer errors from its {@code parent} such that if
	 * the parent completes in error then so too will this Promise.
	 *
	 */
	public Promise() {
		this(SynchronousDispatcher.INSTANCE, null);
	}


	/**
	 * Creates a new unfulfilled promise.
	 * <p>
	 * The {@code dispatcher} is used when notifying the Promise's consumers, determining the thread on which they are
	 * called. The given {@code env} is used to determine the default await timeout. If {@code env} is {@code null} the
	 * default await timeout will be 30 seconds. This Promise will consumer errors from its {@code parent} such that if
	 * the parent completes in error then so too will this Promise.
	 *
	 * @param dispatcher The Dispatcher to run any downstream subscribers
	 * @param env        The Environment, if any, from which the default await timeout is obtained
	 */
	public Promise(Dispatcher dispatcher, @Nullable Environment env) {
		this.dispatcher = dispatcher;
		this.environment = env;
		this.defaultTimeout = env != null ? env.getProperty("reactor.await.defaultTimeout", Long.class, 30000L) : 30000L;
		this.pendingCondition = lock.newCondition();
	}

	/**
	 * Creates a new promise that has been fulfilled with the given {@code value}.
	 * <p>
	 * The {@code observable} is used when notifying the Promise's consumers. The given {@code env} is used to determine
	 * the default await timeout. If {@code env} is {@code null} the default await timeout will be 30 seconds.
	 *
	 * @param value      The value that fulfills the promise
	 * @param dispatcher The Dispatcher to run any downstream subscribers
	 * @param env        The Environment, if any, from which the default await timeout is obtained
	 */
	public Promise(O value, Dispatcher dispatcher,
	               @Nullable Environment env) {
		this(dispatcher, env);
		state = Stream.State.COMPLETE;
		this.value = value;
	}

	/**
	 * Creates a new promise that has failed with the given {@code error}.
	 * <p>
	 * The {@code observable} is used when notifying the Promise's consumers, determining the thread on which they are
	 * called. The given {@code env} is used to determine the default await timeout. If {@code env} is {@code null} the
	 * default await timeout will be 30 seconds.
	 *
	 * @param error      The error the completed the promise
	 * @param dispatcher The Dispatcher to run any downstream subscribers
	 * @param env        The Environment, if any, from which the default await timeout is obtained
	 */
	public Promise(Throwable error, Dispatcher dispatcher,
	               @Nullable Environment env) {
		this(dispatcher, env);
		state = Stream.State.ERROR;
		this.error = error;
	}

	/**
	 * Assign a {@link Consumer} that will either be invoked later, when the {@code Promise} is completed by either
	 * setting a value or propagating an error, or, if this {@code Promise} has already been fulfilled, is immediately
	 * scheduled to be executed on the current {@link reactor.event.dispatch.Dispatcher}.
	 *
	 * @param onComplete the completion {@link Consumer}
	 * @return {@literal the new Promise}
	 */
	public Promise<O> onComplete(@Nonnull final Consumer<Promise<O>> onComplete) {
		return stream().connect(new FinallyAction<O, Promise<O>>(dispatcher, this, onComplete)).next();
	}

	/**
	 * Assign a {@link Consumer} that will either be invoked later, when the {@code Promise} is successfully completed
	 * with
	 * a value, or, if this {@code Promise} has already been fulfilled, is immediately scheduled to be executed on the
	 * current {@link Dispatcher}.
	 *
	 * @param onSuccess the success {@link Consumer}
	 * @return {@literal the new Promise}
	 */
	public Promise<O> onSuccess(@Nonnull final Consumer<O> onSuccess) {
		return stream().observe(onSuccess).next();
	}

	/**
	 * Assign a {@link Consumer} that will either be invoked later, when the {@code Promise} is completed with an error,
	 * or, if this {@code Promise} has already been fulfilled, is immediately scheduled to be executed on the current
	 * {@link Dispatcher}. The error is recovered and materialized as the next signal to the returned stream.
	 *
	 * @param onError the error {@link Consumer}
	 * @return {@literal the new Promise}
	 */
	public Promise<Throwable> onError(@Nonnull final Consumer<Throwable> onError) {
		return stream().recover(Throwable.class).observe(onError).next();
	}

	/**
	 * Indicates whether this {@code Promise} has been completed with either an error or a value
	 *
	 * @return {@code true} if this {@code Promise} is complete, {@code false} otherwise.
	 * @see #isPending()
	 */
	public boolean isComplete() {
		lock.lock();
		try {
			return state != Stream.State.READY;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Indicates whether this {@code Promise} has yet to be completed with a value or an error.
	 *
	 * @return {@code true} if this {@code Promise} is still pending, {@code false} otherwise.
	 * @see #isComplete()
	 */
	public boolean isPending() {
		lock.lock();
		try {
			return state == Stream.State.READY;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Indicates whether this {@code Promise} has been successfully completed a value.
	 *
	 * @return {@code true} if this {@code Promise} is successful, {@code false} otherwise.
	 */
	public boolean isSuccess() {
		lock.lock();
		try {
			return state == Stream.State.COMPLETE;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Indicates whether this {@code Promise} has been completed with an error.
	 *
	 * @return {@code true} if this {@code Promise} was completed with an error, {@code false} otherwise.
	 */
	public boolean isError() {
		lock.lock();
		try {
			return state == Stream.State.ERROR;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Block the calling thread, waiting for the completion of this {@code Promise}. A default timeout as specified in
	 * Reactor's {@link Environment} properties using the key {@code reactor.await.defaultTimeout} is used. The
	 * default is
	 * 30 seconds. If the promise is completed with an error a RuntimeException that wraps the error is thrown.
	 *
	 * @return the value of this {@code Promise} or {@code null} if the timeout is reached and the {@code Promise} has
	 * not
	 * completed
	 * @throws InterruptedException if the thread is interruped while awaiting completion
	 * @throws RuntimeException     if the promise is completed with an error
	 */
	public O await() throws InterruptedException {
		return await(defaultTimeout, TimeUnit.MILLISECONDS);
	}

	/**
	 * Block the calling thread for the specified time, waiting for the completion of this {@code Promise}. If the
	 * promise
	 * is completed with an error a RuntimeException that wraps the error is thrown.
	 *
	 * @param timeout the timeout value
	 * @param unit    the {@link TimeUnit} of the timeout value
	 * @return the value of this {@code Promise} or {@code null} if the timeout is reached and the {@code Promise} has
	 * not
	 * completed
	 * @throws InterruptedException if the thread is interruped while awaiting completion
	 */
	public O await(long timeout, TimeUnit unit) throws InterruptedException {
		if (!isPending()) {
			return get();
		}

		lock.lock();
		try {
			hasBlockers = true;
			if (timeout >= 0) {
				long msTimeout = TimeUnit.MILLISECONDS.convert(timeout, unit);
				long endTime = System.currentTimeMillis() + msTimeout;
				while (state == Stream.State.READY && (System.currentTimeMillis()) < endTime) {
					this.pendingCondition.await(200, TimeUnit.MILLISECONDS);
				}
			} else {
				while (state == Stream.State.READY) {
					this.pendingCondition.await(200, TimeUnit.MILLISECONDS);
				}
			}
		} finally {
			hasBlockers = false;
			lock.unlock();
		}

		return get();
	}

	/**
	 * Returns the value that completed this promise. Returns {@code null} if the promise has not been completed. If the
	 * promise is completed with an error a RuntimeException that wraps the error is thrown.
	 *
	 * @return the value that completed the promise, or {@code null} if it has not been completed
	 * @throws RuntimeException if the promise was completed with an error
	 */
	@Override
	public O get() {
		lock.lock();
		try {
			if (state == Stream.State.COMPLETE) {
				return value;
			} else if (state == Stream.State.ERROR) {
				if (RuntimeException.class.isInstance(error)) {
					throw (RuntimeException) error;
				} else {
					throw new RuntimeException(error);
				}
			} else {
				return null;
			}
		} finally {
			lock.unlock();
		}
	}


	/**
	 * Return the error (if any) that has completed this {@code Promise}. Returns {@code null} if the promise has not
	 * been
	 * completed, or was completed with a value.
	 *
	 * @return the error (if any)
	 */
	public Throwable reason() {
		lock.lock();
		try {
			return error;
		} finally {
			lock.unlock();
		}
	}

	public Stream<O> stream() {
		lock.lock();
		try {
			if (outboundStream == null) {
				if (isSuccess()) {
					outboundStream = new ForEachAction<O>(Arrays.asList(value), dispatcher).env(environment);
				} else {
					outboundStream = new Stream<O>(dispatcher, environment, 1);
					outboundStream.setKeepAlive(false);
					if (isError()) {
						outboundStream.broadcastError(error);
					}
				}
			}

		} finally {
			lock.unlock();
		}
		return outboundStream;
	}

	@Override
	public void subscribe(final Subscriber<? super O> subscriber) {
		stream().subscribe(subscriber);
	}

	public Environment getEnvironment() {
		return environment;
	}

	@Override
	public void onSubscribe(Subscription subscription) {
		this.subscription = subscription;
		subscription.request(1);
	}

	@Override
	public void onNext(O element) {
		valueAccepted(element);
	}

	@Override
	public void onComplete() {
		completeAccepted();
	}

	@Override
	public void onError(Throwable cause) {
		errorAccepted(cause);
	}

	@Override
	public void accept(O o) {
		valueAccepted(o);
	}


	public StreamUtils.StreamVisitor debug() {
		Stream<?> debugged = findOldestStream();

		return debugged == null ? stream().debug() : debugged.debug();
	}

	@SuppressWarnings("unchecked")
	public Stream<?> findOldestStream() {

		if(subscription == null){
			return outboundStream;
		}

		Subscription sub = subscription;
		Action<?,?> that = null;

		while (sub != null
				&& StreamSubscription.class.isAssignableFrom(sub.getClass())
				&& ((StreamSubscription<?>) sub).getPublisher() != null
				&& Action.class.isAssignableFrom(((StreamSubscription<?>) sub).getPublisher().getClass())
				) {

			that =  (Action<?,?>)((StreamSubscription<?>) sub).getPublisher();
			sub = that.getSubscription();
		}
		return that;
	}


	protected void errorAccepted(Throwable error) {
		lock.lock();
		try {
			if (!isPending()) throw new IllegalStateException();
			this.error = error;
			this.state = Stream.State.ERROR;

			if (outboundStream != null) {
				outboundStream.broadcastError(error);
			}

			if (hasBlockers) {
				pendingCondition.signalAll();
				hasBlockers = false;
			}
		} finally {
			lock.unlock();
		}
	}

	protected void valueAccepted(O value) {
		lock.lock();
		try {
			if (!isPending()) {
				throw new IllegalStateException();
			}
			this.value = value;
			this.state = Stream.State.COMPLETE;

			if (outboundStream != null) {
				outboundStream.broadcastNext(value);
				outboundStream.broadcastComplete();
			}

			if (hasBlockers) {
				pendingCondition.signalAll();
				hasBlockers = false;
			}
		} finally {
			lock.unlock();
		}
	}

	protected void completeAccepted() {
		lock.lock();
		try {
			if (isPending()) {
				valueAccepted(null);
			} /*else if (subscription != null) {
				//this.subscription.cancel();
			}*/
		} finally {
			lock.unlock();
		}
	}

	@Override
	public String toString() {
		lock.lock();
		try {
			return "Promise{" +
					"value=" + value +
					", state=" + state +
					", error=" + error +
					'}';
		} finally {
			lock.unlock();
		}
	}

}