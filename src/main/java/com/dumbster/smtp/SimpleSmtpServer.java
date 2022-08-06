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

import javax.security.auth.callback.Callback;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Dummy SMTP server for testing purposes. */
@Slf4j
public final class SimpleSmtpServer implements AutoCloseable {

	/** Default SMTP port is 25. */
	public static final int DEFAULT_SMTP_PORT = 25;

	/** pick any free port. */
	public static final int AUTO_SMTP_PORT = 0;

	/** Stores all of the email received since this instance started up. */
	private final List<SmtpMessage> receivedMail;

	private final CallbackSmtpServer callbackServer;

	/**
	 * Creates an instance of a started SimpleSmtpServer.
	 *
	 * @param port port number the server should listen to
	 * @return a reference to the running SMTP server
	 * @throws IOException when listening on the socket causes one
	 */
	public static SimpleSmtpServer start(int port) throws IOException {
		final List<SmtpMessage> receievedMail = new ArrayList<>();
		Consumer<SmtpMessage> callback = (SmtpMessage m) -> {
			synchronized (receievedMail) {
				receievedMail.add(m);
			}
		};
		return new SimpleSmtpServer(CallbackSmtpServer.startSMTP(port, callback), receievedMail);
	}

	/**
	 * private constructor because factory method {@link #start(int)} better indicates that
	 * the created server is already running
	 */
	private SimpleSmtpServer(CallbackSmtpServer callbackServer, List<SmtpMessage> receivedMail) {
		this.callbackServer = callbackServer;
		this.receivedMail = receivedMail;
	}

	/**
	 * @return the port the server is listening on
	 */
	public int getPort() {
		return callbackServer.getPort();
	}

	/**
	 * @return list of {@link SmtpMessage}s received by since start up or last reset.
	 */
	public List<SmtpMessage> getReceivedEmails() {
		synchronized (receivedMail) {
			return Collections.unmodifiableList(new ArrayList<>(receivedMail));
		}
	}

	/**
	 * forgets all received emails
	 */
	public void reset() {
		synchronized (receivedMail) {
			receivedMail.clear();
		}
	}

	/**
	 * Stops the server. Server is shutdown after processing of the current request is complete.
	 */
	public void stop() {
		this.callbackServer.stop();
	}

	/**
	 * synonym for {@link #stop()}
	 */
	@Override
	public void close() {
		stop();
	}

}
