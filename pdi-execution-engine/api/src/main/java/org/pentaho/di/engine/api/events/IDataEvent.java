package org.pentaho.di.engine.api.events;

import org.pentaho.di.engine.api.model.IRow;

import java.util.List;

/**
 * An {@link IPDIEvent} associated with an {@link IRow} element. This event contains the data, the IPDIEventSource
 * which emitted the event and the direction of the flow.
 * <p>
 * Created by nbaker on 5/30/16.
 */
public interface IDataEvent extends IPDIEvent {
  enum TYPE { IN, OUT, ERROR }

  enum STATE { ACTIVE, COMPLETE, EMPTY }

  TYPE getType();

  STATE getState();

  /**
   * Rows of data or otherwise
   *
   * @return
   */
  List<IRow> getRows();

  /**
   * Component which emitted the event
   *
   * @return
   */
  IPDIEventSource<?> getEventSource();
}