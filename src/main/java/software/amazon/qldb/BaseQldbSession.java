/*
 * Copyright 2014-2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance with
 * the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package software.amazon.qldb;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazon.ion.IonSystem;
import com.amazonaws.AmazonClientException;

import software.amazon.qldb.exceptions.Errors;

/**
 * The abstract base session to a specific ledger within QLDB, containing the properties and methods shared by the
 * asynchronous and synchronous implementations of a session to a specific ledger within QLDB.
 */
abstract class BaseQldbSession {
    private static final Logger logger = LoggerFactory.getLogger(BaseQldbSession.class);
    private static final long SLEEP_BASE_MS = 10;
    private static final long SLEEP_CAP_MS = 5000;
    static final String TABLE_NAME_QUERY = "SELECT VALUE name FROM information_schema.user_tables WHERE status = 'ACTIVE'";

    final int retryLimit;
    final Session session;
    final AtomicBoolean isClosed = new AtomicBoolean(true);
    final IonSystem ionSystem;

    BaseQldbSession(Session session, int retryLimit, IonSystem ionSystem) {
        this.retryLimit = retryLimit;
        this.ionSystem = ionSystem;
        this.session = session;
        this.isClosed.set(false);
    }

    /**
     * Determine if the driver's sessions are closed.
     *
     * @return {@code true} if the sessions are closed; {@code false} otherwise.
     */
    public boolean isClosed() {
        return isClosed.get();
    }

    /**
     * Retrieve the ledger name session is for.
     *
     * @return The ledger name this session is for.
     */
    public String getLedgerName() {
        return session.getLedgerName();
    }

    /**
     * Retrieve the session token of this session.
     *
     * @return The session token of this session.
     */
    public String getSessionToken() {
        return session.getToken();
    }

    /**
     * Determine if the session is alive by sending an abort message. Will close the session if the abort is
     * unsuccessful.
     *
     * This should only be used when the session is known to not be in use, otherwise the state will be abandoned.
     *
     * @return {@code true} if the session was aborted successfully; {@code false} if the session is closed.
     */
    boolean abortOrClose() {
        if (isClosed.get()) {
            return false;
        }
        try {
            session.sendAbort();
            return true;
        } catch (AmazonClientException e) {
            isClosed.set(true);
            return false;
        }
    }

    /**
     * Mark the session as closed.
     */
    void softClose() {
        isClosed.set(true);
    }

    /**
     * Check and throw if the session is closed.
     *
     * @throws IllegalStateException if the session is closed.
     */
    void throwIfClosed() {
        if (isClosed.get()) {
            logger.error(Errors.SESSION_CLOSED.get());
            throw new IllegalStateException(Errors.SESSION_CLOSED.get());
        }
    }

    /**
     * Implement an exponential backoff with jitter sleep.
     *
     *
     * @param attemptNumber
     *                  The attempt number for the retry, used for the exponential portion of the sleep.
     */
    static void retrySleep(int attemptNumber) {
        try {
            // Algorithm taken from https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/
            final double jitterRand = Math.random();
            final double exponentialBackoff = Math.min(SLEEP_CAP_MS, (long) Math.pow(SLEEP_BASE_MS, attemptNumber));
            Thread.sleep((long) (jitterRand * (exponentialBackoff + 1)));
        } catch (InterruptedException e) {
            // Reset the interruption flag.
            Thread.currentThread().interrupt();
        }
    }
}
