/*
 * Copyright 2007 Sun Microsystems, Inc.
 *
 * This file is part of Project Darkstar Server.
 *
 * Project Darkstar Server is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation and
 * distributed hereunder to you.
 *
 * Project Darkstar Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.sun.sgs.impl.service.session;

import com.sun.sgs.app.AppListener;
import com.sun.sgs.app.ClientSession;
import com.sun.sgs.app.ClientSessionId;
import com.sun.sgs.app.ClientSessionListener;
import com.sun.sgs.app.Delivery;
import com.sun.sgs.app.ExceptionRetryStatus;
import com.sun.sgs.app.ManagedObject;
import com.sun.sgs.auth.Identity;
import com.sun.sgs.impl.auth.NamePasswordCredentials;
import com.sun.sgs.impl.kernel.StandardProperties;
import com.sun.sgs.impl.service.session.ClientSessionServiceImpl.Context;
import com.sun.sgs.impl.sharedutil.CompactId;
import com.sun.sgs.impl.sharedutil.HexDumper;
import com.sun.sgs.impl.sharedutil.LoggerWrapper;
import com.sun.sgs.impl.sharedutil.MessageBuffer;
import com.sun.sgs.impl.util.AbstractKernelRunnable;
import com.sun.sgs.impl.util.NonDurableTaskQueue;
import com.sun.sgs.io.AsynchronousMessageChannel;
import com.sun.sgs.kernel.KernelRunnable;
import com.sun.sgs.nio.channels.CompletionHandler;
import com.sun.sgs.nio.channels.IoFuture;
import com.sun.sgs.protocol.simple.SimpleSgsProtocol;
import com.sun.sgs.service.ClientSessionService;
import com.sun.sgs.service.DataService;
import com.sun.sgs.service.ProtocolMessageListener;
import com.sun.sgs.service.SgsClientSession;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.login.LoginException;

/**
 * Implements a client session.
 */
public class ClientSessionImpl implements SgsClientSession, Serializable {

    /** The serialVersionUID for this class. */
    private final static long serialVersionUID = 1L;
    
    /** Connection state. */
    private static enum State {
        /** A connection is in progress */
	CONNECTING,
        /** Session is connected */
        CONNECTED,
        /** Reconnection is in progress */
        RECONNECTING,
        /** Disconnection is in progress */
        DISCONNECTING, 
        /** Session is disconnected */
        DISCONNECTED
    }

    /** Random number generator for generating session ids. */
    private static final Random random = new Random(getSeed());
    
    /** The logger for this class. */
    private static final LoggerWrapper logger =
	new LoggerWrapper(Logger.getLogger(ClientSessionImpl.class.getName()));

    /** Message for indicating login/authentication failure. */
    private static final String LOGIN_REFUSED_REASON = "Login refused";

    /** The client session service that created this client session. */
    private final ClientSessionServiceImpl sessionService;

    /** The data service. */
    private final DataService dataService;
    
    /** The IO channel for sending messages to the client. */
    private AsynchronousMessageChannel sessionConnection = null;
    
    private final ByteBuffer readBuffer;

    /** The session id. */
    private final CompactId sessionId;

    /** The reconnection key. */
    private final CompactId reconnectionKey;

    /** The identity for this session. */
    private Identity identity;

    /** The lock for accessing the connection state and sending messages. */
    private final Object lock = new Object();
    
    /** The connection state. */
    private State state = State.CONNECTING;

    /** The client session listener for this client session.*/
    private SessionListener listener;

    /** Indicates whether session disconnection has been handled. */
    private boolean disconnectHandled = false;

    /** Indicates whether this session is shut down. */
    private boolean shutdown = false;

    /** The sequence number for ordered messages sent from this client. */
    private AtomicLong sequenceNumber = new AtomicLong(0);

    /** The queue of tasks for notifying listeners of received messages. */
    private NonDurableTaskQueue taskQueue = null;

    /**
     * Constructs an instance of this class with the specified {@code
     * sessionService} and session {@code id}.
     *
     * @param	sessionService the service that created this instance
     * @param	id the session ID for this instance
     */
    ClientSessionImpl(ClientSessionServiceImpl sessionService, byte[] id) {
	if (sessionService == null) {
	    throw new NullPointerException("sessionService is null");
	}
	this.sessionService = sessionService;
        this.dataService = sessionService.dataService;
        this.readBuffer = ByteBuffer.allocate(128 * 1024);
	this.sessionId = new CompactId(id);
	this.reconnectionKey = sessionId; // not used yet
    }

    /**
     * Constructs an instance of this class with the specified name
     * and session id.  The returned session is disconnected and cannot
     * send or receive messages.
     *
     * This constructor is used during deserialization to construct a
     * disconnected client session if a client session with the
     * specified session id can't be located in the client session
     * service of the current app context.
     */
    private ClientSessionImpl(
	CompactId sessionId,
        Identity identity)
    {
	this.sessionService =
	    (ClientSessionServiceImpl) ClientSessionServiceImpl.getInstance();
	this.dataService = sessionService.dataService;
        this.readBuffer = null;
	this.sessionId = sessionId;
        this.identity = identity;
	this.reconnectionKey = sessionId; // not used yet
	this.state = State.DISCONNECTED;
	this.disconnectHandled = true;
	this.shutdown = true;
    }

    /* -- Implement ClientSession -- */

    /** {@inheritDoc} */
    public String getName() {
	Identity thisIdentity = getIdentity();
        String name = (thisIdentity == null) ? null : thisIdentity.getName();
	logger.log(Level.FINEST, "getName returns {0}", name);
	return name;
    }
    
    /** {@inheritDoc} */
    public ClientSessionId getSessionId() {
	logger.log(Level.FINEST, "getSessionId returns {0}", sessionId);
        return new ClientSessionId(sessionId.getId());
    }

    /**
     * Returns the client session ID for this client session in {@code
     * CompactId} format.
     *
     * @return the client session ID as a {@code CompactId}
     */
    public CompactId getCompactSessionId() {
	return sessionId;
    }

    /** {@inheritDoc} */
    public boolean isConnected() {

	State currentState = getCurrentState();

	boolean connected =
	    currentState == State.CONNECTING ||
	    currentState == State.CONNECTED ||
	    currentState == State.RECONNECTING;

	logger.log(Level.FINEST, "isConnected returns {0}", connected);
	return connected;
    }

    /** {@inheritDoc} */
    public void send(final byte[] message) {
	try {
            if (message.length > SimpleSgsProtocol.MAX_MESSAGE_LENGTH) {
                throw new IllegalArgumentException(
                    "message too long: " + message.length + " > " +
                        SimpleSgsProtocol.MAX_MESSAGE_LENGTH);
            }
	    switch (getCurrentState()) {

	    case CONNECTING:
	    case CONNECTED:
	    case RECONNECTING:
		MessageBuffer buf =
		    new MessageBuffer(3 + 8 + 2 + message.length);
		buf.putByte(SimpleSgsProtocol.VERSION).
		    putByte(SimpleSgsProtocol.APPLICATION_SERVICE).
		    putByte(SimpleSgsProtocol.SESSION_MESSAGE).
                    putLong(sequenceNumber.getAndIncrement()).
		    putShort(message.length).
		    putBytes(message);
		sendProtocolMessageOnCommit(buf.getBuffer(), Delivery.RELIABLE);
		break;
	    
	    default:
		throw new IllegalStateException("client session not connected");
	    }
	} catch (RuntimeException e) {
	    logger.logThrow(
		Level.FINEST, e, "send message:{0} throws", message);
	    throw e;
	}
	
	logger.log(Level.FINEST, "send message:{0} returns", message);
    }

    /** {@inheritDoc} */
    public void disconnect() {
	if (getCurrentState() != State.DISCONNECTED) {
	    getContext().requestDisconnect(this);
	}
	logger.log(Level.FINEST, "disconnect returns");
    }

    /* -- Implement SgsClientSession -- */

    /** {@inheritDoc} */
    public Identity getIdentity() {
	Identity thisIdentity;
	synchronized (lock) {
	    thisIdentity = identity;
	}
        logger.log(Level.FINEST, "getIdentity returns {0}", thisIdentity);
	return thisIdentity;
    }

    /** {@inheritDoc} */
    public void sendProtocolMessage(byte[] message, Delivery delivery) {
	// TBI: ignore delivery for now...
	try {
	    if (getCurrentState() != State.DISCONNECTED) {
                // TODO this is not elegant
                ByteBuffer buf = ByteBuffer.allocateDirect(message.length + 4);
                buf.putInt(message.length);
                buf.put(message);
                buf.flip();

                // TODO completion handler
		IoFuture<Void, Void> writeFuture =
                    sessionConnection.write(buf, null);

                // FIXME don't wait synchronously!!!
                try {
                    writeFuture.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                
            } else {
		if (logger.isLoggable(Level.FINER)) {
		    logger.log(
		        Level.FINER,
			"sendProtocolMessage session:{0} " +
			"session is disconnected", this);
		}
	    }
		    
	} catch (RuntimeException e) {
	    if (logger.isLoggable(Level.WARNING)) {
		logger.logThrow(
		    Level.WARNING, e,
		    "sendProtocolMessage session:{0} throws", this);
	    }
	}
	
	if (logger.isLoggable(Level.FINEST)) {
	    logger.log(
		Level.FINEST,
		"sendProtocolMessage session:{0} message:{1} returns",
		this, HexDumper.format(message));
	}
    }

    /** {@inheritDoc} */
    public void sendProtocolMessageOnCommit(byte[] message, Delivery delivery) {
	if (getCurrentState() != State.DISCONNECTED) {
	    getContext().addMessage(this, message, delivery);
	}
    }

    /* -- Implement Object -- */

    /** {@inheritDoc} */
    public boolean equals(Object obj) {
	if (this == obj) {
	    return true;
	} else if (obj.getClass() == this.getClass()) {
	    ClientSessionImpl session = (ClientSessionImpl) obj;
	    return
		areEqualIdentities(getIdentity(), session.getIdentity()) &&
		sessionId.equals(session.sessionId);
	}
	return false;
    }

    /**
     * Returns {@code true} if the given identities are either both
     * null, or both non-null and invoking {@code equals} on the first
     * identity passing the second identity returns {@code true}.
     */
    private static boolean areEqualIdentities(Identity id1, Identity id2) {
	if (id1 == null) {
	    return id2 == null;
	} else if (id2 == null) {
	    return false;
	} else {
	    return id1.equals(id2);
	}
    }
    
    /** {@inheritDoc} */
    public int hashCode() {
	return sessionId.hashCode();
    }

    /** {@inheritDoc} */
    public String toString() {
	return getClass().getName() + "[" + getName() + "]@" + sessionId;
    }
    
    /* -- Serialization methods -- */

    private Object writeReplace() {
	return new External(sessionId, getIdentity());
    }

    /**
     * Represents the persistent representation for a client session
     * (its name and session id).
     */
    private final static class External implements Serializable {

	private final static long serialVersionUID = 1L;

	private final byte[] idBytes;
        private final Identity identity;

	External(CompactId sessionId, Identity identity) {
	    this.idBytes = sessionId.getId();
            this.identity = identity;
	}

	private void writeObject(ObjectOutputStream out) throws IOException {
	    out.defaultWriteObject();
	}

	private void readObject(ObjectInputStream in)
	    throws IOException, ClassNotFoundException
	{
	    in.defaultReadObject();
	}

	private Object readResolve() throws ObjectStreamException {
	    ClientSessionService service =
		ClientSessionServiceImpl.getInstance();
	    ClientSession session = service.getClientSession(idBytes);
	    if (session == null) {
		CompactId sessionId = new CompactId(idBytes);
		session = new ClientSessionImpl(sessionId, identity);
	    }
	    return session;
	}
    }

    /* -- other methods -- */

    /**
     * Returns the current state.
     */
    private State getCurrentState() {
	State currentState;
	synchronized (lock) {
	    currentState = state;
	}
	return currentState;
    }

    /**
     * Returns the current context, throwing
     * TransactionNotActiveException if there is no current
     * transaction, and throwing IllegalStateException if there is a
     * problem with the state of the transaction or the client session
     * service configuration.
     */
    private Context getContext() {
	return sessionService.checkContext();
    }

    /**
     * Handles a disconnect request (if not already handled) by doing
     * the following:
     *
     * a) sending a disconnect acknowledgment (LOGOUT_SUCCESS)
     * if 'graceful' is true
     *
     * b) closing this session's connection
     *
     * c) submitting a transactional task to call the 'disconnected'
     * callback on the listener for this session.
     *
     * @param graceful if the disconnection was graceful (i.e., due to
     * a logout request).
     */
    void handleDisconnect(final boolean graceful) {
	synchronized (lock) {
	    if (disconnectHandled) {
		return;
	    }
	    disconnectHandled = true;
	    if (state != State.DISCONNECTED) {
		state = State.DISCONNECTING;
	    }
	}

	sessionService.disconnected(this);

	final Identity thisIdentity = getIdentity();
	if (thisIdentity != null) {
	    // TBD: Due to the scheduler's behavior, this notification
	    // may happen out of order with respect to the
	    // 'notifyLoggedIn' callback.  Also, this notification may
	    // also happen even though 'notifyLoggedIn' was not invoked.
	    // Are these behaviors okay?  -- ann (3/19/07)
	    sessionService.scheduleTask(new AbstractKernelRunnable() {
		    public void run() {
			thisIdentity.notifyLoggedOut();
		    }}, thisIdentity);
	}

	if (getCurrentState() != State.DISCONNECTED) {
	    if (graceful) {
		MessageBuffer buf = new MessageBuffer(3);
		buf.putByte(SimpleSgsProtocol.VERSION).
		    putByte(SimpleSgsProtocol.APPLICATION_SERVICE).
		    putByte(SimpleSgsProtocol.LOGOUT_SUCCESS);
	    
		sendProtocolMessage(buf.getBuffer(), Delivery.RELIABLE);
	    }

	    try {
		sessionConnection.close();
	    } catch (IOException e) {
		if (logger.isLoggable(Level.WARNING)) {
		    logger.logThrow(
		    	Level.WARNING, e,
			"handleDisconnect (close) handle:{0} throws",
			sessionConnection);
		}
	    }
	}

	if (listener != null) {
	    scheduleTask(new AbstractKernelRunnable() {
		public void run() throws IOException {
		    listener.get().disconnected(graceful);
		    listener.remove();
		}});
	}
    }

    /**
     * Flags this session as shut down, and closes the connection.
     */
    void shutdown() {
	synchronized (lock) {
	    if (shutdown == true) {
		return;
	    }
	    shutdown = true;
	    disconnectHandled = true;
	    state = State.DISCONNECTED;
	    if (sessionConnection != null) {
		try {
		    sessionConnection.close();
		} catch (IOException e) {
		    // ignore
		}
	    }
	}
    }
    
    /** Returns the ConnectionListener for this session. */
    void connected(AsynchronousMessageChannel conn) {
        if (logger.isLoggable(Level.FINER)) {
            logger.log(
                Level.FINER, "Handler.connected handle:{0}", conn);
        }

        synchronized (lock) {
            // check if there is already a handle set
            if (sessionConnection != null) {
                logger.log(Level.WARNING,
                    "session already connected to {0}", sessionConnection);
                try {
                    conn.close();
                } catch (IOException e) {
                    // ignore
                }
                return;
            }

            sessionConnection = conn;

            switch (state) {

            case CONNECTING:
            case RECONNECTING:
                state = State.CONNECTED;
                break;
            default:
                break;
            }
        }

        readBuffer.clear();
        sessionConnection.read(readBuffer, new Listener());
    }

    /** Returns a random seed to use in generating session ids. */
    private static long getSeed() {
	byte[] seedArray = SecureRandom.getSeed(8);
	long seed = 0;
	for (long b : seedArray) {
	    seed <<= 8;
	    seed += b & 0xff;
	}
	return seed;
    }

    /* -- ConnectionListener implementation -- */

    /**
     * Listener for connection-related events for this session's
     * Connection.
     */
    private class Listener implements CompletionHandler<ByteBuffer, Void> {

        public void completed(IoFuture<ByteBuffer, Void> result) {
            try {
                ByteBuffer message = result.getNow();
                if (message == null) {
                    disconnected();
                    return;
                }

                int len = message.getInt();
                
                if (len != message.remaining()) {
                    logger.log(Level.SEVERE,
                        "Message length mismatch; expect {0} but have {1}",
                        new Object[] { len, message.remaining() });
                }

                byte[] bytes = new byte[message.remaining()];
                message.get(bytes);

                if (logger.isLoggable(Level.FINEST)) {
                    logger.log(
                        Level.FINEST,
                        "Listener read completed on {0}, buffer:{1}",
                        sessionConnection, HexDumper.format(bytes));
                }

                bytesReceived(bytes);

                // Keep reading
                sessionConnection.read(readBuffer, this);

            } catch (ExecutionException e) {
                if (logger.isLoggable(Level.WARNING)) {
                    logger.logThrow(
                        Level.WARNING, e,
                        "Listener exception {0}", sessionConnection);
                }
                disconnected();
            }
        }

	private void disconnected() {
	    if (logger.isLoggable(Level.FINER)) {
		logger.log(Level.FINER,
                    "Listener.disconnected {0}", sessionConnection);
	    }

	    synchronized (lock) {
		if (!disconnectHandled) {
		    scheduleNonTransactionalTask(new AbstractKernelRunnable() {
			public void run() {
			    handleDisconnect(false);
			}});
		}

		state = State.DISCONNECTED;
	    }
	}

	private void bytesReceived(byte[] buffer) {

	    if (buffer.length < 3) {
		if (logger.isLoggable(Level.SEVERE)) {
		    logger.log(
		        Level.SEVERE,
			"Handler.messageReceived malformed protocol message:{0}",
			buffer);
		}
		// TBD: should the connection be disconnected?
		return;
	    }

	    MessageBuffer msg = new MessageBuffer(buffer);
		
	    /*
	     * Handle version.
	     */
	    byte version = msg.getByte();
	    if (version != SimpleSgsProtocol.VERSION) {
		if (logger.isLoggable(Level.SEVERE)) {
		    logger.log(
			Level.SEVERE,
			"Handler.messageReceived protocol version:{0}, " +
			"expected {1}", version, SimpleSgsProtocol.VERSION);
		}
		    // TBD: should the connection be disconnected?
		return;
	    }

	    /*
	     * Dispatch message to service.
	     */
	    byte serviceId = msg.getByte();

	    if (serviceId == SimpleSgsProtocol.APPLICATION_SERVICE) {
		handleApplicationServiceMessage(msg);
	    } else {
		ProtocolMessageListener serviceListener =
		    sessionService.getProtocolMessageListener(serviceId);
		if (serviceListener != null) {
		    if (getIdentity() == null) {
			if (logger.isLoggable(Level.WARNING)) {
			    logger.log(
			        Level.WARNING,
				"session:{0} received message for " +
				"service ID:{1} before successful login",
				this, serviceId);
			    return;
			}
		    }
		    
		    serviceListener.receivedMessage(
			ClientSessionImpl.this, buffer);
		    
		} else {
		    if (logger.isLoggable(Level.SEVERE)) {
		    	logger.log(
			    Level.SEVERE,
			    "session:{0} unknown service ID:{1}",
			    this, serviceId);
		    }
		}
	    }
	}

	/**
	 * Handles an APPLICATION_SERVICE message received by the
	 * {@code bytesReceived} method.  When this method is invoked,
	 * the specified message buffer's current position points to
	 * the operation code of the protocol message.  The protocol
	 * version and service ID have already been processed by the
	 * caller.
	 */
	private void handleApplicationServiceMessage(MessageBuffer msg) {
	    byte opcode = msg.getByte();

	    if (logger.isLoggable(Level.FINEST)) {
		logger.log(
 		    Level.FINEST,
		    "Handler.messageReceived processing opcode:{0}",
		    Integer.toHexString(opcode));
	    }
	    
	    switch (opcode) {
		
	    case SimpleSgsProtocol.LOGIN_REQUEST:
		String name = msg.getString();
		String password = msg.getString();

		try {
		    Identity authenticatedIdentity =
			authenticate(name, password);
		    synchronized (lock) {
			identity = authenticatedIdentity;
			taskQueue =
			    new NonDurableTaskQueue(
				sessionService.txnProxy,
				sessionService.nonDurableTaskScheduler,
				identity);
		    }
		    scheduleTask(new LoginTask());
		} catch (LoginException e) {
		    scheduleNonTransactionalTask(new AbstractKernelRunnable() {
			public void run() {
			    sendProtocolMessage(getLoginNackMessage(),
						Delivery.RELIABLE);
			    handleDisconnect(false);
			}});
		}
		break;
		
	    case SimpleSgsProtocol.RECONNECT_REQUEST:
		break;

	    case SimpleSgsProtocol.SESSION_MESSAGE:
		if (getIdentity() == null) {
		    logger.log(
		    	Level.WARNING,
			"session message received before login:{0}", this);
		    break;
		}
                msg.getLong(); // TODO Check sequence num
		int size = msg.getUnsignedShort();
		final byte[] clientMessage = msg.getBytes(size);
		taskQueue.addTask(new AbstractKernelRunnable() {
		    public void run() {
			if (isConnected()) {
			    listener.get().receivedMessage(clientMessage);
			}
		    }});
		break;

	    case SimpleSgsProtocol.LOGOUT_REQUEST:
	        scheduleNonTransactionalTask(new AbstractKernelRunnable() {
	            public void run() {
	                handleDisconnect(isConnected());
	            }});
		break;
		
	    default:
		if (logger.isLoggable(Level.SEVERE)) {
		    logger.log(
			Level.SEVERE,
			"Handler.messageReceived unknown operation code:{0}",
			opcode);
		}

		scheduleNonTransactionalTask(new AbstractKernelRunnable() {
		    public void run() {
			handleDisconnect(false);
		    }});
		break;
	    }
	}

    }

    /**
     * Authenticates the specified username and password, throwing
     * LoginException if authentication fails.
     */
    private Identity authenticate(String username, String password)
	throws LoginException
    {
	return sessionService.identityManager.authenticateIdentity(
	    new NamePasswordCredentials(username, password.toCharArray()));
    }

    /**
     * Schedules a non-durable, transactional task.
     */
    private void scheduleTask(KernelRunnable task) {
	sessionService.scheduleTask(task, getIdentity());
    }

    /**
     * Schedules a non-durable, non-transactional task.
     */
    private void scheduleNonTransactionalTask(KernelRunnable task) {
	sessionService.scheduleNonTransactionalTask(task, getIdentity());
    }

    /**
     * Wrapper for persisting a {@code ClientSessionListener} that is
     * either a {@code ManagedObject} or {@code Serializable}.
     */
    private class SessionListener {

	private final String listenerKey;

	private final boolean isManaged;

	@SuppressWarnings("hiding")
	SessionListener(ClientSessionListener listener) {
	    assert listener != null && listener instanceof Serializable;
	    
	    ManagedObject managedObj;
	    if (listener instanceof ManagedObject) {
		isManaged = true;
		managedObj = (ManagedObject) listener;
		
	    } else {
		// listener is simply Serializable
		isManaged = false;
		managedObj = new ClientSessionListenerWrapper(listener);
	    }
	    
	    listenerKey =
		ClientSessionImpl.class.getName() + "." +
		Integer.toHexString(random.nextInt());
	    dataService.setServiceBinding(listenerKey, managedObj);
	}

	ClientSessionListener get() {
	    ManagedObject obj = 
		    dataService.getServiceBinding(
			listenerKey, ManagedObject.class);
	    return
		(isManaged) ?
		((ClientSessionListener) obj) :
		((ClientSessionListenerWrapper) obj).get();
	}

	void remove() {
	    if (!isManaged) {
		ClientSessionListenerWrapper wrapper =
		    dataService.getServiceBinding(
			listenerKey, ClientSessionListenerWrapper.class);
		dataService.removeObject(wrapper);
	    }
	    dataService.removeServiceBinding(listenerKey);
	}
    }

    /**
     * A {@code ManagedObject} wrapper for a {@code ClientSessionListener}.
     */
    static class ClientSessionListenerWrapper
	implements ManagedObject, Serializable
    {
	private final static long serialVersionUID = 1L;
	
	private ClientSessionListener listener;

	ClientSessionListenerWrapper(ClientSessionListener listener) {
	    assert listener != null && listener instanceof Serializable;
	    this.listener = listener;
	}

	ClientSessionListener get() {
	    return listener;
	}
    }

    /**
     * This is a transactional task to notify the application's
     * {@code AppListener} that this session has logged in.
     */
    private class LoginTask extends AbstractKernelRunnable {

	/**
	 * Invokes the {@code AppListener}'s {@code loggedIn}
	 * callback, which returns a client session listener.  If the
	 * returned listener is serializable, then this method does
	 * the following:
	 *
	 * a) queues the appropriate acknowledgment to be
	 * sent when this transaction commits, and
	 * b) schedules a task (on transaction commit) to call
	 * {@code notifyLoggedIn} on the identity.
	 *
	 * If the client session needs to be disconnected (if {@code
	 * loggedIn} returns a non-serializable listener (including
	 * {@code null}), or throws a non-retryable {@code
	 * RuntimeException}, then this method submits a
	 * non-transactional task to disconnect the client session.
	 * If {@code loggedIn} throws a retryable {@code
	 * RuntimeException}, then that exception is thrown to the
	 * caller.
	 */
	public void run() {
	    AppListener appListener =
		dataService.getServiceBinding(
		    StandardProperties.APP_LISTENER, AppListener.class);
	    logger.log(
		Level.FINEST,
		"LoginTask.run invoking AppListener.loggedIn session:{0}",
		getName());

	    ClientSessionListener returnedListener = null;
	    RuntimeException ex = null;

	    try {
		returnedListener = appListener.loggedIn(ClientSessionImpl.this);
	    } catch (RuntimeException e) {
		ex = e;
	    }
		
	    if (returnedListener instanceof Serializable) {
		logger.log(
		    Level.FINEST,
		    "LoginTask.run AppListener.loggedIn returned {0}",
		    returnedListener);

		listener = new SessionListener(returnedListener);
		MessageBuffer ack =
		    new MessageBuffer(
			3 + sessionId.getExternalFormByteCount() +
			reconnectionKey.getExternalFormByteCount());
		ack.putByte(SimpleSgsProtocol.VERSION).
		    putByte(SimpleSgsProtocol.APPLICATION_SERVICE).
		    putByte(SimpleSgsProtocol.LOGIN_SUCCESS).
		    putBytes(sessionId.getExternalForm()).
		    putBytes(reconnectionKey.getExternalForm());
		
		getContext().addMessageFirst(
		    ClientSessionImpl.this, ack.getBuffer(), Delivery.RELIABLE);

		final Identity thisIdentity = getIdentity();
		sessionService.scheduleTaskOnCommit(new AbstractKernelRunnable() {
		    public void run() {
			logger.log(
			    Level.FINE,
			    "calling notifyLoggedIn on identity:{0}",
			    thisIdentity);
			// notify that this identity logged in,
			// whether or not this session is connected at
			// the time of notification.
			thisIdentity.notifyLoggedIn();
		    }});
		
	    } else {
		if (ex == null) {
		    logger.log(
		        Level.WARNING,
			"LoginTask.run AppListener.loggedIn returned " +
			"non-serializable listener {0}",
			returnedListener);
		} else if (!(ex instanceof ExceptionRetryStatus) ||
			   ((ExceptionRetryStatus) ex).shouldRetry() == false) {
		    logger.logThrow(
			Level.WARNING, ex,
			"Invoking loggedIn on AppListener:{0} with " +
			"session: {1} throws",
			appListener, ClientSessionImpl.this);
		} else {
		    throw ex;
		}
		getContext().addMessageFirst(
		    ClientSessionImpl.this, getLoginNackMessage(),
		    Delivery.RELIABLE);
		getContext().requestDisconnect(ClientSessionImpl.this);
	    }
	}
    }

    /**
     * Returns a byte array containing a LOGIN_FAILURE protocol message.
     */
    private static byte[] getLoginNackMessage() {
        int stringSize = MessageBuffer.getSize(LOGIN_REFUSED_REASON);
        MessageBuffer ack =
            new MessageBuffer(3 + stringSize);
        ack.putByte(SimpleSgsProtocol.VERSION).
            putByte(SimpleSgsProtocol.APPLICATION_SERVICE).
            putByte(SimpleSgsProtocol.LOGIN_FAILURE).
            putString(LOGIN_REFUSED_REASON);
        return ack.getBuffer();
    }
}
