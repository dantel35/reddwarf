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

package com.sun.sgs.impl.util.lock;

import com.sun.sgs.impl.sharedutil.LoggerWrapper;
import static com.sun.sgs.impl.sharedutil.Objects.uncheckedCast;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static java.util.logging.Level.FINER;
import static java.util.logging.Level.FINEST;
import java.util.logging.Logger;

/**
 * A class for managing lock conflicts. <p>
 *
 * This class uses the {@link Logger} named {@code com.sun.sgs.impl.util.lock}
 * to log information at the following logging levels: <p>
 *
 * <ul>
 * <li> {@link #FINER FINER} - Releasing locks; requesting, waiting for, and
 *	returning from lock requests
 * <li> {@link #FINEST FINEST} - Notifying new lock owners, results of
 *	requesting locks before waiting, releasing locks, results of attempting
 *	to assign locks to waiters
 * </ul> <p>
 *
 * The implementation of this class uses the following thread synchronization
 * scheme to avoid internal deadlocks:
 *
 * <ul>
 *
 * <li>Synchronization is only used on {@link Locker} objects and on the {@code
 *     Map}s that hold {@code Lock} objects
 *
 * <li>A thread can synchronize on at most one locker and one lock at a time,
 *     always synchronizing on the locker first
 *
 * </ul>
 *
 * To make it easier to adhere to these rules, the implementation takes the
 * following steps:
 *
 * <ul>
 *
 * <li>The {@code Lock} class is not synchronized <p>
 *
 *     Callers of non-{@code Object} methods on the {@code Lock} class should
 *     make sure that they are synchronized on the associated key map.
 *
 * <li>The {@link Locker} class and its subclasses only use synchronization for
 *     getter and setter methods
 *
 * <li>Blocks synchronized on a {@code Lock} should not synchronize on anything
 *     else <p>
 *
 *     The code enforces this requirement by having lock methods not make calls
 *     to other classes, and by performing minimal work while synchronized on
 *     the associated key map.
 *
 * <li>Blocks synchronized on a {@code Locker} should not synchronize on a
 *     different locker, but can synchronize on a {@code Lock}
 *
 *     In fact, only one method synchronizes on a {@code Locker} and on a
 *     {@code Lock} -- the {@link #waitForLockInternal waitForLockInternal}
 *     method.  That method also makes sure that the only synchronized {@code
 *     Locker} methods that it calls are on the locker it has already
 *     synchronized on.
 *
 * <li>Use assertions to check adherence to the scheme
 *
 * </ul>
 *
 * @param	<K> the type of key
 * @param	<L> the type of locker
 */
public abstract class LockManager<K, L extends Locker<K, L>> {

    /** The logger for this class. */
    static final LoggerWrapper logger = new LoggerWrapper(
	Logger.getLogger("com.sun.sgs.impl.util.lock"));

    /**
     * The maximum number of milliseconds to spend attempting to acquire a
     * lock.
     */
    private final long lockTimeout;

    /**
     * The number of separate maps to use for storing keys in order to support
     * concurrent access.
     */
    private final int numKeyMaps;

    /**
     * An array of maps from key to lock.  The map to use is chosen by using
     * the key's hash code mod the number of key maps.  Synchronization for
     * locks is based on locking the associated key map.  Non-{@code Object}
     * methods on locks should not be used without synchronizing on the
     * associated key map lock.
     */
    private final Map<K, Lock<K, L>>[] keyMaps;

    /**
     * When assertions are enabled, holds the {@code Locker} that the
     * current thread is synchronized on, if any.
     */
    final ThreadLocal<Locker<K, L>> currentLockerSync =
	new ThreadLocal<Locker<K, L>>();

    /**
     * When assertions are enabled, hold the {@code Key} whose associated
     * {@code Map} the current thread is synchronized on, if any.
     */
    final ThreadLocal<K> currentKeySync = new ThreadLocal<K>();

    /* -- Constructor -- */

    /**
     * Creates an instance of this class.
     *
     * @param	lockTimeout the maximum number of milliseconds to acquire a
     *		lock
     * @param	numKeyMaps the number of separate maps to use for storing keys
     * @throws	IllegalArgumentException if {@code lockTimeout} or {@code
     *		numKeyMaps} is less than {@code 1}
     */
    LockManager(long lockTimeout, int numKeyMaps) {
	if (lockTimeout < 1) {
	    throw new IllegalArgumentException(
		"The lockTimeout must not be less than 1");
	} else if (numKeyMaps < 1) {
	    throw new IllegalArgumentException(
		"The numKeyMaps must not be less than 1");
	}
	this.lockTimeout = lockTimeout;
	this.numKeyMaps = numKeyMaps;
	keyMaps = uncheckedCast(new Map[numKeyMaps]);
	for (int i = 0; i < numKeyMaps; i++) {
	    keyMaps[i] = new HashMap<K, Lock<K, L>>();
	}
    }

    /* -- Public methods -- */

    /**
     * Waits for a previous attempt to obtain a lock that blocked.  Returns
     * information about any conflict that occurred while attempting to acquire
     * the lock, or else {@code null} if the lock was acquired or the
     * transaction was not waiting.  If the {@code type} field of the return
     * value is {@link LockConflictType#DEADLOCK DEADLOCK}, then the caller
     * should abort the transaction, and any subsequent lock or wait requests
     * will throw {@code IllegalStateException}.
     *
     * @param	locker the locker requesting the lock
     * @return	lock conflict information, or {@code null} if there was no
     *		conflict
     * @throws	IllegalArgumentException if {@code locker} has a different lock
     *		manager 
     * @throws	IllegalStateException if an earlier lock attempt for this
     *		transaction produced a deadlock
     */
    public LockConflict<K, L> waitForLock(L locker) {
	checkLockManager(locker);
	checkLockerNotAborted(locker);
	return waitForLockInternal(locker);
    }

    /**
     * Releases a lock held by a locker.  This method does nothing if the lock
     * is not held.
     *
     * @param	locker the locker holding the lock
     * @param	key the key identifying the lock
     * @throws	IllegalArgumentException if {@code locker} has a different lock
     *		manager 
     */
    public void releaseLock(L locker, K key) {
	if (logger.isLoggable(FINER)) {
	    logger.log(FINER, "release {0} {1}", locker, key);
	}
	releaseLockInternal(locker, key, false);
    }

    /**
     * Returns a possibly read-only list that contains a snapshot of the
     * current owners of a lock, as identified by lock requests.
     *
     * @param	key the key identifying the lock
     * @return	a list of the requests
     */
    public List<LockRequest<K, L>> getOwners(K key) {
	Map<K, Lock<K, L>> keyMap = getKeyMap(key);
	assert Lock.noteSync(this, key);
	try {
	    synchronized (keyMap) {
		return getLock(key, keyMap).copyOwners(this);
	    }
	} finally {
	    assert Lock.noteUnsync(this, key);
	}
    }

    /**
     * Returns a possibly read-only list that contains a snapshot of the
     * current waiters for a lock, as identified by lock requests.
     *
     * @param	key the key identifying the lock
     * @return	a list of the requests
     */
    public List<LockRequest<K, L>> getWaiters(K key) {
	Map<K, Lock<K, L>> keyMap = getKeyMap(key);
	assert Lock.noteSync(this, key);
	try {
	    synchronized (keyMap) {
		return getLock(key, keyMap).copyWaiters(this);
	    }
	} finally {
	    assert Lock.noteUnsync(this, key);
	}
    }

    /**
     * A utility method that adds two non-negative longs, returning {@link
     * Long#MAX_VALUE} if the value would overflow.
     *
     * @param	x first value
     * @param	y second value
     * @return	the sum
     */
    public static long addCheckOverflow(long x, long y) {
	assert x >= 0 && y >= 0;
	long result = x + y;
	return (result >= 0) ? result : Long.MAX_VALUE;
    }

    /* -- Package access methods -- */

    /**
     * Attempts to acquire a lock, waiting if needed, and supplying an optional
     * timestamp.  Returns information about conflicts that occurred while
     * attempting to acquire the lock that prevented the lock from being
     * acquired, or else {@code null} if the lock was acquired.  If the {@code
     * type} field of the return value is {@link LockConflictType#DEADLOCK
     * DEADLOCK}, then the caller should abort the transaction, and any
     * subsequent lock or wait requests will throw {@code
     * IllegalStateException}.  If the {@code requestedStartTime} is not {@code
     * -1}, then it specifies the time when the operation that is requesting
     * the lock was originally requested to start.
     *
     * @param	locker the locker requesting the lock
     * @param	key the key identifying the lock
     * @param	forWrite whether to request a write lock
     * @param	requestedStartTime the time in milliseconds that the operation
     *		associated with this request was originally requested to start,
     *		or {@code -1} if not specified
     * @return	lock conflict information, or {@code null} if there was no
     *		conflict
     * @throws	IllegalArgumentException if {@code locker} has a different lock
     *		manager, or if {@code requestedStartTime} is less than {@code
     *		-1}
     * @throws	IllegalStateException if an earlier lock attempt for this
     *		transaction produced a deadlock, or if still waiting for an
     *		earlier attempt to complete
     */
    LockConflict<K, L> lock(
	L locker, K key, boolean forWrite, long requestedStartTime)
    {
	checkLockManager(locker);
	checkLockerNotAborted(locker);
	LockConflict<K, L> conflict =
	    lockNoWaitInternal(locker, key, forWrite, requestedStartTime);
	return (conflict == null) ? null : waitForLockInternal(locker);
    }

    /**
     * Attempts to acquire a lock, returning immediately, and supplying an
     * optional timestamp.  Returns information about any conflict that
     * occurred while attempting to acquire the lock, or else {@code null} if
     * the lock was acquired.  If the attempt to acquire the lock was blocked,
     * returns a value with a {@code type} field of {@link
     * LockConflictType#BLOCKED BLOCKED} rather than waiting.  If the {@code
     * type} field of the return value is {@link LockConflictType#DEADLOCK
     * DEADLOCK}, then the caller should abort the transaction, and any
     * subsequent lock or wait requests will throw {@code
     * IllegalStateException}.
     *
     * @param	locker the locker requesting the lock
     * @param	key the key identifying the lock
     * @param	forWrite whether to request a write lock
     * @param	requestedStartTime the time in milliseconds that the operation
     *		associated with this request was originally requested to start,
     *		or {@code -1} if not specified
     * @return	lock conflict information, or {@code null} if there was no
     *		conflict
     * @throws	IllegalArgumentException if {@code locker} has a different lock
     *		manager 
     * @throws	IllegalStateException if an earlier lock attempt for this
     *		transaction produced a deadlock, or if still waiting for an
     *		earlier attempt to complete
     */
    LockConflict<K, L> lockNoWait(
	L locker, K key, boolean forWrite, long requestedStartTime)
    {
	checkLockManager(locker);
	checkLockerNotAborted(locker);
	return lockNoWaitInternal(locker, key, forWrite, requestedStartTime);
    }

    /**
     * Returns the key map to use for the specified key.
     *
     * @param	key the key
     * @return	the associated key map
     */
    Map<K, Lock<K, L>> getKeyMap(K key) {
	/* Mask off the sign bit to get a positive value */
	int index = (key.hashCode() & Integer.MAX_VALUE) % numKeyMaps;
	return keyMaps[index];
    }

    /**
     * Returns the lock associated with the specified key from the key map,
     * which should be the one returned by calling {@link #getKeyMap
     * getKeyMap}.  The lock on {@code keyMap} should be held.
     *
     * @param	key the key
     * @param	keyMap the keyMap
     * @return	the associated lock
     */
    Lock<K, L> getLock(K key, Map<K, Lock<K, L>> keyMap) {
	assert Thread.holdsLock(keyMap);
	Lock<K, L> lock = keyMap.get(key);
	if (lock == null) {
	    lock = new Lock<K, L>(key);
	    keyMap.put(key, lock);
	}
	return lock;
    }

    /** Attempts to acquire a lock, returning immediately. */
    LockConflict<K, L> lockNoWaitInternal(
	L locker, K key, boolean forWrite, long requestedStartTime)
    {
	if (locker.getWaitingFor() != null) {
	    throw new IllegalStateException(
		"Attempt to obtain a new lock while waiting");
	}
	LockConflict<K, L> conflict = locker.getConflict();
	if (conflict != null) {
	    if (conflict.type == LockConflictType.DEADLOCK) {
		throw new IllegalStateException(
		    "Attempt to obtain a new lock after a deadlock");
	    } else {
		/* Ignoring the previous conflict */
		locker.setConflict(null);
	    }
	}
	LockAttemptResult<K, L> result;
	Map<K, Lock<K, L>> keyMap = getKeyMap(key);
	assert Lock.noteSync(this, key);
	try {
	    synchronized (keyMap) {
		Lock<K, L> lock = getLock(key, keyMap);
		result =
		    lock.lock(locker, forWrite, false, requestedStartTime);
	    }
	} finally {
	    assert Lock.noteUnsync(this, key);
	}
	if (result == null) {
	    if (logger.isLoggable(FINER)) {
		logger.log(FINER,
			   "lock {0}, {1}, forWrite:{2}" +
			   "\n  returns null (already granted)",
			   locker, key, forWrite);
	    }
	    return null;
	}
	if (result.conflict == null) {
	    if (logger.isLoggable(FINER)) {
		logger.log(FINER,
			   "lock {0}, {1}, forWrite:{2}" +
			   "\n  returns null (granted)",
			   locker, key, forWrite);
	    }
	    return null;
	}
	if (logger.isLoggable(FINEST)) {
	    logger.log(FINEST,
		       "lock attempt {0}, {1}, forWrite:{2}" +
		       "\n  returns blocked",
		       locker, key, forWrite);
	}
	locker.setWaitingFor(result);
	conflict = new LockConflict<K, L>(
	    result.request, LockConflictType.BLOCKED, result.conflict);
	if (logger.isLoggable(FINER)) {
	    logger.log(FINER,
		       "lock {0}, {1}, forWrite:{2}\n  returns {3}",
			locker, key, forWrite, conflict);
	}
	return conflict;
    }

    /** Releases a lock, only downgrading it if downgrade is true. */
    void releaseLockInternal(L locker, K key, boolean downgrade) {
	checkLockManager(locker);
	List<L> newOwners = Collections.emptyList();
	Map<K, Lock<K, L>> keyMap = getKeyMap(key);
	assert Lock.noteSync(this, key);
	try {
	    synchronized (keyMap) {
		/* Don't create the lock if it isn't present */
		Lock<K, L> lock = keyMap.get(key);
		if (lock != null) {
		    newOwners = lock.release(locker, downgrade);
		    if (!lock.inUse(locker.lockManager)) {
			keyMap.remove(key);
		    }
		}
	    }
	} finally {
	    assert Lock.noteUnsync(this, key);
	}
	for (L newOwner : newOwners) {
	    logger.log(FINEST, "notify new owner {0}", newOwner);
	    assert newOwner.noteSync();
	    try {
		synchronized (newOwner) {
		    newOwner.notify();
		}
	    } finally {
		assert newOwner.noteUnsync();
	    }
	}
    }

    /* -- Private methods -- */

    /** Checks if the locker has been requested to abort. */
    private static <K, L extends Locker<K, L>> void checkLockerNotAborted(
	L locker)
    {
	LockConflict<K, L> lockConflict = locker.getConflict();
	if (lockConflict != null &&
	    lockConflict.type == LockConflictType.DEADLOCK)
	{
	    throw new IllegalStateException(
		"Transaction must abort: " + locker);
	}
    }

    /** Attempts to acquire a lock, waiting if needed. */
    private LockConflict<K, L> waitForLockInternal(L locker) {
	assert locker.noteSync();
	try {
	    synchronized (locker) {
		LockAttemptResult<K, L> result = locker.getWaitingFor();
		if (result == null) {
		    logger.log(
			FINER,
			"lock {0}\n  returns null (not waiting)", locker);
		    return null;
		}
		Lock<K, L> lock;
		K key = result.request.key;
		Map<K, Lock<K, L>> keyMap = getKeyMap(key);
		assert Lock.noteSync(this, key);
		try {
		    synchronized (keyMap) {
			lock = getLock(key, keyMap);
		    }
		} finally {
		    assert Lock.noteUnsync(this, key);
		}
		long now = System.currentTimeMillis();
		long stop = locker.getLockTimeoutTime(now, lockTimeout);
		LockConflict<K, L> conflict = null;
		while (true) {
		    conflict = locker.getConflict();
		    if (conflict != null) {
			break;
		    } else if (now >= stop) {
			conflict = new LockConflict<K, L>(
			    result.request, LockConflictType.TIMEOUT,
			    result.conflict);
			locker.setConflict(conflict);
			break;
		    }
		    boolean isOwner;
		    assert Lock.noteSync(this, key);
		    try {
			synchronized (keyMap) {
			    isOwner = lock.isOwner(result.request);
			}
		    } finally {
			assert Lock.noteUnsync(this, key);
		    }
		    if (isOwner) {
			locker.setWaitingFor(null);
			if (logger.isLoggable(FINER)) {
			    logger.log(
				FINER,
				"lock {0}, {1}, forWrite:{2}" +
				"\n  returns null (granted)",
				locker, key, result.request.getForWrite());
			}
			return null;
		    }
		    if (logger.isLoggable(FINER)) {
			logger.log(FINER,
				   "wait for lock {0}, {1}, forWrite:{2}" +
				   ", wait:{3,number,#}",
				   locker, key, result.request.getForWrite(),
				   stop - now);
		    }
		    try {
			locker.wait(stop - now);
		    } catch (InterruptedException e) {
			conflict = new LockConflict<K, L>(
			    result.request, LockConflictType.INTERRUPTED,
			    result.conflict);
			locker.setConflict(conflict);
			break;
		    }
		    now = System.currentTimeMillis();
		}
		assert conflict != null;
		assert Lock.noteSync(this, key);
		try {
		    synchronized (keyMap) {
			lock.flushWaiter(locker);
		    }
		} finally {
		    assert Lock.noteUnsync(this, key);
		}
		locker.setWaitingFor(null);
		if (logger.isLoggable(FINER)) {
		    logger.log(FINER,
			       "lock {0}, {1}, forWrite:{2}\n  returns {3}",
			       locker, key, result.request.getForWrite(),
			       conflict);
		}
		return conflict;
	    }
	} finally {
	    assert locker.noteUnsync();
	}
    }

    /** Checks that the locker has this lock manager. */
    private void checkLockManager(L locker) {
	if (locker.getLockManager() != this) {
	    throw new IllegalArgumentException(
		"The locker has a different lock manager");
	}
    }
}