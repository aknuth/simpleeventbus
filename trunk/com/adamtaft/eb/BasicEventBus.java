package com.adamtaft.eb;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;



/**
 * A simple Event Bus implementation which receives events or messages from
 * various sources and distributes them to all subscribers of the event type.
 * This is highly useful for programs which are event driven. Swing applications
 * in particular can benefit from an event bus architecture, as opposed to the
 * traditional event listener architecture it employs.
 * <p>
 * The BasicEventBus class is thread safe and uses a background thread to notify the
 * subscribers of the event. The subscribers are notified in a serial fashion,
 * and only one event will be published at a time. Though, the
 * {@link #publish(Object)} method is done in a non-blocking way.
 * <p>
 * Subscribers subscribe to the EventBus using the {@link #subscribe(Object)}
 * method. A specific subscriber type is not required, but the subscriber will
 * be reflected to find all methods annotated with the {@link EventHandler}
 * annotations. These methods will be invoked as needed by the event bus based
 * on the type of the first parameter to the annotated method.
 * <p>
 * An event handler can indicate that it can veto events by setting the
 * {@link EventHandler#canVeto()} value to true.  This will inform the EventBus
 * of the subscriber's desire to veto the event.  A vetoed event will not be
 * sent to the regular subscribers.
 * <p>
 * During publication of an event, all veto EventHandler methods will be notified
 * first and allowed to throw a {@link VetoException} indicating that the event
 * has been vetoed and should not be published to the remaining event handlers.
 * If no vetoes have been made, the regular subscriber handlers will be notified
 * of the event.
 * <p>
 * Subscribers are stored using a {@link WeakReference} such that a memory leak
 * can be avoided if the client fails to unsubscribe at the end of the use.
 * However, calling the {@link #unsubscribe(Object)} method is highly
 * recommended none-the-less.
 * 
 * @author Adam Taft
 */
public final class BasicEventBus implements EventBus {

	private final List<SubscriberInfo> subscribers = new CopyOnWriteArrayList<SubscriberInfo>();
	private final BlockingQueue<Object> queue = new LinkedBlockingQueue<Object>();
	
	/**
	 * The ExecutorService used to handle event delivery to the event handlers.
	 */
	private final ExecutorService executorService;
	
	/**
	 * Default constructor sets up the executorService property to use the
	 * {@link Executors#newCachedThreadPool()} implementation.  The configured
	 * ExecutorService will have a custom ThreadFactory such that the threads
	 * returned will be daemon threads (and thus not block the application
	 * from shutting down).
	 */
	public BasicEventBus() {
		this(Executors.newCachedThreadPool(new ThreadFactory() {
			private final ThreadFactory delegate = Executors.defaultThreadFactory();

			@Override
			public Thread newThread(Runnable r) {
				Thread t = delegate.newThread(r);
				t.setDaemon(true);
				return t;
			}
		}));
	}
	
	public BasicEventBus(ExecutorService executorService) {
		// start the background daemon consumer thread.
		Thread t = new Thread(new EventQueueRunner());
		t.setDaemon(true);
		t.start();
		
		this.executorService = executorService;
	}

	/**
	 * Subscribe the specified instance as a potential event subscriber.
	 * The subscriber must annotate a method (or two) with the {@link EventHandler}
	 * annotation if it expects to receive notifications.
	 * <p>
	 * Note that the EventBus maintains a {@link WeakReference} to the subscriber,
	 * but it is still adviced to call the {@link #unsubscribe(Object)} method
	 * if the subscriber does not wish to receive events any longer.
	 * 
	 * @param subscriber The subscriber object which will receive notifications on {@link EventHandler} annotated methods.
	 */
	public void subscribe(Object subscriber) {
		Method[] methods = subscriber.getClass().getDeclaredMethods();
		for (Method method : methods) {
			// look for the EventHandler annotation on the method, if it exists
			// if not, this returns null, and go to the next method
			EventHandler eh = method.getAnnotation(EventHandler.class);
			if (eh == null)
				continue;

			// evaluate the parameters of the method.
			// only a single parameter of the Object type is allowed for the handler method.
			Class<?>[] parameters = method.getParameterTypes();
			if (parameters.length != 1) {
				throw new IllegalArgumentException("EventHandler methods must specify a single Object paramter.");
			}

			// add the subscriber to the list
			SubscriberInfo info = new SubscriberInfo(parameters[0], method, subscriber, eh.canVeto());
			subscribers.add(info);
		}
	}

	
	/***
	 * Unsubscribe the specified subscriber from receiving future published
	 * events.
	 * 
	 * @param subscriber The object to unsubcribe from future events.
	 */
	public void unsubscribe(Object subscriber) {
		List<SubscriberInfo> killList = new ArrayList<SubscriberInfo>();
		for (SubscriberInfo info : subscribers) {
			Object obj = info.getSubscriber();
			if (obj == null || obj == subscriber) {
				killList.add(info);
			}
		}
		for (SubscriberInfo kill : killList) {
			subscribers.remove(kill);
		}
	}

	
	/**
	 * Publish the specified event to the event bus.  Based on the type
	 * of the event, the EventBus will publish the event to the subscribing
	 * objects.
	 * 
	 * @param event The event to publish on the event bus.
	 */
	public void publish(Object event) {
		try {
			queue.put(event);

		} catch (InterruptedException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}
	
	
	/**
	 * Returns if the event bus has pending events.
	 * 
	 * @return Returns true if the event bus has pending events to publish.
	 */
	public boolean hasPendingEvents() {
		return queue.size() > 0;
	}

	
	// called on the background thread.
	private void notifySubscribers(final Object evt) {
		// roll through the subscribers
		// we find the veto handlers, regular handlers
		// we also store information about any that have been garbage collected
		final List<Callable<Void>> vetoList = new ArrayList<Callable<Void>>();
		final List<Callable<Void>> reguList = new ArrayList<Callable<Void>>();
		final List<SubscriberInfo> killList = new ArrayList<SubscriberInfo>();

		for (final SubscriberInfo info : subscribers) {
			if (! info.matchesEvent(evt)) continue;
			
			Callable<Void> c = new Callable<Void>() {
				@Override
				public Void call() throws Exception {
					Object obj = info.getSubscriber();
					if (obj == null) {
						killList.add(info);
						return null;
					}
					info.getMethod().invoke(obj, evt);
					return null;
				}
			};
			
			if (info.isVetoHandler()) {
				vetoList.add(c);
			} else {
				reguList.add(c);
			}
			
		}
		
		// used to keep track if a veto was called.
		// if so, the regular list won't be processed.
		boolean vetoCalled = false;
		
		// submit the veto calls to the executor service
		List<Future<Void>> vetoFutures;
		try {
			vetoFutures = executorService.invokeAll(vetoList);
		} catch (InterruptedException e) {
			// we just return if interrupted.
			return;
		}
		// wait for these to return
		for (Future<Void> f : vetoFutures) {
			try {
				f.get();
			} catch (InterruptedException e) {
				return;
			} catch (ExecutionException e) {
				// look for an InvocationTargetException from the method.invoke call
				Throwable cause = e.getCause();
				if (cause instanceof InvocationTargetException) {
					// we're hunting for VetoException
					cause = cause.getCause();
					if (cause instanceof VetoException) {
						vetoCalled = true;
						continue;
					}
				} else if (cause instanceof RuntimeException) {
					throw (RuntimeException) cause;
				} else {
					throw new RuntimeException(cause);					
				}
			}
		}
		
		// cleanout any kill list items from the veto calls
		for (SubscriberInfo kill : killList) {
			subscribers.remove(kill);
		}
		
		// ignore vetoCalled if the actual event was a VetoEvent
		// i.e. one cannot veto a VetoEvent
		if (vetoCalled && (evt instanceof VetoEvent)) {
			vetoCalled = false;
		}

		// if vetoed, publish a VetoEvent and return
		if (vetoCalled) {
			publish(new VetoEvent(evt));
			return;
		}
		
		// submit the regular handler calls
		List<Future<Void>> reguFutures;
		try {
			reguFutures = executorService.invokeAll(reguList);
		} catch (InterruptedException e) {
			return;
		}
		
		// wait for these to return
		for (Future<Void> f : reguFutures) {
			try {
				f.get();
			} catch (InterruptedException e) {
				return;
			} catch (ExecutionException e) {
				Throwable cause = e.getCause();
				if (cause instanceof RuntimeException) {
					throw (RuntimeException) cause;
				} else {
					throw new RuntimeException(cause);
				}
			}
		}		
		
		// kill the killList
		for (SubscriberInfo kill : killList) {
			subscribers.remove(kill);
		}
		
	}
		

	// the background thread runnable, simply extracts
	// any events from the queue and publishes them.
	private class EventQueueRunner implements Runnable {
		@Override
		public void run() {
			try {
				while (true) {
					notifySubscribers(queue.take());
				}

			} catch (InterruptedException e) {
				e.printStackTrace();
				throw new RuntimeException(e);
			}
		}
	}

	
	// used to hold the subscriber
	private static class SubscriberInfo {
		private final Class<?> eventClass;
		private final Method method;
		private final WeakReference<?> subscriber;
		private final boolean vetoHandler;

		public SubscriberInfo(Class<?> eventClass, Method method, Object subscriber, boolean vetoHandler) {
			this.eventClass = eventClass;
			this.method = method;
			this.subscriber = new WeakReference<Object>(subscriber);
			this.vetoHandler = vetoHandler;
		}

		public boolean matchesEvent(Object event) {
			return event.getClass().equals(eventClass);
		}

		public Method getMethod() {
			return method;
		}

		public Object getSubscriber() {
			return subscriber.get();
		}

		public boolean isVetoHandler() {
			return vetoHandler;
		}
		
	}
}
