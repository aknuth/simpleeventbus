package com.adamtaft.eb.example;

import com.adamtaft.eb.EventBusService;
import com.adamtaft.eb.EventHandler;
import com.adamtaft.eb.VetoEvent;
import com.adamtaft.eb.VetoException;

public class VetoExample {

	@EventHandler
	public void handleStringEventWithoutVeto(String evt) {
		throw new AssertionError("This shouldn't have happened, it should have been vetoed.");
	}
	
	@EventHandler(canVeto=true)
	public void handleStringEventWithVeto(String evt) {
		System.out.println("event message was: " + evt);
		throw new VetoException();
	}
	
	@EventHandler
	public void handleVeto(VetoEvent vetoEvent) {
		System.out.println("Veto has occured on bus: " + vetoEvent);
	}
	
	public static void main(String[] args) {
		// create the handler
		VetoExample ve = new VetoExample();
		
		// subscribe it to the bus
		EventBusService.subscribe(ve);
		
		// publish an event that will get vetoed
		EventBusService.publish("String Event (should be vetoed)");
	}
	
}
