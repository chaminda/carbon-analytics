/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.carbon.event.processor.manager.core.internal;

import com.hazelcast.core.*;
import org.apache.log4j.Logger;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.event.processor.manager.commons.transport.client.TCPEventPublisher;
import org.wso2.carbon.event.processor.manager.commons.transport.server.StreamCallback;
import org.wso2.carbon.event.processor.manager.commons.transport.server.TCPEventServer;
import org.wso2.carbon.event.processor.manager.commons.transport.server.TCPEventServerConfig;
import org.wso2.carbon.event.processor.manager.commons.utils.HostAndPort;
import org.wso2.carbon.event.processor.manager.core.*;
import org.wso2.carbon.event.processor.manager.core.config.*;
import org.wso2.carbon.event.processor.manager.core.exception.EventManagementException;
import org.wso2.carbon.event.processor.manager.core.exception.ManagementConfigurationException;
import org.wso2.carbon.event.processor.manager.core.internal.ds.EventManagementServiceValueHolder;
import org.wso2.carbon.event.processor.manager.core.internal.util.ConfigurationConstants;
import org.wso2.carbon.event.processor.manager.core.internal.util.ManagementModeConfigurationLoader;
import org.wso2.carbon.utils.ConfigurationContextService;
import org.wso2.siddhi.core.event.Event;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class CarbonEventManagementService implements EventManagementService {

    private static Logger log = Logger.getLogger(CarbonEventManagementService.class);

    private Mode mode = Mode.SingleNode;
    private ManagementModeInfo managementModeInfo;

    private EventProcessorManagementService processorManager;
    private EventReceiverManagementService receiverManager;
    private EventPublisherManagementService publisherManager;

    private IMap<String, HostAndPort> members;
    private CopyOnWriteArrayList<HostAndPort> syncMembers = new CopyOnWriteArrayList<HostAndPort>();
    private ConcurrentHashMap<String, EventSync> eventSyncMap = new ConcurrentHashMap<String, EventSync>();
    private TCPEventServer tcpEventServer;
    private ConcurrentHashMap<HostAndPort, TCPEventPublisher> tcpEventPublisherPool = new ConcurrentHashMap<HostAndPort, TCPEventPublisher>();

    private ScheduledExecutorService executorService = new ScheduledThreadPoolExecutor(3);

    private HAManager haManager = null;
    private IMap<String, Long> haEventPublisherTimeSyncMap = null;

    private PersistenceManager persistenceManager = null;

    private StormReceiverCoordinator stormReceiverCoordinator = null;

    private boolean isManagerNode = false;
    private boolean isWorkerNode = false;
    private boolean isPresenterNode = false;


    public CarbonEventManagementService() {
        try {
            managementModeInfo = ManagementModeConfigurationLoader.loadManagementModeInfo();
            mode = managementModeInfo.getMode();
        } catch (ManagementConfigurationException e) {
            throw new EventManagementException("Error getting management mode information", e);
        }
        if (mode == Mode.HA) {
            HAConfiguration haConfiguration = managementModeInfo.getHaConfiguration();
            startServer(haConfiguration.getTransport());
        } else if (mode == Mode.SingleNode) {
            PersistenceConfiguration persistConfig = managementModeInfo.getPersistenceConfiguration();
            if (persistConfig != null) {
                ScheduledExecutorService scheduledExecutorService = Executors
                        .newScheduledThreadPool(persistConfig.getThreadPoolSize());
                long persistenceTimeInterval = persistConfig.getPersistenceTimeInterval();
                if (persistenceTimeInterval > 0) {
                    persistenceManager = new PersistenceManager(scheduledExecutorService, persistenceTimeInterval);
                    persistenceManager.init();
                }
            }
        } else if (mode == Mode.Distributed) {
            DistributedConfiguration distributedConfiguration = managementModeInfo.getDistributedConfiguration();
            isManagerNode = distributedConfiguration.isManagerNode();
            isWorkerNode = distributedConfiguration.isWorkerNode();
            if (isWorkerNode) {
                stormReceiverCoordinator = new StormReceiverCoordinator();
            }
            isPresenterNode = distributedConfiguration.isPresenterNode();
            if (isPresenterNode) {
                startServer(distributedConfiguration.getLocalPresenterConfig());
            }
        }
    }

    public void init(HazelcastInstance hazelcastInstance) {

        members = hazelcastInstance.getMap(ConfigurationConstants.MEMBERS);

        if (mode == Mode.HA) {
            HAConfiguration haConfiguration = managementModeInfo.getHaConfiguration();
            haManager = new HAManager(hazelcastInstance, haConfiguration, executorService);
            haManager.init();

            members.set(hazelcastInstance.getCluster().getLocalMember().getUuid(), haConfiguration.getTransport());

            if (haEventPublisherTimeSyncMap == null) {
                haEventPublisherTimeSyncMap = EventManagementServiceValueHolder.getHazelcastInstance()
                        .getMap(ConfigurationConstants.HA_EVENT_PUBLISHER_TIME_SYNC_MAP);
            }
        } else if (mode == Mode.Distributed) {
            if (stormReceiverCoordinator != null) {
                stormReceiverCoordinator.tryBecomeCoordinator();
            }
            if (isPresenterNode) {
                DistributedConfiguration distributedConfiguration = managementModeInfo.getDistributedConfiguration();
                members.set(hazelcastInstance.getCluster().getLocalMember().getUuid(), distributedConfiguration.getLocalPresenterConfig());
            }
            checkMemberUpdate();
        } else if (mode == Mode.SingleNode) {
            log.warn("CEP started with clustering enabled, but SingleNode configuration given.");
        }

        hazelcastInstance.getCluster().addMembershipListener(new MembershipListener() {
            @Override
            public void memberAdded(MembershipEvent membershipEvent) {
                checkMemberUpdate();
                if (haManager != null) {
                    haManager.verifyState();
                }
            }

            @Override
            public void memberRemoved(MembershipEvent membershipEvent) {
                members.remove(membershipEvent.getMember().getUuid());
                checkMemberUpdate();
                if (mode == Mode.HA) {
                    if (haManager != null) {
                        haManager.tryChangeState();
                    }
                } else if (mode == mode.Distributed) {
                    if (stormReceiverCoordinator != null) {
                        stormReceiverCoordinator.tryBecomeCoordinator();
                    }
                }
            }

            @Override
            public void memberAttributeChanged(MemberAttributeEvent memberAttributeEvent) {

            }

        });

    }

    public void init(ConfigurationContextService configurationContextService) {
        if (mode == Mode.SingleNode || isWorkerNode) {
            receiverManager.start();
        }
        if (mode != Mode.Distributed || isWorkerNode) {
            executorService.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        log.info("Starting polling event adapters");
                        EventReceiverManagementService eventReceiverManagementService = getEventReceiverManagementService();
                        if (eventReceiverManagementService != null) {
                            eventReceiverManagementService.startPolling();
                        } else {
                            log.error("Adapter polling failed as EventReceiverManagementService not available");
                        }
                    } catch (Exception e) {
                        log.error("Unexpected error occurred when start polling event adapters", e);
                    }
                }
            }, ConfigurationConstants.AXIS_TIME_INTERVAL_IN_MILLISECONDS * 4, TimeUnit.MILLISECONDS);
        }

        executorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                checkMemberUpdate();
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    public void shutdown() {
        if (haManager != null) {
            haManager.shutdown();
        }
        if (executorService != null) {
            executorService.shutdown();
        }
        if (persistenceManager != null) {
            persistenceManager.shutdown();
        }
        if (members != null) {
            members.remove(EventManagementServiceValueHolder.getHazelcastInstance().getCluster().getLocalMember().getUuid());
        }
        syncMembers.clear();
        if (tcpEventServer != null) {
            tcpEventServer.shutdown();
        }
    }

    public byte[] getState() {
        if (mode == Mode.HA) {
            return haManager.getState();
        }
        return null;
    }

    public ManagementModeInfo getManagementModeInfo() {
        return managementModeInfo;
    }

    public void subscribe(Manager manager) {
        if (manager.getType() == Manager.ManagerType.Processor) {
            this.processorManager = (EventProcessorManagementService) manager;
        } else if (manager.getType() == Manager.ManagerType.Receiver) {
            this.receiverManager = (EventReceiverManagementService) manager;
        } else if (manager.getType() == Manager.ManagerType.Publisher) {
            this.publisherManager = (EventPublisherManagementService) manager;
        }
    }

    @Override
    public void unsubscribe(Manager manager) {
        if (manager.getType() == Manager.ManagerType.Processor) {
            this.processorManager = null;
        } else if (manager.getType() == Manager.ManagerType.Receiver) {
            this.receiverManager = null;
        } else if (manager.getType() == Manager.ManagerType.Publisher) {
            this.publisherManager = null;
        }
    }

    @Override
    public void syncEvent(String syncId, Event event) {
        if (syncMembers != null) {
            for (HostAndPort member : syncMembers) {
                TCPEventPublisher publisher = tcpEventPublisherPool.get(member);
                if (publisher != null) {
                    try {
                        publisher.sendEvent(syncId, event.getTimestamp(), event.getData(), true);
                    } catch (IOException e) {
                        log.error("Error sending sync events to " + syncId, e);
                    }
                }
            }
        }

    }

    @Override
    public void registerEventSync(EventSync eventSync) {
        eventSyncMap.putIfAbsent(eventSync.getStreamDefinition().getId(), eventSync);
        for (TCPEventPublisher tcpEventPublisher : tcpEventPublisherPool.values()) {
            tcpEventPublisher.addStreamDefinition(eventSync.getStreamDefinition());
        }
        if (tcpEventServer != null) {
            tcpEventServer.addStreamDefinition(eventSync.getStreamDefinition());
        }
    }

    @Override
    public void unregisterEventSync(String syncId) {
        EventSync eventSync = eventSyncMap.remove(syncId);
        if (eventSync != null) {
            for (TCPEventPublisher tcpEventPublisher : tcpEventPublisherPool.values()) {
                tcpEventPublisher.removeStreamDefinition(eventSync.getStreamDefinition());
            }
            if (tcpEventServer != null) {
                tcpEventServer.removeStreamDefinition(eventSync.getStreamDefinition().getId());
            }
        }

    }

    public EventProcessorManagementService getEventProcessorManagementService() {
        return processorManager;
    }

    public EventReceiverManagementService getEventReceiverManagementService() {
        return receiverManager;
    }

    public EventPublisherManagementService getEventPublisherManagementService() {
        return publisherManager;
    }

    private void startServer(HostAndPort member) {
        if (tcpEventServer == null) {
            TCPEventServerConfig tcpEventServerConfig = new TCPEventServerConfig(member.getPort());
            tcpEventServerConfig.setNumberOfThreads(10); //todo fix
            tcpEventServer = new TCPEventServer(tcpEventServerConfig, new StreamCallback() {
                @Override
                public void receive(String streamId, long timestamp, Object[] data) {
                    int index = streamId.indexOf("/");
                    if (index != -1) {
                        int tenantId = Integer.parseInt(streamId.substring(0, index));
                        try {
                            PrivilegedCarbonContext.startTenantFlow();
                            PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantId(tenantId);
                            PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantDomain(true);

                            EventSync eventSync = eventSyncMap.get(streamId);

                            if (log.isDebugEnabled()) {
                                log.debug("Event Received to :" + streamId);
                            }
                            if (eventSync != null) {
                                eventSync.process(new Event(timestamp, data));
                            }

                        } catch (Exception e) {
                            log.error("Unable to start event adpaters for tenant :" + tenantId, e);
                        } finally {
                            PrivilegedCarbonContext.endTenantFlow();
                        }
                    }
                }
            }, null);
            for (EventSync eventSync : eventSyncMap.values()) {
                tcpEventServer.addStreamDefinition(eventSync.getStreamDefinition());
            }
            tcpEventServer.start();
            log.info("Event Management TCPEventServer for EventReceiver started on port " + member.getPort());
        }
    }

    public synchronized void updateMembers(List<HostAndPort> members) {
        List<HostAndPort> currentMembers = new ArrayList<>(tcpEventPublisherPool.keySet());

        for (HostAndPort member : members) {
            if (!currentMembers.remove(member)) {
                addMember(member);
            }
        }
        for (HostAndPort member : currentMembers) {
            removeMember(member);
        }
    }

    public synchronized void removeMember(HostAndPort member) {
        TCPEventPublisher tcpEventPublisher = tcpEventPublisherPool.remove(member);
        if (tcpEventPublisher != null) {
            tcpEventPublisher.shutdown();
            log.info("CEP sync publisher disconnected from Member '" + member.getHostName() + ":" + member.getPort() + "'");
        }
    }

    public synchronized void cleanSyncMembers(List<HostAndPort> members) {
        List<HostAndPort> currentMembers = new ArrayList<>(tcpEventPublisherPool.keySet());

        for (HostAndPort member : members) {
            currentMembers.remove(member);
        }
        for (HostAndPort member : currentMembers) {
            syncMembers.remove(member);
        }
    }

    public synchronized void addMember(HostAndPort member) {
        try {
            if (!tcpEventPublisherPool.containsKey(member)) {
                TCPEventPublisher tcpEventPublisher = new TCPEventPublisher(
                        member.getHostName() + ":" + member.getPort(), false, null);
                for (EventSync eventSync : eventSyncMap.values()) {
                    tcpEventPublisher.addStreamDefinition(eventSync.getStreamDefinition());
                }
                tcpEventPublisherPool.putIfAbsent(member, tcpEventPublisher);
                log.info("CEP sync publisher initiated to Member '" + member.getHostName() + ":" + member.getPort()
                        + "'");
            }
        } catch (IOException e) {
            log.error("Error occurred while trying to start the publisher: " + e.getMessage(), e);
        }
    }

    public void setSyncMembers(List<HostAndPort> members) {
        syncMembers.clear();
        syncMembers.addAll(members);
    }

    private void checkMemberUpdate() {
        if (members != null) {
            if (mode == Mode.Distributed) {
                List<HostAndPort> memberList = new ArrayList<HostAndPort>(members.values());
                memberList.remove(managementModeInfo.getDistributedConfiguration().getLocalPresenterConfig());
                updateMembers(memberList);
                cleanSyncMembers(memberList);
            } else if (mode == Mode.HA) {
                List<HostAndPort> memberList = new ArrayList<HostAndPort>(members.values());
                memberList.remove(managementModeInfo.getHaConfiguration().getTransport());
                updateMembers(memberList);
                cleanSyncMembers(memberList);
            }
        }
    }

    @Override
    public void updateLatestEventSentTime(String publisherName, int tenantId, long timestamp) {

        haEventPublisherTimeSyncMap.putAsync(tenantId + "-" + publisherName,
                EventManagementServiceValueHolder.getHazelcastInstance().getCluster().getClusterTime());
    }

    @Override
    public long getLatestEventSentTime(String publisherName, int tenantId) {
        if (haEventPublisherTimeSyncMap == null) {
            haEventPublisherTimeSyncMap = EventManagementServiceValueHolder.getHazelcastInstance()
                    .getMap(ConfigurationConstants.HA_EVENT_PUBLISHER_TIME_SYNC_MAP);
        }
        Object latestTimePublished = haEventPublisherTimeSyncMap.get(tenantId + "-" + publisherName);
        if (latestTimePublished != null) {
            return (Long) latestTimePublished;
        }
        return 0;
    }

    @Override
    public long getClusterTimeInMillis() {
        return EventManagementServiceValueHolder.getHazelcastInstance().getCluster().getClusterTime();
    }
}