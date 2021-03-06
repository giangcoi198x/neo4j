/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.raft.log.segmented;

import java.io.File;
import java.io.IOException;

import org.neo4j.coreedge.raft.log.EntryRecord;
import org.neo4j.coreedge.raft.log.RaftLog;
import org.neo4j.coreedge.raft.log.RaftLogCursor;
import org.neo4j.coreedge.raft.log.RaftLogEntry;
import org.neo4j.coreedge.raft.replication.ReplicatedContent;
import org.neo4j.coreedge.raft.state.ChannelMarshal;
import org.neo4j.cursor.IOCursor;
import org.neo4j.helpers.collection.LruCache;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.LogProvider;

import static java.lang.String.format;

/**
 * The segmented RAFT log is an append only log supporting the operations required to support
 * the RAFT consensus algorithm.
 * <p>
 * A RAFT log must be able to append new entries, but also truncate not yet committed entries,
 * prune out old compacted entries and skip to a later starting point.
 * <p>
 * The RAFT log consists of a sequence of individual log files, called segments, with
 * the following format:
 * <p>
 * [HEADER] [ENTRY]*
 * <p>
 * So a header with zero or more entries following it. Each segment file contains a consecutive
 * sequence of appended entries. The operations of truncating and skipping in the log is implemented
 * by switching to the next segment file, called the next version. A new segment file is also started
 * when the threshold for a particular file has been reached.
 */
public class SegmentedRaftLog extends LifecycleAdapter implements RaftLog
{
    public static final String SEGMENTED_LOG_DIRECTORY_NAME = "raft-log";
    private final FileSystemAbstraction fileSystem;
    private final File directory;
    private final long rotateAtSize;
    private final ChannelMarshal<ReplicatedContent> contentMarshal;
    private final FileNames fileNames;

    private boolean needsRecovery;
    private final LogProvider logProvider;
    private final LruCache<Long,RaftLogEntry> entryCache; // TODO: replace with ring buffer, limit based on size
    private final SegmentedRaftLogPruner segmentedRaftLogPruner;

    private State state;

    public SegmentedRaftLog( FileSystemAbstraction fileSystem, File directory, long rotateAtSize,
            ChannelMarshal<ReplicatedContent> contentMarshal, LogProvider logProvider, int entryCacheSize,
            String pruningStrategyConfig )
    {
        this.fileSystem = fileSystem;
        this.directory = directory;
        this.rotateAtSize = rotateAtSize;
        this.contentMarshal = contentMarshal;

        this.fileNames = new FileNames( directory );
        this.logProvider = logProvider;
        this.entryCache = entryCacheSize >= 1 ? new LruCache<>( "raft-log-entry-cache", entryCacheSize ) : null;
        this.segmentedRaftLogPruner = new SegmentedRaftLogPruner( pruningStrategyConfig, logProvider );
    }

    @Override
    public synchronized void start() throws IOException, DamagedLogStorageException
    {
        if ( !directory.exists() && !directory.mkdirs() )
        {
            throw new IOException( "Could not create: " + directory );
        }

        RecoveryProtocol recoveryProtocol = new RecoveryProtocol( fileSystem, fileNames, contentMarshal, logProvider );
        state = recoveryProtocol.run();

    }

    @Override
    public void shutdown() throws DisposedException
    {
        state.segments.close();
    }

    @Override
    public synchronized long append( RaftLogEntry... entries ) throws IOException
    {
        ensureOk();

        try
        {
            for ( RaftLogEntry entry : entries )
            {
                state.appendIndex++;
                updateTerm( entry );
                state.segments.last().write( state.appendIndex, entry );
                cacheEntry( entry );
            }
            state.segments.last().flush();
        }
        catch ( Throwable e )
        {
            needsRecovery = true;
            throw e;
        }

        if ( state.segments.last().position() >= rotateAtSize )
        {
            rotateSegment( state.appendIndex, state.appendIndex, state.currentTerm );
        }

        return state.appendIndex;
    }

    private void ensureOk()
    {
        if ( needsRecovery )
        {
            throw new IllegalStateException( "Raft log requires recovery" );
        }
    }

    private void updateTerm( RaftLogEntry entry )
    {
        if ( entry.term() >= state.currentTerm )
        {
            state.currentTerm = entry.term();
        }
        else
        {
            throw new IllegalStateException(
                    format( "Non-monotonic term %d for entry %s in term %d", entry.term(), entry.toString(),
                            state.currentTerm ) );
        }
    }

    @Override
    public synchronized void truncate( long fromIndex ) throws IOException
    {
        if ( state.appendIndex < fromIndex )
        {
            throw new IllegalArgumentException( "Cannot truncate at index " + fromIndex + " when append index is " +
                    state.appendIndex );
        }

        invalidateCache();

        long newAppendIndex = fromIndex - 1;
        long newTerm = readEntryTerm( newAppendIndex );
        truncateSegment( state.appendIndex, newAppendIndex, newTerm );

        state.appendIndex = newAppendIndex;
        state.currentTerm = newTerm;
    }

    private void rotateSegment( long prevFileLastIndex, long prevIndex, long prevTerm ) throws IOException
    {
        state.segments.last().closeWriter();
        state.segments.rotate( prevFileLastIndex, prevIndex, prevTerm );
    }

    private void truncateSegment( long prevFileLastIndex, long prevIndex, long prevTerm ) throws IOException
    {
        state.segments.last().closeWriter();
        state.segments.truncate( prevFileLastIndex, prevIndex, prevTerm );
    }

    private void skipSegment( long prevFileLastIndex, long prevIndex, long prevTerm ) throws IOException
    {
        state.segments.last().closeWriter();
        state.segments.skip( prevFileLastIndex, prevIndex, prevTerm );
    }

    @Override
    public long appendIndex()
    {
        return state.appendIndex;
    }

    @Override
    public long prevIndex()
    {
        return state.prevIndex;
    }

    @Override
    public RaftLogCursor getEntryCursor( long fromIndex ) throws IOException
    {
        final IOCursor<EntryRecord> inner = new EntryCursor( state.segments, fromIndex );
        return new SegmentedRaftLogCursor( fromIndex, inner );
    }

    @Override
    public synchronized long skip( long newIndex, long newTerm ) throws IOException
    {
        if ( state.appendIndex < newIndex )
        {
            skipSegment( state.appendIndex, newIndex, newTerm );

            state.prevTerm = newTerm;
            state.prevIndex = newIndex;
            state.appendIndex = newIndex;
        }

        return state.appendIndex;
    }

    private RaftLogEntry readLogEntry( long logIndex ) throws IOException
    {
        RaftLogEntry entry = getFromCache( logIndex );
        if ( entry != null )
        {
            return entry;
        }

        try ( IOCursor<EntryRecord> cursor = new EntryCursor( state.segments, logIndex ) )
        {
            return cursor.next() ? cursor.get().logEntry() : null;
        }
    }

    @Override
    public long readEntryTerm( long logIndex ) throws IOException
    {
        if ( logIndex == state.prevIndex )
        {
            return state.prevTerm;
        }
        else if ( logIndex < state.prevIndex || logIndex > state.appendIndex )
        {
            return -1;
        }

        // Not found in cache but within our valid range, so we must read from the log.
        RaftLogEntry raftLogEntry = readLogEntry( logIndex );
        assert (raftLogEntry != null);
        return raftLogEntry.term();
    }

    @Override
    public long prune( long safeIndex ) throws IOException
    {
        long pruneIndex = segmentedRaftLogPruner.getIndexToPruneFrom( safeIndex, state.segments );
        SegmentFile oldestNotDisposed = state.segments.prune( pruneIndex );
        state.prevIndex = oldestNotDisposed.header().prevIndex();
        state.prevTerm = oldestNotDisposed.header().prevTerm();
        return state.prevIndex;
    }

    private void cacheEntry( RaftLogEntry entry )
    {
        if( entryCache != null )
        {
            entryCache.put( state.appendIndex, entry );
        }
    }

    private RaftLogEntry getFromCache( long logIndex )
    {
        return entryCache != null ? entryCache.get( logIndex ) : null;
    }

    private void invalidateCache()
    {
        if ( entryCache != null )
        {
            entryCache.clear();
        }
    }
}
