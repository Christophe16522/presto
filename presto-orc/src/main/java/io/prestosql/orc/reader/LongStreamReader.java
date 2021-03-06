/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.orc.reader;

import com.google.common.io.Closer;
import io.prestosql.memory.context.AggregatedMemoryContext;
import io.prestosql.orc.StreamDescriptor;
import io.prestosql.orc.metadata.ColumnEncoding;
import io.prestosql.orc.metadata.ColumnEncoding.ColumnEncodingKind;
import io.prestosql.orc.stream.InputStreamSources;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.type.Type;
import org.openjdk.jol.info.ClassLayout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.util.List;

import static com.google.common.base.MoreObjects.toStringHelper;
import static io.prestosql.orc.metadata.ColumnEncoding.ColumnEncodingKind.DICTIONARY;
import static io.prestosql.orc.metadata.ColumnEncoding.ColumnEncodingKind.DIRECT;
import static io.prestosql.orc.metadata.ColumnEncoding.ColumnEncodingKind.DIRECT_V2;
import static java.util.Objects.requireNonNull;

public class LongStreamReader
        implements StreamReader
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(LongStreamReader.class).instanceSize();

    private final StreamDescriptor streamDescriptor;
    private final LongDirectStreamReader directReader;
    private final LongDictionaryStreamReader dictionaryReader;
    private StreamReader currentReader;

    public LongStreamReader(StreamDescriptor streamDescriptor, AggregatedMemoryContext systemMemoryContext)
    {
        this.streamDescriptor = requireNonNull(streamDescriptor, "stream is null");
        directReader = new LongDirectStreamReader(streamDescriptor, systemMemoryContext.newLocalMemoryContext(LongStreamReader.class.getSimpleName()));
        dictionaryReader = new LongDictionaryStreamReader(streamDescriptor, systemMemoryContext.newLocalMemoryContext(LongStreamReader.class.getSimpleName()));
    }

    @Override
    public void prepareNextRead(int batchSize)
    {
        currentReader.prepareNextRead(batchSize);
    }

    @Override
    public Block readBlock(Type type)
            throws IOException
    {
        return currentReader.readBlock(type);
    }

    @Override
    public void startStripe(ZoneId timeZone, InputStreamSources dictionaryStreamSources, List<ColumnEncoding> encoding)
            throws IOException
    {
        ColumnEncodingKind kind = encoding.get(streamDescriptor.getStreamId()).getColumnEncodingKind();
        if (kind == DIRECT || kind == DIRECT_V2) {
            currentReader = directReader;
        }
        else if (kind == DICTIONARY) {
            currentReader = dictionaryReader;
        }
        else {
            throw new IllegalArgumentException("Unsupported encoding " + kind);
        }

        currentReader.startStripe(timeZone, dictionaryStreamSources, encoding);
    }

    @Override
    public void startRowGroup(InputStreamSources dataStreamSources)
            throws IOException
    {
        currentReader.startRowGroup(dataStreamSources);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .addValue(streamDescriptor)
                .toString();
    }

    @Override
    public void close()
    {
        try (Closer closer = Closer.create()) {
            closer.register(() -> directReader.close());
            closer.register(() -> dictionaryReader.close());
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        return INSTANCE_SIZE + directReader.getRetainedSizeInBytes() + dictionaryReader.getRetainedSizeInBytes();
    }
}
