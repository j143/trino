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
package io.trino.operator.window;

import com.google.common.collect.ImmutableList;
import io.trino.metadata.Signature;
import io.trino.operator.aggregation.Accumulator;
import io.trino.operator.aggregation.AccumulatorFactory;
import io.trino.operator.aggregation.InternalAggregationFunction;
import io.trino.operator.aggregation.LambdaProvider;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.function.WindowFunction;
import io.trino.spi.function.WindowIndex;

import java.util.List;
import java.util.Optional;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;

public class AggregateWindowFunction
        implements WindowFunction
{
    private final List<Integer> argumentChannels;
    private final AccumulatorFactory accumulatorFactory;
    private final boolean accumulatorHasRemoveInput;

    private WindowIndex windowIndex;
    private Accumulator accumulator;
    private int currentStart;
    private int currentEnd;

    private AggregateWindowFunction(InternalAggregationFunction function, List<Integer> argumentChannels, List<LambdaProvider> lambdaProviders)
    {
        this.argumentChannels = ImmutableList.copyOf(argumentChannels);
        this.accumulatorFactory = function.bind(
                argumentChannels,
                Optional.empty(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                null,
                false,
                null,
                null,
                lambdaProviders,
                false,
                null);

        this.accumulatorHasRemoveInput = accumulatorFactory.hasRemoveInput();
    }

    @Override
    public void reset(WindowIndex windowIndex)
    {
        this.windowIndex = windowIndex;
        resetAccumulator();
    }

    @Override
    public void processRow(BlockBuilder output, int peerGroupStart, int peerGroupEnd, int frameStart, int frameEnd)
    {
        if (frameStart < 0) {
            // empty frame
            resetAccumulator();
        }
        else if ((frameStart == currentStart) && (frameEnd >= currentEnd)) {
            // same or expanding frame
            accumulate(currentEnd + 1, frameEnd);
            currentEnd = frameEnd;
        }
        else {
            buildNewFrame(frameStart, frameEnd);
        }

        accumulator.evaluateFinal(output);
    }

    private void buildNewFrame(int frameStart, int frameEnd)
    {
        if (accumulatorHasRemoveInput) {
            // Note that all the start/end intervals are inclusive on both ends!
            if (currentStart < 0) {
                currentStart = 0;
                currentEnd = -1;
            }
            int overlapStart = max(frameStart, currentStart);
            int overlapEnd = min(frameEnd, currentEnd);
            int prefixRemoveLength = overlapStart - currentStart;
            int suffixRemoveLength = currentEnd - overlapEnd;

            if ((overlapEnd - overlapStart + 1) > (prefixRemoveLength + suffixRemoveLength)) {
                // It's worth keeping the overlap, and removing the now-unused prefix
                if (currentStart < frameStart) {
                    remove(currentStart, frameStart - 1);
                }
                if (frameEnd < currentEnd) {
                    remove(frameEnd + 1, currentEnd);
                }
                if (frameStart < currentStart) {
                    accumulate(frameStart, currentStart - 1);
                }
                if (currentEnd < frameEnd) {
                    accumulate(currentEnd + 1, frameEnd);
                }
                currentStart = frameStart;
                currentEnd = frameEnd;
                return;
            }
        }

        // We couldn't or didn't want to modify the accumulation: instead, discard the current accumulation and start fresh.
        resetAccumulator();
        accumulate(frameStart, frameEnd);
        currentStart = frameStart;
        currentEnd = frameEnd;
    }

    private void accumulate(int start, int end)
    {
        accumulator.addInput(windowIndex, argumentChannels, start, end);
    }

    private void remove(int start, int end)
    {
        accumulator.removeInput(windowIndex, argumentChannels, start, end);
    }

    private void resetAccumulator()
    {
        if (currentStart >= 0) {
            accumulator = accumulatorFactory.createAccumulator();
            currentStart = -1;
            currentEnd = -1;
        }
    }

    public static WindowFunctionSupplier supplier(Signature signature, InternalAggregationFunction function)
    {
        requireNonNull(function, "function is null");
        return new AbstractWindowFunctionSupplier(signature, null, function.getLambdaInterfaces())
        {
            @Override
            protected WindowFunction newWindowFunction(List<Integer> inputs, boolean ignoreNulls, List<LambdaProvider> lambdaProviders)
            {
                return new AggregateWindowFunction(function, inputs, lambdaProviders);
            }
        };
    }
}
