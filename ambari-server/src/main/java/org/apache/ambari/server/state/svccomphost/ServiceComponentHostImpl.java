/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.server.state.svccomphost;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.controller.ServiceComponentHostResponse;
import org.apache.ambari.server.orm.dao.HostComponentConfigMappingDAO;
import org.apache.ambari.server.orm.dao.HostComponentDesiredConfigMappingDAO;
import org.apache.ambari.server.orm.dao.HostComponentDesiredStateDAO;
import org.apache.ambari.server.orm.dao.HostComponentStateDAO;
import org.apache.ambari.server.orm.dao.HostDAO;
import org.apache.ambari.server.orm.dao.ServiceComponentDesiredStateDAO;
import org.apache.ambari.server.orm.entities.HostComponentConfigMappingEntity;
import org.apache.ambari.server.orm.entities.HostComponentDesiredConfigMappingEntity;
import org.apache.ambari.server.orm.entities.HostComponentDesiredStateEntity;
import org.apache.ambari.server.orm.entities.HostComponentDesiredStateEntityPK;
import org.apache.ambari.server.orm.entities.HostComponentStateEntity;
import org.apache.ambari.server.orm.entities.HostComponentStateEntityPK;
import org.apache.ambari.server.orm.entities.HostEntity;
import org.apache.ambari.server.orm.entities.ServiceComponentDesiredStateEntity;
import org.apache.ambari.server.orm.entities.ServiceComponentDesiredStateEntityPK;
import org.apache.ambari.server.state.*;
import org.apache.ambari.server.state.DesiredConfig.HostOverride;
import org.apache.ambari.server.state.fsm.InvalidStateTransitionException;
import org.apache.ambari.server.state.fsm.SingleArcTransition;
import org.apache.ambari.server.state.fsm.StateMachine;
import org.apache.ambari.server.state.fsm.StateMachineFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.google.inject.persist.Transactional;
import java.util.logging.Level;

public class ServiceComponentHostImpl implements ServiceComponentHost {

  private static final Logger LOG =
      LoggerFactory.getLogger(ServiceComponentHostImpl.class);

  // FIXME need more debug logs

  private final ReadWriteLock clusterGlobalLock;
  private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
  private final Lock readLock = readWriteLock.readLock();
  private final Lock writeLock = readWriteLock.writeLock();

  private final ServiceComponent serviceComponent;
  private final Host host;
  private boolean persisted = false;

  @Inject
  Gson gson;
  @Inject
  HostComponentStateDAO hostComponentStateDAO;
  @Inject
  HostComponentDesiredStateDAO hostComponentDesiredStateDAO;
  @Inject
  HostDAO hostDAO;
  @Inject
  ServiceComponentDesiredStateDAO serviceComponentDesiredStateDAO;
  @Inject
  Clusters clusters;
  @Inject
  HostComponentDesiredConfigMappingDAO hostComponentDesiredConfigMappingDAO;
  @Inject
  HostComponentConfigMappingDAO hostComponentConfigMappingDAO;
  
  @Inject
  ConfigHelper helper;

  private HostComponentStateEntity stateEntity;
  private HostComponentDesiredStateEntity desiredStateEntity;

  private Map<String, String> configs;
  private Map<String, String> desiredConfigs;

  private long lastOpStartTime;
  private long lastOpEndTime;
  private long lastOpLastUpdateTime;
  private Map<String, DesiredConfig> actualConfigs = new HashMap<String, DesiredConfig>();

  private static final StateMachineFactory
  <ServiceComponentHostImpl, State,
  ServiceComponentHostEventType, ServiceComponentHostEvent>
    daemonStateMachineFactory
      = new StateMachineFactory<ServiceComponentHostImpl,
          State, ServiceComponentHostEventType,
          ServiceComponentHostEvent>
          (State.INIT)

  // define the state machine of a HostServiceComponent for runnable
  // components

     .addTransition(State.INIT,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALLING,
         State.INSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
         
     .addTransition(State.INSTALLED,
         State.INSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
         
     .addTransition(State.INSTALLING,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.INSTALLING,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.INSTALLING,
         State.INSTALL_FAILED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.INSTALL_FAILED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALL_FAILED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.INSTALLED,
         State.STARTING,
         ServiceComponentHostEventType.HOST_SVCCOMP_START,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALLED,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UNINSTALL,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALLED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALLED,
         State.STOPPING,
         ServiceComponentHostEventType.HOST_SVCCOMP_STOP,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALLED,
         State.UPGRADING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UPGRADE,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALLED,
          State.INSTALLED,
          ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
          new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.INSTALLED,
          State.STARTED,
          ServiceComponentHostEventType.HOST_SVCCOMP_STARTED,
          new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.INSTALLED,
          State.INSTALLED,
          ServiceComponentHostEventType.HOST_SVCCOMP_STOPPED,
          new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.STARTING,
         State.STARTING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
         
     .addTransition(State.STARTING,
         State.STARTING,
         ServiceComponentHostEventType.HOST_SVCCOMP_START,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.STARTING,
         State.STARTED,
         ServiceComponentHostEventType.HOST_SVCCOMP_STARTED,
         new ServiceComponentHostOpCompletedTransition())
         
     .addTransition(State.STARTING,
         State.INSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.INSTALLED,
         State.STARTING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.STARTED,
          State.STARTED,
          ServiceComponentHostEventType.HOST_SVCCOMP_STARTED,
          new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.STARTED,
         State.STOPPING,
         ServiceComponentHostEventType.HOST_SVCCOMP_STOP,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.STARTED,
          State.STARTED,
          ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
          new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.STARTED,
          State.INSTALLED,
          ServiceComponentHostEventType.HOST_SVCCOMP_STOPPED,
          new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.STOPPING,
         State.STOPPING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.STOPPING,
         State.INSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_STOPPED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.STOPPING,
         State.STARTED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.STARTED,
         State.STOPPING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.UNINSTALLING,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.UNINSTALLING,
         State.UNINSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.UNINSTALLING,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.UPGRADING,
         State.UPGRADING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.UPGRADING,
         State.INSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.UPGRADING,
         State.UPGRADING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.UPGRADING,
         State.UPGRADING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UPGRADE,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.UPGRADING,
         State.UPGRADING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UPGRADE,
         new ServiceComponentHostOpInProgressTransition())

     .addTransition(State.UNINSTALLING,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.UNINSTALLING,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UNINSTALL,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.UNINSTALLED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.UNINSTALLED,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_WIPEOUT,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.WIPING_OUT,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.WIPING_OUT,
         State.INIT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.WIPING_OUT,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.WIPING_OUT,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.WIPING_OUT,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_WIPEOUT,
         new ServiceComponentHostOpStartedTransition())

      .addTransition(State.INSTALLED,
          State.MAINTENANCE,
          ServiceComponentHostEventType.HOST_SVCCOMP_MAINTENANCE,
          new ServiceComponentHostOpCompletedTransition())
      .addTransition(State.MAINTENANCE,
          State.MAINTENANCE,
          ServiceComponentHostEventType.HOST_SVCCOMP_MAINTENANCE,
          new ServiceComponentHostOpCompletedTransition())
      .addTransition(State.MAINTENANCE,
          State.INSTALLED,
          ServiceComponentHostEventType.HOST_SVCCOMP_RESTORE,
          new ServiceComponentHostOpCompletedTransition())
      
      .addTransition(State.UNKNOWN,
          State.MAINTENANCE,
          ServiceComponentHostEventType.HOST_SVCCOMP_MAINTENANCE,
          new ServiceComponentHostOpCompletedTransition())

     .installTopology();

  private static final StateMachineFactory
  <ServiceComponentHostImpl, State,
  ServiceComponentHostEventType, ServiceComponentHostEvent>
    clientStateMachineFactory
      = new StateMachineFactory<ServiceComponentHostImpl,
          State, ServiceComponentHostEventType,
          ServiceComponentHostEvent>
          (State.INIT)

  // define the state machine of a HostServiceComponent for client only
  // components

     .addTransition(State.INIT,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.INSTALLING,
         State.INSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
    

    
     .addTransition(State.INSTALLING,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())
     
     .addTransition(State.INSTALLING,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.INSTALLING,
         State.INSTALL_FAILED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.INSTALL_FAILED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALL_FAILED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())

    .addTransition(State.INSTALLED,
         State.INSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.INSTALLED,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UNINSTALL,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALLED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())
    .addTransition(State.INSTALLED,
         State.UPGRADING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UPGRADE,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.UPGRADING,
         State.UPGRADING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.UPGRADING,
         State.INSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.UPGRADING,
         State.UPGRADING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.UPGRADING,
         State.UPGRADING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UPGRADE,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.UPGRADING,
         State.UPGRADING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UPGRADE,
         new ServiceComponentHostOpInProgressTransition())

     .addTransition(State.UNINSTALLING,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.UNINSTALLING,
         State.UNINSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.UNINSTALLING,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.UNINSTALLING,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.UNINSTALLING,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UNINSTALL,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.UNINSTALLED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.UNINSTALLED,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_WIPEOUT,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.WIPING_OUT,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.WIPING_OUT,
         State.INIT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.WIPING_OUT,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.WIPING_OUT,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.WIPING_OUT,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_WIPEOUT,
         new ServiceComponentHostOpStartedTransition())

     .installTopology();


  private final StateMachine<State,
      ServiceComponentHostEventType, ServiceComponentHostEvent> stateMachine;

  static class ServiceComponentHostOpCompletedTransition
     implements SingleArcTransition<ServiceComponentHostImpl,
         ServiceComponentHostEvent> {

    @Override
    public void transition(ServiceComponentHostImpl impl,
        ServiceComponentHostEvent event) {
      // TODO Audit logs
      impl.updateLastOpInfo(event.getType(), event.getOpTimestamp());
    }

  }

  static class ServiceComponentHostOpStartedTransition
    implements SingleArcTransition<ServiceComponentHostImpl,
        ServiceComponentHostEvent> {

    @Override
    public void transition(ServiceComponentHostImpl impl,
        ServiceComponentHostEvent event) {
      // TODO Audit logs
      // FIXME handle restartOp event
      impl.updateLastOpInfo(event.getType(), event.getOpTimestamp());
      if (event.getType() == ServiceComponentHostEventType.HOST_SVCCOMP_START) {
        ServiceComponentHostStartEvent e =
            (ServiceComponentHostStartEvent) event;
        if (LOG.isDebugEnabled()) {
          LOG.debug("Updating live state configs during START event"
              + ", updated configs set size=" + e.getConfigs().size());
        }
        impl.setConfigs(e.getConfigs());
      } else if (event.getType() ==
          ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL) {
        ServiceComponentHostInstallEvent e =
            (ServiceComponentHostInstallEvent) event;
        if (LOG.isDebugEnabled()) {
          LOG.debug("Updating live stack version during INSTALL event"
              + ", new stack verion=" + e.getStackId());
        }
        impl.setStackVersion(new StackId(e.getStackId()));
      }
    }
  }

  static class ServiceComponentHostOpInProgressTransition
    implements SingleArcTransition<ServiceComponentHostImpl,
        ServiceComponentHostEvent> {

    @Override
    public void transition(ServiceComponentHostImpl impl,
        ServiceComponentHostEvent event) {
      // TODO Audit logs
      impl.updateLastOpInfo(event.getType(), event.getOpTimestamp());
    }
  }


  private void resetLastOpInfo() {
    clusterGlobalLock.readLock().lock();
    try {
      try {
        writeLock.lock();
        setLastOpStartTime(-1);
        setLastOpLastUpdateTime(-1);
        setLastOpEndTime(-1);
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  private void updateLastOpInfo(ServiceComponentHostEventType eventType,
      long time) {
    clusterGlobalLock.readLock().lock();
    try {
      try {
        writeLock.lock();
        switch (eventType) {
          case HOST_SVCCOMP_INSTALL:
          case HOST_SVCCOMP_START:
          case HOST_SVCCOMP_STOP:
          case HOST_SVCCOMP_UNINSTALL:
          case HOST_SVCCOMP_WIPEOUT:
          case HOST_SVCCOMP_OP_RESTART:
            resetLastOpInfo();
            setLastOpStartTime(time);
            break;
          case HOST_SVCCOMP_OP_FAILED:
          case HOST_SVCCOMP_OP_SUCCEEDED:
          case HOST_SVCCOMP_STOPPED:
          case HOST_SVCCOMP_STARTED:
            setLastOpLastUpdateTime(time);
            setLastOpEndTime(time);
            break;
          case HOST_SVCCOMP_OP_IN_PROGRESS:
            setLastOpLastUpdateTime(time);
            break;
        }
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @AssistedInject
  public ServiceComponentHostImpl(@Assisted ServiceComponent serviceComponent,
                                  @Assisted String hostName, @Assisted boolean isClient, Injector injector) {
    injector.injectMembers(this);

    if (isClient) {
      this.stateMachine = clientStateMachineFactory.make(this);
    } else {
      this.stateMachine = daemonStateMachineFactory.make(this);
    }

    this.serviceComponent = serviceComponent;
    this.clusterGlobalLock = serviceComponent.getClusterGlobalLock();

    stateEntity = new HostComponentStateEntity();
    stateEntity.setClusterId(serviceComponent.getClusterId());
    stateEntity.setComponentName(serviceComponent.getName());
    stateEntity.setServiceName(serviceComponent.getServiceName());
    stateEntity.setHostName(hostName);
    stateEntity.setCurrentState(stateMachine.getCurrentState());
    stateEntity.setCurrentStackVersion(gson.toJson(new StackId()));

    desiredStateEntity = new HostComponentDesiredStateEntity();
    desiredStateEntity.setClusterId(serviceComponent.getClusterId());
    desiredStateEntity.setComponentName(serviceComponent.getName());
    desiredStateEntity.setServiceName(serviceComponent.getServiceName());
    desiredStateEntity.setHostName(hostName);
    desiredStateEntity.setDesiredState(State.INIT);
    desiredStateEntity.setDesiredStackVersion(
        gson.toJson(serviceComponent.getDesiredStackVersion()));

    try {
      this.host = clusters.getHost(hostName);
    } catch (AmbariException e) {
      //TODO exception?
      LOG.error("Host '{}' was not found" + hostName);
      throw new RuntimeException(e);
    }

    this.resetLastOpInfo();
    this.desiredConfigs = new HashMap<String, String>();
    this.configs = new HashMap<String, String>();
  }

  @AssistedInject
  public ServiceComponentHostImpl(@Assisted ServiceComponent serviceComponent,
                                  @Assisted HostComponentStateEntity stateEntity,
                                  @Assisted HostComponentDesiredStateEntity desiredStateEntity,
                                  Injector injector) {
    injector.injectMembers(this);
    this.serviceComponent = serviceComponent;
    this.clusterGlobalLock = serviceComponent.getClusterGlobalLock();

    this.desiredStateEntity = desiredStateEntity;
    this.stateEntity = stateEntity;
    //TODO implement State Machine init as now type choosing is hardcoded in above code
    if (serviceComponent.isClientComponent()) {
      this.stateMachine = clientStateMachineFactory.make(this);
    } else {
      this.stateMachine = daemonStateMachineFactory.make(this);
    }
    this.stateMachine.setCurrentState(stateEntity.getCurrentState());

    try {
      this.host = clusters.getHost(stateEntity.getHostName());
    } catch (AmbariException e) {
      //TODO exception? impossible due to database restrictions
      LOG.error("Host '{}' was not found " + stateEntity.getHostName());
      throw new RuntimeException(e);
    }

    desiredConfigs = new HashMap<String, String>();
    configs = new HashMap<String, String>();

    for (HostComponentDesiredConfigMappingEntity entity : desiredStateEntity.getHostComponentDesiredConfigMappingEntities()) {
      desiredConfigs.put(entity.getConfigType(), entity.getVersionTag());
    }
    // FIXME no configs in live state being persisted in DB??

    persisted = true;
  }

  @Override
  public State getState() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return stateMachine.getCurrentState();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public void setState(State state) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        stateMachine.setCurrentState(state);
        stateEntity.setCurrentState(state);
        saveIfPersisted();
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  @Transactional
  public void handleEvent(ServiceComponentHostEvent event)
      throws InvalidStateTransitionException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Handling ServiceComponentHostEvent event,"
          + " eventType=" + event.getType().name()
          + ", event=" + event.toString());
    }
    State oldState = getState();
    clusterGlobalLock.readLock().lock();
    try {
      try {
        writeLock.lock();
        try {
          stateMachine.doTransition(event.getType(), event);
          stateEntity.setCurrentState(stateMachine.getCurrentState());
          saveIfPersisted();
          // TODO Audit logs
        } catch (InvalidStateTransitionException e) {
          LOG.error("Can't handle ServiceComponentHostEvent event at"
              + " current state"
              + ", serviceComponentName=" + this.getServiceComponentName()
              + ", hostName=" + this.getHostName()
              + ", currentState=" + oldState
              + ", eventType=" + event.getType()
              + ", event=" + event);
          throw e;
        }
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

    if (!oldState.equals(getState())) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("ServiceComponentHost transitioned to a new state"
            + ", serviceComponentName=" + this.getServiceComponentName()
            + ", hostName=" + this.getHostName()
            + ", oldState=" + oldState
            + ", currentState=" + getState()
            + ", eventType=" + event.getType().name()
            + ", event=" + event);
      }
    }
  }

  @Override
  public String getServiceComponentName() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return serviceComponent.getName();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }


  }

  @Override
  public String getHostName() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return host.getHostName();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }


  }

  /**
   * @return the lastOpStartTime
   */
  public long getLastOpStartTime() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return lastOpStartTime;
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  /**
   * @param lastOpStartTime the lastOpStartTime to set
   */
  public void setLastOpStartTime(long lastOpStartTime) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        this.lastOpStartTime = lastOpStartTime;
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  /**
   * @return the lastOpEndTime
   */
  public long getLastOpEndTime() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return lastOpEndTime;
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  /**
   * @param lastOpEndTime the lastOpEndTime to set
   */
  public void setLastOpEndTime(long lastOpEndTime) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        this.lastOpEndTime = lastOpEndTime;
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  /**
   * @return the lastOpLastUpdateTime
   */
  public long getLastOpLastUpdateTime() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return lastOpLastUpdateTime;
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  /**
   * @param lastOpLastUpdateTime the lastOpLastUpdateTime to set
   */
  public void setLastOpLastUpdateTime(long lastOpLastUpdateTime) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        this.lastOpLastUpdateTime = lastOpLastUpdateTime;
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public long getClusterId() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return serviceComponent.getClusterId();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }


  }

  @Override
  public String getServiceName() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return serviceComponent.getServiceName();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }


  }

  Map<String, String> getConfigVersions() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        if (this.configs != null) {
          return Collections.unmodifiableMap(configs);
        } else {
          return new HashMap<String, String>();
        }
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }


  }

  @Override
  public Map<String, Config> getConfigs() throws AmbariException {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        Map<String, Config> map = new HashMap<String, Config>();
        Cluster cluster = clusters.getClusterById(getClusterId());
        for (Entry<String, String> entry : configs.entrySet()) {
          Config config = cluster.getConfig(
              entry.getKey(), entry.getValue());
          if (null != config) {
            map.put(entry.getKey(), config);
          }
        }
        return map;
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Transactional
  void setConfigs(Map<String, String> configs) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {

        Set<String> deletedTypes = new HashSet<String>();
        for (String type : this.configs.keySet()) {
          if (!configs.containsKey(type)) {
            deletedTypes.add(type);
          }
        }

        long now = Long.valueOf(new Date().getTime());

        for (Entry<String, String> entry : configs.entrySet()) {

          boolean contains = false;
          for (HostComponentConfigMappingEntity mappingEntity : stateEntity.getHostComponentConfigMappingEntities()) {
            if (entry.getKey().equals(mappingEntity.getConfigType())) {
              if (LOG.isDebugEnabled()) {
                LOG.debug("Updating live config to ServiceComponentHost"
                    + ", clusterId=" + stateEntity.getClusterId()
                    + ", serviceName=" + stateEntity.getServiceName()
                    + ", componentName=" + stateEntity.getComponentName()
                    + ", hostname=" + stateEntity.getHostName()
                    + ", configType=" + entry.getKey()
                    + ", configVersionTag=" + entry.getValue());
              }
              contains = true;
              mappingEntity.setVersionTag(entry.getValue());
              mappingEntity.setTimestamp(now);
              hostComponentConfigMappingDAO.merge(mappingEntity);
              break;
            }
          }

          if (!contains) {
            HostComponentConfigMappingEntity newEntity =
                new HostComponentConfigMappingEntity();
            newEntity.setClusterId(stateEntity.getClusterId());
            newEntity.setServiceName(stateEntity.getServiceName());
            newEntity.setComponentName(stateEntity.getComponentName());
            newEntity.setHostName(stateEntity.getHostName());
            newEntity.setConfigType(entry.getKey());
            newEntity.setVersionTag(entry.getValue());
            newEntity.setTimestamp(now);

            if (LOG.isDebugEnabled()) {
              LOG.debug("Adding new live config to ServiceComponentHost"
                  + ", clusterId=" + stateEntity.getClusterId()
                  + ", serviceName=" + stateEntity.getServiceName()
                  + ", componentName=" + stateEntity.getComponentName()
                  + ", hostname=" + stateEntity.getHostName()
                  + ", configType=" + entry.getKey()
                  + ", configVersionTag=" + entry.getValue());
            }
            stateEntity.getHostComponentConfigMappingEntities().add(newEntity);
            newEntity.setHostComponentStateEntity(stateEntity);
            hostComponentConfigMappingDAO.create(newEntity);
          }
        }

        if (!deletedTypes.isEmpty()) {
          List<HostComponentConfigMappingEntity> deleteEntities =
              hostComponentConfigMappingDAO.findByHostComponentAndType(
                  stateEntity.getClusterId(), stateEntity.getServiceName(),
                  stateEntity.getComponentName(),
                  stateEntity.getHostName(), deletedTypes);
          for (HostComponentConfigMappingEntity deleteEntity : deleteEntities) {
            if (LOG.isDebugEnabled()) {
              LOG.debug("Deleting live config to ServiceComponentHost"
                  + ", clusterId=" + stateEntity.getClusterId()
                  + ", serviceName=" + stateEntity.getServiceName()
                  + ", componentName=" + stateEntity.getComponentName()
                  + ", hostname=" + stateEntity.getHostName()
                  + ", configType=" + deleteEntity.getConfigType()
                  + ", configVersionTag=" + deleteEntity.getVersionTag());
            }
            stateEntity.getHostComponentConfigMappingEntities().remove(
                deleteEntity);
            if (persisted) {
              hostComponentConfigMappingDAO.remove(deleteEntity);
            }
          }
        }
        this.configs = configs;
        saveIfPersisted();
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public StackId getStackVersion() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return gson.fromJson(stateEntity.getCurrentStackVersion(), StackId.class);
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public void setStackVersion(StackId stackVersion) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        stateEntity.setCurrentStackVersion(gson.toJson(stackVersion));
        saveIfPersisted();
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }


  @Override
  public State getDesiredState() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return desiredStateEntity.getDesiredState();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public void setDesiredState(State state) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        desiredStateEntity.setDesiredState(state);
        saveIfPersisted();
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public Map<String, String> getDesiredConfigVersionsRecursive() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        Map<String, String> fullDesiredConfigVersions =
            new HashMap<String, String>();
        Map<String, Config> desiredConfs = getDesiredConfigs();
        for (Config c : desiredConfs.values()) {
          fullDesiredConfigVersions.put(c.getType(), c.getVersionTag());
        }
        return fullDesiredConfigVersions;
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }


  @Override
  public Map<String, Config> getDesiredConfigs() {
    clusterGlobalLock.readLock().lock();
    try {
      Map<String, Config> map = new HashMap<String, Config>();
      readLock.lock();
      try {
        for (Entry<String, String> entry : desiredConfigs.entrySet()) {
          Config config = clusters.getClusterById(getClusterId()).getConfig(
              entry.getKey(), entry.getValue());
          if (null != config) {
            map.put(entry.getKey(), config);
          }
        }
      } catch (AmbariException e) {
        // TODO do something
        return null;
      } finally {
        readLock.unlock();
      }
      // do a union with component level configs
      Map<String, Config> compConfigs = serviceComponent.getDesiredConfigs();
      for (Entry<String, Config> entry : compConfigs.entrySet()) {
        if (!map.containsKey(entry.getKey())) {
          map.put(entry.getKey(), entry.getValue());
        }
      }
      return Collections.unmodifiableMap(map);
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  @Transactional
  public void updateDesiredConfigs(Map<String, Config> configs) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {

        for (Entry<String, Config> entry : configs.entrySet()) {

          boolean contains = false;
          for (HostComponentDesiredConfigMappingEntity desiredConfigMappingEntity : desiredStateEntity.getHostComponentDesiredConfigMappingEntities()) {
            if (entry.getKey().equals(desiredConfigMappingEntity.getConfigType())) {
              contains = true;
              desiredConfigMappingEntity.setVersionTag(entry.getValue().getVersionTag());
              desiredConfigMappingEntity.setTimestamp(new Date().getTime());
              hostComponentDesiredConfigMappingDAO.merge(desiredConfigMappingEntity);
              break;
            }
          }

          if (!contains) {
            HostComponentDesiredConfigMappingEntity newEntity = new HostComponentDesiredConfigMappingEntity();
            newEntity.setClusterId(desiredStateEntity.getClusterId());
            newEntity.setServiceName(desiredStateEntity.getServiceName());
            newEntity.setComponentName(desiredStateEntity.getComponentName());
            newEntity.setHostName(desiredStateEntity.getHostName());
            newEntity.setConfigType(entry.getKey());
            newEntity.setVersionTag(entry.getValue().getVersionTag());
            newEntity.setTimestamp(new Date().getTime());
            newEntity.setHostComponentDesiredStateEntity(desiredStateEntity);
            desiredStateEntity.getHostComponentDesiredConfigMappingEntities().add(newEntity);
            hostComponentDesiredConfigMappingDAO.create(newEntity);
          }

          this.desiredConfigs.put(entry.getKey(), entry.getValue().getVersionTag());
        }

        saveIfPersisted();
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public StackId getDesiredStackVersion() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return gson.fromJson(desiredStateEntity.getDesiredStackVersion(), StackId.class);
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public void setDesiredStackVersion(StackId stackVersion) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        desiredStateEntity.setDesiredStackVersion(gson.toJson(stackVersion));
        saveIfPersisted();
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public ServiceComponentHostResponse convertToResponse() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        ServiceComponentHostResponse r = new ServiceComponentHostResponse(
            serviceComponent.getClusterName(),
            serviceComponent.getServiceName(),
            serviceComponent.getName(),
            getHostName(),
            configs,
            desiredConfigs,
            getState().toString(),
            getStackVersion().getStackId(),
            getDesiredState().toString(),
            getDesiredStackVersion().getStackId());

        r.setActualConfigs(actualConfigs);

        try {
          r.setStaleConfig(helper.isStaleConfigs(this));
        } catch (Exception e) {
          LOG.error("Could not determine stale config", e);
        }
        
        return r;
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public String getClusterName() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return serviceComponent.getClusterName();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public void debugDump(StringBuilder sb) {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        sb.append("ServiceComponentHost={ hostname=" + getHostName()
            + ", serviceComponentName=" + serviceComponent.getName()
            + ", clusterName=" + serviceComponent.getClusterName()
            + ", serviceName=" + serviceComponent.getServiceName()
            + ", desiredStackVersion=" + getDesiredStackVersion()
            + ", desiredState=" + getDesiredState()
            + ", stackVersion=" + getStackVersion()
            + ", state=" + getState()
            + " }");
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public boolean isPersisted() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return persisted;
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public void persist() {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        if (!persisted) {
          persistEntities();
          refresh();
          host.refresh();
          serviceComponent.refresh();
          persisted = true;
        } else {
          saveIfPersisted();
        }
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Transactional
  protected void persistEntities() {
    HostEntity hostEntity = hostDAO.findByName(getHostName());
    hostEntity.getHostComponentStateEntities().add(stateEntity);
    hostEntity.getHostComponentDesiredStateEntities().add(desiredStateEntity);

    ServiceComponentDesiredStateEntityPK dpk = new ServiceComponentDesiredStateEntityPK();
    dpk.setClusterId(serviceComponent.getClusterId());
    dpk.setServiceName(serviceComponent.getServiceName());
    dpk.setComponentName(serviceComponent.getName());

    ServiceComponentDesiredStateEntity serviceComponentDesiredStateEntity = serviceComponentDesiredStateDAO.findByPK(dpk);
    serviceComponentDesiredStateEntity.getHostComponentDesiredStateEntities().add(desiredStateEntity);

    desiredStateEntity.setServiceComponentDesiredStateEntity(serviceComponentDesiredStateEntity);
    desiredStateEntity.setHostEntity(hostEntity);
    stateEntity.setServiceComponentDesiredStateEntity(serviceComponentDesiredStateEntity);
    stateEntity.setHostEntity(hostEntity);

    hostComponentStateDAO.create(stateEntity);
    hostComponentDesiredStateDAO.create(desiredStateEntity);

    serviceComponentDesiredStateDAO.merge(serviceComponentDesiredStateEntity);
    hostDAO.merge(hostEntity);
  }

  @Override
  @Transactional
  public void refresh() {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        if (isPersisted()) {
          HostComponentStateEntityPK pk = new HostComponentStateEntityPK();
          HostComponentDesiredStateEntityPK dpk = new HostComponentDesiredStateEntityPK();
          pk.setClusterId(getClusterId());
          pk.setComponentName(getServiceComponentName());
          pk.setServiceName(getServiceName());
          pk.setHostName(getHostName());
          dpk.setClusterId(getClusterId());
          dpk.setComponentName(getServiceComponentName());
          dpk.setServiceName(getServiceName());
          dpk.setHostName(getHostName());
          stateEntity = hostComponentStateDAO.findByPK(pk);
          desiredStateEntity = hostComponentDesiredStateDAO.findByPK(dpk);
          hostComponentStateDAO.refresh(stateEntity);
          hostComponentDesiredStateDAO.refresh(desiredStateEntity);
        }
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Transactional
  private void saveIfPersisted() {
    if (isPersisted()) {
      hostComponentStateDAO.merge(stateEntity);
      hostComponentDesiredStateDAO.merge(desiredStateEntity);
    }
  }

  @Override
  public boolean canBeRemoved() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {

        return (getState().isRemovableState());

      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public void deleteDesiredConfigs(Set<String> configTypes) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        hostComponentDesiredConfigMappingDAO.removeByType(configTypes);
        for (String configType : configTypes) {
          desiredConfigs.remove(configType);
        }
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public void delete() {
    clusterGlobalLock.writeLock().lock();
    try {
      writeLock.lock();
      try {
        if (persisted) {
          removeEntities();
          persisted = false;
        }
        clusters.getCluster(this.getClusterName()).removeServiceComponentHost(this);
        desiredConfigs.clear();
      } catch (AmbariException ex) {
        if (LOG.isDebugEnabled()) {
          LOG.error(ex.getMessage());
        }
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }

  }

  @Transactional
  protected void removeEntities() {
    HostComponentStateEntityPK pk = new HostComponentStateEntityPK();
    pk.setClusterId(stateEntity.getClusterId());
    pk.setComponentName(stateEntity.getComponentName());
    pk.setServiceName(stateEntity.getServiceName());
    pk.setHostName(stateEntity.getHostName());

    hostComponentStateDAO.removeByPK(pk);

    HostComponentDesiredStateEntityPK desiredPK = new HostComponentDesiredStateEntityPK();
    desiredPK.setClusterId(desiredStateEntity.getClusterId());
    desiredPK.setComponentName(desiredStateEntity.getComponentName());
    desiredPK.setServiceName(desiredStateEntity.getServiceName());
    desiredPK.setHostName(desiredStateEntity.getHostName());

    hostComponentDesiredStateDAO.removeByPK(desiredPK);
  }
  
  @Override
  public void updateActualConfigs(Map<String, Map<String, String>> configTags) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {

        actualConfigs = new HashMap<String, DesiredConfig>();

        String hostName = getHostName();

        for (Entry<String, Map<String, String>> entry : configTags.entrySet()) {
          String type = entry.getKey();
          Map<String, String> values = entry.getValue();

          String tag = values.get("tag");
          String hostTag = values.get("host_override_tag");

          DesiredConfig dc = new DesiredConfig();
          dc.setServiceName(values.get("service_override_tag"));
          dc.setVersion(tag);
          actualConfigs.put(type, dc);
          if (null != hostTag && null != hostName) {
            List<HostOverride> list = new ArrayList<HostOverride>();
            list.add(new HostOverride(hostName, hostTag));
            dc.setHostOverrides(list);
          }
        }
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }
  
  @Override
  public Map<String, DesiredConfig> getActualConfigs() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return actualConfigs;
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public HostState getHostState() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return host.getState();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }
  
  
}
