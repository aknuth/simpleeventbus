package com.adamtaft.eb;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;



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

	{
		Thread t = new Thread(new EventQueueRunner());
		t.setDaemon(true);
		t.start();
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
	private void notifySubscribers(Object evt) {
		// roll through the subscribers
		// we find the veto handlers, regular handlers, and null handlers
		// (those that have been GC)
		List<SubscriberInfo> vetoList = new ArrayList<SubscriberInfo>();
		List<SubscriberInfo> reguList = new ArrayList<SubscriberInfo>();
		List<SubscriberInfo> killList = new ArrayList<SubscriberInfo>();

		for (SubscriberInfo info : subscribers) {
			if (info.matchesEvent(evt)) {
				if (info.isVetoHandler()) {
					vetoList.add(info);
				} else {
					reguList.add(info);
				}
			}
		}


		// used to keep track if a veto was called.
		// if so, the regular list won't be processed.
		boolean vetoCalled = false;

		// send event to veto handlers
		for (SubscriberInfo veto : vetoList) {
			try {
				Object obj = veto.getSubscriber();
				if (obj == null) {
					killList.add(veto);
					continue; 
				}

				veto.getMethod().invoke(obj, evt);

			} catch (InvocationTargetException e) {
				// this can happen if the veto was thrown
				Throwable cause = e.getCause();
				if (cause instanceof VetoException) {
					vetoCalled = true;

				} else if (cause instanceof RuntimeException) {
					throw (RuntimeException) cause;

				} else {
					throw new RuntimeException(cause);
				}

			} catch (Exception e) {
				if (e instanceof RuntimeException) {
					throw (RuntimeException) e;
				}

				throw new RuntimeException(e);
			}
		}
		
		// ignore vetoCalled if the actual event was a VetoEvent
		// i.e. one cannot veto a VetoEvent
		if (vetoCalled && (evt instanceof VetoEvent)) {
			vetoCalled = false;
		}

		// if the veto was called, publish a new VetoEvent to the buss
		// we also return short here, since we don't want the regulars
		// to run after a veto
		if (vetoCalled) {
			publish(new VetoEvent(evt));
			return;
		}

		// run through the regular list
		for (SubscriberInfo info : reguList) {
			try {
				Object obj = info.getSubscriber();
				if (obj == null) {
					killList.add(info);
					continue;
				}

				info.getMethod().invoke(obj, evt);

			} catch (Exception e) {
				if (e instanceof RuntimeException) {
					throw (RuntimeException) e;
				}
				throw new RuntimeException(e);
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
		private final Class<? extends Object> eventClass;
		private final Method method;
		private final WeakReference<?> subscriber;
		private final boolean vetoHandler;

		public SubscriberInfo(Class<? extends Object> eventClass, Method method, Object subscriber, boolean vetoHandler) {
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
