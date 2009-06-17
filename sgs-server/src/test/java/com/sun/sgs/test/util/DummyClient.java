/*
 * Copyright 2007-2009 Sun Microsystems, Inc.
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

package com.sun.sgs.test.util;

import com.sun.sgs.impl.io.SocketEndpoint;
import com.sun.sgs.impl.io.TransportType;
import com.sun.sgs.impl.sharedutil.HexDumper;
import com.sun.sgs.impl.sharedutil.MessageBuffer;
import com.sun.sgs.io.Connection;
import com.sun.sgs.io.ConnectionListener;
import com.sun.sgs.io.Connector;
import com.sun.sgs.protocol.simple.SimpleSgsProtocol;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.Assert;

/**
 * Dummy client code for testing purposes.
 */
public class DummyClient extends Assert {
    
    private static final int WAIT_TIME = 5000;
    private final Map<BigInteger, DummyClient> dummyClients;
	
    public final String name;
    private String password;
    private Connector<SocketAddress> connector;
    private Listener listener;
    private Connection connection;
    private boolean connected = false;
    private final Object lock = new Object();
    private final Object disconnectedCallbackLock = new Object();
    private boolean loginAck = false;
    private boolean loginSuccess = false;
    private boolean loginRedirect = false;
    private boolean logoutAck = false;
    private boolean awaitGraceful = false;
    private boolean awaitLoginFailure = false;
    private String reason;
    private String redirectHost;
    public int redirectPort;
    private byte[] reconnectKey = new byte[0];

    private boolean relocateSession;
    private String relocateHost;
    private int relocatePort;
    private byte[] relocateKey = new byte[0];
    private boolean relocateAck;
    private boolean relocateSuccess;
    private boolean relocateMessage;
    private int messagesReceivedDuringRelocation = 0;
	
    private volatile boolean receivedDisconnectedCallback = false;
    private volatile boolean graceful = false;
	
    public volatile int expectedMessages0;
    // Messages received by this client's associated ClientSessionListener
    public Queue<byte[]> messages = new ConcurrentLinkedQueue<byte[]>();
	

    public DummyClient(String name,
		       Map<BigInteger, DummyClient> dummyClients)
    {
	this.name = name;
	this.dummyClients = dummyClients;
    }

    public DummyClient connect(int port) {
	connected = false;
	listener = new Listener();
	try {
	    SocketEndpoint endpoint =
		new SocketEndpoint(
		    new InetSocketAddress(InetAddress.getLocalHost(), port),
		    TransportType.RELIABLE);
	    connector = endpoint.createConnector();
	    connector.connect(listener);
	} catch (Exception e) {
	    System.err.println(toString() + " connect throws: " + e);
	    e.printStackTrace();
	    throw new RuntimeException("DummyClient.connect failed", e);
	}
	synchronized (lock) {
	    try {
		if (connected == false) {
		    lock.wait(WAIT_TIME);
		}
		if (connected != true) {
		    throw new RuntimeException(
			toString() + " connect timed out to " + port);
		}
	    } catch (InterruptedException e) {
		throw new RuntimeException(
		    toString() + " connect timed out to " + port, e);
	    }
	}
	return this;
    }

    public void disconnect() {
	System.err.println(toString() + " disconnecting");

	synchronized (lock) {
	    if (connected == false) {
		return;
	    }
	    connected = false;
	}

	try {
	    connection.close();
	} catch (IOException e) {
	    System.err.println(toString() + " disconnect exception:" + e);
	}

	synchronized (lock) {
	    lock.notifyAll();
	}
    }

    public void setDisconnectedCallbackInvoked(boolean graceful) {
	receivedDisconnectedCallback = true;
	this.graceful = graceful;
	synchronized (disconnectedCallbackLock) {
	    disconnectedCallbackLock.notifyAll();
	}
    }

    public void assertDisconnectedCallbackInvoked(boolean graceful) {
	synchronized (disconnectedCallbackLock) {
	    assertTrue(receivedDisconnectedCallback);
	    assertEquals(this.graceful, graceful);
	}
    }

    public void assertDisconnectedCallbackNotInvoked() {
	synchronized (disconnectedCallbackLock) {
	    assertFalse(receivedDisconnectedCallback);
	}
    }

    /**
     * Sends a login request and waits for it to be acknowledged,
     * returning {@code true} if login was successful, and {@code
     * false} if login was redirected.  If the login was not successful
     * or redirected, then a {@code RuntimeException} is thrown because
     * the login operation timed out before being acknowledged.
     */
    public boolean login() {
	return login(true);
    }

    /**
     * Sends a login request and if {@code waitForLogin} is {@code
     * true} waits for the request to be acknowledged, returning {@code
     * true} if login was successful, and {@code false} if login was
     * redirected, otherwise a {@code RuntimeException} is thrown
     * because the login operation timed out before being acknowldeged.
     *
     * If {@code waitForLogin} is false, this method returns {@code
     * true} if the login is known to be successful (the outcome may
     * not yet be known because the login operation is asynchronous),
     * otherwise it returns false.  Invoke {@code waitForLogin} to wait
     * for an expected successful login.
     */
    public boolean login(boolean waitForLogin) {
	synchronized (lock) {
	    if (connected == false) {
		throw new RuntimeException(toString() + " not connected");
	    }
	}
	this.password = "password";

	MessageBuffer buf =
	    new MessageBuffer(2 + MessageBuffer.getSize(name) +
			      MessageBuffer.getSize(password));
	buf.putByte(SimpleSgsProtocol.LOGIN_REQUEST).
	    putByte(SimpleSgsProtocol.VERSION).
	    putString(name).
	    putString(password);
	loginAck = false;
	try {
	    connection.sendBytes(buf.getBuffer());
	} catch (IOException e) {
	    throw new RuntimeException(e);
	}
	if (waitForLogin) {
	    return waitForLogin();
	} else {
	    synchronized (lock) {
		return loginSuccess;
	    }
	}
    }

    /**
     * Waits for a login acknowledgement, and returns {@code true} if
     * login was successful, {@code false} if login was redirected or
     * failed, otherwise a {@code RuntimeException} is thrown because
     * the login operation timed out before being acknowledged.
     */
    public boolean waitForLogin() {
	synchronized (lock) {
	    try {
		if (loginAck == false) {
		    lock.wait(WAIT_TIME);
		}
		if (loginAck != true) {
		    throw new RuntimeException(toString() + " login timed out");
		}
		if (loginRedirect == true) {
		    return false;
		}
		return loginSuccess;
	    } catch (InterruptedException e) {
		throw new RuntimeException(toString() + " login timed out", e);
	    }
	}
    }

    public boolean isLoginRedirected() {
	return loginRedirect;
    }
    
    public void relocate(int newPort, boolean useValidKey,
			 boolean shouldSucceed)
    {
	synchronized (lock) {
	    if (!relocateSession) {
		waitForRelocationNotification(newPort);
	    } else {
		assertEquals(newPort, relocatePort);
	    }
	}
	System.err.println(toString() + " relocating...");
	disconnect();
	relocateAck = false;
	relocateSuccess = false;
	connect(relocatePort);
	byte[] key = useValidKey ? relocateKey : new byte[0];
	ByteBuffer buf = ByteBuffer.allocate(2 + key.length);
	buf.put(SimpleSgsProtocol.RELOCATE_REQUEST).
	    put(SimpleSgsProtocol.VERSION).
	    put(key).
	    flip();
	try {
	    connection.sendBytes(buf.array());
	} catch (IOException e) {
	    throw new RuntimeException(e);
	}
	synchronized (lock) {
	    try {
		if (!relocateAck) {
		    lock.wait(WAIT_TIME);
		}
		if (!relocateAck) {
		    throw new RuntimeException(
			toString() + " relocate timed out");
		}
		if (shouldSucceed) {
		    if (!relocateSuccess) {
			fail("relocation failed: " + relocateMessage);
		    }
		} else if (relocateSuccess) {
		    fail("relocation succeeded");
		}
	    } catch (InterruptedException e) {
		throw new RuntimeException(
		    toString() + " relocate timed out", e);
	    }
	}
	assertEquals(0, messagesReceivedDuringRelocation);
    }

    /**
     * Waits for client to receive a RELOCATE_NOTIFICATION message.
     * Throws a RuntimeException if the notification is not received
     * before the timeout period, or if the relocation port specified
     * in the notification does not match the specified {@code newPort}.
     */
    public void waitForRelocationNotification(int newPort) {
	System.err.println(toString() +
			   " waiting for relocation notification...");
	synchronized (lock) {
	    try {
		if (relocateSession == false) {
		    lock.wait(WAIT_TIME);
		}
		if (relocateSession != true) {
		    throw new RuntimeException(
			toString() + " relocate notification timed out");
		}
		assertEquals(newPort, relocatePort);
	    } catch (InterruptedException e) {
		throw new RuntimeException(
		    toString() + " relocated timed out", e);
	    }
	}
    }

    /**
     * Throws a {@code RuntimeException} if this session is not
     * logged in.
     */
    private void checkLoggedIn() {
	synchronized (lock) {
	    if (!connected || !loginSuccess) {
		throw new RuntimeException(
		    toString() + " not connected or loggedIn");
	    }
	}
    }

    /**
     * Sends a SESSION_MESSAGE with the specified content.
     */
    public void sendMessage(byte[] message) {
	MessageBuffer buf =
	    new MessageBuffer(5 + reconnectKey.length + message.length);
	buf.putByte(SimpleSgsProtocol.SESSION_MESSAGE).
	    putByteArray(reconnectKey).
	    putByteArray(message);
	try {
	    connection.sendBytes(buf.getBuffer());
	} catch (IOException e) {
	    throw new RuntimeException(e);
	}
    }

    /**
     * From this client, sends the number of messages (each containing a
     * monotonically increasing sequence number), then waits for all the
     * messages to be received by this client's associated {@code
     * ClientSessionListener}, and validates the sequence of messages
     * received by the listener.  
     */
    public void sendMessagesInSequence(int numMessages, int expectedMessages) {
	this.expectedMessages0 = expectedMessages;
	    
	for (int i = 0; i < numMessages; i++) {
	    MessageBuffer buf = new MessageBuffer(4);
	    buf.putInt(i);
	    sendMessage(buf.getBuffer());
	}
	    
	validateMessageSequence(messages, expectedMessages);
    }

    /**
     * Waits for this client to receive the number of messages sent from
     * the application.
     */
    public Queue<byte[]>
	waitForClientToReceiveExpectedMessages(int expectedMessages)
    {
	Queue<byte[]> clientReceivedMessages =
	    listener.getClientReceivedMessages();
	waitForExpectedMessages(clientReceivedMessages, expectedMessages);
	return clientReceivedMessages;
    }

    /**
     * Waits for the number of expected messages to be deposited in the
     * specified message queue.
     */
    private void waitForExpectedMessages(
	Queue<byte[]> messageQueue, int expectedMessages)
    {
	this.expectedMessages0 = expectedMessages;
	synchronized (messageQueue) {
	    if (messageQueue.size() != expectedMessages) {
		try {
		    messageQueue.wait(WAIT_TIME);
		} catch (InterruptedException e) {
		}
	    }
	}
	int receivedMessages = messageQueue.size();
	if (receivedMessages != expectedMessages) {
	    fail(toString() + " expected " + expectedMessages +
		 ", received " + receivedMessages);
	}
    }

    /**
     * Waits for the number of expected messages to be recorded in the
     * specified 'list', and validates that the expected number of messages
     * were received by the ClientSessionListener in the correct sequence.
     */
    public void validateMessageSequence(
	Queue<byte[]> messageQueue, int expectedMessages)
    {
	waitForExpectedMessages(messageQueue, expectedMessages);
	if (expectedMessages != 0) {
	    int i = (new MessageBuffer(messageQueue.peek())).getInt();
	    for (byte[] message : messageQueue) {
		MessageBuffer buf = new MessageBuffer(message);
		int value = buf.getInt();
		System.err.println(toString() + " received message " + value);
		if (value != i) {
		    fail("expected message " + i + ", got " + value);
		}
		i++;
	    }
	}
    }
	
    public void logout() {
	synchronized (lock) {
	    if (connected == false) {
		return;
	    }
	    logoutAck = false;
	    awaitGraceful = true;
	}
	MessageBuffer buf = new MessageBuffer(1);
	buf.putByte(SimpleSgsProtocol.LOGOUT_REQUEST);
	try {
	    connection.sendBytes(buf.getBuffer());
	} catch (IOException e) {
	    throw new RuntimeException(e);
	}
	synchronized (lock) {
	    try {
		if (logoutAck == false) {
		    lock.wait(WAIT_TIME);
		}
		if (logoutAck != true) {
		    throw new RuntimeException(
			toString() + " disconnect timed out");
		}
	    } catch (InterruptedException e) {
		throw new RuntimeException(
		    toString() + " disconnect timed out", e);
	    } finally {
		if (! logoutAck)
		    disconnect();
	    }
	}
    }

    public void checkDisconnectedCallback(boolean graceful)
	throws Exception
    {
	synchronized (disconnectedCallbackLock) {
	    if (!receivedDisconnectedCallback) {
		disconnectedCallbackLock.wait(WAIT_TIME);
	    }
	}
	if (!receivedDisconnectedCallback) {
	    fail(toString() + " disconnected callback not invoked");
	} else if (this.graceful != graceful) {
	    fail(toString() + " graceful was: " + this.graceful +
		 ", expected: " + graceful);
	}
	System.err.println(toString() + " disconnect successful");
    }

    public boolean isConnected() {
	synchronized (lock) {
	    return connected;
	}
    }

    // Returns true if disconnect occurred.
    public boolean waitForDisconnect() {
	synchronized (lock) {
	    try {
		if (connected == true) {
		    lock.wait(WAIT_TIME);
		}
	    } catch (InterruptedException ignore) {
	    }
	    return !connected;
	}
    }

    public String toString() {
	return "[" + name + "]";
    }
	
    private class Listener implements ConnectionListener {

	final Queue<byte[]> clientReceivedMessages =
	    new ConcurrentLinkedQueue<byte[]>();

	Queue<byte[]> getClientReceivedMessages() {
	    return clientReceivedMessages;
	}

	    
	/** {@inheritDoc} */
	public void bytesReceived(Connection conn, byte[] buffer) {
	    if (connection != conn) {
		System.err.println(
		    "DummyClient.Listener connected wrong handle, got:" +
		    conn + ", expected:" + connection);
		return;
	    }

	    MessageBuffer buf = new MessageBuffer(buffer);

	    byte opcode = buf.getByte();

	    switch (opcode) {

	    case SimpleSgsProtocol.LOGIN_SUCCESS:
		reconnectKey = buf.getBytes(buf.limit() - buf.position());
		dummyClients.put(
		    new BigInteger(1, reconnectKey), DummyClient.this);
		synchronized (lock) {
		    loginAck = true;
		    loginSuccess = true;
		    System.err.println("login succeeded: " + name);
		    lock.notifyAll();
		}
		sendMessage(new byte[0]);
		break;
		    
	    case SimpleSgsProtocol.LOGIN_FAILURE:
		reason = buf.getString();
		synchronized (lock) {
		    loginAck = true;
		    loginSuccess = false;
		    System.err.println("login failed: " + name +
				       ", reason:" + reason);
		    lock.notifyAll();
		}
		break;

	    case SimpleSgsProtocol.LOGIN_REDIRECT:
		redirectHost = buf.getString();
		redirectPort = buf.getInt();
		synchronized (lock) {
		    loginAck = true;
		    loginRedirect = true;
		    System.err.println("login redirected: " + name +
				       ", host:" + redirectHost +
				       ", port:" + redirectPort);
		    lock.notifyAll();
		}
		break;
		    
	    case SimpleSgsProtocol.RELOCATE_NOTIFICATION:
		relocateHost = buf.getString();
		relocatePort = buf.getInt();
		relocateKey = buf.getBytes(buf.limit() - buf.position());
		synchronized (lock) {
		    relocateSession = true;
		    System.err.println(
			"session to relocate: " + name +
			", host:" + relocateHost +
			", port:" + relocatePort +
			", key:" + HexDumper.toHexString(relocateKey));
		    lock.notifyAll();
		} break;

	    case SimpleSgsProtocol.RELOCATE_SUCCESS:
		reconnectKey = buf.getBytes(buf.limit() - buf.position());
		dummyClients.put(new BigInteger(1, reconnectKey),
				 DummyClient.this);
		synchronized (lock) {
		    relocateAck = true;
		    relocateSuccess = true;
		    messagesReceivedDuringRelocation = messages.size();
		    System.err.println("relocate succeeded: " + name);
		    lock.notifyAll();
		}
		sendMessage(new byte[0]);
		break;
		    
	    case SimpleSgsProtocol.RELOCATE_FAILURE:
		reason = buf.getString();
		synchronized (lock) {
		    relocateAck = true;
		    relocateSuccess = false;
		    messagesReceivedDuringRelocation = messages.size();
		    System.err.println("relocate failed: " + name +
				       ", reason:" + reason);
		    lock.notifyAll();
		}
		break;
		    
	    case SimpleSgsProtocol.LOGOUT_SUCCESS:
		synchronized (lock) {
		    logoutAck = true;
		    System.err.println("logout succeeded: " + name);
		    lock.notifyAll();
		}
		break;

	    case SimpleSgsProtocol.SESSION_MESSAGE:
		byte[] message = buf.getBytes(buf.limit() - buf.position());
		synchronized (clientReceivedMessages) {
		    clientReceivedMessages.add(message);
		    System.err.println(
			"[" + name + "] received SESSION_MESSAGE: " +
			HexDumper.toHexString(message));
		    if (clientReceivedMessages.size() == expectedMessages0) {
			clientReceivedMessages.notifyAll();
		    }
		}
		break;

	    default:

		System.err.println("bytesReceived: unknown op code: " + opcode);
		break;
	    }
	}

	/**
	 * Gives a subclass a chance to handle an opCode that isn't
	 * processed by this client implementation.
	 */
	protected void unhandledOpCode(byte opCode, MessageBuffer buf) {
	}


	/** {@inheritDoc} */
	public void connected(Connection conn) {
	    System.err.println("DummyClient.Listener.connected");
	    if (connection != null) {
		System.err.println(
		    "DummyClient.Listener.already connected handle: " +
		    connection);
		return;
	    }
	    connection = conn;
	    synchronized (lock) {
		connected = true;
		lock.notifyAll();
	    }
	}

	/** {@inheritDoc} */
	public void disconnected(Connection conn) {
	    synchronized (lock) {
		connected = false;
		connection = null;
		lock.notifyAll();
	    }
	}
	    
	/** {@inheritDoc} */
	public void exceptionThrown(Connection conn, Throwable exception) {
	    System.err.println("DummyClient.Listener.exceptionThrown " +
			       "exception:" + exception);
	    exception.printStackTrace();
	}
    }
}
