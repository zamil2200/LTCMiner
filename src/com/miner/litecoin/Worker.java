package com.miner.litecoin;
import java.io.IOException;
import android.util.Log;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.AccessControlException;
import java.security.GeneralSecurityException;
import java.util.Observable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import com.miner.litecoin.Work;
import com.raad287.LTCMiner.R;
import android.util.Log;

public class Worker extends Observable implements Runnable {

	private static final long WORK_TIMEOUT = 60 * 1000; // ms
	
	public static enum Notification {
		SYSTEM_ERROR,
		PERMISSION_ERROR,
		CONNECTION_ERROR,
		AUTHENTICATION_ERROR,
		COMMUNICATION_ERROR,
		LONG_POLLING_FAILED,
		LONG_POLLING_ENABLED,
		NEW_BLOCK_DETECTED,
		NEW_WORK,
		POW_TRUE,
		POW_FALSE,
		TERMINATED
	};
	
	private URL url;
	private String auth;
	private long scanTime; // ms
	private long retryPause; // ms
	private int nThreads;
	private double throttleFactor;
	private volatile Work curWork = null;
	private URL lpUrl = null;
	private HttpURLConnection lpConn = null;
	private AtomicLong hashes = new AtomicLong(0L);
	private Console console;
	private Thread[] threads;
	

	public Worker(URL url, String auth, long scanMillis, long pauseMillis, int nThreads, double throttle, Console c) {
		console = c;
		Log.i("LC","Worker Initializing");
		this.url = url;
		this.auth = auth;
		this.scanTime = scanMillis;
		this.retryPause = pauseMillis;
		if (nThreads < 0)
			throw new IllegalArgumentException();
		this.nThreads = nThreads;
		if (throttle <= 0.0 || throttle > 1.0)
			throw new IllegalArgumentException();
		this.throttleFactor = 1.0 / throttle - 1.0;
		Log.i("LC","Worker Initialized");
	}
	
	public long getRetryPause() {
		Log.i("LC","Worker retryPause");
		return retryPause;
	}
	
	public long getHashes() {
		return hashes.get();
	}
	
	private volatile boolean running = false;
	

	public synchronized void stop() {
		android.util.Log.i("LC","Worker stop");
		console.write("Worker: Stopping work...");
		running = false;
		this.notifyAll();
	}
	
	
	public void run() {
		Log.i("LC","Worker run");

		running = true;
		synchronized (this) {
			threads = new Thread[1 + nThreads];
			for (int i = 0; i < nThreads; ++i)
			(threads[1 + i] = new Thread(new WorkChecker(i))).start();
			do {
				try {
					if (curWork == null || ((Work) curWork).getAge() >= WORK_TIMEOUT || lpUrl == null) {
						curWork = getWork();
						if (lpUrl == null) {
							try {
								if ((lpUrl = curWork.getLongPollingURL()) != null) {
									(threads[0] = new Thread(new LongPoller())).start();
									setChanged();
									notifyObservers(Notification.LONG_POLLING_ENABLED);
								}
							} catch (Exception e) { }
						}
						setChanged();
						notifyObservers(Notification.NEW_WORK);
					}
					if (!running)
						break;

					this.wait(Math.min(scanTime, Math.max(1L, WORK_TIMEOUT - curWork.getAge())));
				} catch (InterruptedException e) {
					
					}
				 catch (NullPointerException e) { 
					Log.i("LC", "In Worker: Null Pointer Exception"+e.getMessage());
				}
			} while (running);
			running = false;
			Log.i("LC", "Worker run loop exited");
			}
			if (lpConn != null) {
				
				lpConn.disconnect();
				Log.i("LC", "Longpoller disconnected");
				console.write("Worker: Longpoller disconnected");
			}
			try 
			{
				for (Thread t : threads) {
					if (t != null) {
			
						Log.i("LC", "Worker: Killing thread ID: "+t.getId());
						console.write("Worker: Killing thread ID: "+t.getId());
						t.join((long) 200); // Work Threads have 200ms to finish
					}
				}
				Log.i("LC", "Worker: Threads Killed");
				console.write("Worker: Threads killed");
			}
			catch (InterruptedException e) { }
		curWork = null;
		setChanged();
		notifyObservers(Notification.TERMINATED);
	}
	
	private synchronized Work getWork() {
		android.util.Log.i("LC","Worker getWork");
		while (running) {
		try {
			Log.i("LC", url+" "+auth);
			return new Work(url, auth);
		} catch (Exception e) {
			if (!running)
				break;
		         setChanged();
		         if (e instanceof IllegalArgumentException) {
		        	 notifyObservers(Notification.AUTHENTICATION_ERROR);
		        	 Log.i("LC", "Auth Error");
		        	 
		        	 stop();
		        	 break;
		         } else if (e instanceof AccessControlException) {
		        	 notifyObservers(Notification.PERMISSION_ERROR);
		        	 Log.i("LC", "Permission Error");
		        	 stop();
		        	 break;
		         } else if (e instanceof IOException) {
		        	 notifyObservers(Notification.CONNECTION_ERROR);
		        	 Log.i("LC", "Connect Error");
		         } else {
		        	 notifyObservers(Notification.COMMUNICATION_ERROR);
		        	 Log.i("LC", "Commm Error: "+e);
		         }
		         try {
		        	 curWork = null;
		        	 this.wait(retryPause);
		         } catch (InterruptedException ie) { }
			}
			}
		return null;
	}
	
	private class LongPoller implements Runnable {
		private static final int READ_TIMEOUT = 30 * 60 * 1000; // ms
		
		public void run() {
		while (running) {
			try {
				lpConn = (HttpURLConnection) lpUrl.openConnection();
				lpConn.setReadTimeout(READ_TIMEOUT);
				curWork = new Work(lpConn, url, auth);
			if (!running)
				break;
			synchronized (Worker.this) {
				setChanged();
				notifyObservers(Notification.NEW_BLOCK_DETECTED);
				setChanged();
				notifyObservers(Notification.NEW_WORK);
		        Worker.this.notify();
		}
		} catch (SocketTimeoutException e) {
		} catch (Exception e) {
		if (!running)
		break;
		setChanged();
		notifyObservers(Notification.LONG_POLLING_FAILED);
		try {
		Thread.sleep(retryPause);
		} catch (InterruptedException ie) { }
		}
		}
		lpUrl = null;
		lpConn = null;
		}
		}
		
		private class WorkChecker implements Runnable {
		private static final long THROTTLE_WAIT_TIME = 100L * 1000000L; // ns
		private int index;
		private int step;
		public WorkChecker(int index) {
		this.index = index;
		for (step = 1; step < nThreads; step <<= 1);
		}
		public void run() {
		try {
		Hasher hasher = new Hasher();
		int nonce = index;
		long dt, t0 = System.nanoTime();
		while (running) {
		try {
		if (curWork.meetsTarget(nonce, hasher)) {
			new Thread(new WorkSubmitter(curWork, nonce)).start();
		if (lpUrl == null) {
		synchronized (Worker.this) {
		curWork = null;
		Worker.this.notify();
		}
		}
		}
		nonce += step;
		hashes.incrementAndGet();
		if (throttleFactor > 0.0 && (dt = System.nanoTime() - t0) > THROTTLE_WAIT_TIME) {
		LockSupport.parkNanos(Math.max(0L, (long) (throttleFactor * dt)));
		t0 = System.nanoTime();
		}
		} catch (NullPointerException e) {
		try {
		Thread.sleep(1L);
		} catch (InterruptedException ie) { }
		}
		}
		} catch (GeneralSecurityException e) {
		setChanged();
		notifyObservers(Notification.SYSTEM_ERROR);
		stop();
		}
		}
		}
		
		private class WorkSubmitter implements Runnable {
		private Work work;
		private int nonce;
		public WorkSubmitter(Work w, int nonce) {
		this.work = w;
		this.nonce = nonce;
		}
		public void run() {
		try {
		boolean result = work.submit(nonce);
		         setChanged();
		notifyObservers(result ? Notification.POW_TRUE : Notification.POW_FALSE);
		} catch (IOException e) { }
		}
		}
		
	
	}
