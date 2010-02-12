package com.adamtaft.eb.example;

import com.adamtaft.eb.EventBus;
import com.adamtaft.eb.EventBusFactory;
import com.adamtaft.eb.EventHandler;

public class WeakReferenceExample {

	@EventHandler
	public void handleString(String evt) {
		System.out.println(evt);
	}
	
	public static void main(String[] args) throws Exception {
		// create an event handler for this example
		WeakReferenceExample wre = new WeakReferenceExample();
		
		// subscribe the handler to the event bus
		EventBus bus = EventBusFactory.getEventBus();
		bus.subscribe(wre);
		
		// send an event to the buss
		bus.publish("First String Event");
		
		// note, we pause here for a second, to let the event get out
		Thread.sleep(100);
		
		// set the reference to null
		// IT'S STILL BETTER TO UNSUBSCRIBE.  THIS SHOULD ONLY BE CONSIDERED A FALLBACK.
		wre = null;
		
		// Pretty please, run the garbage collection.
		System.gc();
		
		// Ideally, this second event won't show up.  YMMV
		// You might see this second event if the garbage collector didn't run
		// This is system and JVM specific.  This example does work for me.
		bus.publish("Second String Event");
	}
	
}
