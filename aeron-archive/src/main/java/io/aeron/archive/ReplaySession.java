/*
 * Copyright 2014-2018 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.archive;

import io.aeron.Counter;
import io.aeron.ExclusivePublication;
import io.aeron.Publication;
import io.aeron.logbuffer.ExclusiveBufferClaim;
import io.aeron.protocol.HeaderFlyweight;
import org.agrona.CloseHelper;
import org.agrona.LangUtil;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.File;

/**
 * A replay session with a client which works through the required request response flow and streaming of recorded data.
 * The {@link ArchiveConductor} will initiate a session on receiving a ReplayRequest
 * (see {@link io.aeron.archive.codecs.ReplayRequestDecoder}).
 * <p>
 * The session will:
 * <ul>
 * <li>Validate request parameters and respond with appropriate error if unable to replay.</li>
 * <li>Wait for replay publication to connect to the subscriber. If no subscriber appears within
 * {@link Archive.Configuration#CONNECT_TIMEOUT_PROP_NAME} the session will terminate and respond with an error.</li>
 * <li>Once the replay publication is connected an OK response to control client will be sent.</li>
 * <li>Stream recorded data into the replayPublication {@link ExclusivePublication}.</li>
 * <li>If the replay is aborted part way through, send a ReplayAborted message and terminate.</li>
 * </ul>
 */
class ReplaySession implements Session, SimpleFragmentHandler, AutoCloseable
{
    enum State
    {
        INIT, REPLAY, INACTIVE
    }

    private static final int REPLAY_FRAGMENT_LIMIT = Archive.Configuration.replayFragmentLimit();

    private long connectDeadlineMs;
    private final long correlationId;
    private final long sessionId;
    private final ExclusiveBufferClaim bufferClaim = new ExclusiveBufferClaim();
    private final ExclusivePublication replayPublication;
    private final RecordingFragmentReader cursor;
    private final ControlSession controlSession;
    private final EpochClock epochClock;
    private State state = State.INIT;
    private String errorMessage = null;
    private volatile boolean isAborted;

    ReplaySession(
        final long replayPosition,
        final long replayLength,
        final long replaySessionId,
        final long connectTimeoutMs,
        final Catalog catalog,
        final ControlSession controlSession,
        final File archiveDir,
        final ControlResponseProxy controlResponseProxy,
        final long correlationId,
        final EpochClock epochClock,
        final ExclusivePublication replayPublication,
        final RecordingSummary recordingSummary,
        final Counter recordingPosition)
    {
        this.controlSession = controlSession;
        this.sessionId = replaySessionId;
        this.correlationId = correlationId;
        this.epochClock = epochClock;
        this.replayPublication = replayPublication;

        RecordingFragmentReader cursor = null;
        try
        {
            cursor = new RecordingFragmentReader(
                catalog,
                recordingSummary,
                archiveDir,
                replayPosition,
                replayLength,
                recordingPosition);
        }
        catch (final Exception ex)
        {
            CloseHelper.close(replayPublication);
            final String msg = "replay recording id " + recordingSummary.recordingId + " - " + ex.getMessage();
            controlSession.attemptErrorResponse(correlationId, msg, controlResponseProxy);
            LangUtil.rethrowUnchecked(ex);
        }

        this.cursor = cursor;

        controlSession.sendOkResponse(correlationId, replaySessionId, controlResponseProxy);
        connectDeadlineMs = epochClock.time() + connectTimeoutMs;
    }

    public void close()
    {
        CloseHelper.close(replayPublication);

        if (null != cursor)
        {
            cursor.close();
        }
    }

    public long sessionId()
    {
        return sessionId;
    }

    public int doWork()
    {
        int workCount = 0;

        if (isAborted)
        {
            state = State.INACTIVE;
        }

        if (State.INIT == state)
        {
            workCount += init();
        }

        if (State.REPLAY == state)
        {
            workCount += replay();
        }

        return workCount;
    }

    public void abort()
    {
        isAborted = true;
    }

    public boolean isDone()
    {
        return state == State.INACTIVE;
    }

    public boolean onFragment(
        final UnsafeBuffer buffer,
        final int offset,
        final int length,
        final int frameType,
        final byte flags,
        final long reservedValue)
    {
        long result = 0;
        if (frameType == HeaderFlyweight.HDR_TYPE_DATA)
        {
            final ExclusiveBufferClaim bufferClaim = this.bufferClaim;
            result = replayPublication.tryClaim(length, bufferClaim);
            if (result > 0)
            {
                bufferClaim
                    .flags(flags)
                    .reservedValue(reservedValue)
                    .buffer().putBytes(bufferClaim.offset(), buffer, offset, length);

                bufferClaim.commit();
                return true;
            }
        }
        else if (frameType == HeaderFlyweight.HDR_TYPE_PAD)
        {
            result = replayPublication.appendPadding(length);
            if (result > 0)
            {
                return true;
            }
        }

        if (result == Publication.CLOSED || result == Publication.NOT_CONNECTED)
        {
            onError("stream closed before replay is complete");
        }

        return false;
    }

    long recordingId()
    {
        return cursor.recordingId();
    }

    State state()
    {
        return state;
    }

    void sendPendingError(final ControlResponseProxy controlResponseProxy)
    {
        if (null != errorMessage && !controlSession.isDone())
        {
            controlSession.attemptErrorResponse(correlationId, errorMessage, controlResponseProxy);
        }
    }

    private int init()
    {
        if (!replayPublication.isConnected())
        {
            if (epochClock.time() > connectDeadlineMs)
            {
                onError("no connection established for replay");
            }

            return 0;
        }

        state = State.REPLAY;

        return 1;
    }

    private int replay()
    {
        int workDone = 0;
        try
        {
            workDone = cursor.controlledPoll(this, REPLAY_FRAGMENT_LIMIT);
            if (cursor.isDone())
            {
                state = State.INACTIVE;
            }
        }
        catch (final Exception ex)
        {
            onError("cursor read failed");
            LangUtil.rethrowUnchecked(ex);
        }

        return workDone;
    }

    private void onError(final String errorMessage)
    {
        state = State.INACTIVE;
        this.errorMessage = errorMessage;
    }
}
