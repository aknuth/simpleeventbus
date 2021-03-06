package com.adamtaft.eb;

import java.util.EventObject;

/**
 * A VetoEvent is sent out of the event bus when a veto has
 * been made by the subscriber.  The subscriber will have
 * indicated a veto by throwing a {@link VetoException} in
 * the {@link EventHandler} annotated method.
 *
 * @author Adam Taft
 */
public class VetoEvent extends EventObject {
	private static final long serialVersionUID = 1L;

	public VetoEvent(Object event) {
		super(event);
	}
	
}
