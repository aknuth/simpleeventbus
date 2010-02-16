package com.adamtaft.eb.example;

import java.awt.event.ActionEvent;

import com.adamtaft.eb.EventBusService;
import com.adamtaft.eb.EventHandler;

public class SimpleExample {

	@EventHandler
	public void handleString(String evt) {
		System.out.println("handleString called: " + evt);
	}
	
	@EventHandler
	public void handleActionEvent(ActionEvent evt) {
		System.out.println("handleActionEvent called: " + evt);
	}
	

	public static void main(String[] args) {
		// create an event handler
		SimpleExample se = new SimpleExample();
		
		// subscribe it to the EventBus
		EventBusService.subscribe(se);
		
		// publish some events to the bus.
		EventBusService.publish("Some String Event");
		EventBusService.publish(new ActionEvent("Fake Action Event Source", -1, "Fake Command"));
		
		// this shouldn't be seen, since no handler is interested in Object
		EventBusService.publish(new Object());
		
		// don't forget to unsubscribe if you're done.
		// not required in this case, since the program ends here anyway.
		EventBusService.unsubscribe(se);
		
		// Future messages shouldn't be seen by the SimpleExample handler after
		// being unsubscribed.
		EventBusService.publish("This event should not be seen after the unsubscribe call.");
	}
	
}
