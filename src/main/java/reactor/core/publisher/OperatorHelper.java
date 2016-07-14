/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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
package reactor.core.publisher;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Fuseable;
import reactor.core.Loopback;
import reactor.core.Producer;
import reactor.core.Receiver;
import reactor.core.subscriber.DeferredSubscription;
import reactor.core.subscriber.SubscriberState;
import reactor.core.subscriber.SubscriptionHelper;

/**
 * @author Stephane Maldini
 */
enum OperatorHelper {
	;

	/**
	 * A Subscriber/Subscription barrier that holds a single value at most and properly gates asynchronous behaviors
	 * resulting from concurrent request or cancel and onXXX signals.
	 *
	 * @param <I> The upstream sequence type
	 * @param <O> The downstream sequence type
	 */
	public static class DeferredScalarSubscriber<I, O> implements Subscriber<I>, Loopback,
	                                                              SubscriberState,
	                                                              Receiver, Producer,
	                                                              Fuseable.QueueSubscription<O> {

		static final int SDS_NO_REQUEST_NO_VALUE   = 0;
		static final int SDS_NO_REQUEST_HAS_VALUE  = 1;
		static final int SDS_HAS_REQUEST_NO_VALUE  = 2;
		static final int SDS_HAS_REQUEST_HAS_VALUE = 3;

		protected final Subscriber<? super O> subscriber;

		protected O value;

		volatile int state;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<DeferredScalarSubscriber> STATE =
				AtomicIntegerFieldUpdater.newUpdater(DeferredScalarSubscriber.class, "state");

		protected byte outputFused;

		static final byte OUTPUT_NO_VALUE = 1;
		static final byte OUTPUT_HAS_VALUE = 2;
		static final byte OUTPUT_COMPLETE = 3;

		public DeferredScalarSubscriber(Subscriber<? super O> subscriber) {
			this.subscriber = subscriber;
		}

		@Override
		public void request(long n) {
			if (SubscriptionHelper.validate(n)) {
				for (; ; ) {
					int s = state;
					if (s == SDS_HAS_REQUEST_NO_VALUE || s == SDS_HAS_REQUEST_HAS_VALUE) {
						return;
					}
					if (s == SDS_NO_REQUEST_HAS_VALUE) {
						if (STATE.compareAndSet(this, SDS_NO_REQUEST_HAS_VALUE, SDS_HAS_REQUEST_HAS_VALUE)) {
							Subscriber<? super O> a = downstream();
							a.onNext(value);
							a.onComplete();
						}
						return;
					}
					if (STATE.compareAndSet(this, SDS_NO_REQUEST_NO_VALUE, SDS_HAS_REQUEST_NO_VALUE)) {
						return;
					}
				}
			}
		}

		@Override
		public void cancel() {
			state = SDS_HAS_REQUEST_HAS_VALUE;
		}

		@Override
		@SuppressWarnings("unchecked")
		public void onNext(I t) {
			value = (O) t;
		}

		@Override
		public void onError(Throwable t) {
			subscriber.onError(t);
		}

		@Override
		public void onSubscribe(Subscription s) {
			//if upstream
		}

		@Override
		public void onComplete() {
			subscriber.onComplete();
		}

		@Override
		public final boolean isCancelled() {
			return state == SDS_HAS_REQUEST_HAS_VALUE;
		}

		@Override
		public final Subscriber<? super O> downstream() {
			return subscriber;
		}

		public void setValue(O value) {
			this.value = value;
		}

		/**
		 * Tries to emit the value and complete the underlying subscriber or
		 * stores the value away until there is a request for it.
		 * <p>
		 * Make sure this method is called at most once
		 * @param value the value to emit
		 */
		public final void complete(O value) {
			Objects.requireNonNull(value);
			for (; ; ) {
				int s = state;
				if (s == SDS_NO_REQUEST_HAS_VALUE || s == SDS_HAS_REQUEST_HAS_VALUE) {
					return;
				}
				if (s == SDS_HAS_REQUEST_NO_VALUE) {
					if (outputFused == OUTPUT_NO_VALUE) {
						setValue(value); // make sure poll sees it
						outputFused = OUTPUT_HAS_VALUE;
					}
					Subscriber<? super O> a = downstream();
					a.onNext(value);
					if (state != SDS_HAS_REQUEST_HAS_VALUE) {
						a.onComplete();
					}
					return;
				}
				setValue(value);
				if (STATE.compareAndSet(this, SDS_NO_REQUEST_NO_VALUE, SDS_NO_REQUEST_HAS_VALUE)) {
					return;
				}
			}
		}

		@Override
		public boolean isStarted() {
			return state != SDS_NO_REQUEST_NO_VALUE;
		}

		@Override
		public Object connectedOutput() {
			return value;
		}

		@Override
		public boolean isTerminated() {
			return isCancelled();
		}

		@Override
		public Object upstream() {
			return value;
		}

		@Override
		public int requestFusion(int requestedMode) {
			if ((requestedMode & Fuseable.ASYNC) != 0) {
				outputFused = OUTPUT_NO_VALUE;
				return Fuseable.ASYNC;
			}
			return Fuseable.NONE;
		}

		@Override
		public O poll() {
			if (outputFused == OUTPUT_HAS_VALUE) {
				outputFused = OUTPUT_COMPLETE;
				return value;
			}
			return null;
		}

		@Override
		public boolean isEmpty() {
			return outputFused != OUTPUT_HAS_VALUE;
		}

		@Override
		public void clear() {
			outputFused = OUTPUT_COMPLETE;
			value = null;
		}

		@Override
		public int size() {
			return isEmpty() ? 0 : 1;
		}
	}

	/**
	 * Arbitrates the requests and cancellation for a Subscription that may be set onSubscribe once only.
	 * <p>
	 * Note that {@link #request(long)} doesn't validate the amount.
	 *
	 * @param <I> the input value type
	 * @param <O> the output value type
	 */
	public static class DeferredSubscriptionSubscriber<I, O>
			extends DeferredSubscription
	implements Subscriber<I>, Producer {

		protected final Subscriber<? super O> subscriber;

		/**
		 * Constructs a SingleSubscriptionArbiter with zero initial request.
		 *
		 * @param subscriber the actual subscriber
		 */
		public DeferredSubscriptionSubscriber(Subscriber<? super O> subscriber) {
			this.subscriber = Objects.requireNonNull(subscriber, "subscriber");
		}

		@Override
		public final Subscriber<? super O> downstream() {
			return subscriber;
		}

		@Override
		public void onSubscribe(Subscription s) {
			set(s);
		}

		@Override
		@SuppressWarnings("unchecked")
		public void onNext(I t) {
			subscriber.onNext((O) t);
		}

		@Override
		public void onError(Throwable t) {
			subscriber.onError(t);
		}

		@Override
		public void onComplete() {
			subscriber.onComplete();
		}
	}
}