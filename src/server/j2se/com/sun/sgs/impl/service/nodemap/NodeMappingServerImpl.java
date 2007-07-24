/*
 * Copyright 2007 Sun Microsystems, Inc.  All rights reserved.
 */

package com.sun.sgs.impl.service.nodemap;

import com.sun.sgs.app.NameNotBoundException;
import com.sun.sgs.auth.Identity;
import com.sun.sgs.impl.sharedutil.LoggerWrapper;
import com.sun.sgs.impl.sharedutil.PropertiesWrapper;
import com.sun.sgs.impl.util.AbstractKernelRunnable;
import com.sun.sgs.impl.util.BoundNamesUtil;
import com.sun.sgs.impl.util.Exporter;
import com.sun.sgs.kernel.ComponentRegistry;
import com.sun.sgs.kernel.KernelRunnable;
import com.sun.sgs.kernel.TaskOwner;
import com.sun.sgs.kernel.TaskScheduler;
import com.sun.sgs.service.DataService;
import com.sun.sgs.service.Node;
import com.sun.sgs.service.Service;
import com.sun.sgs.service.TransactionProxy;
import com.sun.sgs.service.TransactionRunner;
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The remote server portion of the node mapping service.  This
 * portion of the service is used for any global operations, such
 * as selecting a node for an identity.
 * Additionally, all changes to the map are made by the server so it
 * can notify listeners of changes, no matter which node is affected.
 *
 * In addition to the properties supported by the {@link DataService}
 * class, the {@link #NodeMappingServerImpl constructor} supports the following
 * properties: <p>
 *
 * <ul>
 * <li> <i>Key:</i> {@code
 *      com.sun.sgs.impl.service.nodemap.NodeMappingServerImpl.port} <br>
 *      <i>Default:</i> {@code 44533} <br>
 *      Specifies the network port for the server.  This value must be greater
 *      than or equal to {@code 0} and no greater than {@code 65535}.  If the
 *      value specified is {@code 0}, then an anonymous port will be chosen.
 *      The value chosen will be logged, and can also be accessed with the
 *      {@link #getPort getPort} method. <p>
 *
 * <dt> <i>Property:</i> <code><b>
 *	com.sun.sgs.impl.service.nodemap.NodeMappingServerImpl.policy.class
 *	</b></code> <br>
 *	<i>Default:</i>
 *	<code>com.sun.sgs.impl.service.nodemap.RoundRobinPolicy</code>
 *
 ** <dd style="padding-top: .5em">The name of the class that implements {@link
 *	NotifyClient}, used for the node assignment policy. The class should be
 *      public, not abstract, and should provide a public constructor with a 
 *      {@link Properties} parameter. <p>
 * </ul> <p>
 *
 */
public class NodeMappingServerImpl implements NodeMappingServer, Service {
    
    /** The name of this class. */
    private static final String CLASSNAME = 
            NodeMappingServerImpl.class.getName();
    
    /** The logger for this class. */
    private static final LoggerWrapper logger =
            new LoggerWrapper(Logger.getLogger(CLASSNAME));
    
    /**
     * The property that specifies the name of the class that implements
     * DataStore.
     */
    private static final String ASSIGN_POLICY_CLASS_PROPERTY =
	CLASSNAME + ".policy.class";
    
    /** The port we've been exported on. */
    private final int port;
    
    /** The exporter for this server */
    private final Exporter<NodeMappingServer> exporter;
    
    /** The transaction proxy, or null if configure has not been called. */ 
    private static TransactionProxy txnProxy;
    
    /** The watchdog service. */
//    private Watchdog watchdogService;
    
    private final TaskScheduler taskScheduler;
    private TaskOwner proxyOwner;
	
    /** The data service. */
    private DataService dataService;
    
    private final NodeAssignPolicy assignPolicy;

    
    /** The set of identities that are waiting to be removed,
     *  mapped to the thread that will do the removal. 
     */
    //JANE what to do if this server fails before all the removes
    // complete?  Should I persist a list of identities-to-be-removed
    // in the datastore, removing them when the thing is actually removed?
    // Or should I have some background thread that's walking over
    // my persisted stuff in batches over time, checking for dead stuff?
    private final Map<Identity, RemoveThread> removeMap =
        Collections.synchronizedMap(new HashMap<Identity, RemoveThread>());
    
    
    /** 
     * The set of clients of this server who wish to be notified if
     * there's a change in the map.
     */
    private final Map<Long, NotifyClient> notifyMap =
                                    new HashMap<Long, NotifyClient>();   

    /**
     * Creates a new instance of NodeMappingServerImpl, called from the
     * local NodeMappingService.
     * @param properties service properties
     * @param systemRegistry system registry
     *
     * @throws Exception if an error occurs during creation
     */
    public NodeMappingServerImpl(Properties properties, 
            ComponentRegistry systemRegistry)  throws Exception 
    {
        logger.log(Level.CONFIG, 
                   "Creating NodeMappingServerImpl properties:{0}", properties); 
        
        taskScheduler = systemRegistry.getComponent(TaskScheduler.class);
        
 	PropertiesWrapper wrappedProps = new PropertiesWrapper(properties);
        int requestedPort = wrappedProps.getIntProperty(
            NodeMapUtil.getServerPortProperty(), 
            NodeMapUtil.getDefaultServerPort());
        if (requestedPort < 0 || requestedPort > 65535) {
            throw new IllegalArgumentException(
                "The " + NodeMapUtil.getServerPortProperty() + 
                " property value must be " +
                "greater than or equal to 0 and less than 65535: " +
                requestedPort);
        } 
        
        String policyClassName = wrappedProps.getProperty(
		ASSIGN_POLICY_CLASS_PROPERTY);	    
        if (policyClassName == null) {
            assignPolicy = new RoundRobinPolicy(properties);
        } else {
            assignPolicy = wrappedProps.getClassInstanceProperty(
                ASSIGN_POLICY_CLASS_PROPERTY, NodeAssignPolicy.class,
                new Class[] { Properties.class }, properties);
        }
        
        exporter = new Exporter<NodeMappingServer>();
        port = exporter.export(this, requestedPort, 
                               NodeMapUtil.getServerExportName());
        if (requestedPort == 0) {
            logger.log(Level.CONFIG, "Server is using port {0,number,#}", port);
        }
        
    }
    
    /* -- Implement Service -- */
    /*  OK, this object is really an implementation detail of the service.
     *  But it's convenient to be able to inherit the javadoc comments, as
     *  we need to abide by the same rules at configuration and shutdown time.
     */
    
    /** {@inheritDoc} */
    public void configure(ComponentRegistry registry, TransactionProxy proxy) {
        logger.log(Level.CONFIG, "Configuring NodeMappingServerImpl");

        if (registry == null) {
            throw new NullPointerException("null registry");
        } else if (proxy == null) {
            throw new NullPointerException("null transaction proxy");
        }
        
        synchronized (NodeMappingServerImpl.class) {
            if (NodeMappingServerImpl.txnProxy == null) {
                NodeMappingServerImpl.txnProxy = proxy;
            } else {
                assert NodeMappingServerImpl.txnProxy == proxy;
            }
        }

        synchronized (this) {
            if (dataService != null) {
                throw new IllegalStateException("Already configured");
            }
            dataService = registry.getComponent(DataService.class);

            proxyOwner = txnProxy.getCurrentOwner();

//                watchdogService = registry.getComponent(WatchdogService.class);

        }
        
        
        // Register our node listener with the watchdog service.
//        watchdogService.addNodeListener(new Listener());
        // Ask watchdog service for the current nodes.
//        long localNodeId = watchdogService.getLocalNodeId();
//        for (Node node: watchdogService.getNodes()) {
//            long id = node.getId();
        //the localNodeId check isn't quite right...what if only one node?
//            if (node.isAlive() && id != localNodeId) {
//                assignPolicy.nodeStarted(n.getId());
//            }
//        }
        //JANE FOR NOW just create a few nodes
        for (int i = 0; i < 5; i++) {
            Node n = new NodeImpl(i);
            assignPolicy.nodeStarted(n.getId());
//            System.out.println("JANE adding id " + n.getId());
        }
        
        // Restore any information cached info that was in the datastore.
        // Need to find all nodes in datastore, check that they are still
        // live, and add to liveNodes (keeping a list of now-dead-nodes).
        // Then ask watchdog for live nodes, and add any new ones to our
        // list (BUT NOT if id == watchdogService.getLocalNodeId()!).
        // Will watchdog.getNodes return both live and dead nodes?
    }
    
    /**
     * Removes any configuration effects, in case the transaction
     * configuring the service aborts.  This is more convenient than joining
     * the transaction.
     */ 
    void unconfigure() {
        dataService = null;
        assignPolicy.reset();
    }
    
    /** {@inheritDoc} */
    public boolean shutdown() {
        boolean ok = exporter.unexport();
        
        Collection<RemoveThread> threads = removeMap.values();
        for (RemoveThread t : threads) {
            System.out.println("interrupt pending remove thread");
            t.interrupt();
        }
        return ok;
    }
    
    /** {@inheritDoc} */
    public String getName() {
        return toString();
    }
    
    /* Our node listener for the watchdog service.  This will be notified
     * when a node starts or stops.
     */
//    private class Listener implements NodeListener {
//        
//        Listener() {
//            
//        }
//        
//        /** {@inheritDoc} */
//        void nodeStarted(Node node){
//            assignPolicy.nodeStarted(node.getId());
////            liveNodes.add(node);       
//        }
//        
//        /** {@inheritDoc} */
//        void nodeFailed(Node node){
//            long nodeId = node.getId();
//            assignPolicy.nodeFailed(nodeId);
////            liveNodes.remove(node);
//            
//        
//            // Move assignments to new nodes:
//            //    In a transaction, look up the next id.
//            //    Find the preferred node via mapNode.
//            
//        }
//    }
    
    private long mapNode(String serviceName, Identity id, Node oldNode) {
        assert(serviceName != null);
        assert(id != null);
        
        // Choose the node.  This needs to occur outside of a transaction,
        // as it could take a while.
        final Node newNode = assignPolicy.chooseNode(id);

        final long newNodeId = newNode.getId();
        final String idkey = NodeMapUtil.getIdentityKey(id);
        final String statuskey = NodeMapUtil.getStatusKey(id, newNodeId, serviceName);
        final String nodekey = NodeMapUtil.getNodeKey(newNodeId, id);
        
        // if moving, want to look up the old IdentityMO?
        final IdentityMO idmo = new IdentityMO(id, newNodeId);
        System.out.println("STORING " + idmo + " with key " + idkey);

        final Node old = oldNode;
        final long oldId = old.getId();
        String ostatusk = null;
        String onodek = null;
        if (oldNode != null) {
            ostatusk =  NodeMapUtil.getPartialStatusKey(id, oldId);
            onodek = NodeMapUtil.getNodeKey(oldId, id);
        }
        final String oldStatusKey = ostatusk;
        final String oldNodeKey = onodek;
        
        try {
            runTransactionally(new AbstractKernelRunnable() {
                public void run() {
                    // Add the id->node mapping.
                    dataService.setServiceBinding(idkey, idmo);
                    // Add the node->id mapping
                    dataService.setServiceBinding(nodekey, idmo);
                    // Reference count
                    dataService.setServiceBinding(statuskey, idmo);

                    if (old != null) {
                        //Clean up the old mappings.
                        dataService.removeServiceBinding(oldNodeKey);

                        Iterator<String> iter =
                            BoundNamesUtil.getServiceBoundNamesIterator(
                                dataService, oldStatusKey);

                        while (iter.hasNext()) {
                            // JANE need to copy the old values to the new?
                            String key = iter.next();
                            key.replaceAll(String.valueOf(oldId), 
                                           String.valueOf(newNodeId));
                            dataService.setServiceBinding(key, idmo);
                            iter.remove();
                        }
                    }
                }});
        } catch (Exception e) {
            //JANE?
        }

        // Tell our listeners
        notifyListeners(oldNode, newNode, id);
        return newNodeId;
    }
    
    /* -- Implement NodeMappingServer -- */

    /** {@inheritDoc} */
    public void assignNode(Class service, Identity id) throws IOException {
        final String serviceName = service.getName();
        
        if (id == null) {
            throw new NullPointerException("null id");
        }
        
        // Check to see if we already have an assignment.  If so, we just
        // need to update the status.  Otherwise, we need to make our
        // persistent updates and notify listeners. 
        final String idkey = NodeMapUtil.getIdentityKey(id);      
        NodeMapUtil.GetIdTask idtask = 
                new NodeMapUtil.GetIdTask(dataService, idkey);
        try {
            runTransactionally(idtask);
        } catch (Exception ex) {
            // Do nothing.  We expect to get an exception if the service
            // binding isn't found;  we'll find this out from the task
            // return value.
        }
        
        final IdentityMO foundId = idtask.getId();
        if (foundId == null) {
            // Choose the node.  This needs to occur outside of a transaction,
            // as it could take a while.
            Node newNode = assignPolicy.chooseNode(id);

            final long newNodeId = newNode.getId();
            final String statuskey = 
                    NodeMapUtil.getStatusKey(id, newNodeId, serviceName);
            final String nodekey = NodeMapUtil.getNodeKey(newNodeId, id);
            final IdentityMO idmo = new IdentityMO(id, newNodeId);
            System.out.println("STORING " + idmo + " with key " + idkey);
            
            try {         
                runTransactionally(new AbstractKernelRunnable() {
                    public void run() {
                        // Add the id->node mapping.
                        dataService.setServiceBinding(idkey, idmo);
                        // Add the node->id mapping
                        dataService.setServiceBinding(nodekey, idmo);
                        // Add the status
                        dataService.setServiceBinding(statuskey, idmo);
                    }});
            } catch (Exception ex) {
                logger.logThrow(Level.WARNING, ex, 
                                "Adding mappings for {0} failed", idmo);
            }

            // Tell our listeners
            notifyListeners(null, newNode, id);
            
            logger.log(Level.FINEST, "assignNode id:{0} to {1}", id, newNode);
        } else {
            long foundNodeId = foundId.getNodeId();
            try {
                // The identity already has an assignment but we still need to
                // update the status.
                final String statuskey = 
                        NodeMapUtil.getStatusKey(id, foundNodeId, serviceName);
                runTransactionally(new AbstractKernelRunnable() {
                    public void run() {
                        dataService.setServiceBinding(statuskey, foundId);
                    }});
            } catch (Exception ex) {
                logger.logThrow(Level.WARNING, ex, 
                                "Adding status for {0} failed", foundId);
            }
                
            logger.log(Level.FINEST, "assignNode id:{0} already on {1}", 
                       id, foundNodeId);
        }
               
    }

    /** {@inheritDoc} */
    public void canRemove(Identity id) throws IOException {
        // Some node believes that an identity is no longer being used.
        // Wait for a bit to see if that's true, then remove it.
        // remove wait should be a property
        final long REMOVEWAIT = 1000;
        if (removeMap.containsKey(id)) {
            return;
        } 
        
        // Jane - should remove map be persisted?
        RemoveThread thread = new RemoveThread(id, REMOVEWAIT);
        removeMap.put(id, thread);
        thread.start();
    }
    
    private class RemoveThread extends Thread {
        private final Identity id;
        private final long waitTime;
        
        RemoveThread(Identity identity, long wait) {
            id = identity;
            waitTime = wait;
        }
        public void run() {
            System.out.println("in remove thread run .." + System.currentTimeMillis());
            try {
                sleep(waitTime);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                removeMap.remove(id);
                return;
            }
            System.out.println("back from sleep .." + System.currentTimeMillis());

            RemoveTask rtask = new RemoveTask(id);
            try {
                runTransactionally(rtask);
                if (rtask.isDead()) {
                    notifyListeners(new NodeImpl(rtask.nodeId()), null, id);
                }
            } catch (Exception ex) {
                // JANE ??
                ex.printStackTrace();
            } finally {
                removeMap.remove(id);
            }
        }
    }
    
    private class RemoveTask implements KernelRunnable {
        private final Identity id;
        private final String idkey;
        private final String statuskey;
        private boolean dead = true;
        // set if dead == true
        private long nodeId;
        
        RemoveTask(Identity id) {
            this.id = id;
            idkey = NodeMapUtil.getIdentityKey(id);
            statuskey = NodeMapUtil.getPartialStatusKey(id);
        }
        public void run() {
            IdentityMO idmo = null;
            {
                try {
                    idmo = dataService.getServiceBinding(idkey, IdentityMO.class);
                } catch (NameNotBoundException e) {
                    // ??
                    e.printStackTrace();
                } 
            }
            if (idmo != null) { 
                try {
                    // Check the status, and remove it if still dead.  We'd
                    // expect to find NO bound names for any node
                    String name = dataService.nextServiceBoundName(statuskey);
                    dead = (name == null || !name.startsWith(statuskey));
//                    dead = 
//                        (dataService.nextServiceBoundName(statuskey) == null);

                } catch (NameNotBoundException e) {
                    e.printStackTrace();
                }
                if (dead) {
                    nodeId = idmo.getNodeId();
                    // Update the node info (note:  ids are only on one node
                    // at a time)
                    String nodekey = NodeMapUtil.getNodeKey(nodeId, id);
                    dataService.removeServiceBinding(nodekey);

                    // Finally, remove the id
                    dataService.removeObject(idmo);
                    dataService.removeServiceBinding(idkey);
                }
            }

        }
        public String getBaseTaskType() {
            return "AssignNodeTask";
        }
        
        boolean isDead() {
            return dead;
        }
        long nodeId() {
            return nodeId;
        } 
    }
    
    /** {@inheritDoc} */
    public void registerNodeListener(NotifyClient client, long nodeId) 
        throws IOException
    {
        synchronized(notifyMap) {
            notifyMap.put(nodeId, client);
        }
    }
    
    /**
     * {@inheritDoc}
     * Also called internally when we hear a node has died.
     */
    public void unregisterNodeListener(long nodeId) throws IOException {
        synchronized(notifyMap) {
            notifyMap.remove(nodeId);
        }
    }    
    
    // Perhaps need to have a separate thread do this work.
    private void notifyListeners(Node oldNode, Node newNode, Identity id) {
        System.out.println("In notifyListeners, id is " + id);
        NotifyClient oldClient = null;
        NotifyClient newClient = null;
        synchronized(notifyMap) {
            if (oldNode != null) {
                oldClient = notifyMap.get(oldNode.getId());
            }
            if (newNode != null) {
                newClient = notifyMap.get(newNode.getId());
            }
        }

        // Tell the old node that the identity moved
        if (oldClient != null) {
            try {
                oldClient.removed(id, newNode);
            } catch (RemoteException ex) {
                logger.logThrow(Level.WARNING, ex, 
                        "A communication error occured while notifying" +
                        " node {0} that {1} has been removed", oldClient, id);
            }
        }

        // Tell the new node that the identity moved
        if (newClient != null) {
            try {
                newClient.added(id, oldNode);
            } catch (RemoteException ex) {
                logger.logThrow(Level.WARNING, ex, 
                        "A communication error occured while notifying" +
                        " node {0} that {1} has been added", newClient, id);
            }
        }
    }
    
    /**
     * Returns the port being used for this server.
     *
     * @return  the port
     */
    public int getPort() {
        return port;
    }
    
    
    /**
     *  Run the given task synchronously, and transactionally, retrying
     *  if the exception is of type <@code ExcetpionRetryStatus>.
     * @param task the task
     */
    private void runTransactionally(KernelRunnable task) throws Exception {   
        taskScheduler.runTask(new TransactionRunner(task), proxyOwner, true);      
    }
}
    
