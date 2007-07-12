package com.sun.sgs.benchmark.app.impl;

import java.io.Serializable;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import com.sun.sgs.app.AppContext;
import com.sun.sgs.app.ClientSession;

import com.sun.sgs.benchmark.app.BehaviorModule;

/**
 * 
 * 
 */
public class CpuIntensiveModule implements BehaviorModule, Serializable {

    private static final long serialVersionUID = 0x82F9E38CF1DL;

   /**
     * Returns an empty list of {@code Runnable} operations.
     *
     * @param args op-codes denoting the arguments
     *
     * @return an empty list
     */
    public List<Runnable> getOperations(ClientSession session, byte[] args) {
	return new LinkedList<Runnable>();
    }

    /**
     * Returns a list with a single {@code Runnable} that will spin on
     * the CPU for the number of milliseconds specified in the first
     * parameter of {@code args}.
     *
     */
    public List<Runnable> getOperations(ClientSession session, Object[] args) {
	List<Runnable> operations = new LinkedList<Runnable>();
	if (args.length != 1) {
	    System.out.printf("invalid parameter(s) to %s: %s\n",
			      this, Arrays.toString(args));
	    return operations;
	}
	Long duration = null;
	try {
	    duration = (Long)(args[0]);
	}
	catch (ClassCastException cce) {
	    System.out.printf("invalid parameter(s) to %s: %s\n" +
			      "expected java.lang.Long\n" ,
			      this, args[0]);
	    return operations;
	}

	final long d = duration.longValue();
	operations.add(new Runnable() {
		public void run() {
		    long startTime = System.currentTimeMillis();
		    long stopTime = startTime + d;
		    for (int i = Integer.MIN_VALUE; 
			 System.currentTimeMillis() < stopTime;
			 i++);
		}
	    });
	return operations;
    }

}
