/*
 * Dumbster - a dummy SMTP server
 * Copyright 2016 Joachim Nicolay
 * Copyright 2004 Jason Paul Kitchen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dumbster.smtp;

import lombok.extern.slf4j.Slf4j;

import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Dummy SMTP server for testing purposes. */
@Slf4j
public final class CallbackSmtpServer implements AutoCloseable {

	/** Default SMTP port is 25. */
	public static final int DEFAULT_SMTP_PORT = 25;

	/** Default SMTPS port is 587. */
	public static final int DEFAULT_SMTPS_PORT = 587;

	/** pick any free port. */
	public static final int AUTO_SMTP_PORT = 0;

	/** When stopping wait this long for any still ongoing transmission */
	private static final int STOP_TIMEOUT = 20000;

	private static final Pattern CRLF = Pattern.compile("\r\n");

	/** The server socket this server listens to. */
	private final ServerSocket serverSocket;

	private final ExecutorService pool = Executors.newCachedThreadPool();

	/** Indicates the server thread that it should stop */
	private AtomicBoolean stopped = new AtomicBoolean(false);

	/**
	 * Creates an instance of a started SimpleSmtpServer.
	 *
	 * @param port port number the server should listen to
	 * @return a reference to the running SMTP server
	 * @throws IOException when listening on the socket causes one
	 */
	public static CallbackSmtpServer startSMTP(int port, Consumer<SmtpMessage> callback) throws IOException {
		return new CallbackSmtpServer(new ServerSocket(Math.max(port, 0)), callback);
	}

	public static CallbackSmtpServer startSMTPS(int port, String keystorePath, String keystorePassword, Consumer<SmtpMessage> callback) throws IOException {
		ServerSocketFactory factory = createSSLServerSocketFactory(keystorePath, keystorePassword);
		return new CallbackSmtpServer(factory.createServerSocket(port), callback);
	}

	/**
	 * private constructor because factory method {@link #start(int)} better indicates that
	 * the created server is already running
	 * @param serverSocket socket to listen on
	 */
	private CallbackSmtpServer(ServerSocket serverSocket, Consumer<SmtpMessage> callback) {
		this.serverSocket = serverSocket;
		this.pool.execute(() -> handleConnection(serverSocket, this.stopped, this.pool, callback));
	}

	/**
	 * @return the port the server is listening on
	 */
	public int getPort() {
		return serverSocket.getLocalPort();
	}

	/**
	 * Stops the server. Server is shutdown after processing of the current request is complete.
	 */
	public void stop() {
		if (stopped.get()) {
			return;
		}
		// Mark us closed
		stopped.set(true);
		try {
			// Kick the server accept loop
			serverSocket.close();
		} catch (IOException e) {
			log.warn("trouble closing the server socket", e);
		}
		// and block until worker is finished
		try {
			pool.shutdownNow();
			pool.awaitTermination(STOP_TIMEOUT, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			log.warn("interrupted when waiting for worker thread to finish", e);
		}
	}

	/**
	 * synonym for {@link #stop()}
	 */
	@Override
	public void close() {
		stop();
	}

	public static SSLServerSocketFactory createSSLServerSocketFactory(String keystorePath, String keystorePassword) throws RuntimeException {
		try {
			KeyStore keystore = KeyStore.getInstance("JKS");
			keystore.load(new FileInputStream(keystorePath), keystorePassword.toCharArray());
			KeyManagerFactory keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			keyManager.init(keystore, keystorePassword.toCharArray());
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(keyManager.getKeyManagers(), null, null);
			return context.getServerSocketFactory();
		} catch(Exception e) {
			throw new RuntimeException(e);
		}
	}

	static void handleConnection(ServerSocket serverSocket, AtomicBoolean stopped, ExecutorService pool, Consumer<SmtpMessage> handler) {
		try {
			// Server: loop until stopped
			while (!stopped.get()) {
				// Start server socket and listen for client connections
				//noinspection resource
				try (Socket socket = serverSocket.accept()) {
					Runnable accept = () -> {
						try {
							Scanner input = new Scanner(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1)).useDelimiter(CRLF);
							PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1));
							List<SmtpMessage> messages = handleTransaction(out, input);
							messages.forEach(handler);
						} catch(Exception e) {
							throw new RuntimeException(e);
						}
					};
					pool.execute(accept);
				}
			}
		} catch (Exception e) {
			// SocketException expected when stopping the server
			if (!stopped.get()) {
				log.error("hit exception when running server", e);
				try {
					serverSocket.close();
				} catch (IOException ex) {
					log.error("and one when closing the port", ex);
				}
			}
		}
	}

	/**
	 * Handle an SMTP transaction, i.e. all activity between initial connect and QUIT command.
	 *
	 * @param out   output stream
	 * @param input input stream
	 * @return List of SmtpMessage
	 * @throws IOException
	 */
	private static List<SmtpMessage> handleTransaction(PrintWriter out, Iterator<String> input) throws IOException {
		// Initialize the state machine
		SmtpState smtpState = SmtpState.CONNECT;
		SmtpRequest smtpRequest = new SmtpRequest(SmtpActionType.CONNECT, "", smtpState);

		// Execute the connection request
		SmtpResponse smtpResponse = smtpRequest.execute();

		// Send initial response
		sendResponse(out, smtpResponse);
		smtpState = smtpResponse.getNextState();

		List<SmtpMessage> msgList = new ArrayList<>();
		SmtpMessage msg = new SmtpMessage();

		while (smtpState != SmtpState.CONNECT) {
			String line = input.next();

			if (line == null) {
				break;
			}

			// Create request from client input and current state
			SmtpRequest request = SmtpRequest.createRequest(line, smtpState);
			// Execute request and create response object
			SmtpResponse response = request.execute();
			// Move to next internal state
			smtpState = response.getNextState();
			// Send response to client
			sendResponse(out, response);

			// Store input in message
			String params = request.params;
			msg.store(response, params);

			// If message reception is complete save it
			if (smtpState == SmtpState.QUIT) {
				msgList.add(msg);
				msg = new SmtpMessage();
			}
		}

		return msgList;
	}

	/**
	 * Send response to client.
	 *
	 * @param out          socket output stream
	 * @param smtpResponse response object
	 */
	private static void sendResponse(PrintWriter out, SmtpResponse smtpResponse) {
		if (smtpResponse.getCode() > 0) {
			int code = smtpResponse.getCode();
			String message = smtpResponse.getMessage();
			out.print(code + " " + message + "\r\n");
			out.flush();
		}
	}
}
