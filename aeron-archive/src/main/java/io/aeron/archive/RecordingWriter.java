/*
 * Copyright 2014-2017 Real Logic Ltd.
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
package io.aeron.archive;

import io.aeron.archive.codecs.RecordingDescriptorDecoder;
import io.aeron.archive.codecs.RecordingDescriptorEncoder;
import io.aeron.logbuffer.FrameDescriptor;
import io.aeron.logbuffer.Header;
import io.aeron.logbuffer.RawBlockHandler;
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.*;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;

import static io.aeron.archive.ArchiveUtil.recordingOffset;

/**
 * Responsible for writing out a recording into the file system. A recording has descriptor file and a set of data files
 * written into the archive folder.
 * <p>
 * Design note: While this class is notionally closely related to the {@link RecordingSession} it is separated from it
 * for the following reasons:
 * <ul>
 * <li> Easier testing and in particular simplified re-use in testing. </li>
 * <li> Isolation of an external relationship, namely the FS</li>
 * <li> While a {@link RecordingWriter} is part of a {@link RecordingSession}, a session may transition without actually
 * creating a {@link RecordingWriter}.</li>
 * </ul>
 */
class RecordingWriter implements AutoCloseable, RawBlockHandler
{
    private static final boolean POSITION_CHECKS =
        !Boolean.getBoolean("aeron.archive.recorder.position.checks.off");

    private static final int NULL_SEGMENT_POSITION = -1;

    private final boolean forceWrites;
    private final boolean forceMetadata;
    private final int initialRecordingTermId;
    private final int termBufferLength;
    private final int termsMask;
    private final long recordingId;

    private final FileChannel archiveDirChannel;
    private final File archiveDir;
    private final EpochClock epochClock;
    private final UnsafeBuffer descriptorBuffer;
    private final RecordingDescriptorEncoder descriptorEncoder;
    private final int segmentFileLength;
    private final long startPosition;

    /**
     * Index is in the range 0:segmentFileLength, except before the first block for this image is received indicated
     * by NULL_SEGMENT_POSITION
     */
    private int segmentPosition = NULL_SEGMENT_POSITION;
    private int segmentIndex = 0;
    private FileChannel recordingFileChannel;

    private boolean closed = false;
    private long stopPosition = Catalog.NULL_POSITION;
    private long startTimestamp = Catalog.NULL_TIME;
    private long stopTimestamp = Catalog.NULL_TIME;

    RecordingWriter(final Context context, final UnsafeBuffer descriptorBuffer)
    {
        this.descriptorBuffer = descriptorBuffer;
        descriptorEncoder = new RecordingDescriptorEncoder().wrap(descriptorBuffer, Catalog.CATALOG_FRAME_LENGTH);
        final RecordingDescriptorDecoder descriptorDecoder = new RecordingDescriptorDecoder().wrap(
            descriptorBuffer,
            Catalog.CATALOG_FRAME_LENGTH,
            RecordingDescriptorDecoder.BLOCK_LENGTH,
            RecordingDescriptorDecoder.SCHEMA_VERSION);

        final int termBufferLength = descriptorDecoder.termBufferLength();

        this.epochClock = context.epochClock;
        this.archiveDirChannel = context.archiveDirChannel;
        this.archiveDir = context.archiveDir;
        this.segmentFileLength = Math.max(context.segmentFileLength, termBufferLength);
        this.forceWrites = context.fileSyncLevel > 0;
        this.forceMetadata = context.fileSyncLevel > 1;

        this.termBufferLength = termBufferLength;
        this.recordingId = descriptorDecoder.recordingId();
        this.startPosition = descriptorDecoder.startPosition();
        this.initialRecordingTermId = (int) (descriptorDecoder.initialTermId() + (startPosition / termBufferLength));
        this.stopPosition = startPosition;

        this.termsMask = (segmentFileLength / this.termBufferLength) - 1;
        if (((termsMask + 1) & termsMask) != 0)
        {
            throw new IllegalArgumentException(
                "It is assumed the termBufferLength is a power of 2, and that the number of terms" +
                    "in a file is also a power of 2");
        }
    }

    public void onBlock(
        final FileChannel fileChannel,
        final long fileOffset,
        final UnsafeBuffer termBuffer,
        final int termOffset,
        final int blockLength,
        final int sessionId,
        final int termId)
    {
        try
        {
            if (Catalog.NULL_POSITION == segmentPosition)
            {
                onFirstWrite(termOffset);
            }

            if (segmentFileLength == segmentPosition)
            {
                onFileRollOver();
            }

            validateWritePreConditions(termId, termOffset, blockLength);

            long written = 0;
            do
            {
                written += transferTo(fileChannel, fileOffset + written, (int) (blockLength - written));
            }
            while (written != blockLength);

            if (forceWrites)
            {
                forceData(recordingFileChannel, forceMetadata);
            }

            afterWrite(blockLength);
            validateWritePostConditions();
        }
        catch (final ClosedByInterruptException e)
        {
            Thread.interrupted();
            close();
            throw new IllegalStateException("Image file channel has been closed by interrupt, recording aborted.", e);
        }
        catch (final Exception ex)
        {
            close();
            LangUtil.rethrowUnchecked(ex);
        }
    }

    public void close()
    {
        if (closed)
        {
            return;
        }

        closed = true;

        if (descriptorBuffer != null)
        {
            UnsafeAccess.UNSAFE.storeFence();
            stopTimestamp = epochClock.time();
            descriptorEncoder.stopTimestamp(stopTimestamp);
            UnsafeAccess.UNSAFE.storeFence();
        }

        if (recordingFileChannel != null)
        {
            CloseHelper.close(recordingFileChannel);
        }
    }

    /**
     * Convenience method for testing purposes only.
     */
    void writeFragment(final DirectBuffer buffer, final Header header)
    {
        final int termOffset = header.termOffset();
        final int frameLength = header.frameLength();
        final int alignedLength = BitUtil.align(frameLength, FrameDescriptor.FRAME_ALIGNMENT);

        try
        {
            if (Catalog.NULL_POSITION == segmentPosition)
            {
                onFirstWrite(termOffset);
            }
            if (segmentFileLength == segmentPosition)
            {
                onFileRollOver();
            }

            validateWritePreConditions(header.termId(), termOffset, alignedLength);

            final ByteBuffer src = buffer.byteBuffer().duplicate();
            src.position(termOffset).limit(termOffset + frameLength);
            final int written = writeData(src, segmentPosition, recordingFileChannel);
            recordingFileChannel.position(segmentPosition + alignedLength);

            if (written != frameLength)
            {
                throw new IllegalStateException();
            }

            if (forceWrites)
            {
                forceData(recordingFileChannel, forceMetadata);
            }

            afterWrite(alignedLength);
            validateWritePostConditions();
        }
        catch (final Exception ex)
        {
            close();
            LangUtil.rethrowUnchecked(ex);
        }
    }

    long recordingId()
    {
        return recordingId;
    }

    int segmentFileLength()
    {
        return segmentFileLength;
    }

    long startPosition()
    {
        return startPosition;
    }

    long stopPosition()
    {
        return stopPosition;
    }

    // extend for testing
    int writeData(final ByteBuffer buffer, final int position, final FileChannel fileChannel) throws IOException
    {
        return fileChannel.write(buffer, position);
    }

    // extend for testing
    long transferTo(
        final FileChannel fromFileChannel,
        final long fileOffset,
        final int blockLength) throws IOException
    {
        return fromFileChannel.transferTo(fileOffset, blockLength, recordingFileChannel);
    }

    // extend for testing
    void newRecordingSegmentFile()
    {
        final String segmentFileName = ArchiveUtil.recordingDataFileName(recordingId, segmentIndex);
        final File file = new File(archiveDir, segmentFileName);

        RandomAccessFile recordingFile = null;
        try
        {
            recordingFile = new RandomAccessFile(file, "rw");
            recordingFile.setLength(segmentFileLength + DataHeaderFlyweight.HEADER_LENGTH);
            recordingFileChannel = recordingFile.getChannel();
            if (forceWrites && null != archiveDirChannel)
            {
                forceData(archiveDirChannel, forceMetadata);
            }
        }
        catch (final IOException ex)
        {
            CloseHelper.quietClose(recordingFile);
            close();
            LangUtil.rethrowUnchecked(ex);
        }
    }

    // extend for testing
    void forceData(final FileChannel fileChannel, final boolean forceMetadata) throws IOException
    {
        fileChannel.force(forceMetadata);
    }

    boolean isClosed()
    {
        return closed;
    }

    private void onFileRollOver()
    {
        CloseHelper.close(recordingFileChannel);
        segmentPosition = 0;
        segmentIndex++;
        newRecordingSegmentFile();
    }

    private void onFirstWrite(final int termOffset) throws IOException
    {
        validateStartTermOffset(termOffset);

        segmentPosition = termOffset;
        startTimestamp = epochClock.time();
        descriptorEncoder.startTimestamp(startTimestamp);
        newRecordingSegmentFile();

        if (segmentPosition != 0)
        {
            recordingFileChannel.position(segmentPosition);
        }
    }

    private void afterWrite(final int blockLength)
    {
        UnsafeAccess.UNSAFE.storeFence();
        segmentPosition += blockLength;
        stopPosition += blockLength;
        descriptorEncoder.stopPosition(stopPosition);
        UnsafeAccess.UNSAFE.storeFence();
    }

    private void validateStartTermOffset(final int termOffset)
    {
        if (POSITION_CHECKS)
        {
            final int expectedStartTermOffset = (int)(startPosition & (termBufferLength - 1));
            if (expectedStartTermOffset != termOffset)
            {
                throw new IllegalStateException();
            }
        }
    }

    private void validateWritePreConditions(
        final int termId,
        final int termOffset,
        final int blockLength) throws IOException
    {
        if (POSITION_CHECKS)
        {
            if ((termOffset + blockLength) > termBufferLength)
            {
                throw new IllegalStateException("termOffset(=" + termOffset +
                    ") + blockLength(=" + blockLength +
                    ") > termBufferLength(=" + termBufferLength + ")");
            }

            if (recordingFileChannel.position() != segmentPosition)
            {
                throw new IllegalStateException("Expected recordingFileChannel.position(): " +
                    recordingFileChannel.position() + " to match segmentPosition: " + segmentPosition);
            }

            final int recordingOffset = recordingOffset(
                termOffset,
                termId,
                initialRecordingTermId,
                termsMask,
                termBufferLength);

            if (recordingOffset != segmentPosition)
            {
                throw new IllegalStateException("Expected recordingOffset:" +
                    recordingOffset + " to match segmentPosition: " + segmentPosition);
            }
        }
    }

    private void validateWritePostConditions() throws IOException
    {
        if (POSITION_CHECKS)
        {
            if (recordingFileChannel.position() != segmentPosition)
            {
                throw new IllegalStateException();
            }
        }
    }

    static class Context
    {
        FileChannel archiveDirChannel;
        File archiveDir;
        EpochClock epochClock;
        int fileSyncLevel = 0;
        int segmentFileLength = 1024 * 1024 * 1024;

        Context archiveDirChannel(final FileChannel archiveDirChannel)
        {
            this.archiveDirChannel = archiveDirChannel;
            return this;
        }

        Context archiveDir(final File archiveDir)
        {
            this.archiveDir = archiveDir;
            return this;
        }

        Context epochClock(final EpochClock epochClock)
        {
            this.epochClock = epochClock;
            return this;
        }

        Context fileSyncLevel(final int syncLevel)
        {
            this.fileSyncLevel = syncLevel;
            return this;
        }

        Context recordingFileLength(final int recordingFileLength)
        {
            this.segmentFileLength = recordingFileLength;
            return this;
        }

        int recordingFileLength()
        {
            return segmentFileLength;
        }
    }
}
