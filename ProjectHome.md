# Introduction #

The Simple Java Event Bus is an "event bus" library implementation that helps to decouple highly dependent components by using a subscribe and publish type architecture.  An event bus helps to simplify event communication between multiple components and promotes a more stable and simplistic decoupled interface between each.

# Event Bus Pattern #
There are many patterns devoted to reducing component coupling.  An event bus is one such pattern where objects can "subscribe" to receive certain specific "events" from the bus.  As an event is "published" to the event bus, it will be propagated to any subscriber which is interested in the event type.  This allows each component to couple solely to the event bus itself and not directly with each other.

An event bus can be thought of as a replacement for the observer pattern, where in the observer pattern, each component is observing an observable directly.  In the event bus pattern, each component simply subscribes to the event bus and waits for its event notification methods to be invoked when interesting events have occurred.  In this way, an event bus can be thought of like the observer pattern with an extra layer of decoupling.

# Use Cases #
While multiple use cases exist for an event bus, a common case is for complicated Swing based programs.  In a traditional Swing application, multiple sources of events can exist across the application.  A menu or toolbar button, a mouse click, window resizing, or some other external data source could feasibly produce the same desired result in the application.  Selecting a certain row in a JTable, for example, might result in a toolbar button being deactivated, a database record updated, and a new window spawned.  A double click of a JTree item might invoke another long chain of events.

In these cases, a traditional Swing application would directly couple the table or tree with each individual button, model or service interface, giving complexities to the setup of each component.

The event bus helps solve this problem by simply allowing each component to subscribe to the event bus, and when an interesting action occurs (such as the row selection in a table), an event should be generated to the bus.  Each subscriber to the event type will be notified and can react accordingly.

# Example Use #

A subscriber to the event bus has to simply annotate one or more methods with the @EventHandler annotation.  Additionally, the subscriber must subscribe to the bus by calling its subscribe() method.  A method annotated with @EventHandler must have a single parameter as an argument to the method which is the event type of interest.

A few examples exist in the com.adamtaft.eb.example package in the source code, but here is a quick overview of how to use the simple event bus.

```
public class MyEventHandler {
  @EventHandler
  public void handleStringEvent(String event) {
    System.out.println("received: " + event);
  }

  public static void main(String[] args) {
    MyEventHandler meh = new MyEventHandler();
    EventBusService.subscribe(meh);

    // later, events occur, usually from mouse clicks, etc.
    EventBusService.publish("Some String Event");
  }
}
```

# Vetoing #

A nice feature of this event bus is the ability for a handler to veto certain events.  In this way, a vetoed event will not be delivered to any of the regular listeners for that event type.  An event handler (subscriber) that wishes to veto an event must simply set the @EventHandler(canVeto=true) parameter and then throw a VetoException, like this:

```
public class MyEventHandler {
  @EventHandler
  public void handleStringEvent(String event) {
    throw new AssertionError("Should not happen, event was vetoed.");
  }

  @EventHandler(canVeto=true)
  public void vetoStringEvent(String event) {
    throw new VetoException();
  }

  public static void main(String[] args) {
    MyEventHandler meh = new MyEventHandler();
    EventBusService.subscribe(meh);

    // this time, the event should not be displayed
    // because the event has been vetoed and therefore
    // not delivered to the regular handlers
    EventBusService.publish("Some String Event");
  }
}
```

# Weak References #

The BasicEventBus implements of the EventBus interface uses a WeakReference to store the internal subscriber object.  Thus, if the subscriber looses all its strong references, the event bus will automatically remove the garbage collected reference to the subscriber.  This is a nice feature to help prevent memory leaks, however it is preferred to call the EventBus unsubscribe() method instead.

A consequence of using WeakReference is that subscribers created without a strong reference may never really receive events.  For example, subscribers created as anonymous references, etc.  A future version of this bus may allow for different types of references to be used in the internal bus code.

# Other Similar Projects #
An existing Java project already exists, called [Event Bus](http://www.eventbus.org/) by Michael Bushe, that implements the event bus pattern.  This project attempts to accomplish a smaller subset of the existing functionality of Mr. Bushe's project.  One should consider his project when evaluating this one, as his may better suit your needs.  This project is likely more simple to use, but it does not offer the same number of features.

A good description of the pattern is provided by Microsoft MSDN.  http://msdn.microsoft.com/library/en-us/dnpag/html/ArchMessageBus.asp